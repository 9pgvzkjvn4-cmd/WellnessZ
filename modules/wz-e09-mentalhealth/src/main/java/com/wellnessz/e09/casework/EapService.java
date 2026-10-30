package com.wellnessz.e09.casework;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.wellnessz.common.error.ApiException;
import com.wellnessz.e09.model.E09EapReferralRepository;
import com.wellnessz.e09.model.E09SupportCaseRepository;
import com.wellnessz.e09.model.EapReferral;
import com.wellnessz.e09.model.MentalHealthEnums.ClosureReason;
import com.wellnessz.e09.model.MentalHealthEnums.EapStatus;
import com.wellnessz.e09.model.MentalHealthEnums.SupportStatus;
import com.wellnessz.e09.model.SupportCase;
import com.wellnessz.e09.rules.Calendar;
import com.wellnessz.e09.support.MhSupport;
import com.wellnessz.e09.support.SupportAccess;
import com.wellnessz.masterdata.PartnerOrganisation;
import com.wellnessz.security.audit.AuditService;
import com.wellnessz.security.consent.Consent;
import com.wellnessz.security.consent.ConsentPurpose;
import com.wellnessz.security.consent.ConsentRepository;
import com.wellnessz.security.consent.ConsentService;
import com.wellnessz.security.disclosure.DisclosureLogService;
import com.wellnessz.security.identity.CurrentUser;

import tools.jackson.core.type.TypeReference;

/**
 * EAP / counselling partner referral (E09-BL-005): consent-gated e-referral with a 5-working-day first-appointment target; the
 * partner sees minimal data of its own referrals only (disclosure-logged) and feeds back status, attendance and closure reason –
 * never clinical content or free text.
 */
@Service
public class EapService {

    public static final String RESOURCE = "EAP_REFERRAL";
    private static final TypeReference<List<Attendance>> ATTENDANCE = new TypeReference<>() {
    };

    private final E09EapReferralRepository referrals;
    private final E09SupportCaseRepository supportCases;
    private final SupportCaseService cases;
    private final ConsentService consents;
    private final ConsentRepository consentRepository;
    private final DisclosureLogService disclosures;
    private final MhSupport support;
    private final SupportAccess access;
    private final AuditService audit;

    public EapService(E09EapReferralRepository referrals, E09SupportCaseRepository supportCases, SupportCaseService cases, ConsentService consents,
                      ConsentRepository consentRepository,
                      DisclosureLogService disclosures, MhSupport support, SupportAccess access, AuditService audit) {
        this.referrals = referrals;
        this.supportCases = supportCases;
        this.cases = cases;
        this.consents = consents;
        this.consentRepository = consentRepository;
        this.disclosures = disclosures;
        this.support = support;
        this.access = access;
        this.audit = audit;
    }

    public record Attendance(LocalDate date, boolean attended) {
    }

    public record CreateCommand(String partnerCode, int sessionsAuthorised, String urgency, UUID consentId) {
    }

    public record EapReferralView(UUID id, String ref, UUID caseId, String caseRef, String partnerCode, String partnerName, String status,
                                  int sessionsAuthorised, String urgency, Instant sentAt, LocalDate firstAppointmentDueBy,
                                  List<Attendance> attendance, String closureReason, Instant feedbackAt) {
    }

    /** Minimal partner view (contract §3.2): no employee id, no concern, no clinical content. */
    public record PartnerReferralView(UUID id, String ref, String displayName, String contactPhone, String preferredContact, int sessionsAuthorised,
                                      String urgency, String status, Instant sentAt, LocalDate firstAppointmentDueBy, List<Attendance> attendance,
                                      String closureReason) {
    }

