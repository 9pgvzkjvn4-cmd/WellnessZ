package com.wellnessz.e11.publichealth;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.wellnessz.casekernel.support.CaseTimeline;
import com.wellnessz.casekernel.support.EmployeeRecordAccess;
import com.wellnessz.common.error.ApiException;
import com.wellnessz.common.model.DataClassification;
import com.wellnessz.common.model.Roles;
import com.wellnessz.documents.DocumentService;
import com.wellnessz.e11.exposure.ProtocolTimers;
import com.wellnessz.e11.model.CdCase;
import com.wellnessz.e11.model.Condition;
import com.wellnessz.e11.model.E11CdCaseRepository;
import com.wellnessz.e11.model.E11ConditionRepository;
import com.wellnessz.e11.model.E11ContactRepository;
import com.wellnessz.e11.model.E11ExposureRepository;
import com.wellnessz.e11.model.E11ExposureTaskRepository;
import com.wellnessz.e11.model.E11PathwayRepository;
import com.wellnessz.e11.model.E11PhCorrespondenceRepository;
import com.wellnessz.e11.model.E11PhReportRepository;
import com.wellnessz.e11.model.Exposure;
import com.wellnessz.e11.model.ExposureTask;
import com.wellnessz.e11.model.PhCorrespondence;
import com.wellnessz.e11.model.PhReport;
import com.wellnessz.e11.support.TravelHealthSupport;
import com.wellnessz.masterdata.Employee;
import com.wellnessz.masterdata.PartnerOrganisation;
import com.wellnessz.masterdata.PartnerOrganisationRepository;
import com.wellnessz.notification.Priority;
import com.wellnessz.security.abac.AccessPolicy;
import com.wellnessz.security.audit.AuditService;
import com.wellnessz.security.disclosure.DisclosureLogService;
import com.wellnessz.security.identity.CurrentUser;

/**
 * Public-health and authority reporting (E11-F06): a signed {@code PUBLIC_HEALTH_REPORT} for a notifiable communicable-disease
 * case or an exposure, the statutory deadline from the condition's {@code reportWithinHours} (exposures: configuration),
 * submission tracking (logged as a disclosure to the authority), the correspondence log (authority letters uploaded as
 * {@code AUTHORITY_CORRESPONDENCE}) and the {@code public-health-deadlines} alerts.
 */
@Service
public class PublicHealthReportService {

    public static final String PENDING = "PENDING";
    public static final String SUBMITTED = "SUBMITTED";
    public static final String OVERDUE = "OVERDUE";
    static final DateTimeFormatter DUE = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'UTC'", Locale.ROOT).withZone(ZoneOffset.UTC);

    private final E11PhReportRepository reports;
    private final E11PhCorrespondenceRepository correspondence;
    private final E11CdCaseRepository cdCases;
    private final E11ConditionRepository conditions;
    private final E11ContactRepository contacts;
    private final E11ExposureRepository exposures;
    private final E11ExposureTaskRepository exposureTasks;
    private final E11PathwayRepository pathways;
    private final PartnerOrganisationRepository partners;
    private final DocumentService documents;
    private final DisclosureLogService disclosures;
    private final EmployeeRecordAccess records;
    private final CaseTimeline timeline;
    private final TravelHealthSupport support;
    private final AuditService audit;

    public PublicHealthReportService(E11PhReportRepository reports, E11PhCorrespondenceRepository correspondence, E11CdCaseRepository cdCases,
                                     E11ConditionRepository conditions, E11ContactRepository contacts, E11ExposureRepository exposures,
                                     E11ExposureTaskRepository exposureTasks, E11PathwayRepository pathways, PartnerOrganisationRepository partners,
                                     DocumentService documents, DisclosureLogService disclosures, EmployeeRecordAccess records, CaseTimeline timeline,
                                     TravelHealthSupport support, AuditService audit) {
        this.reports = reports;
        this.correspondence = correspondence;
        this.cdCases = cdCases;
        this.conditions = conditions;
        this.contacts = contacts;
        this.exposures = exposures;
        this.exposureTasks = exposureTasks;
        this.pathways = pathways;
        this.partners = partners;
        this.documents = documents;
        this.disclosures = disclosures;
        this.records = records;
        this.timeline = timeline;
        this.support = support;
        this.audit = audit;
    }

