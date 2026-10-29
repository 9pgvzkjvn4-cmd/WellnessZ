package com.wellnessz.e04.outcome;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.wellnessz.casekernel.model.OhCase;
import com.wellnessz.casekernel.support.CaseTimeline;
import com.wellnessz.common.error.ApiException;
import com.wellnessz.e04.model.TreatmentOutcome;
import com.wellnessz.e04.model.TreatmentOutcomeRepository;
import com.wellnessz.e04.support.TreatmentAccess;
import com.wellnessz.e04.support.TreatmentSupport;
import com.wellnessz.security.abac.AccessPolicy;
import com.wellnessz.security.audit.AuditService;
import com.wellnessz.security.identity.CurrentUser;

/** Outcome capture at BASELINE / REVIEW / CLOSURE (E04-F06): NPRS pain, functional scale, RTW date, residual impairment. */
@Service
public class OutcomeService {

    private final TreatmentOutcomeRepository outcomes;
    private final TreatmentAccess access;
    private final CaseTimeline timeline;
    private final TreatmentSupport support;
    private final AuditService audit;

    public OutcomeService(TreatmentOutcomeRepository outcomes, TreatmentAccess access, CaseTimeline timeline, TreatmentSupport support,
                          AuditService audit) {
        this.outcomes = outcomes;
        this.access = access;
        this.timeline = timeline;
        this.support = support;
        this.audit = audit;
    }

    public record OutcomeCommand(@NotBlank @Pattern(regexp = "BASELINE|REVIEW|CLOSURE") String milestone, @NotNull @Min(0) @Max(10) Integer painScore,
                                 @NotBlank @Pattern(regexp = "NONE|ODI|QUICKDASH|PSFS|LEFS") String functionScale,
                                 @NotNull @Min(0) @Max(100) Integer functionScore, LocalDate rtwDate,
                                 @Min(0) @Max(100) Integer residualImpairmentPct, @Size(max = 2000) String note) {
    }

    /** Change against the BASELINE measurement: current − baseline (pain down / function up = improvement). */
    public record Improvement(int painDelta, int functionDelta) {
    }

    public record OutcomeView(UUID id, UUID caseId, String milestone, int painScore, String functionScale, int functionScore, LocalDate rtwDate,
                              Integer residualImpairmentPct, String note, String recordedBy, Instant recordedAt, Improvement improvement) {
    }

    @Transactional
    public OutcomeView record(CurrentUser user, UUID caseId, OutcomeCommand cmd) {
        OhCase c = access.clinical(user, caseId, AccessPolicy.Action.UPDATE, "OUTCOME_RECORD");
        if ("BASELINE".equals(cmd.milestone()) && outcomes.existsByCaseIdAndMilestone(caseId, "BASELINE")) {
            throw ApiException.conflict("baseline_exists", "A baseline outcome is already recorded for this case");
        }
        TreatmentOutcome o = new TreatmentOutcome();
        o.setId(UUID.randomUUID());
        o.setCaseId(c.getId());
        o.setEmployeeId(c.getEmployeeId());
        o.setMilestone(cmd.milestone());
        o.setPainScore(cmd.painScore());
        o.setFunctionScale(cmd.functionScale());
        o.setFunctionScore(cmd.functionScore());
        o.setRtwDate(cmd.rtwDate());
        o.setResidualImpairmentPct(cmd.residualImpairmentPct());
        o.setNote(TreatmentSupport.clinical("note", cmd.note(), 2000));
        o.setRecordedBy(user.username());
        o.setRecordedAt(support.now());
        outcomes.save(o);
        timeline.record(c.getId(), "OUTCOME_RECORDED", "Outcome recorded (" + cmd.milestone() + ")");
        audit.record("OUTCOME_RECORD", "CASE", caseId.toString(), AuditService.Outcome.SUCCESS, cmd.milestone());
        return view(o, outcomes.findByCaseIdOrderByRecordedAtAsc(caseId).stream().filter(x -> "BASELINE".equals(x.getMilestone())).findFirst()
                .orElse(null));
    }

    /** Outcomes of the case, each with its change against the baseline, and the overall improvement (latest − baseline). */
    public record OutcomeList(List<OutcomeView> outcomes, Improvement improvement) {
    }

    @Transactional(readOnly = true)
    public OutcomeList list(CurrentUser user, UUID caseId) {
        access.clinical(user, caseId, AccessPolicy.Action.READ, "OUTCOME_LIST");
        List<TreatmentOutcome> list = outcomes.findByCaseIdOrderByRecordedAtAsc(caseId);
        TreatmentOutcome baseline = list.stream().filter(x -> "BASELINE".equals(x.getMilestone())).findFirst().orElse(null);
        audit.record("OUTCOME_LIST", "CASE", caseId.toString(), AuditService.Outcome.SUCCESS, "rows=" + list.size());
        List<OutcomeView> views = list.stream().map(o -> view(o, baseline)).toList();
        Improvement overall = null;
        for (OutcomeView v : views) {
            if (v.improvement() != null) {
                overall = v.improvement();
            }
        }
        return new OutcomeList(views, overall);
    }

    static OutcomeView view(TreatmentOutcome o, TreatmentOutcome baseline) {
        Improvement imp = baseline == null || baseline.getId().equals(o.getId()) ? null
                : new Improvement(o.getPainScore() - baseline.getPainScore(), o.getFunctionScore() - baseline.getFunctionScore());
        return new OutcomeView(o.getId(), o.getCaseId(), o.getMilestone(), o.getPainScore(), o.getFunctionScale(), o.getFunctionScore(),
                o.getRtwDate(), o.getResidualImpairmentPct(), o.getNote(), o.getRecordedBy(), o.getRecordedAt(), imp);
    }
}
