package com.wellnessz.e02.sla;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import com.wellnessz.casekernel.model.OhCase;
import com.wellnessz.casekernel.model.OhCaseRepository;
import com.wellnessz.casekernel.support.CaseTimeline;
import com.wellnessz.e02.E02Events;
import com.wellnessz.e02.model.SlaPolicy;
import com.wellnessz.e02.model.SlaPolicyRepository;
import com.wellnessz.e02.model.SlaTimer;
import com.wellnessz.e02.model.SlaTimerRepository;
import com.wellnessz.integration.outbox.EventBus;

/**
 * SLA timers per case milestone (E01-BL-023, E02-F04): start at intake, amber at a configurable percentage, breach →
 * Nursing Head notification and ESCALATED flag. Timers pause while the case waits for an external party or the
 * employee. Durations are elapsed minutes; "1 working day" is configured as 480 minutes (business calendar is a
 * planned E18 enhancement).
 */
@Service
public class SlaService {

    public static final String TRIAGE = "TRIAGE";
    public static final String ESCALATION_RESPONSE = "ESCALATION_RESPONSE";
    private static final Logger log = LoggerFactory.getLogger(SlaService.class);

    private final SlaPolicyRepository policies;
    private final SlaTimerRepository timers;
    private final OhCaseRepository cases;
    private final CaseTimeline timeline;
    private final EventBus bus;
    private final TransactionTemplate tx;
    private final Clock clock;

    public SlaService(SlaPolicyRepository policies, SlaTimerRepository timers, OhCaseRepository cases, CaseTimeline timeline,
                      EventBus bus, TransactionTemplate tx, Clock clock) {
        this.policies = policies;
        this.timers = timers;
        this.cases = cases;
        this.timeline = timeline;
        this.bus = bus;
        this.tx = tx;
        this.clock = clock;
    }

    public enum State { ON_TRACK, AT_RISK, BREACHED, MET, NONE, PAUSED }

    public record SlaView(String milestone, State state, Instant dueAt, Long minutesRemaining) {
        public static final SlaView NONE = new SlaView(null, State.NONE, null, null);
    }

    @Transactional
    public SlaTimer start(OhCase c, String milestone) {
        return start(c.getId(), milestone, c.getUrgency().name(), c.getCaseType().name(), null);
    }

    /** Starts (or restarts) a milestone timer; {@code overrideMinutes} lets escalations pass their own target. */
    @Transactional
    public SlaTimer start(UUID caseId, String milestone, String urgency, String caseType, Integer overrideMinutes) {
        cancel(caseId, milestone);
        int minutes = overrideMinutes != null ? overrideMinutes : policy(milestone, urgency, caseType).map(SlaPolicy::getTargetMinutes).orElse(480);
        int amberPct = policy(milestone, urgency, caseType).map(SlaPolicy::getAmberPercent).orElse(75);
        Instant now = clock.instant();
        SlaTimer t = new SlaTimer();
        t.setId(UUID.randomUUID());
        t.setCaseId(caseId);
        t.setMilestone(milestone);
        t.setStartedAt(now);
        t.setDueAt(now.plus(Duration.ofMinutes(minutes)));
        t.setAmberAt(now.plusSeconds(minutes * 60L * amberPct / 100));
        t.setStatus("RUNNING");
        return timers.save(t);
    }

    /** Marks the running milestone as met (a breached timer keeps its BREACHED outcome for reporting). */
    @Transactional
    public void stop(UUID caseId, String milestone) {
        for (SlaTimer t : open(caseId)) {
            if (t.getMilestone().equals(milestone)) {
                t.setStoppedAt(clock.instant());
                if (!"BREACHED".equals(t.getStatus())) {
                    t.setStatus(t.getDueAt().isBefore(clock.instant()) ? "BREACHED" : "MET");
                }
            }
        }
    }

    @Transactional
    public void cancel(UUID caseId, String milestone) {
        for (SlaTimer t : open(caseId)) {
            if (t.getMilestone().equals(milestone) && t.getStoppedAt() == null) {
                t.setStatus("BREACHED".equals(t.getStatus()) ? "BREACHED" : "CANCELLED");
                t.setStoppedAt(clock.instant());
            }
        }
    }

    @Transactional
    public void cancelAll(UUID caseId) {
        open(caseId).forEach(t -> cancel(caseId, t.getMilestone()));
    }

