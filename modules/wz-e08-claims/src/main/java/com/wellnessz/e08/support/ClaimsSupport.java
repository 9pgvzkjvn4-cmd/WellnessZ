package com.wellnessz.e08.support;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
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
import com.wellnessz.masterdata.JobCode;
import com.wellnessz.masterdata.JobCodeRepository;
import com.wellnessz.masterdata.OrgUnit;
import com.wellnessz.masterdata.OrgUnitRepository;
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
 * Shared E08 helpers: time and configuration, reference numbers, people / unit / partner-organisation lookups, text
 * screening and content-free notifications (claims team mailbox, legal mailbox, OH staff, adjusters).
 */
@Component
public class ClaimsSupport {

    public static final String LEGAL_EMAIL_KEY = "claims.legal-email";
    public static final String MAILBOX_EMAIL_KEY = "claims.mailbox-email";
    public static final Map<String, Object> NONE = Map.of();
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {
    };

    private final EmployeeRepository employees;
    private final JobCodeRepository jobCodes;
    private final OrgUnitRepository orgUnits;
    private final StaffMemberRepository staff;
    private final PartnerOrganisationRepository partners;
    private final ConfigService config;
    private final NotificationService notifications;
    private final JdbcTemplate jdbc;
    private final JsonMapper json;
    private final Clock clock;
    private final String ohPortal;
    private final String partnerPortal;
    private final String managerPortal;

