package com.wellnessz.e04.referral;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.wellnessz.casekernel.model.OhCase;
import com.wellnessz.casekernel.support.ExternalReferralGateway;
import com.wellnessz.common.error.ApiException;
import com.wellnessz.common.model.DataClassification;
import com.wellnessz.documents.DocumentService;
import com.wellnessz.documents.model.Document;
import com.wellnessz.documents.model.DocumentRepository;
import com.wellnessz.documents.model.DocumentShare;
import com.wellnessz.documents.model.DocumentShareRepository;
import com.wellnessz.e04.model.Referral;
import com.wellnessz.e04.model.ServiceDefinition;
import com.wellnessz.e04.model.TreatmentReferralRepository;
import com.wellnessz.e04.support.Consents;
import com.wellnessz.e04.support.TreatmentAccess;
import com.wellnessz.e04.support.TreatmentSupport;
import com.wellnessz.masterdata.PartnerOrganisation;
import com.wellnessz.security.abac.AccessPolicy;
import com.wellnessz.security.audit.AuditService;
import com.wellnessz.security.consent.ConsentPurpose;
import com.wellnessz.security.disclosure.DisclosureLogService;
import com.wellnessz.security.identity.CurrentUser;

/**
 * E-referral management for the OH team (E04-F02): wizard with the service's structured data set, consent-checked send
 * (signed referral letter, FHIR R4 ServiceRequest, partner shares), worklist with overdue / chase state, reminders,
 * cancellation, report-quality rating, the daily {@code referral-chase} job, the FHIR integration endpoints and the
 * {@link ExternalReferralGateway} used by claims (IME).
 */
@Service
public class EReferralService implements ExternalReferralGateway {

    static final Set<String> OPEN = Set.of("DRAFT", "SENT", "ACCEPTED", "SCHEDULED", "SEEN");

    private final TreatmentReferralRepository referrals;
    private final ReferralCore core;
    private final TreatmentAccess access;
    private final DocumentService documents;
    private final DocumentRepository documentRepository;
    private final DocumentShareRepository shares;
    private final DisclosureLogService disclosures;
    private final Consents consents;
    private final TreatmentSupport support;
    private final AuditService audit;

    public EReferralService(TreatmentReferralRepository referrals, ReferralCore core, TreatmentAccess access, DocumentService documents,
                            DocumentRepository documentRepository, DocumentShareRepository shares, DisclosureLogService disclosures,
                            Consents consents, TreatmentSupport support, AuditService audit) {
        this.referrals = referrals;
        this.core = core;
        this.access = access;
        this.documents = documents;
        this.documentRepository = documentRepository;
        this.shares = shares;
        this.disclosures = disclosures;
        this.consents = consents;
        this.support = support;
        this.audit = audit;
    }

    public record ReferralCommand(@NotBlank @Pattern(regexp = "[A-Z][A-Z0-9_]{1,39}") String serviceCode,
                                  @NotBlank @Pattern(regexp = "ROUTINE|URGENT") String urgency,
                                  @Pattern(regexp = "[A-Z0-9_]{2,40}") String providerCode, @Size(max = 4000) String reason,
                                  @Size(max = 4000) String question, @Size(max = 50) Map<String, Object> dataSet, UUID consentId,
                                  @Pattern(regexp = "PORTAL|FHIR|SECURE_EMAIL") String channel, @Size(max = 10) List<UUID> attachDocumentIds) {
    }

    public record ServiceView(String code, String name, List<String> providerTypes, List<DataSetValidator.Field> requiredFields,
                              int reportDueDays, int urgentAppointmentHours, int routineAppointmentDays) {
    }

    public record Reminded(int reminded) {
    }

    // ------------------------------------------------------------------ catalogue

    @Transactional(readOnly = true)
    public List<ServiceView> services() {
        List<ServiceView> out = new ArrayList<>();
        for (ServiceDefinition s : core.lookups(List.of()).svc.values().stream().filter(ServiceDefinition::isActive)
                .sorted(java.util.Comparator.comparing(ServiceDefinition::getCode)).toList()) {
            out.add(new ServiceView(s.getCode(), s.getName(), TreatmentSupport.csv(s.getProviderTypes()), core.fields(s), s.getReportDueDays(),
                    s.getUrgentAppointmentHours(), s.getRoutineAppointmentDays()));
        }
        return out;
    }

