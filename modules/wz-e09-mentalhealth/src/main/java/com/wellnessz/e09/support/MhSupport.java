package com.wellnessz.e09.support;

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

import com.wellnessz.common.error.ApiException;
import com.wellnessz.common.model.Roles;
import com.wellnessz.common.text.ClinicalContentException;
import com.wellnessz.masterdata.ConfigService;
import com.wellnessz.masterdata.Employee;
import com.wellnessz.masterdata.EmployeeRepository;
import com.wellnessz.masterdata.OrgUnit;
import com.wellnessz.masterdata.OrgUnitRepository;
import com.wellnessz.masterdata.PartnerOrganisation;
import com.wellnessz.masterdata.PartnerOrganisationRepository;
import com.wellnessz.masterdata.StaffMember;
import com.wellnessz.masterdata.StaffMemberRepository;
import com.wellnessz.notification.Channel;
import com.wellnessz.notification.NotificationService;
import com.wellnessz.notification.Priority;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Shared E09 helpers: time and configuration, reference numbers, people / unit / partner lookups, the named-clinician list,
 * text screening and content-free notifications (approved {@code support.*} / {@code cism.*} templates only).
 */
@Component
public class MhSupport {

    public static final String NAMED_CLINICIANS_KEY = "mentalhealth.named-clinicians";

    private final EmployeeRepository employees;
    private final OrgUnitRepository orgUnits;
    private final StaffMemberRepository staff;
    private final PartnerOrganisationRepository partners;
    private final ConfigService config;
    private final NotificationService notifications;
    private final JdbcTemplate jdbc;
    private final JsonMapper json;
    private final Clock clock;
    private final int minGroupSize;
    private final String ohPortal;
    private final String employeePortal;
    private final String partnerPortal;

