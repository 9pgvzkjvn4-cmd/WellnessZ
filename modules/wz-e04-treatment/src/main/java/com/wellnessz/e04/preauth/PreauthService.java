package com.wellnessz.e04.preauth;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.wellnessz.casekernel.model.OhCase;
import com.wellnessz.casekernel.model.OhCaseRepository;
import com.wellnessz.casekernel.support.CaseCostRecorder;
import com.wellnessz.casekernel.support.CaseTimeline;
import com.wellnessz.common.error.ApiException;
import com.wellnessz.common.model.DataClassification;
import com.wellnessz.common.model.Roles;
import com.wellnessz.documents.DocumentService;
import com.wellnessz.documents.model.DocumentShare;
import com.wellnessz.e04.E04Events;
import com.wellnessz.e04.model.Preauth;
import com.wellnessz.e04.model.PreauthRepository;
import com.wellnessz.e04.model.ProviderInvoice;
import com.wellnessz.e04.model.ProviderInvoiceRepository;
import com.wellnessz.e04.model.Referral;
import com.wellnessz.e04.model.ServiceDefinition;
import com.wellnessz.e04.model.ServiceDefinitionRepository;
import com.wellnessz.e04.model.TreatmentReferralRepository;
import com.wellnessz.e04.support.Consents;
import com.wellnessz.e04.support.Money;
import com.wellnessz.e04.support.TreatmentAccess;
import com.wellnessz.e04.support.TreatmentSpis;
import com.wellnessz.e04.support.TreatmentSupport;
import com.wellnessz.integration.outbox.EventBus;
import com.wellnessz.masterdata.OrgUnit;
import com.wellnessz.masterdata.OrgUnitRepository;
import com.wellnessz.masterdata.PartnerOrganisation;
import com.wellnessz.security.abac.AccessPolicy;
import com.wellnessz.security.audit.AuditService;
import com.wellnessz.security.consent.ConsentPurpose;
import com.wellnessz.security.identity.CurrentUser;

/**
 * Pre-authorisation & cost tracking (E04-F04): consent-checked pre-authorisation requests to insurers / TPAs (signed
 * {@code PREAUTH_REQUEST} shared INSURER), decisions by claims coordination, the payer's adjuster or the insurer system,
 * provider invoices matched against the approved amount, costs posted to the case ledger ({@link CaseCostRecorder}) and
 * unit-level cost roll-ups (no names).
 */
@Service
public class PreauthService {

    static final Set<String> APPROVED = Set.of("APPROVED", "PARTIALLY_APPROVED");
    static final Set<String> PARTNER_VISIBLE = Set.of("SUBMITTED", "APPROVED", "PARTIALLY_APPROVED", "DECLINED", "EXPIRED");

    private final PreauthRepository preauths;
    private final ProviderInvoiceRepository invoices;
    private final TreatmentReferralRepository referrals;
    private final ServiceDefinitionRepository services;
    private final OhCaseRepository cases;
    private final OrgUnitRepository orgUnits;
    private final TreatmentAccess access;
    private final DocumentService documents;
    private final Consents consents;
    private final CaseTimeline timeline;
    private final TreatmentSpis spis;
    private final TreatmentSupport support;
    private final EventBus bus;
    private final AuditService audit;

    public PreauthService(PreauthRepository preauths, ProviderInvoiceRepository invoices, TreatmentReferralRepository referrals,
                          ServiceDefinitionRepository services, OhCaseRepository cases, OrgUnitRepository orgUnits, TreatmentAccess access,
                          DocumentService documents, Consents consents, CaseTimeline timeline, TreatmentSpis spis, TreatmentSupport support,
                          EventBus bus, AuditService audit) {
        this.preauths = preauths;
        this.invoices = invoices;
        this.referrals = referrals;
        this.services = services;
        this.cases = cases;
        this.orgUnits = orgUnits;
        this.access = access;
        this.documents = documents;
        this.consents = consents;
        this.timeline = timeline;
        this.spis = spis;
        this.support = support;
        this.bus = bus;
        this.audit = audit;
    }

    // ------------------------------------------------------------------ commands & views

