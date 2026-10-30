package com.wellnessz.e09.casework;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.wellnessz.casekernel.CaseService;
import com.wellnessz.casekernel.model.CaseCategory;
import com.wellnessz.casekernel.model.CaseSource;
import com.wellnessz.casekernel.model.CaseStatus;
import com.wellnessz.casekernel.model.CaseType;
import com.wellnessz.casekernel.model.OhCase;
import com.wellnessz.casekernel.model.OhCaseRepository;
import com.wellnessz.casekernel.model.OpenCaseCommand;
import com.wellnessz.common.error.ApiException;
import com.wellnessz.common.model.Roles;
import com.wellnessz.e09.casework.SupportViews.ContactView;
import com.wellnessz.e09.casework.SupportViews.EapSummary;
import com.wellnessz.e09.casework.SupportViews.NoteView;
import com.wellnessz.e09.casework.SupportViews.ReliefSummary;
import com.wellnessz.e09.casework.SupportViews.ScreenView;
import com.wellnessz.e09.casework.SupportViews.SupportCaseDetail;
import com.wellnessz.e09.casework.SupportViews.SupportCaseView;
import com.wellnessz.e09.casework.SupportViews.TimelineEntry;
import com.wellnessz.e09.casework.SupportViews.UrgentView;
import com.wellnessz.e09.model.E09EapReferralRepository;
import com.wellnessz.e09.model.E09ReliefRepository;
import com.wellnessz.e09.model.E09ScreenResultRepository;
import com.wellnessz.e09.model.E09SupportCaseRepository;
import com.wellnessz.e09.model.E09SupportContactRepository;
import com.wellnessz.e09.model.E09SupportEventRepository;
import com.wellnessz.e09.model.E09SupportNoteRepository;
import com.wellnessz.e09.model.E09UrgentItemRepository;
import com.wellnessz.e09.model.EapReferral;
import com.wellnessz.e09.model.MentalHealthEnums.Concern;
import com.wellnessz.e09.model.MentalHealthEnums.Risk;
import com.wellnessz.e09.model.MentalHealthEnums.Sharing;
import com.wellnessz.e09.model.MentalHealthEnums.Source;
import com.wellnessz.e09.model.MentalHealthEnums.SupportStatus;
import com.wellnessz.e09.model.Relief;
import com.wellnessz.e09.model.SupportCase;
import com.wellnessz.e09.model.SupportContact;
import com.wellnessz.e09.model.SupportEvent;
import com.wellnessz.e09.model.SupportNote;
import com.wellnessz.e09.model.UrgentItem;
import com.wellnessz.e09.support.MhSupport;
import com.wellnessz.e09.support.SupportAccess;
import com.wellnessz.masterdata.Employee;
import com.wellnessz.security.audit.AuditService;
import com.wellnessz.security.disclosure.DisclosureLogService;
import com.wellnessz.security.identity.CurrentUser;

/**
 * Confidential psychological-health case type (E09-F01): restricted support cases backed by restricted kernel cases (category
 * PSYCHOLOGICAL), named-clinician access with break-glass, the general-OH placeholder (count only), the employee's sharing
 * choice and the manager's view of what was shared (disclosure-logged).
 */
@Service
public class SupportCaseService {

    public static final String SHARING_RESOURCE = "SUPPORT_SHARING";

    private final E09SupportCaseRepository cases;
    private final E09SupportNoteRepository notes;
    private final E09SupportContactRepository contacts;
    private final E09SupportEventRepository events;
    private final E09ScreenResultRepository screens;
    private final E09UrgentItemRepository urgent;
    private final E09EapReferralRepository eap;
    private final E09ReliefRepository reliefs;
    private final OhCaseRepository kernelCases;
    private final CaseService kernel;
    private final SupportAccess access;
    private final MhSupport support;
    private final AuditService audit;
    private final DisclosureLogService disclosures;

    public SupportCaseService(E09SupportCaseRepository cases, E09SupportNoteRepository notes, E09SupportContactRepository contacts,
                              E09SupportEventRepository events, E09ScreenResultRepository screens, E09UrgentItemRepository urgent,
                              E09EapReferralRepository eap, E09ReliefRepository reliefs, OhCaseRepository kernelCases, CaseService kernel,
                              SupportAccess access, MhSupport support, AuditService audit, DisclosureLogService disclosures) {
        this.cases = cases;
        this.notes = notes;
        this.contacts = contacts;
        this.events = events;
        this.screens = screens;
        this.urgent = urgent;
        this.eap = eap;
        this.reliefs = reliefs;
        this.kernelCases = kernelCases;
        this.kernel = kernel;
        this.access = access;
        this.support = support;
        this.audit = audit;
        this.disclosures = disclosures;
    }

