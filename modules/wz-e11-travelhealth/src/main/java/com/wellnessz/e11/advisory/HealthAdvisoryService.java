package com.wellnessz.e11.advisory;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.wellnessz.common.error.ApiException;
import com.wellnessz.e11.model.Advisory;
import com.wellnessz.e11.model.AdvisoryTarget;
import com.wellnessz.e11.model.E11AdvisoryRepository;
import com.wellnessz.e11.model.E11AdvisoryTargetRepository;
import com.wellnessz.e11.support.TravelHealthSupport;
import com.wellnessz.masterdata.DutyAssignmentRepository;
import com.wellnessz.masterdata.Employee;
import com.wellnessz.security.audit.AuditService;
import com.wellnessz.security.identity.CurrentUser;

/**
 * Destination / layover and base health advisories (E11-F05): authored by OH physicians / AMEs, targeted at crew rostered to
 * the destination inside the validity window (duty assignments) plus base staff with the given job codes, published with
 * {@code advisory.published} and acknowledged in the employee app. Acknowledgement statistics are small-cell suppressed.
 * The body is general destination health advice written for staff, never personal health information.
 */
@Service
public class HealthAdvisoryService {

    private final E11AdvisoryRepository advisories;
    private final E11AdvisoryTargetRepository targets;
    private final DutyAssignmentRepository duties;
    private final TravelHealthSupport support;
    private final AuditService audit;

    public HealthAdvisoryService(E11AdvisoryRepository advisories, E11AdvisoryTargetRepository targets, DutyAssignmentRepository duties,
                                 TravelHealthSupport support, AuditService audit) {
        this.advisories = advisories;
        this.targets = targets;
        this.duties = duties;
        this.support = support;
        this.audit = audit;
    }

    public record AdvisoryCommand(@NotBlank @Size(max = 200) String title, @Pattern(regexp = "[A-Z0-9_\\-]{2,40}") String destinationCode,
                                  @Pattern(regexp = "[A-Z0-9_\\-]{2,40}") String baseCode,
                                  @Size(max = 20) List<@NotNull @Pattern(regexp = "[A-Za-z0-9_\\-]{1,64}") String> roles,
                                  @NotBlank @Pattern(regexp = "INFO|WARNING|CRITICAL") String severity, @NotBlank @Size(max = 4000) String body,
                                  @NotNull LocalDate validFrom, @NotNull LocalDate validUntil) {
    }

    public record AdvisoryView(UUID id, String title, String destinationCode, String baseCode, List<String> roles, String severity, String body,
                               LocalDate validFrom, LocalDate validUntil, String publishedBy, Instant publishedAt, Integer targeted,
                               Integer acknowledged, Double ratePct, boolean suppressed) {
    }

    public record PendingView(String employeeId, String displayName, String reason, String dutyRef, Instant notifiedAt) {
    }

    public record AcknowledgementView(UUID advisoryId, int targeted, Integer acknowledged, Double ratePct, boolean suppressed, List<PendingView> pending) {
    }

    public record MyAdvisory(UUID id, String title, String destinationCode, String baseCode, String severity, String body, LocalDate validFrom,
                             LocalDate validUntil, Instant acknowledgedAt) {
    }

    AdvisoryView view(Advisory a) {
        List<AdvisoryTarget> ts = targets.findByAdvisoryId(a.getId());
        int targeted = ts.size();
        int acked = (int) ts.stream().filter(t -> t.getAcknowledgedAt() != null).count();
        boolean suppressed = targeted < support.minGroupSize();
        return new AdvisoryView(a.getId(), a.getTitle(), a.getDestinationCode(), a.getBaseCode(), TravelHealthSupport.csv(a.getRoles()), a.getSeverity(),
                a.getBody(), a.getValidFrom(), a.getValidUntil(), a.getPublishedBy(), a.getPublishedAt(), suppressed ? null : targeted,
                suppressed ? null : acked, suppressed ? null : AdvisoryTargeting.rate(acked, targeted), suppressed);
    }