    public record PreauthCommand(@Pattern(regexp = "[A-Z0-9_]{2,40}") String payerCode, @NotBlank @Pattern(regexp = "[A-Z][A-Z0-9_]{1,39}") String serviceCode,
                                 @NotBlank @Pattern(regexp = "[A-Z0-9_]{2,40}") String providerCode, UUID referralId,
                                 @NotNull @Valid Money estimate, @Size(max = 4000) String justification, UUID consentId) {
    }

    public record DecisionCommand(@NotBlank @Pattern(regexp = "APPROVED|PARTIALLY_APPROVED|DECLINED") String status,
                                  @Size(max = 60) @Pattern(regexp = "[A-Za-z0-9_/\\-]{1,60}") String approvalRef, @Valid Money approvedAmount,
                                  LocalDate validUntil, @Size(max = 1000) String note) {
    }

    public record InvoiceCommand(@NotBlank @Pattern(regexp = "[A-Z0-9_]{2,40}") String providerCode,
                                 @NotBlank @Pattern(regexp = "[A-Za-z0-9_/\\-]{1,60}") String invoiceNumber,
                                 @NotBlank @Pattern(regexp = "\\d{1,10}(\\.\\d{1,2})?") String amount, @NotBlank @Pattern(regexp = "[A-Z]{3}") String currency,
                                 @NotNull LocalDate serviceDate, UUID referralId, UUID preauthId, UUID documentId) {
    }

    public record PreauthView(UUID id, String ref, UUID caseId, String caseRef, String employeeId, String displayName, String payerCode,
                              String payerName, String serviceCode, String serviceName, String providerCode, String providerName, UUID referralId,
                              Money estimate, String status, String requestedBy, Instant createdAt, Instant submittedAt, Instant slaDueAt,
                              boolean slaBreached, Instant decidedAt, String approvalRef, Money approvedAmount, LocalDate validUntil,
                              UUID documentId, String justification, String decisionNote) {
    }

    public record InvoiceView(UUID id, UUID caseId, String caseRef, String employeeId, String providerCode, String providerName,
                              String invoiceNumber, Money amount, LocalDate serviceDate, UUID referralId, UUID preauthId, UUID documentId,
                              String status, UUID matchedPreauthId, Money variance, String note, Instant createdAt, String createdBy) {
    }

    public record CostRow(String key, String label, int cases, Money estimated, Money invoiced, Money avgPerCase, boolean suppressed) {
    }

    // ------------------------------------------------------------------ pre-authorisation

    @Transactional
    public PreauthView create(CurrentUser user, UUID caseId, PreauthCommand cmd) {
        OhCase c = access.financial(user, caseId, AccessPolicy.Action.UPDATE, "PREAUTH_CREATE");
        boolean oh = user.hasAnyRole(Roles.OH_TEAM);
        String justification = TreatmentSupport.clinical("justification", cmd.justification(), 4000);
        if (oh && justification == null) {
            throw ApiException.badRequest("justification_required", "A clinical justification is required");
        }
        if (!oh && justification != null) {
            throw ApiException.badRequest("justification_oh_only", "The clinical justification is provided by Occupational Health only");
        }
        String payer = cmd.payerCode() != null ? cmd.payerCode()
                : spis.claimContext(caseId).map(x -> x.payerCode()).orElseThrow(() -> TreatmentSupport.unprocessable("payer_required",
                        "No claim with a payer on this case – choose the insurer / TPA"));
        PartnerOrganisation p = support.requireActivePartner(payer, "payerCode");
        if (!Set.of("INSURER", "TPA").contains(p.getOrgType())) {
            throw TreatmentSupport.unprocessable("invalid_payer", "The payer must be an insurer or TPA");
        }
        service(cmd.serviceCode());
        support.requireActivePartner(cmd.providerCode(), "providerCode");
        if (cmd.referralId() != null) {
            referrals.findById(cmd.referralId()).filter(r -> r.getCaseId().equals(caseId))
                    .orElseThrow(() -> ApiException.badRequest("invalid_referral", "The referral belongs to another case"));
        }
        Instant now = support.now();
        Preauth a = new Preauth();
        a.setId(UUID.randomUUID());
        a.setRef(support.nextRef("PA"));
        a.setCaseId(c.getId());
        a.setEmployeeId(c.getEmployeeId());
        a.setPayerCode(p.getCode());
        a.setServiceCode(cmd.serviceCode());
        a.setProviderCode(cmd.providerCode());
        a.setReferralId(cmd.referralId());
        a.setEstimateAmount(cmd.estimate().value());
        a.setCurrency(cmd.estimate().normalisedCurrency());
        a.setJustification(justification);
        a.setConsentId(cmd.consentId());
        a.setStatus("DRAFT");
        a.setRequestedBy(user.username());
        a.setCreatedAt(now);
        a.setUpdatedAt(now);
        preauths.save(a);
        timeline.record(c.getId(), "PREAUTH_CREATED", "Pre-authorisation " + a.getRef() + " drafted");
        audit.record("PREAUTH_CREATE", "PREAUTH", a.getId().toString(), AuditService.Outcome.SUCCESS, "payer=" + p.getCode());
        return view(a, c, oh);
    }

