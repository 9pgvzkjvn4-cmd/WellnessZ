package com.wellnessz.e09.peer;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.wellnessz.common.error.ApiException;
import com.wellnessz.common.events.DomainEvent;
import com.wellnessz.e09.model.E09PeerContactRepository;
import com.wellnessz.e09.model.E09PeerContactRequestRepository;
import com.wellnessz.e09.model.E09PeerTrainingRepository;
import com.wellnessz.e09.model.E09PeerVolunteerRepository;
import com.wellnessz.e09.model.E09SmsReportRepository;
import com.wellnessz.e09.model.E09TrustedReferralRepository;
import com.wellnessz.e09.model.MentalHealthEnums.PeerCategory;
import com.wellnessz.e09.model.MentalHealthEnums.PeerOutcome;
import com.wellnessz.e09.model.PeerContact;
import com.wellnessz.e09.model.PeerContactRequest;
import com.wellnessz.e09.model.PeerTraining;
import com.wellnessz.e09.model.PeerVolunteer;
import com.wellnessz.e09.model.SmsReport;
import com.wellnessz.e09.model.TrustedReferral;
import com.wellnessz.e09.rules.Calendar;
import com.wellnessz.e09.rules.ContactCodes;
import com.wellnessz.e09.rules.Suppression;
import com.wellnessz.e09.support.MhSupport;
import com.wellnessz.e09.support.SupportAccess;
import com.wellnessz.integration.outbox.EventBus;
import com.wellnessz.notification.Priority;
import com.wellnessz.security.audit.AuditService;
import com.wellnessz.security.identity.CurrentUser;

/**
 * Flight-crew peer support programme (E09-F03). The programme is non-punitive and <b>not identifiable to the operator</b>:
 * contact requests and contact logs carry a random pseudonymous contact code, an alias chosen by the requester and an encrypted
 * callback phone (purged after the retention period) – never an employee id or name. The operator / SMS receive aggregated
 * counts only, with small cells suppressed.
 */
@Service
public class PeerSupportService {

    public static final String SMS_DESTINATION = "sms-safety";

    private final E09PeerVolunteerRepository volunteers;
    private final E09PeerTrainingRepository training;
    private final E09PeerContactRequestRepository requests;
    private final E09PeerContactRepository contacts;
    private final E09TrustedReferralRepository referrals;
    private final E09SmsReportRepository reports;
    private final SupportAccess access;
    private final MhSupport support;
    private final AuditService audit;
    private final EventBus bus;

    public PeerSupportService(E09PeerVolunteerRepository volunteers, E09PeerTrainingRepository training, E09PeerContactRequestRepository requests,
                              E09PeerContactRepository contacts, E09TrustedReferralRepository referrals, E09SmsReportRepository reports,
                              SupportAccess access, MhSupport support, AuditService audit, EventBus bus) {
        this.volunteers = volunteers;
        this.training = training;
        this.requests = requests;
        this.contacts = contacts;
        this.referrals = referrals;
        this.reports = reports;
        this.access = access;
        this.support = support;
        this.audit = audit;
        this.bus = bus;
    }

    // ------------------------------------------------------------------ volunteer register (coordinator)

    public record TrainingRecord(String course, LocalDate completedOn, LocalDate expiresOn) {
    }

    public record VolunteerCommand(String username, String displayName, String email, String base, List<String> languages, boolean available,
                                   List<TrainingRecord> trainingRecords) {
    }

    public record VolunteerView(UUID id, String username, String displayName, String email, String base, List<String> languages, boolean available,
                                boolean trained, List<TrainingRecord> trainingRecords, long openRequests, Instant updatedAt) {
    }

    @Transactional(readOnly = true)
    public List<VolunteerView> volunteers() {
        List<PeerVolunteer> all = volunteers.findAllByOrderByDisplayNameAsc();
        Map<UUID, List<PeerTraining>> byVolunteer = training.findByVolunteerIdIn(all.stream().map(PeerVolunteer::getId).toList()).stream()
                .collect(Collectors.groupingBy(PeerTraining::getVolunteerId));
        return all.stream().map(v -> view(v, byVolunteer.getOrDefault(v.getId(), List.of()))).toList();
    }