    /** Waiting for an external party / the employee pauses the clock (E02-HLR-04). */
    @Transactional
    public void pause(UUID caseId) {
        for (SlaTimer t : open(caseId)) {
            if ("RUNNING".equals(t.getStatus())) {
                t.setPausedRemainingSeconds(Math.max(0, Duration.between(clock.instant(), t.getDueAt()).toSeconds()));
                t.setStatus("PAUSED");
            }
        }
    }

    @Transactional
    public void resume(UUID caseId) {
        for (SlaTimer t : open(caseId)) {
            if ("PAUSED".equals(t.getStatus())) {
                long remaining = t.getPausedRemainingSeconds() == null ? 0 : t.getPausedRemainingSeconds();
                long total = Duration.between(t.getStartedAt(), t.getDueAt()).toSeconds();
                Instant now = clock.instant();
                t.setDueAt(now.plusSeconds(remaining));
                t.setAmberAt(now.plusSeconds(Math.max(0, remaining - total / 4)));
                t.setPausedRemainingSeconds(null);
                t.setStatus("RUNNING");
            }
        }
    }

    public SlaView view(UUID caseId) {
        List<SlaTimer> all = timers.findByCaseIdOrderByStartedAtDesc(caseId);
        Optional<SlaTimer> current = all.stream().filter(t -> t.getStoppedAt() == null)
                .min(Comparator.comparing(SlaTimer::getDueAt));
        if (current.isEmpty()) {
            return all.stream().findFirst().map(t -> new SlaView(t.getMilestone(),
                    "BREACHED".equals(t.getStatus()) ? State.BREACHED : "MET".equals(t.getStatus()) ? State.MET : State.NONE,
                    t.getDueAt(), null)).orElse(SlaView.NONE);
        }
        return view(current.get(), clock.instant());
    }

    public static SlaView view(SlaTimer t, Instant now) {
        if ("PAUSED".equals(t.getStatus())) {
            return new SlaView(t.getMilestone(), State.PAUSED, null,
                    t.getPausedRemainingSeconds() == null ? null : t.getPausedRemainingSeconds() / 60);
        }
        long remaining = Duration.between(now, t.getDueAt()).toMinutes();
        State s = "BREACHED".equals(t.getStatus()) || now.isAfter(t.getDueAt()) ? State.BREACHED
                : !now.isBefore(t.getAmberAt()) ? State.AT_RISK : State.ON_TRACK;
        return new SlaView(t.getMilestone(), s, t.getDueAt(), remaining);
    }

    private List<SlaTimer> open(UUID caseId) {
        return timers.findByCaseIdAndStatusIn(caseId, List.of("RUNNING", "PAUSED", "BREACHED")).stream()
                .filter(t -> t.getStoppedAt() == null).toList();
    }

    private Optional<SlaPolicy> policy(String milestone, String urgency, String caseType) {
        List<SlaPolicy> candidates = policies.findByMilestoneAndUrgency(milestone, urgency);
        return candidates.stream().filter(p -> caseType != null && caseType.equals(p.getCaseType())).findFirst()
                .or(() -> candidates.stream().filter(p -> p.getCaseType() == null).findFirst());
    }

    /** Amber and breach detection; SKIP LOCKED makes it safe on every replica. */
    @Scheduled(fixedDelayString = "${wellnessz.triage.sla.poll-interval:PT60S}")
    public void monitor() {
        tx.executeWithoutResult(s -> monitorBatch());
    }

    void monitorBatch() {
        Instant now = clock.instant();
        for (SlaTimer t : timers.claimDue(now)) {
            OhCase c = cases.findById(t.getCaseId()).orElse(null);
            if (c == null || !c.isOpen()) {
                t.setStatus("CANCELLED");
                t.setStoppedAt(now);
                continue;
            }
            if (!now.isBefore(t.getDueAt())) {
                t.setStatus("BREACHED");
                t.setBreachedAt(now);
                c.setEscalated(true, now);
                timeline.record(c.getId(), "SLA_BREACHED", t.getMilestone() + " SLA breached – escalated to Nursing Head");
                bus.publish(new E02Events.SlaAlert(c.getId().toString(), c.getCaseRef(), t.getMilestone(), "BREACHED", "sla.breached.v1"));
                log.info("SLA breached case={} milestone={}", c.getCaseRef(), t.getMilestone());
            } else if (!t.isAmberNotified()) {
                t.setAmberNotified(true);
                bus.publish(new E02Events.SlaAlert(c.getId().toString(), c.getCaseRef(), t.getMilestone(), "AT RISK", "sla.at-risk.v1"));
            }
        }
    }
}
