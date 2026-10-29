package com.wellnessz.e05.support;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.wellnessz.common.error.ApiException;
import com.wellnessz.common.text.ClinicalContentException;
import com.wellnessz.masterdata.Employee;
import com.wellnessz.masterdata.EmployeeRepository;
import com.wellnessz.masterdata.StaffMember;
import com.wellnessz.masterdata.StaffMemberRepository;
import com.wellnessz.masterdata.fitness.Restriction;
import com.wellnessz.masterdata.fitness.RestrictionRepository;
import com.wellnessz.notification.Channel;
import com.wellnessz.notification.NotificationService;
import com.wellnessz.notification.Priority;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Shared E05 helpers: catalogue validation of restrictions, JSON (de)serialisation of functional restriction lists,
 * clinical-text screening and content-free notifications to employees, managers and OH staff.
 */
@Component
public class FitnessSupport {

    public static final int MAX_RESTRICTIONS = 20;
    private static final TypeReference<List<RestrictionRef.View>> VIEWS = new TypeReference<>() {
    };
    private static final ZoneId UTC = ZoneOffset.UTC;

    private final RestrictionRepository catalogue;
    private final EmployeeRepository employees;
    private final StaffMemberRepository staff;
    private final NotificationService notifications;
    private final JsonMapper json;
    private final Clock clock;
    private final String employeePortal;
    private final String managerPortal;
    private final String ohPortal;

    public FitnessSupport(RestrictionRepository catalogue, EmployeeRepository employees, StaffMemberRepository staff,
                          NotificationService notifications, JsonMapper json, Clock clock,
                          @Value("${wellnessz.notification.employee-portal-url:https://employee.wellnessz.local}") String employeePortal,
                          @Value("${wellnessz.notification.manager-workspace-url:https://manager.wellnessz.local}") String managerPortal,
                          @Value("${wellnessz.notification.oh-workspace-url:https://oh.wellnessz.local}") String ohPortal) {
        this.catalogue = catalogue;
        this.employees = employees;
        this.staff = staff;
        this.notifications = notifications;
        this.json = json;
        this.clock = clock;
        this.employeePortal = employeePortal;
        this.managerPortal = managerPortal;
        this.ohPortal = ohPortal;
    }

    public LocalDate today() {
        return LocalDate.now(clock.withZone(UTC));
    }

    public Clock clock() {
        return clock;
    }

    // ------------------------------------------------------------------ restrictions

    /** Validates restrictions against the catalogue and renders their functional labels. */
    public List<RestrictionRef.View> resolve(List<RestrictionRef.Input> inputs) {
        if (inputs == null || inputs.isEmpty()) {
            return List.of();
        }
        if (inputs.size() > MAX_RESTRICTIONS) {
            throw ApiException.badRequest("too_many_restrictions", "At most " + MAX_RESTRICTIONS + " restrictions");
        }
        List<RestrictionRef.View> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (RestrictionRef.Input in : inputs) {
            if (in == null || in.code() == null) {
                throw ApiException.badRequest("invalid_restriction", "Restriction code required");
            }
            Restriction r = catalogue.findById(in.code()).filter(Restriction::isActive)
                    .orElseThrow(() -> ApiException.badRequest("unknown_restriction", "Unknown or inactive restriction: " + in.code()));
            if (!seen.add(r.getCode())) {
                throw ApiException.badRequest("duplicate_restriction", "Restriction listed twice: " + r.getCode());
            }
            BigDecimal value = in.value();
            if (r.isValueRequired() && value == null) {
                throw ApiException.badRequest("restriction_value_required", "A value is required for " + r.getCode());
            }
            if (value != null && (value.signum() < 0 || value.compareTo(BigDecimal.valueOf(500)) > 0)) {
                throw ApiException.badRequest("invalid_restriction_value", "Restriction values must be between 0 and 500");
            }
            out.add(new RestrictionRef.View(r.getCode(), r.isValueRequired() ? r.render(value) : r.getLabel(),
                    r.isValueRequired() ? value : null));
        }
        return List.copyOf(out);
    }

    public Optional<Restriction> catalogueEntry(String code) {
        return catalogue.findById(code);
    }

    public String write(List<RestrictionRef.View> views) {
        return json.writeValueAsString(views == null ? List.of() : views);
    }

    public List<RestrictionRef.View> read(String text) {
        return text == null || text.isBlank() ? List.of() : json.readValue(text, VIEWS);
    }

    public String writeJson(Object value) {
        return json.writeValueAsString(value);
    }

    public <T> T readJson(String text, TypeReference<T> type) {
        return json.readValue(text, type);
    }

    public static String labels(List<RestrictionRef.View> views) {
        return views.isEmpty() ? "None" : String.join("; ", views.stream().map(RestrictionRef.View::label).toList());
    }

    // ------------------------------------------------------------------ text

    /** Functional (manager / HR / employee facing) text: length-limited and screened for clinical content. */
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

    /** Clinical (OH-only) text: length-limited, stored encrypted by the entity converter. */
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

    // ------------------------------------------------------------------ people

    public Optional<Employee> employee(String employeeId) {
        return employeeId == null ? Optional.empty() : employees.findById(employeeId);
    }

    public String employeeName(String employeeId) {
        return employee(employeeId).map(Employee::getDisplayName).orElse(employeeId);
    }

    public String staffName(String username) {
        return username == null ? null : staff.findById(username).map(StaffMember::getDisplayName).orElse(username);
    }

    public List<StaffMember> staffWithRole(String role) {
        return staff.findByActiveTrueOrderByDisplayName().stream().filter(s -> s.hasRole(role)).toList();
    }

    // ------------------------------------------------------------------ content-free notifications

    public void notifyEmployee(String employeeId, String template, Map<String, String> params) {
        employee(employeeId).filter(e -> e.getEmail() != null).ifPresent(e -> notifications.enqueue(e.getEmployeeId(), e.getEmail(),
                Channel.EMAIL, Priority.NORMAL, template, withDefaults(params, firstName(e), employeePortal), UTC));
    }

    public void notifyManagerOf(String employeeId, String template, Map<String, String> params) {
        employee(employeeId).map(Employee::getManagerEmployeeId).flatMap(this::employee).filter(m -> m.getEmail() != null)
                .ifPresent(m -> notifications.enqueue(m.getEmployeeId(), m.getEmail(), Channel.EMAIL, Priority.NORMAL, template,
                        withDefaults(params, firstName(m), managerPortal), UTC));
    }

    public void notifyStaff(String username, String template, Map<String, String> params) {
        if (username == null) {
            return;
        }
        staff.findById(username).filter(s -> s.getEmail() != null).ifPresent(s -> notifications.enqueue(s.getUsername(), s.getEmail(),
                Channel.EMAIL, Priority.NORMAL, template, withDefaults(params, null, ohPortal), UTC));
    }

    public void notifyRole(String role, String template, Map<String, String> params) {
        staffWithRole(role).forEach(s -> notifyStaff(s.getUsername(), template, params));
    }

    private static String firstName(Employee e) {
        String n = e.getDisplayName();
        return n == null || n.isBlank() ? "colleague" : n.split(" ")[0];
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