    @Transactional(readOnly = true)
    public VolunteerView volunteer(UUID id) {
        PeerVolunteer v = volunteers.findById(id).orElseThrow(() -> ApiException.notFound("Volunteer"));
        return view(v, training.findByVolunteerIdOrderByCompletedOnDesc(id));
    }

    @Transactional
    public VolunteerView createVolunteer(CurrentUser user, VolunteerCommand cmd) {
        if (volunteers.findByUsername(cmd.username()).isPresent()) {
            throw ApiException.conflict("volunteer_exists", "A volunteer with this username is already registered");
        }
        PeerVolunteer v = PeerVolunteer.create();
        v.setCreatedAt(support.now());
        apply(v, cmd);
        v.setUsername(cmd.username());
        volunteers.save(v);
        replaceTraining(v, cmd.trainingRecords());
        audit.record("PEER_VOLUNTEER_CREATE", "PEER_VOLUNTEER", v.getId().toString(), AuditService.Outcome.SUCCESS, null);
        return view(v, training.findByVolunteerIdOrderByCompletedOnDesc(v.getId()));
    }

    @Transactional
    public VolunteerView updateVolunteer(CurrentUser user, UUID id, VolunteerCommand cmd) {
        PeerVolunteer v = volunteers.findById(id).orElseThrow(() -> ApiException.notFound("Volunteer"));
        if (!v.getUsername().equals(cmd.username())) {
            throw MhSupport.unprocessable("username_immutable", "The username of a volunteer cannot be changed");
        }
        apply(v, cmd);
        replaceTraining(v, cmd.trainingRecords());
        audit.record("PEER_VOLUNTEER_UPDATE", "PEER_VOLUNTEER", id.toString(), AuditService.Outcome.SUCCESS, null);
        return view(v, training.findByVolunteerIdOrderByCompletedOnDesc(v.getId()));
    }

    private void apply(PeerVolunteer v, VolunteerCommand cmd) {
        v.setDisplayName(cmd.displayName().strip());
        v.setEmail(cmd.email() == null || cmd.email().isBlank() ? null : cmd.email().strip());
        v.setBase(cmd.base().toUpperCase(Locale.ROOT));
        v.setLanguages(cmd.languages() == null ? null : cmd.languages().stream().filter(Objects::nonNull).map(l -> l.strip().toLowerCase(Locale.ROOT))
                .distinct().collect(Collectors.joining(",")));
        v.setAvailable(cmd.available());
        v.setUpdatedAt(support.now());
    }

    private void replaceTraining(PeerVolunteer v, List<TrainingRecord> records) {
        if (records == null) {
            return;
        }
        training.deleteByVolunteerId(v.getId());
        training.flush();
        for (TrainingRecord r : records) {
            if (r.expiresOn() != null && r.expiresOn().isBefore(r.completedOn())) {
                throw MhSupport.unprocessable("invalid_training", "Training expiry lies before completion");
            }
            if (r.completedOn().isAfter(support.today())) {
                throw MhSupport.unprocessable("invalid_training", "Training completion lies in the future");
            }
            PeerTraining t = PeerTraining.create();
            t.setVolunteerId(v.getId());
            t.setCourse(MhSupport.screened("course", r.course(), 200));
            t.setCompletedOn(r.completedOn());
            t.setExpiresOn(r.expiresOn());
            training.save(t);
        }
    }

    private boolean trained(List<PeerTraining> records) {
        LocalDate today = support.today();
        return records.stream().anyMatch(t -> t.getExpiresOn() == null || !t.getExpiresOn().isBefore(today));
    }

