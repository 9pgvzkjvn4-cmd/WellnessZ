package com.wellnessz.e08.legal;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.wellnessz.casekernel.model.CaseTask;
import com.wellnessz.casekernel.model.OhCase;
import com.wellnessz.casekernel.model.OhCaseRepository;
import com.wellnessz.casekernel.support.CaseAccess;
import com.wellnessz.casekernel.support.CaseTimeline;
import com.wellnessz.casekernel.support.CaseWorkService;
import com.wellnessz.casekernel.support.ExternalReferralGateway;
import com.wellnessz.casekernel.support.ReferralStatusListener;
import com.wellnessz.common.error.ApiException;
import com.wellnessz.common.model.Roles;
import com.wellnessz.documents.DocumentService;
import com.wellnessz.e08.model.Claim;
import com.wellnessz.e08.model.ClaimEnums.LegalRequestStatus;
import com.wellnessz.e08.model.ClaimEnums.LegalRequestType;
import com.wellnessz.e08.model.ClaimHistory;
import com.wellnessz.e08.model.E08ClaimHistoryRepository;
import com.wellnessz.e08.model.E08ClaimRepository;
import com.wellnessz.e08.model.E08LegalReportRepository;
import com.wellnessz.e08.model.E08LegalRequestRepository;
import com.wellnessz.e08.model.LegalReport;
import com.wellnessz.e08.model.LegalRequest;
import com.wellnessz.e08.support.ClaimAccess;
import com.wellnessz.e08.support.ClaimsSupport;
import com.wellnessz.masterdata.PartnerOrganisation;
import com.wellnessz.security.abac.AccessPolicy;
import com.wellnessz.security.audit.AuditService;
import com.wellnessz.security.disclosure.DisclosureLogService;
import com.wellnessz.security.identity.CurrentUser;

/**
 * Medico-legal and litigation support (E08-F06): litigation hold (every document of the case on legal hold, retention
 * suspended), privilege marking (privileged material is excluded from disclosure sets), legal requests – medico-legal
 * reports written and signed by physicians (new version each time), expert opinions, and IMEs raised through the E04
 * {@link ExternalReferralGateway} when present (graceful fallback to an OH task when it is not) – and the legal access set
 * for report content (audited and disclosure-logged). Status updates of IME referrals arrive through
 * {@link ReferralStatusListener} (sourceType {@code CLAIM_IME}).
 */
@Service
public class LegalService implements ReferralStatusListener {

    public static final String SOURCE_TYPE = "CLAIM_IME";
    private static final Logger log = LoggerFactory.getLogger(LegalService.class);

    public record ReportVersion(int version, UUID documentId, Instant createdAt, String author) {
    }

    public record LegalRequestView(UUID id, String ref, UUID claimId, String claimRef, String caseRef, String type, String status, String questions,
                                   LocalDate dueDate, String providerCode, String providerName, UUID referralId, String referralRef,
                                   String referralStatus, List<ReportVersion> reports, String createdBy, Instant createdAt, Instant closedAt) {
    }

    public record HoldView(UUID claimId, String claimRef, boolean litigationHold, String reason, int documentsAffected, boolean privileged,
                           String privilegeBasis) {
    }

    private final E08LegalRequestRepository requests;
    private final E08LegalReportRepository reports;
    private final E08ClaimRepository claims;
    private final E08ClaimHistoryRepository history;
    private final OhCaseRepository cases;
    private final CaseAccess caseAccess;
    private final CaseTimeline timeline;
    private final CaseWorkService work;
    private final DocumentService documents;
    private final ObjectProvider<ExternalReferralGateway> referrals;
    private final ClaimAccess access;
    private final ClaimsSupport support;
    private final DisclosureLogService disclosures;
    private final AuditService audit;

    public LegalService(E08LegalRequestRepository requests, E08LegalReportRepository reports, E08ClaimRepository claims,
                        E08ClaimHistoryRepository history, OhCaseRepository cases, CaseAccess caseAccess, CaseTimeline timeline, CaseWorkService work,
                        DocumentService documents, ObjectProvider<ExternalReferralGateway> referrals, ClaimAccess access, ClaimsSupport support,
                        DisclosureLogService disclosures, AuditService audit) {
        this.requests = requests;
        this.reports = reports;
        this.claims = claims;
        this.history = history;
        this.cases = cases;
        this.caseAccess = caseAccess;
        this.timeline = timeline;
        this.work = work;
        this.documents = documents;
        this.referrals = referrals;
        this.access = access;
        this.support = support;
        this.disclosures = disclosures;
        this.audit = audit;
    }

