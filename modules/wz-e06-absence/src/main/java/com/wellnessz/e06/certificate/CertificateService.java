package com.wellnessz.e06.certificate;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.wellnessz.common.error.ApiException;
import com.wellnessz.common.model.DataClassification;
import com.wellnessz.common.model.Roles;
import com.wellnessz.documents.DocumentService;
import com.wellnessz.e05.support.FitnessSupport;
import com.wellnessz.e06.E06Events;
import com.wellnessz.e06.model.AbsenceEnums;
import com.wellnessz.e06.model.AbsenceEnums.CertificateStatus;
import com.wellnessz.e06.model.Certificate;
import com.wellnessz.e06.model.CertificateCall;
import com.wellnessz.e06.model.AbsenceCertificateCallRepository;
import com.wellnessz.e06.model.AbsenceCertificateRepository;
import com.wellnessz.e06.model.Episode;
import com.wellnessz.e06.model.E06EpisodeRepository;
import com.wellnessz.e06.support.AbsenceSupport;
import com.wellnessz.integration.outbox.EventBus;
import com.wellnessz.masterdata.Employee;
import com.wellnessz.security.audit.AuditService;
import com.wellnessz.security.identity.CurrentUser;

/**
 * Medical certificates (E06-F03): employee upload, clinic scan, partner electronic certificates; OCR field extraction,
 * duplicate and alteration checks, validation checklist, verification calls, verification outcome with HR escalation.
 * HR / managers / the employee see validity only (PENDING / VALID / INVALID); the content stays with OH and reception.
 */
@Service("absenceCertificateService")
public class CertificateService {

    public static final String EMPLOYEE_UPLOAD = "EMPLOYEE_UPLOAD";
    public static final String CLINIC_SCAN = "CLINIC_SCAN";
    public static final String PARTNER_ELECTRONIC = "PARTNER_ELECTRONIC";
    static final int MAX_DAYS = 366;
    static final int MAX_PDF_BYTES = 10 * 1024 * 1024;

    private final AbsenceCertificateRepository certificates;
    private final AbsenceCertificateCallRepository calls;
    private final E06EpisodeRepository episodes;
    private final DocumentService documents;
    private final EventBus bus;
    private final AbsenceSupport support;
    private final AuditService audit;

    public CertificateService(AbsenceCertificateRepository certificates, AbsenceCertificateCallRepository calls, E06EpisodeRepository episodes,
                              DocumentService documents, EventBus bus, AbsenceSupport support, AuditService audit) {
        this.certificates = certificates;
        this.calls = calls;
        this.episodes = episodes;
        this.documents = documents;
        this.bus = bus;
        this.support = support;
        this.audit = audit;
    }

    // ------------------------------------------------------------------ views

    public record SelfView(UUID id, LocalDate fromDate, LocalDate toDate, String status, Instant receivedAt) {
    }

    public record ExtractedView(String issuerName, LocalDate issueDate, LocalDate fromDate, LocalDate toDate, String registration) {
    }

    public record CallView(UUID id, String calledParty, String outcome, String note, String by, Instant at) {
    }

    public record CertificateView(UUID id, String employeeId, String displayName, String source, UUID documentId, UUID episodeId,
                                  LocalDate fromDate, LocalDate toDate, String issuerName, String issuerRegistration, String certificateNumber,
                                  String partnerOrg, LocalDate issueDate, String fitNote, ExtractedView extracted, Map<String, Object> checklist,
                                  String status, UUID duplicateOfId, List<String> alterationFlags, List<CallView> calls, Instant receivedAt,
                                  String receivedBy, String validatedBy, Instant validatedAt, String validationNote, String verifiedBy,
                                  Instant verifiedAt, boolean escalated) {
    }

    public record WorkspaceView(UUID id, String employeeId, String displayName, LocalDate fromDate, LocalDate toDate, String validity,
                                boolean escalated) {
    }

    public record PartnerCommand(String employeeRef, String partnerOrg, String certificateNumber, String issuerName, String issuerRegistration,
                                 LocalDate issueDate, LocalDate fromDate, LocalDate toDate, String fitNote, String pdfBase64) {
    }

    // ------------------------------------------------------------------ intake