    // ------------------------------------------------------------------ wizard

    @Transactional
    public ReferralCore.ReferralView create(CurrentUser user, UUID caseId, ReferralCommand cmd) {
        OhCase c = access.clinical(user, caseId, AccessPolicy.Action.UPDATE, "REFERRAL_CREATE");
        Instant now = support.now();
        Referral r = new Referral();
        r.setId(UUID.randomUUID());
        r.setRef(support.nextRef("RFL"));
        r.setCaseId(c.getId());
        r.setEmployeeId(c.getEmployeeId());
        r.setStatus("DRAFT");
        r.setCreatedBy(user.username());
        r.setCreatedAt(now);
        r.setUpdatedAt(now);
        apply(r, cmd);
        referrals.save(r);
        core.transition(r, "DRAFT", user.username());
        audit.record("REFERRAL_CREATE", "REFERRAL", r.getId().toString(), AuditService.Outcome.SUCCESS, r.getServiceCode());
        return view(r, true);
    }

    @Transactional
    public ReferralCore.ReferralView update(CurrentUser user, UUID id, ReferralCommand cmd) {
        Referral r = load(user, id, AccessPolicy.Action.UPDATE, "REFERRAL_UPDATE");
        core.requireStatus(r, Set.of("DRAFT"), "edit");
        apply(r, cmd);
        r.setUpdatedAt(support.now());
        audit.record("REFERRAL_UPDATE", "REFERRAL", id.toString(), AuditService.Outcome.SUCCESS, null);
        return view(r, true);
    }