    @Transactional
    public PreauthView submit(CurrentUser user, UUID id) {
        Preauth a = preauths.findById(id).orElseThrow(() -> ApiException.notFound("Pre-authorisation"));
        OhCase c = access.financial(user, a.getCaseId(), AccessPolicy.Action.EXPORT, "PREAUTH_SUBMIT");
        if (!"DRAFT".equals(a.getStatus())) {
            throw TreatmentSupport.unprocessable("invalid_transition", "Only draft pre-authorisations can be submitted");
        }
        UUID consentId = consents.require(a.getConsentId(), a.getEmployeeId(), ConsentPurpose.INSURER_SHARING, "Sharing with an insurer or TPA");
        a.setConsentId(consentId);
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("employeeName", support.displayName(a.getEmployeeId()));
        fields.put("employeeId", a.getEmployeeId());
        fields.put("payer", support.partnerName(a.getPayerCode()));
        fields.put("requestRef", a.getRef());
        fields.put("service", service(a.getServiceCode()).getName());
        fields.put("provider", support.partnerName(a.getProviderCode()));
        fields.put("estimate", a.getCurrency() + " " + a.getEstimateAmount().toPlainString());
        fields.put("justification", a.getJustification() != null ? a.getJustification()
                : "Requested by claims coordination; the clinical justification is available from Occupational Health on request.");
        fields.put("requestedBy", support.staffName(user.username()));
        DocumentService.DocumentView d = documents.generateSigned(user.username(), "PREAUTH_REQUEST", a.getEmployeeId(), a.getCaseId(), fields);
        documents.shareAsSystem(user.username(), d.id(), DocumentShare.Target.INSURER, a.getPayerCode(), consentId);
        Instant now = support.now();
        a.setDocumentId(d.id());
        a.setSubmittedAt(now);
        a.setSlaDueAt(now.plus(Duration.ofHours(support.configInt("treatment.preauth.sla-hours", 48))));
        a.setStatus("SUBMITTED");
        a.setUpdatedAt(now);
        spis.recordCost(user.username(), new CaseCostRecorder.CostEntry(a.getCaseId(), a.getEmployeeId(), CaseCostRecorder.Category.MEDICAL,
                CaseCostRecorder.Kind.ESTIMATE, a.getEstimateAmount(), a.getCurrency(), support.today(), a.getProviderCode(),
                "Pre-authorisation " + a.getRef(), "PREAUTH", a.getId().toString()));
        statusChanged(a, c);
        support.notifyPartner(a.getPayerCode(), "preauth.status", Map.of("reference", a.getRef(), "caseRef", a.getRef(), "level", "SUBMITTED"),
                "/#/preauthorisations");
        audit.record("PREAUTH_SUBMIT", "PREAUTH", id.toString(), AuditService.Outcome.SUCCESS, "payer=" + a.getPayerCode());
        return view(a, c, user.hasAnyRole(Roles.OH_TEAM));
    }

    @Transactional
    public PreauthView decideAsCoordinator(CurrentUser user, UUID id, DecisionCommand cmd) {
        Preauth a = preauths.findById(id).orElseThrow(() -> ApiException.notFound("Pre-authorisation"));
        OhCase c = access.financial(user, a.getCaseId(), AccessPolicy.Action.UPDATE, "PREAUTH_DECIDE");
        decide(a, user.username(), cmd);
        return view(a, c, false);
    }

