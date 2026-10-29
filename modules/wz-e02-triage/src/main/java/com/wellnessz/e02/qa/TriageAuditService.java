package com.wellnessz.e02.qa;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.wellnessz.casekernel.model.OhCase;
import com.wellnessz.casekernel.model.OhCaseRepository;
import com.wellnessz.common.error.ApiException;
import com.wellnessz.common.model.Roles;
import com.wellnessz.e02.model.AuditSample;
import com.wellnessz.e02.model.AuditSampleRepository;
import com.wellnessz.e02.model.TriageAssessment;
import com.wellnessz.e02.model.TriageAssessmentRepository;
import com.wellnessz.security.audit.AuditService;
import com.wellnessz.security.identity.CurrentUser;

/** Triage quality audit: random sample of assessments, reviewer agreement, agreement rate (E02-BL-016). */
@Service
public class TriageAuditService {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final AuditSampleRepository samples;
    private final TriageAssessmentRepository assessments;
    private final OhCaseRepository cases;
    private final AuditService audit;
    private final Clock clock;

    public TriageAuditService(AuditSampleRepository samples, TriageAssessmentRepository assessments, OhCaseRepository cases,
                              AuditService audit, Clock clock) {
        this.samples = samples;
        this.assessments = assessments;
        this.cases = cases;
        this.audit = audit;
        this.clock = clock;
    }

    public record SampleView(UUID id, UUID assessmentId, UUID caseId, String caseRef, String protocolCode, String computedUrgency,
                             String finalUrgency, String assessedBy, Instant assessedAt, boolean reviewed, Boolean agree,
                             String comment, String reviewer) {
    }

    @Transactional
    public List<SampleView> sample(CurrentUser user, Instant from, Instant to, int percent) {
        if (!user.hasRole(Roles.NURSING_HEAD)) {
            throw ApiException.forbidden("Only the Nursing Head draws audit samples");
        }
        if (percent < 1 || percent > 100) {
            throw ApiException.badRequest("invalid_percent", "Percent must be 1–100");
        }
        List<TriageAssessment> pool = new ArrayList<>(assessments.findByAssessedAtBetween(from, to).stream()
                .filter(a -> !samples.existsByAssessmentId(a.getId())).toList());
        Collections.shuffle(pool, RANDOM);
        int n = (int) Math.ceil(pool.size() * percent / 100.0);
        List<SampleView> out = new ArrayList<>();
        for (TriageAssessment a : pool.subList(0, Math.min(n, pool.size()))) {
            AuditSample s = new AuditSample();
            s.setId(UUID.randomUUID());
            s.setCaseId(a.getCaseId());
            s.setAssessmentId(a.getId());
            s.setSampledAt(clock.instant());
            samples.save(s);
            out.add(view(s));
        }
        audit.record("TRIAGE_AUDIT_SAMPLE", "TRIAGE", null, AuditService.Outcome.SUCCESS, "samples=" + out.size());
        return out;
    }

    @Transactional(readOnly = true)
    public List<SampleView> list(CurrentUser user) {
        reviewer(user);
        return samples.findAllByOrderBySampledAtDesc().stream().limit(500).map(this::view).toList();
    }

    @Transactional
    public SampleView review(CurrentUser user, UUID id, boolean agree, String comment) {
        reviewer(user);
        AuditSample s = samples.findById(id).orElseThrow(() -> ApiException.notFound("Sample"));
        TriageAssessment a = assessments.findById(s.getAssessmentId()).orElseThrow();
        if (user.username().equals(a.getAssessedBy())) {
            throw ApiException.conflict("self_review", "Reviewers cannot audit their own triage");
        }
        s.setAgree(agree);
        s.setComment(comment);
        s.setReviewer(user.username());
        s.setReviewedAt(clock.instant());
        return view(s);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> report(CurrentUser user) {
        reviewer(user);
        List<AuditSample> all = samples.findAll();
        long reviewed = all.stream().filter(s -> s.getReviewedAt() != null).count();
        long agreed = all.stream().filter(s -> Boolean.TRUE.equals(s.getAgree())).count();
        return Map.of("sampled", all.size(), "reviewed", reviewed, "agreementRate", reviewed == 0 ? 0.0 : (double) agreed / reviewed);
    }

    private static void reviewer(CurrentUser user) {
        if (!user.hasAnyRole(Roles.NURSING_HEAD, Roles.CMO, Roles.OH_PHYSICIAN)) {
            throw ApiException.forbidden("Triage audit is for the Nursing Head, CMO and physicians");
        }
    }

    private SampleView view(AuditSample s) {
        TriageAssessment a = assessments.findById(s.getAssessmentId()).orElseThrow();
        String ref = cases.findById(s.getCaseId()).map(OhCase::getCaseRef).orElse(null);
        return new SampleView(s.getId(), s.getAssessmentId(), s.getCaseId(), ref, a.getProtocolCode(), a.getComputedUrgency(),
                a.getFinalUrgency(), a.getAssessedBy(), a.getAssessedAt(), s.getReviewedAt() != null, s.getAgree(), s.getComment(),
                s.getReviewer());
    }
}
