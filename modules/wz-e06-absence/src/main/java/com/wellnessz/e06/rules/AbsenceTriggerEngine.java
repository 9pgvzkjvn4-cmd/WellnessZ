package com.wellnessz.e06.rules;

import java.time.Duration;
import java.time.LocalDate;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.wellnessz.casekernel.CaseService;
import com.wellnessz.casekernel.model.CaseCategory;
import com.wellnessz.casekernel.model.CaseSource;
import com.wellnessz.casekernel.model.CaseType;
import com.wellnessz.casekernel.model.FitnessStatus;
import com.wellnessz.casekernel.model.OhCase;
import com.wellnessz.casekernel.model.OpenCaseCommand;
import com.wellnessz.casekernel.model.Urgency;
import com.wellnessz.casekernel.support.CaseTimeline;
import com.wellnessz.e06.E06Events;
import com.wellnessz.e06.lta.LtaService;
import com.wellnessz.e06.model.AbsenceEnums.QueueStatus;
import com.wellnessz.e06.model.AbsenceEnums.RuleAction;
import com.wellnessz.e06.model.Episode;
import com.wellnessz.e06.model.E06EpisodeRepository;
import com.wellnessz.e06.model.QueueItem;
import com.wellnessz.e06.model.AbsenceQueueItemRepository;
import com.wellnessz.e06.support.AbsenceSupport;
import com.wellnessz.integration.outbox.EventBus;
import com.wellnessz.masterdata.Employee;
import com.wellnessz.security.audit.AuditService;

/**
 * Applies the trigger rules (E06-F02) on every ingested / updated episode and nightly: raises at most one open queue item
 * per employee and action; LONG_TERM starts the long-term absence pathway, AME_REVIEW opens a FITNESS case with
 * UNFIT_PENDING and publishes the status-only crew roster feed.
 */
@Service
public class AbsenceTriggerEngine {

    private final AbsenceRuleService rules;
    private final AbsenceFacts facts;
    private final E06EpisodeRepository episodes;
    private final AbsenceQueueItemRepository queue;
    private final LtaService lta;
    private final CaseService cases;
    private final CaseTimeline timeline;
    private final EventBus bus;
    private final AbsenceSupport support;
    private final AuditService audit;

    public AbsenceTriggerEngine(AbsenceRuleService rules, AbsenceFacts facts, E06EpisodeRepository episodes, AbsenceQueueItemRepository queue, LtaService lta,
                                CaseService cases, CaseTimeline timeline, EventBus bus, AbsenceSupport support, AuditService audit) {
        this.rules = rules;
        this.facts = facts;
        this.episodes = episodes;
        this.queue = queue;
        this.lta = lta;
        this.cases = cases;
        this.timeline = timeline;
        this.bus = bus;
        this.support = support;
        this.audit = audit;
    }

    public record Run(int employees, int raised) {
    }

    /** Evaluates one employee; returns the number of queue items raised. */
    @Transactional
    public int evaluate(String employeeId, LocalDate asOf) {
        Employee e = support.employee(employeeId).orElse(null);
        if (e == null) {
            return 0;
        }
        int raised = 0;
        for (RuleEngine.Match m : RuleEngine.evaluateAll(rules.activeRules(), facts.facts(e), asOf)) {
            if (raise(e, m, asOf)) {
                raised++;
            }
        }
        return raised;
    }

    /** Nightly {@code absence-triggers}: everyone with an open or recently ended episode. */
    @Transactional
    public Run evaluateAll(LocalDate asOf) {
        Set<String> population = new LinkedHashSet<>(episodes.findEmployeesActiveSince(asOf.minusDays(RuleEngine.RECENT_DAYS)));
        int raised = 0;
        for (String id : population) {
            raised += evaluate(id, asOf);
        }
        return new Run(population.size(), raised);
    }

    private boolean raise(Employee e, RuleEngine.Match m, LocalDate asOf) {
        String action = m.rule().action();
        if (queue.findFirstByEmployeeIdAndActionAndStatusNot(e.getEmployeeId(), action, QueueStatus.CLOSED.name()).isPresent()) {
            return false;
        }
        if (RuleAction.LONG_TERM.name().equals(action) && lta.active(e.getEmployeeId()).isPresent()) {
            return false;
        }
        Optional<Episode> ep = m.episodeId() == null ? Optional.empty() : episodes.findById(m.episodeId());
        QueueItem q = QueueItem.create();
        q.setEmployeeId(e.getEmployeeId());
        q.setRuleId(m.rule().id());
        q.setRuleCode(m.rule().code());
        q.setRuleName(m.rule().name());
        q.setAction(action);
        q.setStatus(QueueStatus.NEW.name());
        q.setRaisedAt(support.now());
        int slaDays = RuleAction.AME_REVIEW.name().equals(action) ? support.intConfig("absence.queue.ame-sla-days", 1)
                : support.intConfig("absence.queue.sla-days", 5);
        q.setSlaDueAt(q.getRaisedAt().plus(Duration.ofDays(slaDays)));
        q.setEpisodeId(m.episodeId());
        q.setDetail(m.detail());
        if (RuleAction.LONG_TERM.name().equals(action) && ep.isPresent()) {
            OhCase c = lta.start(e, ep.get(), asOf);
            q.setCaseId(c.getId());
            q.setCaseRef(c.getCaseRef());
        } else if (RuleAction.AME_REVIEW.name().equals(action)) {
            String key = ep.map(x -> x.getId().toString()).orElse(e.getEmployeeId() + ":" + asOf);
            OhCase c = cases.openIdempotent(OpenCaseCommand.builder(e.getEmployeeId(), CaseCategory.DECREASE_IN_FITNESS, CaseSource.ABSENCE_TRIGGER)
                    .caseType(CaseType.FITNESS).sourceRef("absence-ame:" + key).urgency(Urgency.URGENT)
                    .initialFitness(FitnessStatus.UNFIT_PENDING).occurredOn(ep.map(Episode::getStartDate).orElse(asOf)).build());
            cases.publishFitness(c); // crew rostering: PENDING – status only
            timeline.record(c.getId(), "AME_REVIEW_REQUIRED", "Crew sickness absence – AME review required before return to flying duties");
            q.setCaseId(c.getId());
            q.setCaseRef(c.getCaseRef());
        }
        queue.save(q);
        bus.publish(new E06Events.TriggerRaised(q.getId().toString(), e.getEmployeeId(), action));
        audit.record("ABSENCE_TRIGGER_RAISED", "ABSENCE_QUEUE_ITEM", q.getId().toString(), AuditService.Outcome.SUCCESS, m.rule().code());
        return true;
    }

    /** Rule ids / codes used by the queue view. */
    public List<RuleEngine.Rule> activeRules() {
        return rules.activeRules();
    }
}