    private VolunteerView view(PeerVolunteer v, List<PeerTraining> records) {
        return new VolunteerView(v.getId(), v.getUsername(), v.getDisplayName(), v.getEmail(), v.getBase(), languages(v), v.isAvailable(),
                trained(records), records.stream().map(t -> new TrainingRecord(t.getCourse(), t.getCompletedOn(), t.getExpiresOn())).toList(),
                requests.countByVolunteerUsernameAndStatus(v.getUsername(), "OPEN"), v.getUpdatedAt());
    }

    private static List<String> languages(PeerVolunteer v) {
        return v.getLanguages() == null || v.getLanguages().isBlank() ? List.of() : Arrays.asList(v.getLanguages().split(","));
    }

    // ------------------------------------------------------------------ crew: available peers & pseudonymous contact request

    public record PeerView(String alias, String base, List<String> languages) {
    }

    private List<PeerVolunteer> availableTrained() {
        List<PeerVolunteer> avail = volunteers.findByAvailableTrueOrderByDisplayNameAsc();
        Map<UUID, List<PeerTraining>> byVolunteer = training.findByVolunteerIdIn(avail.stream().map(PeerVolunteer::getId).toList()).stream()
                .collect(Collectors.groupingBy(PeerTraining::getVolunteerId));
        return avail.stream().filter(v -> trained(byVolunteer.getOrDefault(v.getId(), List.of()))).toList();
    }

    /** Available, trained peers – first names only, never surnames or usernames. */
    @Transactional(readOnly = true)
    public List<PeerView> peers(CurrentUser user) {
        return availableTrained().stream().filter(v -> !v.getUsername().equals(user.username()))
                .map(v -> new PeerView(MhSupport.firstName(v.getDisplayName()), v.getBase(), languages(v))).toList();
    }

    public record ContactRequestResult(String contactCode) {
    }

    /**
     * Routes the request to an available trained volunteer (same base and language preferred, then the fewest open requests). The
     * requester's identity is not stored with the request – the audit record carries no code.
     */
    @Transactional
    public ContactRequestResult contactRequest(CurrentUser user, String alias, String callbackPhone, String base, String language) {
        String b = base == null || base.isBlank() ? null : base.strip().toUpperCase(Locale.ROOT);
        String l = language == null || language.isBlank() ? null : language.strip().toLowerCase(Locale.ROOT);
        PeerVolunteer v = availableTrained().stream().filter(x -> !x.getUsername().equals(user.username()))
                .min(Comparator.<PeerVolunteer>comparingInt(x -> b != null && b.equals(x.getBase()) ? 0 : 1)
                        .thenComparingInt(x -> l != null && languages(x).contains(l) ? 0 : 1)
                        .thenComparingLong(x -> requests.countByVolunteerUsernameAndStatus(x.getUsername(), "OPEN"))
                        .thenComparing(PeerVolunteer::getUsername))
                .orElseThrow(() -> MhSupport.unprocessable("no_peer_available", "No peer volunteer is available right now – please use the signposting"));
        String code = newCode();
        PeerContactRequest r = PeerContactRequest.create();
        r.setContactCode(code);
        r.setAlias(MhSupport.trimmed("alias", alias, 60));
        r.setCallbackPhone(MhSupport.trimmed("callbackPhone", callbackPhone, 40));
        r.setBase(b);
        r.setLanguage(l);
        r.setVolunteerUsername(v.getUsername());
        r.setStatus("OPEN");
        r.setRequestedAt(support.now());
        requests.save(r);
        support.notifyAddress(v.getUsername(), v.getEmail() != null ? v.getEmail() : support.staff(v.getUsername()).map(s -> s.getEmail()).orElse(null),
                "support.urgent-callback", Map.of("reference", code, "level", "peer contact request"), "#/peer-support", Priority.NORMAL);
        audit.record("PEER_CONTACT_REQUEST", "PEER_SUPPORT", null, AuditService.Outcome.SUCCESS, null);
        return new ContactRequestResult(code);
    }