    public record CreateCommand(UUID communicableCaseId, UUID exposureId, @NotBlank @Pattern(regexp = "[A-Z0-9_]{2,40}") String authorityCode) {
    }

    public record SubmitCommand(@NotBlank @Pattern(regexp = "EMAIL|PORTAL|PHONE") String channel, @NotNull Instant submittedAt,
                                @Pattern(regexp = "[A-Za-z0-9_./:#\\- ]{1,100}") String reference) {
    }

    public record CorrespondenceCommand(@NotBlank @Pattern(regexp = "IN|OUT") String direction, @NotBlank @Size(max = 2000) String summary,
                                        UUID documentId) {
    }

    public record CorrespondenceView(UUID id, String direction, String summary, Instant at, UUID documentId, String recordedBy, Instant recordedAt) {
    }

    public record ReportView(UUID id, String ref, UUID communicableCaseId, UUID exposureId, UUID caseId, String employeeId, String displayName,
                             String authorityCode, String authorityName, String subject, String status, Instant dueAt, Double hoursLeft,
                             UUID documentId, String channel, Instant submittedAt, String submissionReference, Boolean submittedOnTime,
                             List<CorrespondenceView> correspondence, String createdBy, Instant createdAt) {
    }

    static String status(PhReport r, Instant now) {
        if (SUBMITTED.equals(r.getStatus())) {
            return SUBMITTED;
        }
        return now.isAfter(r.getDueAt()) ? OVERDUE : PENDING;
    }

    static Double hoursLeft(PhReport r, Instant now) {
        if (SUBMITTED.equals(r.getStatus())) {
            return null;
        }
        return BigDecimal.valueOf(Duration.between(now, r.getDueAt()).toSeconds()).divide(BigDecimal.valueOf(3600), 1, RoundingMode.HALF_UP).doubleValue();
    }

    ReportView view(PhReport r, String displayName) {
        Instant now = support.now();
        String authority = partners.findById(r.getAuthorityCode()).map(PartnerOrganisation::getName).orElse(r.getAuthorityCode());
        List<CorrespondenceView> log = correspondence.findByReportIdOrderByRecordedAtAsc(r.getId()).stream()
                .map(c -> new CorrespondenceView(c.getId(), c.getDirection(), c.getSummary(), c.getRecordedAt(), c.getDocumentId(), c.getRecordedBy(), c.getRecordedAt())).toList();
        return new ReportView(r.getId(), r.getReportRef(), r.getCdCaseId(), r.getExposureId(), r.getCaseId(), r.getEmployeeId(), displayName,
                r.getAuthorityCode(), authority, r.getSubject(), status(r, now), r.getDueAt(), hoursLeft(r, now), r.getDocumentId(), r.getChannel(),
                r.getSubmittedAt(), r.getSubmissionReference(), r.getSubmittedAt() == null ? null : !r.getSubmittedAt().isAfter(r.getDueAt()), log,
                r.getCreatedBy(), r.getCreatedAt());
    }

    PartnerOrganisation authority(String code) {
        return partners.findById(code).filter(PartnerOrganisation::isActive).filter(p -> "AUTHORITY".equals(p.getOrgType()))
                .orElseThrow(() -> ApiException.badRequest("unknown_authority", "Unknown public-health authority"));
    }

