package com.wellnessz.e08.disclosure;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.wellnessz.casekernel.support.CaseTimeline;
import com.wellnessz.casekernel.support.EmployeeRecordAccess;
import com.wellnessz.common.error.ApiException;
import com.wellnessz.common.model.DataClassification;
import com.wellnessz.common.model.Roles;
import com.wellnessz.documents.DocumentService;
import com.wellnessz.documents.PdfRenderer;
import com.wellnessz.documents.model.Document;
import com.wellnessz.documents.model.DocumentRepository;
import com.wellnessz.documents.model.DocumentShare;
import com.wellnessz.documents.model.DocumentShareRepository;
import com.wellnessz.e08.model.Claim;
import com.wellnessz.e08.model.ClaimEnums.DisclosureSetStatus;
import com.wellnessz.e08.model.DisclosureSet;
import com.wellnessz.e08.model.DisclosureSetDocument;
import com.wellnessz.e08.model.E08DisclosureSetDocumentRepository;
import com.wellnessz.e08.model.E08DisclosureSetRepository;
import com.wellnessz.e08.model.E08LegalReportRepository;
import com.wellnessz.e08.model.E08LegalRequestRepository;
import com.wellnessz.e08.model.LegalReport;
import com.wellnessz.e08.model.LegalRequest;
import com.wellnessz.e08.support.ClaimAccess;
import com.wellnessz.e08.support.ClaimsSupport;
import com.wellnessz.e08.support.InsurerConsent;
import com.wellnessz.masterdata.Employee;
import com.wellnessz.masterdata.PartnerOrganisation;
import com.wellnessz.security.audit.AuditService;
import com.wellnessz.security.disclosure.DisclosureLogService;
import com.wellnessz.security.identity.CurrentUser;

/**
 * Consent-gated document exchange with insurers / TPAs (E08-F03): disclosure sets built from the claim employee's documents
 * under {@link DisclosureRules}, OH redaction into a REDACTED_COPY, sending (active INSURER_SHARING consent checked, one
 * INSURER share per document through the document service, which writes the disclosure log), revocation of the shares, and
 * the DPO disclosure report.
 */
@Service
public class DisclosureSetService {

    public record SetDocument(UUID documentId, String title, String type, UUID redactedFromId) {
    }

    public record SetView(UUID id, UUID claimId, String claimRef, String recipientCode, String recipientName, String purpose, String status,
                          List<SetDocument> documents, UUID consentId, Instant sentAt, String sentBy, Instant revokedAt, String revokedBy,
                          String createdBy, Instant createdAt) {
    }

    public record CandidateView(UUID documentId, String documentNumber, String title, String type, Instant createdAt, boolean disclosable,
                                String reason) {
    }

    public record DisclosureRow(UUID id, Instant at, String employeeId, String recipientType, String recipientName, String resourceType,
                                String resourceId, String document, String purpose, UUID consentId, String by, String status, Instant revokedAt) {
    }

    private final E08DisclosureSetRepository sets;
    private final E08DisclosureSetDocumentRepository setDocuments;
    private final E08LegalRequestRepository legalRequests;
    private final E08LegalReportRepository legalReports;
    private final DocumentRepository documentRepository;
    private final DocumentShareRepository shares;
    private final DocumentService documents;
    private final PdfRenderer renderer;
    private final EmployeeRecordAccess records;
    private final InsurerConsent consent;
    private final ClaimAccess access;
    private final ClaimsSupport support;
    private final DisclosureLogService disclosures;
    private final CaseTimeline timeline;
    private final JdbcTemplate jdbc;
    private final AuditService audit;

    public DisclosureSetService(E08DisclosureSetRepository sets, E08DisclosureSetDocumentRepository setDocuments,
                                E08LegalRequestRepository legalRequests, E08LegalReportRepository legalReports, DocumentRepository documentRepository,
                                DocumentShareRepository shares, DocumentService documents, PdfRenderer renderer, EmployeeRecordAccess records,
                                InsurerConsent consent, ClaimAccess access, ClaimsSupport support, DisclosureLogService disclosures,
                                CaseTimeline timeline, JdbcTemplate jdbc, AuditService audit) {
        this.sets = sets;
        this.setDocuments = setDocuments;
        this.legalRequests = legalRequests;
        this.legalReports = legalReports;
        this.documentRepository = documentRepository;
        this.shares = shares;
        this.documents = documents;
        this.renderer = renderer;
        this.records = records;
        this.consent = consent;
        this.access = access;
        this.support = support;
        this.disclosures = disclosures;
        this.timeline = timeline;
        this.jdbc = jdbc;
        this.audit = audit;
    }

