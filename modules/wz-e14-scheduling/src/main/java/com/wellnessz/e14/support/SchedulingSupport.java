package com.wellnessz.e14.support;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;

import jakarta.persistence.EntityManager;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.wellnessz.common.error.ApiException;
import com.wellnessz.masterdata.ClinicSite;
import com.wellnessz.masterdata.ClinicSiteRepository;
import com.wellnessz.masterdata.ConfigService;
import com.wellnessz.masterdata.Employee;
import com.wellnessz.masterdata.EmployeeRepository;
import com.wellnessz.masterdata.StaffMember;
import com.wellnessz.masterdata.StaffMemberRepository;
import com.wellnessz.notification.Channel;
import com.wellnessz.notification.NotificationService;
import com.wellnessz.notification.Priority;
import com.wellnessz.security.audit.AuditService;

/**
 * Shared E14 helpers: clock and site time zones, configuration, demographics (names only – scheduling never reads clinical
 * content), content-free notifications, audit and the advisory locks that serialise conflicting bookings.
 */
@Component
public class SchedulingSupport {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH);
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH);

    private final Clock clock;
    private final ClinicSiteRepository sites;
    private final EmployeeRepository employees;
    private final StaffMemberRepository staff;
    private final ConfigService config;
    private final NotificationService notifications;
    private final AuditService audit;
    private final EntityManager em;
    private final String employeePortal;
    private final String ohPortal;

    public SchedulingSupport(Clock clock, ClinicSiteRepository sites, EmployeeRepository employees, StaffMemberRepository staff,
                             ConfigService config, NotificationService notifications, AuditService audit, EntityManager em,
                             @Value("${wellnessz.notification.employee-portal-url:https://employee.wellnessz.local}") String employeePortal,
                             @Value("${wellnessz.notification.oh-workspace-url:https://oh.wellnessz.local}") String ohPortal) {
        this.clock = clock;
        this.sites = sites;
        this.employees = employees;
        this.staff = staff;
        this.config = config;
        this.notifications = notifications;
        this.audit = audit;
        this.em = em;
        this.employeePortal = employeePortal;
        this.ohPortal = ohPortal;
    }

    // ------------------------------------------------------------------ time & sites

    public Clock clock() {
        return clock;
    }

    public Instant now() {
        return clock.instant();
    }

    public LocalDate todayUtc() {
        return LocalDate.now(clock.withZone(ZoneOffset.UTC));
    }

    public ClinicSite site(String code) {
        return sites.findById(code).orElseThrow(() -> ApiException.notFound("Clinic site"));
    }

    public Optional<ClinicSite> findSite(String code) {
        return code == null ? Optional.empty() : sites.findById(code);
    }

    public List<ClinicSite> activeSites() {
        return sites.findByActiveTrueOrderByCode();
    }

    public ZoneId zone(String siteCode) {
        return findSite(siteCode).map(s -> ZoneId.of(s.getTimeZone())).orElse(ZoneOffset.UTC);
    }

    public LocalDate today(String siteCode) {
        return LocalDate.now(clock.withZone(zone(siteCode)));
    }

    public String siteName(String code) {
        return findSite(code).map(ClinicSite::getName).orElse(code);
    }

    /** Active sites serving the employee's location (home site first), or all active sites when unknown. */
    public List<ClinicSite> homeSites(String employeeId) {
        String location = employee(employeeId).map(Employee::getLocationCode).orElse(null);
        List<ClinicSite> local = location == null ? List.of() : sites.findByLocationCodeAndActiveTrueOrderByCode(location);
        if (!local.isEmpty()) {
            return local.stream().sorted((a, b) -> Integer.compare(homeRank(a), homeRank(b))).toList();
        }
        return activeSites();
    }

    private static int homeRank(ClinicSite s) {
        return switch (s.getSiteType()) {
            case "HQ_MEDICAL_CENTRE" -> 0;
            case "AIRSIDE_POST" -> 1;
            case "MOBILE" -> 9;
            default -> 5;
        };
    }

    // ------------------------------------------------------------------ configuration

    public int intConfig(String key, int fallback) {
        return config.get(key).map(v -> {
            try {
                return Integer.parseInt(v.trim());
            } catch (NumberFormatException e) {
                return fallback;
            }
        }).orElse(fallback);
    }

    public boolean flag(String key, boolean fallback) {
        return config.flag(key, fallback);
    }

    public Optional<String> config(String key) {
        return config.get(key);
    }

    // ------------------------------------------------------------------ people (demographics only)

    public Optional<Employee> employee(String employeeId) {
        return employeeId == null ? Optional.empty() : employees.findById(employeeId);
    }

    public Optional<Employee> employeeByBadge(String badgeId) {
        return employees.findByBadgeIdAndActiveTrue(badgeId);
    }

    public Map<String, Employee> employees(Collection<String> ids) {
        Map<String, Employee> out = new LinkedHashMap<>();
        if (!ids.isEmpty()) {
            employees.findAllById(new TreeSet<>(ids)).forEach(e -> out.put(e.getEmployeeId(), e));
        }
        return out;
    }

    public String displayName(String employeeId) {
        return employee(employeeId).map(Employee::getDisplayName).orElse(employeeId);
    }

    public Optional<StaffMember> staff(String username) {
        return username == null ? Optional.empty() : staff.findById(username);
    }

    public String staffName(String username) {
        return username == null ? null : staff(username).map(StaffMember::getDisplayName).orElse(username);
    }

    public List<StaffMember> activeStaff() {
        return staff.findByActiveTrueOrderByDisplayName();
    }

    // ------------------------------------------------------------------ content-free notifications

    public Map<String, String> appointmentParams(String siteCode, Instant startsAt) {
        ZoneId zone = zone(siteCode);
        Map<String, String> p = new LinkedHashMap<>();
        p.put("appointmentDate", DATE.format(startsAt.atZone(zone)));
        p.put("appointmentTime", TIME.format(startsAt.atZone(zone)));
        p.put("site", siteName(siteCode));
        return p;
    }

    /** Employee notification (candidates without an HRIS record are not notified by e-mail here). */
    public void notifyEmployee(String employeeId, String template, Map<String, String> params) {
        employee(employeeId).filter(e -> e.getEmail() != null).ifPresent(e -> {
            Map<String, String> p = new LinkedHashMap<>(params);
            String name = e.getDisplayName();
            p.putIfAbsent("firstName", name == null || name.isBlank() ? "colleague" : name.split(" ")[0]);
            p.putIfAbsent("portalLink", employeePortal + "/#/appointments");
            notifications.enqueue(e.getEmployeeId(), e.getEmail(), Channel.EMAIL, Priority.NORMAL, template, p, ZoneOffset.UTC);
        });
    }

    public void notifyStaff(StaffMember s, String template, Map<String, String> params, Priority priority) {
        if (s.getEmail() == null) {
            return;
        }
        Map<String, String> p = new LinkedHashMap<>(params);
        p.putIfAbsent("portalLink", ohPortal + "/#/scheduling/equipment");
        notifications.enqueue(s.getUsername(), s.getEmail(), Channel.EMAIL, priority, template, p, ZoneOffset.UTC);
    }

    public void notifyAddress(String recipientId, String address, String template, Map<String, String> params) {
        Map<String, String> p = new LinkedHashMap<>(params);
        p.putIfAbsent("portalLink", ohPortal + "/#/scheduling/equipment");
        notifications.enqueue(recipientId, address, Channel.EMAIL, Priority.NORMAL, template, p, ZoneOffset.UTC);
    }

    // ------------------------------------------------------------------ audit

    public void audit(String action, String resourceType, String resourceId) {
        audit.record(action, resourceType, resourceId, AuditService.Outcome.SUCCESS, null);
    }

    // ------------------------------------------------------------------ concurrency

    /**
     * Transaction-scoped advisory locks (sorted to avoid deadlocks) that serialise bookings touching the same site,
     * clinician or employee, so the conflict check and the insert are atomic across application replicas.
     */
    public void lock(Collection<String> keys) {
        for (String k : new TreeSet<>(keys.stream().filter(java.util.Objects::nonNull).toList())) {
            em.createNativeQuery("select pg_advisory_xact_lock(cast(hashtext(:k) as bigint))").setParameter("k", "e14:" + k).getSingleResult();
        }
    }

    // ------------------------------------------------------------------ csv columns

    public static List<String> csv(String value) {
        return value == null || value.isBlank() ? List.of()
                : Arrays.stream(value.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    }

    public static String csv(Collection<String> values) {
        return values == null || values.isEmpty() ? null : String.join(",", values);
    }
}
