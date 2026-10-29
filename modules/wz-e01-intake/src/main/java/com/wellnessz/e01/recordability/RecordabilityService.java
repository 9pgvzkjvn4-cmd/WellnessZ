package com.wellnessz.e01.recordability;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.wellnessz.casekernel.model.CaseEnums.Recordability;
import com.wellnessz.casekernel.model.CaseEnums.WorkRelated;
import com.wellnessz.casekernel.model.CaseStatus;
import com.wellnessz.casekernel.model.CaseType;
import com.wellnessz.casekernel.model.OhCase;
import com.wellnessz.casekernel.support.CaseAccess;
import com.wellnessz.casekernel.support.CaseTimeline;
import com.wellnessz.casekernel.support.CaseTransitionGuard;
import com.wellnessz.common.error.ApiException;
import com.wellnessz.common.model.Roles;
import com.wellnessz.security.abac.AccessPolicy;
import com.wellnessz.security.audit.AuditService;
import com.wellnessz.security.identity.CurrentUser;

import tools.jackson.databind.json.JsonMapper;

/**
 * Guided recordability classification (E01-F07): the server computes the class from the answers, an override or any
 * change of an existing classification requires a reason code, every version is kept, and classification is
 * mandatory before injury / illness / exposure cases leave INTAKE.
 */
@Service
public class RecordabilityService implements CaseTransitionGuard {

    static final Set<CaseType> REQUIRES_CLASSIFICATION = Set.of(CaseType.INJURY, CaseType.ILLNESS, CaseType.EXPOSURE);

    private final RecordabilityAssessmentRepository assessments;
    private final RecordabilityMappingRepository mappings;
    private final CaseAccess access;
    private final CaseTimeline timeline;
    private final AuditService audit;
    private final JdbcTemplate jdbc;
    private final JsonMapper json;
    private final Clock clock;
    private final int minGroup;

    public RecordabilityService(RecordabilityAssessmentRepository assessments, RecordabilityMappingRepository mappings,
                                CaseAccess access, CaseTimeline timeline, AuditService audit, JdbcTemplate jdbc, JsonMapper json,
                                Clock clock, @Value("${wellnessz.analytics.min-group-size:5}") int minGroup) {
        this.assessments = assessments;
        this.mappings = mappings;
        this.access = access;
        this.timeline = timeline;
        this.audit = audit;
        this.jdbc = jdbc;
        this.json = json;
        this.clock = clock;
        this.minGroup = minGroup;
    }

    public record Result(Recordability recordability, Recordability computed, WorkRelated workRelated, String localCategory,
                         boolean caaReportable, int version) {
    }

    public record HistoryItem(int version, Recordability computed, Recordability recordability, WorkRelated workRelated,
                              String reasonCode, String rationale, String localCategory, boolean caaReportable,
                              String assessedBy, java.time.Instant assessedAt) {
    }

    @Transactional
    public Result classify(CurrentUser user, UUID caseId, Map<String, Object> answers, WorkRelated workRelated,
                           Recordability override, String reasonCode, String rationale) {
        if (!user.hasAnyRole(Roles.OH_CLINICIAN, Roles.OH_PHYSICIAN, Roles.AME, Roles.CMO, Roles.NURSING_HEAD)) {
            throw ApiException.forbidden("Not permitted to classify recordability");
        }
        OhCase c = access.clinical(user, caseId, AccessPolicy.Action.UPDATE, "RECORDABILITY_CLASSIFY");
        Recordability computed = RecordabilityClassifier.classify(answers);
        Recordability fin = override != null ? override : computed;
        var previous = assessments.findFirstByCaseIdOrderByVersionDesc(caseId);
        boolean changing = previous.isPresent() && (previous.get().getFinalClass() != fin || previous.get().getWorkRelated() != workRelated);
        if ((override != null && override != computed) || changing) {
            if (reasonCode == null || !RecordabilityClassifier.REASON_CODES.contains(reasonCode)) {
                throw ApiException.badRequest("reason_required", "A reason code is required to override or change a classification");
            }
        }
        RecordabilityMapping m = mappings.findById(fin).orElse(null);
        int version = previous.map(p -> p.getVersion() + 1).orElse(1);
        assessments.save(new RecordabilityAssessment(caseId, version, json.writeValueAsString(answers == null ? Map.of() : answers),
                computed, fin, workRelated, reasonCode, rationale, m == null ? null : m.getLocalCategory(),
                m != null && m.isCaaReportable(), user.username(), clock.instant()));
        c.classify(fin, workRelated, clock.instant());
        timeline.record(c.getId(), "RECORDABILITY", "Recordability v" + version + ": " + fin + ", work-related " + workRelated);
        audit.record("RECORDABILITY_CLASSIFY", "CASE", caseId.toString(), AuditService.Outcome.SUCCESS, fin + "/" + workRelated);
        return new Result(fin, computed, workRelated, m == null ? null : m.getLocalCategory(), m != null && m.isCaaReportable(), version);
    }

    @Transactional(readOnly = true)
    public List<HistoryItem> history(CurrentUser user, UUID caseId) {
        access.clinical(user, caseId, AccessPolicy.Action.READ, "RECORDABILITY_READ");
        return assessments.findByCaseIdOrderByVersionDesc(caseId).stream().map(a -> new HistoryItem(a.getVersion(), a.getComputed(),
                a.getFinalClass(), a.getWorkRelated(), a.getReasonCode(), a.getRationale(), a.getLocalCategory(), a.isCaaReportable(),
                a.getAssessedBy(), a.getAssessedAt())).toList();
    }

    /** Counts by unit × class for regulatory logs / TRIFR inputs, small cells suppressed. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> report(LocalDate from, LocalDate to) {
        var start = Timestamp.from((from == null ? LocalDate.now(clock).withDayOfYear(1) : from).atStartOfDay().toInstant(ZoneOffset.UTC));
        var end = Timestamp.from((to == null ? LocalDate.now(clock) : to).plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC));
        return jdbc.queryForList("""
                select coalesce(subsidiary_code,'UNKNOWN') unit, recordability, count(*) n from casemgmt.oh_case
                 where created_at >= ? and created_at < ? and recordability <> 'NOT_CLASSIFIED' and work_related = 'YES'
                 group by 1, 2 order by 1, 2""", start, end).stream()
                .map(r -> {
                    long n = ((Number) r.get("n")).longValue();
                    boolean suppressed = n < minGroup;
                    return Map.<String, Object>of("unit", r.get("unit"), "recordability", r.get("recordability"),
                            "count", suppressed ? "<" + minGroup : n, "suppressed", suppressed);
                }).toList();
    }

    /** E01-F07: injury / illness / exposure cases cannot leave INTAKE unclassified. */
    @Override
    public void check(OhCase c, CaseStatus next) {
        if (c.getStatus() == CaseStatus.INTAKE && next != CaseStatus.CLOSED && REQUIRES_CLASSIFICATION.contains(c.getCaseType())
                && c.getRecordability() == Recordability.NOT_CLASSIFIED) {
            throw new ApiException(org.springframework.http.HttpStatus.UNPROCESSABLE_CONTENT, "recordability_required",
                    "Classify recordability before the case can leave Intake");
        }
    }
}