    // ------------------------------------------------------------------ candidates

    /** Documents of the claim's employee with their disclosability for this actor (titles and types only – no content). */
    @Transactional(readOnly = true)
    public List<CandidateView> candidates(CurrentUser user, UUID claimId) {
        Claim c = access.require(user, claimId, writer(user), "CLAIM_DOCUMENTS_LIST");
        boolean oh = actsAsOh(user, c);
        Set<UUID> privileged = privilegedDocuments(c);
        List<CandidateView> out = new ArrayList<>();
        for (Document d : documentRepository.findByEmployeeIdOrderByCreatedAtDesc(c.getEmployeeId())) {
            if (d.getClassification() == DataClassification.RESTRICTED_CLINICAL || d.getStatus() == Document.Status.SUPERSEDED
                    || d.getStatus() == Document.Status.REVOKED) {
                continue; // restricted class is not even listed outside the restricted team
            }
            String reason = DisclosureRules.refusal(candidate(d, privileged), c.getEmployeeId(), oh).orElse(null);
            out.add(new CandidateView(d.getId(), d.getDocumentNumber(), d.getTitle(), d.getType(), d.getCreatedAt(), reason == null, reason));
        }
        audit.record("CLAIM_DOCUMENTS_LIST", "CLAIM", claimId.toString(), AuditService.Outcome.SUCCESS, "rows=" + out.size());
        return out;
    }

    /** The claim employee's active INSURER_SHARING consents (to pick one when sending a set). */
    @Transactional(readOnly = true)
    public List<InsurerConsent.ConsentView> consents(CurrentUser user, UUID claimId) {
        Claim c = access.require(user, claimId, writer(user), "CLAIM_CONSENTS_READ");
        List<InsurerConsent.ConsentView> out = consent.active(c.getEmployeeId());
        audit.record("CLAIM_CONSENTS_READ", "CLAIM", claimId.toString(), AuditService.Outcome.SUCCESS, "rows=" + out.size());
        return out;
    }

    // ------------------------------------------------------------------ sets

    @Transactional
    public SetView create(CurrentUser user, UUID claimId, String recipientCode, String purpose, List<UUID> documentIds) {
        Claim c = access.require(user, claimId, writer(user), "CLAIM_DISCLOSURE_CREATE");
        PartnerOrganisation recipient = support.requirePayer(recipientCode);
        String why = ClaimsSupport.screened("purpose", purpose, 500);
        if (why == null) {
            throw ApiException.badRequest("purpose_required", "A purpose is required");
        }
        if (documentIds == null || documentIds.isEmpty() || documentIds.size() > 50) {
            throw ApiException.badRequest("invalid_documents", "Between 1 and 50 documents");
        }
        boolean oh = actsAsOh(user, c);
        Set<UUID> privileged = privilegedDocuments(c);
        Instant now = support.now();
        DisclosureSet s = DisclosureSet.create();
        s.setClaimId(c.getId());
        s.setRecipientCode(recipient.getCode());
        s.setPurpose(why);
        s.setStatus(DisclosureSetStatus.DRAFT.name());
        s.setCreatedBy(user.username());
        s.setCreatedAt(now);
        sets.save(s);
        int pos = 0;
        for (UUID docId : new LinkedHashSet<>(documentIds)) {
            Document d = documentRepository.findById(docId).orElse(null);
            String reason = d == null ? "unknown document" : DisclosureRules.refusal(candidate(d, privileged), c.getEmployeeId(), oh).orElse(null);
            if (reason != null) {
                throw ClaimsSupport.unprocessable("document_not_disclosable", "Document " + docId + " cannot be disclosed: " + reason);
            }
            DisclosureSetDocument e = DisclosureSetDocument.create();
            e.setSetId(s.getId());
            e.setPosition(pos++);
            e.setDocumentId(d.getId());
            e.setTitle(d.getTitle() + " (" + d.getDocumentNumber() + ")");
            e.setDocType(d.getType());
            e.setAddedBy(user.username());
            e.setAddedByOh(oh);
            setDocuments.save(e);
        }
        audit.record("CLAIM_DISCLOSURE_CREATE", "DISCLOSURE_SET", s.getId().toString(), AuditService.Outcome.SUCCESS, "documents=" + pos);
        return view(s, c);
    }

