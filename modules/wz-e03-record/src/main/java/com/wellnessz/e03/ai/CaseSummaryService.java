package com.wellnessz.e03.ai;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.wellnessz.casekernel.model.CaseTask;
import com.wellnessz.casekernel.model.CaseTaskRepository;
import com.wellnessz.casekernel.model.OhCase;
import com.wellnessz.casekernel.support.CaseAccess;
import com.wellnessz.casekernel.support.ChartContributor;
import com.wellnessz.e03.encounter.ClinicalEncounterService;
import com.wellnessz.e03.model.ClinicalEncounter;
import com.wellnessz.e03.support.CodeRef;
import com.wellnessz.e03.support.RecordGuard;
import com.wellnessz.security.abac.AccessPolicy;
import com.wellnessz.security.audit.AuditService;
import com.wellnessz.security.identity.CurrentUser;

/**
 * Rule-based (non-AI) structured case summary for handover (E03-F07, {@code GET /cases/{id}/summary}): key dates, coded
 * diagnoses, fitness, restrictions (from chart contributors), open tasks and the last encounter. Deterministic – no model.
 */
@Service
public class CaseSummaryService {

    private static final Logger log = LoggerFactory.getLogger(CaseSummaryService.class);

    private final CaseAccess caseAccess;
    private final CaseTaskRepository tasks;
    private final ClinicalEncounterService encounters;
    private final RecordGuard guard;
    private final List<ChartContributor> contributors;
    private final AuditService audit;
    private final Clock clock;

    public CaseSummaryService(CaseAccess caseAccess, CaseTaskRepository tasks, ClinicalEncounterService encounters, RecordGuard guard,
                              List<ChartContributor> contributors, AuditService audit, Clock clock) {
        this.caseAccess = caseAccess;
        this.tasks = tasks;
        this.encounters = encounters;
        this.guard = guard;
        this.contributors = List.copyOf(contributors);
        this.audit = audit;
        this.clock = clock;
    }

    public record KeyDates(Instant opened, LocalDate occurredOn, Instant lastUpdated, Instant lastEncounterAt, LocalDate restrictionUntil) {
    }

    public record CodedDiagnosis(String system, String code, String display, boolean primary, UUID encounterId) {
    }

    public record Fitness(String status, LocalDate restrictionUntil) {
    }

    public record OpenTask(UUID id, String type, String title, Instant dueAt, String assignee) {
    }

    public record CaseSummary(UUID caseId, String caseRef, String caseType, String status, String urgency, String owner, KeyDates keyDates,
                              CodeRef primaryDiagnosis, List<CodedDiagnosis> diagnoses, Fitness fitness,
                              @JsonInclude(JsonInclude.Include.ALWAYS) Object restrictions, List<OpenTask> openTasks,
                              ClinicalEncounterService.EncounterSummary lastEncounter, int encounterCount, String generator, Instant generatedAt) {
    }

    @Transactional(readOnly = true)
    public CaseSummary summary(CurrentUser user, UUID caseId) {
        OhCase c = caseAccess.clinical(user, caseId, AccessPolicy.Action.READ, "CASE_SUMMARY");
        boolean restricted = guard.canSeeRestricted(user, guard.employee(c.getEmployeeId()));
        List<ClinicalEncounter> list = encounters.forCase(caseId).stream()
                .filter(e -> restricted || !"RESTRICTED_CLINICAL".equals(e.getClassification()))
                .filter(e -> !"ENTERED_IN_ERROR".equals(e.getStatus())).toList();
        Map<String, CodedDiagnosis> dx = new LinkedHashMap<>();
        if (c.getPrimaryDiagnosisCode() != null) {
            dx.put(c.getPrimaryDiagnosisSystem() + "|" + c.getPrimaryDiagnosisCode(), new CodedDiagnosis(c.getPrimaryDiagnosisSystem(),
                    c.getPrimaryDiagnosisCode(), c.getPrimaryDiagnosisDisplay(), true, null));
        }
        for (ClinicalEncounter e : list) {
            for (ClinicalEncounterService.Diagnosis d : encounters.diagnosesOf(e.getId())) {
                dx.putIfAbsent(d.system() + "|" + d.code(), new CodedDiagnosis(d.system(), d.code(), d.display(), false, e.getId()));
            }
        }
        ClinicalEncounter last = list.isEmpty() ? null : list.get(0);
        Object restrictions = List.of();
        for (ChartContributor contributor : contributors) {
            try {
                Object r = contributor.contribute(user, c.getEmployeeId()).sections().get("restrictions");
                if (r != null) {
                    restrictions = r;
                }
            } catch (RuntimeException ex) {
                log.warn("Chart contributor {} failed: {}", contributor.getClass().getSimpleName(), ex.getClass().getSimpleName());
            }
        }
        List<OpenTask> open = new ArrayList<>();
        for (CaseTask t : tasks.findByCaseIdAndStatus(caseId, CaseTask.Status.OPEN)) {
            open.add(new OpenTask(t.getId(), t.getType().name(), t.getTitle(), t.getDueAt(), t.getAssignee()));
        }
        KeyDates dates = new KeyDates(c.getCreatedAt(), c.getOccurredOn(), c.getUpdatedAt(),
                last == null ? null : last.getSignedAt() != null ? last.getSignedAt() : last.getCreatedAt(), c.getRestrictionUntil());
        audit.record("CASE_SUMMARY", "CASE", caseId.toString(), AuditService.Outcome.SUCCESS, "encounters=" + list.size());
        return new CaseSummary(c.getId(), c.getCaseRef(), c.getCaseType() == null ? null : c.getCaseType().name(), c.getStatus().name(),
                c.getUrgency() == null ? null : c.getUrgency().name(), c.getOwnerUsername(), dates,
                CodeRef.of(c.getPrimaryDiagnosisSystem(), c.getPrimaryDiagnosisCode(), c.getPrimaryDiagnosisDisplay()),
                new ArrayList<>(dx.values()), new Fitness(c.getFitnessStatus() == null ? null : c.getFitnessStatus().name(),
                c.getRestrictionUntil()), restrictions, open,
                last == null ? null : new ClinicalEncounterService.EncounterSummary(last.getId(), last.getEmployeeId(), last.getCaseId(), last.getType(),
                        last.getStatus(), last.getTitle(), last.getAuthor(), last.getCreatedAt(), last.getSignedAt(),
                        CodeRef.of(last.getPrimaryDiagnosisSystem(), last.getPrimaryDiagnosisCode(), last.getPrimaryDiagnosisDisplay()),
                        last.isAiAssisted()), list.size(), "RULE_BASED", clock.instant());
    }
}