    private String newCode() {
        for (int i = 0; i < 10; i++) {
            String code = ContactCodes.generate();
            if (!requests.existsByContactCode(code) && !contacts.existsByContactCode(code)) {
                return code;
            }
        }
        throw new IllegalStateException("could not allocate a contact code");
    }

    // ------------------------------------------------------------------ volunteer: my requests & contact log

    public record RequestView(String contactCode, String alias, String callbackPhone, String base, String language, String status, Instant requestedAt) {
    }

    @Transactional(readOnly = true)
    public List<RequestView> myRequests(CurrentUser user) {
        Instant since = support.now().minusSeconds(86_400L * support.intConfig("mentalhealth.peer-callback-retention-days", 30));
        return requests.findByVolunteerUsernameAndRequestedAtAfterOrderByRequestedAtDesc(user.username(), since).stream()
                .map(r -> new RequestView(r.getContactCode(), r.getAlias(), r.getCallbackPhone(), r.getBase(), r.getLanguage(), r.getStatus(),
                        r.getRequestedAt())).toList();
    }

    public record ContactCommand(String contactCode, LocalDate date, int durationMinutes, PeerCategory category, PeerOutcome outcome) {
    }

    public record ContactView(UUID id, String contactCode, LocalDate date, int durationMinutes, String category, String outcome, String base,
                              Instant referredAt) {
    }

    @Transactional
    public ContactView logContact(CurrentUser user, ContactCommand cmd) {
        if (cmd.date().isAfter(support.today())) {
            throw MhSupport.unprocessable("invalid_date", "The contact date lies in the future");
        }
        PeerVolunteer me = volunteers.findByUsername(user.username())
                .orElseThrow(() -> MhSupport.unprocessable("not_registered", "You are not registered as a peer volunteer"));
        String code;
        String base = me.getBase();
        if (cmd.contactCode() == null || cmd.contactCode().isBlank()) {
            code = newCode(); // walk-up contact without a prior request
        } else {
            PeerContactRequest r = requests.findByContactCode(cmd.contactCode()).filter(x -> x.getVolunteerUsername().equals(user.username()))
                    .orElseThrow(() -> ApiException.notFound("Contact request"));
            code = r.getContactCode();
            if (r.getBase() != null) {
                base = r.getBase();
            }
            r.setStatus("LOGGED");
        }
        PeerContact c = PeerContact.create();
        c.setContactCode(code);
        c.setVolunteerUsername(user.username());
        c.setContactDate(cmd.date());
        c.setDurationMinutes(cmd.durationMinutes());
        c.setCategory(cmd.category().name());
        c.setOutcome(cmd.outcome().name());
        c.setBase(base);
        c.setCreatedAt(support.now());
        contacts.save(c);
        audit.record("PEER_CONTACT_LOG", "PEER_CONTACT", c.getId().toString(), AuditService.Outcome.SUCCESS, null);
        return contactView(c);
    }

    @Transactional(readOnly = true)
    public List<ContactView> myContacts(CurrentUser user) {
        return contacts.findByVolunteerUsernameOrderByContactDateDesc(user.username()).stream().map(PeerSupportService::contactView).toList();
    }

    private static ContactView contactView(PeerContact c) {
        return new ContactView(c.getId(), c.getContactCode(), c.getContactDate(), c.getDurationMinutes(), c.getCategory(), c.getOutcome(),
                c.getBase(), c.getReferredAt());
    }