    public MhSupport(EmployeeRepository employees, OrgUnitRepository orgUnits, StaffMemberRepository staff, PartnerOrganisationRepository partners,
                     ConfigService config, NotificationService notifications, JdbcTemplate jdbc, JsonMapper json, Clock clock,
                     @Value("${wellnessz.analytics.min-group-size:5}") int minGroupSize,
                     @Value("${wellnessz.notification.oh-workspace-url:https://oh.wellnessz.local}") String ohPortal,
                     @Value("${wellnessz.notification.employee-portal-url:https://employee.wellnessz.local}") String employeePortal,
                     @Value("${wellnessz.notification.partner-portal-url:https://partners.wellnessz.local}") String partnerPortal) {
        this.employees = employees;
        this.orgUnits = orgUnits;
        this.staff = staff;
        this.partners = partners;
        this.config = config;
        this.notifications = notifications;
        this.jdbc = jdbc;
        this.json = json;
        this.clock = clock;
        this.minGroupSize = minGroupSize;
        this.ohPortal = ohPortal;
        this.employeePortal = employeePortal;
        this.partnerPortal = partnerPortal;
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

    public Optional<String> config(String key) {
        return config.get(key).map(String::trim).filter(v -> !v.isEmpty());
    }

    public String config(String key, String defaultValue) {
        return config(key).orElse(defaultValue);
    }

    public int intConfig(String key, int defaultValue) {
        return config(key).map(v -> {
            try {
                return Integer.parseInt(v);
            } catch (NumberFormatException e) {
                return defaultValue;
            }
        }).orElse(defaultValue);
    }

    public List<String> listConfig(String key, String defaultValue) {
        return Arrays.stream(config(key, defaultValue).split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    }

    public int minGroupSize() {
        return minGroupSize;
    }

    // ------------------------------------------------------------------ reference numbers

    /** {@code PREFIX-YYYY-NNNNNN} with a gap-tolerant per-prefix, per-year counter. */
    public String nextRef(String prefix) {
        int year = today().getYear();
        Long n = jdbc.queryForObject("""
                insert into mentalhealth.ref_counter (prefix, seq_year, last_value) values (?, ?, 1)
                on conflict (prefix, seq_year) do update set last_value = mentalhealth.ref_counter.last_value + 1
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

    // ------------------------------------------------------------------ text

    /** Free text that may reach non-clinical readers: trimmed, length-limited and screened for clinical content. */
    public static String screened(String field, String value, int max) {
        String v = trimmed(field, value, max);
        if (v != null) {
            ClinicalContentException.screen(field, v);
        }
        return v;
    }

    /** Free text kept inside the restricted team (encrypted at rest): trimmed and length-limited only. */
    public static String trimmed(String field, String value, int max) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String v = value.strip();
        if (v.length() > max) {
            throw ApiException.badRequest("too_long", "Field '" + field + "' exceeds " + max + " characters");
        }
        return v;
    }

    public static ApiException unprocessable(String code, String message) {
        return new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, code, message);
    }

    // ------------------------------------------------------------------ people & units

    public Optional<Employee> employee(String employeeId) {
        return employeeId == null ? Optional.empty() : employees.findById(employeeId);
    }

    public Employee requireEmployee(String employeeId) {
        return employee(employeeId).orElseThrow(() -> ApiException.notFound("Employee"));
    }

    public String name(String employeeId) {
        return employee(employeeId).map(Employee::getDisplayName).orElse(employeeId);
    }

    public static String firstName(String displayName) {
        if (displayName == null || displayName.isBlank()) {
            return "colleague";
        }
        String s = displayName.strip();
        int i = s.indexOf(' ');
        return i > 0 ? s.substring(0, i) : s;
    }

    public Optional<OrgUnit> unit(String code) {
        return code == null ? Optional.empty() : orgUnits.findById(code);
    }

    public String unitName(String code) {
        return unit(code).map(OrgUnit::getName).orElse(code);
    }

    public List<OrgUnit> units() {
        return orgUnits.findAll();
    }

    /** Employees whose department lies inside the org scope path (manager / HR scope). */
    public List<String> employeesInScope(String scopePath) {
        if (scopePath == null || scopePath.isBlank()) {
            return List.of();
        }
        return jdbc.queryForList("""
                select e.employee_id from masterdata.employee e join masterdata.org_unit o on o.code = e.department_code
                 where e.active = true and (o.path = ? or o.path like concat(?, '/%'))""", String.class, scopePath, scopePath);
    }

    /** Active head count per department code. */
    public Map<String, Long> headcountByDepartment() {
        Map<String, Long> out = new LinkedHashMap<>();
        jdbc.query("select department_code, count(*) from masterdata.employee where active = true group by department_code",
                rs -> {
                    out.put(rs.getString(1), rs.getLong(2));
                });
        return out;
    }

    public Optional<StaffMember> staff(String username) {
        return username == null ? Optional.empty() : staff.findById(username);
    }

    /** Named mental-health clinicians: configured usernames, or every active PSYCH_CLINICIAN when the list is empty. */
    public List<String> namedClinicians() {
        List<String> named = listConfig(NAMED_CLINICIANS_KEY, "");
        if (!named.isEmpty()) {
            return named;
        }
        return staff.findByActiveTrueOrderByDisplayName().stream().filter(s -> s.hasRole(Roles.PSYCH_CLINICIAN)).map(StaffMember::getUsername).toList();
    }

    // ------------------------------------------------------------------ partners

    public Optional<PartnerOrganisation> partner(String code) {
        return code == null ? Optional.empty() : partners.findById(code);
    }

    public String partnerName(String code) {
        return partner(code).map(PartnerOrganisation::getName).orElse(code);
    }

    // ------------------------------------------------------------------ content-free notifications

    public void notifyClinicians(String template, Map<String, String> params, String route) {
        notifyClinicians(template, params, route, Priority.NORMAL);
    }

    /** Named clinicians; {@link Priority#URGENT} (same-day call backs) bypasses quiet hours. */
    public void notifyClinicians(String template, Map<String, String> params, String route, Priority priority) {
        for (String username : namedClinicians()) {
            staff(username).filter(s -> s.getEmail() != null).ifPresent(s -> notifications.enqueue(s.getUsername(), s.getEmail(), Channel.EMAIL,
                    priority, template, link(params, ohPortal, route), ZoneOffset.UTC));
        }
    }

    public void notifyAddress(String recipientId, String email, String template, Map<String, String> params, String route, Priority priority) {
        if (email != null && !email.isBlank()) {
            notifications.enqueue(recipientId, email, Channel.EMAIL, priority, template, link(params, ohPortal, route), ZoneOffset.UTC);
        }
    }

    public void notifyEmployee(String employeeId, String template, Map<String, String> params, String route) {
        employee(employeeId).filter(e -> e.getEmail() != null).ifPresent(e -> {
            Map<String, String> p = new LinkedHashMap<>(params);
            p.putIfAbsent("firstName", firstName(e.getDisplayName()));
            notifications.enqueue(e.getEmployeeId(), e.getEmail(), Channel.EMAIL, Priority.NORMAL, template, link(p, employeePortal, route),
                    ZoneOffset.UTC);
        });
    }

    public void notifyPartner(String partnerCode, String template, Map<String, String> params, String route) {
        partner(partnerCode).map(PartnerOrganisation::getContactEmail).ifPresent(a -> notifications.enqueue("partner:" + partnerCode, a,
                Channel.EMAIL, Priority.NORMAL, template, link(params, partnerPortal, route), ZoneOffset.UTC));
    }

    private static Map<String, String> link(Map<String, String> params, String portal, String route) {
        Map<String, String> out = new LinkedHashMap<>(params);
        out.putIfAbsent("portalLink", portal + "/" + route);
        return out;
    }
}
