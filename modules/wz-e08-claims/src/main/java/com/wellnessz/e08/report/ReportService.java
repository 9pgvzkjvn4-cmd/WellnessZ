package com.wellnessz.e08.report;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.wellnessz.casekernel.model.CaseEnums;
import com.wellnessz.casekernel.model.OhCase;
import com.wellnessz.casekernel.model.OhCaseRepository;
import com.wellnessz.casekernel.support.CaseTimeline;
import com.wellnessz.common.error.ApiException;
import com.wellnessz.common.model.DataClassification;
import com.wellnessz.common.model.Roles;
import com.wellnessz.common.text.ClinicalTextDetector;
import com.wellnessz.documents.DocumentService;
import com.wellnessz.documents.model.DocumentShare;
import com.wellnessz.e08.model.Claim;
import com.wellnessz.e08.model.ClaimEnums.ReportStatus;
import com.wellnessz.e08.model.ClaimEnums.SubmissionChannel;
import com.wellnessz.e08.model.E08ClaimRepository;
import com.wellnessz.e08.model.E08FirstReportRepository;
import com.wellnessz.e08.model.E08ReportFormRepository;
import com.wellnessz.e08.model.FirstReport;
import com.wellnessz.e08.model.ReportForm;
import com.wellnessz.e08.support.ClaimAccess;
import com.wellnessz.e08.support.ClaimsSupport;
import com.wellnessz.e08.support.FieldProblem;
import com.wellnessz.e08.support.InsurerConsent;
import com.wellnessz.ehsintake.model.EhsIncident;
import com.wellnessz.ehsintake.model.EhsIncidentRepository;
import com.wellnessz.masterdata.Employee;
import com.wellnessz.notification.Priority;
import com.wellnessz.security.audit.AuditService;
import com.wellnessz.security.disclosure.DisclosureLogService;
import com.wellnessz.security.identity.CurrentUser;

import tools.jackson.core.type.TypeReference;

/**
 * Statutory and insurer e-reporting (E08-F02): first reports auto-populated from the incident, case and HRIS, edited while
 * draft (the injury description is screened for diagnoses beyond the nature of injury), submitted as a signed
 * FIRST_REPORT_OF_INJURY – to insurers through a consent-checked INSURER share, to authorities as a legal obligation (no
 * consent, disclosure log with the legal basis) – through the {@link ClaimsSubmissionGateway}; acknowledgements manual or by
 * integration; hourly deadline job (reminder, LATE, escalation) and the on-time KPI.
 */
@Service
public class ReportService {

    static final Set<String> OPEN = Set.of(ReportStatus.DRAFT.name(), ReportStatus.LATE.name(), ReportStatus.REJECTED.name());
    private static final TypeReference<List<FieldDef>> FIELD_DEFS = new TypeReference<>() {
    };
    private static final TypeReference<Map<String, String>> FIELDS = new TypeReference<>() {
    };
    /** Merge fields of the FIRST_REPORT_OF_INJURY template (required ones must be filled before submission). */
    static final List<String> TEMPLATE_FIELDS = List.of("employer", "employeeName", "employeeId", "jobTitle", "occurredAt", "location",
            "description", "bodyPart", "injuryNature", "lostTime", "reportRef", "reportedBy");
    static final Set<String> REQUIRED = Set.of("employer", "employeeName", "employeeId", "jobTitle", "occurredAt", "location", "description",
            "bodyPart", "injuryNature", "reportedBy");

    public record FieldDef(String code, String label, String source) {
    }

    public record FormView(String code, String jurisdiction, String recipientType, String recipientCode, String recipientName, String title,
                           int deadlineHours, Integer specifiedDeadlineHours, String trigger, List<FieldDef> fields, String signOffStatus) {
    }

    public record ReportView(UUID id, String ref, UUID claimId, String claimRef, String formCode, String recipientType, String recipientCode,
                             String recipientName, String status, Instant dueAt, long hoursLeft, boolean specifiedInjury, Map<String, String> fields,
                             String channel, UUID documentId, Instant submittedAt, String submittedBy, String ackRef, Instant acknowledgedAt,
                             UUID ackDocumentId, String rejectionReason) {
    }