    // ------------------------------------------------------------------ hold & privilege

    @Transactional
    public HoldView legalHold(CurrentUser user, UUID claimId, boolean hold, String reason) {
        Claim c = access.require(user, claimId, ClaimAccess.roles(user, Roles.LEGAL_COUNSEL), "CLAIM_LEGAL_HOLD");
        String why = ClaimsSupport.screened("reason", reason, 1000);
        if (hold && why == null) {
            throw ApiException.badRequest("reason_required", "A reason is required for a litigation hold");
        }
        String docReason = why == null ? "Litigation hold released" : why.substring(0, Math.min(why.length(), 500));
        int n = documents.legalHoldForCase(user.username(), c.getCaseId(), hold, docReason);
        Instant now = support.now();
        c.setLitigationHold(hold);
        c.setHoldReason(hold ? why : null);
        c.setHoldSetBy(user.username());
        c.setHoldSetAt(now);
        c.setUpdatedAt(now);
        record(c, hold ? "LEGAL_HOLD_SET" : "LEGAL_HOLD_RELEASED", why, user.username());
        timeline.record(c.getCaseId(), hold ? "LEGAL_HOLD_SET" : "LEGAL_HOLD_RELEASED",
                "Litigation hold " + (hold ? "set" : "released") + " for claim " + c.getClaimRef() + " (" + n + " documents)");
        audit.record(hold ? "CLAIM_LEGAL_HOLD_SET" : "CLAIM_LEGAL_HOLD_RELEASED", "CLAIM", claimId.toString(), AuditService.Outcome.SUCCESS,
                "documents=" + n);
        return new HoldView(c.getId(), c.getClaimRef(), hold, c.getHoldReason(), n, c.isPrivileged(), c.getPrivilegeBasis());
    }

    @Transactional
    public HoldView privilege(CurrentUser user, UUID claimId, boolean privileged, String basis) {
        Claim c = access.require(user, claimId, ClaimAccess.roles(user, Roles.LEGAL_COUNSEL), "CLAIM_PRIVILEGE");
        String b = ClaimsSupport.screened("basis", basis, 1000);
        if (privileged && b == null) {
            throw ApiException.badRequest("basis_required", "The legal basis of the privilege is required");
        }
        Instant now = support.now();
        c.setPrivileged(privileged);
        c.setPrivilegeBasis(privileged ? b : null);
        c.setPrivilegeSetBy(user.username());
        c.setPrivilegeSetAt(now);
        c.setUpdatedAt(now);
        record(c, privileged ? "PRIVILEGE_SET" : "PRIVILEGE_RELEASED", b, user.username());
        audit.record("CLAIM_PRIVILEGE", "CLAIM", claimId.toString(), AuditService.Outcome.SUCCESS, "privileged=" + privileged);
        return new HoldView(c.getId(), c.getClaimRef(), c.isLitigationHold(), c.getHoldReason(), 0, privileged, c.getPrivilegeBasis());
    }

    // ------------------------------------------------------------------ legal requests

