package com.wellnessz.e33.service;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import com.wellnessz.casekernel.device.DeviceCodes;
import com.wellnessz.casekernel.device.DeviceObservation;
import com.wellnessz.casekernel.module.ModuleRegistry;
import com.wellnessz.common.error.ApiException;
import com.wellnessz.common.model.Roles;
import com.wellnessz.masterdata.ConfigService;
import com.wellnessz.masterdata.Employee;
import com.wellnessz.masterdata.EmployeeRepository;
import com.wellnessz.notification.Channel;
import com.wellnessz.notification.NotificationService;
import com.wellnessz.notification.Priority;
import com.wellnessz.security.identity.CurrentUser;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/** Shared E33 helpers: clock, configuration (E18), module guard, JSON, HRIS lookups, acting-MRO rule and notifications. */
@Component
public class CaptureSupport {

    public static final String MODULE = "E33";
    static final String ACTING_MRO_KEY = "substance.acting-mro-physicians";

    private final Clock clock;
    private final ConfigService config;
    private final ModuleRegistry modules;
    private final EmployeeRepository employees;
    private final NotificationService notifications;
    private final JsonMapper json;
    private final String employeePortal;

    public CaptureSupport(Clock clock, ConfigService config, ModuleRegistry modules, EmployeeRepository employees,
                          NotificationService notifications, JsonMapper json,
                          @Value("${wellnessz.notification.employee-portal-url:https://employee.wellnessz.local}") String employeePortal) {
        this.clock = clock;
        this.config = config;
        this.modules = modules;
        this.employees = employees;
        this.notifications = notifications;
        this.json = json;
        this.employeePortal = employeePortal;
    }

    // ------------------------------------------------------------------ time, configuration, module guard

    public Instant now() {
        return clock.instant();
    }

    public LocalDate today() {
        return LocalDate.now(clock.withZone(ZoneOffset.UTC));
    }

    public void requireEnabled() {
        modules.requireEnabled(MODULE);
    }

    public boolean enabled() {
        return modules.isEnabled(MODULE);
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

    public List<String> configCsv(String key, String fallback) {
        return csv(configString(key).orElse(fallback));
    }

    public int sessionTimeoutMinutes() {
        return Math.max(1, configInt("device.capture.session-timeout-minutes", 10));
    }

    public BigDecimal ocrMinConfidence() {
        return configDecimal("device.ocr.min-confidence", new BigDecimal("0.90"));
    }

    // ------------------------------------------------------------------ people and roles

    public Optional<Employee> employee(String employeeId) {
        return employeeId == null ? Optional.empty() : employees.findById(employeeId);
    }

    public Optional<Employee> employeeByBadge(String badgeId) {
        return badgeId == null || badgeId.isBlank() ? Optional.empty() : employees.findByBadgeIdAndActiveTrue(badgeId);
    }

    public String displayName(String employeeId) {
        return employee(employeeId).map(Employee::getDisplayName).orElse(employeeId);
    }

    public static String firstName(String displayName) {
        return displayName == null || displayName.isBlank() ? "colleague" : displayName.trim().split(" ")[0];
    }

    /** MRO, or an OH physician designated in {@code substance.acting-mro-physicians} (the E10 rule). */
    public boolean actsAsMro(CurrentUser user) {
        if (user.hasRole(Roles.MRO)) {
            return true;
        }
        return user.hasRole(Roles.OH_PHYSICIAN) && configCsv(ACTING_MRO_KEY, "").contains(user.username());
    }

    // ------------------------------------------------------------------ JSON

    public String write(Object value) {
        return value == null ? null : json.writeValueAsString(value);
    }

    public Map<String, Object> readMap(String text) {
        if (text == null || text.isBlank()) {
            return new LinkedHashMap<>();
        }
        return json.readValue(text, new TypeReference<LinkedHashMap<String, Object>>() {
        });
    }

    /** Quality flags as stored (gate, outcome, code, message – never values). */
    public String writeFlags(List<DeviceObservation.QualityFlag> flags) {
        return flags == null || flags.isEmpty() ? null : write(flags);
    }

    public List<DeviceObservation.QualityFlag> readFlags(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        List<Map<String, Object>> raw = json.readValue(text, new TypeReference<List<Map<String, Object>>>() {
        });
        List<DeviceObservation.QualityFlag> out = new ArrayList<>();
        for (Map<String, Object> m : raw) {
            out.add(new DeviceObservation.QualityFlag(str(m.get("gate")), DeviceCodes.GateOutcome.valueOf(str(m.get("outcome"))),
                    str(m.get("code")), str(m.get("message"))));
        }
        return out;
    }

    private static String str(Object o) {
        return o == null ? null : o.toString();
    }

    public static List<String> csv(String value) {
        return value == null || value.isBlank() ? List.of()
                : Arrays.stream(value.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    }

    // ------------------------------------------------------------------ errors and notifications

    public static ApiException unprocessable(String code, String message) {
        return new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, code, message);
    }

    /** Content-free notification to an employee ({@code device.summary-available}). */
    public void notifyEmployee(String employeeId, String template, String path) {
        employee(employeeId).filter(e -> e.getEmail() != null).ifPresent(e -> notifications.enqueue(e.getEmployeeId(), e.getEmail(),
                Channel.EMAIL, Priority.NORMAL, template,
                Map.of("firstName", firstName(e.getDisplayName()), "portalLink", employeePortal + path), ZoneOffset.UTC));
    }
}
