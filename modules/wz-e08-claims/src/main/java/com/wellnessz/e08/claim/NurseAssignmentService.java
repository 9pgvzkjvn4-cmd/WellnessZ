package com.wellnessz.e08.claim;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.wellnessz.casekernel.model.OhCase;
import com.wellnessz.casekernel.model.OhCaseRepository;
import com.wellnessz.casekernel.support.CaseTimeline;
import com.wellnessz.common.error.ApiException;
import com.wellnessz.common.model.DataClassification;
import com.wellnessz.common.model.Roles;
import com.wellnessz.e02.routing.AssignmentEngine;
import com.wellnessz.e02.routing.AssignmentService;
import com.wellnessz.e08.E08Events;
import com.wellnessz.e08.model.Claim;
import com.wellnessz.e08.model.ClaimHistory;
import com.wellnessz.e08.model.E08ClaimHistoryRepository;
import com.wellnessz.e08.model.E08ClaimRepository;
import com.wellnessz.e08.support.ClaimsSupport;
import com.wellnessz.integration.outbox.EventBus;
import com.wellnessz.masterdata.StaffMember;
import com.wellnessz.security.identity.CurrentUser;

/**
 * Automatic nurse case manager for lost-time claims (E08-F04): chosen by {@link NurseAssignment}, assigned through the E02
 * assignment service as a system actor (case owner change with history and timeline), notified with {@code claim.assigned}.
 * KPI: share of lost-time claims assigned within the SLA ({@code claims.nurse-sla-hours}, 48 h).
 */
@Service
public class NurseAssignmentService {

    private static final Logger log = LoggerFactory.getLogger(NurseAssignmentService.class);
    /** System actor used for the E02 assignment (OH nursing-head authority, no person behind it). */
    static final CurrentUser SYSTEM = new CurrentUser("system", "claims-automation", null, "Claims automation", null, Set.of(Roles.NURSING_HEAD));

    private final ClaimsSupport support;
    private final AssignmentEngine engine;
    private final AssignmentService assignments;
    private final OhCaseRepository cases;
    private final E08ClaimRepository claims;
    private final E08ClaimHistoryRepository history;
    private final CaseTimeline timeline;
    private final EventBus bus;

    public NurseAssignmentService(ClaimsSupport support, AssignmentEngine engine, AssignmentService assignments, OhCaseRepository cases,
                                  E08ClaimRepository claims, E08ClaimHistoryRepository history, CaseTimeline timeline, EventBus bus) {
        this.support = support;
        this.engine = engine;
        this.assignments = assignments;
        this.cases = cases;
        this.claims = claims;
        this.history = history;
        this.timeline = timeline;
        this.bus = bus;
    }

    /** Assigns a nurse case manager when the claim is lost-time and has none yet; returns the chosen username. */
    @Transactional
    public Optional<String> assignIfNeeded(Claim c, String actor) {
        if (!c.isLostTime() || c.getNurseUsername() != null) {
            return Optional.empty();
        }
        OhCase oc = cases.findById(c.getCaseId()).orElse(null);
        LocalDate today = support.today();
        List<NurseAssignment.Candidate> staff = new ArrayList<>();
        NurseAssignment.Candidate owner = null;
        for (StaffMember s : support.activeStaff()) {
            NurseAssignment.Candidate cand = new NurseAssignment.Candidate(s.getUsername(), new HashSet<>(StaffMember.list(s.getRoles())),
                    s.getCaseloadCap(), s.isAvailableOn(today));
            staff.add(cand);
            if (oc != null && s.getUsername().equals(oc.getOwnerUsername())) {
                owner = cand;
            }
        }
        Optional<NurseAssignment.Choice> choice = NurseAssignment.choose(owner, staff, engine.openCaseload());
        if (choice.isEmpty()) {
            record(c, "NURSE_UNASSIGNED", "No case manager available within caseload cap", actor);
            return Optional.empty();
        }
        String username = choice.get().username();
        if (!choice.get().caseOwner() && oc != null && oc.getClassification() != DataClassification.RESTRICTED_CLINICAL) {
            try {
                assignments.assign(SYSTEM, oc.getId(), username, null, "Nurse case manager for lost-time claim " + c.getClaimRef());
            } catch (ApiException e) {
                // e.g. restricted-class case: the claim still records its nurse; OH routes the case manually
                log.info("E08 nurse assignment: case owner not changed ({})", e.code());
            }
        }
        Instant now = support.now();
        c.setNurseUsername(username);
        c.setNurseAssignedAt(now);
        c.setUpdatedAt(now);
        claims.save(c);
        record(c, "NURSE_ASSIGNED", "Nurse case manager " + username, actor);
        if (oc != null) {
            timeline.record(oc.getId(), "CLAIM_NURSE_ASSIGNED", "Nurse case manager assigned for claim " + c.getClaimRef());
        }
        bus.publish(new E08Events.NurseAssigned(c.getId().toString(), username));
        support.notifyStaff(username, "claim.assigned", Map.of("reference", c.getClaimRef(), "caseRef", c.getCaseRef()),
                "#/claims/" + c.getId());
        return Optional.of(username);
    }

    private void record(Claim c, String type, String reason, String actor) {
        ClaimHistory h = ClaimHistory.create();
        h.setClaimId(c.getId());
        h.setEventType(type);
        h.setToStatus(c.getStatus());
        h.setReason(reason);
        h.setActor(actor);
        h.setOccurredAt(support.now());
        history.save(h);
    }

    // ------------------------------------------------------------------ KPI

    public record Unassigned(UUID claimId, String claimRef, Instant notifiedAt) {
    }

    public record AssignmentKpi(int lostTimeClaims, int assignedWithin48h, Double pct, List<Unassigned> unassigned) {
    }

    @Transactional(readOnly = true)
    public AssignmentKpi kpi(LocalDate from, LocalDate to) {
        LocalDate f = from == null ? support.today().minusDays(90) : from;
        LocalDate t = to == null ? support.today() : to;
        if (t.isBefore(f)) {
            throw ApiException.badRequest("invalid_range", "'to' must not be before 'from'");
        }
        Duration sla = Duration.ofHours(support.intConfig("claims.nurse-sla-hours", 48));
        int total = 0;
        int within = 0;
        List<Unassigned> open = new ArrayList<>();
        for (Claim c : claims.findByLostTimeTrueOrderByNotifiedAtDesc()) {
            Instant anchor = c.getLostTimeAt() != null ? c.getLostTimeAt() : c.getNotifiedAt();
            LocalDate day = anchor.atZone(ZoneOffset.UTC).toLocalDate();
            if (day.isBefore(f) || day.isAfter(t)) {
                continue;
            }
            total++;
            if (c.getNurseAssignedAt() != null && !Duration.between(anchor, c.getNurseAssignedAt()).minus(sla).isPositive()) {
                within++;
            }
            if (c.getNurseUsername() == null) {
                open.add(new Unassigned(c.getId(), c.getClaimRef(), c.getNotifiedAt()));
            }
        }
        Double pct = total == 0 ? null : Math.round(1000.0 * within / total) / 10.0;
        return new AssignmentKpi(total, within, pct, open);
    }
}
