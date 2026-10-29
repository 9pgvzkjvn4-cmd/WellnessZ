package com.wellnessz.e06.support;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.wellnessz.casekernel.support.EmployeeRecordAccess;
import com.wellnessz.common.error.ApiException;
import com.wellnessz.common.model.DataClassification;
import com.wellnessz.common.model.DisclosureLevel;
import com.wellnessz.common.model.Roles;
import com.wellnessz.e05.support.FitnessSupport;
import com.wellnessz.masterdata.ClinicSite;
import com.wellnessz.masterdata.ClinicSiteRepository;
import com.wellnessz.masterdata.ConfigService;
import com.wellnessz.masterdata.Employee;
import com.wellnessz.masterdata.EmployeeRepository;
import com.wellnessz.masterdata.JobCode;
import com.wellnessz.masterdata.JobCodeRepository;
import com.wellnessz.masterdata.OrgUnit;
import com.wellnessz.masterdata.OrgUnitRepository;
import com.wellnessz.notification.Channel;
import com.wellnessz.notification.NotificationService;
import com.wellnessz.notification.Priority;
import com.wellnessz.security.abac.AccessDecision;
import com.wellnessz.security.abac.AccessPolicy;
import com.wellnessz.security.audit.AuditService;
import com.wellnessz.security.identity.CurrentUser;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Shared E06 helpers: configuration, manager / HR scope checks (status-only access, denied → audited 404), employees in
 * scope, content-free notifications (delegating to the E05 helpers) and the HR absence mailbox, home clinic sites.
 */
@Component
public class AbsenceSupport {

    public static final String HR_EMAIL_KEY = "absence.hr-email";
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {
    };
    private static final TypeReference<Map<String, String>> STRING_MAP = new TypeReference<>() {
    };

    private final FitnessSupport fitness;
    private final EmployeeRepository employees;
    private final JobCodeRepository jobCodes;
    private final OrgUnitRepository orgUnits;
    private final ClinicSiteRepository sites;
    private final EmployeeRecordAccess records;
    private final ConfigService config;
    private final NotificationService notifications;
    private final AuditService audit;
    private final JdbcTemplate jdbc;
    private final JsonMapper json;
    private final String managerPortal;
    private final String employeePortal;
    private final String ohPortal;

    public AbsenceSupport(FitnessSupport fitness, EmployeeRepository employees, JobCodeRepository jobCodes, OrgUnitRepository orgUnits,
                          ClinicSiteRepository sites, EmployeeRecordAccess records, ConfigService config, NotificationService notifications,
                          AuditService audit, JdbcTemplate jdbc, JsonMapper json,
                          @Value("${wellnessz.notification.manager-workspace-url:https://manager.wellnessz.local}") String managerPortal,
                          @Value("${wellnessz.notification.employee-portal-url:https://employee.wellnessz.local}") String employeePortal,
                          @Value("${wellnessz.notification.oh-workspace-url:https://oh.wellnessz.local}") String ohPortal) {
        this.fitness = fitness;
        this.employees = employees;
        this.jobCodes = jobCodes;
        this.orgUnits = orgUnits;
        this.sites = sites;
        this.records = records;
        this.config = config;
        this.notifications = notifications;
        this.audit = audit;
        this.jdbc = jdbc;
        this.json = json;
        this.managerPortal = managerPortal;
        this.employeePortal = employeePortal;
        this.ohPortal = ohPortal;
    }

    // ------------------------------------------------------------------ time & config

    public Clock clock() {
        return fitness.clock();
    }

    public Instant now() {
        return fitness.clock().instant();
    }

    public LocalDate today() {
        return LocalDate.now(fitness.clock().withZone(ZoneOffset.UTC));
    }

    public boolean flag(String key, boolean defaultValue) {
        return config.flag(key, defaultValue);
    }

    public int intConfig(String key, int defaultValue) {
        return config.get(key).map(v -> {
            try {
                return Integer.parseInt(v.trim());
            } catch (NumberFormatException e) {
                return defaultValue;
            }
        }).orElse(defaultValue);
    }

