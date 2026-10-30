package com.wellnessz.e12.support;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import com.wellnessz.common.error.ApiException;
import com.wellnessz.common.text.ClinicalContentException;
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
import com.wellnessz.security.identity.CurrentUser;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Shared E12 helpers: clock, configuration, HRIS / org-unit lookups and manager scope, JSON, text validation and the
 * content-free notifications ({@code campaign.invitation}, {@code screening.follow-up}, {@code ergonomic.action-due}).
 */
@Component
public class PromotionSupport {

    private final Clock clock;
    private final ConfigService config;
    private final EmployeeRepository employees;
    private final OrgUnitRepository orgUnits;
    private final StaffMemberRepository staff;
    private final NotificationService notifications;
    private final JsonMapper json;
    private final String employeePortal;
    private final String managerPortal;
    private final String ohPortal;
    private final int minGroupSize;

    public PromotionSupport(Clock clock, ConfigService config, EmployeeRepository employees, OrgUnitRepository orgUnits,
                            StaffMemberRepository staff, NotificationService notifications, JsonMapper json,
                            @Value("${wellnessz.notification.employee-portal-url:https://employee.wellnessz.local}") String employeePortal,
                            @Value("${wellnessz.notification.manager-workspace-url:https://manager.wellnessz.local}") String managerPortal,
                            @Value("${wellnessz.notification.oh-workspace-url:https://oh.wellnessz.local}") String ohPortal,
                            @Value("${wellnessz.analytics.min-group-size:5}") int minGroupSize) {
        this.clock = clock;
        this.config = config;
        this.employees = employees;
        this.orgUnits = orgUnits;
        this.staff = staff;
        this.notifications = notifications;
        this.json = json;
        this.employeePortal = employeePortal;
        this.managerPortal = managerPortal;
        this.ohPortal = ohPortal;
        this.minGroupSize = Math.max(2, minGroupSize);
    }

    // ------------------------------------------------------------------ time & configuration

    public Clock clock() {
        return clock;
    }

    public LocalDate today() {
        return LocalDate.now(clock.withZone(ZoneOffset.UTC));
    }

    public Instant now() {
        return clock.instant();
    }

    public int minGroupSize() {
        return minGroupSize;
    }

    public Optional<String> configString(String key) {
        return config.get(key).map(String::trim).filter(s -> !s.isEmpty());
    }

    public int configInt(String key, int fallback) {
        try {
            return configString(key).map(Integer::parseInt).orElse(fallback);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    public BigDecimal configDecimal(String key, BigDecimal fallback) {
        try {
            return configString(key).map(BigDecimal::new).orElse(fallback);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    public String nextRef(String prefix, long sequence) {
        return String.format("%s-%d-%06d", prefix, today().getYear(), sequence);
    }

    // ------------------------------------------------------------------ people & org units

    public Optional<Employee> employee(String employeeId) {
        return employeeId == null ? Optional.empty() : employees.findById(employeeId);
    }

    public Employee requireEmployee(String employeeId) {
        return employee(employeeId).orElseThrow(() -> ApiException.notFound("Employee"));
    }

    /** Employee by id or badge (integration references). */
    public Optional<Employee> byRef(String ref) {
        if (ref == null || ref.isBlank()) {
            return Optional.empty();
        }
        return employees.findById(ref).or(() -> employees.findByBadgeIdAndActiveTrue(ref));
    }

    public Map<String, Employee> employeesById(Collection<String> ids) {
        Map<String, Employee> out = new LinkedHashMap<>();
        employees.findAllById(ids).forEach(e -> out.put(e.getEmployeeId(), e));
        return out;
    }

    public List<Employee> activeEmployeesIn(Collection<String> unitCodes) {
        Set<String> units = Set.copyOf(unitCodes);
        return employees.findAll().stream().filter(Employee::isActive).filter(e -> units.contains(e.getDepartmentCode())).toList();
    }

    public List<Employee> activeEmployees() {
        return employees.findAll().stream().filter(Employee::isActive).toList();
    }

    public String displayName(String employeeId) {
        return employee(employeeId).map(Employee::getDisplayName).orElse(employeeId);
    }

    public boolean unitExists(String code) {
        return code != null && orgUnits.findById(code).filter(OrgUnit::isActive).isPresent();
    }

    public static boolean inScope(String scope, String path) {
        return scope != null && path != null && (path.equals(scope) || path.startsWith(scope + "/"));
    }

    /**
     * Org units a manager may see in the manager workspace: units whose path lies inside the caller's org scope, plus the
     * manager's own department (when no scope is carried in the token).
     */
    public Set<String> unitsInScope(CurrentUser user) {
        Set<String> out = new LinkedHashSet<>();
        if (user.orgScope() != null && !user.orgScope().startsWith("/PARTNERS/")) {
            orgUnits.findAll().stream().filter(u -> inScope(user.orgScope(), u.getPath())).map(OrgUnit::getCode).forEach(out::add);
        }
        if (out.isEmpty()) {
            employee(user.employeeId()).map(Employee::getDepartmentCode).ifPresent(out::add);
        }
        return out;
    }

    public Optional<StaffMember> staffMember(String username) {
        return username == null ? Optional.empty() : staff.findById(username);
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

    public static List<String> csv(String value) {
        return value == null || value.isBlank() ? List.of()
                : Arrays.stream(value.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    }

    public static String joinCsv(Collection<String> values) {
        return values == null || values.isEmpty() ? null : String.join(",", values);
    }

    // ------------------------------------------------------------------ text

    /** Text shown outside OH: length-limited and screened for clinical content (422 clinical_content_not_allowed). */
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

    public static ApiException unprocessable(String code, String message) {
        return new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, code, message);
    }

    // ------------------------------------------------------------------ content-free notifications

    public void notifyEmployee(String employeeId, String template, Map<String, String> params, String path) {
        employee(employeeId).filter(e -> e.getEmail() != null).ifPresent(e -> notifications.enqueue(e.getEmployeeId(), e.getEmail(),
                Channel.EMAIL, Priority.NORMAL, template, withDefaults(params, firstName(e.getDisplayName()), employeePortal + path), ZoneOffset.UTC));
    }

    /** Action owner: a staff member (username) or an employee (id); falls back silently when neither has an address. */
    public void notifyOwner(String owner, String template, Map<String, String> params, boolean manager) {
        Optional<StaffMember> s = staffMember(owner);
        if (s.isPresent() && s.get().getEmail() != null) {
            notifications.enqueue(s.get().getUsername(), s.get().getEmail(), Channel.EMAIL, Priority.NORMAL, template,
                    withDefaults(params, firstName(s.get().getDisplayName()), ohPortal + "/#/ergonomics"), ZoneOffset.UTC);
            return;
        }
        employee(owner).filter(e -> e.getEmail() != null).ifPresent(e -> notifications.enqueue(e.getEmployeeId(), e.getEmail(), Channel.EMAIL,
                Priority.NORMAL, template, withDefaults(params, firstName(e.getDisplayName()),
                        (manager ? managerPortal : employeePortal) + "/#/ergonomics"), ZoneOffset.UTC));
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