    // ------------------------------------------------------------------ opening (shared by every entry point)

    /** What an entry point knows when it opens a support case. */
    public record OpenSupport(String employeeId, Source source, Concern concern, Risk risk, String actor, String sourceType, String sourceRef) {
    }

    /** Opens a restricted kernel case (PSYCHOLOGICAL / MENTAL_HEALTH) and the support case on top of it. */
    @Transactional
    public SupportCase open(OpenSupport cmd) {
        Employee e = support.requireEmployee(cmd.employeeId());
        OhCase k = kernel.open(OpenCaseCommand.builder(e.getEmployeeId(), CaseCategory.PSYCHOLOGICAL, kernelSource(cmd))
                .caseType(CaseType.MENTAL_HEALTH).employeeName(e.getDisplayName()).build());
        Instant now = support.now();
        SupportCase c = SupportCase.create();
        c.setCaseRef(support.nextRef("SUP"));
        c.setKernelCaseId(k.getId());
        c.setEmployeeId(e.getEmployeeId());
        c.setStatus(SupportStatus.OPEN.name());
        c.setRisk((cmd.risk() == null ? Risk.LOW : cmd.risk()).name());
        c.setConcern((cmd.concern() == null ? Concern.OTHER : cmd.concern()).name());
        c.setSource(cmd.source().name());
        c.setSourceType(cmd.sourceType());
        c.setSourceRef(cmd.sourceRef());
        c.setOpenedAt(now);
        c.setOpenedBy(cmd.actor());
        c.setSharing(Sharing.NOTHING.name());
        c.setUpdatedAt(now);
        cases.save(c);
        event(c, "OPENED", "Support case opened (" + cmd.source() + ")", cmd.actor());
        audit.record("SUPPORT_CASE_OPENED", SupportAccess.RESOURCE, c.getId().toString(), AuditService.Outcome.SUCCESS, cmd.source().name());
        return c;
    }

    private static CaseSource kernelSource(OpenSupport cmd) {
        return switch (cmd.source()) {
            case SELF, PEER -> CaseSource.SELF_REFERRAL;
            case CLINICIAN -> CaseSource.WALK_IN;
            case CISM -> CaseSource.SYSTEM_TRIGGER;
            case PROGRAMME_REFERRAL -> "DA_TEST".equals(cmd.sourceType()) ? CaseSource.DA_TEST : CaseSource.SYSTEM_TRIGGER;
        };
    }

    /** The employee's most recent case that is not closed. */
    @Transactional(readOnly = true)
    public Optional<SupportCase> openCaseOf(String employeeId) {
        return cases.findByEmployeeIdOrderByOpenedAtDesc(employeeId).stream()
                .filter(c -> !SupportStatus.CLOSED.name().equals(c.getStatus())).findFirst();
    }

    public void event(SupportCase c, String type, String summary, String actor) {
        SupportEvent e = SupportEvent.create();
        e.setSupportCaseId(c.getId());
        e.setEventType(type);
        e.setSummary(summary.length() > 300 ? summary.substring(0, 300) : summary);
        e.setActor(actor == null ? "system" : actor);
        e.setOccurredAt(support.now());
        events.save(e);
    }

    public void contact(SupportCase c, String type, String outcome, String note, String actor) {
        SupportContact x = SupportContact.create();
        x.setSupportCaseId(c.getId());
        x.setContactType(type);
        x.setOutcome(outcome);
        x.setNote(note);
        x.setActor(actor);
        x.setOccurredAt(support.now());
        contacts.save(x);
        c.setLastContactAt(x.getOccurredAt());
        c.setUpdatedAt(x.getOccurredAt());
    }

    public SupportCase load(UUID id) {
        return cases.findById(id).orElseThrow(() -> ApiException.notFound("Support case"));
    }

    public Optional<OhCase> kernelCase(SupportCase c) {
        return kernelCases.findById(c.getKernelCaseId());
    }