    @Transactional
    public AdvisoryView publish(CurrentUser user, AdvisoryCommand cmd) {
        if (cmd.destinationCode() == null && cmd.baseCode() == null) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "target_required", "Give a destination or a base");
        }
        LocalDate today = support.today();
        if (cmd.validUntil().isBefore(cmd.validFrom()) || cmd.validUntil().isBefore(today) || cmd.validUntil().isAfter(cmd.validFrom().plusDays(366))) {
            throw ApiException.badRequest("invalid_dates", "The validity must end after it starts, not in the past and within a year");
        }
        Advisory a = Advisory.create();
        a.setTitle(cmd.title().strip());
        a.setDestinationCode(cmd.destinationCode());
        a.setBaseCode(cmd.baseCode());
        a.setRoles(TravelHealthSupport.joinCsv(cmd.roles() == null ? List.of() : cmd.roles().stream().distinct().toList()));
        a.setSeverity(cmd.severity());
        a.setBody(cmd.body().strip());
        a.setValidFrom(cmd.validFrom());
        a.setValidUntil(cmd.validUntil());
        a.setPublishedBy(user.username());
        a.setPublishedAt(support.now());
        advisories.save(a);
        int n = target(a);
        audit.record("ADVISORY_PUBLISH", "ADVISORY", a.getId().toString(), AuditService.Outcome.SUCCESS, "targeted=" + n);
        return view(a);
    }

    /** Computes the targets and notifies the newly targeted people; returns the number added. */
    int target(Advisory a) {
        List<AdvisoryTargeting.RosteredDuty> rostered = new ArrayList<>();
        if (a.getDestinationCode() != null) {
            Instant from = a.getValidFrom().atStartOfDay(ZoneOffset.UTC).toInstant();
            Instant to = a.getValidUntil().plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
            duties.findToDestination(a.getDestinationCode(), from, to)
                    .forEach(d -> rostered.add(new AdvisoryTargeting.RosteredDuty(d.getEmployeeId(), d.getDutyRef())));
        }
        List<AdvisoryTargeting.StaffMember> staff = a.getBaseCode() == null ? List.of() : support.activeEmployees().stream()
                .map(e -> new AdvisoryTargeting.StaffMember(e.getEmployeeId(), e.getLocationCode(), e.getJobCode(), e.isActive())).toList();
        List<AdvisoryTargeting.Target> wanted = AdvisoryTargeting.targets(rostered, staff, a.getBaseCode(), Set.copyOf(TravelHealthSupport.csv(a.getRoles())));
        Set<String> existing = new HashSet<>();
        targets.findByAdvisoryId(a.getId()).forEach(t -> existing.add(t.getEmployeeId()));
        Map<String, Employee> people = support.employeesById(wanted.stream().map(AdvisoryTargeting.Target::employeeId).toList());
        Instant now = support.now();
        String site = a.getDestinationCode() != null ? a.getDestinationCode() : a.getBaseCode();
        int added = 0;
        for (AdvisoryTargeting.Target t : wanted) {
            if (!people.containsKey(t.employeeId()) || !existing.add(t.employeeId())) {
                continue;
            }
            AdvisoryTarget at = AdvisoryTarget.create();
            at.setAdvisoryId(a.getId());
            at.setEmployeeId(t.employeeId());
            at.setReason(t.reason());
            at.setDutyRef(t.dutyRef());
            at.setNotifiedAt(now);
            targets.save(at);
            support.notifyEmployee(t.employeeId(), "advisory.published", Map.of("site", site), "/travel");
            added++;
        }
        return added;
    }

    /** Daily: adds crew newly rostered to the destination of advisories that are still valid. */
    @Transactional
    public int retarget(LocalDate today) {
        int added = 0;
        for (Advisory a : advisories.findByValidUntilGreaterThanEqual(today)) {
            added += target(a);
        }
        return added;
    }

    @Transactional(readOnly = true)
    public List<AdvisoryView> list() {
        return advisories.findAllByOrderByPublishedAtDesc().stream().map(this::view).toList();
    }

    @Transactional(readOnly = true)
    public AdvisoryView get(UUID id) {
        return view(advisories.findById(id).orElseThrow(() -> ApiException.notFound("Advisory")));
    }

    @Transactional(readOnly = true)
    public AcknowledgementView acknowledgements(UUID id) {
        Advisory a = advisories.findById(id).orElseThrow(() -> ApiException.notFound("Advisory"));
        List<AdvisoryTarget> ts = targets.findByAdvisoryId(a.getId());
        int targeted = ts.size();
        int acked = (int) ts.stream().filter(t -> t.getAcknowledgedAt() != null).count();
        boolean suppressed = targeted < support.minGroupSize();
        Map<String, Employee> people = support.employeesById(ts.stream().map(AdvisoryTarget::getEmployeeId).toList());
        // the follow-up list is operational (OH chases outstanding acknowledgements); the rate is a statistic and suppressed
        List<PendingView> pending = ts.stream().filter(t -> t.getAcknowledgedAt() == null)
                .map(t -> new PendingView(t.getEmployeeId(), people.containsKey(t.getEmployeeId()) ? people.get(t.getEmployeeId()).getDisplayName()
                        : t.getEmployeeId(), t.getReason(), t.getDutyRef(), t.getNotifiedAt())).toList();
        audit.record("ADVISORY_ACKNOWLEDGEMENTS_READ", "ADVISORY", id.toString(), AuditService.Outcome.SUCCESS, "pending=" + pending.size());
        return new AcknowledgementView(a.getId(), targeted, suppressed ? null : acked, suppressed ? null : AdvisoryTargeting.rate(acked, targeted),
                suppressed, pending);
    }

    static MyAdvisory mine(Advisory a, AdvisoryTarget t) {
        return new MyAdvisory(a.getId(), a.getTitle(), a.getDestinationCode(), a.getBaseCode(), a.getSeverity(), a.getBody(), a.getValidFrom(),
                a.getValidUntil(), t.getAcknowledgedAt());
    }

    @Transactional(readOnly = true)
    public List<MyAdvisory> mine(CurrentUser user) {
        LocalDate today = support.today();
        List<MyAdvisory> out = new ArrayList<>();
        for (AdvisoryTarget t : targets.findByEmployeeId(user.employeeId())) {
            advisories.findById(t.getAdvisoryId()).filter(a -> !a.getValidUntil().isBefore(today)).ifPresent(a -> out.add(mine(a, t)));
        }
        out.sort(java.util.Comparator.comparing(MyAdvisory::validFrom).reversed());
        return out;
    }

    @Transactional
    public MyAdvisory acknowledge(CurrentUser user, UUID id) {
        AdvisoryTarget t = targets.findByAdvisoryIdAndEmployeeId(id, user.employeeId()).orElseThrow(() -> ApiException.notFound("Advisory"));
        Advisory a = advisories.findById(id).orElseThrow(() -> ApiException.notFound("Advisory"));
        if (t.getAcknowledgedAt() == null) {
            t.setAcknowledgedAt(support.now());
            targets.save(t);
            audit.record("ADVISORY_ACKNOWLEDGE", "ADVISORY", id.toString(), AuditService.Outcome.SUCCESS, null);
        }
        return mine(a, t);
    }
}