    @Transactional
    public PreauthView decideAsAdjuster(CurrentUser user, UUID id, DecisionCommand cmd) {
        String org = TreatmentSupport.requirePartnerOrg(user);
        Preauth a = preauths.findById(id).filter(x -> org.equals(x.getPayerCode()) && PARTNER_VISIBLE.contains(x.getStatus())
                && consents.isActive(x.getConsentId(), x.getEmployeeId(), ConsentPurpose.INSURER_SHARING)).orElse(null);
        if (a == null) {
            audit.record("PREAUTH_DECIDE_PARTNER", "PREAUTH", id.toString(), AuditService.Outcome.DENIED, "Not the payer organisation");
            throw ApiException.notFound("Pre-authorisation");
        }
        decide(a, user.username(), cmd);
        return partnerView(a);
    }

    @Transactional
    public PreauthView decideByRef(String actor, String ref, DecisionCommand cmd) {
        Preauth a = preauths.findByRef(ref).orElseThrow(() -> ApiException.notFound("Pre-authorisation"));
        decide(a, actor, cmd);
        return partnerView(a);
    }

    private void decide(Preauth a, String actor, DecisionCommand cmd) {
        if (!"SUBMITTED".equals(a.getStatus())) {
            throw TreatmentSupport.unprocessable("invalid_transition", "Only submitted pre-authorisations can be decided");
        }
        Instant now = support.now();
        a.setStatus(cmd.status());
        a.setDecidedAt(now);
        a.setDecidedBy(actor);
        a.setApprovalRef(cmd.approvalRef());
        a.setDecisionNote(TreatmentSupport.functional("note", cmd.note(), 1000));
        if (APPROVED.contains(cmd.status())) {
            BigDecimal amount = cmd.approvedAmount() != null ? cmd.approvedAmount().value() : null;
            if (amount == null && "PARTIALLY_APPROVED".equals(cmd.status())) {
                throw ApiException.badRequest("approved_amount_required", "A partial approval needs the approved amount");
            }
            if (cmd.approvedAmount() != null && !cmd.approvedAmount().normalisedCurrency().equals(a.getCurrency())) {
                throw ApiException.badRequest("currency_mismatch", "The approved amount must be in the currency of the estimate");
            }
            a.setApprovedAmount(amount == null ? a.getEstimateAmount() : amount);
            a.setValidUntil(cmd.validUntil() == null ? support.today().plusDays(90) : cmd.validUntil());
            spis.recordCost(actor, new CaseCostRecorder.CostEntry(a.getCaseId(), a.getEmployeeId(), CaseCostRecorder.Category.MEDICAL,
                    CaseCostRecorder.Kind.ESTIMATE, a.getApprovedAmount(), a.getCurrency(), support.today(), a.getProviderCode(),
                    "Pre-authorisation " + a.getRef() + " approved", "PREAUTH", a.getId().toString()));
        }
        a.setUpdatedAt(now);
        OhCase c = cases.findById(a.getCaseId()).orElse(null);
        statusChanged(a, c);
        support.notifyStaff(a.getRequestedBy(), "preauth.status", Map.of("reference", a.getRef(), "caseRef", c == null ? "" : c.getCaseRef(),
                "level", a.getStatus()), "/#/cases/" + a.getCaseId() + "/costs");
        audit.recordAs(actor, "PREAUTH", "PREAUTH_DECISION", "PREAUTH", a.getId().toString(), AuditService.Outcome.SUCCESS, a.getStatus());
    }

    private void statusChanged(Preauth a, OhCase c) {
        if (c != null) {
            timeline.record(c.getId(), "PREAUTH_" + a.getStatus(), "Pre-authorisation " + a.getRef() + " " + a.getStatus().toLowerCase(java.util.Locale.ROOT));
        }
        bus.publish(new E04Events.PreauthStatus(a.getId().toString(), a.getCaseId().toString(), a.getStatus()));
    }

    /** Daily: approvals past their validity without an invoice expire. */
    @Transactional
    public int expire() {
        int n = 0;
        for (Preauth a : preauths.findByStatusInAndValidUntilBefore(APPROVED, support.today())) {
            if (invoices.findByPreauthIdOrMatchedPreauthId(a.getId(), a.getId()).isEmpty()) {
                a.setStatus("EXPIRED");
                a.setUpdatedAt(support.now());
                statusChanged(a, cases.findById(a.getCaseId()).orElse(null));
                n++;
            }
        }
        return n;
    }