    /** Named clinician (or break-glass for reads) on an existing case, else audited 404. */
    public SupportCase require(CurrentUser user, UUID id, String auditAction, boolean write) {
        SupportCase c = cases.findById(id).orElse(null);
        if (c == null) {
            throw access.deny(auditAction, id.toString(), "Unknown support case");
        }
        boolean allowed = write ? access.isNamedClinician(user) : access.canRead(user, c);
        if (!allowed) {
            throw access.deny(auditAction, id.toString(), "Restricted class");
        }
        return c;
    }

    // ------------------------------------------------------------------ clinician reads & changes

    @Transactional(readOnly = true)
    public List<SupportCaseView> list(CurrentUser user, String status, String risk) {
        access.requireNamed(user, "SUPPORT_CASE_LIST", null);
        List<SupportCaseView> out = cases.findAllByOrderByOpenedAtDesc().stream()
                .filter(c -> status == null || status.equals(c.getStatus()))
                .filter(c -> risk == null || risk.equals(c.getRisk()))
                .map(this::view).toList();
        audit.record("SUPPORT_CASE_LIST", SupportAccess.RESOURCE, null, AuditService.Outcome.SUCCESS, "returned=" + out.size());
        return out;
    }

    @Transactional(readOnly = true)
    public SupportCaseDetail get(CurrentUser user, UUID id) {
        SupportCase c = require(user, id, "SUPPORT_CASE_READ", false);
        String basis = access.isNamedClinician(user) ? "NAMED_CLINICIAN" : "BREAK_GLASS";
        audit.record("SUPPORT_CASE_READ", SupportAccess.RESOURCE, id.toString(), AuditService.Outcome.SUCCESS, basis);
        return detail(c, basis);
    }

    public record CreateCommand(String employeeId, Concern concern, Risk risk, String note, Source source) {
    }

    @Transactional
    public SupportCaseDetail create(CurrentUser user, CreateCommand cmd) {
        access.requireNamed(user, "SUPPORT_CASE_CREATE", cmd.employeeId());
        support.employee(cmd.employeeId()).orElseThrow(() -> ApiException.notFound("Employee"));
        if (openCaseOf(cmd.employeeId()).isPresent()) {
            throw ApiException.conflict("open_case_exists", "The employee already has an open support case");
        }
        Source source = cmd.source() == null ? Source.CLINICIAN : cmd.source();
        if (source != Source.CLINICIAN && source != Source.PEER) {
            throw MhSupport.unprocessable("invalid_source", "Clinician-opened cases have source CLINICIAN or PEER");
        }
        SupportCase c = open(new OpenSupport(cmd.employeeId(), source, cmd.concern(), cmd.risk(), user.username(), null, null));
        String note = MhSupport.trimmed("note", cmd.note(), 8000);
        if (note != null) {
            saveNote(c, note, user.username());
        }
        if (cmd.risk() == Risk.URGENT) {
            c.setStatus(SupportStatus.ACTIVE.name());
        }
        return detail(c, "NAMED_CLINICIAN");
    }

    @Transactional
    public NoteView addNote(CurrentUser user, UUID id, String text) {
        SupportCase c = require(user, id, "SUPPORT_NOTE_ADD", true);
        if (SupportStatus.CLOSED.name().equals(c.getStatus())) {
            throw ApiException.conflict("case_closed", "The support case is closed");
        }
        SupportNote n = saveNote(c, MhSupport.trimmed("text", text, 8000), user.username());
        if (SupportStatus.OPEN.name().equals(c.getStatus()) || SupportStatus.SCREENED.name().equals(c.getStatus())) {
            c.setStatus(SupportStatus.ACTIVE.name());
        }
        c.setUpdatedAt(support.now());
        audit.record("SUPPORT_NOTE_ADD", SupportAccess.RESOURCE, id.toString(), AuditService.Outcome.SUCCESS, null);
        return new NoteView(n.getId(), n.getText(), n.getAuthor(), n.getCreatedAt());
    }

    private SupportNote saveNote(SupportCase c, String text, String author) {
        if (text == null) {
            throw ApiException.badRequest("text_required", "Note text is required");
        }
        SupportNote n = SupportNote.create();
        n.setSupportCaseId(c.getId());
        n.setText(text);
        n.setAuthor(author);
        n.setCreatedAt(support.now());
        notes.save(n);
        event(c, "NOTE_ADDED", "Clinical note added", author);
        return n;
    }