    public record DashboardRow(UUID id, String ref, String claimRef, String formCode, String recipientCode, String status, Instant dueAt,
                               long hoursLeft, Instant submittedAt, Instant acknowledgedAt) {
    }

    public record Kpi(Double onTimeSubmissionPct, long submitted, long onTime, long late, long open) {
    }

    private final E08ReportFormRepository forms;
    private final E08FirstReportRepository reports;
    private final E08ClaimRepository claims;
    private final OhCaseRepository cases;
    private final EhsIncidentRepository incidents;
    private final ClaimAccess access;
    private final ClaimsSupport support;
    private final InsurerConsent consent;
    private final DocumentService documents;
    private final DisclosureLogService disclosures;
    private final ClaimsSubmissionGateway gateway;
    private final CaseTimeline timeline;
    private final AuditService audit;

    public ReportService(E08ReportFormRepository forms, E08FirstReportRepository reports, E08ClaimRepository claims, OhCaseRepository cases,
                         EhsIncidentRepository incidents, ClaimAccess access, ClaimsSupport support, InsurerConsent consent,
                         DocumentService documents, DisclosureLogService disclosures, ClaimsSubmissionGateway gateway, CaseTimeline timeline,
                         AuditService audit) {
        this.forms = forms;
        this.reports = reports;
        this.claims = claims;
        this.cases = cases;
        this.incidents = incidents;
        this.access = access;
        this.support = support;
        this.consent = consent;
        this.documents = documents;
        this.disclosures = disclosures;
        this.gateway = gateway;
        this.timeline = timeline;
        this.audit = audit;
    }

    // ------------------------------------------------------------------ forms

    @Transactional(readOnly = true)
    public List<FormView> forms() {
        return forms.findByActiveTrueOrderByCode().stream().map(this::formView).toList();
    }

    private FormView formView(ReportForm f) {
        return new FormView(f.getCode(), f.getJurisdiction(), f.getRecipientType(), f.getRecipientCode(),
                f.getRecipientCode() == null ? null : support.partnerName(f.getRecipientCode()), f.getTitle(), f.getDeadlineHours(),
                f.getSpecifiedDeadlineHours(), f.getTriggerRule(), support.read(f.getFields(), FIELD_DEFS), f.getSignOffStatus());
    }

    // ------------------------------------------------------------------ lifecycle

    @Transactional
    public ReportView create(CurrentUser user, UUID claimId, String formCode, Boolean specifiedInjury) {
        Claim c = access.require(user, claimId, ClaimAccess.roles(user, Roles.CLAIMS_COORDINATOR, Roles.HSE_OFFICER), "CLAIM_REPORT_CREATE");
        ReportForm f = forms.findById(formCode == null ? "" : formCode).filter(ReportForm::isActive)
                .orElseThrow(() -> ClaimsSupport.unprocessable("unknown_form", "Unknown report form"));
        boolean exists = reports.findByClaimIdOrderByCreatedAtAsc(claimId).stream()
                .anyMatch(r -> r.getFormCode().equals(f.getCode()) && !ReportStatus.REJECTED.name().equals(r.getStatus()));
        if (exists) {
            throw ApiException.conflict("report_exists", "A " + f.getCode() + " report already exists for this claim");
        }
        OhCase oc = cases.findById(c.getCaseId()).orElse(null);
        Optional<EhsIncident> incident = incident(oc);
        boolean fatal = oc != null && oc.getRecordability() == CaseEnums.Recordability.FATALITY;
        boolean specified = Boolean.TRUE.equals(specifiedInjury) || fatal;
        Instant now = support.now();
        FirstReport r = FirstReport.create();
        r.setReportRef(support.nextRef("FROI"));
        r.setClaimId(c.getId());
        r.setFormCode(f.getCode());
        r.setRecipientType(f.getRecipientType());
        r.setRecipientCode(f.getRecipientCode() != null ? f.getRecipientCode() : c.getPayerCode());
        r.setStatus(ReportStatus.DRAFT.name());
        r.setSpecifiedInjury(specified);
        r.setDueAt(ReportDeadlines.dueAt(rule(f), incidentAt(c, incident), c.getNotifiedAt(), specified));
        r.setFields(support.json(populate(user, f, c, oc, incident.orElse(null), r.getReportRef())));
        r.setCreatedBy(user.username());
        r.setCreatedAt(now);
        r.setUpdatedAt(now);
        reports.save(r);
        audit.record("CLAIM_REPORT_CREATE", "FIRST_REPORT", r.getId().toString(), AuditService.Outcome.SUCCESS, f.getCode());
        return view(r, c);
    }

