package com.wellnessz.documents;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import com.wellnessz.casekernel.support.EmployeeRecordAccess;
import com.wellnessz.common.error.ApiException;
import com.wellnessz.common.model.DataClassification;
import com.wellnessz.common.model.Roles;
import com.wellnessz.common.text.ClinicalContentException;
import com.wellnessz.documents.model.Document;
import com.wellnessz.documents.model.DocumentRepository;
import com.wellnessz.documents.model.DocumentShare;
import com.wellnessz.documents.model.DocumentShareRepository;
import com.wellnessz.masterdata.Employee;
import com.wellnessz.masterdata.EmployeeRepository;
import com.wellnessz.notification.Channel;
import com.wellnessz.notification.NotificationService;
import com.wellnessz.notification.Priority;
import com.wellnessz.secrets.FieldEncryptor;
import com.wellnessz.security.abac.AccessPolicy;
import com.wellnessz.security.audit.AuditService;
import com.wellnessz.security.consent.ConsentPurpose;
import com.wellnessz.security.consent.ConsentService;
import com.wellnessz.security.disclosure.DisclosureLogService;
import com.wellnessz.security.identity.CurrentUser;

/**
 * Document service (E03-F06): template generation to PDF, e-signature with SHA-256 integrity, versioning, sharing
 * with consent and disclosure logging, scanned-document ingestion with text extraction / OCR, privacy-preserving
 * full-text search and retention / legal hold. Used by E03 (record) and E05 (fitness, RTW, job offers).
 */
@Service
public class DocumentService {