    @Transactional
    public SupportCaseDetail close(CurrentUser user, UUID id, String reason) {
        SupportCase c = require(user, id, "SUPPORT_CASE_CLOSE", true);
        if (SupportStatus.CLOSED.name().equals(c.getStatus())) {
            throw ApiException.conflict("case_closed", "The support case is already closed");
        }
        if (!urgent.findBySupportCaseIdAndStatus(c.getId(), "OPEN").isEmpty()) {
            throw ApiException.conflict("urgent_open", "Resolve the open urgent item before closing the case");
        }
        if (reliefs.findByEmployeeIdAndStatus(c.getEmployeeId(), "ACTIVE").filter(r -> r.getSupportCaseId().equals(c.getId())).isPresent()) {
            throw ApiException.conflict("relief_active", "End the relief from duties before closing the case");
        }
        Instant now = support.now();
        c.setStatus(SupportStatus.CLOSED.name());
        c.setClosedAt(now);
        c.setCloseReason(MhSupport.trimmed("reason", reason, 2000));
        c.setUpdatedAt(now);
        kernelCase(c).filter(k -> k.getStatus().canTransitionTo(CaseStatus.CLOSED)).ifPresent(k -> k.transition(CaseStatus.CLOSED, now));
        event(c, "CLOSED", "Support case closed", user.username());
        audit.record("SUPPORT_CASE_CLOSE", SupportAccess.RESOURCE, id.toString(), AuditService.Outcome.SUCCESS, null);
        return detail(c, "NAMED_CLINICIAN");
    }

    // ------------------------------------------------------------------ general-OH placeholder

    @Transactional(readOnly = true)
    public SupportViews.Placeholder placeholder(CurrentUser user, String employeeId) {
        long n = cases.countByEmployeeIdAndStatusNot(employeeId, SupportStatus.CLOSED.name());
        audit.record("SUPPORT_PLACEHOLDER_READ", "EMPLOYEE", employeeId, AuditService.Outcome.SUCCESS, "restrictedCases=" + n);
        return new SupportViews.Placeholder(n);
    }

    // ------------------------------------------------------------------ the employee's sharing choice (E09-BL-002)

    @Transactional(readOnly = true)
    public SupportViews.SharingView mySharing(CurrentUser user) {
        String emp = requireEmployeeId(user);
        List<SupportCase> mine = cases.findByEmployeeIdOrderByOpenedAtDesc(emp);
        Optional<SupportCase> c = mine.stream().filter(x -> !SupportStatus.CLOSED.name().equals(x.getStatus())).findFirst()
                .or(() -> mine.stream().findFirst());
        return c.map(x -> new SupportViews.SharingView(x.getId(), x.getCaseRef(), x.getSharing(), x.getSharingAdjustments(), x.getSharingChangedAt()))
                .orElseGet(() -> new SupportViews.SharingView(null, null, Sharing.NOTHING.name(), null, null));
    }

    @Transactional
    public SupportViews.SharingView setSharing(CurrentUser user, UUID caseId, Sharing choice, String adjustments) {
        String emp = requireEmployeeId(user);
        SupportCase c = cases.findById(caseId).orElse(null);
        if (c == null || !emp.equals(c.getEmployeeId())) {
            audit.record("SUPPORT_SHARING_CHANGE", SupportAccess.RESOURCE, caseId.toString(), AuditService.Outcome.DENIED, "Not the data subject");
            throw ApiException.notFound("Support case");
        }
        String adj = choice == Sharing.FITNESS_AND_ADJUSTMENTS ? MhSupport.screened("adjustments", adjustments, 1000) : null;
        if (choice == Sharing.FITNESS_AND_ADJUSTMENTS && adj == null) {
            throw MhSupport.unprocessable("adjustments_required", "Describe the adjustments you want to share");
        }
        String before = c.getSharing();
        Instant now = support.now();
        c.setSharing(choice.name());
        c.setSharingAdjustments(adj);
        c.setSharingChangedAt(now);
        c.setUpdatedAt(now);
        if (!before.equals(choice.name()) || choice == Sharing.FITNESS_AND_ADJUSTMENTS) {
            // a narrower (or changed) choice ends what the manager received before
            disclosures.revokeResource(SHARING_RESOURCE, c.getId().toString(), "Sharing changed by the employee to " + choice);
        }
        event(c, "SHARING_CHANGED", "Sharing with the manager: " + before + " → " + choice, user.username());
        audit.record("SUPPORT_SHARING_CHANGE", SupportAccess.RESOURCE, c.getId().toString(), AuditService.Outcome.SUCCESS, before + "->" + choice);
        return new SupportViews.SharingView(c.getId(), c.getCaseRef(), c.getSharing(), c.getSharingAdjustments(), c.getSharingChangedAt());
    }