    @Transactional
    public SelfView employeeUpload(CurrentUser user, byte[] bytes, LocalDate from, LocalDate to, UUID episodeId) {
        if (user.employeeId() == null || support.employee(user.employeeId()).isEmpty()) {
            throw ApiException.forbidden("Certificates are uploaded by the employee");
        }
        Certificate c = fromFile(user.username(), user.employeeId(), EMPLOYEE_UPLOAD, bytes, from, to, episodeId);
        audit.record("ABSENCE_CERTIFICATE_UPLOAD", "ABSENCE_CERTIFICATE", c.getId().toString(), AuditService.Outcome.SUCCESS, EMPLOYEE_UPLOAD);
        return self(c);
    }

    @Transactional
    public CertificateView clinicScan(CurrentUser user, String employeeId, byte[] bytes, LocalDate from, LocalDate to, UUID episodeId) {
        if (AbsenceSupport.isOh(user)) {
            support.requireClinicalUpdate(user, employeeId, "ABSENCE_CERTIFICATE_SCAN");
        } else if (!user.hasRole(Roles.CLINIC_RECEPTION) || support.employee(employeeId).isEmpty()) {
            audit.record("ABSENCE_CERTIFICATE_SCAN", "EMPLOYEE", employeeId, AuditService.Outcome.DENIED, "Unknown employee");
            throw ApiException.notFound("Employee");
        }
        Certificate c = fromFile(user.username(), employeeId, CLINIC_SCAN, bytes, from, to, episodeId);
        audit.record("ABSENCE_CERTIFICATE_SCAN", "ABSENCE_CERTIFICATE", c.getId().toString(), AuditService.Outcome.SUCCESS, CLINIC_SCAN);
        return view(c);
    }

    private Certificate fromFile(String actor, String employeeId, String source, byte[] bytes, LocalDate from, LocalDate to, UUID episodeId) {
        checkDates(from, to);
        Episode ep = episode(employeeId, episodeId, from, to);
        DocumentService.Stored stored = documents.storeUpload(actor, employeeId, ep == null ? null : ep.getCaseId(), "SICK_CERTIFICATE",
                "Sick certificate " + from + " to " + to, bytes, DataClassification.CLINICAL);
        CertificateChecks.Extracted x = CertificateChecks.extract(stored.extractedText());
        Certificate c = base(actor, employeeId, source, from, to, ep);
        c.setDocumentId(stored.document().id());
        c.setSha256(stored.sha256());
        c.setExtracted(CertificateChecks.Extracted.blank(x) ? null : support.json(x));
        c.setIssuerName(x.issuerName());
        c.setIssuerRegistration(x.registration());
        c.setIssueDate(x.issueDate());
        boolean pdf = "application/pdf".equals(stored.document().contentType());
        List<String> flags = CertificateChecks.alterationFlags(from, to, null, x, !stored.extractedText().isBlank(), pdf ? bytes : null);
        c.setAlterationFlags(flags.isEmpty() ? null : String.join(",", flags));
        Optional<UUID> dup = CertificateChecks.duplicateOf(candidate(c), known(employeeId, c.getSha256(), null));
        c.setDuplicateOfId(dup.orElse(null));
        c.setStatus(CertificateStatus.RECEIVED.name());
        certificates.save(c);
        support.notifyEmployee(employeeId, "absence.certificate-received", Map.of("documentNumber", stored.document().documentNumber()));
        return c;
    }

