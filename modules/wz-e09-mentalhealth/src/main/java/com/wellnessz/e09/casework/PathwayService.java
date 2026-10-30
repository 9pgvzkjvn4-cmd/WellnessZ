package com.wellnessz.e09.casework;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.wellnessz.casekernel.model.CaseTask;
import com.wellnessz.casekernel.model.CaseTaskRepository;
import com.wellnessz.casekernel.model.Urgency;
import com.wellnessz.common.error.ApiException;
import com.wellnessz.common.model.Roles;
import com.wellnessz.e09.casework.SupportCaseService.OpenSupport;
import com.wellnessz.e09.casework.SupportViews.Emergency;
import com.wellnessz.e09.casework.SupportViews.Governance;
import com.wellnessz.e09.casework.SupportViews.InstrumentInfo;
import com.wellnessz.e09.casework.SupportViews.Option;
import com.wellnessz.e09.casework.SupportViews.Protocol;
import com.wellnessz.e09.casework.SupportViews.Question;
import com.wellnessz.e09.casework.SupportViews.ScreenDefinition;
import com.wellnessz.e09.casework.SupportViews.ScreenOutcome;
import com.wellnessz.e09.casework.SupportViews.Signpost;
import com.wellnessz.e09.casework.SupportViews.Signposting;
import com.wellnessz.e09.casework.SupportViews.UrgentView;
import com.wellnessz.e09.model.E09ScreenResultRepository;
import com.wellnessz.e09.model.E09UrgentItemRepository;
import com.wellnessz.e09.model.MentalHealthEnums.Concern;
import com.wellnessz.e09.model.MentalHealthEnums.Instrument;
import com.wellnessz.e09.model.MentalHealthEnums.PreferredContact;
import com.wellnessz.e09.model.MentalHealthEnums.Risk;
import com.wellnessz.e09.model.MentalHealthEnums.Source;
import com.wellnessz.e09.model.MentalHealthEnums.SupportStatus;
import com.wellnessz.e09.model.MentalHealthEnums.UrgentOutcome;
import com.wellnessz.e09.model.ScreenResult;
import com.wellnessz.e09.model.SupportCase;
import com.wellnessz.e09.model.UrgentItem;
import com.wellnessz.e09.rules.Calendar;
import com.wellnessz.e09.rules.Screening;
import com.wellnessz.e09.support.MhSupport;
import com.wellnessz.e09.support.SupportAccess;
import com.wellnessz.masterdata.Employee;
import com.wellnessz.notification.Priority;
import com.wellnessz.security.audit.AuditService;
import com.wellnessz.security.identity.CurrentUser;

import tools.jackson.core.type.TypeReference;

/**
 * Self-referral, signposting, validated screening and urgent-risk escalation (E09-F02): screens are scored under clinical
 * governance; PHQ-9 item 9 &gt; 0 or a SEVERE band raises an URGENT item – a same-day kernel CALL_BACK task and a content-free
 * {@code support.urgent-callback} to the named clinicians – handled with the urgent-risk protocol.
 */
@Service
public class PathwayService {

    private static final TypeReference<List<String>> STRINGS = new TypeReference<>() {
    };

    private final SupportCaseService cases;
    private final E09ScreenResultRepository screens;
    private final E09UrgentItemRepository urgent;
    private final CaseTaskRepository tasks;
    private final SupportAccess access;
    private final MhSupport support;
    private final AuditService audit;
    private final JdbcTemplate jdbc;

    public PathwayService(SupportCaseService cases, E09ScreenResultRepository screens, E09UrgentItemRepository urgent, CaseTaskRepository tasks,
                          SupportAccess access, MhSupport support, AuditService audit, JdbcTemplate jdbc) {
        this.cases = cases;
        this.screens = screens;
        this.urgent = urgent;
        this.tasks = tasks;
        this.access = access;
        this.support = support;
        this.audit = audit;
        this.jdbc = jdbc;
    }

    // ------------------------------------------------------------------ signposting

    @Transactional(readOnly = true)
    public Signposting signposting() {
        List<Signpost> items = jdbc.query("""
                select code, title, summary, url, phone, available_24h from mentalhealth.signposting where active = true order by sort_order""",
                (rs, i) -> new Signpost(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getBoolean(6)));
        return new Signposting(items, new Emergency(support.config("mentalhealth.emergency-text",
                "If you are in immediate danger, call the emergency services now."), emergencyPhone()));
    }

