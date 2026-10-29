package com.wellnessz.e04.support;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.Year;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Collection;
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
import com.wellnessz.masterdata.PartnerOrganisation;
import com.wellnessz.masterdata.PartnerOrganisationRepository;
import com.wellnessz.masterdata.StaffMember;
import com.wellnessz.masterdata.StaffMemberRepository;
import com.wellnessz.notification.Channel;
import com.wellnessz.notification.NotificationService;
import com.wellnessz.notification.Priority;
import com.wellnessz.security.identity.CurrentUser;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Shared E04 helpers: clock, configuration values, HRIS / staff / partner-organisation lookups, reference numbers, JSON,
 * text validation and the content-free notifications (approved templates only – never a diagnosis, plan content or
 * report).
 */
@Component
public class TreatmentSupport {

    private final Clock clock;
    private final ConfigService config;
    private final EmployeeRepository employees;
    private final StaffMemberRepository staff;
    private final PartnerOrganisationRepository partners;
    private final NotificationService notifications;
    private final JsonMapper json;
    private final JdbcTemplate jdbc;
    private final String employeePortal;
    private final String ohPortal;
    private final String partnerPortal;
    private final int minGroupSize;

    public TreatmentSupport(Clock clock, ConfigService config, EmployeeRepository employees, StaffMemberRepository staff,
                            PartnerOrganisationRepository partners, NotificationService notifications, JsonMapper json, JdbcTemplate jdbc,
                            @Value("${wellnessz.notification.employee-portal-url:https://employee.wellnessz.local}") String employeePortal,
                            @Value("${wellnessz.notification.oh-workspace-url:https://oh.wellnessz.local}") String ohPortal,
                            @Value("${wellnessz.notification.partner-portal-url:https://partners.wellnessz.local}") String partnerPortal,
                            @Value("${wellnessz.analytics.min-group-size:5}") int minGroupSize) {
        this.clock = clock;
        this.config = config;
        this.employees = employees;
        this.staff = staff;
        this.partners = partners;
        this.notifications = notifications;
        this.json = json;
        this.jdbc = jdbc;
        this.employeePortal = employeePortal;
        this.ohPortal = ohPortal;
        this.partnerPortal = partnerPortal;
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

    public String configString(String key, String fallback) {
        return config.get(key).map(String::trim).filter(s -> !s.isEmpty()).orElse(fallback);
    }

    public String baseCurrency() {
        return configString("treatment.base-currency", "GBP");
    }

    // ------------------------------------------------------------------ reference numbers

    /** Gap-tolerant per-prefix, per-year counter: {@code PREFIX-YYYY-NNNNNN}. */
    public String nextRef(String prefix) {
        int year = Year.now(clock.withZone(ZoneOffset.UTC)).getValue();
        Long n = jdbc.queryForObject("""
                insert into treatment.ref_sequence (prefix, seq_year, last_value) values (?, ?, 1)
                on conflict (prefix, seq_year) do update set last_value = treatment.ref_sequence.last_value + 1
                returning last_value""", Long.class, prefix, year);
        return prefix + "-" + year + "-" + String.format(Locale.ROOT, "%06d", n);
    }

    // ------------------------------------------------------------------ people & organisations

    public Optional<Employee> employee(String employeeId) {
        return employeeId == null ? Optional.empty() : employees.findById(employeeId);
    }

    public String displayName(String employeeId) {
        return employee(employeeId).map(Employee::getDisplayName).orElse(employeeId);
    }

    public Map<String, Employee> employeesById(Collection<String> ids) {
        Map<String, Employee> out = new LinkedHashMap<>();
        employees.findAllById(ids).forEach(e -> out.put(e.getEmployeeId(), e));
        return out;
    }

    public Optional<StaffMember> staffMember(String username) {
        return username == null ? Optional.empty() : staff.findById(username);
    }

    public String staffName(String username) {
        return staffMember(username).map(StaffMember::getDisplayName).orElse(username);
    }

    public List<StaffMember> staffWithRole(String role) {
        return staff.findByActiveTrueOrderByDisplayName().stream().filter(s -> s.hasRole(role)).toList();
    }

    public Optional<PartnerOrganisation> partner(String code) {
        return code == null ? Optional.empty() : partners.findById(code);
    }

    public String partnerName(String code) {
        return partner(code).map(PartnerOrganisation::getName).orElse(code);
    }

    public Map<String, PartnerOrganisation> partnersByCode() {
        Map<String, PartnerOrganisation> out = new LinkedHashMap<>();
        partners.findAll().forEach(p -> out.put(p.getCode(), p));
        return out;
    }

    public PartnerOrganisation requireActivePartner(String code, String field) {
        return partner(code).filter(PartnerOrganisation::isActive)
                .orElseThrow(() -> new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "unknown_organisation",
                        "Unknown or inactive organisation in '" + field + "'"));
    }