    @Transactional
    public LegalRequestView create(CurrentUser user, UUID claimId, String type, String questions, LocalDate dueDate, String providerCode) {
        Claim c = access.require(user, claimId, ClaimAccess.roles(user, Roles.LEGAL_COUNSEL, Roles.CLAIMS_COORDINATOR), "CLAIM_LEGAL_REQUEST");
        LegalRequestType t;
        try {
            t = LegalRequestType.valueOf(type == null ? "" : type);
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("invalid_type", "type must be MEDICO_LEGAL_REPORT, EXPERT_OPINION or IME");
        }
        if (questions == null || questions.isBlank() || questions.length() > 4000) {
            throw ApiException.badRequest("invalid_questions", "questions are required (max 4000 characters)");
        }
        if (dueDate == null || dueDate.isBefore(support.today())) {
            throw ApiException.badRequest("invalid_due_date", "dueDate must be today or later");
        }
        String provider = null;
        if (providerCode != null && !providerCode.isBlank()) {
            if (t == LegalRequestType.MEDICO_LEGAL_REPORT) {
                throw ApiException.badRequest("provider_not_applicable", "Medico-legal reports are written by OH physicians");
            }
            provider = support.partner(providerCode).filter(PartnerOrganisation::isActive)
                    .filter(p -> !List.of("INSURER", "TPA", "AUTHORITY").contains(p.getOrgType()))
                    .map(PartnerOrganisation::getCode)
                    .orElseThrow(() -> ClaimsSupport.unprocessable("unknown_provider", "Unknown or inactive provider"));
        }
        Instant now = support.now();
        LegalRequest r = LegalRequest.create();
        r.setRequestRef(support.nextRef("LEG"));
        r.setClaimId(c.getId());
        r.setCaseId(c.getCaseId());
        r.setRequestType(t.name());
        r.setStatus(LegalRequestStatus.OPEN.name());
        r.setQuestions(questions.strip());
        r.setDueDate(dueDate);
        r.setProviderCode(provider);
        r.setCreatedBy(user.username());
        r.setCreatedAt(now);
        r.setUpdatedAt(now);
        requests.save(r);
        Map<String, String> params = Map.of("reference", r.getRequestRef(), "caseRef", c.getCaseRef(), "dueDate", dueDate.toString());
        if (t == LegalRequestType.IME) {
            raiseIme(user, c, r, params);
        } else {
            notifyPhysicians(params);
        }
        record(c, "LEGAL_REQUEST", t.name() + " " + r.getRequestRef(), user.username());
        audit.record("CLAIM_LEGAL_REQUEST", "LEGAL_REQUEST", r.getId().toString(), AuditService.Outcome.SUCCESS, t.name());
        return view(r, c);
    }

    private void raiseIme(CurrentUser user, Claim c, LegalRequest r, Map<String, String> params) {
        ExternalReferralGateway gateway = referrals.getIfAvailable();
        Optional<ExternalReferralGateway.ReferralRef> ref = Optional.empty();
        if (gateway != null) {
            ref = gateway.requestReferral(user.username(), new ExternalReferralGateway.ReferralRequest(c.getCaseId(), c.getEmployeeId(), "IME",
                    r.getProviderCode(), "ROUTINE", "Independent medical examination requested for claim " + c.getClaimRef() + " (" + r.getRequestRef() + ")",
                    SOURCE_TYPE, r.getId().toString()));
        }
        if (ref.isPresent()) {
            r.setReferralId(ref.get().referralId());
            r.setReferralRef(ref.get().referralRef());
            r.setReferralStatus(ref.get().status());
            timeline.record(c.getCaseId(), "CLAIM_IME_REQUESTED", "IME referral " + ref.get().referralRef() + " drafted for legal request " + r.getRequestRef());
        } else {
            // graceful degradation: no referral service – OH raises the IME referral manually
            r.setReferralStatus("NOT_AVAILABLE");
            OhCase oc = cases.findById(c.getCaseId()).orElse(null);
            work.systemTask(c.getCaseId(), CaseTask.Type.REMINDER, "Arrange independent medical examination for legal request " + r.getRequestRef(),
                    r.getDueDate().atStartOfDay(ZoneOffset.UTC).toInstant(), oc == null ? null : oc.getOwnerUsername(), user.username());
            log.info("E08 IME request without referral service – OH task created");
        }
        notifyPhysicians(params);
    }

    private void notifyPhysicians(Map<String, String> params) {
        support.notifyRole(Roles.OH_PHYSICIAN, "legal.request", params, "#/legal");
        support.notifyRole(Roles.CMO, "legal.request", params, "#/legal");
    }

    @Transactional(readOnly = true)
    public List<LegalRequestView> list(CurrentUser user, String status) {
        List<LegalRequestView> out = requests.findAllByOrderByCreatedAtDesc().stream()
                .filter(r -> status == null || status.equals(r.getStatus()))
                .map(r -> view(r, claims.findById(r.getClaimId()).orElseThrow()))
                .toList();
        audit.record("LEGAL_REQUESTS_READ", "LEGAL_REQUEST", null, AuditService.Outcome.SUCCESS, "rows=" + out.size());
        return out;
    }

    @Transactional(readOnly = true)
    public LegalRequestView get(CurrentUser user, UUID id) {
        LegalRequest r = requests.findById(id).orElseThrow(() -> ApiException.notFound("Legal request"));
        audit.record("LEGAL_REQUEST_READ", "LEGAL_REQUEST", id.toString(), AuditService.Outcome.SUCCESS, null);
        return view(r, claims.findById(r.getClaimId()).orElseThrow());
    }