    private String emergencyPhone() {
        return support.config("mentalhealth.emergency-phone", "999");
    }

    // ------------------------------------------------------------------ self-referral

    public record SelfReferral(PreferredContact preferredContact, String phone, String bestTime, Concern concern, String message) {
    }

    @Transactional
    public SupportViews.SelfReferralResult selfReferral(CurrentUser user, SelfReferral cmd) {
        String emp = SupportCaseService.requireEmployeeId(user);
        Employee e = support.requireEmployee(emp);
        String phone = MhSupport.trimmed("phone", cmd.phone(), 40);
        if (phone == null && cmd.preferredContact() == PreferredContact.PHONE) {
            phone = e.getPhone();
            if (phone == null) {
                throw MhSupport.unprocessable("phone_required", "A phone number is needed for a call back");
            }
        }
        SupportCase c = cases.openCaseOf(emp).orElseGet(() -> cases.open(new OpenSupport(emp, Source.SELF, cmd.concern(), Risk.LOW,
                user.username(), null, null)));
        c.setPreferredContact(cmd.preferredContact().name());
        if (phone != null) {
            c.setContactPhone(phone);
        }
        c.setBestTime(MhSupport.trimmed("bestTime", cmd.bestTime(), 100));
        String message = MhSupport.trimmed("message", cmd.message(), 2000);
        if (message != null) {
            c.setMessage(message);
        }
        LocalDate due = Calendar.plusWorkingDays(support.today(), support.intConfig("mentalhealth.first-contact-working-days", 2));
        Instant nextContactBy = due.atTime(17, 0).toInstant(ZoneOffset.UTC);
        if (c.getNextContactBy() == null || c.getNextContactBy().isAfter(nextContactBy)) {
            c.setNextContactBy(nextContactBy);
        }
        cases.contact(c, "SELF_REFERRAL", null, null, user.username());
        cases.event(c, "SELF_REFERRAL", "Self-referral received (contact by " + due + ")", user.username());
        support.notifyEmployee(emp, "support.request-received", Map.of("dueDate", due.toString()), "support");
        audit.record("SUPPORT_SELF_REFERRAL", SupportAccess.RESOURCE, c.getId().toString(), AuditService.Outcome.SUCCESS, cmd.concern().name());
        return new SupportViews.SelfReferralResult(c.getCaseRef(), c.getNextContactBy());
    }

    // ------------------------------------------------------------------ screens

    @Transactional(readOnly = true)
    public ScreenDefinition definition(Instrument instrument) {
        String title = jdbc.queryForObject("select title from mentalhealth.instrument where code = ?", String.class, instrument.name());
        List<Question> q = jdbc.query("select code, text from mentalhealth.instrument_item where instrument = ? order by position",
                (rs, i) -> new Question(rs.getString(1), rs.getString(2)), instrument.name());
        List<Option> o = jdbc.query("select value, label from mentalhealth.instrument_option where instrument = ? order by value",
                (rs, i) -> new Option(rs.getInt(1), rs.getString(2)), instrument.name());
        return new ScreenDefinition(instrument.name(), title, q, o);
    }

    /** Employee self-screen: stored with the open case (an urgent screen opens one so the call back can be tracked). */
    @Transactional
    public ScreenOutcome selfScreen(CurrentUser user, Instrument instrument, List<Integer> answers) {
        String emp = SupportCaseService.requireEmployeeId(user);
        support.requireEmployee(emp);
        Screening.Result r = score(instrument, answers);
        Optional<SupportCase> open = cases.openCaseOf(emp);
        SupportCase c = open.orElse(null);
        if (c == null && r.urgent()) {
            c = cases.open(new OpenSupport(emp, Source.SELF, instrument == Instrument.PHQ9 ? Concern.LOW_MOOD : Concern.ANXIETY, r.risk(),
                    user.username(), null, null));
        }
        ScreenResult s = save(emp, c, r, answers, "SELF");
        if (c != null) {
            applyToCase(c, s, r, user.username());
        }
        audit.record("SUPPORT_SCREEN_SELF", SupportAccess.RESOURCE, c == null ? null : c.getId().toString(), AuditService.Outcome.SUCCESS,
                instrument.name());
        return new ScreenOutcome(s.getId(), instrument.name(), r.score(), r.band().name(), r.urgent(), Screening.nextSteps(r, emergencyPhone()),
                c == null ? null : c.getCaseRef());
    }