    @Transactional
    public ReportView create(CurrentUser user, CreateCommand cmd) {
        if ((cmd.communicableCaseId() == null) == (cmd.exposureId() == null)) {
            throw ApiException.badRequest("subject_required", "Give either a communicable-disease case or an exposure");
        }
        PartnerOrganisation authority = authority(cmd.authorityCode());
        Instant now = support.now();
        PhReport r = PhReport.create();
        Map<String, String> fields = new LinkedHashMap<>();
        Employee e;
        if (cmd.communicableCaseId() != null) {
            CdCase c = cdCases.findById(cmd.communicableCaseId()).orElseThrow(() -> ApiException.notFound("Communicable-disease case"));
            e = records.requireClinical(user, c.getEmployeeId(), DataClassification.CLINICAL, AccessPolicy.Action.CREATE, "PH_REPORT_CREATE");
            if (!reports.findByCdCaseIdAndAuthorityCode(c.getId(), authority.getCode()).isEmpty()) {
                throw ApiException.conflict("report_exists", "A report to this authority already exists for the case");
            }
            Condition cond = conditions.findById(c.getConditionCode()).orElseThrow();
            Instant base = c.getConfirmedAt() != null ? c.getConfirmedAt() : c.getCreatedAt();
            r.setCdCaseId(c.getId());
            r.setCaseId(c.getCaseId());
            r.setSubject(cond.getName());
            r.setDueAt(c.getReportDueAt() != null ? c.getReportDueAt()
                    : base.plusSeconds((cond.getReportWithinHours() == null ? 72L : cond.getReportWithinHours()) * 3600L));
            fields.put("condition", cond.getName());
            fields.put("onsetDate", c.getOnsetDate().toString());
            long traced = contacts.countByCdCaseId(c.getId());
            long notified = contacts.countByCdCaseIdAndNotifiedAtIsNotNull(c.getId());
            fields.put("contacts", traced + " identified, " + notified + " notified and monitored");
            fields.put("measures", c.getExclusionDecision() == null ? "Assessment in progress"
                    : "Exclusion decision: " + c.getExclusionDecision().toLowerCase(Locale.ROOT) + " from " + c.getExclusionFrom()
                            + (c.getExclusionUntil() == null ? "" : " until " + c.getExclusionUntil())
                            + (c.isFoodHandler() ? "; excluded from food handling" : ""));
        } else {
            Exposure x = exposures.findById(cmd.exposureId()).orElseThrow(() -> ApiException.notFound("Exposure"));
            e = records.requireClinical(user, x.getEmployeeId(), DataClassification.CLINICAL, AccessPolicy.Action.CREATE, "PH_REPORT_CREATE");
            if (!reports.findByExposureIdAndAuthorityCode(x.getId(), authority.getCode()).isEmpty()) {
                throw ApiException.conflict("report_exists", "A report to this authority already exists for the exposure");
            }
            String pathwayName = pathways.findById(x.getPathway()).map(p -> p.getName()).orElse(x.getPathway());
            r.setExposureId(x.getId());
            r.setCaseId(x.getCaseId());
            r.setSubject(pathwayName);
            r.setDueAt(x.getOccurredAt().plusSeconds(support.configInt("travelhealth.public-health.exposure-report-hours", 72) * 3600L));
            fields.put("condition", pathwayName + (x.getFlightRef() == null ? "" : " – flight " + x.getFlightRef()));
            fields.put("onsetDate", LocalDate.ofInstant(x.getOccurredAt(), ZoneOffset.UTC).toString());
            List<String> done = exposureTasks.findByExposureIdOrderByPositionAsc(x.getId()).stream()
                    .filter(t -> ProtocolTimers.DONE.equals(t.getStatus())).map(ExposureTask::getLabel).toList();
            fields.put("measures", done.isEmpty() ? "Protocol started" : String.join("; ", done));
        }
        r.setReportRef(support.nextRef("PHR"));
        r.setEmployeeId(e.getEmployeeId());
        r.setAuthorityCode(authority.getCode());
        r.setStatus(PENDING);
        r.setCreatedBy(user.username());
        r.setCreatedAt(now);
        r.setUpdatedAt(now);
        fields.put("reportRef", r.getReportRef());
        fields.put("authority", authority.getName());
        fields.put("employeeName", e.getDisplayName());
        fields.put("employeeId", e.getEmployeeId());
        fields.put("reportedBy", support.staffName(user.username()));
        r.setDocumentId(documents.generateSigned(user.username(), "PUBLIC_HEALTH_REPORT", e.getEmployeeId(), r.getCaseId(), fields).id());
        reports.save(r);
        if (r.getCaseId() != null) {
            timeline.record(r.getCaseId(), "PUBLIC_HEALTH_REPORT", "Authority report " + r.getReportRef() + " prepared for " + authority.getName());
        }
        audit.record("PH_REPORT_CREATE", "PH_REPORT", r.getId().toString(), AuditService.Outcome.SUCCESS, authority.getCode());
        return view(r, e.getDisplayName());
    }