    @Transactional(readOnly = true)
    public List<PreauthView> list(CurrentUser user, String status) {
        PageRequest page = PageRequest.of(0, 500);
        List<Preauth> list = status == null ? preauths.findAllByOrderByCreatedAtDesc(page) : preauths.findByStatusOrderByCreatedAtDesc(status, page);
        Map<UUID, OhCase> caseMap = new HashMap<>();
        cases.findAllById(list.stream().map(Preauth::getCaseId).distinct().toList()).forEach(c -> caseMap.put(c.getId(), c));
        boolean oh = user.hasAnyRole(Roles.OH_TEAM);
        List<PreauthView> out = new ArrayList<>();
        for (Preauth a : list) {
            OhCase c = caseMap.get(a.getCaseId());
            if (c != null && (oh ? access.canSeeClinical(user, c) : c.getClassification() != DataClassification.RESTRICTED_CLINICAL)) {
                out.add(view(a, c, false));
            }
        }
        audit.record("PREAUTH_LIST", "PREAUTH", null, AuditService.Outcome.SUCCESS, "rows=" + out.size());
        return out;
    }

    @Transactional(readOnly = true)
    public List<PreauthView> byCase(CurrentUser user, UUID caseId) {
        OhCase c = access.financial(user, caseId, AccessPolicy.Action.READ, "PREAUTH_CASE_LIST");
        boolean oh = user.hasAnyRole(Roles.OH_TEAM);
        List<PreauthView> out = preauths.findByCaseIdOrderByCreatedAtDesc(caseId).stream().map(a -> view(a, c, oh)).toList();
        audit.record("PREAUTH_CASE_LIST", "CASE", caseId.toString(), AuditService.Outcome.SUCCESS, "rows=" + out.size());
        return out;
    }

    /** TPA / insurer adjuster: own payer only, consented, never the clinical justification (only via the shared document). */
    @Transactional(readOnly = true)
    public List<PreauthView> partnerList(CurrentUser user, String status) {
        String org = TreatmentSupport.requirePartnerOrg(user);
        Set<String> statuses = status == null ? PARTNER_VISIBLE : PARTNER_VISIBLE.contains(status) ? Set.of(status) : Set.of();
        List<PreauthView> out = preauths.findByPayerCodeAndStatusInOrderByCreatedAtDesc(org, statuses, PageRequest.of(0, 500)).stream()
                .filter(a -> consents.isActive(a.getConsentId(), a.getEmployeeId(), ConsentPurpose.INSURER_SHARING)).map(this::partnerView).toList();
        audit.record("PARTNER_PREAUTH_LIST", "PREAUTH", null, AuditService.Outcome.SUCCESS, "payer=" + org + " rows=" + out.size());
        return out;
    }

    PreauthView partnerView(Preauth a) {
        Instant now = support.now();
        return new PreauthView(a.getId(), a.getRef(), null, null, a.getEmployeeId(), support.displayName(a.getEmployeeId()), a.getPayerCode(),
                support.partnerName(a.getPayerCode()), a.getServiceCode(), serviceName(a.getServiceCode()), a.getProviderCode(),
                support.partnerName(a.getProviderCode()), null, Money.of(a.getEstimateAmount(), a.getCurrency()), a.getStatus(), null, a.getCreatedAt(),
                a.getSubmittedAt(), a.getSlaDueAt(), slaBreached(a, now), a.getDecidedAt(), a.getApprovalRef(),
                Money.of(a.getApprovedAmount(), a.getCurrency()), a.getValidUntil(), a.getDocumentId(), null, a.getDecisionNote());
    }