    public Set<LocalDate> publicHolidays() {
        Set<LocalDate> out = new HashSet<>();
        config.get("absence.public-holidays").ifPresent(v -> {
            for (String s : v.split(",")) {
                try {
                    out.add(LocalDate.parse(s.trim()));
                } catch (RuntimeException e) {
                    // ignore malformed entries
                }
            }
        });
        return out;
    }

    /** Adds {@code days} working days (Monday–Friday) to {@code from}. */
    public static LocalDate plusWorkingDays(LocalDate from, int days) {
        LocalDate d = from;
        int added = 0;
        while (added < days) {
            d = d.plusDays(1);
            if (d.getDayOfWeek() != DayOfWeek.SATURDAY && d.getDayOfWeek() != DayOfWeek.SUNDAY) {
                added++;
            }
        }
        return d;
    }

    // ------------------------------------------------------------------ JSON

    public String json(Object value) {
        return fitness.writeJson(value);
    }

    public Map<String, Object> readMap(String text) {
        return text == null || text.isBlank() ? new LinkedHashMap<>() : fitness.readJson(text, MAP);
    }

    public <T> T fromJson(String text, Class<T> type) {
        return json.readValue(text, type);
    }

    public Map<String, String> readStringMap(String text) {
        return text == null || text.isBlank() ? new LinkedHashMap<>() : fitness.readJson(text, STRING_MAP);
    }

    // ------------------------------------------------------------------ people

    public Optional<Employee> employee(String employeeId) {
        return employeeId == null ? Optional.empty() : employees.findById(employeeId);
    }

    public String name(String employeeId) {
        return fitness.employeeName(employeeId);
    }

    public String staffName(String username) {
        return fitness.staffName(username);
    }

    /** Organisational unit label shown to managers / HR (department name), never anything clinical. */
    public String unit(Employee e) {
        if (e == null || e.getDepartmentCode() == null) {
            return "–";
        }
        return orgUnits.findById(e.getDepartmentCode()).map(OrgUnit::getName).orElse(e.getDepartmentCode());
    }

    public String unit(String employeeId) {
        return unit(employee(employeeId).orElse(null));
    }

    public Optional<JobCode> jobCode(Employee e) {
        return e == null || e.getJobCode() == null ? Optional.empty() : jobCodes.findById(e.getJobCode());
    }

    /** Resolves a feed / partner employee reference: employee id first, then badge id. */
    public Optional<Employee> reconcile(String employeeRef) {
        if (employeeRef == null || employeeRef.isBlank()) {
            return Optional.empty();
        }
        return employees.findById(employeeRef).or(() -> employees.findByBadgeIdAndActiveTrue(employeeRef));
    }

    public static boolean isOh(CurrentUser user) {
        return user.hasAnyRole(Roles.OH_TEAM);
    }

    /** Status-only scope: the employee's line manager, or a manager / HR user whose org scope covers the employee. */
    public boolean inScope(CurrentUser user, Employee e) {
        if (e == null) {
            return false;
        }
        if (user.hasRole(Roles.MANAGER) && user.employeeId() != null && user.employeeId().equals(e.getManagerEmployeeId())) {
            return true;
        }
        if (!user.hasAnyRole(Roles.MANAGER, Roles.HR)) {
            return false;
        }
        AccessDecision d = records.decide(user, AccessPolicy.Action.READ, "EMPLOYEE_RECORD", e, DataClassification.CONFIDENTIAL);
        return d.permitted() && d.disclosure().atLeast(DisclosureLevel.STATUS_ONLY);
    }

    /** Manager / HR in scope, otherwise an audited 404. */
    public Employee requireInScope(CurrentUser user, String employeeId, String auditAction) {
        Employee e = employee(employeeId).orElse(null);
        if (e == null || !inScope(user, e)) {
            audit.record(auditAction, "EMPLOYEE", employeeId, AuditService.Outcome.DENIED, "Outside scope");
            throw ApiException.notFound("Employee");
        }
        return e;
    }

    /** OH clinical access to the employee's record (audited 404 otherwise). */
    public Employee requireClinical(CurrentUser user, String employeeId, String auditAction) {
        return records.requireClinical(user, employeeId, DataClassification.CLINICAL, AccessPolicy.Action.READ, auditAction);
    }

    public Employee requireClinicalUpdate(CurrentUser user, String employeeId, String auditAction) {
        return records.requireClinical(user, employeeId, DataClassification.CLINICAL, AccessPolicy.Action.UPDATE, auditAction);
    }