    /** Organisation of a partner-portal user: last segment of {@code org_scope} (e.g. {@code /PARTNERS/CITYHOSP}). */
    public static String partnerOrg(CurrentUser user) {
        if (!user.hasAnyRole(Roles.PARTNER_PROVIDER, Roles.TPA_ADJUSTER) || user.orgScope() == null) {
            return null;
        }
        String s = user.orgScope();
        String last = s.substring(s.lastIndexOf('/') + 1);
        return last.matches("[A-Z0-9_]{2,40}") ? last : null;
    }

    public static String requirePartnerOrg(CurrentUser user) {
        String org = partnerOrg(user);
        if (org == null) {
            throw ApiException.forbidden("No partner organisation in the caller's scope");
        }
        return org;
    }

    // ------------------------------------------------------------------ JSON

    public String write(Object value) {
        return value == null ? null : json.writeValueAsString(value);
    }

    public <T> T read(String text, TypeReference<T> type, T fallback) {
        if (text == null || text.isBlank()) {
            return fallback;
        }
        return json.readValue(text, type);
    }

    public List<String> readStrings(String text) {
        return read(text, new TypeReference<List<String>>() {
        }, List.of());
    }

    public List<Map<String, Object>> readMaps(String text) {
        return read(text, new TypeReference<List<Map<String, Object>>>() {
        }, List.of());
    }

    public Map<String, Object> readMap(String text) {
        return read(text, new TypeReference<LinkedHashMap<String, Object>>() {
        }, new LinkedHashMap<>());
    }

    public static List<String> csv(String value) {
        return value == null || value.isBlank() ? List.of()
                : Arrays.stream(value.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    }

    public static String joinCsv(Collection<String> values) {
        return values == null || values.isEmpty() ? null : String.join(",", values);
    }

    // ------------------------------------------------------------------ text

    /** Text shown to non-OH readers (partners, insurers, employees' free text): length-limited and screened (422). */
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

    public static ApiException unprocessable(String code, String message) {
        return new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, code, message);
    }

    // ------------------------------------------------------------------ content-free notifications

    public void notifyEmployee(String employeeId, String template, Map<String, String> params, String path) {
        employee(employeeId).filter(e -> e.getEmail() != null).ifPresent(e -> notifications.enqueue(e.getEmployeeId(), e.getEmail(),
                Channel.EMAIL, Priority.NORMAL, template, withDefaults(params, firstName(e.getDisplayName()), employeePortal + path),
                ZoneOffset.UTC));
    }

    public void notifyStaff(String username, String template, Map<String, String> params, String ohPath) {
        staffMember(username).filter(s -> s.getEmail() != null).ifPresent(s -> notifications.enqueue(s.getUsername(), s.getEmail(),
                Channel.EMAIL, Priority.NORMAL, template, withDefaults(params, null, ohPortal + ohPath), ZoneOffset.UTC));
    }

    public void notifyRole(String role, String template, Map<String, String> params, String ohPath) {
        staffWithRole(role).forEach(s -> notifyStaff(s.getUsername(), template, params, ohPath));
    }

    /** Provider contact mailbox (partner portal link). */
    public void notifyPartner(String providerCode, String template, Map<String, String> params, String portalPath) {
        partner(providerCode).filter(p -> p.getContactEmail() != null).ifPresent(p -> notifications.enqueue(p.getCode(), p.getContactEmail(),
                Channel.EMAIL, Priority.NORMAL, template, withDefaults(params, null, partnerPortal + portalPath), ZoneOffset.UTC));
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