    /** Clinician-administered screen on a case. */
    @Transactional
    public ScreenOutcome clinicianScreen(CurrentUser user, UUID caseId, Instrument instrument, List<Integer> answers) {
        SupportCase c = cases.require(user, caseId, "SUPPORT_SCREEN_CLINICIAN", true);
        Screening.Result r = score(instrument, answers);
        ScreenResult s = save(c.getEmployeeId(), c, r, answers, user.username());
        applyToCase(c, s, r, user.username());
        audit.record("SUPPORT_SCREEN_CLINICIAN", SupportAccess.RESOURCE, caseId.toString(), AuditService.Outcome.SUCCESS, instrument.name());
        return new ScreenOutcome(s.getId(), instrument.name(), r.score(), r.band().name(), r.urgent(), Screening.nextSteps(r, emergencyPhone()),
                c.getCaseRef());
    }

    private static Screening.Result score(Instrument instrument, List<Integer> answers) {
        try {
            return Screening.score(instrument, answers);
        } catch (IllegalArgumentException e) {
            throw MhSupport.unprocessable("invalid_answers", "Answer every question with a value from 0 to 3 ("
                    + Screening.itemCount(instrument) + " answers)");
        }
    }

    private ScreenResult save(String employeeId, SupportCase c, Screening.Result r, List<Integer> answers, String by) {
        ScreenResult s = ScreenResult.create();
        s.setSupportCaseId(c == null ? null : c.getId());
        s.setEmployeeId(employeeId);
        s.setInstrument(r.instrument().name());
        s.setAnswers(answers.stream().map(String::valueOf).collect(Collectors.joining(",")));
        s.setScore(r.score());
        s.setBand(r.band().name());
        s.setUrgent(r.urgent());
        s.setAdministeredBy(by);
        s.setCreatedAt(support.now());
        return screens.save(s);
    }

    private void applyToCase(SupportCase c, ScreenResult s, Screening.Result r, String actor) {
        Risk current = Risk.valueOf(c.getRisk());
        c.setRisk(current.max(r.risk()).name());
        if (SupportStatus.OPEN.name().equals(c.getStatus())) {
            c.setStatus(SupportStatus.SCREENED.name());
        }
        c.setUpdatedAt(support.now());
        cases.event(c, "SCREEN_COMPLETED", r.instrument() + " screen completed (" + r.band() + (r.urgent() ? ", urgent" : "") + ")", actor);
        if (r.urgent()) {
            raiseUrgent(c, s.getId(), r.instrument() == Instrument.PHQ9 && !"SEVERE".equals(r.band().name()) ? "PHQ9_ITEM9" : "SEVERE_BAND", actor);
        }
    }

    /** Same-day call back: kernel CALL_BACK task, urgent item, content-free alert to the named clinicians. One open item per case. */
    public UrgentItem raiseUrgent(SupportCase c, UUID screenId, String trigger, String actor) {
        List<UrgentItem> open = urgent.findBySupportCaseIdAndStatus(c.getId(), "OPEN");
        if (!open.isEmpty()) {
            return open.get(0);
        }
        Instant now = support.now();
        Instant callBackBy = now.plusSeconds(3600L * support.intConfig("mentalhealth.urgent-callback-hours", 4));
        List<String> named = support.namedClinicians();
        CaseTask task = tasks.save(new CaseTask(c.getKernelCaseId(), CaseTask.Type.CALL_BACK, "Same-day confidential call back", callBackBy,
                named.isEmpty() ? null : named.get(0), actor == null ? "system" : actor, now));
        UrgentItem i = UrgentItem.create();
        i.setSupportCaseId(c.getId());
        i.setScreenId(screenId);
        i.setKernelTaskId(task.getId());
        i.setTriggerCode(trigger);
        i.setCallBackBy(callBackBy);
        i.setStatus("OPEN");
        i.setCreatedAt(now);
        urgent.save(i);
        c.setRisk(Risk.URGENT.name());
        cases.kernelCase(c).ifPresent(k -> k.raiseUrgencyTo(Urgency.URGENT, now));
        cases.event(c, "URGENT_RAISED", "Urgent item raised – call back by " + callBackBy, actor);
        support.notifyClinicians("support.urgent-callback", Map.of("reference", c.getCaseRef(), "level", "same day"), "#/support/urgent",
                Priority.URGENT);
        audit.record("SUPPORT_URGENT_RAISED", SupportAccess.RESOURCE, c.getId().toString(), AuditService.Outcome.SUCCESS, trigger);
        return i;
    }