    @Transactional
    public CertificateView partner(String actor, PartnerCommand cmd) {
        Employee e = support.reconcile(cmd.employeeRef()).orElseThrow(() -> new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "unknown_employee",
                "The employee reference could not be reconciled"));
        checkDates(cmd.fromDate(), cmd.toDate());
        if (cmd.issueDate() != null && cmd.issueDate().isAfter(support.today().plusDays(1))) {
            throw ApiException.badRequest("invalid_dates", "issueDate is in the future");
        }
        Episode ep = episode(e.getEmployeeId(), null, cmd.fromDate(), cmd.toDate());
        Certificate c = base(actor, e.getEmployeeId(), PARTNER_ELECTRONIC, cmd.fromDate(), cmd.toDate(), ep);
        c.setPartnerOrg(cmd.partnerOrg());
        c.setCertificateNumber(cmd.certificateNumber());
        c.setIssuerName(cmd.issuerName());
        c.setIssuerRegistration(cmd.issuerRegistration());
        c.setIssueDate(cmd.issueDate());
        c.setFitNote(cmd.fitNote());
        byte[] pdf = null;
        if (cmd.pdfBase64() != null && !cmd.pdfBase64().isBlank()) {
            try {
                pdf = Base64.getDecoder().decode(cmd.pdfBase64().strip());
            } catch (IllegalArgumentException ex) {
                throw ApiException.badRequest("invalid_pdf", "pdfBase64 is not valid base64");
            }
            if (pdf.length > MAX_PDF_BYTES) {
                throw ApiException.badRequest("file_too_large", "Files must be between 1 byte and 10 MB");
            }
            DocumentService.Stored stored = documents.storeUpload(actor, e.getEmployeeId(), ep == null ? null : ep.getCaseId(), "SICK_CERTIFICATE",
                    "Electronic sick certificate " + cmd.certificateNumber(), pdf, DataClassification.CLINICAL);
            c.setDocumentId(stored.document().id());
            c.setSha256(stored.sha256());
            CertificateChecks.Extracted x = CertificateChecks.extract(stored.extractedText());
            c.setExtracted(CertificateChecks.Extracted.blank(x) ? null : support.json(x));
        }
        List<String> flags = CertificateChecks.alterationFlags(cmd.fromDate(), cmd.toDate(), cmd.issueDate(), null, null,
                pdf != null && "application/pdf".equals(contentType(pdf)) ? pdf : null);
        c.setAlterationFlags(flags.isEmpty() ? null : String.join(",", flags));
        Optional<UUID> dup = CertificateChecks.duplicateOf(candidate(c), known(e.getEmployeeId(), c.getSha256(), c.getCertificateNumber()));
        c.setDuplicateOfId(dup.orElse(null));
        // verified source: valid unless it duplicates a certificate on file
        c.setStatus(dup.isPresent() ? CertificateStatus.SUSPICIOUS.name() : CertificateStatus.VALID.name());
        if (dup.isEmpty()) {
            c.setValidatedBy(actor);
            c.setValidatedAt(support.now());
        }
        certificates.save(c);
        if (dup.isEmpty()) {
            publishValidity(c);
        }
        audit.recordAs(actor, "INTEGRATION", "ABSENCE_CERTIFICATE_PARTNER", "ABSENCE_CERTIFICATE", c.getId().toString(),
                AuditService.Outcome.SUCCESS, c.getStatus());
        return view(c);
    }

    private static String contentType(byte[] b) {
        return b.length > 4 && b[0] == '%' && b[1] == 'P' && b[2] == 'D' && b[3] == 'F' ? "application/pdf" : "other";
    }

    private static void checkDates(LocalDate from, LocalDate to) {
        if (from == null || to == null || to.isBefore(from)) {
            throw ApiException.badRequest("invalid_dates", "toDate must not be before fromDate");
        }
        if (java.time.temporal.ChronoUnit.DAYS.between(from, to) > MAX_DAYS) {
            throw ApiException.badRequest("invalid_dates", "A certificate covers at most one year");
        }
    }

    /** The episode the certificate covers: the one given (must be the employee's), or the employee's overlapping episode. */
    private Episode episode(String employeeId, UUID episodeId, LocalDate from, LocalDate to) {
        if (episodeId != null) {
            return episodes.findById(episodeId).filter(e -> e.getEmployeeId().equals(employeeId))
                    .orElseThrow(() -> ApiException.notFound("Episode"));
        }
        return episodes.findByEmployeeIdOrderByStartDateDesc(employeeId).stream()
                .filter(e -> !e.getStartDate().isAfter(to) && (e.getEndDate() == null || !e.getEndDate().isBefore(from))).findFirst().orElse(null);
    }

    private Certificate base(String actor, String employeeId, String source, LocalDate from, LocalDate to, Episode ep) {
        Certificate c = Certificate.create();
        c.setEmployeeId(employeeId);
        c.setSource(source);
        c.setFromDate(from);
        c.setToDate(to);
        c.setEpisodeId(ep == null ? null : ep.getId());
        c.setReceivedAt(support.now());
        c.setReceivedBy(actor);
        return c;
    }

    private static CertificateChecks.Candidate candidate(Certificate c) {
        return new CertificateChecks.Candidate(c.getEmployeeId(), c.getSha256(), c.getIssuerRegistration(), c.getCertificateNumber(),
                c.getFromDate(), c.getToDate());
    }

    private List<CertificateChecks.Known> known(String employeeId, String sha256, String certificateNumber) {
        Map<UUID, Certificate> pool = new LinkedHashMap<>();
        certificates.findByEmployeeIdOrderByFromDateDesc(employeeId).forEach(c -> pool.put(c.getId(), c));
        if (sha256 != null) {
            certificates.findBySha256(sha256).forEach(c -> pool.put(c.getId(), c));
        }
        if (certificateNumber != null) {
            certificates.findByCertificateNumber(certificateNumber).forEach(c -> pool.put(c.getId(), c));
        }
        return pool.values().stream().map(c -> new CertificateChecks.Known(c.getId(), c.getEmployeeId(), c.getSha256(), c.getIssuerRegistration(),
                c.getCertificateNumber(), c.getFromDate(), c.getToDate())).toList();
    }

    // ------------------------------------------------------------------ OH / reception work list

    @Transactional(readOnly = true)
    public List<CertificateView> list(CurrentUser user, String status, String employeeId) {
        List<Certificate> list = employeeId != null && !employeeId.isBlank() ? certificates.findByEmployeeIdOrderByFromDateDesc(employeeId)
                : certificates.findAllByOrderByReceivedAtDesc();
        List<CertificateView> out = new ArrayList<>();
        for (Certificate c : list) {
            if (status != null && !status.isBlank() && !status.equals(c.getStatus())) {
                continue;
            }
            if (AbsenceSupport.isOh(user) && !support.canSeeClinical(user, support.employee(c.getEmployeeId()).orElse(null))) {
                continue;
            }
            out.add(view(c));
        }
        audit.record("ABSENCE_CERTIFICATES_READ", "ABSENCE_CERTIFICATE", employeeId, AuditService.Outcome.SUCCESS, "rows=" + out.size());
        return out;
    }

    @Transactional
    public CertificateView validate(CurrentUser user, UUID id, Map<String, Boolean> checklist, String outcome, String note) {
        Certificate c = load(id);
        if (!CertificateStatus.RECEIVED.name().equals(c.getStatus()) && !CertificateStatus.SUSPICIOUS.name().equals(c.getStatus())
                && !CertificateStatus.VALID.name().equals(c.getStatus()) && !CertificateStatus.INVALID.name().equals(c.getStatus())) {
            throw ApiException.conflict("already_verified", "The certificate has already been verified");
        }
        c.setChecklist(support.json(checklist == null ? Map.of() : checklist));
        c.setStatus(CertificateStatus.valueOf(outcome).name());
        c.setValidatedBy(user.username());
        c.setValidatedAt(support.now());
        c.setValidationNote(FitnessSupport.clinical("note", note, 1000));
        if (!CertificateStatus.SUSPICIOUS.name().equals(outcome)) {
            publishValidity(c);
        }
        audit.record("ABSENCE_CERTIFICATE_VALIDATE", "ABSENCE_CERTIFICATE", id.toString(), AuditService.Outcome.SUCCESS, outcome);
        return view(c);
    }

    @Transactional
    public CertificateView call(CurrentUser user, UUID id, String calledParty, String outcome, String note) {
        Certificate c = load(id);
        CertificateCall call = CertificateCall.create();
        call.setCertificateId(c.getId());
        call.setCalledParty(calledParty.strip());
        call.setOutcome(outcome);
        call.setNote(FitnessSupport.clinical("note", note, 1000));
        call.setCalledBy(user.username());
        call.setCalledAt(support.now());
        calls.save(call);
        audit.record("ABSENCE_CERTIFICATE_CALL", "ABSENCE_CERTIFICATE", id.toString(), AuditService.Outcome.SUCCESS, outcome);
        return view(c);
    }

    @Transactional
    public CertificateView verify(CurrentUser user, UUID id, String outcome, boolean escalateToHr) {
        if (!AbsenceSupport.isOh(user)) {
            throw ApiException.forbidden("Occupational Health only");
        }
        Certificate c = load(id);
        support.requireClinicalUpdate(user, c.getEmployeeId(), "ABSENCE_CERTIFICATE_VERIFY");
        c.setStatus(CertificateStatus.valueOf(outcome).name());
        c.setVerifiedBy(user.username());
        c.setVerifiedAt(support.now());
        if (escalateToHr && !c.isEscalated()) {
            c.setEscalated(true);
            Employee e = support.employee(c.getEmployeeId()).orElse(null);
            Map<String, String> params = new LinkedHashMap<>();
            params.put("employeeName", e == null ? c.getEmployeeId() : e.getDisplayName());
            params.put("unit", support.unit(e));
            support.notifyHr("absence.certificate-escalation", params, "#/absence");
        }
        publishValidity(c);
        audit.record("ABSENCE_CERTIFICATE_VERIFY", "ABSENCE_CERTIFICATE", id.toString(), AuditService.Outcome.SUCCESS,
                outcome + (escalateToHr ? " escalated" : ""));
        return view(c);
    }

    private Certificate load(UUID id) {
        return certificates.findById(id).orElseThrow(() -> ApiException.notFound("Certificate"));
    }

    private void publishValidity(Certificate c) {
        bus.publish(new E06Events.HrAbsenceStatus(UUID.randomUUID().toString(), c.getEmployeeId(), null,
                AbsenceEnums.validity(c.getStatus()).name(), c.getFromDate()), E06Events.HR_DESTINATION);
    }

    /** Raw file for OH / reception (the endpoint authorised the reader). */
    @Transactional(readOnly = true)
    public DocumentService.Content content(CurrentUser user, UUID id) {
        Certificate c = load(id);
        if (AbsenceSupport.isOh(user)) {
            support.requireClinical(user, c.getEmployeeId(), "ABSENCE_CERTIFICATE_CONTENT");
        }
        if (c.getDocumentId() == null) {
            throw ApiException.notFound("Certificate file");
        }
        audit.record("ABSENCE_CERTIFICATE_CONTENT", "ABSENCE_CERTIFICATE", id.toString(), AuditService.Outcome.SUCCESS, null);
        return documents.systemContent(user.username(), c.getDocumentId());
    }

    // ------------------------------------------------------------------ employee / workspace

    @Transactional(readOnly = true)
    public List<SelfView> mine(CurrentUser user) {
        if (user.employeeId() == null) {
            return List.of();
        }
        audit.record("ABSENCE_CERTIFICATES_SELF_READ", "EMPLOYEE", user.employeeId(), AuditService.Outcome.SUCCESS, null);
        return certificates.findByEmployeeIdOrderByFromDateDesc(user.employeeId()).stream().map(CertificateService::self).toList();
    }

    @Transactional(readOnly = true)
    public List<WorkspaceView> workspace(CurrentUser user, String employeeId) {
        List<Employee> scope = employeeId != null && !employeeId.isBlank()
                ? List.of(support.requireInScope(user, employeeId, "ABSENCE_CERTIFICATES_WORKSPACE_READ"))
                : support.employeesInScope(user);
        List<WorkspaceView> out = new ArrayList<>();
        for (Employee e : scope) {
            for (Certificate c : certificates.findByEmployeeIdOrderByFromDateDesc(e.getEmployeeId())) {
                out.add(new WorkspaceView(c.getId(), e.getEmployeeId(), e.getDisplayName(), c.getFromDate(), c.getToDate(),
                        AbsenceEnums.validity(c.getStatus()).name(), c.isEscalated()));
            }
            audit.record("ABSENCE_CERTIFICATES_WORKSPACE_READ", "EMPLOYEE", e.getEmployeeId(), AuditService.Outcome.SUCCESS, "validity-only");
        }
        return out;
    }

    static SelfView self(Certificate c) {
        return new SelfView(c.getId(), c.getFromDate(), c.getToDate(), AbsenceEnums.validity(c.getStatus()) == AbsenceEnums.CertificateValidity.PENDING
                ? "RECEIVED" : AbsenceEnums.validity(c.getStatus()).name(), c.getReceivedAt());
    }

    private CertificateView view(Certificate c) {
        CertificateChecks.Extracted x = c.getExtracted() == null ? null
                : support.fromJson(c.getExtracted(), CertificateChecks.Extracted.class);
        List<CallView> cs = calls.findByCertificateIdInOrderByCalledAtAsc(List.of(c.getId())).stream()
                .map(k -> new CallView(k.getId(), k.getCalledParty(), k.getOutcome(), k.getNote(), k.getCalledBy(), k.getCalledAt())).toList();
        return new CertificateView(c.getId(), c.getEmployeeId(), support.name(c.getEmployeeId()), c.getSource(), c.getDocumentId(), c.getEpisodeId(),
                c.getFromDate(), c.getToDate(), c.getIssuerName(), c.getIssuerRegistration(), c.getCertificateNumber(), c.getPartnerOrg(),
                c.getIssueDate(), c.getFitNote(),
                x == null ? new ExtractedView(null, null, null, null, null)
                        : new ExtractedView(x.issuerName(), x.issueDate(), x.fromDate(), x.toDate(), x.registration()),
                c.getChecklist() == null ? Map.of() : support.readMap(c.getChecklist()), c.getStatus(), c.getDuplicateOfId(),
                c.getAlterationFlags() == null ? List.of() : List.of(c.getAlterationFlags().split(",")), cs, c.getReceivedAt(), c.getReceivedBy(),
                c.getValidatedBy(), c.getValidatedAt(), c.getValidationNote(), c.getVerifiedBy(), c.getVerifiedAt(), c.isEscalated());
    }
}