    PreauthView view(Preauth a, OhCase c, boolean withJustification) {
        return new PreauthView(a.getId(), a.getRef(), a.getCaseId(), c == null ? null : c.getCaseRef(), a.getEmployeeId(),
                support.displayName(a.getEmployeeId()), a.getPayerCode(), support.partnerName(a.getPayerCode()), a.getServiceCode(),
                serviceName(a.getServiceCode()), a.getProviderCode(), support.partnerName(a.getProviderCode()), a.getReferralId(),
                Money.of(a.getEstimateAmount(), a.getCurrency()), a.getStatus(), a.getRequestedBy(), a.getCreatedAt(), a.getSubmittedAt(),
                a.getSlaDueAt(), slaBreached(a, support.now()), a.getDecidedAt(), a.getApprovalRef(), Money.of(a.getApprovedAmount(), a.getCurrency()),
                a.getValidUntil(), a.getDocumentId(), withJustification ? a.getJustification() : null, a.getDecisionNote());
    }

    static boolean slaBreached(Preauth a, Instant now) {
        if (a.getSlaDueAt() == null) {
            return false;
        }
        Instant end = a.getDecidedAt() == null ? now : a.getDecidedAt();
        return end.isAfter(a.getSlaDueAt());
    }

    private ServiceDefinition service(String code) {
        return services.findById(code).orElseThrow(() -> ApiException.badRequest("unknown_service", "Unknown service"));
    }

    private String serviceName(String code) {
        return services.findById(code).map(ServiceDefinition::getName).orElse(code);
    }

    // ------------------------------------------------------------------ invoices

    @Transactional
    public InvoiceView createInvoice(CurrentUser user, UUID caseId, InvoiceCommand cmd) {
        OhCase c = access.financial(user, caseId, AccessPolicy.Action.UPDATE, "INVOICE_CREATE");
        support.requireActivePartner(cmd.providerCode(), "providerCode");
        Money amount = new Money(cmd.amount(), cmd.currency());
        ProviderInvoice i = recordInvoice(user.username(), c, cmd.providerCode(), cmd.invoiceNumber(), amount, cmd.serviceDate(),
                cmd.referralId(), cmd.preauthId(), cmd.documentId());
        return view(i, c);
    }

    /** Shared by the claims / finance endpoint and the partner portal upload: duplicate check, matching, cost ledger. */
    @Transactional
    public ProviderInvoice recordInvoice(String actor, OhCase c, String providerCode, String invoiceNumber, Money amount, LocalDate serviceDate,
                                         UUID referralId, UUID preauthId, UUID documentId) {
        if (invoices.existsByProviderCodeAndInvoiceNumber(providerCode, invoiceNumber)) {
            throw ApiException.conflict("invoice_exists", "This invoice number was already received from the provider");
        }
        if (serviceDate.isAfter(support.today())) {
            throw ApiException.badRequest("invalid_service_date", "The service date cannot be in the future");
        }
        if (referralId != null) {
            referrals.findById(referralId).filter(r -> r.getCaseId().equals(c.getId()))
                    .orElseThrow(() -> ApiException.badRequest("invalid_referral", "The referral belongs to another case"));
        }
        Preauth matched = null;
        if (preauthId != null) {
            matched = preauths.findById(preauthId).filter(x -> x.getCaseId().equals(c.getId()))
                    .orElseThrow(() -> ApiException.badRequest("invalid_preauth", "The pre-authorisation belongs to another case"));
            if (!APPROVED.contains(matched.getStatus())) {
                matched = null;
            }
        } else {
            matched = preauths.findByCaseIdOrderByCreatedAtDesc(c.getId()).stream()
                    .filter(x -> APPROVED.contains(x.getStatus()) && providerCode.equals(x.getProviderCode())
                            && (x.getValidUntil() == null || !x.getValidUntil().isBefore(serviceDate)))
                    .findFirst().orElse(null);
        }
        InvoiceMatcher.Match m = InvoiceMatcher.match(amount.value(), amount.normalisedCurrency(),
                matched == null ? null : matched.getApprovedAmount(), matched == null ? null : matched.getCurrency(),
                support.configDecimal("treatment.invoice.tolerance-pct", BigDecimal.valueOf(5)));
        Instant now = support.now();
        ProviderInvoice i = new ProviderInvoice();
        i.setId(UUID.randomUUID());
        i.setCaseId(c.getId());
        i.setEmployeeId(c.getEmployeeId());
        i.setProviderCode(providerCode);
        i.setInvoiceNumber(invoiceNumber);
        i.setAmount(amount.value());
        i.setCurrency(amount.normalisedCurrency());
        i.setServiceDate(serviceDate);
        i.setReferralId(referralId);
        i.setPreauthId(preauthId);
        i.setDocumentId(documentId);
        i.setStatus(m.status());
        i.setMatchedPreauthId(matched == null ? null : matched.getId());
        i.setVariance(m.variance());
        i.setCreatedBy(actor);
        i.setCreatedAt(now);
        i.setUpdatedAt(now);
        invoices.save(i);
        spis.recordCost(actor, new CaseCostRecorder.CostEntry(c.getId(), c.getEmployeeId(), CaseCostRecorder.Category.MEDICAL,
                CaseCostRecorder.Kind.INVOICE, i.getAmount(), i.getCurrency(), serviceDate, providerCode, "Provider invoice " + invoiceNumber,
                "PROVIDER_INVOICE", i.getId().toString()));
        timeline.record(c.getId(), "INVOICE_RECEIVED", "Provider invoice received (" + m.status() + ")");
        audit.recordAs(actor, "MODULE", "INVOICE_CREATE", "INVOICE", i.getId().toString(), AuditService.Outcome.SUCCESS, m.status());
        return i;
    }