    @Transactional
    public EapReferralView create(CurrentUser user, UUID caseId, CreateCommand cmd) {
        SupportCase c = cases.require(user, caseId, "EAP_REFERRAL_CREATE", true);
        if (SupportStatus.CLOSED.name().equals(c.getStatus())) {
            throw ApiException.conflict("case_closed", "The support case is closed");
        }
        PartnerOrganisation p = support.partner(cmd.partnerCode()).filter(PartnerOrganisation::isActive)
                .filter(x -> "MENTAL_HEALTH".equals(x.getOrgType()))
                .orElseThrow(() -> MhSupport.unprocessable("unknown_partner", "Unknown or inactive EAP / counselling partner"));
        if (cmd.consentId() == null || !(consents.isActive(cmd.consentId(), c.getEmployeeId(), ConsentPurpose.TREATMENT_SHARING_PARTNER)
                || consents.isActive(cmd.consentId(), c.getEmployeeId(), ConsentPurpose.EAP_REFERRAL))) {
            throw MhSupport.unprocessable("consent_required", "An active consent of the employee to the partner referral is required");
        }
        boolean active = referrals.findBySupportCaseIdOrderBySentAtDesc(c.getId()).stream()
                .anyMatch(r -> !EapStatus.CLOSED.name().equals(r.getStatus()));
        if (active) {
            throw ApiException.conflict("referral_active", "The case already has an active EAP referral");
        }
        Instant now = support.now();
        EapReferral r = EapReferral.create();
        r.setRef(support.nextRef("EAP"));
        r.setSupportCaseId(c.getId());
        r.setEmployeeId(c.getEmployeeId());
        r.setPartnerCode(p.getCode());
        r.setSessionsAuthorised(cmd.sessionsAuthorised());
        r.setUrgency(cmd.urgency());
        r.setConsentId(cmd.consentId());
        r.setStatus(EapStatus.SENT.name());
        r.setFirstAppointmentDueBy(Calendar.plusWorkingDays(support.today(), support.intConfig("mentalhealth.eap-first-appointment-working-days", 5)));
        r.setSentAt(now);
        r.setSentBy(user.username());
        r.setUpdatedAt(now);
        referrals.save(r);
        c.setStatus(SupportStatus.REFERRED.name());
        c.setUpdatedAt(now);
        cases.event(c, "EAP_REFERRED", "Referred to " + p.getName() + " (" + r.getRef() + ")", user.username());
        disclosures.record(c.getEmployeeId(), "PARTNER", p.getName(), RESOURCE, r.getId().toString(), "EAP referral " + r.getRef(),
                "EAP / counselling referral (name, contact, sessions)", cmd.consentId(), user.username());
        support.notifyPartner(p.getCode(), "support.partner-referral", Map.of("reference", r.getRef()), "eap-referrals");
        audit.record("EAP_REFERRAL_CREATE", RESOURCE, r.getId().toString(), AuditService.Outcome.SUCCESS, p.getCode());
        return view(r, c);
    }

    public record ConsentView(UUID id, String purpose, Instant grantedAt, String scope) {
    }

    /** Active consents of the case's employee (consent picker of the EAP referral), optionally of one purpose. */
    @Transactional(readOnly = true)
    public List<ConsentView> consents(CurrentUser user, UUID caseId, ConsentPurpose purpose) {
        SupportCase c = cases.require(user, caseId, "SUPPORT_CONSENTS_READ", true);
        return consentRepository.findByEmployeeIdOrderByGrantedAtDesc(c.getEmployeeId()).stream()
                .filter(x -> x.getStatus() == Consent.Status.GRANTED && (purpose == null || x.getPurpose() == purpose))
                .map(x -> new ConsentView(x.getId(), x.getPurpose().name(), x.getGrantedAt(), x.getScope())).toList();
    }

    public record ClinicianReferralView(UUID id, String ref, UUID caseId, String caseRef, String employeeId, String displayName, String partnerCode,
                                        String partnerName, String status, int sessionsAuthorised, String urgency, Instant sentAt,
                                        LocalDate firstAppointmentDueBy, List<Attendance> attendance, String closureReason, Instant feedbackAt) {
    }

    /** All EAP referrals – overview for the named clinicians. */
    @Transactional(readOnly = true)
    public List<ClinicianReferralView> list(CurrentUser user, String status) {
        access.requireNamed(user, "EAP_REFERRAL_LIST", null);
        List<ClinicianReferralView> out = referrals.findAllByOrderBySentAtDesc().stream()
                .filter(r -> status == null || status.equals(r.getStatus()))
                .map(r -> {
                    SupportCase c = supportCases.findById(r.getSupportCaseId()).orElseThrow();
                    return new ClinicianReferralView(r.getId(), r.getRef(), c.getId(), c.getCaseRef(), r.getEmployeeId(), support.name(r.getEmployeeId()),
                            r.getPartnerCode(), support.partnerName(r.getPartnerCode()), r.getStatus(), r.getSessionsAuthorised(), r.getUrgency(),
                            r.getSentAt(), r.getFirstAppointmentDueBy(), attendance(r), r.getClosureReason(), r.getFeedbackAt());
                }).toList();
        audit.record("EAP_REFERRAL_LIST", RESOURCE, null, AuditService.Outcome.SUCCESS, "returned=" + out.size());
        return out;
    }

    // ------------------------------------------------------------------ partner portal

    @Transactional
    public List<PartnerReferralView> partnerList(CurrentUser user, String status) {
        String org = user.partnerOrganisation();
        if (org == null) {
            audit.record("EAP_PARTNER_LIST", RESOURCE, null, AuditService.Outcome.DENIED, "No partner organisation");
            return List.of();
        }
        List<PartnerReferralView> out = new ArrayList<>();
        for (EapReferral r : referrals.findByPartnerCodeOrderBySentAtDesc(org)) {
            if (status != null && !status.equals(r.getStatus())) {
                continue;
            }
            SupportCase c = supportCases.findById(r.getSupportCaseId()).orElse(null);
            if (c == null) {
                continue;
            }
            out.add(partnerView(r, c));
            logDisclosure(r, user);
        }
        audit.record("EAP_PARTNER_LIST", RESOURCE, null, AuditService.Outcome.SUCCESS, "returned=" + out.size());
        return out;
    }

