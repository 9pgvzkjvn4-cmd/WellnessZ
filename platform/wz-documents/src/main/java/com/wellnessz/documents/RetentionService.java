package com.wellnessz.documents;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.wellnessz.common.error.ApiException;
import com.wellnessz.documents.model.Document;
import com.wellnessz.documents.model.DocumentRepository;
import com.wellnessz.documents.model.RetentionRule;
import com.wellnessz.documents.model.RetentionRuleRepository;
import com.wellnessz.integration.JobRunGuard;
import com.wellnessz.security.audit.AuditService;
import com.wellnessz.security.identity.CurrentUser;

/**
 * Retention schedule and flagging (E03-BL-017). Documents past their retention period and not on legal hold are listed
 * for DPO review; nothing is deleted automatically – destruction is a reviewed, audited decision outside this job.
 */
@Service
public class RetentionService {

    private static final Logger log = LoggerFactory.getLogger(RetentionService.class);
    static final List<String> TRIGGERS = List.of("LAST_ENCOUNTER", "DOCUMENT_DATE", "EMPLOYMENT_END");

    private final RetentionRuleRepository rules;
    private final DocumentRepository documents;
    private final JobRunGuard guard;
    private final AuditService audit;
    private final Clock clock;

    public RetentionService(RetentionRuleRepository rules, DocumentRepository documents, JobRunGuard guard, AuditService audit, Clock clock) {
        this.rules = rules;
        this.documents = documents;
        this.guard = guard;
        this.audit = audit;
        this.clock = clock;
    }

    public record RuleView(String recordClass, int retentionYears, String trigger, String legalBasis, String approvedBy, Instant approvedAt) {
        static RuleView of(RetentionRule r) {
            return new RuleView(r.getRecordClass(), r.getRetentionYears(), r.getTriggerEvent(), r.getLegalBasis(), r.getApprovedBy(), r.getApprovedAt());
        }
    }

    public record DueItem(String documentNumber, java.util.UUID documentId, String type, String recordClass, String employeeId,
                          Instant createdAt, LocalDate retainUntil) {
    }

    @Transactional(readOnly = true)
    public List<RuleView> schedule() {
        return rules.findAllByOrderByRecordClassAsc().stream().map(RuleView::of).toList();
    }

    @Transactional
    public RuleView update(CurrentUser user, String recordClass, int years, String trigger, String basis) {
        if (!TRIGGERS.contains(trigger) || years < 1 || years > 100) {
            throw ApiException.badRequest("invalid_retention", "Retention 1-100 years with a valid trigger");
        }
        RetentionRule r = rules.findById(recordClass).orElseGet(() -> new RetentionRule(recordClass));
        r.update(years, trigger, basis, user.username(), clock.instant());
        rules.save(r);
        audit.record("RETENTION_SCHEDULE_APPROVED", "RETENTION", recordClass, AuditService.Outcome.SUCCESS, years + "y " + trigger);
        return RuleView.of(r);
    }

    /** Documents past retention, not on legal hold (document-date trigger applied; other triggers need HRIS end dates). */
    @Transactional(readOnly = true)
    public List<DueItem> due() {
        Map<String, RetentionRule> byClass = rules.findAll().stream().collect(Collectors.toMap(RetentionRule::getRecordClass, r -> r));
        Instant now = clock.instant();
        List<DueItem> out = new ArrayList<>();
        for (String type : List.of("FITNESS_CERTIFICATE", "RESTRICTION_NOTE", "FOOD_HANDLER_CLEARANCE", "CABIN_CREW_MEDICAL_REPORT",
                "CREW_MEDICAL_REPORT", "RESULT_LETTER", "PHOTO", "EXTERNAL_REPORT", "REFERRAL_LETTER", "RTW_PLAN", "JOB_OFFER",
                "IHR_DECISION_LETTER", "OTHER")) {
            String cls = DocumentTemplates.recordClassFor(type);
            RetentionRule r = byClass.get(cls);
            if (r == null) {
                continue;
            }
            Instant before = LocalDate.now(clock.withZone(ZoneOffset.UTC)).minusYears(r.getRetentionYears()).atStartOfDay().toInstant(ZoneOffset.UTC);
            for (Document d : documents.findRetentionCandidates(List.of(type), before)) {
                out.add(new DueItem(d.getDocumentNumber(), d.getId(), d.getType(), cls, d.getEmployeeId(), d.getCreatedAt(),
                        d.getCreatedAt().atZone(ZoneOffset.UTC).toLocalDate().plusYears(r.getRetentionYears())));
            }
        }
        if (!out.isEmpty() && now != null) {
            audit.record("RETENTION_DUE_LISTED", "RETENTION", null, AuditService.Outcome.SUCCESS, "due=" + out.size());
        }
        return out;
    }

    /** Daily flagging run (once per day across instances). */
    @Scheduled(cron = "${wellnessz.documents.retention-cron:0 30 2 * * *}", zone = "UTC")
    @Transactional(readOnly = true)
    public void dailyFlag() {
        if (!guard.tryStart("document-retention", LocalDate.now(clock.withZone(ZoneOffset.UTC)).toString())) {
            return;
        }
        int due = due().size();
        log.info("Retention review: {} document(s) past retention and not on legal hold", due);
    }
}