    @Transactional
    public InvoiceView invoiceStatus(CurrentUser user, UUID id, String status, String note) {
        ProviderInvoice i = invoices.findById(id).orElseThrow(() -> ApiException.notFound("Invoice"));
        OhCase c = access.financial(user, i.getCaseId(), AccessPolicy.Action.UPDATE, "INVOICE_STATUS");
        boolean ok = switch (status) {
            case "APPROVED", "DISPUTED" -> Set.of("RECEIVED", "MATCHED", "DISPUTED", "APPROVED").contains(i.getStatus()) && !status.equals(i.getStatus());
            case "PAID" -> Set.of("APPROVED", "MATCHED").contains(i.getStatus());
            default -> false;
        };
        if (!ok) {
            throw TreatmentSupport.unprocessable("invalid_transition", "Invoice " + i.getStatus() + " -> " + status + " not allowed");
        }
        i.setStatus(status);
        i.setNote(TreatmentSupport.functional("note", note, 1000));
        i.setUpdatedAt(support.now());
        timeline.record(c.getId(), "INVOICE_" + status, "Provider invoice " + status.toLowerCase(java.util.Locale.ROOT));
        audit.record("INVOICE_STATUS", "INVOICE", id.toString(), AuditService.Outcome.SUCCESS, status);
        return view(i, c);
    }

    @Transactional(readOnly = true)
    public List<InvoiceView> invoicesByCase(CurrentUser user, UUID caseId) {
        OhCase c = access.financial(user, caseId, AccessPolicy.Action.READ, "INVOICE_CASE_LIST");
        List<InvoiceView> out = invoices.findByCaseIdOrderByCreatedAtDesc(caseId).stream().map(i -> view(i, c)).toList();
        audit.record("INVOICE_CASE_LIST", "CASE", caseId.toString(), AuditService.Outcome.SUCCESS, "rows=" + out.size());
        return out;
    }

    public InvoiceView view(ProviderInvoice i, OhCase c) {
        return new InvoiceView(i.getId(), i.getCaseId(), c == null ? null : c.getCaseRef(), i.getEmployeeId(), i.getProviderCode(),
                support.partnerName(i.getProviderCode()), i.getInvoiceNumber(), Money.of(i.getAmount(), i.getCurrency()), i.getServiceDate(),
                i.getReferralId(), i.getPreauthId(), i.getDocumentId(), i.getStatus(), i.getMatchedPreauthId(),
                i.getVariance() == null ? null : new Money(i.getVariance().setScale(2, RoundingMode.HALF_UP).toPlainString(), i.getCurrency()),
                i.getNote(), i.getCreatedAt(), i.getCreatedBy());
    }

    // ------------------------------------------------------------------ cost roll-up (unit level, no names)