    /** Physician writes the opinion: MEDICO_LEGAL_REPORT generated and signed – a new document version each time. */
    @Transactional
    public LegalRequestView report(CurrentUser user, UUID id, String opinion) {
        LegalRequest r = requests.findById(id).orElseThrow(() -> ApiException.notFound("Legal request"));
        if (LegalRequestStatus.CLOSED.name().equals(r.getStatus())) {
            throw ApiException.conflict("request_closed", "The legal request is closed");
        }
        if (opinion == null || opinion.isBlank() || opinion.length() > 4000) {
            throw ApiException.badRequest("invalid_opinion", "opinion is required (max 4000 characters)");
        }
        OhCase oc = caseAccess.clinical(user, r.getCaseId(), AccessPolicy.Action.UPDATE, "LEGAL_REPORT_WRITE");
        Claim c = claims.findById(r.getClaimId()).orElseThrow();
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("employeeName", oc.getEmployeeDisplayName() != null ? oc.getEmployeeDisplayName() : support.name(oc.getEmployeeId()));
        fields.put("caseRef", oc.getCaseRef());
        fields.put("requestRef", r.getRequestRef());
        fields.put("questions", r.getQuestions());
        fields.put("opinion", opinion.strip());
        String privilege = c.isPrivileged() ? "Legally privileged – " + c.getPrivilegeBasis() : "Not marked privileged";
        fields.put("privilege", privilege.length() > 400 ? privilege.substring(0, 400) : privilege);
        fields.put("author", user.displayName() != null ? user.displayName() : user.username());
        List<LegalReport> existing = reports.findByRequestIdOrderByVersionAsc(id);
        DocumentService.DocumentView draft = existing.isEmpty()
                ? documents.generate(user, "MEDICO_LEGAL_REPORT", oc.getEmployeeId(), oc.getId(), fields)
                : documents.newVersion(user, existing.get(existing.size() - 1).getDocumentId(), fields);
        DocumentService.DocumentView signed = documents.sign(user, draft.id());
        LegalReport rep = LegalReport.create();
        rep.setRequestId(id);
        rep.setVersion(existing.size() + 1);
        rep.setDocumentId(signed.id());
        rep.setAuthor(user.username());
        rep.setCreatedAt(support.now());
        reports.save(rep);
        r.setStatus(LegalRequestStatus.REPORT_READY.name());
        r.setUpdatedAt(support.now());
        audit.record("LEGAL_REPORT_WRITE", "LEGAL_REQUEST", id.toString(), AuditService.Outcome.SUCCESS, "version=" + rep.getVersion());
        return view(r, c);
    }

    /** Report content for the legal access set: legal counsel and physicians; audited, disclosure-logged for legal counsel. */
    @Transactional
    public DocumentService.Content content(CurrentUser user, UUID id, int version) {
        LegalRequest r = requests.findById(id).orElseThrow(() -> ApiException.notFound("Legal request"));
        if (!user.hasRole(Roles.LEGAL_COUNSEL)) {
            caseAccess.clinical(user, r.getCaseId(), AccessPolicy.Action.READ, "LEGAL_REPORT_READ");
        }
        LegalReport rep = reports.findByRequestIdAndVersion(id, version).orElseThrow(() -> ApiException.notFound("Report version"));
        DocumentService.Content content = documents.systemContent(user.username(), rep.getDocumentId());
        if (user.hasRole(Roles.LEGAL_COUNSEL)) {
            Claim c = claims.findById(r.getClaimId()).orElseThrow();
            String recipient = "Legal counsel " + user.username();
            String title = "Legal report " + r.getRequestRef() + " v" + version;
            if (!support.alreadyDisclosed("LEGAL_REPORT", rep.getDocumentId().toString(), recipient, title)) {
                disclosures.record(c.getEmployeeId(), "LEGAL", recipient, "LEGAL_REPORT", rep.getDocumentId().toString(), title, "LEGAL_PROCEEDINGS",
                        null, user.username());
            }
        }
        audit.record("LEGAL_REPORT_READ", "LEGAL_REQUEST", id.toString(), AuditService.Outcome.SUCCESS, "version=" + version);
        return content;
    }

