package com.wellnessz.e07.followup;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.wellnessz.casekernel.support.AbsenceSignalListener;
import com.wellnessz.casekernel.support.EmployeeRecordAccess;
import com.wellnessz.casekernel.support.FitnessClearanceGuard;
import com.wellnessz.common.error.ApiException;
import com.wellnessz.common.model.DataClassification;
import com.wellnessz.e07.model.ReturnAfterIllness;
import com.wellnessz.e07.model.ReturnAfterIllnessRepository;
import com.wellnessz.e07.support.SurveillanceSupport;
import com.wellnessz.masterdata.Employee;
import com.wellnessz.security.abac.AccessPolicy;
import com.wellnessz.security.audit.AuditService;
import com.wellnessz.security.identity.CurrentUser;

/**
 * Return-after-serious-illness medicals (E07-BL-020): raised by long-term absence (E06 signal) for safety-critical roles
 * or manually by a clinician; while one is REQUIRED, E05 cannot record a FIT / FIT_WITH_LIMITATIONS decision.
 */
@Service
public class RaiService implements AbsenceSignalListener, FitnessClearanceGuard {

    static final String REQUIRED = "REQUIRED";
    static final String BLOCKING_REASON = "A return-after-illness medical is required before this employee returns to a safety-critical role";

    private final ReturnAfterIllnessRepository rai;
    private final EmployeeRecordAccess records;
    private final SurveillanceSupport support;
    private final AuditService audit;

    public RaiService(ReturnAfterIllnessRepository rai, EmployeeRecordAccess records, SurveillanceSupport support, AuditService audit) {
        this.rai = rai;
        this.records = records;
        this.support = support;
        this.audit = audit;
    }

    public record RaiView(UUID id, String employeeId, String displayName, String reason, String source, UUID caseId, String status,
                          Instant createdAt, Instant completedAt) {
    }

    RaiView view(ReturnAfterIllness r) {
        return new RaiView(r.getId(), r.getEmployeeId(), support.displayName(r.getEmployeeId()), r.getReason(), r.getSource(), r.getCaseId(),
                r.getStatus(), r.getCreatedAt(), r.getCompletedAt());
    }

    // ------------------------------------------------------------------ SPIs

    @Override
    @Transactional
    public void longTermAbsence(String employeeId, UUID caseId, LocalDate startDate, long days) {
        Employee e = support.employee(employeeId).orElse(null);
        if (e == null || !support.isSafetyCritical(e) || rai.existsByEmployeeIdAndStatus(employeeId, REQUIRED)) {
            return;
        }
        ReturnAfterIllness r = create(employeeId, "LONG_TERM_ABSENCE", "E06", caseId, "system");
        audit.recordAs("system", "MODULE", "RAI_REQUIRED", "RAI", r.getId().toString(), AuditService.Outcome.SUCCESS, "source=E06 days=" + days);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<String> blockingReason(String employeeId, String outcome) {
        return rai.existsByEmployeeIdAndStatus(employeeId, REQUIRED) ? Optional.of(BLOCKING_REASON) : Optional.empty();
    }

    public boolean required(String employeeId) {
        return rai.existsByEmployeeIdAndStatus(employeeId, REQUIRED);
    }

    // ------------------------------------------------------------------ API

    @Transactional(readOnly = true)
    public List<RaiView> list(CurrentUser user, String status) {
        List<ReturnAfterIllness> list = status == null ? rai.findAllByOrderByCreatedAtDesc(PageRequest.of(0, 1000))
                : rai.findByStatusOrderByCreatedAtDesc(status, PageRequest.of(0, 1000));
        List<RaiView> out = list.stream().map(this::view).toList();
        audit.record("RAI_LIST", "RAI", null, AuditService.Outcome.SUCCESS, "rows=" + out.size());
        return out;
    }

    @Transactional
    public RaiView create(CurrentUser user, String employeeId, String reason, UUID caseId) {
        records.requireClinical(user, employeeId, DataClassification.CLINICAL, AccessPolicy.Action.CREATE, "RAI_CREATE");
        if (rai.existsByEmployeeIdAndStatus(employeeId, REQUIRED)) {
            throw ApiException.conflict("rai_exists", "A return-after-illness medical is already required for this employee");
        }
        ReturnAfterIllness r = create(employeeId, reason, "MANUAL", caseId, user.username());
        audit.record("RAI_CREATE", "RAI", r.getId().toString(), AuditService.Outcome.SUCCESS, reason);
        return view(r);
    }

    private ReturnAfterIllness create(String employeeId, String reason, String source, UUID caseId, String actor) {
        ReturnAfterIllness r = new ReturnAfterIllness();
        r.setId(UUID.randomUUID());
        r.setEmployeeId(employeeId);
        r.setReason(reason);
        r.setSource(source);
        r.setCaseId(caseId);
        r.setStatus(REQUIRED);
        r.setCreatedBy(actor);
        r.setCreatedAt(support.now());
        rai.save(r);
        support.notifyRole("OH_PHYSICIAN", "surveillance.follow-up", Map.of("caseRef", "RAI-" + r.getId().toString().substring(0, 8),
                "dueDate", support.today().plusDays(14).toString()), "/#/surveillance/rai");
        return r;
    }

    @Transactional
    public RaiView complete(CurrentUser user, UUID id, UUID encounterId, String note) {
        ReturnAfterIllness r = loadRequired(user, id, "RAI_COMPLETE");
        r.setStatus("COMPLETED");
        r.setCompletedBy(user.username());
        r.setCompletedAt(support.now());
        r.setEncounterId(encounterId);
        r.setNote(SurveillanceSupport.clinical("note", note, 4000));
        audit.record("RAI_COMPLETE", "RAI", id.toString(), AuditService.Outcome.SUCCESS, null);
        return view(r);
    }

    @Transactional
    public RaiView waive(CurrentUser user, UUID id, String reason) {
        ReturnAfterIllness r = loadRequired(user, id, "RAI_WAIVE");
        if (reason == null || reason.isBlank()) {
            throw ApiException.badRequest("reason_required", "Give the reason for waiving the medical");
        }
        r.setStatus("WAIVED");
        r.setCompletedBy(user.username());
        r.setCompletedAt(support.now());
        r.setNote(SurveillanceSupport.clinical("reason", reason, 4000));
        audit.record("RAI_WAIVE", "RAI", id.toString(), AuditService.Outcome.SUCCESS, null);
        return view(r);
    }

    private ReturnAfterIllness loadRequired(CurrentUser user, UUID id, String auditAction) {
        ReturnAfterIllness r = rai.findById(id).orElseThrow(() -> ApiException.notFound("Return-after-illness item"));
        records.requireClinical(user, r.getEmployeeId(), DataClassification.CLINICAL, AccessPolicy.Action.UPDATE, auditAction);
        if (!REQUIRED.equals(r.getStatus())) {
            throw ApiException.conflict("rai_closed", "This item is already " + r.getStatus().toLowerCase(java.util.Locale.ROOT));
        }
        return r;
    }
}