    /** Manager view: only what employees in scope chose to share (fitness status, adjustments) – disclosure-logged. */
    @Transactional
    public List<SupportViews.SharedOutcome> sharedOutcomes(CurrentUser user) {
        List<String> inScope = new ArrayList<>(support.employeesInScope(user.orgScope()));
        inScope.remove(user.employeeId());
        if (inScope.isEmpty()) {
            return List.of();
        }
        Set<String> seen = new HashSet<>();
        List<SupportViews.SharedOutcome> out = new ArrayList<>();
        for (SupportCase c : cases.findSharedFor(inScope)) {
            if (SupportStatus.CLOSED.name().equals(c.getStatus()) || !seen.add(c.getEmployeeId())) {
                continue;
            }
            String fitness = kernelCase(c).map(k -> k.getFitnessStatus().name()).orElse("NOT_ASSESSED");
            String adjustments = Sharing.FITNESS_AND_ADJUSTMENTS.name().equals(c.getSharing()) ? c.getSharingAdjustments() : null;
            String title = "Shared outcome: " + fitness + (adjustments == null ? "" : " + adjustments");
            if (!alreadyDisclosed(c, user.username(), title)) {
                disclosures.record(c.getEmployeeId(), "MANAGER", user.username(), SHARING_RESOURCE, c.getId().toString(), title,
                        "Employee-chosen sharing (" + c.getSharing() + ")", null, user.username());
            }
            out.add(new SupportViews.SharedOutcome(c.getEmployeeId(), support.name(c.getEmployeeId()), fitness, adjustments, c.getSharingChangedAt()));
        }
        audit.record("SUPPORT_SHARED_OUTCOMES_READ", SupportAccess.RESOURCE, null, AuditService.Outcome.SUCCESS, "returned=" + out.size());
        return out;
    }

    private boolean alreadyDisclosed(SupportCase c, String recipient, String title) {
        return disclosures.forEmployee(c.getEmployeeId(), false).stream()
                .anyMatch(d -> SHARING_RESOURCE.equals(d.resourceType()) && c.getId().toString().equals(d.resourceId())
                        && recipient.equals(d.recipientName()) && title.equals(d.documentTitle()) && "ACTIVE".equals(d.status()));
    }

    static String requireEmployeeId(CurrentUser user) {
        if (user.employeeId() == null || !user.hasRole(Roles.EMPLOYEE)) {
            throw ApiException.forbidden("Available to employees only");
        }
        return user.employeeId();
    }

    // ------------------------------------------------------------------ projections

    public SupportCaseView view(SupportCase c) {
        boolean urgentOpen = !urgent.findBySupportCaseIdAndStatus(c.getId(), "OPEN").isEmpty();
        return new SupportCaseView(c.getId(), c.getCaseRef(), c.getEmployeeId(), support.name(c.getEmployeeId()), c.getStatus(), c.getRisk(),
                c.getConcern(), c.getSource(), c.getOpenedAt(), c.getLastContactAt(), c.getSharing(), latestEap(c).orElse(null),
                latestRelief(c).orElse(null), urgentOpen ? Boolean.TRUE : null);
    }