    @Transactional
    public ReportView update(CurrentUser user, UUID reportId, Map<String, String> fields, Boolean specifiedInjury) {
        FirstReport r = reports.findById(reportId).orElseThrow(() -> ApiException.notFound("Report"));
        Claim c = access.require(user, r.getClaimId(), ClaimAccess.roles(user, Roles.CLAIMS_COORDINATOR, Roles.HSE_OFFICER), "CLAIM_REPORT_UPDATE");
        if (!OPEN.contains(r.getStatus())) {
            throw ApiException.conflict("report_submitted", "Only draft reports can be edited");
        }
        ReportForm f = forms.findById(r.getFormCode()).orElseThrow(() -> ApiException.notFound("Report form"));
        Set<String> allowed = new java.util.HashSet<>();
        support.read(f.getFields(), FIELD_DEFS).forEach(d -> allowed.add(d.code()));
        Map<String, String> current = fields(r);
        if (fields != null) {
            if (fields.size() > 40) {
                throw ApiException.badRequest("too_many_fields", "Too many fields");
            }
            for (Map.Entry<String, String> e : fields.entrySet()) {
                if (!allowed.contains(e.getKey()) || "reportRef".equals(e.getKey())) {
                    throw ApiException.badRequest("unknown_field", "Unknown or read-only field: " + e.getKey());
                }
                String v = e.getValue() == null ? null : e.getValue().strip();
                int max = "description".equals(e.getKey()) ? 4000 : 400;
                if (v != null && v.length() > max) {
                    throw ApiException.badRequest("too_long", "Field '" + e.getKey() + "' exceeds " + max + " characters");
                }
                if ("description".equals(e.getKey())) {
                    v = ClaimsSupport.screened("fields.description", v, max); // facts of the event – no diagnoses
                }
                if (v == null || v.isEmpty()) {
                    current.remove(e.getKey());
                } else {
                    current.put(e.getKey(), v);
                }
            }
        }
        if (specifiedInjury != null && specifiedInjury != r.isSpecifiedInjury()) {
            r.setSpecifiedInjury(specifiedInjury);
            OhCase oc = cases.findById(c.getCaseId()).orElse(null);
            r.setDueAt(ReportDeadlines.dueAt(rule(f), incidentAt(c, incident(oc)), c.getNotifiedAt(), specifiedInjury));
        }
        r.setFields(support.json(current));
        if (ReportStatus.REJECTED.name().equals(r.getStatus())) {
            r.setStatus(ReportStatus.DRAFT.name());
        }
        r.setUpdatedAt(support.now());
        audit.record("CLAIM_REPORT_UPDATE", "FIRST_REPORT", reportId.toString(), AuditService.Outcome.SUCCESS, null);
        return view(r, c);
    }