    private void logDisclosure(EapReferral r, CurrentUser user) {
        String title = "EAP referral " + r.getRef() + " (" + r.getStatus() + ")";
        boolean logged = disclosures.forEmployee(r.getEmployeeId(), false).stream()
                .anyMatch(d -> RESOURCE.equals(d.resourceType()) && r.getId().toString().equals(d.resourceId()) && title.equals(d.documentTitle())
                        && "ACTIVE".equals(d.status()));
        if (!logged) {
            disclosures.record(r.getEmployeeId(), "PARTNER", support.partnerName(r.getPartnerCode()), RESOURCE, r.getId().toString(), title,
                    "Partner portal: name, contact, sessions", r.getConsentId(), user.username());
        }
    }

    public record Feedback(EapStatus status, List<Attendance> attendance, ClosureReason closureReason) {
    }

    @Transactional
    public PartnerReferralView feedback(CurrentUser user, UUID id, Feedback f) {
        String org = user.partnerOrganisation();
        EapReferral r = referrals.findById(id).orElse(null);
        if (r == null || org == null || !org.equals(r.getPartnerCode())) {
            audit.record("EAP_FEEDBACK", RESOURCE, id.toString(), AuditService.Outcome.DENIED, "Not the referral's partner");
            throw ApiException.notFound("Referral");
        }
        EapStatus current = EapStatus.valueOf(r.getStatus());
        if (current == EapStatus.CLOSED) {
            throw ApiException.conflict("referral_closed", "The referral is closed");
        }
        if (f.status().ordinal() < current.ordinal() || f.status() == EapStatus.SENT) {
            throw ApiException.conflict("invalid_transition", "Status " + current + " -> " + f.status() + " not allowed");
        }
        if (f.status() == EapStatus.CLOSED && f.closureReason() == null) {
            throw MhSupport.unprocessable("closure_reason_required", "A closure reason is required when closing");
        }
        if (f.status() != EapStatus.CLOSED && f.closureReason() != null) {
            throw MhSupport.unprocessable("closure_reason_not_allowed", "A closure reason is only given when closing");
        }
        Map<LocalDate, Attendance> merged = new TreeMap<>();
        if (r.getAttendance() != null) {
            support.read(r.getAttendance(), ATTENDANCE).forEach(a -> merged.put(a.date(), a));
        }
        if (f.attendance() != null) {
            for (Attendance a : f.attendance()) {
                if (a == null || a.date() == null || a.date().isAfter(support.today()) || a.date().isBefore(r.getSentAt().atZone(java.time.ZoneOffset.UTC)
                        .toLocalDate())) {
                    throw MhSupport.unprocessable("invalid_attendance", "Attendance dates lie between the referral and today");
                }
                merged.put(a.date(), a);
            }
        }
        if (merged.values().stream().filter(Attendance::attended).count() > r.getSessionsAuthorised()) {
            throw MhSupport.unprocessable("sessions_exceeded", "More attended sessions than authorised");
        }
        Instant now = support.now();
        r.setStatus(f.status().name());
        r.setAttendance(merged.isEmpty() ? null : support.json(new ArrayList<>(merged.values())));
        r.setClosureReason(f.closureReason() == null ? null : f.closureReason().name());
        r.setFeedbackAt(now);
        r.setFeedbackBy(user.username());
        r.setUpdatedAt(now);
        SupportCase c = supportCases.findById(r.getSupportCaseId()).orElseThrow();
        cases.event(c, "EAP_FEEDBACK", "EAP " + r.getRef() + ": " + f.status() + (f.closureReason() == null ? "" : " (" + f.closureReason() + ")")
                + ", attended " + merged.values().stream().filter(Attendance::attended).count(), user.username());
        if (f.status() == EapStatus.CLOSED && SupportStatus.REFERRED.name().equals(c.getStatus())) {
            c.setStatus(SupportStatus.ACTIVE.name());
        }
        c.setLastContactAt(now);
        c.setUpdatedAt(now);
        audit.record("EAP_FEEDBACK", RESOURCE, id.toString(), AuditService.Outcome.SUCCESS, f.status().name());
        return partnerView(r, c);
    }

    private PartnerReferralView partnerView(EapReferral r, SupportCase c) {
        return new PartnerReferralView(r.getId(), r.getRef(), support.name(c.getEmployeeId()), c.getContactPhone(), c.getPreferredContact(),
                r.getSessionsAuthorised(), r.getUrgency(), r.getStatus(), r.getSentAt(), r.getFirstAppointmentDueBy(), attendance(r),
                r.getClosureReason());
    }

    private List<Attendance> attendance(EapReferral r) {
        return r.getAttendance() == null ? List.of() : support.read(r.getAttendance(), ATTENDANCE);
    }

    private EapReferralView view(EapReferral r, SupportCase c) {
        return new EapReferralView(r.getId(), r.getRef(), c.getId(), c.getCaseRef(), r.getPartnerCode(), support.partnerName(r.getPartnerCode()),
                r.getStatus(), r.getSessionsAuthorised(), r.getUrgency(), r.getSentAt(), r.getFirstAppointmentDueBy(), attendance(r),
                r.getClosureReason(), r.getFeedbackAt());
    }
}
