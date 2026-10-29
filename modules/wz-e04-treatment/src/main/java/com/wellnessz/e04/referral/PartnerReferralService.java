package com.wellnessz.e04.referral;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.wellnessz.casekernel.model.OhCase;
import com.wellnessz.common.error.ApiException;
import com.wellnessz.common.model.DataClassification;
import com.wellnessz.documents.DocumentService;
import com.wellnessz.e04.model.ProviderInvoice;
import com.wellnessz.e04.model.Referral;
import com.wellnessz.e04.model.TreatmentReferralRepository;
import com.wellnessz.e04.preauth.PreauthService;
import com.wellnessz.e04.support.Consents;
import com.wellnessz.e04.support.Money;
import com.wellnessz.e04.support.TreatmentAccess;
import com.wellnessz.e04.support.TreatmentSupport;
import com.wellnessz.masterdata.Employee;
import com.wellnessz.security.audit.AuditService;
import com.wellnessz.security.consent.ConsentPurpose;
import com.wellnessz.security.disclosure.DisclosureLogService;
import com.wellnessz.security.identity.CurrentUser;

/**
 * Partner provider portal (E04-F02): a provider sees and acts on the referrals addressed to its own organisation only
 * (org code = last segment of {@code org_scope}) and only under the employee's active TREATMENT_SHARING_PARTNER consent;
 * anything else is 404. The first read of each referral writes the disclosure log.
 */
@Service
public class PartnerReferralService {

    static final Set<String> VISIBLE = Set.of("SENT", "ACCEPTED", "DECLINED", "SCHEDULED", "SEEN", "REPORT_RECEIVED", "CLOSED", "CANCELLED");

    private final TreatmentReferralRepository referrals;
    private final ReferralCore core;
    private final EReferralService oh;
    private final PreauthService preauth;
    private final DocumentService documents;
    private final TreatmentAccess access;
    private final Consents consents;
    private final DisclosureLogService disclosures;
    private final TreatmentSupport support;
    private final AuditService audit;

    public PartnerReferralService(TreatmentReferralRepository referrals, ReferralCore core, EReferralService oh, PreauthService preauth,
                                  DocumentService documents, TreatmentAccess access, Consents consents, DisclosureLogService disclosures,
                                  TreatmentSupport support, AuditService audit) {
        this.referrals = referrals;
        this.core = core;
        this.oh = oh;
        this.preauth = preauth;
        this.documents = documents;
        this.access = access;
        this.consents = consents;
        this.disclosures = disclosures;
        this.support = support;
        this.audit = audit;
    }

    public record Patient(String displayName, String employeeId, String phone) {
    }

    public record PartnerReferral(UUID id, String ref, String serviceCode, String serviceName, String urgency, String status, Instant sentAt,
                                  Instant acceptedAt, Instant appointmentAt, String appointmentLocation, String appointmentInstructions,
                                  Instant seenAt, Instant reportDueAt, Instant reportReceivedAt, String declineReason, Patient patient, String reason,
                                  String question, Map<String, Object> dataSet, UUID letterDocumentId, List<ReferralCore.Attachment> attachments,
                                  List<ReferralCore.HistoryView> history) {
    }

    public record InvoiceReceipt(UUID id, String invoiceNumber, Money amount, String status, UUID documentId) {
    }

    // ------------------------------------------------------------------ reads

    @Transactional
    public List<PartnerReferral> list(CurrentUser user, String status) {
        String org = TreatmentSupport.requirePartnerOrg(user);
        Set<String> statuses = status == null ? VISIBLE : VISIBLE.contains(status) ? Set.of(status) : Set.of();
        List<Referral> list = referrals.findByProviderCodeAndStatusInOrderByCreatedAtDesc(org, statuses, PageRequest.of(0, 500)).stream()
                .filter(this::consented).toList();
        ReferralCore.Lookups l = core.lookups(list);
        List<PartnerReferral> out = new ArrayList<>();
        for (Referral r : list) {
            firstRead(user, r);
            out.add(view(r, l, false));
        }
        audit.record("PARTNER_REFERRAL_LIST", "REFERRAL", null, AuditService.Outcome.SUCCESS, "partner=" + org + " rows=" + out.size());
        return out;
    }

