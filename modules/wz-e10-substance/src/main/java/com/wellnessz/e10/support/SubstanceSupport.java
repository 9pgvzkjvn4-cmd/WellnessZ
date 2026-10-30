package com.wellnessz.e10.support;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.wellnessz.casekernel.support.EmployeeRecordAccess;
import com.wellnessz.common.error.ApiException;
import com.wellnessz.common.model.DataClassification;
import com.wellnessz.common.model.DisclosureLevel;
import com.wellnessz.common.model.Roles;
import com.wellnessz.masterdata.ConfigService;
import com.wellnessz.masterdata.Employee;
import com.wellnessz.masterdata.EmployeeRepository;
import com.wellnessz.masterdata.OrgUnit;
import com.wellnessz.masterdata.OrgUnitRepository;
import com.wellnessz.masterdata.StaffMember;
import com.wellnessz.masterdata.StaffMemberRepository;
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
 * Shared E10 helpers: time and configuration, reference numbers, people lookups, results access (MRO / designated OH
 * physician), laboratory client binding, status-only scope for HR / crew scheduling, JSON and content-free notifications.
 */
@Component("e10SubstanceSupport")
public class SubstanceSupport {

    public static final String LAB_CODE_KEY = "substance.laboratory-code";
    public static final String ACTING_MRO_KEY = "substance.acting-mro-physicians";
    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {
    };

    private final EmployeeRepository employees;
    private final OrgUnitRepository orgUnits;
    private final StaffMemberRepository staff;
    private final EmployeeRecordAccess records;
    private final ConfigService config;
    private final NotificationService notifications;
    private final AuditService audit;
    private final JdbcTemplate jdbc;
    private final JsonMapper json;
    private final Clock clock;
    private final String ohPortal;
    private final String employeePortal;
    private final String managerPortal;
    private final int minGroupSize;

    public SubstanceSupport(EmployeeRepository employees, OrgUnitRepository orgUnits, StaffMemberRepository staff, EmployeeRecordAccess records,
                            ConfigService config, NotificationService notifications, AuditService audit, JdbcTemplate jdbc, JsonMapper json,
                            Clock clock,
                            @Value("${wellnessz.notification.oh-workspace-url:https://oh.wellnessz.local}") String ohPortal,
                            @Value("${wellnessz.notification.employee-portal-url:https://employee.wellnessz.local}") String employeePortal,
                            @Value("${wellnessz.notification.manager-workspace-url:https://manager.wellnessz.local}") String managerPortal,
                            @Value("${wellnessz.analytics.min-group-size:5}") int minGroupSize) {
        this.employees = employees;
        this.orgUnits = orgUnits;
        this.staff = staff;
        this.records = records;
        this.config = config;
        this.notifications = notifications;
        this.audit = audit;
        this.jdbc = jdbc;
        this.json = json;
        this.clock = clock;
        this.ohPortal = ohPortal;
        this.employeePortal = employeePortal;
        this.managerPortal = managerPortal;
        this.minGroupSize = minGroupSize;
    }

    // ------------------------------------------------------------------ time & config

    public Clock clock() {
        return clock;
    }

    public Instant now() {
        return clock.instant();
    }

    public LocalDate today() {
        return LocalDate.now(clock.withZone(ZoneOffset.UTC));
    }

    public int minGroupSize() {
        return minGroupSize;
    }

    public Optional<String> config(String key) {
        return config.get(key).map(String::trim).filter(v -> !v.isEmpty());
    }

    public String config(String key, String defaultValue) {
        return config(key).orElse(defaultValue);
    }

    public String laboratoryCode() {
        return config(LAB_CODE_KEY, "TOXLAB");
    }

    public String defaultSite() {
        return config("substance.collection-site", "LHR-OH-CLINIC");
    }

    // ------------------------------------------------------------------ reference numbers

    /** {@code PREFIX-YYYY-NNNNNN} with a gap-tolerant per-prefix, per-year counter. */
    public String nextRef(String prefix) {
        int year = today().getYear();
        Long n = jdbc.queryForObject("""
                insert into substance.ref_counter (prefix, seq_year, last_value) values (?, ?, 1)
                on conflict (prefix, seq_year) do update set last_value = substance.ref_counter.last_value + 1
                returning last_value""", Long.class, prefix, year);
        return String.format(Locale.ROOT, "%s-%d-%06d", prefix, year, n);
    }

    // ------------------------------------------------------------------ JSON

    public String json(Object value) {
        return value == null ? null : json.writeValueAsString(value);
    }

    public <T> T read(String text, TypeReference<T> type) {
        return json.readValue(text, type);
    }

    public List<String> readIds(String text) {
        return text == null || text.isBlank() ? List.of() : json.readValue(text, STRING_LIST);
    }

    public static List<String> split(String csv) {
        return csv == null || csv.isBlank() ? List.of() : Arrays.stream(csv.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    }

    public static ApiException unprocessable(String code, String message) {
        return new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, code, message);
    }

    // ------------------------------------------------------------------ people

    public Optional<Employee> employee(String employeeId) {
        return employeeId == null ? Optional.empty() : employees.findById(employeeId);
    }

    public String name(String employeeId) {
        return employee(employeeId).map(Employee::getDisplayName).orElse(employeeId);
    }