    @Transactional
    public ReportView submit(CurrentUser user, UUID reportId, String channel, UUID consentId) {
        FirstReport r = reports.findById(reportId).orElseThrow(() -> ApiException.notFound("Report"));
        Claim c = access.require(user, r.getClaimId(), ClaimAccess.roles(user, Roles.CLAIMS_COORDINATOR, Roles.HSE_OFFICER), "CLAIM_REPORT_SUBMIT");
        if (!OPEN.contains(r.getStatus())) {
            throw ApiException.conflict("report_submitted", "The report has already been submitted");
        }
        SubmissionChannel ch;
        try {
            ch = SubmissionChannel.valueOf(channel == null ? "" : channel.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("invalid_channel", "channel must be API, EMAIL or PORTAL");
        }
        ReportForm f = forms.findById(r.getFormCode()).orElseThrow(() -> ApiException.notFound("Report form"));
        Map<String, String> values = fields(r);
        for (String req : REQUIRED) {
            if (values.get(req) == null || values.get(req).isBlank()) {
                throw new FieldProblem("report_incomplete", "Complete the report before submitting it: " + req, "fields." + req);
            }
        }
        if (values.get("description") != null && ClinicalTextDetector.looksClinical(values.get("description"))) {
            throw new com.wellnessz.common.text.ClinicalContentException("fields.description");
        }
        boolean insurer = "INSURER".equals(r.getRecipientType());
        UUID consentToUse = insurer ? consent.require(c.getEmployeeId(), consentId) : null;
        Map<String, String> merge = new LinkedHashMap<>();
        for (String k : TEMPLATE_FIELDS) {
            if (values.get(k) != null) {
                merge.put(k, values.get(k));
            }
        }
        merge.put("reportRef", r.getReportRef());
        DocumentService.DocumentView doc = documents.generateSigned(user.username(), "FIRST_REPORT_OF_INJURY", c.getEmployeeId(), c.getCaseId(), merge);
        Instant now = support.now();
        String recipientName = support.partnerName(r.getRecipientCode());
        if (insurer) {
            documents.shareAsSystem(user.username(), doc.id(), DocumentShare.Target.INSURER, r.getRecipientCode(), consentToUse);
        } else {
            disclosures.record(c.getEmployeeId(), "AUTHORITY", recipientName, "FIRST_REPORT", r.getId().toString(),
                    f.getTitle() + " (" + r.getReportRef() + ")", "LEGAL_OBLIGATION " + f.getCode() + " (" + f.getJurisdiction() + ")",
                    null, user.username());
        }
        ClaimsSubmissionGateway.Receipt receipt = gateway.submit(new ClaimsSubmissionGateway.Submission(r.getId(), r.getReportRef(), c.getClaimRef(),
                f.getCode(), f.getJurisdiction(), r.getRecipientType(), r.getRecipientCode(), recipientName, c.getPayerCode(), c.getEmployeeId(),
                c.getIncidentDate(), now, r.getDueAt(), ch.name(), doc.id(), doc.documentNumber()));
        r.setStatus(ReportStatus.SUBMITTED.name());
        r.setChannel(ch.name());
        r.setDocumentId(doc.id());
        r.setMessageId(receipt.messageId());
        r.setSubmittedAt(now);
        r.setSubmittedBy(user.username());
        r.setRejectionReason(null);
        r.setUpdatedAt(now);
        timeline.record(c.getCaseId(), "CLAIM_REPORT_SUBMITTED", "First report " + r.getReportRef() + " (" + f.getCode() + ") submitted");
        audit.record("CLAIM_REPORT_SUBMIT", "FIRST_REPORT", reportId.toString(), AuditService.Outcome.SUCCESS,
                f.getCode() + " to " + r.getRecipientCode() + " via " + ch);
        return view(r, c);
    }

    @Transactional
    public ReportView acknowledge(CurrentUser user, UUID reportId, String ackRef, Instant receivedAt, byte[] file) {
        FirstReport r = reports.findById(reportId).orElseThrow(() -> ApiException.notFound("Report"));
        Claim c = access.require(user, r.getClaimId(), ClaimAccess.roles(user, Roles.CLAIMS_COORDINATOR), "CLAIM_REPORT_ACK");
        if (!ReportStatus.SUBMITTED.name().equals(r.getStatus())) {
            throw ApiException.conflict("not_submitted", "Only submitted reports can be acknowledged");
        }
        if (file != null && file.length > 0) {
            DocumentService.Stored s = documents.storeUpload(user.username(), c.getEmployeeId(), c.getCaseId(), "INSURER_ACKNOWLEDGEMENT",
                    "Acknowledgement " + r.getReportRef(), file, DataClassification.CONFIDENTIAL);
            r.setAckDocumentId(s.document().id());
        }
        applyAck(r, ackRef, receivedAt, ReportStatus.ACKNOWLEDGED, null);
        audit.record("CLAIM_REPORT_ACK", "FIRST_REPORT", reportId.toString(), AuditService.Outcome.SUCCESS, null);
        return view(r, c);
    }

    @Transactional
    public ReportView integrationAck(CurrentUser client, String ref, String ackRef, Instant receivedAt, String status, String reason) {
        FirstReport r = reports.findByReportRef(ref).orElseThrow(() -> ApiException.notFound("Report"));
        // only the recipient's own system (org_scope /PARTNERS/<recipientCode>) may acknowledge or reject a report
        String org = client.partnerOrganisation();
        if (org == null || !org.equals(r.getRecipientCode())) {
            audit.recordAs(client.username(), "INTEGRATION", "CLAIM_REPORT_ACK", "FIRST_REPORT", r.getId().toString(), AuditService.Outcome.DENIED,
                    "client organisation " + org + " is not the recipient");
            throw ApiException.notFound("Report");
        }
        ReportStatus s = "REJECTED".equals(status) ? ReportStatus.REJECTED : ReportStatus.ACKNOWLEDGED;
        if (!ReportStatus.SUBMITTED.name().equals(r.getStatus())) {
            if (s.name().equals(r.getStatus()) && ackRef != null && ackRef.equals(r.getAckRef())) {
                return view(r, claims.findById(r.getClaimId()).orElseThrow());
            }
            throw ApiException.conflict("not_submitted", "Only submitted reports can be acknowledged");
        }
        applyAck(r, ackRef, receivedAt, s, ClaimsSupport.screened("reason", reason, 1000));
        audit.record("CLAIM_REPORT_ACK", "FIRST_REPORT", r.getId().toString(), AuditService.Outcome.SUCCESS, "integration " + s);
        return view(r, claims.findById(r.getClaimId()).orElseThrow());
    }

    private void applyAck(FirstReport r, String ackRef, Instant receivedAt, ReportStatus status, String reason) {
        if (ackRef == null || ackRef.isBlank() || ackRef.length() > 100) {
            throw ApiException.badRequest("invalid_ack_ref", "ackRef is required (max 100 characters)");
        }
        Instant at = receivedAt != null ? receivedAt : support.now();
        if (at.isAfter(support.now().plusSeconds(300))) {
            throw ApiException.badRequest("invalid_received_at", "receivedAt cannot be in the future");
        }
        r.setStatus(status.name());
        r.setAckRef(ackRef.strip());
        r.setAcknowledgedAt(at);
        r.setRejectionReason(reason);
        r.setUpdatedAt(support.now());
    }

    // ------------------------------------------------------------------ dashboard & KPI

    @Transactional(readOnly = true)
    public List<DashboardRow> dashboard(String status, Integer dueWithinHours) {
        Instant now = support.now();
        List<DashboardRow> out = new ArrayList<>();
        Map<UUID, String> refs = new LinkedHashMap<>();
        for (FirstReport r : reports.findAllByOrderByDueAtAsc()) {
            if (status != null && !status.equals(r.getStatus())) {
                continue;
            }
            long left = ReportDeadlines.hoursLeft(r.getDueAt(), now);
            if (dueWithinHours != null && (!OPEN.contains(r.getStatus()) || left > dueWithinHours)) {
                continue;
            }
            String claimRef = refs.computeIfAbsent(r.getClaimId(), id -> claims.findById(id).map(Claim::getClaimRef).orElse(null));
            out.add(new DashboardRow(r.getId(), r.getReportRef(), claimRef, r.getFormCode(), r.getRecipientCode(), r.getStatus(), r.getDueAt(), left,
                    r.getSubmittedAt(), r.getAcknowledgedAt()));
        }
        return out;
    }

    @Transactional(readOnly = true)
    public List<ReportView> forClaim(CurrentUser user, UUID claimId) {
        Claim c = access.require(user, claimId, ClaimAccess.roles(user, Roles.CLAIMS_COORDINATOR, Roles.HSE_OFFICER), "CLAIM_REPORT_LIST");
        return reports.findByClaimIdOrderByCreatedAtAsc(claimId).stream().map(r -> view(r, c)).toList();
    }

    @Transactional(readOnly = true)
    public ReportView get(CurrentUser user, UUID reportId) {
        FirstReport r = reports.findById(reportId).orElseThrow(() -> ApiException.notFound("Report"));
        Claim c = access.require(user, r.getClaimId(), ClaimAccess.roles(user, Roles.CLAIMS_COORDINATOR, Roles.HSE_OFFICER), "CLAIM_REPORT_READ");
        return view(r, c);
    }

    @Transactional(readOnly = true)
    public Kpi kpi() {
        List<FirstReport> all = reports.findAll();
        List<ReportDeadlines.Outcome> submitted = all.stream().filter(r -> r.getSubmittedAt() != null)
                .map(r -> new ReportDeadlines.Outcome(r.getDueAt(), r.getSubmittedAt())).toList();
        long onTime = submitted.stream().filter(o -> !o.submittedAt().isAfter(o.dueAt())).count();
        long open = all.stream().filter(r -> OPEN.contains(r.getStatus())).count();
        return new Kpi(ReportDeadlines.onTimePct(submitted), submitted.size(), onTime, submitted.size() - onTime, open);
    }

    // ------------------------------------------------------------------ hourly job

    public record DeadlineRun(int reminded, int late) {
    }

    /** Due within the reminder window and not submitted → {@code claim.report-due}; past due → LATE + escalation to the claims mailbox. */
    @Transactional
    public DeadlineRun processDeadlines() {
        Instant now = support.now();
        int reminderHours = support.intConfig("claims.report-reminder-hours", 24);
        int reminded = 0;
        int late = 0;
        List<FirstReport> open = reports.findByStatusInAndDueAtBeforeOrderByDueAtAsc(List.of(ReportStatus.DRAFT.name(), ReportStatus.LATE.name()),
                now.plusSeconds(reminderHours * 3600L + 60));
        for (FirstReport r : open) {
            Claim c = claims.findById(r.getClaimId()).orElse(null);
            if (c == null) {
                continue;
            }
            Map<String, String> params = Map.of("reference", c.getClaimRef(), "dueDate",
                    r.getDueAt().atZone(ZoneOffset.UTC).toLocalDateTime().withSecond(0).withNano(0).toString().replace('T', ' ') + " UTC");
            if (ReportDeadlines.overdue(r.getDueAt(), now)) {
                if (ReportStatus.DRAFT.name().equals(r.getStatus())) {
                    r.setStatus(ReportStatus.LATE.name());
                    r.setUpdatedAt(now);
                    late++;
                }
                if (r.getEscalatedAt() == null) {
                    r.setEscalatedAt(now);
                    support.notifyClaimsTeam("claim.report-due", params, "#/claims/reports", Priority.URGENT);
                    timeline.record(c.getCaseId(), "CLAIM_REPORT_LATE", "First report " + r.getReportRef() + " passed its deadline");
                }
            } else if (r.getRemindedAt() == null && ReportDeadlines.reminderDue(r.getDueAt(), now, reminderHours)) {
                r.setRemindedAt(now);
                support.notifyClaimsTeam("claim.report-due", params, "#/claims/reports", Priority.NORMAL);
                reminded++;
            }
        }
        return new DeadlineRun(reminded, late);
    }

    // ------------------------------------------------------------------ helpers

    static ReportDeadlines.Rule rule(ReportForm f) {
        return new ReportDeadlines.Rule(f.getDeadlineHours(), f.getSpecifiedDeadlineHours(), f.getDeadlineAnchor());
    }

    private Optional<EhsIncident> incident(OhCase oc) {
        if (oc == null || oc.getIncidentRef() == null) {
            return Optional.empty();
        }
        List<EhsIncident> list = incidents.findByExternalRefOrderByReceivedAt(oc.getIncidentRef());
        return list.stream().filter(i -> oc.getEmployeeId().equals(i.getEmployeeId())).findFirst().or(() -> list.stream().findFirst());
    }

    private static Instant incidentAt(Claim c, Optional<EhsIncident> incident) {
        return incident.map(EhsIncident::getOccurredAt)
                .orElseGet(() -> c.getIncidentDate() == null ? null : c.getIncidentDate().atStartOfDay(ZoneOffset.UTC).toInstant());
    }

    /** Auto-population from the incident (facts of the event), the case (body part), HRIS and configuration – never clinical notes. */
    private Map<String, String> populate(CurrentUser user, ReportForm f, Claim c, OhCase oc, EhsIncident incident, String reportRef) {
        Employee e = support.employee(c.getEmployeeId()).orElse(null);
        Map<String, String> all = new LinkedHashMap<>();
        all.put("employer", support.config("claims.employer-name", "WellnessZ Airline"));
        all.put("employerRef", support.config("claims.employer-ref", "WZ-EMP-0001"));
        all.put("claimRef", c.getClaimRef());
        all.put("policyNumber", c.getPolicyNumber());
        all.put("employeeName", e != null ? e.getDisplayName() : null);
        all.put("employeeId", c.getEmployeeId());
        all.put("jobTitle", support.jobTitle(e));
        Instant at = incidentAt(c, Optional.ofNullable(incident));
        all.put("occurredAt", at == null ? null : at.atZone(ZoneOffset.UTC).toLocalDateTime().toString().replace('T', ' ') + " UTC");
        all.put("location", incident != null && incident.getLocation() != null ? incident.getLocation() : oc == null ? null : oc.getLocationCode());
        String description = incident == null ? null : incident.getDescription();
        if (description != null && (ClinicalTextDetector.looksClinical(description) || description.length() > 4000)) {
            description = null; // the coordinator writes the facts of the event; never copy diagnoses
        }
        all.put("description", description);
        all.put("bodyPart", oc != null && oc.getBodyPart() != null ? oc.getBodyPart() : incident == null ? null : incident.getBodyPart());
        all.put("injuryNature", incident == null ? null : incident.getIncidentClass());
        all.put("lostTime", c.isLostTime() ? "Yes – time lost from work" : "No time lost reported");
        all.put("reportRef", reportRef);
        all.put("reportedBy", user.displayName() != null ? user.displayName() : user.username());
        Map<String, String> out = new LinkedHashMap<>();
        for (FieldDef d : support.read(f.getFields(), FIELD_DEFS)) {
            String v = all.get(d.code());
            if (v != null && !v.isBlank()) {
                out.put(d.code(), v.length() > 4000 ? v.substring(0, 4000) : v);
            }
        }
        return out;
    }

    private Map<String, String> fields(FirstReport r) {
        return r.getFields() == null ? new LinkedHashMap<>() : new LinkedHashMap<>(support.read(r.getFields(), FIELDS));
    }

    ReportView view(FirstReport r, Claim c) {
        return new ReportView(r.getId(), r.getReportRef(), r.getClaimId(), c.getClaimRef(), r.getFormCode(), r.getRecipientType(), r.getRecipientCode(),
                support.partnerName(r.getRecipientCode()), r.getStatus(), r.getDueAt(), ReportDeadlines.hoursLeft(r.getDueAt(), support.now()),
                r.isSpecifiedInjury(), fields(r), r.getChannel(), r.getDocumentId(), r.getSubmittedAt(), r.getSubmittedBy(), r.getAckRef(),
                r.getAcknowledgedAt(), r.getAckDocumentId(), r.getRejectionReason());
    }
}