    /** Refers the pseudonymous contact to the trusted mental-health professional (named clinicians' queue). */
    @Transactional
    public ContactView refer(CurrentUser user, UUID contactId) {
        PeerContact c = contacts.findById(contactId).filter(x -> x.getVolunteerUsername().equals(user.username())).orElse(null);
        if (c == null) {
            audit.record("PEER_CONTACT_REFER", "PEER_CONTACT", contactId.toString(), AuditService.Outcome.DENIED, "Not the volunteer's contact");
            throw ApiException.notFound("Contact");
        }
        if (referrals.existsByPeerContactId(contactId)) {
            throw ApiException.conflict("already_referred", "The contact has already been referred");
        }
        Instant now = support.now();
        TrustedReferral t = TrustedReferral.create();
        t.setPeerContactId(c.getId());
        t.setContactCode(c.getContactCode());
        t.setReferredBy(user.username());
        t.setReferredAt(now);
        t.setStatus("OPEN");
        referrals.save(t);
        c.setReferredAt(now);
        support.notifyClinicians("support.urgent-callback", Map.of("reference", c.getContactCode(), "level", "peer referral"), "#/peer-support");
        audit.record("PEER_CONTACT_REFER", "PEER_CONTACT", contactId.toString(), AuditService.Outcome.SUCCESS, null);
        return contactView(c);
    }

    public record TrustedReferralView(UUID id, String contactCode, String alias, String callbackPhone, String language, Instant referredAt,
                                      String status, String pickedUpBy, Instant pickedUpAt) {
    }

    /** Trusted professional's queue (named clinicians): pseudonymous code, alias and – while retained – the callback phone. */
    @Transactional(readOnly = true)
    public List<TrustedReferralView> trustedReferrals(CurrentUser user) {
        access.requireNamed(user, "PEER_REFERRAL_LIST", null);
        return referrals.findAllByOrderByReferredAtDesc().stream().map(t -> {
            PeerContactRequest r = requests.findByContactCode(t.getContactCode()).orElse(null);
            return new TrustedReferralView(t.getId(), t.getContactCode(), r == null ? null : r.getAlias(), r == null ? null : r.getCallbackPhone(),
                    r == null ? null : r.getLanguage(), t.getReferredAt(), t.getStatus(), t.getPickedUpBy(), t.getPickedUpAt());
        }).toList();
    }

    @Transactional
    public TrustedReferralView pickUp(CurrentUser user, UUID id) {
        access.requireNamed(user, "PEER_REFERRAL_PICK_UP", id.toString());
        TrustedReferral t = referrals.findById(id).orElseThrow(() -> ApiException.notFound("Referral"));
        if (!"OPEN".equals(t.getStatus())) {
            throw ApiException.conflict("already_picked_up", "The referral has already been picked up");
        }
        t.setStatus("PICKED_UP");
        t.setPickedUpBy(user.username());
        t.setPickedUpAt(support.now());
        audit.record("PEER_REFERRAL_PICK_UP", "PEER_CONTACT", id.toString(), AuditService.Outcome.SUCCESS, null);
        return new TrustedReferralView(t.getId(), t.getContactCode(), null, null, null, t.getReferredAt(), t.getStatus(), t.getPickedUpBy(),
                t.getPickedUpAt());
    }

    // ------------------------------------------------------------------ quarterly anonymised SMS report (E09-BL-009)

    public record ReportRow(String base, String category, Long contacts, boolean suppressed) {
    }

    public record ReportTotals(Long contacts, boolean suppressed, Long referredToTrustedProfessional, long activeVolunteers, int minGroupSize) {
    }

    public record QuarterlyReport(String quarter, LocalDate from, LocalDate to, List<ReportRow> rows, ReportTotals totals, String reportRef,
                                  Instant sentAt) {
    }