    private void apply(Referral r, ReferralCommand cmd) {
        ServiceDefinition s = core.service(cmd.serviceCode());
        r.setServiceCode(s.getCode());
        r.setUrgency(cmd.urgency());
        r.setChannel(cmd.channel() == null ? "PORTAL" : cmd.channel());
        if (cmd.providerCode() != null) {
            checkProvider(s, cmd.providerCode());
        }
        r.setProviderCode(cmd.providerCode());
        r.setReason(TreatmentSupport.clinical("reason", cmd.reason(), 4000));
        r.setQuestion(TreatmentSupport.clinical("question", cmd.question(), 4000));
        Map<String, Object> ds = DataSetValidator.validate(core.fields(s), cmd.dataSet(), false);
        r.setDataSet(ds.isEmpty() ? null : support.write(ds));
        r.setConsentId(cmd.consentId());
        List<Map<String, Object>> atts = new ArrayList<>();
        for (UUID docId : cmd.attachDocumentIds() == null ? List.<UUID>of() : cmd.attachDocumentIds().stream().distinct().toList()) {
            Document d = documentRepository.findById(docId).filter(x -> x.getEmployeeId().equals(r.getEmployeeId()))
                    .orElseThrow(() -> ApiException.badRequest("invalid_attachment", "Attachments must be documents of this employee"));
            if (d.getStatus() != Document.Status.SIGNED || "PHOTO".equals(d.getType())
                    || d.getClassification() == DataClassification.RESTRICTED_CLINICAL) {
                throw ApiException.badRequest("invalid_attachment", "Only signed, shareable documents can be attached");
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("documentId", d.getId().toString());
            m.put("title", d.getTitle() + " (" + d.getDocumentNumber() + ")");
            atts.add(m);
        }
        r.setAttachments(atts.isEmpty() ? null : support.write(atts));
    }

    private PartnerOrganisation checkProvider(ServiceDefinition s, String providerCode) {
        PartnerOrganisation p = support.requireActivePartner(providerCode, "providerCode");
        if (!TreatmentSupport.csv(s.getProviderTypes()).contains(p.getOrgType())) {
            throw TreatmentSupport.unprocessable("provider_not_suitable", "The provider does not offer this type of service");
        }
        return p;
    }

    // ------------------------------------------------------------------ send

    @Transactional
    public ReferralCore.ReferralView send(CurrentUser user, UUID id) {
        Referral r = load(user, id, AccessPolicy.Action.EXPORT, "REFERRAL_SEND");
        core.requireStatus(r, Set.of("DRAFT"), "send");
        ServiceDefinition s = core.service(r.getServiceCode());
        if (r.getProviderCode() == null) {
            throw new DataSetValidator.DataSetException(org.springframework.http.HttpStatus.UNPROCESSABLE_CONTENT, "referral_incomplete",
                    "Choose a provider before sending", "providerCode");
        }
        if (r.getReason() == null || r.getQuestion() == null) {
            throw new DataSetValidator.DataSetException(org.springframework.http.HttpStatus.UNPROCESSABLE_CONTENT, "referral_incomplete",
                    "Reason and question for the provider are required", r.getReason() == null ? "reason" : "question");
        }
        PartnerOrganisation p = checkProvider(s, r.getProviderCode());
        Map<String, Object> ds = DataSetValidator.validate(core.fields(s), core.dataSet(r), true);
        UUID consentId = consents.require(r.getConsentId(), r.getEmployeeId(), ConsentPurpose.TREATMENT_SHARING_PARTNER, "Sending a referral");
        r.setConsentId(consentId);
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("employeeName", support.displayName(r.getEmployeeId()));
        fields.put("recipient", p.getName() + " – " + s.getName());
        fields.put("reason", r.getReason());
        StringBuilder history = new StringBuilder();
        ds.forEach((k, v) -> history.append(k).append(": ").append(v instanceof List<?> l ? String.join(", ", l.stream().map(String::valueOf).toList())
                : v).append("; "));
        fields.put("history", history.length() == 0 ? null : history.length() > 4000 ? history.substring(0, 4000) : history.toString());
        fields.put("question", r.getQuestion());
        fields.put("referrer", support.staffName(user.username()));
        DocumentService.DocumentView letter = documents.generate(user, "REFERRAL_LETTER", r.getEmployeeId(), r.getCaseId(), fields);
        letter = documents.sign(user, letter.id());
        documents.shareAsSystem(user.username(), letter.id(), DocumentShare.Target.PARTNER, p.getCode(), consentId);
        for (ReferralCore.Attachment a : core.attachments(r)) {
            documents.shareAsSystem(user.username(), a.documentId(), DocumentShare.Target.PARTNER, p.getCode(), consentId);
        }
        Instant now = support.now();
        r.setLetterDocumentId(letter.id());
        r.setSentAt(now);
        r.setSentBy(user.username());
        r.setReportDueAt(now.plus(Duration.ofDays(s.getReportDueDays())));
        core.transition(r, "SENT", user.username());
        r.setFhir(support.write(core.fhir(r)));
        support.notifyPartner(p.getCode(), "referral.sent", Map.of("reference", r.getRef()), "/#/referrals/" + r.getId());
        audit.record("REFERRAL_SEND", "REFERRAL", id.toString(), AuditService.Outcome.SUCCESS, "provider=" + p.getCode() + " channel=" + r.getChannel());
        return view(r, true);
    }

    // ------------------------------------------------------------------ reads

    @Transactional(readOnly = true)
    public List<ReferralCore.ReferralView> list(CurrentUser user, String status, Boolean overdue, Boolean mine, String serviceCode,
                                                String providerCode) {
        PageRequest page = PageRequest.of(0, 500);
        List<Referral> list = status == null ? referrals.findAllByOrderByCreatedAtDesc(page) : referrals.findByStatusOrderByCreatedAtDesc(status, page);
        ReferralCore.Lookups l = core.lookups(list);
        Map<UUID, Boolean> visible = new HashMap<>();
        Instant now = support.now();
        List<ReferralCore.ReferralView> out = new ArrayList<>();
        for (Referral r : list) {
            if (serviceCode != null && !serviceCode.equals(r.getServiceCode()) || providerCode != null && !providerCode.equals(r.getProviderCode())
                    || Boolean.TRUE.equals(mine) && !user.username().equals(r.getCreatedBy())
                    || overdue != null && overdue != ChaseRules.overdue(r.getStatus(), r.getReportDueAt(), r.getReportReceivedAt(), now)) {
                continue;
            }
            boolean ok = visible.computeIfAbsent(r.getCaseId(), k -> l.caseOf(r).map(c -> access.canSeeClinical(user, c)).orElse(false));
            if (ok) {
                out.add(core.view(r, l, false));
            }
        }
        audit.record("REFERRAL_LIST", "REFERRAL", null, AuditService.Outcome.SUCCESS, "rows=" + out.size());
        return out;
    }

    @Transactional(readOnly = true)
    public ReferralCore.ReferralView get(CurrentUser user, UUID id) {
        Referral r = load(user, id, AccessPolicy.Action.READ, "REFERRAL_READ");
        audit.record("REFERRAL_READ", "REFERRAL", id.toString(), AuditService.Outcome.SUCCESS, null);
        return view(r, true);
    }

    @Transactional(readOnly = true)
    public List<ReferralCore.ReferralView> byCase(CurrentUser user, UUID caseId) {
        access.clinical(user, caseId, AccessPolicy.Action.READ, "REFERRAL_CASE_LIST");
        List<Referral> list = referrals.findByCaseIdOrderByCreatedAtDesc(caseId);
        ReferralCore.Lookups l = core.lookups(list);
        audit.record("REFERRAL_CASE_LIST", "CASE", caseId.toString(), AuditService.Outcome.SUCCESS, "rows=" + list.size());
        return list.stream().map(r -> core.view(r, l, false)).toList();
    }

    ReferralCore.ReferralView view(Referral r, boolean detail) {
        return core.view(r, core.lookups(List.of(r)), detail);
    }

    Referral load(CurrentUser user, UUID id, AccessPolicy.Action action, String auditAction) {
        Referral r = referrals.findById(id).orElseThrow(() -> ApiException.notFound("Referral"));
        access.clinical(user, r.getCaseId(), action, auditAction);
        return r;
    }

    // ------------------------------------------------------------------ actions

    @Transactional
    public Reminded remind(CurrentUser user, List<UUID> ids) {
        int n = 0;
        for (Referral r : referrals.findAllById(ids.stream().distinct().toList())) {
            OhCase c = access.find(r.getCaseId()).orElse(null);
            if (c == null || !access.canSeeClinical(user, c) || !ChaseRules.AWAITING_REPORT.contains(r.getStatus()) || r.getProviderCode() == null) {
                continue;
            }
            if ("SENT".equals(r.getStatus())) {
                support.notifyPartner(r.getProviderCode(), "referral.sent", Map.of("reference", r.getRef()), "/#/referrals/" + r.getId());
            } else {
                support.notifyPartner(r.getProviderCode(), "referral.chase", Map.of("reference", r.getRef(), "dueDate",
                        r.getReportDueAt() == null ? "" : r.getReportDueAt().atZone(java.time.ZoneOffset.UTC).toLocalDate().toString()),
                        "/#/referrals/" + r.getId());
            }
            n++;
        }
        audit.record("REFERRAL_REMIND", "REFERRAL", null, AuditService.Outcome.SUCCESS, "reminded=" + n);
        return new Reminded(n);
    }

    @Transactional
    public ReferralCore.ReferralView cancel(CurrentUser user, UUID id, String reason) {
        Referral r = load(user, id, AccessPolicy.Action.UPDATE, "REFERRAL_CANCEL");
        core.requireStatus(r, OPEN, "cancel");
        r.setCancelReason(TreatmentSupport.clinical("reason", reason, 2000));
        r.setCancelledAt(support.now());
        revokePartnerShares(r, user.username(), "Referral cancelled");
        core.transition(r, "CANCELLED", user.username());
        audit.record("REFERRAL_CANCEL", "REFERRAL", id.toString(), AuditService.Outcome.SUCCESS, null);
        return view(r, true);
    }

    @Transactional
    public ReferralCore.ReferralView close(CurrentUser user, UUID id) {
        Referral r = load(user, id, AccessPolicy.Action.UPDATE, "REFERRAL_CLOSE");
        core.requireStatus(r, Set.of("REPORT_RECEIVED", "SEEN", "DECLINED"), "close");
        r.setClosedAt(support.now());
        core.transition(r, "CLOSED", user.username());
        audit.record("REFERRAL_CLOSE", "REFERRAL", id.toString(), AuditService.Outcome.SUCCESS, null);
        return view(r, true);
    }

    @Transactional
    public ReferralCore.ReferralView rate(CurrentUser user, UUID id, int rating, String comment) {
        Referral r = load(user, id, AccessPolicy.Action.UPDATE, "REFERRAL_RATE");
        core.requireStatus(r, Set.of("REPORT_RECEIVED", "CLOSED"), "rate");
        r.setRating(rating);
        r.setRatingComment(TreatmentSupport.clinical("comment", comment, 2000));
        r.setRatedBy(user.username());
        r.setUpdatedAt(support.now());
        audit.record("REFERRAL_RATE", "REFERRAL", id.toString(), AuditService.Outcome.SUCCESS, "rating=" + rating);
        return view(r, true);
    }

    void revokePartnerShares(Referral r, String actor, String reason) {
        List<UUID> docs = new ArrayList<>();
        if (r.getLetterDocumentId() != null) {
            docs.add(r.getLetterDocumentId());
        }
        core.attachments(r).forEach(a -> docs.add(a.documentId()));
        for (UUID d : docs) {
            for (DocumentShare s : shares.findByDocumentIdOrderBySharedAtAsc(d)) {
                if (s.isActive() && s.getTarget() == DocumentShare.Target.PARTNER && s.getRecipient().equals(r.getProviderCode())) {
                    s.revoke(actor, reason, support.now());
                    disclosures.revokeResource("DOCUMENT_SHARE", s.getId().toString(), reason);
                }
            }
        }
    }

    // ------------------------------------------------------------------ chase job

    public record ChaseRun(int chased, int escalated) {
    }

    /** Daily {@code referral-chase}: overdue reports are chased with the provider, then escalated to the referring clinician. */
    @Transactional
    public ChaseRun chase() {
        Instant now = support.now();
        int max = support.configInt("treatment.referral.max-chases", 2);
        int interval = support.configInt("treatment.referral.chase-interval-days", 2);
        int chased = 0;
        int escalated = 0;
        for (Referral r : referrals.findByStatusInAndReportDueAtBefore(ChaseRules.AWAITING_REPORT, now)) {
            ChaseRules.Action a = ChaseRules.decide(r.getStatus(), r.getReportDueAt(), r.getReportReceivedAt(), r.getChaseCount(),
                    r.getLastChasedAt(), r.isEscalated(), now, max, interval);
            if (a == ChaseRules.Action.CHASE) {
                support.notifyPartner(r.getProviderCode(), "referral.chase", Map.of("reference", r.getRef(), "dueDate",
                        r.getReportDueAt().atZone(java.time.ZoneOffset.UTC).toLocalDate().toString()), "/#/referrals/" + r.getId());
                r.setChaseCount(r.getChaseCount() + 1);
                r.setLastChasedAt(now);
                chased++;
            } else if (a == ChaseRules.Action.ESCALATE) {
                r.setEscalated(true);
                r.setLastChasedAt(now);
                String clinician = r.getSentBy() == null ? r.getCreatedBy() : r.getSentBy();
                String caseRef = access.caseRef(r.getCaseId());
                support.notifyStaff(clinician, "referral.overdue-team", Map.of("reference", r.getRef(), "caseRef", caseRef == null ? "" : caseRef,
                        "level", "report overdue after " + r.getChaseCount() + " reminders"), "/#/referrals/" + r.getId());
                escalated++;
            }
            if (a != ChaseRules.Action.NONE) {
                r.setUpdatedAt(now);
                audit.recordAs("system", "MODULE", "REFERRAL_" + a.name(), "REFERRAL", r.getId().toString(), AuditService.Outcome.SUCCESS,
                        "chaseCount=" + r.getChaseCount());
            }
        }
        return new ChaseRun(chased, escalated);
    }

    // ------------------------------------------------------------------ integration (FHIR)

    @Transactional(readOnly = true)
    public Map<String, Object> fhir(String actor, UUID id) {
        Referral r = referrals.findById(id).filter(x -> !"DRAFT".equals(x.getStatus())).orElseThrow(() -> ApiException.notFound("Referral"));
        audit.recordAs(actor, "INTEGRATION", "REFERRAL_FHIR_READ", "REFERRAL", id.toString(), AuditService.Outcome.SUCCESS, r.getProviderCode());
        disclosures.record(r.getEmployeeId(), "PARTNER", "Partner " + r.getProviderCode() + " (FHIR)", "REFERRAL", r.getId().toString(),
                "Referral " + r.getRef(), "TREATMENT", r.getConsentId(), actor);
        return core.fhir(r);
    }

    public record DocumentReferenceResult(UUID referralId, String ref, String status, UUID documentId) {
    }

    /** Inbound FHIR R4 DocumentReference with the provider's report → REPORT_RECEIVED. */
    @Transactional
    public DocumentReferenceResult documentReference(String actor, Map<String, Object> body) {
        String related = FhirMapper.relatedServiceRequest(body)
                .orElseThrow(() -> ApiException.badRequest("invalid_document_reference", "DocumentReference.context.related must reference the ServiceRequest"));
        Referral r;
        try {
            r = referrals.findById(UUID.fromString(related)).orElse(null);
        } catch (IllegalArgumentException e) {
            r = referrals.findByRef(related).orElse(null);
        }
        if (r == null) {
            throw ApiException.notFound("Referral");
        }
        FhirMapper.Attachment a = FhirMapper.attachment(body)
                .orElseThrow(() -> ApiException.badRequest("invalid_document_reference", "DocumentReference.content[0].attachment.data required"));
        byte[] bytes;
        try {
            bytes = Base64.getMimeDecoder().decode(a.data());
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("invalid_document_reference", "Attachment data is not valid base64");
        }
        UUID docId = receiveReport(r, actor, bytes, a.title(), a.description());
        audit.recordAs(actor, "INTEGRATION", "REFERRAL_REPORT_FHIR", "REFERRAL", r.getId().toString(), AuditService.Outcome.SUCCESS, null);
        return new DocumentReferenceResult(r.getId(), r.getRef(), r.getStatus(), docId);
    }

    /** Stores the provider's report (PROVIDER_REPORT on the case) and moves the referral to REPORT_RECEIVED. */
    public UUID receiveReport(Referral r, String actor, byte[] bytes, String title, String summary) {
        core.requireStatus(r, Set.of("ACCEPTED", "SCHEDULED", "SEEN", "REPORT_RECEIVED"), "upload a report for");
        DocumentService.Stored stored = documents.storeUpload(actor, r.getEmployeeId(), r.getCaseId(), "PROVIDER_REPORT",
                title == null || title.isBlank() ? "Provider report – " + r.getRef() : PlanSafe.title(title), bytes, DataClassification.CLINICAL);
        Instant now = support.now();
        r.setReportDocumentId(stored.document().id());
        r.setReportReceivedAt(now);
        r.setReportSummary(TreatmentSupport.clinical("summary", summary, 4000));
        if (r.getSeenAt() == null) {
            r.setSeenAt(now);
        }
        core.transition(r, "REPORT_RECEIVED", actor);
        return stored.document().id();
    }

    /** Title hygiene for uploaded documents. */
    static final class PlanSafe {
        private PlanSafe() {
        }

        static String title(String t) {
            String v = t.replaceAll("[\\p{Cntrl}<>]", "").strip();
            return v.length() > 150 ? v.substring(0, 150) : v;
        }
    }

    // ------------------------------------------------------------------ ExternalReferralGateway (E08 IME)

    @Override
    @Transactional
    public java.util.Optional<ReferralRef> requestReferral(String actor, ReferralRequest req) {
        if (req == null || req.serviceCode() == null || req.caseId() == null) {
            return java.util.Optional.empty();
        }
        ServiceDefinition s = core.lookups(List.of()).svc.get(req.serviceCode());
        OhCase c = access.find(req.caseId()).orElse(null);
        if (s == null || !s.isActive() || c == null) {
            return java.util.Optional.empty();
        }
        Instant now = support.now();
        Referral r = new Referral();
        r.setId(UUID.randomUUID());
        r.setRef(support.nextRef("RFL"));
        r.setCaseId(c.getId());
        r.setEmployeeId(c.getEmployeeId());
        r.setServiceCode(s.getCode());
        String provider = req.providerCode();
        if (provider != null && support.partner(provider).filter(PartnerOrganisation::isActive).isEmpty()) {
            provider = null;
        }
        r.setProviderCode(provider);
        r.setUrgency("URGENT".equals(req.urgency()) ? "URGENT" : "ROUTINE");
        r.setChannel("PORTAL");
        r.setStatus("DRAFT");
        r.setReason(TreatmentSupport.clinical("reason", req.reason(), 4000));
        r.setSourceType(req.sourceType());
        r.setSourceRef(req.sourceRef());
        r.setCreatedBy(actor == null ? "system" : actor);
        r.setCreatedAt(now);
        r.setUpdatedAt(now);
        referrals.save(r);
        core.transition(r, "DRAFT", r.getCreatedBy());
        audit.recordAs(r.getCreatedBy(), "MODULE", "REFERRAL_REQUESTED", "REFERRAL", r.getId().toString(), AuditService.Outcome.SUCCESS,
                "source=" + req.sourceType());
        return java.util.Optional.of(new ReferralRef(r.getId(), r.getRef(), r.getStatus()));
    }
}