    public SupportCaseDetail detail(SupportCase c, String basis) {
        List<EapSummary> referrals = eap.findBySupportCaseIdOrderBySentAtDesc(c.getId()).stream().map(this::eapSummary).toList();
        List<NoteView> n = notes.findBySupportCaseIdOrderByCreatedAtAsc(c.getId()).stream()
                .map(x -> new NoteView(x.getId(), x.getText(), x.getAuthor(), x.getCreatedAt())).toList();
        List<ScreenView> s = screens.findBySupportCaseIdOrderByCreatedAtAsc(c.getId()).stream()
                .map(x -> new ScreenView(x.getId(), x.getInstrument(), x.getScore(), x.getBand(), x.isUrgent(), x.getAdministeredBy(), x.getCreatedAt()))
                .toList();
        List<ContactView> ct = contacts.findBySupportCaseIdOrderByOccurredAtAsc(c.getId()).stream()
                .map(x -> new ContactView(x.getId(), x.getContactType(), x.getOutcome(), x.getNote(), x.getActor(), x.getOccurredAt())).toList();
        List<UrgentView> u = urgent.findBySupportCaseIdAndStatus(c.getId(), "OPEN").stream().map(this::urgentView).toList();
        List<TimelineEntry> t = events.findBySupportCaseIdOrderByOccurredAtAsc(c.getId()).stream()
                .map(x -> new TimelineEntry(x.getEventType(), x.getSummary(), x.getActor(), x.getOccurredAt())).toList();
        String kernelRef = kernelCase(c).map(OhCase::getCaseRef).orElse(null);
        return new SupportCaseDetail(c.getId(), c.getCaseRef(), kernelRef, c.getEmployeeId(), support.name(c.getEmployeeId()), c.getStatus(),
                c.getRisk(), c.getConcern(), c.getSource(), c.getSourceType(), c.getSourceRef(), c.getOpenedAt(), c.getOpenedBy(),
                c.getLastContactAt(), c.getSharing(), c.getSharingAdjustments(), c.getSharingChangedAt(), c.getPreferredContact(),
                c.getContactPhone(), c.getBestTime(), c.getMessage(), c.getNextContactBy(), c.getClosedAt(), c.getCloseReason(),
                referrals.isEmpty() ? null : referrals.get(0), referrals, latestRelief(c).orElse(null), n, s, ct, u, t, basis);
    }

    public UrgentView urgentView(UrgentItem i) {
        SupportCase c = cases.findById(i.getSupportCaseId()).orElse(null);
        return new UrgentView(i.getId(), i.getSupportCaseId(), c == null ? null : c.getCaseRef(), c == null ? null : c.getEmployeeId(),
                c == null ? null : support.name(c.getEmployeeId()), c == null ? null : c.getRisk(), i.getTriggerCode(), i.getCallBackBy(),
                "OPEN".equals(i.getStatus()) && support.now().isAfter(i.getCallBackBy()), i.getStatus(), i.getOutcome(), i.getCreatedAt());
    }

    private Optional<EapSummary> latestEap(SupportCase c) {
        return eap.findBySupportCaseIdOrderBySentAtDesc(c.getId()).stream().findFirst().map(this::eapSummary);
    }

    public EapSummary eapSummary(EapReferral r) {
        return new EapSummary(r.getId(), r.getRef(), r.getPartnerCode(), support.partnerName(r.getPartnerCode()), r.getStatus(), r.getSentAt(),
                r.getFirstAppointmentDueBy(), r.getSessionsAuthorised(), r.getUrgency(), r.getClosureReason());
    }

    private Optional<ReliefSummary> latestRelief(SupportCase c) {
        return reliefs.findBySupportCaseIdOrderByCreatedAtDesc(c.getId()).stream().findFirst()
                .map(r -> new ReliefSummary(r.getId(), r.getFromDate(), r.getExpectedUntil(), r.getStatus(), r.getDecision()));
    }

    /** Count of open support cases per employee (dashboards); never names or content. */
    @Transactional(readOnly = true)
    public Map<String, Long> openedSinceByEmployee(Instant since) {
        Map<String, Long> out = new LinkedHashMap<>();
        cases.employeesOpenedSince(since).forEach(e -> out.merge(e, 1L, Long::sum));
        return out;
    }

    static final Comparator<UrgentView> BY_CALL_BACK = Comparator.comparing(UrgentView::callBackBy);

    /** For reliefs opened outside the support pathway (AME): the employee's open case, or a new clinician case. */
    @Transactional
    public SupportCase openOrCreate(String employeeId, Source source, Concern concern, String actor) {
        return openCaseOf(employeeId).orElseGet(() -> open(new OpenSupport(employeeId, source, concern, Risk.LOW, actor, null, null)));
    }

    /** Relief of a case (helper for the relief service). */
    public List<Relief> reliefsOf(SupportCase c) {
        return reliefs.findBySupportCaseIdOrderByCreatedAtDesc(c.getId());
    }
}