    @Transactional(readOnly = true)
    public QuarterlyReport quarterly(CurrentUser user, String quarter) {
        Calendar.Quarter q = quarter(quarter);
        int k = support.minGroupSize();
        List<PeerContact> in = contacts.findByContactDateBetween(q.from(), q.to());
        Map<String, Long> cells = new TreeMap<>();
        in.forEach(c -> cells.merge(c.getBase() + "|" + c.getCategory(), 1L, Long::sum));
        List<ReportRow> rows = new ArrayList<>();
        cells.forEach((key, n) -> {
            String[] parts = key.split("\\|", 2);
            rows.add(new ReportRow(parts[0], parts[1], Suppression.publish(n, k), Suppression.suppressed(n, k)));
        });
        long total = in.size();
        long referred = in.stream().filter(c -> c.getReferredAt() != null).count();
        long active = volunteers.findByAvailableTrueOrderByDisplayNameAsc().size();
        SmsReport sent = reports.findByQuarter(q.code()).orElse(null);
        audit.record("PEER_REPORT_READ", "PEER_SUPPORT", q.code(), AuditService.Outcome.SUCCESS, null);
        return new QuarterlyReport(q.code(), q.from(), q.to(), rows, new ReportTotals(Suppression.publish(total, k), Suppression.suppressed(total, k),
                Suppression.publish(referred, k), active, k), sent == null ? null : sent.getReportRef(), sent == null ? null : sent.getSentAt());
    }

    /** One flat, allow-listed event per published cell ({@code sms-safety}: messageId, quarter, reportRef, category, count, base). */
    public record SmsCell(String messageId, String quarter, String reportRef, String category, long count, String base) implements DomainEvent {
        @Override
        public String eventType() {
            return "peer-support.quarterly-report.v1";
        }

        @Override
        public String aggregateType() {
            return "PEER_SUPPORT_REPORT";
        }

        @Override
        public String aggregateId() {
            return reportRef;
        }
    }

    public record SendResult(String quarter, String reportRef, int published, int suppressed, Instant sentAt) {
    }

    @Transactional
    public SendResult send(CurrentUser user, String quarter) {
        Calendar.Quarter q = quarter(quarter);
        if (!q.to().isBefore(support.today())) {
            throw MhSupport.unprocessable("quarter_not_ended", "Only completed quarters are reported to the SMS");
        }
        if (reports.findByQuarter(q.code()).isPresent()) {
            throw ApiException.conflict("already_sent", "The report for this quarter has already been sent");
        }
        QuarterlyReport r = quarterly(user, quarter);
        String ref = support.nextRef("SMS");
        int published = 0;
        int suppressed = 0;
        for (ReportRow row : r.rows()) {
            if (row.suppressed()) {
                suppressed++;
                continue;
            }
            bus.publish(new SmsCell(UUID.randomUUID().toString(), q.code(), ref, row.category(), row.contacts(), row.base()), SMS_DESTINATION);
            published++;
        }
        Instant now = support.now();
        SmsReport s = SmsReport.create();
        s.setQuarter(q.code());
        s.setReportRef(ref);
        s.setCells(published);
        s.setSentBy(user.username());
        s.setSentAt(now);
        reports.save(s);
        audit.record("PEER_REPORT_SEND", "PEER_SUPPORT", ref, AuditService.Outcome.SUCCESS, "cells=" + published + ",suppressed=" + suppressed);
        return new SendResult(q.code(), ref, published, suppressed, now);
    }

    private Calendar.Quarter quarter(String quarter) {
        String code = quarter == null || quarter.isBlank() ? Calendar.quarterOf(support.today().minusMonths(3)) : quarter;
        try {
            return Calendar.quarter(code);
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("invalid_quarter", "quarter must be YYYY-Qn");
        }
    }

    // ------------------------------------------------------------------ retention (job)

    /** Purges callback phone numbers older than the retention period. */
    @Transactional
    public int purgeCallbackPhones() {
        Instant before = support.now().minusSeconds(86_400L * support.intConfig("mentalhealth.peer-callback-retention-days", 30));
        List<PeerContactRequest> due = requests.findPhonesToPurge(before);
        Instant now = support.now();
        due.forEach(r -> {
            r.setCallbackPhone(null);
            r.setPhonePurgedAt(now);
        });
        if (!due.isEmpty()) {
            audit.recordAs("system", "JOB", "PEER_CALLBACK_PURGE", "PEER_SUPPORT", null, AuditService.Outcome.SUCCESS, "purged=" + due.size());
        }
        return due.size();
    }
}