    @Transactional
    public LegalRequestView close(CurrentUser user, UUID id) {
        LegalRequest r = requests.findById(id).orElseThrow(() -> ApiException.notFound("Legal request"));
        if (LegalRequestStatus.CLOSED.name().equals(r.getStatus())) {
            throw ApiException.conflict("request_closed", "The legal request is already closed");
        }
        r.setStatus(LegalRequestStatus.CLOSED.name());
        r.setClosedAt(support.now());
        r.setClosedBy(user.username());
        r.setUpdatedAt(support.now());
        audit.record("LEGAL_REQUEST_CLOSE", "LEGAL_REQUEST", id.toString(), AuditService.Outcome.SUCCESS, null);
        return view(r, claims.findById(r.getClaimId()).orElseThrow());
    }

    // ------------------------------------------------------------------ ReferralStatusListener (E04)

    @Override
    public boolean supports(String sourceType) {
        return SOURCE_TYPE.equals(sourceType);
    }

    @Override
    @Transactional
    public void referralStatusChanged(UUID referralId, String sourceType, String sourceRef, Status status, UUID reportDocumentId) {
        if (!supports(sourceType) || sourceRef == null || status == null) {
            return;
        }
        UUID requestId;
        try {
            requestId = UUID.fromString(sourceRef);
        } catch (IllegalArgumentException e) {
            return;
        }
        LegalRequest r = requests.findById(requestId).orElse(null);
        if (r == null) {
            return;
        }
        if (r.getReferralId() == null) {
            r.setReferralId(referralId);
        }
        r.setReferralStatus(status.name());
        boolean closed = LegalRequestStatus.CLOSED.name().equals(r.getStatus());
        switch (status) {
            case SENT, ACCEPTED, SCHEDULED, SEEN -> {
                if (!closed && !LegalRequestStatus.REPORT_READY.name().equals(r.getStatus())) {
                    r.setStatus(LegalRequestStatus.IN_PROGRESS.name());
                }
            }
            case REPORT_RECEIVED -> {
                if (reportDocumentId != null && reports.findByRequestIdOrderByVersionAsc(r.getId()).stream()
                        .noneMatch(x -> x.getDocumentId().equals(reportDocumentId))) {
                    LegalReport rep = LegalReport.create();
                    rep.setRequestId(r.getId());
                    rep.setVersion(reports.findByRequestIdOrderByVersionAsc(r.getId()).size() + 1);
                    rep.setDocumentId(reportDocumentId);
                    rep.setAuthor(r.getProviderCode() != null ? "provider:" + r.getProviderCode() : "provider");
                    rep.setCreatedAt(support.now());
                    reports.save(rep);
                }
                if (!closed) {
                    r.setStatus(LegalRequestStatus.REPORT_READY.name());
                }
            }
            case DECLINED, CANCELLED -> {
                if (!closed && !LegalRequestStatus.REPORT_READY.name().equals(r.getStatus())) {
                    r.setStatus(LegalRequestStatus.OPEN.name());
                }
            }
            default -> {
                // DRAFT / CLOSED: referral bookkeeping only
            }
        }
        r.setUpdatedAt(support.now());
        audit.recordAs("system", "MODULE", "LEGAL_REQUEST_REFERRAL_STATUS", "LEGAL_REQUEST", r.getId().toString(), AuditService.Outcome.SUCCESS,
                status.name());
    }

    // ------------------------------------------------------------------ helpers

    private void record(Claim c, String type, String reason, String actor) {
        ClaimHistory h = ClaimHistory.create();
        h.setClaimId(c.getId());
        h.setEventType(type);
        h.setToStatus(c.getStatus());
        h.setReason(reason == null ? null : reason.substring(0, Math.min(reason.length(), 1000)));
        h.setActor(actor);
        h.setOccurredAt(support.now());
        history.save(h);
    }

    private LegalRequestView view(LegalRequest r, Claim c) {
        List<ReportVersion> versions = reports.findByRequestIdOrderByVersionAsc(r.getId()).stream()
                .map(x -> new ReportVersion(x.getVersion(), x.getDocumentId(), x.getCreatedAt(), x.getAuthor())).toList();
        return new LegalRequestView(r.getId(), r.getRequestRef(), r.getClaimId(), c.getClaimRef(), c.getCaseRef(), r.getRequestType(), r.getStatus(),
                r.getQuestions(), r.getDueDate(), r.getProviderCode(), r.getProviderCode() == null ? null : support.partnerName(r.getProviderCode()),
                r.getReferralId(), r.getReferralRef(), r.getReferralStatus(), versions, r.getCreatedBy(), r.getCreatedAt(), r.getClosedAt());
    }
}