    @Transactional
    public PartnerReferral get(CurrentUser user, UUID id) {
        Referral r = load(user, id, "PARTNER_REFERRAL_READ");
        firstRead(user, r);
        audit.record("PARTNER_REFERRAL_READ", "REFERRAL", id.toString(), AuditService.Outcome.SUCCESS, null);
        return view(r, core.lookups(List.of(r)), true);
    }

    private boolean consented(Referral r) {
        return consents.isActive(r.getConsentId(), r.getEmployeeId(), ConsentPurpose.TREATMENT_SHARING_PARTNER);
    }

    private void firstRead(CurrentUser user, Referral r) {
        if (r.getPartnerReadAt() == null) {
            r.setPartnerReadAt(support.now());
            disclosures.record(r.getEmployeeId(), "PARTNER", support.partnerName(r.getProviderCode()), "REFERRAL", r.getId().toString(),
                    "Referral " + r.getRef(), "TREATMENT", r.getConsentId(), user.username());
        }
    }

    /** Own organisation, visible status and active consent – otherwise an audited 404. */
    Referral load(CurrentUser user, UUID id, String auditAction) {
        String org = TreatmentSupport.requirePartnerOrg(user);
        Referral r = referrals.findById(id).orElse(null);
        if (r == null || !org.equals(r.getProviderCode()) || !VISIBLE.contains(r.getStatus()) || !consented(r)) {
            audit.record(auditAction, "REFERRAL", id.toString(), AuditService.Outcome.DENIED, "Not addressed to the caller's organisation");
            throw ApiException.notFound("Referral");
        }
        return r;
    }

    PartnerReferral view(Referral r, ReferralCore.Lookups l, boolean detail) {
        Employee e = support.employee(r.getEmployeeId()).orElse(null);
        boolean cancelled = "CANCELLED".equals(r.getStatus());
        return new PartnerReferral(r.getId(), r.getRef(), r.getServiceCode(), l.serviceName(r.getServiceCode()), r.getUrgency(), r.getStatus(),
                r.getSentAt(), r.getAcceptedAt(), r.getAppointmentAt(), r.getAppointmentLocation(), r.getAppointmentInstructions(), r.getSeenAt(),
                r.getReportDueAt(), r.getReportReceivedAt(), r.getDeclineReason(),
                new Patient(e == null ? r.getEmployeeId() : e.getDisplayName(), r.getEmployeeId(), e == null || cancelled ? null : e.getPhone()),
                cancelled ? null : r.getReason(), cancelled ? null : r.getQuestion(), cancelled ? null : core.dataSet(r),
                cancelled ? null : r.getLetterDocumentId(), cancelled ? List.of() : core.attachments(r),
                detail ? core.view(r, l, true).history() : null);
    }

    // ------------------------------------------------------------------ actions

    @Transactional
    public PartnerReferral accept(CurrentUser user, UUID id) {
        Referral r = load(user, id, "PARTNER_REFERRAL_ACCEPT");
        core.requireStatus(r, Set.of("SENT"), "accept");
        r.setAcceptedAt(support.now());
        core.transition(r, "ACCEPTED", user.username());
        audit.record("PARTNER_REFERRAL_ACCEPT", "REFERRAL", id.toString(), AuditService.Outcome.SUCCESS, null);
        return view(r, core.lookups(List.of(r)), true);
    }

    @Transactional
    public PartnerReferral decline(CurrentUser user, UUID id, String reason) {
        Referral r = load(user, id, "PARTNER_REFERRAL_DECLINE");
        core.requireStatus(r, Set.of("SENT", "ACCEPTED"), "decline");
        r.setDeclineReason(TreatmentSupport.functional("reason", reason, 500));
        r.setDeclinedAt(support.now());
        core.transition(r, "DECLINED", user.username());
        String clinician = r.getSentBy() == null ? r.getCreatedBy() : r.getSentBy();
        support.notifyStaff(clinician, "case.update", Map.of("caseRef", nz(access.caseRef(r.getCaseId()))), "/#/referrals/" + r.getId());
        audit.record("PARTNER_REFERRAL_DECLINE", "REFERRAL", id.toString(), AuditService.Outcome.SUCCESS, null);
        return view(r, core.lookups(List.of(r)), true);
    }