    @Transactional(readOnly = true)
    public List<CostRow> costs(CurrentUser user, String groupBy, LocalDate from, LocalDate to) {
        LocalDate f = from == null ? support.today().withDayOfYear(1) : from;
        LocalDate t = to == null ? support.today() : to;
        if (t.isBefore(f)) {
            throw ApiException.badRequest("invalid_range", "'to' is before 'from'");
        }
        String base = support.baseCurrency();
        Map<String, BigDecimal> estimated = new TreeMap<>();
        Map<String, BigDecimal> invoiced = new TreeMap<>();
        Map<String, Set<UUID>> caseSets = new TreeMap<>();
        Map<UUID, OhCase> caseCache = new HashMap<>();
        Instant fi = f.atStartOfDay().toInstant(ZoneOffset.UTC);
        Instant ti = t.plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC);
        for (Preauth a : preauths.findBySubmittedAtBetween(fi, ti)) {
            if (!base.equals(a.getCurrency()) || "CANCELLED".equals(a.getStatus())) {
                continue;
            }
            String key = key(groupBy, a.getCaseId(), caseCache);
            BigDecimal v = a.getApprovedAmount() != null ? a.getApprovedAmount() : a.getEstimateAmount();
            estimated.merge(key, v, BigDecimal::add);
            caseSets.computeIfAbsent(key, k -> new HashSet<>()).add(a.getCaseId());
        }
        for (ProviderInvoice i : invoices.findByServiceDateBetween(f, t)) {
            if (!base.equals(i.getCurrency())) {
                continue;
            }
            String key = key(groupBy, i.getCaseId(), caseCache);
            invoiced.merge(key, i.getAmount(), BigDecimal::add);
            caseSets.computeIfAbsent(key, k -> new HashSet<>()).add(i.getCaseId());
        }
        List<CostRow> out = new ArrayList<>();
        for (Map.Entry<String, Set<UUID>> e : caseSets.entrySet()) {
            int n = e.getValue().size();
            boolean suppressed = n < support.minGroupSize();
            BigDecimal est = estimated.getOrDefault(e.getKey(), BigDecimal.ZERO);
            BigDecimal inv = invoiced.getOrDefault(e.getKey(), BigDecimal.ZERO);
            out.add(new CostRow(e.getKey(), label(groupBy, e.getKey()), n, suppressed ? null : Money.of(est, base),
                    suppressed ? null : Money.of(inv, base),
                    suppressed ? null : Money.of(inv.divide(BigDecimal.valueOf(n), 2, RoundingMode.HALF_UP), base), suppressed));
        }
        audit.record("TREATMENT_COSTS_READ", "COSTS", groupBy, AuditService.Outcome.SUCCESS, "rows=" + out.size());
        return out;
    }

    public String costsCsv(CurrentUser user, String groupBy, LocalDate from, LocalDate to) {
        StringBuilder sb = new StringBuilder("key,label,cases,estimated,invoiced,avgPerCase,currency,suppressed\n");
        for (CostRow r : costs(user, groupBy, from, to)) {
            sb.append(csv(r.key())).append(',').append(csv(r.label())).append(',').append(r.cases()).append(',')
                    .append(r.estimated() == null ? "" : r.estimated().amount()).append(',')
                    .append(r.invoiced() == null ? "" : r.invoiced().amount()).append(',')
                    .append(r.avgPerCase() == null ? "" : r.avgPerCase().amount()).append(',').append(support.baseCurrency()).append(',')
                    .append(r.suppressed()).append('\n');
        }
        return sb.toString();
    }

    static String csv(String v) {
        if (v == null) {
            return "";
        }
        String s = v.replace("\"", "\"\"");
        if (!s.isEmpty() && "=+-@".indexOf(s.charAt(0)) >= 0) {
            s = "'" + s;
        }
        return "\"" + s + "\"";
    }

    private String key(String groupBy, UUID caseId, Map<UUID, OhCase> cache) {
        OhCase c = cache.computeIfAbsent(caseId, id -> cases.findById(id).orElse(null));
        String k = c == null ? null : switch (groupBy) {
            case "DEPARTMENT" -> c.getOrgUnitCode();
            case "COST_CENTRE" -> c.getCostCentre();
            default -> c.getSubsidiaryCode();
        };
        return k == null ? "UNASSIGNED" : k;
    }

    private String label(String groupBy, String key) {
        if ("DEPARTMENT".equals(groupBy)) {
            return orgUnits.findById(key).map(OrgUnit::getName).orElse(key);
        }
        return key;
    }

    public Optional<Referral> referral(UUID id) {
        return referrals.findById(id);
    }
}
