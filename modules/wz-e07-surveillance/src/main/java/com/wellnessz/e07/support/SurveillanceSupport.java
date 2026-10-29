package com.wellnessz.e07.support;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.wellnessz.common.error.ApiException;
import com.wellnessz.common.model.Roles;
import com.wellnessz.common.text.ClinicalContentException;
import com.wellnessz.masterdata.ClinicSite;
import com.wellnessz.masterdata.ClinicSiteRepository;
import com.wellnessz.masterdata.ConfigService;
import com.wellnessz.masterdata.Employee;
import com.wellnessz.masterdata.EmployeeRepository;
import com.wellnessz.masterdata.JobCode;
import com.wellnessz.masterdata.JobCodeRepository;
import com.wellnessz.masterdata.OrgUnit;
import com.wellnessz.masterdata.OrgUnitRepository;
import com.wellnessz.masterdata.StaffMember;
import com.wellnessz.masterdata.StaffMemberRepository;
import com.wellnessz.notification.Channel;
import com.wellnessz.notification.NotificationService;
import com.wellnessz.notification.Priority;
import com.wellnessz.security.identity.CurrentUser;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Shared E07 helpers: clock, configuration values, HRIS / job-code / site lookups, JSON, text validation and the
 * content-free notifications (approved templates only – never a programme outcome, result or exposure value).
 */
@Component
public class SurveillanceSupport {

    private static final ZoneId UTC = ZoneOffset.UTC;
    public static final Set<String> CREW_CATEGORIES = Set.of("FLIGHT_CREW", "CABIN_CREW");

    private final Clock clock;
    private final ConfigService config;
    private final EmployeeRepository employees;
    private final JobCodeRepository jobCodes;
    private final OrgUnitRepository orgUnits;
    private final ClinicSiteRepository sites;
    private final StaffMemberRepository staff;
    private final NotificationService notifications;
    private final JsonMapper json;
    private final String employeePortal;
    private final String managerPortal;
    private final String ohPortal;
    private final int minGroupSize;

    public SurveillanceSupport(Clock clock, ConfigService config, EmployeeRepository employees, JobCodeRepository jobCodes,
                               OrgUnitRepository orgUnits, ClinicSiteRepository sites, StaffMemberRepository staff,
                               NotificationService notifications, JsonMapper json,
                               @Value("${wellnessz.notification.employee-portal-url:https://employee.wellnessz.local}") String employeePortal,
                               @Value("${wellnessz.notification.manager-workspace-url:https://manager.wellnessz.local}") String managerPortal,
                               @Value("${wellnessz.notification.oh-workspace-url:https://oh.wellnessz.local}") String ohPortal,
                               @Value("${wellnessz.analytics.min-group-size:5}") int minGroupSize) {
        this.clock = clock;
        this.config = config;
        this.employees = employees;
        this.jobCodes = jobCodes;
        this.orgUnits = orgUnits;
        this.sites = sites;
        this.staff = staff;
        this.notifications = notifications;
        this.json = json;
        this.employeePortal = employeePortal;
        this.managerPortal = managerPortal;
        this.ohPortal = ohPortal;
        this.minGroupSize = minGroupSize;
    }

    // ------------------------------------------------------------------ time & configuration

    public Clock clock() {
        return clock;
    }

    public LocalDate today() {
        return LocalDate.now(clock.withZone(UTC));
    }

    public java.time.Instant now() {
        return clock.instant();
    }

    public int minGroupSize() {
        return minGroupSize;
    }