    @Transactional
    public PartnerReferral appointment(CurrentUser user, UUID id, Instant appointmentAt, String location, String instructions) {
        Referral r = load(user, id, "PARTNER_REFERRAL_APPOINTMENT");
        core.requireStatus(r, Set.of("SENT", "ACCEPTED", "SCHEDULED"), "schedule");
        if (appointmentAt.isBefore(support.now().minusSeconds(3600))) {
            throw ApiException.badRequest("invalid_appointment", "The appointment cannot be in the past");
        }
        if (r.getAcceptedAt() == null) {
            r.setAcceptedAt(support.now());
        }
        r.setAppointmentAt(appointmentAt);
        r.setAppointmentLocation(TreatmentSupport.functional("location", location, 300));
        r.setAppointmentInstructions(TreatmentSupport.functional("instructions", instructions, 1000));
        core.transition(r, "SCHEDULED", user.username());
        var at = appointmentAt.atZone(ZoneOffset.UTC);
        support.notifyEmployee(r.getEmployeeId(), "referral.appointment", Map.of("appointmentDate", at.toLocalDate().toString(),
                "appointmentTime", at.toLocalTime().withSecond(0).withNano(0) + " UTC"), "/#/treatment");
        audit.record("PARTNER_REFERRAL_APPOINTMENT", "REFERRAL", id.toString(), AuditService.Outcome.SUCCESS, null);
        return view(r, core.lookups(List.of(r)), true);
    }

    @Transactional
    public PartnerReferral seen(CurrentUser user, UUID id) {
        Referral r = load(user, id, "PARTNER_REFERRAL_SEEN");
        core.requireStatus(r, Set.of("ACCEPTED", "SCHEDULED"), "mark as seen");
        r.setSeenAt(support.now());
        core.transition(r, "SEEN", user.username());
        audit.record("PARTNER_REFERRAL_SEEN", "REFERRAL", id.toString(), AuditService.Outcome.SUCCESS, null);
        return view(r, core.lookups(List.of(r)), true);
    }

    @Transactional
    public PartnerReferral report(CurrentUser user, UUID id, byte[] bytes, String fileName, String summary) {
        Referral r = load(user, id, "PARTNER_REFERRAL_REPORT");
        oh.receiveReport(r, user.username(), bytes, fileName == null ? null : "Provider report – " + r.getRef(), summary);
        audit.record("PARTNER_REFERRAL_REPORT", "REFERRAL", id.toString(), AuditService.Outcome.SUCCESS, null);
        return view(r, core.lookups(List.of(r)), true);
    }

    @Transactional
    public InvoiceReceipt invoice(CurrentUser user, UUID id, byte[] bytes, String invoiceNumber, Money amount, LocalDate serviceDate) {
        Referral r = load(user, id, "PARTNER_REFERRAL_INVOICE");
        core.requireStatus(r, Set.of("ACCEPTED", "SCHEDULED", "SEEN", "REPORT_RECEIVED", "CLOSED"), "invoice");
        if (invoiceNumber == null || !invoiceNumber.matches("[A-Za-z0-9_/\\-]{1,60}")) {
            throw ApiException.badRequest("invalid_invoice_number", "Invoice numbers are 1–60 letters, digits, '/', '-' or '_'");
        }
        OhCase c = access.find(r.getCaseId()).orElseThrow(() -> ApiException.notFound("Referral"));
        DocumentService.Stored stored = documents.storeUpload(user.username(), r.getEmployeeId(), r.getCaseId(), "PROVIDER_INVOICE",
                "Provider invoice " + invoiceNumber + " – " + r.getRef(), bytes, DataClassification.CONFIDENTIAL);
        ProviderInvoice i = preauth.recordInvoice(user.username(), c, r.getProviderCode(), invoiceNumber, amount, serviceDate, r.getId(), null,
                stored.document().id());
        audit.record("PARTNER_REFERRAL_INVOICE", "REFERRAL", id.toString(), AuditService.Outcome.SUCCESS, i.getStatus());
        return new InvoiceReceipt(i.getId(), i.getInvoiceNumber(), Money.of(i.getAmount(), i.getCurrency()), i.getStatus(), i.getDocumentId());
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