    @Transactional(readOnly = true)
    public List<SetView> list(CurrentUser user, UUID claimId) {
        Claim c = access.require(user, claimId, x -> user.hasRole(Roles.DPO) || writer(user).test(x), "CLAIM_DISCLOSURE_LIST");
        return sets.findByClaimIdOrderByCreatedAtAsc(claimId).stream().map(s -> view(s, c)).toList();
    }

    /** OH redaction: the document's text is re-rendered with the phrases masked; the REDACTED_COPY replaces it in the set. */
    @Transactional
    public SetView redact(CurrentUser user, UUID setId, UUID documentId, List<String> phrases) {
        DisclosureSet s = sets.findById(setId).orElseThrow(() -> ApiException.notFound("Disclosure set"));
        Claim c = access.require(user, s.getClaimId(), x -> actsAsOh(user, x), "CLAIM_DISCLOSURE_REDACT");
        requireDraft(s);
        List<String> clean = Redaction.validate(phrases);
        DisclosureSetDocument entry = setDocuments.findBySetIdOrderByPositionAsc(setId).stream()
                .filter(e -> e.getDocumentId().equals(documentId)).findFirst()
                .orElseThrow(() -> ApiException.notFound("Document in disclosure set"));
        Document original = documentRepository.findById(documentId).orElseThrow(() -> ApiException.notFound("Document"));
        String text = original.getExtractedText();
        if (text == null || text.isBlank()) {
            throw ClaimsSupport.unprocessable("no_text_layer", "The document has no text layer that could be redacted");
        }
        Redaction.Result masked = Redaction.mask(text, clean);
        List<PdfRenderer.Section> sections = new ArrayList<>();
        sections.add(new PdfRenderer.Section(null, "Redacted copy of " + original.getDocumentNumber() + " – " + masked.replacements()
                + " passage(s) withheld by Occupational Health."));
        sections.add(new PdfRenderer.Section(null, masked.text()));
        PdfRenderer.Rendered pdf = renderer.render("Redacted copy – " + original.getTitle(), original.getDocumentNumber() + "-R", 1, sections,
                "CONFIDENTIAL – redacted copy for disclosure to the insurer under the employee's consent");
        DocumentService.Stored stored = documents.storeUpload(user.username(), original.getEmployeeId(), original.getCaseId(), "REDACTED_COPY",
                "Redacted copy of " + original.getDocumentNumber(), pdf.pdf(),
                original.getClassification() == DataClassification.CONFIDENTIAL ? DataClassification.CONFIDENTIAL : DataClassification.CLINICAL);
        entry.setRedactedFromId(entry.getRedactedFromId() != null ? entry.getRedactedFromId() : original.getId());
        entry.setDocumentId(stored.document().id());
        entry.setTitle(stored.document().title() + " (" + stored.document().documentNumber() + ")");
        entry.setDocType("REDACTED_COPY");
        audit.record("CLAIM_DISCLOSURE_REDACT", "DOCUMENT", original.getId().toString(), AuditService.Outcome.SUCCESS,
                "phrases=" + clean.size() + " replacements=" + masked.replacements());
        return view(s, c);
    }