    public int configInt(String key, int fallback) {
        try {
            return config.get(key).map(String::trim).map(Integer::parseInt).orElse(fallback);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    public BigDecimal configDecimal(String key, BigDecimal fallback) {
        try {
            return config.get(key).map(String::trim).map(BigDecimal::new).orElse(fallback);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    public Optional<String> configString(String key) {
        return config.get(key).map(String::trim).filter(s -> !s.isEmpty());
    }

    // ------------------------------------------------------------------ people & master data

    public Optional<Employee> employee(String employeeId) {
        return employeeId == null ? Optional.empty() : employees.findById(employeeId);
    }

    public Employee requireEmployee(String employeeId) {
        return employee(employeeId).orElseThrow(() -> ApiException.notFound("Employee"));
    }

    public Optional<Employee> byBadge(String badgeId) {
        return badgeId == null ? Optional.empty() : employees.findByBadgeIdAndActiveTrue(badgeId);
    }

    public List<Employee> activeEmployees() {
        return employees.findAll().stream().filter(Employee::isActive).toList();
    }

    public Map<String, Employee> employeesById(java.util.Collection<String> ids) {
        Map<String, Employee> out = new LinkedHashMap<>();
        employees.findAllById(ids).forEach(e -> out.put(e.getEmployeeId(), e));
        return out;
    }

    public String displayName(String employeeId) {
        return employee(employeeId).map(Employee::getDisplayName).orElse(employeeId);
    }

    public Map<String, JobCode> jobCodes() {
        return jobCodes.findAll().stream().collect(Collectors.toMap(JobCode::getCode, j -> j, (a, b) -> a, LinkedHashMap::new));
    }

    public Optional<JobCode> jobCode(String code) {
        return code == null ? Optional.empty() : jobCodes.findById(code);
    }

    public static List<String> csv(String value) {
        return value == null || value.isBlank() ? List.of()
                : Arrays.stream(value.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    }

    public static String joinCsv(java.util.Collection<String> values) {
        return values == null || values.isEmpty() ? null : String.join(",", values);
    }

    public boolean isCrew(Employee e) {
        return e != null && jobCode(e.getJobCode()).map(JobCode::getSafetyCategory).map(CREW_CATEGORIES::contains).orElse(false);
    }

    public boolean isSafetyCritical(Employee e) {
        return e != null && jobCode(e.getJobCode()).map(JobCode::isSafetyCritical).orElse(false);
    }

    public boolean isFoodHandler(Employee e) {
        return e != null && jobCode(e.getJobCode()).map(j -> "FOOD_HANDLER".equals(j.getSafetyCategory())).orElse(false);
    }

    /** Org path of a department, used for manager / HR scope checks. */
    public String orgPath(String departmentCode) {
        return departmentCode == null ? null : orgUnits.findById(departmentCode).map(OrgUnit::getPath).orElse(null);
    }

    public static boolean inScope(String scope, String path) {
        return scope != null && path != null && (path.equals(scope) || path.startsWith(scope + "/"));
    }

    /** Status-only team scope: employees in the caller's org scope, plus a manager's direct reports. */
    public boolean inTeam(CurrentUser user, Employee e) {
        if (user.hasRole(Roles.MANAGER) && user.employeeId() != null && user.employeeId().equals(e.getManagerEmployeeId())) {
            return true;
        }
        return inScope(user.orgScope(), orgPath(e.getDepartmentCode()));
    }

    /** Home clinic site: first active site at the employee's location (contract §2.2). */
    public String homeSite(String locationCode) {
        if (locationCode == null) {
            return null;
        }
        return sites.findByLocationCodeAndActiveTrueOrderByCode(locationCode).stream()
                .filter(s -> !"MOBILE".equals(s.getSiteType()))
                .map(ClinicSite::getCode).findFirst()
                .orElseGet(() -> sites.findByLocationCodeAndActiveTrueOrderByCode(locationCode).stream().map(ClinicSite::getCode)
                        .findFirst().orElse(null));
    }

    public boolean siteExists(String siteCode) {
        return siteCode != null && sites.findById(siteCode).filter(ClinicSite::isActive).isPresent();
    }

    public String staffName(String username) {
        return username == null ? null : staff.findById(username).map(StaffMember::getDisplayName).orElse(username);
    }

    public List<StaffMember> staffWithRole(String role) {
        return staff.findByActiveTrueOrderByDisplayName().stream().filter(s -> s.hasRole(role)).toList();
    }

    // ------------------------------------------------------------------ JSON

    public String write(Object value) {
        return value == null ? null : json.writeValueAsString(value);
    }

    public <T> T read(String text, TypeReference<T> type) {
        return json.readValue(text, type);
    }

    public Map<String, Object> readMap(String text) {
        if (text == null || text.isBlank()) {
            return new LinkedHashMap<>();
        }
        return json.readValue(text, new TypeReference<LinkedHashMap<String, Object>>() {
        });
    }

    // ------------------------------------------------------------------ text

    /** Text shown to non-OH readers: length-limited and screened for clinical content (422). */
    public static String functional(String field, String value, int max) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String v = value.strip();
        if (v.length() > max) {
            throw ApiException.badRequest("too_long", "Field '" + field + "' exceeds " + max + " characters");
        }
        ClinicalContentException.screen(field, v);
        return v;
    }

    /** OH-only text: length-limited (stored encrypted by the entity converter). */
    public static String clinical(String field, String value, int max) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String v = value.strip();
        if (v.length() > max) {
            throw ApiException.badRequest("too_long", "Field '" + field + "' exceeds " + max + " characters");
        }
        return v;
    }

    // ------------------------------------------------------------------ content-free notifications

    public void notifyEmployee(String employeeId, String template, Map<String, String> params) {
        employee(employeeId).filter(e -> e.getEmail() != null).ifPresent(e -> notifications.enqueue(e.getEmployeeId(), e.getEmail(),
                Channel.EMAIL, Priority.NORMAL, template, withDefaults(params, firstName(e.getDisplayName()), employeePortal + "/#/surveillance"),
                UTC));
    }

    public void notifyAddress(String recipientId, String address, String firstName, String template, Map<String, String> params,
                              String portalLink) {
        if (address == null || address.isBlank()) {
            return;
        }
        notifications.enqueue(recipientId, address, Channel.EMAIL, Priority.NORMAL, template,
                withDefaults(params, firstName, portalLink == null ? employeePortal : portalLink), UTC);
    }

    public void notifyManagerOf(String employeeId, String template, Map<String, String> params) {
        employee(employeeId).map(Employee::getManagerEmployeeId).flatMap(this::employee).filter(m -> m.getEmail() != null)
                .ifPresent(m -> notifications.enqueue(m.getEmployeeId(), m.getEmail(), Channel.EMAIL, Priority.NORMAL, template,
                        withDefaults(params, firstName(m.getDisplayName()), managerPortal + "/#/compliance"), UTC));
    }

    public void notifyStaff(StaffMember s, String template, Map<String, String> params, String ohPath) {
        if (s.getEmail() != null) {
            notifications.enqueue(s.getUsername(), s.getEmail(), Channel.EMAIL, Priority.NORMAL, template,
                    withDefaults(params, null, ohPortal + (ohPath == null ? "" : ohPath)), UTC);
        }
    }

    public void notifyRole(String role, String template, Map<String, String> params, String ohPath) {
        staffWithRole(role).forEach(s -> notifyStaff(s, template, params, ohPath));
    }

    public void notifyConfigAddress(String configKey, String template, Map<String, String> params, String portal) {
        configString(configKey).ifPresent(address -> notifications.enqueue(configKey, address, Channel.EMAIL, Priority.NORMAL, template,
                withDefaults(params, null, portal == null ? ohPortal : portal), UTC));
    }

    public String employeePortal() {
        return employeePortal;
    }

    public String managerPortal() {
        return managerPortal;
    }

    public String ohPortal() {
        return ohPortal;
    }

    public static String firstName(String displayName) {
        return displayName == null || displayName.isBlank() ? "colleague" : displayName.trim().split(" ")[0];
    }

    private static Map<String, String> withDefaults(Map<String, String> params, String firstName, String portal) {
        Map<String, String> out = new LinkedHashMap<>(params);
        if (firstName != null) {
            out.putIfAbsent("firstName", firstName);
        }
        out.putIfAbsent("portalLink", portal);
        return out;
    }
}
