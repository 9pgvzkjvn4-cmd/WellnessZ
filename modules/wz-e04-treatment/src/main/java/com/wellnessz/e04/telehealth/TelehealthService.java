package com.wellnessz.e04.telehealth;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.wellnessz.casekernel.model.OhCase;
import com.wellnessz.casekernel.support.AppointmentBooking;
import com.wellnessz.casekernel.support.AppointmentStatusListener;
import com.wellnessz.casekernel.support.CaseTimeline;
import com.wellnessz.casekernel.support.EmployeeRecordAccess;
import com.wellnessz.common.error.ApiException;
import com.wellnessz.common.model.DataClassification;
import com.wellnessz.common.model.Roles;
import com.wellnessz.e03.encounter.ClinicalEncounterService;
import com.wellnessz.e04.model.TelehealthMessage;
import com.wellnessz.e04.model.TelehealthMessageRepository;
import com.wellnessz.e04.model.TelehealthSession;
import com.wellnessz.e04.model.TelehealthSessionRepository;
import com.wellnessz.e04.model.TelehealthToken;
import com.wellnessz.e04.model.TelehealthTokenRepository;
import com.wellnessz.e04.plan.PlanService;
import com.wellnessz.e04.support.TreatmentAccess;
import com.wellnessz.e04.support.TreatmentSpis;
import com.wellnessz.e04.support.TreatmentSupport;
import com.wellnessz.masterdata.Employee;
import com.wellnessz.security.abac.AccessPolicy;
import com.wellnessz.security.audit.AuditService;
import com.wellnessz.security.consent.Consent;
import com.wellnessz.security.consent.ConsentPurpose;
import com.wellnessz.security.consent.ConsentService;
import com.wellnessz.security.disclosure.DisclosureLogService;
import com.wellnessz.security.identity.CurrentUser;

/**
 * Telehealth consultation (E04-F07): scheduling (incl. crew on layover with a partner specialist), employee consent,
 * single-use participant join tokens, encrypted secure chat, completion into a DRAFT E03 encounter and an optional
 * follow-up on the treatment plan.
 */
@Service
public class TelehealthService {

    public static final String SOURCE_TYPE = "TELEHEALTH_SESSION";

    public enum Participant { EMPLOYEE, CLINICIAN, PARTNER }

    static final Set<String> LIVE = Set.of("SCHEDULED", "IN_PROGRESS");

    private final TelehealthSessionRepository sessions;
    private final TelehealthTokenRepository tokens;
    private final TelehealthMessageRepository messages;
    private final EmployeeRecordAccess records;
    private final TreatmentAccess access;
    private final ConsentService consents;
    private final DisclosureLogService disclosures;
    private final ClinicalEncounterService encounters;
    private final PlanService plans;
    private final TelehealthProvider provider;
    private final CaseTimeline timeline;
    private final TreatmentSpis spis;
    private final TreatmentSupport support;
    private final AuditService audit;

    public TelehealthService(TelehealthSessionRepository sessions, TelehealthTokenRepository tokens, TelehealthMessageRepository messages,
                             EmployeeRecordAccess records, TreatmentAccess access, ConsentService consents, DisclosureLogService disclosures,
                             ClinicalEncounterService encounters, PlanService plans, TelehealthProvider provider, CaseTimeline timeline,
                             TreatmentSpis spis, TreatmentSupport support, AuditService audit) {
        this.sessions = sessions;
        this.tokens = tokens;
        this.messages = messages;
        this.records = records;
        this.access = access;
        this.consents = consents;
        this.disclosures = disclosures;
        this.encounters = encounters;
        this.plans = plans;
        this.provider = provider;
        this.timeline = timeline;
        this.spis = spis;
        this.support = support;
        this.audit = audit;
    }

    public record SessionCommand(@NotBlank @Pattern(regexp = "[A-Za-z0-9_\\-]{1,64}") String employeeId, UUID caseId, @NotNull Instant scheduledAt,
                                 @NotNull @Min(10) @Max(90) Integer durationMinutes, @NotBlank @Pattern(regexp = "VIDEO|CHAT") String mode,
                                 @Pattern(regexp = "[A-Za-z0-9_.@\\-]{1,200}") String clinicianUsername,
                                 @Pattern(regexp = "[A-Z0-9_]{2,40}") String providerCode, Boolean crewLayover,
                                 @Pattern(regexp = "[\\p{L} .'\\-]{2,100}") String layoverCity) {
    }