    // ------------------------------------------------------------------ urgent items & protocol

    @Transactional(readOnly = true)
    public List<UrgentView> urgentItems(CurrentUser user) {
        access.requireNamed(user, "SUPPORT_URGENT_LIST", null);
        List<UrgentView> out = urgent.findByStatusOrderByCallBackByAsc("OPEN").stream().map(cases::urgentView).toList();
        audit.record("SUPPORT_URGENT_LIST", SupportAccess.RESOURCE, null, AuditService.Outcome.SUCCESS, "returned=" + out.size());
        return out;
    }

    @Transactional(readOnly = true)
    public Protocol protocol(CurrentUser user) {
        access.requireNamed(user, "SUPPORT_PROTOCOL_READ", null);
        return loadProtocol();
    }

    private Protocol loadProtocol() {
        return jdbc.query("""
                select code, title, version, status, steps, signed_off_by, signed_off_at from mentalhealth.protocol where code = 'URGENT_RISK'""",
                (rs, i) -> new Protocol(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), support.read(rs.getString(5), STRINGS),
                        rs.getString(6), rs.getTimestamp(7) == null ? null : rs.getTimestamp(7).toInstant()))
                .stream().findFirst().orElseThrow(() -> ApiException.notFound("Protocol"));
    }

    @Transactional
    public SupportViews.SupportCaseDetail urgentContact(CurrentUser user, UUID caseId, UrgentOutcome outcome, String note) {
        SupportCase c = cases.require(user, caseId, "SUPPORT_URGENT_CONTACT", true);
        Instant now = support.now();
        cases.contact(c, "URGENT_CALL", outcome.name(), MhSupport.trimmed("note", note, 4000), user.username());
        List<UrgentItem> open = urgent.findBySupportCaseIdAndStatus(c.getId(), "OPEN");
        if (outcome == UrgentOutcome.NOT_REACHED_RETRY) {
            Instant retry = now.plusSeconds(3600);
            open.forEach(i -> {
                i.setCallBackBy(retry);
                tasks.findById(i.getKernelTaskId()).ifPresent(t -> {
                    t.cancel(now);
                    CaseTask next = tasks.save(new CaseTask(c.getKernelCaseId(), CaseTask.Type.CALL_BACK, "Retry confidential call back", retry,
                            user.username(), user.username(), now));
                    i.setKernelTaskId(next.getId());
                });
            });
        } else {
            open.forEach(i -> {
                i.setStatus("RESOLVED");
                i.setOutcome(outcome.name());
                i.setResolvedBy(user.username());
                i.setResolvedAt(now);
                if (i.getKernelTaskId() != null) {
                    tasks.findById(i.getKernelTaskId()).ifPresent(t -> t.complete(now));
                }
            });
            if (outcome == UrgentOutcome.REACHED_SAFE && !SupportStatus.REFERRED.name().equals(c.getStatus())
                    && !SupportStatus.RELIEF.name().equals(c.getStatus())) {
                c.setStatus(SupportStatus.ACTIVE.name());
            }
        }
        cases.event(c, "URGENT_CONTACT", "Urgent call: " + outcome, user.username());
        audit.record("SUPPORT_URGENT_CONTACT", SupportAccess.RESOURCE, caseId.toString(), AuditService.Outcome.SUCCESS, outcome.name());
        return cases.detail(c, "NAMED_CLINICIAN");
    }

    // ------------------------------------------------------------------ governance (E09-BL-014)

    @Transactional(readOnly = true)
    public Governance governance(CurrentUser user) {
        access.requireNamedOr(user, "SUPPORT_GOVERNANCE_READ", null, Roles.CMO);
        List<InstrumentInfo> instruments = jdbc.query("""
                select code, title, version, licence, licence_status, item_count, max_score from mentalhealth.instrument order by code""",
                (rs, i) -> new InstrumentInfo(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getInt(6),
                        rs.getInt(7)));
        Long signposts = jdbc.queryForObject("select count(*) from mentalhealth.signposting where active = true", Long.class);
        return new Governance(instruments, loadProtocol(), support.config("mentalhealth.governance.sign-off", "PENDING"),
                "Instruments, urgent-risk protocol and signposting content pending CMO / mental-health lead sign-off (E09-BL-014)",
                support.namedClinicians().size(), signposts == null ? 0 : signposts);
    }
}