    PhReport require(CurrentUser user, UUID id, AccessPolicy.Action action, String auditAction) {
        PhReport r = reports.findById(id).orElse(null);
        if (r == null) {
            audit.record(auditAction, "PH_REPORT", id.toString(), AuditService.Outcome.DENIED, "Unknown report");
            throw ApiException.notFound("Report");
        }
        records.requireClinical(user, r.getEmployeeId(), DataClassification.CLINICAL, action, auditAction);
        return r;
    }

    @Transactional
    public ReportView submit(CurrentUser user, UUID id, SubmitCommand cmd) {
        PhReport r = require(user, id, AccessPolicy.Action.UPDATE, "PH_REPORT_SUBMIT");
        if (SUBMITTED.equals(r.getStatus())) {
            throw ApiException.conflict("already_submitted", "The report has already been submitted");
        }
        Instant now = support.now();
        if (cmd.submittedAt().isAfter(now.plusSeconds(300)) || cmd.submittedAt().isBefore(r.getCreatedAt().minusSeconds(86_400))) {
            throw ApiException.badRequest("invalid_submitted_at", "The submission time must not be in the future or before the report was prepared");
        }
        PartnerOrganisation authority = authority(r.getAuthorityCode());
        r.setStatus(SUBMITTED);
        r.setChannel(cmd.channel());
        r.setSubmittedAt(cmd.submittedAt());
        r.setSubmissionReference(cmd.reference() == null ? null : cmd.reference().strip());
        r.setSubmittedBy(user.username());
        r.setUpdatedAt(now);
        // statutory notification: a disclosure outside OH – logged without consent (legal obligation)
        disclosures.record(r.getEmployeeId(), "AUTHORITY", authority.getName(), "PUBLIC_HEALTH_REPORT", r.getId().toString(),
                r.getReportRef() + " – statutory notification", "STATUTORY_NOTIFICATION", null, user.username());
        if (r.getCaseId() != null) {
            timeline.record(r.getCaseId(), "PUBLIC_HEALTH_REPORT", "Authority report " + r.getReportRef() + " submitted by " + cmd.channel()
                    + (cmd.submittedAt().isAfter(r.getDueAt()) ? " (after the statutory deadline)" : " (within the statutory deadline)"));
        }
        audit.record("PH_REPORT_SUBMIT", "PH_REPORT", id.toString(), AuditService.Outcome.SUCCESS, cmd.channel());
        return view(r, support.displayName(r.getEmployeeId()));
    }

    @Transactional
    public ReportView correspondence(CurrentUser user, UUID id, CorrespondenceCommand cmd) {
        PhReport r = require(user, id, AccessPolicy.Action.UPDATE, "PH_REPORT_CORRESPONDENCE");
        if (cmd.documentId() != null) {
            DocumentService.DocumentView d = documents.get(user, cmd.documentId());
            if (d.employeeId() != null && !d.employeeId().equals(r.getEmployeeId())) {
                throw ApiException.badRequest("document_mismatch", "The document belongs to another person");
            }
        }
        addCorrespondence(user.username(), r, cmd.direction(), cmd.summary(), cmd.documentId());
        audit.record("PH_REPORT_CORRESPONDENCE", "PH_REPORT", id.toString(), AuditService.Outcome.SUCCESS, cmd.direction());
        return view(r, support.displayName(r.getEmployeeId()));
    }