    public ClaimsSupport(EmployeeRepository employees, JobCodeRepository jobCodes, OrgUnitRepository orgUnits, StaffMemberRepository staff,
                         PartnerOrganisationRepository partners, ConfigService config, NotificationService notifications, JdbcTemplate jdbc,
                         JsonMapper json, Clock clock,
                         @Value("${wellnessz.notification.oh-workspace-url:https://oh.wellnessz.local}") String ohPortal,
                         @Value("${wellnessz.notification.partner-portal-url:https://partners.wellnessz.local}") String partnerPortal,
                         @Value("${wellnessz.notification.manager-workspace-url:https://manager.wellnessz.local}") String managerPortal) {
        this.employees = employees;
        this.jobCodes = jobCodes;
        this.orgUnits = orgUnits;
        this.staff = staff;
        this.partners = partners;
        this.config = config;
        this.notifications = notifications;
        this.jdbc = jdbc;
        this.json = json;
        this.clock = clock;
        this.ohPortal = ohPortal;
        this.partnerPortal = partnerPortal;
        this.managerPortal = managerPortal;
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

    public String baseCurrency() {
        return config("claims.base-currency", "GBP").toUpperCase(Locale.ROOT);
    }

    // ------------------------------------------------------------------ reference numbers

    /** {@code PREFIX-YYYY-NNNNNN} with a gap-tolerant per-prefix, per-year counter. */
    public String nextRef(String prefix) {
        int year = today().getYear();
        Long n = jdbc.queryForObject("""
                insert into claims.ref_counter (prefix, seq_year, last_value) values (?, ?, 1)
                on conflict (prefix, seq_year) do update set last_value = claims.ref_counter.last_value + 1
                returning last_value""", Long.class, prefix, year);
        return String.format(Locale.ROOT, "%s-%d-%06d", prefix, year, n);
    }

    /**
     * True when the same resource (with the same title, i.e. the same status) has already been disclosed to this recipient and
     * the disclosure is still active – repeated reads of an unchanged status are not logged again.
     */
    public boolean alreadyDisclosed(String resourceType, String resourceId, String recipientName, String title) {
        Boolean b = jdbc.queryForObject("""
                select exists (select 1 from platform.disclosure_log where resource_type = ? and resource_id = ? and recipient_name = ?
                                  and resource_title = ? and revoked_at is null)""", Boolean.class, resourceType, resourceId, recipientName, title);
        return Boolean.TRUE.equals(b);
    }

    // ------------------------------------------------------------------ JSON

    public String json(Object value) {
        return value == null ? null : json.writeValueAsString(value);
    }

    public Map<String, Object> readMap(String text) {
        return text == null || text.isBlank() ? new LinkedHashMap<>() : json.readValue(text, MAP);
    }

    public <T> T read(String text, TypeReference<T> type) {
        return json.readValue(text, type);
    }

    // ------------------------------------------------------------------ text

    /** Free text that may reach adjusters, HR, finance or legal: trimmed, length-limited and screened for clinical content. */
    public static String screened(String field, String value, int max) {
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

    // ------------------------------------------------------------------ people & units

    public Optional<Employee> employee(String employeeId) {
        return employeeId == null ? Optional.empty() : employees.findById(employeeId);
    }

    public String name(String employeeId) {
        return employee(employeeId).map(Employee::getDisplayName).orElse(employeeId);
    }

    /** Department label shown with a claim (never anything clinical). */
    public String unit(String employeeId) {
        return employee(employeeId).map(Employee::getDepartmentCode)
                .map(d -> orgUnits.findById(d).map(OrgUnit::getName).orElse(d)).orElse("–");
    }

    public String departmentName(String code) {
        return code == null ? "–" : orgUnits.findById(code).map(OrgUnit::getName).orElse(code);
    }

    public String jobTitle(Employee e) {
        return e == null || e.getJobCode() == null ? null : jobCodes.findById(e.getJobCode()).map(JobCode::getTitle).orElse(e.getJobCode());
    }

    public List<Employee> allEmployees() {
        return employees.findAll();
    }

    public Optional<StaffMember> staff(String username) {
        return username == null ? Optional.empty() : staff.findById(username);
    }

    public String staffName(String username) {
        return staff(username).map(StaffMember::getDisplayName).orElse(username);
    }

    public List<StaffMember> activeStaff() {
        return staff.findByActiveTrueOrderByDisplayName();
    }

    // ------------------------------------------------------------------ partner organisations

    public Optional<PartnerOrganisation> partner(String code) {
        return code == null ? Optional.empty() : partners.findById(code);
    }

    public String partnerName(String code) {
        return partner(code).map(PartnerOrganisation::getName).orElse(code);
    }

    /** Active insurer or TPA, else 422 {@code unknown_payer}. */
    public PartnerOrganisation requirePayer(String code) {
        return partner(code).filter(PartnerOrganisation::isActive)
                .filter(p -> "INSURER".equals(p.getOrgType()) || "TPA".equals(p.getOrgType()))
                .orElseThrow(() -> unprocessable("unknown_payer", "Unknown or inactive insurer / TPA"));
    }

    /** Organisation of a partner-portal user: last segment of {@code org_scope}, e.g. {@code /PARTNERS/GULFTPA} → GULFTPA. */
    public static String partnerOrg(CurrentUser user) {
        if (!user.hasAnyRole(Roles.TPA_ADJUSTER, Roles.PARTNER_PROVIDER) || user.orgScope() == null) {
            return null;
        }
        String s = user.orgScope();
        String last = s.substring(s.lastIndexOf('/') + 1);
        return last.matches("[A-Z0-9_]{2,40}") ? last : null;
    }

    // ------------------------------------------------------------------ roles

    public static boolean isOh(CurrentUser user) {
        return user.hasAnyRole(Roles.OH_TEAM);
    }

    /** Reserves, paid totals and ledger amounts are shown to the claims team, finance and the CMO only. */
    public static boolean seesMoney(CurrentUser user) {
        return user.hasAnyRole(Roles.CLAIMS_COORDINATOR, Roles.FINANCE, Roles.CMO);
    }

    // ------------------------------------------------------------------ content-free notifications

    public void notifyStaff(String username, String template, Map<String, String> params, String route) {
        staff(username).filter(s -> s.getEmail() != null).ifPresent(s -> notifications.enqueue(s.getUsername(), s.getEmail(), Channel.EMAIL,
                Priority.NORMAL, template, link(params, ohPortal, route), ZoneOffset.UTC));
    }

    public void notifyRole(String role, String template, Map<String, String> params, String route) {
        activeStaff().stream().filter(s -> s.hasRole(role)).forEach(s -> notifyStaff(s.getUsername(), template, params, route));
    }

    /** Claims team mailbox ({@code claims.mailbox-email}). */
    public void notifyClaimsTeam(String template, Map<String, String> params, String route, Priority priority) {
        config(MAILBOX_EMAIL_KEY).ifPresent(a -> notifications.enqueue("claims-team", a, Channel.EMAIL, priority, template,
                link(params, ohPortal, route), ZoneOffset.UTC));
    }

    /** Legal counsel mailbox ({@code claims.legal-email}). */
    public void notifyLegal(String template, Map<String, String> params, String route) {
        config(LEGAL_EMAIL_KEY).ifPresent(a -> notifications.enqueue("legal", a, Channel.EMAIL, Priority.NORMAL, template,
                link(params, ohPortal, route), ZoneOffset.UTC));
    }

    /** External adjuster (named e-mail, else the payer's contact address) – partner-portal link, content-free. */
    public void notifyAdjuster(String adjusterEmail, String payerCode, String template, Map<String, String> params, String route) {
        String address = adjusterEmail != null ? adjusterEmail : partner(payerCode).map(PartnerOrganisation::getContactEmail).orElse(null);
        if (address != null) {
            notifications.enqueue("partner:" + payerCode, address, Channel.EMAIL, Priority.NORMAL, template, link(params, partnerPortal, route),
                    ZoneOffset.UTC);
        }
    }

    /** A recipient organisation (insurer / authority) by its contact address. */
    public void notifyOrganisation(String code, String template, Map<String, String> params, String linkText) {
        partner(code).map(PartnerOrganisation::getContactEmail).ifPresent(a -> {
            Map<String, String> p = new LinkedHashMap<>(params);
            p.putIfAbsent("portalLink", linkText);
            notifications.enqueue("partner:" + code, a, Channel.EMAIL, Priority.NORMAL, template, p, ZoneOffset.UTC);
        });
    }

    public String partnerPortal() {
        return partnerPortal;
    }

    public String managerPortal() {
        return managerPortal;
    }

    private static Map<String, String> link(Map<String, String> params, String portal, String route) {
        Map<String, String> out = new LinkedHashMap<>(params);
        out.putIfAbsent("portalLink", portal + "/" + route);
        return out;
    }
}