    public String unit(String employeeId) {
        return employee(employeeId).map(Employee::getDepartmentCode)
                .map(d -> orgUnits.findById(d).map(OrgUnit::getName).orElse(d)).orElse("–");
    }

    public List<Employee> allEmployees() {
        return employees.findAll();
    }

    public String firstName(String employeeId) {
        String n = name(employeeId);
        int i = n.indexOf(' ');
        return i > 0 ? n.substring(0, i) : n;
    }

    // ------------------------------------------------------------------ results access

    /**
     * Results (laboratory values, breath readings, verdicts, interview notes) are visible to the MRO only – and to an OH
     * physician designated in {@code substance.acting-mro-physicians} acting as MRO.
     */
    public boolean actsAsMro(CurrentUser user) {
        if (user.hasRole(Roles.MRO)) {
            return true;
        }
        return user.hasRole(Roles.OH_PHYSICIAN) && split(config(ACTING_MRO_KEY, "")).contains(user.username());
    }

    public void requireMro(CurrentUser user, String action) {
        if (!actsAsMro(user)) {
            audit.record(action, "DA_TEST", null, AuditService.Outcome.DENIED, "Not the MRO or a designated physician");
            throw ApiException.forbidden("Results are restricted to the medical review officer");
        }
    }

    // ------------------------------------------------------------------ laboratory binding

    /**
     * The laboratory machine client must carry {@code org_scope /PARTNERS/<laboratory-code>}; unbound clients and clients of
     * other organisations are told "not found" (and audited) so that nothing about the programme is revealed.
     */
    public String requireLaboratory(CurrentUser client, String action, String resourceId) {
        String org = client.partnerOrganisation();
        String lab = laboratoryCode();
        if (org == null || !org.equals(lab)) {
            audit.recordAs(client.username(), "INTEGRATION", action, "LAB_INTERFACE", resourceId, AuditService.Outcome.DENIED,
                    "client organisation " + org + " is not the programme laboratory");
            throw ApiException.notFound("Laboratory order");
        }
        return org;
    }

    // ------------------------------------------------------------------ status-only scope

    /**
     * HR and managers: employees inside their org scope (ABAC status-only rule). Crew scheduling: employees whose org path is
     * inside the scheduler's org scope (operations).
     */
    public boolean inStatusScope(CurrentUser user, Employee e) {
        if (e == null) {
            return false;
        }
        if (user.hasAnyRole(Roles.HR, Roles.MANAGER)) {
            AccessDecision d = records.decide(user, AccessPolicy.Action.READ, "EMPLOYEE_RECORD", e, DataClassification.CONFIDENTIAL);
            if (d.permitted() && d.disclosure().atLeast(DisclosureLevel.STATUS_ONLY)) {
                return true;
            }
        }
        if (user.hasRole(Roles.CREW_SCHEDULING) && user.orgScope() != null) {
            String path = orgUnits.findById(e.getDepartmentCode()).map(OrgUnit::getPath).orElse(null);
            String scope = user.orgScope();
            return path != null && (path.equals(scope) || path.startsWith(scope.endsWith("/") ? scope : scope + "/"));
        }
        return false;
    }

    /** True when the same status has already been disclosed to this recipient (repeated reads are not logged again). */
    public boolean alreadyDisclosed(String resourceType, String resourceId, String recipientName, String title) {
        Boolean b = jdbc.queryForObject("""
                select exists (select 1 from platform.disclosure_log where resource_type = ? and resource_id = ? and recipient_name = ?
                                  and resource_title = ? and revoked_at is null)""", Boolean.class, resourceType, resourceId, recipientName, title);
        return Boolean.TRUE.equals(b);
    }

    // ------------------------------------------------------------------ content-free notifications

    public List<StaffMember> staffWithRole(String role) {
        return staff.findByActiveTrueOrderByDisplayName().stream().filter(s -> s.hasRole(role)).toList();
    }

    public void notifyRole(String role, String template, Map<String, String> params, String route, Priority priority) {
        staffWithRole(role).stream().filter(s -> s.getEmail() != null).forEach(s -> notifications.enqueue(s.getUsername(), s.getEmail(),
                Channel.EMAIL, priority, template, link(params, ohPortal, route), ZoneOffset.UTC));
    }

    public void notifyEmployee(String employeeId, String template, Map<String, String> params, Priority priority) {
        employee(employeeId).filter(e -> e.getEmail() != null).ifPresent(e -> notifications.enqueue(e.getEmployeeId(), e.getEmail(),
                Channel.EMAIL, priority, template, link(params, employeePortal, "testing"), ZoneOffset.UTC));
    }

    /** A configured mailbox (HR status mailbox, crew scheduling distribution list). */
    public boolean notifyMailbox(String configKey, String recipientId, String template, Map<String, String> params) {
        Optional<String> address = config(configKey);
        address.ifPresent(a -> notifications.enqueue(recipientId, a, Channel.EMAIL, Priority.URGENT, template,
                link(params, managerPortal, "testing"), ZoneOffset.UTC));
        return address.isPresent();
    }

    private static Map<String, String> link(Map<String, String> params, String portal, String route) {
        Map<String, String> out = new LinkedHashMap<>(params);
        out.putIfAbsent("portalLink", portal + "/" + route);
        return out;
    }
}