    public static final Set<String> SCAN_TYPES = Set.of("application/pdf", "image/jpeg", "image/png");
    static final int MAX_SCAN_BYTES = 10 * 1024 * 1024;
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{([a-zA-Z]+)}}");
    private static final Pattern WORD = Pattern.compile("[\\p{L}\\p{N}]{3,40}");

    private final DocumentRepository documents;
    private final DocumentShareRepository shares;
    private final DocumentTemplates templates;
    private final PdfRenderer renderer;
    private final ObjectProvider<OcrProvider> ocr;
    private final EmployeeRecordAccess records;
    private final EmployeeRepository employees;
    private final ConsentService consents;
    private final DisclosureLogService disclosures;
    private final NotificationService notifications;
    private final FieldEncryptor encryptor;
    private final JdbcTemplate jdbc;
    private final AuditService audit;
    private final JsonMapper json;
    private final Clock clock;
    private final String employeePortal;
    private final String managerPortal;
    private final com.wellnessz.casekernel.model.OhCaseRepository cases;

    public DocumentService(DocumentRepository documents, DocumentShareRepository shares, DocumentTemplates templates, PdfRenderer renderer,
                           ObjectProvider<OcrProvider> ocr, EmployeeRecordAccess records, EmployeeRepository employees,
                           ConsentService consents, DisclosureLogService disclosures, NotificationService notifications,
                           FieldEncryptor encryptor, JdbcTemplate jdbc, AuditService audit, JsonMapper json, Clock clock,
                           com.wellnessz.casekernel.model.OhCaseRepository cases,
                           @Value("${wellnessz.notification.employee-portal-url:https://employee.wellnessz.local}") String employeePortal,
                           @Value("${wellnessz.notification.manager-workspace-url:https://manager.wellnessz.local}") String managerPortal) {
        this.documents = documents;
        this.shares = shares;
        this.templates = templates;
        this.renderer = renderer;
        this.ocr = ocr;
        this.records = records;
        this.employees = employees;
        this.consents = consents;
        this.disclosures = disclosures;
        this.notifications = notifications;
        this.encryptor = encryptor;
        this.jdbc = jdbc;
        this.audit = audit;
        this.json = json;
        this.clock = clock;
        this.employeePortal = employeePortal;
        this.managerPortal = managerPortal;
        this.cases = cases;
    }

    // ------------------------------------------------------------------ views

    public record ShareView(UUID id, String target, String partnerOrg, Instant sharedAt, String sharedBy, Instant revokedAt) {
    }

    public record DocumentView(UUID id, String documentNumber, String type, String templateCode, String title, String employeeId,
                               UUID caseId, int version, String status, String contentType, int size, String sha256, String signedBy,
                               Instant signedAt, String createdBy, Instant createdAt, boolean legalHold, List<ShareView> shares,
                               Instant acknowledgedAt) {
    }

    public record SearchHit(DocumentView document, String snippet) {
    }

    public record Content(String fileName, String contentType, byte[] bytes) {
    }

    // ------------------------------------------------------------------ generation

    /**
     * Generates a DRAFT document from a template. OH roles need clinical access to the employee's record; HR may generate
     * HR templates (job offer) for employees inside their org scope only.
     */
    @Transactional
    public DocumentView generate(CurrentUser user, String templateCode, String employeeId, UUID caseId, Map<String, String> fields) {
        DocumentTemplates.Template t = templates.find(templateCode)
                .orElseThrow(() -> ApiException.badRequest("unknown_template", "Unknown document template"));
        if (t.generatorRoles().stream().noneMatch(user::hasRole)) {
            throw ApiException.forbidden("Not permitted to generate this document");
        }
        DataClassification cls = t.containsClinical() ? DataClassification.CLINICAL : DataClassification.CONFIDENTIAL;
        authoriseGenerate(user, employeeId, cls);
        Map<String, String> clean = validateFields(t, fields);
        UUID id = UUID.randomUUID();
        Document d = new Document(id, nextNumber(), id, 1, t.type(), t.code(), t.title(), employeeId, caseId, cls, user.username(), clock.instant());
        render(d, t, clean);
        documents.save(d);
        audit.record("DOCUMENT_GENERATE", "DOCUMENT", id.toString(), AuditService.Outcome.SUCCESS, t.code());
        return view(d, true);
    }

    @Transactional
    public DocumentView newVersion(CurrentUser user, UUID id, Map<String, String> fields) {
        Document prev = requireOh(user, id, AccessPolicy.Action.UPDATE, "DOCUMENT_NEW_VERSION");
        DocumentTemplates.Template t = templates.find(prev.getTemplateCode())
                .orElseThrow(() -> ApiException.badRequest("not_templated", "Only template documents can be versioned"));
        int version = documents.findBySeriesIdOrderByVersionAsc(prev.getSeriesId()).stream().mapToInt(Document::getVersion).max().orElse(1) + 1;
        Document d = new Document(UUID.randomUUID(), nextNumber(), prev.getSeriesId(), version, t.type(), t.code(), t.title(),
                prev.getEmployeeId(), prev.getCaseId(), prev.getClassification(), user.username(), clock.instant());
        render(d, t, validateFields(t, fields));
        documents.save(d);
        audit.record("DOCUMENT_NEW_VERSION", "DOCUMENT", d.getId().toString(), AuditService.Outcome.SUCCESS, "version=" + version);
        return view(d, true);
    }

    /** E-signature: locks the document; the signature hash binds the content hash, signer and time. */
    @Transactional
    public DocumentView sign(CurrentUser user, UUID id) {
        Document d = requireIssuer(user, id, AccessPolicy.Action.UPDATE, "DOCUMENT_SIGN");
        if (d.getStatus() != Document.Status.DRAFT) {
            throw ApiException.conflict("not_draft", "Only draft documents can be signed");
        }
        DocumentTemplates.Template t = templates.find(d.getTemplateCode()).orElse(null);
        Set<String> signers = t == null ? Set.of(Roles.CLINICIANS) : t.signerRoles();
        if (signers.stream().noneMatch(user::hasRole)) {
            throw ApiException.forbidden("Your role cannot sign this document");
        }
        Instant now = clock.instant();
        d.sign(user.username(), now, sha256((d.getSha256() + "|" + user.username() + "|" + now).getBytes(StandardCharsets.UTF_8)));
        for (Document other : documents.findBySeriesIdOrderByVersionAsc(d.getSeriesId())) {
            if (!other.getId().equals(d.getId()) && other.getStatus() == Document.Status.SIGNED) {
                other.setStatus(Document.Status.SUPERSEDED);
                shares.findByDocumentIdOrderBySharedAtAsc(other.getId()).forEach(s -> s.revoke(user.username(), "Superseded by version " + d.getVersion(), now));
            }
        }
        audit.record("DOCUMENT_SIGN", "DOCUMENT", id.toString(), AuditService.Outcome.SUCCESS, d.getSignatureSha256());
        return view(d, true);
    }

    /** Clinical photograph with recorded consent (E03-BL-007); never shareable outside OH. */
    @Transactional
    public DocumentView storePhoto(CurrentUser user, String employeeId, UUID caseId, byte[] bytes, String title, DataClassification cls) {
        records.requireClinical(user, employeeId, cls, AccessPolicy.Action.CREATE, "DOCUMENT_PHOTO");
        String type = sniff(bytes);
        if (!"image/jpeg".equals(type) && !"image/png".equals(type)) {
            throw ApiException.badRequest("invalid_file_type", "Photos must be JPEG or PNG");
        }
        if (bytes.length > MAX_SCAN_BYTES) {
            throw ApiException.badRequest("file_too_large", "Maximum 10 MB");
        }
        UUID id = UUID.randomUUID();
        Document d = new Document(id, nextNumber(), id, 1, "PHOTO", null, title == null ? "Clinical photograph" : title, employeeId, caseId,
                cls, user.username(), clock.instant());
        d.setContent(type, bytes, sha256(bytes), Base64.getEncoder().encodeToString(bytes));
        d.sign(user.username(), clock.instant(), sha256((d.getSha256() + "|" + user.username()).getBytes(StandardCharsets.UTF_8)));
        documents.save(d);
        audit.record("DOCUMENT_PHOTO", "DOCUMENT", id.toString(), AuditService.Outcome.SUCCESS, null);
        return view(d, true);
    }

    /** Scanned or uploaded external document: type verified by magic bytes, text extracted (PDF text layer or OCR). */
    @Transactional
    public DocumentView scan(CurrentUser user, String employeeId, UUID caseId, String documentType, byte[] bytes) {
        if (user.hasRole(Roles.CLINIC_RECEPTION) && !user.hasAnyRole(Roles.OH_TEAM)) {
            records.employee(employeeId); // clinic assistants file documents but cannot read the record
        } else {
            records.requireClinical(user, employeeId, DataClassification.CLINICAL, AccessPolicy.Action.CREATE, "DOCUMENT_SCAN");
        }
        if (bytes.length == 0 || bytes.length > MAX_SCAN_BYTES) {
            throw ApiException.badRequest("file_too_large", "Files must be between 1 byte and 10 MB");
        }
        String type = sniff(bytes);
        if (type == null) {
            throw ApiException.badRequest("invalid_file_type", "Only PDF, JPEG or PNG files are accepted");
        }
        String text = "application/pdf".equals(type) ? renderer.extractText(bytes)
                : Optional.ofNullable(ocr.getIfAvailable()).flatMap(o -> o.extractText(bytes, type)).orElse("");
        String kind = classify(text);
        String docType = documentType == null || documentType.isBlank() ? "EXTERNAL_REPORT" : documentType;
        if (!Set.of("EXTERNAL_REPORT", "OTHER").contains(docType)) {
            throw ApiException.badRequest("invalid_document_type", "Scanned documents are EXTERNAL_REPORT or OTHER");
        }
        UUID id = UUID.randomUUID();
        Document d = new Document(id, nextNumber(), id, 1, docType, null, "External document – " + kind, employeeId, caseId,
                DataClassification.CLINICAL, user.username(), clock.instant());
        d.setContent(type, bytes, sha256(bytes), Base64.getEncoder().encodeToString(bytes));
        d.setExtractedText(text);
        d.sign(user.username(), clock.instant(), sha256((d.getSha256() + "|" + user.username()).getBytes(StandardCharsets.UTF_8)));
        documents.saveAndFlush(d);
        index(d.getId(), text + " " + kind);
        audit.record("DOCUMENT_SCAN", "DOCUMENT", id.toString(), AuditService.Outcome.SUCCESS, kind + " textChars=" + text.length());
        return view(d, user.hasAnyRole(Roles.OH_TEAM));
    }

    // ------------------------------------------------------------------ module-level API (increment 3)

    /** Document types a business module may store from an upload (E06 sick certificates, E07 candidate / external documents). */
    public static final Set<String> UPLOAD_TYPES = Set.of("SICK_CERTIFICATE", "PRE_EMPLOYMENT_DOCUMENT", "VACCINATION_RECORD",
            "EXTERNAL_AEROMEDICAL_REPORT", "EXTERNAL_REPORT");

    /** Stored upload with the text extracted from it (PDF text layer or OCR) and its SHA-256 for duplicate detection. */
    public record Stored(DocumentView document, String extractedText, String sha256) {
    }

    /**
     * Stores an uploaded file on behalf of a business module. <b>The calling module has already authorised the actor</b>
     * (e.g. the employee uploading their own certificate, a candidate, an integration client); this method only validates
     * the file (magic bytes, size), extracts text, hashes and indexes it. The employee id may be a candidate id that is not
     * (yet) in the HRIS master.
     */
    @Transactional
    public Stored storeUpload(String actor, String employeeId, UUID caseId, String documentType, String title, byte[] bytes,
                              DataClassification classification) {
        if (!UPLOAD_TYPES.contains(documentType)) {
            throw ApiException.badRequest("invalid_document_type", "Unsupported upload document type");
        }
        if (bytes == null || bytes.length == 0 || bytes.length > MAX_SCAN_BYTES) {
            throw ApiException.badRequest("file_too_large", "Files must be between 1 byte and 10 MB");
        }
        String type = sniff(bytes);
        if (type == null) {
            throw ApiException.badRequest("invalid_file_type", "Only PDF, JPEG or PNG files are accepted");
        }
        String text = "application/pdf".equals(type) ? renderer.extractText(bytes)
                : Optional.ofNullable(ocr.getIfAvailable()).flatMap(o -> o.extractText(bytes, type)).orElse("");
        UUID id = UUID.randomUUID();
        Document d = new Document(id, nextNumber(), id, 1, documentType, null, title == null || title.isBlank() ? documentType : title,
                employeeId, caseId, classification, actor, clock.instant());
        String hash = sha256(bytes);
        d.setContent(type, bytes, hash, Base64.getEncoder().encodeToString(bytes));
        d.setExtractedText(text);
        d.sign(actor, clock.instant(), sha256((hash + "|" + actor).getBytes(StandardCharsets.UTF_8)));
        documents.saveAndFlush(d);
        index(d.getId(), text);
        audit.recordAs(actor, "MODULE", "DOCUMENT_UPLOAD", "DOCUMENT", id.toString(), AuditService.Outcome.SUCCESS,
                documentType + " textChars=" + text.length());
        return new Stored(view(d, false), text, hash);
    }

    /**
     * Generates and signs a template document in one step for a workflow the calling module has already authorised – e.g.
     * the released OH advice report (E06) or a vaccination certificate requested by the employee (E07). Merge fields are
     * validated exactly as in {@link #generate}.
     */
    @Transactional
    public DocumentView generateSigned(String actor, String templateCode, String employeeId, UUID caseId, Map<String, String> fields) {
        DocumentTemplates.Template t = templates.find(templateCode)
                .orElseThrow(() -> ApiException.badRequest("unknown_template", "Unknown document template"));
        DataClassification cls = t.containsClinical() ? DataClassification.CLINICAL : DataClassification.CONFIDENTIAL;
        Map<String, String> clean = validateFields(t, fields);
        UUID id = UUID.randomUUID();
        Document d = new Document(id, nextNumber(), id, 1, t.type(), t.code(), t.title(), employeeId, caseId, cls, actor, clock.instant());
        render(d, t, clean);
        Instant now = clock.instant();
        d.sign(actor, now, sha256((d.getSha256() + "|" + actor + "|" + now).getBytes(StandardCharsets.UTF_8)));
        documents.save(d);
        audit.recordAs(actor, "MODULE", "DOCUMENT_GENERATE_SIGNED", "DOCUMENT", id.toString(), AuditService.Outcome.SUCCESS, t.code());
        return view(d, true);
    }

    /**
     * Shares a signed document as a workflow step (employee consented to release, employee requested a certificate). The
     * non-clinical rule for managers / HR and the disclosure log apply exactly as for {@link #share}; partner shares are
     * not available here.
     */
    @Transactional
    public ShareView shareAsSystem(String actor, UUID id, DocumentShare.Target target) {
        if (target == DocumentShare.Target.PARTNER) {
            throw ApiException.badRequest("invalid_target", "Partner shares need the employee's consent and an OH user");
        }
        Document d = documents.findById(id).orElseThrow(() -> ApiException.notFound("Document"));
        if (d.getStatus() != Document.Status.SIGNED) {
            throw ApiException.conflict("not_signed", "Sign the document before sharing it");
        }
        return doShare(actor, d, target, null, null);
    }

    /** Raw content for a module endpoint that has already authorised the reader (e.g. a candidate's own upload). */
    @Transactional(readOnly = true)
    public Content systemContent(String actor, UUID id) {
        Document d = documents.findById(id).orElseThrow(() -> ApiException.notFound("Document"));
        audit.recordAs(actor, "MODULE", "DOCUMENT_DOWNLOAD", "DOCUMENT", id.toString(), AuditService.Outcome.SUCCESS, "MODULE");
        String ext = switch (d.getContentType()) {
            case "image/jpeg" -> ".jpg";
            case "image/png" -> ".png";
            default -> ".pdf";
        };
        return new Content(d.getDocumentNumber() + ext, d.getContentType(), Base64.getDecoder().decode(d.getContent()));
    }

    // ------------------------------------------------------------------ sharing

    @Transactional
    public ShareView share(CurrentUser user, UUID id, DocumentShare.Target target, String partnerOrg, UUID consentId) {
        Document d = requireIssuer(user, id, AccessPolicy.Action.EXPORT, "DOCUMENT_SHARE");
        if (!user.hasAnyRole(Roles.OH_TEAM) && target != DocumentShare.Target.EMPLOYEE) {
            throw ApiException.forbidden("HR may only issue its own documents to the employee");
        }
        if (d.getStatus() != Document.Status.SIGNED) {
            throw ApiException.conflict("not_signed", "Sign the document before sharing it");
        }
        if ("PHOTO".equals(d.getType())) {
            throw ApiException.forbidden("Clinical photographs are not shared outside Occupational Health");
        }
        return doShare(user.username(), d, target, partnerOrg, consentId);
    }

    private ShareView doShare(String actor, Document d, DocumentShare.Target target, String partnerOrg, UUID consentId) {
        UUID id = d.getId();
        DocumentTemplates.Template t = d.getTemplateCode() == null ? null : templates.find(d.getTemplateCode()).orElse(null);
        boolean clinical = t == null || t.containsClinical();
        Employee e = records.employee(d.getEmployeeId());
        String recipient;
        String recipientName;
        switch (target) {
            case EMPLOYEE -> {
                recipient = e.getEmployeeId();
                recipientName = "Employee (self)";
            }
            case MANAGER, HR -> {
                if (clinical) {
                    throw ApiException.forbidden("Documents with clinical content cannot be shared with managers or HR");
                }
                if (target == DocumentShare.Target.MANAGER && e.getManagerEmployeeId() == null) {
                    throw ApiException.badRequest("no_manager", "The employee has no line manager in the HRIS");
                }
                recipient = target == DocumentShare.Target.MANAGER ? e.getManagerEmployeeId() : null;
                recipientName = target == DocumentShare.Target.MANAGER
                        ? employees.findById(e.getManagerEmployeeId()).map(Employee::getDisplayName).orElse("Line manager") : "HR business partner";
            }
            case PARTNER -> {
                if (partnerOrg == null || !partnerOrg.matches("[A-Z0-9_]{2,40}")) {
                    throw ApiException.badRequest("partner_required", "Partner organisation code required");
                }
                if (!consents.isActive(consentId, e.getEmployeeId(), ConsentPurpose.TREATMENT_SHARING_PARTNER)) {
                    throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "consent_required",
                            "Sharing with a partner requires the employee's active consent to treatment sharing");
                }
                recipient = partnerOrg;
                recipientName = "Partner " + partnerOrg;
            }
            default -> throw ApiException.badRequest("invalid_target", "Unknown share target");
        }
        Instant now = clock.instant();
        DocumentShare s = shares.save(new DocumentShare(d.getId(), target, recipient, target == DocumentShare.Target.PARTNER ? consentId : null,
                actor, now));
        if (target != DocumentShare.Target.EMPLOYEE) {
            disclosures.record(e.getEmployeeId(), target.name(), recipientName, "DOCUMENT_SHARE", s.getId().toString(),
                    d.getTitle() + " (" + d.getDocumentNumber() + ")", target == DocumentShare.Target.PARTNER ? "TREATMENT" : "FITNESS_FOR_WORK",
                    s.getConsentId(), actor);
        }
        notifyShare(d, e, target);
        audit.record("DOCUMENT_SHARE", "DOCUMENT", id.toString(), AuditService.Outcome.SUCCESS, target.name());
        return shareView(s);
    }

    @Transactional
    public void revokeShare(CurrentUser user, UUID id, UUID shareId) {
        requireOh(user, id, AccessPolicy.Action.UPDATE, "DOCUMENT_SHARE_REVOKE");
        DocumentShare s = shares.findById(shareId).filter(x -> x.getDocumentId().equals(id))
                .orElseThrow(() -> ApiException.notFound("Share"));
        s.revoke(user.username(), "Revoked by Occupational Health", clock.instant());
        disclosures.revokeResource("DOCUMENT_SHARE", shareId.toString(), "Share revoked");
        audit.record("DOCUMENT_SHARE_REVOKE", "DOCUMENT", id.toString(), AuditService.Outcome.SUCCESS, s.getTarget().name());
    }

    /** Consent withdrawal: partner shares made under the consent stop immediately (called by the consent listener). */
    @Transactional
    public int revokeByConsent(UUID consentId) {
        List<DocumentShare> list = shares.findByConsentIdAndRevokedAtIsNull(consentId);
        list.forEach(s -> s.revoke("employee", "Consent withdrawn", clock.instant()));
        return list.size();
    }

    // ------------------------------------------------------------------ reads

    @Transactional(readOnly = true)
    public List<DocumentView> list(CurrentUser user, String employeeId, UUID caseId, String type) {
        records.requireClinical(user, employeeId, DataClassification.CLINICAL, AccessPolicy.Action.READ, "DOCUMENT_LIST");
        Employee e = records.employee(employeeId);
        boolean restricted = records.canSeeClinical(user, e, DataClassification.RESTRICTED_CLINICAL);
        List<Document> list = documents.findByEmployeeIdOrderByCreatedAtDesc(employeeId).stream()
                .filter(d -> caseId == null || caseId.equals(d.getCaseId()))
                .filter(d -> type == null || type.equals(d.getType()))
                .filter(d -> restricted || d.getClassification() != DataClassification.RESTRICTED_CLINICAL)
                .toList();
        audit.record("DOCUMENT_LIST", "EMPLOYEE", employeeId, AuditService.Outcome.SUCCESS, "rows=" + list.size());
        return views(list, true);
    }

    /** Employee of a case (case-scoped document lists); unknown cases are reported as 404. */
    @Transactional(readOnly = true)
    public String employeeOfCase(UUID caseId) {
        return cases.findById(caseId).map(com.wellnessz.casekernel.model.OhCase::getEmployeeId).orElseThrow(() -> ApiException.notFound("Case"));
    }

    @Transactional(readOnly = true)
    public DocumentView get(CurrentUser user, UUID id) {
        Document d = documents.findById(id).orElseThrow(() -> ApiException.notFound("Document"));
        boolean oh = isOhReader(user, d);
        if (!oh && !sharedWith(user, d)) {
            audit.record("DOCUMENT_READ", "DOCUMENT", id.toString(), AuditService.Outcome.DENIED, "Not shared");
            throw ApiException.notFound("Document");
        }
        audit.record("DOCUMENT_READ", "DOCUMENT", id.toString(), AuditService.Outcome.SUCCESS, oh ? "OH" : "SHARED");
        return view(d, oh);
    }

    @Transactional(readOnly = true)
    public Content content(CurrentUser user, UUID id) {
        Document d = documents.findById(id).orElseThrow(() -> ApiException.notFound("Document"));
        boolean oh = isOhReader(user, d);
        if (!oh && !sharedWith(user, d)) {
            audit.record("DOCUMENT_DOWNLOAD", "DOCUMENT", id.toString(), AuditService.Outcome.DENIED, "Not shared");
            throw ApiException.notFound("Document");
        }
        audit.record("DOCUMENT_DOWNLOAD", "DOCUMENT", id.toString(), AuditService.Outcome.SUCCESS, oh ? "OH" : "SHARED");
        String ext = switch (d.getContentType()) {
            case "image/jpeg" -> ".jpg";
            case "image/png" -> ".png";
            default -> ".pdf";
        };
        return new Content(d.getDocumentNumber() + ext, d.getContentType(), Base64.getDecoder().decode(d.getContent()));
    }

    @Transactional(readOnly = true)
    public List<DocumentView> sharedWithMe(CurrentUser user) {
        List<DocumentShare> mine = new ArrayList<>();
        if (user.employeeId() != null) {
            mine.addAll(shares.findByTargetAndRecipientAndRevokedAtIsNull(DocumentShare.Target.EMPLOYEE, user.employeeId()));
        }
        return sharedDocuments(mine, d -> user.employeeId() != null && user.employeeId().equals(d.getEmployeeId()));
    }

    @Transactional(readOnly = true)
    public List<DocumentView> sharedWithWorkspace(CurrentUser user) {
        List<DocumentShare> list = new ArrayList<>();
        if (user.hasRole(Roles.MANAGER) && user.employeeId() != null) {
            list.addAll(shares.findByTargetAndRecipientAndRevokedAtIsNull(DocumentShare.Target.MANAGER, user.employeeId()));
        }
        if (user.hasRole(Roles.HR)) {
            list.addAll(shares.findByTargetAndRevokedAtIsNull(DocumentShare.Target.HR));
        }
        List<DocumentView> out = sharedDocuments(list, d -> sharedWith(user, d));
        audit.record("WORKSPACE_DOCUMENTS_READ", "DOCUMENT", null, AuditService.Outcome.SUCCESS, "rows=" + out.size());
        return out;
    }

    @Transactional(readOnly = true)
    public List<DocumentView> sharedWithPartner(CurrentUser user) {
        String org = partnerOrg(user);
        if (org == null) {
            return List.of();
        }
        List<DocumentView> out = sharedDocuments(shares.findByTargetAndRecipientAndRevokedAtIsNull(DocumentShare.Target.PARTNER, org),
                d -> sharedWith(user, d));
        audit.record("PARTNER_DOCUMENTS_READ", "DOCUMENT", null, AuditService.Outcome.SUCCESS, "partner=" + org + " rows=" + out.size());
        return out;
    }

    @Transactional
    public DocumentView acknowledge(CurrentUser user, UUID id) {
        Document d = documents.findById(id).orElseThrow(() -> ApiException.notFound("Document"));
        if (user.employeeId() == null || !user.employeeId().equals(d.getEmployeeId()) || !sharedWith(user, d)) {
            throw ApiException.notFound("Document");
        }
        d.acknowledge(clock.instant());
        audit.record("DOCUMENT_ACKNOWLEDGE", "DOCUMENT", id.toString(), AuditService.Outcome.SUCCESS, null);
        return view(d, false);
    }

    /** Full-text search over generated and scanned content through keyed blind-index tokens (no plaintext index). */
    @Transactional(readOnly = true)
    public List<SearchHit> search(CurrentUser user, String employeeId, String q) {
        records.requireClinical(user, employeeId, DataClassification.CLINICAL, AccessPolicy.Action.READ, "DOCUMENT_SEARCH");
        Employee e = records.employee(employeeId);
        boolean restricted = records.canSeeClinical(user, e, DataClassification.RESTRICTED_CLINICAL);
        Set<String> words = words(q);
        if (words.isEmpty()) {
            return List.of();
        }
        String tsquery = String.join(" & ", words.stream().map(this::token).toList());
        List<UUID> ids = jdbc.query("select id from docs.document where employee_id = ? and search_vector @@ to_tsquery('simple', ?) "
                + "order by created_at desc limit 50", (rs, i) -> rs.getObject(1, UUID.class), employeeId, tsquery);
        List<SearchHit> hits = new ArrayList<>();
        for (Document d : documents.findByIdIn(ids)) {
            if (!restricted && d.getClassification() == DataClassification.RESTRICTED_CLINICAL) {
                continue;
            }
            hits.add(new SearchHit(view(d, true), snippet(d.getExtractedText(), words)));
        }
        hits.sort((a, b) -> b.document().createdAt().compareTo(a.document().createdAt()));
        audit.record("DOCUMENT_SEARCH", "EMPLOYEE", employeeId, AuditService.Outcome.SUCCESS, "hits=" + hits.size());
        return hits;
    }

    public List<DocumentTemplates.Template> templates(CurrentUser user) {
        return templates.all().stream().filter(t -> t.generatorRoles().stream().anyMatch(user::hasRole)).toList();
    }

    // ------------------------------------------------------------------ retention & legal hold

    @Transactional
    public DocumentView legalHold(CurrentUser user, UUID id, boolean hold, String reason) {
        Document d = documents.findById(id).orElseThrow(() -> ApiException.notFound("Document"));
        for (Document v : documents.findBySeriesIdOrderByVersionAsc(d.getSeriesId())) {
            v.setLegalHold(hold, hold ? reason : null);
        }
        audit.record(hold ? "LEGAL_HOLD_SET" : "LEGAL_HOLD_RELEASED", "DOCUMENT", id.toString(), AuditService.Outcome.SUCCESS, null);
        return view(d, false);
    }

    // ------------------------------------------------------------------ helpers

    private void authoriseGenerate(CurrentUser user, String employeeId, DataClassification cls) {
        if (user.hasAnyRole(Roles.OH_TEAM)) {
            records.requireClinical(user, employeeId, cls, AccessPolicy.Action.CREATE, "DOCUMENT_GENERATE");
        } else {
            records.requireStatus(user, employeeId, "DOCUMENT_GENERATE");
        }
    }

    private Map<String, String> validateFields(DocumentTemplates.Template t, Map<String, String> fields) {
        Map<String, String> in = fields == null ? Map.of() : fields;
        Map<String, String> clean = new LinkedHashMap<>();
        for (DocumentTemplates.MergeField f : t.mergeFields()) {
            String v = in.get(f.key());
            if (v == null || v.isBlank()) {
                if (f.required()) {
                    throw ApiException.badRequest("missing_field", "Required field missing: " + f.key());
                }
                clean.put(f.key(), "–");
                continue;
            }
            if (v.length() > f.maxLength()) {
                throw ApiException.badRequest("too_long", "Field '" + f.key() + "' is too long");
            }
            if (!t.containsClinical()) {
                ClinicalContentException.screen(f.key(), v);
            }
            clean.put(f.key(), v.strip());
        }
        for (String key : in.keySet()) {
            if (t.mergeFields().stream().noneMatch(f -> f.key().equals(key))) {
                throw ApiException.badRequest("unknown_field", "Unknown field: " + key);
            }
        }
        return clean;
    }

    private void render(Document d, DocumentTemplates.Template t, Map<String, String> fields) {
        List<PdfRenderer.Section> sections = new ArrayList<>();
        for (String paragraph : t.body()) {
            sections.add(new PdfRenderer.Section(null, merge(paragraph, fields)));
        }
        sections.add(new PdfRenderer.Section(null, "Generated " + LocalDate.now(clock.withZone(ZoneOffset.UTC)) + " by " + d.getCreatedBy()
                + ". Electronic signature and integrity hash are recorded in the WellnessZ document register."));
        String footer = t.containsClinical() ? "CONFIDENTIAL – Occupational Health medical-in-confidence"
                : "CONFIDENTIAL – contains no clinical information";
        PdfRenderer.Rendered r = renderer.render(t.title(), d.getDocumentNumber(), d.getVersion(), sections, footer);
        d.setContent("application/pdf", r.pdf(), sha256(r.pdf()), Base64.getEncoder().encodeToString(r.pdf()));
        d.setFields(json.writeValueAsString(fields));
        d.setExtractedText(r.text());
        documents.saveAndFlush(d);
        index(d.getId(), r.text());
    }

    static String merge(String paragraph, Map<String, String> fields) {
        Matcher m = PLACEHOLDER.matcher(paragraph);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            m.appendReplacement(out, Matcher.quoteReplacement(fields.getOrDefault(m.group(1), "–")));
        }
        m.appendTail(out);
        return out.toString();
    }

    private void index(UUID id, String text) {
        String tokens = String.join(" ", words(text).stream().limit(5000).map(this::token).toList());
        jdbc.update("update docs.document set search_vector = to_tsvector('simple', ?) where id = ?", tokens, id);
    }

    /** Keyed blind-index token of a normalised word: searchable for equality, meaningless without the key. */
    private String token(String word) {
        String b = encryptor.blindIndex("doc-search:" + word);
        return "t" + b.substring(0, 20).replace('-', 'x').replace('_', 'y').toLowerCase(Locale.ROOT);
    }

    static Set<String> words(String text) {
        Set<String> out = new LinkedHashSet<>();
        if (text == null) {
            return out;
        }
        Matcher m = WORD.matcher(text.toLowerCase(Locale.ROOT));
        while (m.find()) {
            out.add(m.group());
        }
        return out;
    }

    static String snippet(String text, Set<String> words) {
        if (text == null || text.isBlank()) {
            return "";
        }
        String lower = text.toLowerCase(Locale.ROOT);
        int at = words.stream().mapToInt(lower::indexOf).filter(i -> i >= 0).min().orElse(0);
        int from = Math.max(0, at - 60);
        int to = Math.min(text.length(), at + 140);
        return (from > 0 ? "…" : "") + text.substring(from, to).replaceAll("\\s+", " ").strip() + (to < text.length() ? "…" : "");
    }

    static String classify(String text) {
        String t = text == null ? "" : text.toLowerCase(Locale.ROOT);
        if (t.contains("discharge")) {
            return "Discharge summary";
        }
        if (t.contains("audiogram") || t.contains("audiometr")) {
            return "Audiometry report";
        }
        if (t.contains("spirometr")) {
            return "Spirometry report";
        }
        if (t.contains("x-ray") || t.contains("radiolog") || t.contains("mri") || t.contains("ultrasound") || t.contains("imaging")) {
            return "Imaging report";
        }
        if (t.contains("laborator") || t.contains("haemoglobin") || t.contains("hemoglobin") || t.contains("specimen")) {
            return "Laboratory report";
        }
        if (t.contains("physiotherap")) {
            return "Physiotherapy report";
        }
        if (t.contains("dear dr") || t.contains("consultant") || t.contains("clinic letter")) {
            return "Specialist letter";
        }
        return "Unclassified";
    }

    static String sniff(byte[] b) {
        if (b.length > 3 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8 && (b[2] & 0xFF) == 0xFF) {
            return "image/jpeg";
        }
        if (b.length > 8 && (b[0] & 0xFF) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G') {
            return "image/png";
        }
        if (b.length > 4 && b[0] == '%' && b[1] == 'P' && b[2] == 'D' && b[3] == 'F') {
            return "application/pdf";
        }
        return null;
    }

    /**
     * OH team members act on any document they may read clinically; HR acts only on non-clinical templates it is
     * allowed to generate (the alternative-duty job offer) for employees inside its org scope.
     */
    private Document requireIssuer(CurrentUser user, UUID id, AccessPolicy.Action action, String auditAction) {
        if (user.hasAnyRole(Roles.OH_TEAM)) {
            return requireOh(user, id, action, auditAction);
        }
        Document d = documents.findById(id).orElseThrow(() -> ApiException.notFound("Document"));
        DocumentTemplates.Template t = d.getTemplateCode() == null ? null : templates.find(d.getTemplateCode()).orElse(null);
        if (t == null || t.containsClinical() || t.generatorRoles().stream().noneMatch(user::hasRole)) {
            audit.record(auditAction, "DOCUMENT", id.toString(), AuditService.Outcome.DENIED, "Not an issuer of this template");
            throw ApiException.notFound("Document");
        }
        records.requireStatus(user, d.getEmployeeId(), auditAction);
        return d;
    }

    private Document requireOh(CurrentUser user, UUID id, AccessPolicy.Action action, String auditAction) {
        Document d = documents.findById(id).orElseThrow(() -> ApiException.notFound("Document"));
        if (!user.hasAnyRole(Roles.OH_TEAM)) {
            audit.record(auditAction, "DOCUMENT", id.toString(), AuditService.Outcome.DENIED, "Not OH");
            throw ApiException.notFound("Document");
        }
        records.requireClinical(user, d.getEmployeeId(), d.getClassification() == DataClassification.CONFIDENTIAL
                ? DataClassification.CLINICAL : d.getClassification(), action, auditAction);
        return d;
    }

    private boolean isOhReader(CurrentUser user, Document d) {
        if (!user.hasAnyRole(Roles.OH_TEAM)) {
            return false;
        }
        Employee e = employees.findById(d.getEmployeeId()).orElse(null);
        return e != null && records.canSeeClinical(user, e, d.getClassification() == DataClassification.RESTRICTED_CLINICAL
                ? DataClassification.RESTRICTED_CLINICAL : DataClassification.CLINICAL);
    }

    private boolean sharedWith(CurrentUser user, Document d) {
        if (d.getStatus() != Document.Status.SIGNED) {
            return false;
        }
        for (DocumentShare s : shares.findByDocumentIdOrderBySharedAtAsc(d.getId())) {
            if (!s.isActive()) {
                continue;
            }
            boolean ok = switch (s.getTarget()) {
                case EMPLOYEE -> user.employeeId() != null && user.employeeId().equals(d.getEmployeeId());
                case MANAGER -> user.hasRole(Roles.MANAGER) && user.employeeId() != null && user.employeeId().equals(s.getRecipient());
                case HR -> user.hasRole(Roles.HR) && inScope(user, d.getEmployeeId());
                case PARTNER -> user.hasRole(Roles.PARTNER_PROVIDER) && s.getRecipient().equals(partnerOrg(user))
                        && consents.isActive(s.getConsentId(), d.getEmployeeId(), ConsentPurpose.TREATMENT_SHARING_PARTNER);
            };
            if (ok) {
                return true;
            }
        }
        return false;
    }

    private boolean inScope(CurrentUser user, String employeeId) {
        try {
            records.requireStatus(user, employeeId, "DOCUMENT_SCOPE_CHECK");
            return true;
        } catch (ApiException e) {
            return false;
        }
    }

    static String partnerOrg(CurrentUser user) {
        if (!user.hasRole(Roles.PARTNER_PROVIDER) || user.orgScope() == null) {
            return null;
        }
        String s = user.orgScope();
        String last = s.substring(s.lastIndexOf('/') + 1);
        return last.matches("[A-Z0-9_]{2,40}") ? last : null;
    }

    private List<DocumentView> sharedDocuments(List<DocumentShare> list, java.util.function.Predicate<Document> visible) {
        Set<UUID> ids = new LinkedHashSet<>();
        list.forEach(s -> ids.add(s.getDocumentId()));
        return documents.findByIdIn(ids).stream().filter(visible)
                .sorted((a, b) -> b.getCreatedAt().compareTo(a.getCreatedAt())).map(d -> view(d, false)).toList();
    }

    private void notifyShare(Document d, Employee e, DocumentShare.Target target) {
        if ("JOB_OFFER".equals(d.getType())) {
            return; // the job-offer workflow sends its own "offer issued" nudge with the respond-by date
        }
        String template = "FITNESS_CERTIFICATE".equals(d.getType()) ? "certificate.available" : "document.shared";
        if (target == DocumentShare.Target.EMPLOYEE && e.getEmail() != null) {
            notifications.enqueue(e.getEmployeeId(), e.getEmail(), Channel.EMAIL, Priority.NORMAL, template,
                    Map.of("firstName", firstName(e.getDisplayName()), "documentNumber", d.getDocumentNumber(), "portalLink", employeePortal + "/#/documents"),
                    ZoneId.of("UTC"));
        }
        if (target == DocumentShare.Target.MANAGER) {
            employees.findById(e.getManagerEmployeeId()).filter(m -> m.getEmail() != null).ifPresent(m -> notifications.enqueue(m.getEmployeeId(),
                    m.getEmail(), Channel.EMAIL, Priority.NORMAL, "document.shared", Map.of("firstName", firstName(m.getDisplayName()),
                            "documentNumber", d.getDocumentNumber(), "portalLink", managerPortal + "/#/documents"), ZoneId.of("UTC")));
        }
    }

    static String firstName(String displayName) {
        return displayName == null ? "" : displayName.split(" ")[0];
    }

    private List<DocumentView> views(List<Document> list, boolean oh) {
        if (list.isEmpty()) {
            return List.of();
        }
        Map<UUID, List<DocumentShare>> byDoc = new LinkedHashMap<>();
        shares.findByDocumentIdInOrderBySharedAtAsc(list.stream().map(Document::getId).toList())
                .forEach(s -> byDoc.computeIfAbsent(s.getDocumentId(), k -> new ArrayList<>()).add(s));
        return list.stream().map(d -> view(d, oh, byDoc.getOrDefault(d.getId(), List.of()))).toList();
    }

    public DocumentView view(Document d, boolean oh) {
        return view(d, oh, oh ? shares.findByDocumentIdOrderBySharedAtAsc(d.getId()) : List.of());
    }

    private DocumentView view(Document d, boolean oh, List<DocumentShare> list) {
        return new DocumentView(d.getId(), d.getDocumentNumber(), d.getType(), d.getTemplateCode(), d.getTitle(), d.getEmployeeId(), d.getCaseId(),
                d.getVersion(), d.getStatus().name(), d.getContentType(), d.getSizeBytes(), d.getSha256(), d.getSignedBy(), d.getSignedAt(),
                oh ? d.getCreatedBy() : null, d.getCreatedAt(), d.isLegalHold(), oh ? list.stream().map(DocumentService::shareView).toList() : null,
                d.getAcknowledgedAt());
    }

    private static ShareView shareView(DocumentShare s) {
        return new ShareView(s.getId(), s.getTarget().name(), s.getTarget() == DocumentShare.Target.PARTNER ? s.getRecipient() : null,
                s.getSharedAt(), s.getSharedBy(), s.getRevokedAt());
    }

    private String nextNumber() {
        int year = LocalDate.now(clock.withZone(ZoneOffset.UTC)).getYear();
        Long n = jdbc.queryForObject("""
                insert into docs.document_sequence (year, next_val) values (?, 2)
                on conflict (year) do update set next_val = docs.document_sequence.next_val + 1
                returning next_val - 1""", Long.class, year);
        return "DOC-%d-%06d".formatted(year, n);
    }

    static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
