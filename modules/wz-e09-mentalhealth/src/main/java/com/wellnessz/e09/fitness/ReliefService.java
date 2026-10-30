package com.wellnessz.e09.fitness;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.wellnessz.casekernel.CaseService;
import com.wellnessz.casekernel.FitnessFeed;
import com.wellnessz.casekernel.model.FitnessStatus;
import com.wellnessz.casekernel.model.OhCase;
import com.wellnessz.common.error.ApiException;
import com.wellnessz.common.model.Roles;
import com.wellnessz.e09.casework.SupportCaseService;
import com.wellnessz.e09.model.E09ReliefRepository;
import com.wellnessz.e09.model.MentalHealthEnums.Concern;
import com.wellnessz.e09.model.MentalHealthEnums.Source;
import com.wellnessz.e09.model.MentalHealthEnums.SupportStatus;
import com.wellnessz.e09.model.Relief;
import com.wellnessz.e09.model.SupportCase;
import com.wellnessz.e09.support.MhSupport;
import com.wellnessz.e09.support.SupportAccess;
import com.wellnessz.security.audit.AuditService;
import com.wellnessz.security.identity.CurrentUser;

/**
 * Temporary relief from (flight) duties (E09-BL-008): the restricted kernel case carries the fitness status and the kernel
 * publishes the status-only roster feed (UNFIT with an expected end, PENDING without) – rostering never learns why. The AME
 * decides the return (FIT) or an extension.
 */
@Service
public class ReliefService {

    private final E09ReliefRepository reliefs;
    private final SupportCaseService cases;
    private final CaseService kernel;
    private final SupportAccess access;
    private final MhSupport support;
    private final AuditService audit;

    public ReliefService(E09ReliefRepository reliefs, SupportCaseService cases, CaseService kernel, SupportAccess access, MhSupport support,
                         AuditService audit) {
        this.reliefs = reliefs;
        this.cases = cases;
        this.kernel = kernel;
        this.access = access;
        this.support = support;
        this.audit = audit;
    }

    public record ReliefView(UUID id, String employeeId, String displayName, String supportCaseRef, LocalDate from, LocalDate expectedUntil,
                             String status, String rosterStatus, String decision, String decidedBy, Instant decidedAt, String createdBy,
                             Instant createdAt) {
    }

    @Transactional
    public ReliefView start(CurrentUser user, String employeeId, LocalDate from, LocalDate expectedUntil) {
        access.requireNamedOr(user, "SUPPORT_RELIEF_START", employeeId, Roles.AME);
        support.employee(employeeId).orElseThrow(() -> ApiException.notFound("Employee"));
        if (expectedUntil != null && expectedUntil.isBefore(from)) {
            throw MhSupport.unprocessable("invalid_period", "The expected end lies before the start");
        }
        if (reliefs.findByEmployeeIdAndStatus(employeeId, "ACTIVE").isPresent()) {
            throw ApiException.conflict("relief_active", "The employee is already relieved from duties");
        }
        SupportCase c = cases.openOrCreate(employeeId, Source.CLINICIAN, Concern.OTHER, user.username());
        Instant now = support.now();
        Relief r = Relief.create();
        r.setSupportCaseId(c.getId());
        r.setEmployeeId(employeeId);
        r.setFromDate(from);
        r.setExpectedUntil(expectedUntil);
        r.setStatus("ACTIVE");
        r.setCreatedBy(user.username());
        r.setCreatedAt(now);
        reliefs.save(r);
        c.setStatus(SupportStatus.RELIEF.name());
        c.setUpdatedAt(now);
        FitnessStatus f = expectedUntil == null ? FitnessStatus.UNFIT_PENDING : FitnessStatus.TEMPORARILY_UNFIT;
        publish(c, f, expectedUntil, from);
        cases.event(c, "RELIEF_STARTED", "Relieved from duties from " + from + (expectedUntil == null ? "" : " until " + expectedUntil), user.username());
        audit.record("SUPPORT_RELIEF_START", SupportAccess.RESOURCE, c.getId().toString(), AuditService.Outcome.SUCCESS, f.name());
        return view(r, c, FitnessFeed.rosterStatus(f));
    }

    @Transactional
    public ReliefView decide(CurrentUser user, UUID id, String decision, LocalDate until) {
        access.requireRoles(user, "SUPPORT_RELIEF_RETURN", id.toString(), Roles.AME);
        Relief r = reliefs.findById(id).orElseThrow(() -> ApiException.notFound("Relief"));
        if (!"ACTIVE".equals(r.getStatus())) {
            throw ApiException.conflict("relief_ended", "The relief has already ended");
        }
        SupportCase c = cases.load(r.getSupportCaseId());
        Instant now = support.now();
        FitnessStatus f;
        if ("FIT".equals(decision)) {
            r.setStatus("RETURNED");
            f = FitnessStatus.FIT;
            if (SupportStatus.RELIEF.name().equals(c.getStatus())) {
                c.setStatus(SupportStatus.ACTIVE.name());
            }
            publish(c, f, null, support.today());
        } else {
            if (until == null || !until.isAfter(support.today())) {
                throw MhSupport.unprocessable("until_required", "An extension needs a future end date");
            }
            r.setExpectedUntil(until);
            f = FitnessStatus.TEMPORARILY_UNFIT;
            publish(c, f, until, support.today());
        }
        r.setDecision(decision);
        r.setDecidedBy(user.username());
        r.setDecidedAt(now);
        c.setUpdatedAt(now);
        cases.event(c, "RELIEF_DECISION", "Relief decision: " + decision + (until == null ? "" : " until " + until), user.username());
        audit.record("SUPPORT_RELIEF_RETURN", SupportAccess.RESOURCE, c.getId().toString(), AuditService.Outcome.SUCCESS, decision);
        return view(r, c, FitnessFeed.rosterStatus(f));
    }

    @Transactional(readOnly = true)
    public List<ReliefView> list(CurrentUser user, String status) {
        access.requireNamedOr(user, "SUPPORT_RELIEF_LIST", null, Roles.AME);
        return reliefs.findByStatusOrderByFromDateDesc(status == null ? "ACTIVE" : status).stream().map(r -> {
            SupportCase c = cases.load(r.getSupportCaseId());
            String roster = cases.kernelCase(c).map(k -> FitnessFeed.rosterStatus(k.getFitnessStatus())).orElse(null);
            return view(r, c, roster);
        }).toList();
    }

    private void publish(SupportCase c, FitnessStatus f, LocalDate until, LocalDate validFrom) {
        OhCase k = cases.kernelCase(c).orElseThrow(() -> ApiException.notFound("Case"));
        k.recordFitness(f, until, support.now());
        kernel.publishFitness(k, new FitnessFeed(validFrom, until, null, List.of()));
    }

    private ReliefView view(Relief r, SupportCase c, String rosterStatus) {
        return new ReliefView(r.getId(), r.getEmployeeId(), support.name(r.getEmployeeId()), c.getCaseRef(), r.getFromDate(), r.getExpectedUntil(),
                r.getStatus(), rosterStatus, r.getDecision(), r.getDecidedBy(), r.getDecidedAt(), r.getCreatedBy(), r.getCreatedAt());
    }
}