    @Transactional
    public SetView send(CurrentUser user, UUID setId, UUID consentId) {
        DisclosureSet s = sets.findById(setId).orElseThrow(() -> ApiException.notFound("Disclosure set"));
        Claim c = access.require(user, s.getClaimId(), writer(user), "CLAIM_DISCLOSURE_SEND");
        requireDraft(s);
        UUID consentToUse = consent.require(c.getEmployeeId(), consentId);
        Set<UUID> privileged = privilegedDocuments(c);
        List<DisclosureSetDocument> entries = setDocuments.findBySetIdOrderByPositionAsc(setId);
        if (entries.isEmpty()) {
            throw ClaimsSupport.unprocessable("empty_set", "The disclosure set has no documents");
        }
        for (DisclosureSetDocument e : entries) {
            Document d = documentRepository.findById(e.getDocumentId()).orElse(null);
            String reason = d == null ? "unknown document"
                    : DisclosureRules.refusal(candidate(d, privileged), c.getEmployeeId(), e.isAddedByOh()).orElse(null);
            if (reason != null) {
                throw ClaimsSupport.unprocessable("document_not_disclosable", "Document " + e.getDocumentId() + " cannot be disclosed: " + reason);
            }
        }
        for (DisclosureSetDocument e : entries) {
            DocumentService.ShareView share = documents.shareAsSystem(user.username(), e.getDocumentId(), DocumentShare.Target.INSURER,
                    s.getRecipientCode(), consentToUse);
            e.setShareId(share.id());
        }
        Instant now = support.now();
        s.setStatus(DisclosureSetStatus.SENT.name());
        s.setConsentId(consentToUse);
        s.setSentAt(now);
        s.setSentBy(user.username());
        timeline.record(c.getCaseId(), "CLAIM_DISCLOSURE_SENT", entries.size() + " document(s) disclosed to " + s.getRecipientCode()
                + " for claim " + c.getClaimRef() + " under consent");
        audit.record("CLAIM_DISCLOSURE_SEND", "DISCLOSURE_SET", setId.toString(), AuditService.Outcome.SUCCESS,
                "recipient=" + s.getRecipientCode() + " documents=" + entries.size());
        return view(s, c);
    }

    @Transactional
    public SetView revoke(CurrentUser user, UUID setId) {
        DisclosureSet s = sets.findById(setId).orElseThrow(() -> ApiException.notFound("Disclosure set"));
        Claim c = access.require(user, s.getClaimId(), ClaimAccess.roles(user, Roles.CLAIMS_COORDINATOR, Roles.DPO), "CLAIM_DISCLOSURE_REVOKE");
        if (DisclosureSetStatus.REVOKED.name().equals(s.getStatus())) {
            throw ApiException.conflict("already_revoked", "The disclosure set is already revoked");
        }
        Instant now = support.now();
        int revoked = 0;
        for (DisclosureSetDocument e : setDocuments.findBySetIdOrderByPositionAsc(setId)) {
            if (e.getShareId() == null) {
                continue;
            }
            DocumentShare share = shares.findById(e.getShareId()).orElse(null);
            if (share != null && share.isActive()) {
                share.revoke(user.username(), "Claim disclosure set revoked", now);
                disclosures.revokeResource("DOCUMENT_SHARE", share.getId().toString(), "Claim disclosure set revoked");
                revoked++;
            }
        }
        s.setStatus(DisclosureSetStatus.REVOKED.name());
        s.setRevokedAt(now);
        s.setRevokedBy(user.username());
        audit.record("CLAIM_DISCLOSURE_REVOKE", "DISCLOSURE_SET", setId.toString(), AuditService.Outcome.SUCCESS, "shares=" + revoked);
        return view(s, c);
    }

    // ------------------------------------------------------------------ DPO report

    @Transactional(readOnly = true)
    public List<DisclosureRow> report(LocalDate from, LocalDate to) {
        LocalDate f = from == null ? support.today().minusDays(30) : from;
        LocalDate t = to == null ? support.today() : to;
        if (t.isBefore(f)) {
            throw ApiException.badRequest("invalid_range", "'to' must not be before 'from'");
        }
        List<DisclosureRow> out = jdbc.query("""
                select id, disclosed_at, employee_id, recipient_type, recipient_name, resource_type, resource_id, resource_title, purpose,
                       consent_id, disclosed_by, revoked_at
                  from platform.disclosure_log
                 where disclosed_at >= ? and disclosed_at < ?
                   and (purpose in ('CLAIM', 'CLAIM_STATUS', 'LEGAL_PROCEEDINGS') or purpose like 'LEGAL_OBLIGATION%')
                 order by disclosed_at desc
                 limit 5000""", (rs, i) -> new DisclosureRow(rs.getObject("id", UUID.class), rs.getTimestamp("disclosed_at").toInstant(),
                rs.getString("employee_id"), rs.getString("recipient_type"), rs.getString("recipient_name"), rs.getString("resource_type"),
                rs.getString("resource_id"), rs.getString("resource_title"), rs.getString("purpose"), rs.getObject("consent_id", UUID.class),
                rs.getString("disclosed_by"), rs.getTimestamp("revoked_at") == null ? "ACTIVE" : "REVOKED",
                rs.getTimestamp("revoked_at") == null ? null : rs.getTimestamp("revoked_at").toInstant()),
                Timestamp.from(f.atStartOfDay(ZoneOffset.UTC).toInstant()), Timestamp.from(t.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant()));
        audit.record("CLAIM_DISCLOSURE_REPORT", "DISCLOSURE_LOG", null, AuditService.Outcome.SUCCESS, "rows=" + out.size());
        return out;
    }