    public record SessionView(UUID id, String ref, String employeeId, String displayName, UUID caseId, Instant scheduledAt, int durationMinutes,
                              String mode, String status, String clinicianUsername, String clinicianName, String providerCode, String providerName,
                              boolean crewLayover, String layoverCity, boolean consentGiven, UUID encounterId, UUID appointmentId,
                              Instant joinOpensAt, Instant joinClosesAt) {
    }

    public record Join(String joinUrl, Instant expiresAt) {
    }

    public record MessageView(UUID id, String sender, String senderRole, String text, Instant sentAt) {
    }

    public record TokenCheck(boolean valid, String sessionRef, String participant, String username) {
    }

    // ------------------------------------------------------------------ scheduling

    @Transactional
    public SessionView create(CurrentUser user, SessionCommand cmd) {
        Employee e = records.requireClinical(user, cmd.employeeId(), DataClassification.CLINICAL, AccessPolicy.Action.CREATE, "TELEHEALTH_CREATE");
        if (cmd.caseId() != null) {
            OhCase c = access.clinical(user, cmd.caseId(), AccessPolicy.Action.UPDATE, "TELEHEALTH_CREATE");
            if (!c.getEmployeeId().equals(e.getEmployeeId())) {
                throw ApiException.badRequest("case_employee_mismatch", "The case belongs to another employee");
            }
        }
        if (cmd.scheduledAt().isBefore(support.now().minus(Duration.ofMinutes(5)))) {
            throw ApiException.badRequest("invalid_schedule", "The session cannot start in the past");
        }
        if (cmd.providerCode() != null) {
            support.requireActivePartner(cmd.providerCode(), "providerCode");
        }
        boolean layover = Boolean.TRUE.equals(cmd.crewLayover());
        if (layover && cmd.layoverCity() == null) {
            throw ApiException.badRequest("layover_city_required", "Give the layover city for crew on layover");
        }
        Instant now = support.now();
        TelehealthSession s = new TelehealthSession();
        s.setId(UUID.randomUUID());
        s.setRef(support.nextRef("TH"));
        s.setEmployeeId(e.getEmployeeId());
        s.setCaseId(cmd.caseId());
        s.setScheduledAt(cmd.scheduledAt());
        s.setDurationMinutes(cmd.durationMinutes());
        s.setMode(cmd.mode());
        s.setStatus("SCHEDULED");
        s.setClinicianUsername(cmd.clinicianUsername() == null ? user.username() : cmd.clinicianUsername());
        s.setProviderCode(cmd.providerCode());
        s.setCrewLayover(layover);
        s.setLayoverCity(layover ? cmd.layoverCity().strip() : null);
        s.setCreatedBy(user.username());
        s.setCreatedAt(now);
        s.setUpdatedAt(now);
        TelehealthSession saved = sessions.saveAndFlush(s);
        if (saved.getProviderCode() == null && support.staffMember(saved.getClinicianUsername()).isPresent()) {
            spis.book(user.username(), new AppointmentBooking.BookingRequest(saved.getEmployeeId(), "TELEHEALTH_CONSULT", null,
                    LocalDate.ofInstant(saved.getScheduledAt(), ZoneOffset.UTC), null, SOURCE_TYPE, saved.getId().toString(), saved.getCaseId()))
                    .ifPresent(b -> saved.setAppointmentId(b.appointmentId()));
        }
        return created(user, saved);
    }

    private SessionView created(CurrentUser user, TelehealthSession s) {
        ZonedDateTime at = s.getScheduledAt().atZone(ZoneOffset.UTC);
        support.notifyEmployee(s.getEmployeeId(), "telehealth.invite", Map.of("appointmentDate", at.toLocalDate().toString(),
                "appointmentTime", at.toLocalTime().withSecond(0).withNano(0) + " UTC"), "/#/telehealth");
        if (s.getCaseId() != null) {
            timeline.record(s.getCaseId(), "TELEHEALTH_SCHEDULED", "Telehealth consultation " + s.getRef() + " scheduled");
        }
        audit.record("TELEHEALTH_CREATE", "TELEHEALTH_SESSION", s.getId().toString(), AuditService.Outcome.SUCCESS,
                "provider=" + s.getProviderCode() + " layover=" + s.isCrewLayover());
        return view(s, true);
    }

