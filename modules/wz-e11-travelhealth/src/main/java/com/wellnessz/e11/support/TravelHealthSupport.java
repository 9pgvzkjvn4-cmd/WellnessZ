package com.wellnessz.e11.support;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.wellnessz.common.error.ApiException;
import com.wellnessz.masterdata.ConfigService;
import com.wellnessz.masterdata.Employee;
import com.wellnessz.masterdata.EmployeeRepository;
import com.wellnessz.masterdata.JobCode;
import com.wellnessz.masterdata.JobCodeRepository;
import com.wellnessz.masterdata.StaffMember;
import com.wellnessz.masterdata.StaffMemberRepository;
import com.wellnessz.notification.Channel;
import com.wellnessz.notification.NotificationService;
import com.wellnessz.notification.Priority;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Shared E11 helpers: clock, configuration values, HRIS / staff lookups, reference numbers, JSON, text validation and the
 * content-free notifications (approved templates only – never a condition, an index case or an exposure detail).
 */
@Component
public class TravelHealthSupport {

    private final Clock clock;
    private final ConfigService config;
    private final EmployeeRepository employees;
    private final JobCodeRepository jobCodes;
    private final StaffMemberRepository staff;
    private final NotificationService notifications;
    private final JdbcTemplate jdbc;
    private final JsonMapper json;
    private final String employeePortal;
    private final String ohPortal;
    private final int minGroupSize;

    public TravelHealthSupport(Clock clock, ConfigService config, EmployeeRepository employees, JobCodeRepository jobCodes,
                               StaffMemberRepository staff, NotificationService notifications, JdbcTemplate jdbc, JsonMapper json,
                               @Value("${wellnessz.notification.employee-portal-url:https://employee.wellnessz.local}") String employeePortal,
                               @Value("${wellnessz.notification.oh-workspace-url:https://oh.wellnessz.local}") String ohPortal,
                               @Value("${wellnessz.analytics.min-group-size:5}") int minGroupSize) {
        this.clock = clock;
        this.config = config;
        this.employees = employees;
        this.jobCodes = jobCodes;
        this.staff = staff;
        this.notifications = notifications;
        this.jdbc = jdbc;
        this.json = json;
        this.employeePortal = employeePortal;
        this.ohPortal = ohPortal;
        this.minGroupSize = minGroupSize;
    }

    // ------------------------------------------------------------------ time & configuration

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

    /** Next reference number {@code PREFIX-YYYY-NNNNNN} (one counter per prefix and year). */
    public String nextRef(String prefix) {
        int year = today().getYear();
        Long n = jdbc.queryForObject("""
                insert into travelhealth.ref_counter (prefix, seq_year, last_value) values (?, ?, 1)
                on conflict (prefix, seq_year) do update set last_value = travelhealth.ref_counter.last_value + 1
                returning last_value""", Long.class, prefix, year);
        return String.format(Locale.ROOT, "%s-%d-%06d", prefix, year, n);
    }

    // ------------------------------------------------------------------ people

    public Optional<Employee> employee(String employeeId) {
        return employeeId == null ? Optional.empty() : employees.findById(employeeId);
    }

    public Optional<Employee> byBadge(String badgeId) {
        return badgeId == null ? Optional.empty() : employees.findByBadgeIdAndActiveTrue(badgeId);
    }

    public List<Employee> activeEmployees() {
        return employees.findAll().stream().filter(Employee::isActive).toList();
    }

    public Map<String, Employee> employeesById(Collection<String> ids) {
        Map<String, Employee> out = new LinkedHashMap<>();
        if (!ids.isEmpty()) {
            employees.findAllById(ids).forEach(e -> out.put(e.getEmployeeId(), e));
        }
        return out;
    }

    public String displayName(String employeeId) {
        return employee(employeeId).map(Employee::getDisplayName).orElse(employeeId);
    }

    public boolean isFoodHandler(Employee e) {
        return e != null && e.getJobCode() != null
                && jobCodes.findById(e.getJobCode()).map(JobCode::getSafetyCategory).map("FOOD_HANDLER"::equals).orElse(false);
    }

    public String staffName(String username) {
        return username == null ? null : staff.findById(username).map(StaffMember::getDisplayName).orElse(username);
    }

    public Optional<StaffMember> staffMember(String username) {
        return username == null ? Optional.empty() : staff.findById(username).filter(StaffMember::isActive);
    }

    public List<StaffMember> staffWithRole(String role) {
        return staff.findByActiveTrueOrderByDisplayName().stream().filter(s -> s.hasRole(role)).toList();
    }

    // ------------------------------------------------------------------ JSON & lists

    public String write(Object value) {
        return value == null ? null : json.writeValueAsString(value);
    }

    public <T> T read(String text, TypeReference<T> type, T fallback) {
        if (text == null || text.isBlank()) {
            return fallback;
        }
        return json.readValue(text, type);
    }

    public List<String> readList(String text) {
        return read(text, new TypeReference<List<String>>() {
        }, List.of());
    }

    public static List<String> csv(String value) {
        return value == null || value.isBlank() ? List.of()
                : Arrays.stream(value.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    }

    public static String joinCsv(Collection<String> values) {
        return values == null || values.isEmpty() ? null : String.join(",", values);
    }

    // ------------------------------------------------------------------ text

    /** OH-only free text: length-limited (stored encrypted by the entity converter). */
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

    /** Employee notification; the portal link points at the given employee-app route. */
    public void notifyEmployee(String employeeId, String template, Map<String, String> params, String route) {
        employee(employeeId).filter(e -> e.getEmail() != null && !e.getEmail().isBlank()).ifPresent(e -> notifications.enqueue(e.getEmployeeId(),
                e.getEmail(), Channel.EMAIL, Priority.NORMAL, template, withDefaults(params, firstName(e.getDisplayName()),
                        employeePortal + "/#" + route), ZoneOffset.UTC));
    }

    /** OH staff notification (workspace link). */
    public void notifyStaff(StaffMember s, String template, Map<String, String> params, String ohRoute, Priority priority) {
        if (s.getEmail() != null && !s.getEmail().isBlank()) {
            notifications.enqueue(s.getUsername(), s.getEmail(), Channel.EMAIL, priority, template,
                    withDefaults(params, null, ohPortal + "/#" + ohRoute), ZoneOffset.UTC);
        }
    }

    /** Notifies the named OH user when known and active, otherwise every active member of the fallback role. */
    public int notifyOwnerOrRole(String username, String fallbackRole, String template, Map<String, String> params, String ohRoute,
                                 Priority priority) {
        Optional<StaffMember> owner = staffMember(username);
        if (owner.isPresent()) {
            notifyStaff(owner.get(), template, params, ohRoute, priority);
            return 1;
        }
        List<StaffMember> team = staffWithRole(fallbackRole);
        team.forEach(s -> notifyStaff(s, template, params, ohRoute, priority));
        return team.size();
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