    /** Authority letter uploaded as {@code AUTHORITY_CORRESPONDENCE} and logged on the report. */
    @Transactional
    public ReportView upload(CurrentUser user, UUID id, String direction, String summary, String fileName, byte[] bytes) {
        PhReport r = require(user, id, AccessPolicy.Action.UPDATE, "PH_REPORT_CORRESPONDENCE");
        if (!"IN".equals(direction) && !"OUT".equals(direction)) {
            throw ApiException.badRequest("invalid_direction", "direction must be IN or OUT");
        }
        if (summary == null || summary.isBlank() || summary.length() > 2000) {
            throw ApiException.badRequest("invalid_summary", "Give a summary of at most 2000 characters");
        }
        String title = "Authority correspondence " + r.getReportRef();
        DocumentService.Stored stored = documents.storeUpload(user.username(), r.getEmployeeId(), r.getCaseId(), "AUTHORITY_CORRESPONDENCE", title, bytes,
                DataClassification.CLINICAL);
        addCorrespondence(user.username(), r, direction, summary, stored.document().id());
        audit.record("PH_REPORT_CORRESPONDENCE", "PH_REPORT", id.toString(), AuditService.Outcome.SUCCESS, direction + " upload");
        return view(r, support.displayName(r.getEmployeeId()));
    }

    void addCorrespondence(String actor, PhReport r, String direction, String summary, UUID documentId) {
        PhCorrespondence c = PhCorrespondence.create();
        c.setReportId(r.getId());
        c.setDirection(direction);
        c.setSummary(summary.strip());
        c.setDocumentId(documentId);
        c.setRecordedBy(actor);
        c.setRecordedAt(support.now());
        correspondence.save(c);
        r.setUpdatedAt(support.now());
    }

    @Transactional(readOnly = true)
    public List<ReportView> list(CurrentUser user, String status) {
        Instant now = support.now();
        List<PhReport> all = reports.findTop500ByOrderByDueAtAsc();
        Map<String, Employee> people = support.employeesById(all.stream().map(PhReport::getEmployeeId).distinct().toList());
        List<ReportView> out = new ArrayList<>();
        for (PhReport r : all) {
            Employee e = people.get(r.getEmployeeId());
            if (e == null || !records.canSeeClinical(user, e, DataClassification.CLINICAL)) {
                continue;
            }
            if (status == null || status.equals(status(r, now))) {
                out.add(view(r, e.getDisplayName()));
            }
        }
        audit.record("PH_REPORT_LIST", "PH_REPORT", null, AuditService.Outcome.SUCCESS, "rows=" + out.size());
        return out;
    }

    @Transactional(readOnly = true)
    public ReportView get(CurrentUser user, UUID id) {
        PhReport r = require(user, id, AccessPolicy.Action.READ, "PH_REPORT_READ");
        return view(r, support.displayName(r.getEmployeeId()));
    }

    /** Job {@code public-health-deadlines}: {@code public-health.report-due} once when due soon and once when overdue. */
    @Transactional
    public int alertDeadlines(Instant now) {
        int before = support.configInt("travelhealth.public-health.alert-before-hours", 12);
        int sent = 0;
        for (PhReport r : reports.findByStatus(PENDING)) {
            Instant dueSoonFrom = r.getDueAt().minusSeconds(before * 3600L);
            boolean overdue = now.isAfter(r.getDueAt());
            boolean dueSoon = !overdue && !now.isBefore(dueSoonFrom);
            if (overdue && r.getOverdueAlertedAt() == null || dueSoon && r.getDueSoonAlertedAt() == null) {
                support.notifyOwnerOrRole(r.getCreatedBy(), Roles.OH_PHYSICIAN, "public-health.report-due",
                        Map.of("reference", r.getReportRef(), "dueDate", DUE.format(r.getDueAt())), "/public-health", Priority.URGENT);
                if (overdue) {
                    r.setOverdueAlertedAt(now);
                }
                if (r.getDueSoonAlertedAt() == null) {
                    r.setDueSoonAlertedAt(now);
                }
                sent++;
            }
        }
        return sent;
    }
}