    @Transactional(readOnly = true)
    public List<SessionView> list(CurrentUser user, LocalDate from, LocalDate to, String status) {
        LocalDate f = from == null ? support.today().minusDays(7) : from;
        LocalDate t = to == null ? support.today().plusDays(60) : to;
        List<SessionView> out = new ArrayList<>();
        for (TelehealthSession s : sessions.findByScheduledAtBetweenOrderByScheduledAt(f.atStartOfDay().toInstant(ZoneOffset.UTC),
                t.plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC))) {
            if (status != null && !status.equals(s.getStatus())) {
                continue;
            }
            Employee e = support.employee(s.getEmployeeId()).orElse(null);
            if (e != null && records.canSeeClinical(user, e, DataClassification.CLINICAL)) {
                out.add(view(s, true));
            }
            if (out.size() >= 500) {
                break;
            }
        }
        audit.record("TELEHEALTH_LIST", "TELEHEALTH_SESSION", null, AuditService.Outcome.SUCCESS, "rows=" + out.size());
        return out;
    }

    @Transactional
    public List<SessionView> partnerList(CurrentUser user) {
        String org = TreatmentSupport.requirePartnerOrg(user);
        List<SessionView> out = new ArrayList<>();
        for (TelehealthSession s : sessions.findByProviderCodeOrderByScheduledAtDesc(org, PageRequest.of(0, 500))) {
            if (s.getPartnerReadAt() == null) {
                s.setPartnerReadAt(support.now());
                disclosures.record(s.getEmployeeId(), "PARTNER", support.partnerName(org), "TELEHEALTH_SESSION", s.getId().toString(),
                        "Telehealth consultation " + s.getRef(), "TREATMENT", s.getConsentId(), user.username());
            }
            out.add(view(s, false));
        }
        audit.record("PARTNER_TELEHEALTH_LIST", "TELEHEALTH_SESSION", null, AuditService.Outcome.SUCCESS, "partner=" + org + " rows=" + out.size());
        return out;
    }

    @Transactional(readOnly = true)
    public List<SessionView> mine(CurrentUser user) {
        if (user.employeeId() == null) {
            return List.of();
        }
        return sessions.findByEmployeeIdOrderByScheduledAtDesc(user.employeeId()).stream().map(s -> view(s, false)).toList();
    }

    SessionView view(TelehealthSession s, boolean oh) {
        return new SessionView(s.getId(), s.getRef(), s.getEmployeeId(), support.displayName(s.getEmployeeId()), oh ? s.getCaseId() : null,
                s.getScheduledAt(), s.getDurationMinutes(), s.getMode(), s.getStatus(), s.getClinicianUsername(), support.staffName(s.getClinicianUsername()),
                s.getProviderCode(), s.getProviderCode() == null ? null : support.partnerName(s.getProviderCode()), s.isCrewLayover(), s.getLayoverCity(),
                consentGiven(s), oh ? s.getEncounterId() : null, oh ? s.getAppointmentId() : null, TokenWindow.opensAt(s.getScheduledAt()),
                TokenWindow.closesAt(s.getScheduledAt(), s.getDurationMinutes()));
    }

    boolean consentGiven(TelehealthSession s) {
        return consents.isActive(s.getConsentId(), s.getEmployeeId(), ConsentPurpose.TELEHEALTH_CONSULTATION);
    }

    // ------------------------------------------------------------------ participants

    /** Loads the session for a participant; anyone else gets an audited 404. */
    TelehealthSession participant(CurrentUser user, UUID id, Participant role, String auditAction) {
        TelehealthSession s = sessions.findById(id).orElse(null);
        boolean ok = s != null && switch (role) {
            case EMPLOYEE -> user.employeeId() != null && user.employeeId().equals(s.getEmployeeId());
            case PARTNER -> s.getProviderCode() != null && s.getProviderCode().equals(TreatmentSupport.partnerOrg(user));
            case CLINICIAN -> user.hasAnyRole(Roles.OH_TEAM) && support.employee(s.getEmployeeId())
                    .map(e -> records.canSeeClinical(user, e, DataClassification.CLINICAL)).orElse(false);
        };
        if (!ok) {
            audit.record(auditAction, "TELEHEALTH_SESSION", id.toString(), AuditService.Outcome.DENIED, "Not a participant");
            throw ApiException.notFound("Telehealth session");
        }
        return s;
    }

    @Transactional
    public SessionView consent(CurrentUser user, UUID id) {
        TelehealthSession s = participant(user, id, Participant.EMPLOYEE, "TELEHEALTH_CONSENT");
        if (!LIVE.contains(s.getStatus())) {
            throw TreatmentSupport.unprocessable("invalid_transition", "The session is " + s.getStatus());
        }
        if (!consentGiven(s)) {
            Consent c = consents.grant(user, ConsentPurpose.TELEHEALTH_CONSULTATION, "telehealth:" + s.getRef());
            s.setConsentId(c.getId());
            s.setUpdatedAt(support.now());
        }
        audit.record("TELEHEALTH_CONSENT", "TELEHEALTH_SESSION", id.toString(), AuditService.Outcome.SUCCESS, null);
        return view(s, false);
    }

    @Transactional
    public Join join(CurrentUser user, UUID id, Participant role) {
        TelehealthSession s = participant(user, id, role, "TELEHEALTH_JOIN");
        if (!LIVE.contains(s.getStatus())) {
            throw TreatmentSupport.unprocessable("session_closed", "The session is " + s.getStatus());
        }
        if (!consentGiven(s)) {
            throw TreatmentSupport.unprocessable("consent_required", "The employee has not consented to the telehealth consultation yet");
        }
        Instant now = support.now();
        if (!TokenWindow.isOpen(now, s.getScheduledAt(), s.getDurationMinutes())) {
            throw TreatmentSupport.unprocessable("outside_join_window", "Joining opens 15 minutes before the start and closes at the end of the session");
        }
        tokens.findBySessionIdAndUsernameAndUsedAtIsNullAndRevokedFalse(s.getId(), user.username()).forEach(t -> t.setRevoked(true));
        String token = TokenWindow.newToken();
        TelehealthToken t = new TelehealthToken();
        t.setId(UUID.randomUUID());
        t.setSessionId(s.getId());
        t.setParticipant(role.name());
        t.setUsername(user.username());
        t.setTokenHash(TokenWindow.hash(token));
        t.setValidFrom(TokenWindow.opensAt(s.getScheduledAt()));
        t.setValidUntil(TokenWindow.closesAt(s.getScheduledAt(), s.getDurationMinutes()));
        t.setCreatedAt(now);
        tokens.save(t);
        if ("SCHEDULED".equals(s.getStatus()) && role != Participant.EMPLOYEE) {
            s.setStatus("IN_PROGRESS");
            s.setUpdatedAt(now);
        }
        audit.record("TELEHEALTH_JOIN", "TELEHEALTH_SESSION", id.toString(), AuditService.Outcome.SUCCESS, role.name());
        return new Join(provider.joinUrl(s.getRef(), s.getMode(), token), t.getValidUntil());
    }

    /** Telehealth platform callback: redeems a participant token once (single use). */
    @Transactional
    public TokenCheck redeem(String actor, String token) {
        TelehealthToken t = token == null ? null : tokens.findByTokenHash(TokenWindow.hash(token)).orElse(null);
        Instant now = support.now();
        if (t == null || t.isRevoked() || t.getUsedAt() != null || now.isBefore(t.getValidFrom()) || !now.isBefore(t.getValidUntil())) {
            audit.recordAs(actor, "INTEGRATION", "TELEHEALTH_TOKEN_REDEEM", "TELEHEALTH_TOKEN", null, AuditService.Outcome.DENIED, "invalid");
            return new TokenCheck(false, null, null, null);
        }
        t.setUsedAt(now);
        TelehealthSession s = sessions.findById(t.getSessionId()).orElseThrow(() -> ApiException.notFound("Telehealth session"));
        audit.recordAs(actor, "INTEGRATION", "TELEHEALTH_TOKEN_REDEEM", "TELEHEALTH_SESSION", s.getId().toString(), AuditService.Outcome.SUCCESS,
                t.getParticipant());
        return new TokenCheck(true, s.getRef(), t.getParticipant(), t.getUsername());
    }

    @Transactional(readOnly = true)
    public List<MessageView> messages(CurrentUser user, UUID id, Participant role) {
        TelehealthSession s = participant(user, id, role, "TELEHEALTH_MESSAGES_READ");
        audit.record("TELEHEALTH_MESSAGES_READ", "TELEHEALTH_SESSION", id.toString(), AuditService.Outcome.SUCCESS, role.name());
        return messages.findBySessionIdOrderBySentAtAsc(s.getId()).stream().map(this::view).toList();
    }

    @Transactional
    public MessageView post(CurrentUser user, UUID id, Participant role, String text) {
        TelehealthSession s = participant(user, id, role, "TELEHEALTH_MESSAGE_POST");
        if (Set.of("CANCELLED", "NO_SHOW").contains(s.getStatus())) {
            throw TreatmentSupport.unprocessable("session_closed", "The session is " + s.getStatus());
        }
        if (role == Participant.EMPLOYEE && !consentGiven(s)) {
            throw TreatmentSupport.unprocessable("consent_required", "Consent to the telehealth consultation first");
        }
        TelehealthMessage m = new TelehealthMessage();
        m.setId(UUID.randomUUID());
        m.setSessionId(s.getId());
        m.setSender(user.username());
        m.setSenderRole(role.name());
        m.setText(TreatmentSupport.clinical("text", text, 2000));
        m.setSentAt(support.now());
        if (m.getText() == null) {
            throw ApiException.badRequest("empty_message", "Message text required");
        }
        messages.save(m);
        audit.record("TELEHEALTH_MESSAGE_POST", "TELEHEALTH_SESSION", id.toString(), AuditService.Outcome.SUCCESS, role.name());
        return view(m);
    }

    MessageView view(TelehealthMessage m) {
        String name = switch (m.getSenderRole()) {
            case "EMPLOYEE" -> support.employee(m.getSender()).map(Employee::getDisplayName).orElse(m.getSender());
            case "CLINICIAN" -> support.staffName(m.getSender());
            default -> m.getSender();
        };
        return new MessageView(m.getId(), name, m.getSenderRole(), m.getText(), m.getSentAt());
    }

    // ------------------------------------------------------------------ completion

    @Transactional
    public SessionView complete(CurrentUser user, UUID id, String note, LocalDate followUpDate) {
        TelehealthSession s = participant(user, id, Participant.CLINICIAN, "TELEHEALTH_COMPLETE");
        if (!LIVE.contains(s.getStatus())) {
            throw TreatmentSupport.unprocessable("invalid_transition", "The session is " + s.getStatus());
        }
        ClinicalEncounterService.EncounterView enc = encounters.create(user, s.getEmployeeId(),
                new ClinicalEncounterService.CreateRequest("ILLNESS", s.getCaseId(), null));
        encounters.update(user, enc.id(), new ClinicalEncounterService.UpdateRequest(null,
                new ClinicalEncounterService.Soap("Telehealth consultation " + s.getRef() + " (" + s.getMode().toLowerCase(java.util.Locale.ROOT) + ")",
                        null, TreatmentSupport.clinical("note", note, 20000), null), null, null, null, null));
        Instant now = support.now();
        s.setEncounterId(enc.id());
        s.setStatus("COMPLETED");
        s.setCompletedAt(now);
        s.setUpdatedAt(now);
        tokens.findBySessionIdAndUsernameAndUsedAtIsNullAndRevokedFalse(s.getId(), user.username()).forEach(t -> t.setRevoked(true));
        if (followUpDate != null) {
            if (followUpDate.isBefore(support.today())) {
                throw ApiException.badRequest("invalid_due_date", "Follow-up date in the past");
            }
            plans.addFollowUpToActivePlan(s.getCaseId(), followUpDate, "TELEHEALTH", user.username());
        }
        if (s.getCaseId() != null) {
            timeline.record(s.getCaseId(), "TELEHEALTH_COMPLETED", "Telehealth consultation " + s.getRef() + " completed – encounter drafted");
        }
        audit.record("TELEHEALTH_COMPLETE", "TELEHEALTH_SESSION", id.toString(), AuditService.Outcome.SUCCESS, "encounter=" + enc.id());
        return view(s, true);
    }

    @Transactional
    public SessionView cancel(CurrentUser user, UUID id, String reason) {
        TelehealthSession s = participant(user, id, Participant.CLINICIAN, "TELEHEALTH_CANCEL");
        if (!LIVE.contains(s.getStatus())) {
            throw TreatmentSupport.unprocessable("invalid_transition", "The session is " + s.getStatus());
        }
        s.setStatus("CANCELLED");
        s.setCancelReason(TreatmentSupport.clinical("reason", reason, 2000));
        s.setUpdatedAt(support.now());
        if (s.getCaseId() != null) {
            timeline.record(s.getCaseId(), "TELEHEALTH_CANCELLED", "Telehealth consultation " + s.getRef() + " cancelled");
        }
        audit.record("TELEHEALTH_CANCEL", "TELEHEALTH_SESSION", id.toString(), AuditService.Outcome.SUCCESS, null);
        return view(s, true);
    }

    /** E14 callback for the clinic diary booking of a session: NO_SHOW marks the session NO_SHOW. */
    @Transactional
    public void appointmentChanged(String sourceRef, AppointmentStatusListener.Status status) {
        if (status != AppointmentStatusListener.Status.NO_SHOW || sourceRef == null) {
            return;
        }
        try {
            sessions.findById(UUID.fromString(sourceRef)).filter(s -> LIVE.contains(s.getStatus())).ifPresent(s -> {
                s.setStatus("NO_SHOW");
                s.setUpdatedAt(support.now());
            });
        } catch (IllegalArgumentException e) {
            // not one of ours
        }
    }
}