    public boolean canSeeClinical(CurrentUser user, Employee e) {
        return e != null && records.canSeeClinical(user, e, DataClassification.CLINICAL);
    }

    /** Active employees a manager / HR user may see (status only): direct reports plus the org scope. */
    public List<Employee> employeesInScope(CurrentUser user) {
        Set<String> ids = new LinkedHashSet<>();
        if (user.hasRole(Roles.MANAGER) && user.employeeId() != null) {
            ids.addAll(jdbc.queryForList("select employee_id from masterdata.employee where manager_employee_id = ? and active order by display_name",
                    String.class, user.employeeId()));
        }
        if (user.orgScope() != null && user.hasAnyRole(Roles.MANAGER, Roles.HR)) {
            ids.addAll(jdbc.queryForList("""
                    select e.employee_id from masterdata.employee e join masterdata.org_unit o on o.code = e.department_code
                     where e.active and (o.path = ? or o.path like ? || '/%') order by e.display_name""",
                    String.class, user.orgScope(), user.orgScope()));
        }
        List<Employee> out = new ArrayList<>();
        for (Employee e : employees.findAllById(ids)) {
            if (inScope(user, e)) {
                out.add(e);
            }
        }
        out.sort(java.util.Comparator.comparing(Employee::getDisplayName, java.util.Comparator.nullsLast(String::compareTo)));
        return out;
    }

    public List<Employee> allActiveEmployees() {
        return employees.findAll().stream().filter(Employee::isActive).toList();
    }

    public List<Employee> employeesById(Collection<String> ids) {
        return employees.findAllById(ids);
    }

    /** Home clinic site: the first active site at the employee's location. */
    public Optional<String> homeSite(Employee e) {
        if (e == null || e.getLocationCode() == null) {
            return Optional.empty();
        }
        return sites.findByLocationCodeAndActiveTrueOrderByCode(e.getLocationCode()).stream().findFirst().map(ClinicSite::getCode);
    }

    // ------------------------------------------------------------------ content-free notifications

    public void notifyEmployee(String employeeId, String template, Map<String, String> params) {
        fitness.notifyEmployee(employeeId, template, link(params, employeePortal, "#/absence"));
    }

    public void notifyEmployee(String employeeId, String template, Map<String, String> params, String route) {
        fitness.notifyEmployee(employeeId, template, link(params, employeePortal, route));
    }

    public void notifyManagerOf(String employeeId, String template, Map<String, String> params, String route) {
        fitness.notifyManagerOf(employeeId, template, link(params, managerPortal, route));
    }

    public void notifyStaff(String username, String template, Map<String, String> params, String route) {
        fitness.notifyStaff(username, template, link(params, ohPortal, route));
    }

    public void notifyRole(String role, String template, Map<String, String> params, String route) {
        fitness.notifyRole(role, template, link(params, ohPortal, route));
    }

    /** The HR absence mailbox ({@code absence.hr-email}), status-only templates. */
    public void notifyHr(String template, Map<String, String> params, String route) {
        config.get(HR_EMAIL_KEY).filter(a -> !a.isBlank()).ifPresent(address -> notifications.enqueue("hr-absence", address.trim(),
                Channel.EMAIL, Priority.NORMAL, template, link(params, managerPortal, route), ZoneOffset.UTC));
    }

    /** A specific workspace user (manager requester) by employee id, or the HR mailbox when they have no HRIS e-mail. */
    public void notifyRequester(String requesterEmployeeId, String template, Map<String, String> params, String route) {
        Optional<Employee> m = employee(requesterEmployeeId).filter(x -> x.getEmail() != null);
        if (m.isPresent()) {
            Employee e = m.get();
            Map<String, String> p = link(params, managerPortal, route);
            notifications.enqueue(e.getEmployeeId(), e.getEmail(), Channel.EMAIL, Priority.NORMAL, template, p, ZoneOffset.UTC);
        } else {
            notifyHr(template, params, route);
        }
    }

    private static Map<String, String> link(Map<String, String> params, String portal, String route) {
        Map<String, String> out = new LinkedHashMap<>(params);
        out.putIfAbsent("portalLink", portal + "/" + route);
        return out;
    }
}