    // ------------------------------------------------------------------ partner view

    /** Documents of SENT sets of the claim addressed to {@code org} whose share and consent are still active. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> sharedWith(Claim c, String org) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (DisclosureSet s : sets.findByClaimIdOrderByCreatedAtAsc(c.getId())) {
            if (!DisclosureSetStatus.SENT.name().equals(s.getStatus()) || !org.equals(s.getRecipientCode())
                    || !consent.isActive(s.getConsentId(), c.getEmployeeId())) {
                continue;
            }
            for (DisclosureSetDocument e : setDocuments.findBySetIdOrderByPositionAsc(s.getId())) {
                boolean active = e.getShareId() != null && shares.findById(e.getShareId()).map(DocumentShare::isActive).orElse(false);
                if (active) {
                    out.add(Map.of("documentId", e.getDocumentId(), "title", e.getTitle(), "type", e.getDocType(), "sharedAt", s.getSentAt()));
                }
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ helpers

    /** Claims team, or an OH role (clinical access to the employee is checked where OH-only material is involved). */
    private static java.util.function.Predicate<Claim> writer(CurrentUser user) {
        return c -> user.hasRole(Roles.CLAIMS_COORDINATOR) || ClaimsSupport.isOh(user);
    }

    private boolean actsAsOh(CurrentUser user, Claim c) {
        if (!ClaimsSupport.isOh(user)) {
            return false;
        }
        Employee e = support.employee(c.getEmployeeId()).orElse(null);
        return e != null && records.canSeeClinical(user, e, DataClassification.CLINICAL);
    }

    private static void requireDraft(DisclosureSet s) {
        if (!DisclosureSetStatus.DRAFT.name().equals(s.getStatus())) {
            throw ApiException.conflict("not_draft", "Only draft disclosure sets can be changed or sent");
        }
    }

    /** While the claim is marked privileged, the reports answering its legal requests (medico-legal, IME) are privileged material. */
    Set<UUID> privilegedDocuments(Claim c) {
        if (!c.isPrivileged()) {
            return Set.of();
        }
        Set<UUID> out = new HashSet<>();
        List<UUID> requestIds = legalRequests.findByClaimIdOrderByCreatedAtAsc(c.getId()).stream().map(LegalRequest::getId).toList();
        if (!requestIds.isEmpty()) {
            for (LegalReport r : legalReports.findByRequestIdInOrderByVersionAsc(requestIds)) {
                out.add(r.getDocumentId());
            }
        }
        return out;
    }

    private static DisclosureRules.Candidate candidate(Document d, Set<UUID> privileged) {
        return new DisclosureRules.Candidate(d.getType(), d.getEmployeeId(), d.getStatus() == Document.Status.SIGNED,
                d.getClassification() == DataClassification.RESTRICTED_CLINICAL, privileged.contains(d.getId()) || privileged.contains(d.getSeriesId()));
    }

    private SetView view(DisclosureSet s, Claim c) {
        List<SetDocument> docs = setDocuments.findBySetIdOrderByPositionAsc(s.getId()).stream()
                .map(e -> new SetDocument(e.getDocumentId(), e.getTitle(), e.getDocType(), e.getRedactedFromId())).toList();
        return new SetView(s.getId(), s.getClaimId(), c.getClaimRef(), s.getRecipientCode(), support.partnerName(s.getRecipientCode()), s.getPurpose(),
                s.getStatus(), docs, s.getConsentId(), s.getSentAt(), s.getSentBy(), s.getRevokedAt(), s.getRevokedBy(), s.getCreatedBy(),
                s.getCreatedAt());
    }
}
