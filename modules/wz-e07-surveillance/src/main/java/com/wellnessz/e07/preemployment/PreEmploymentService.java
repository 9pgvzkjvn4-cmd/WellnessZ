package com.wellnessz.e07.preemployment;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.wellnessz.casekernel.support.AppointmentBooking;
import com.wellnessz.casekernel.support.AppointmentStatusListener;
import com.wellnessz.common.error.ApiException;
import com.wellnessz.common.model.DataClassification;
import com.wellnessz.common.model.Roles;
import com.wellnessz.documents.DocumentService;
import com.wellnessz.documents.model.DocumentShare;
import com.wellnessz.e07.E07Events;
import com.wellnessz.e07.model.PreEmployment;
import com.wellnessz.e07.model.PreEmploymentRepository;
import com.wellnessz.e07.model.Programme;
import com.wellnessz.e07.programme.Eligibility;
import com.wellnessz.e07.programme.Population;
import com.wellnessz.e07.programme.ProgrammeCatalog;
import com.wellnessz.e07.support.Booker;
import com.wellnessz.e07.support.SurveillanceSupport;
import com.wellnessz.integration.outbox.EventBus;
import com.wellnessz.masterdata.JobCode;
import com.wellnessz.security.audit.AuditService;
import com.wellnessz.security.disclosure.DisclosureLogService;
import com.wellnessz.security.identity.CurrentUser;

import tools.jackson.core.type.TypeReference;

/**
 * Pre-employment / pre-placement medicals (E07-F03): HRIS offer → panel from the job's programmes, candidate portal
 * (questionnaire, consent, documents, booking), OH outcome with the status-only HR feed and clearance document, government
 * medicals, withdrawal with 12-month retention and purge. Candidates are not in the HRIS master.
 */
@Service
public class PreEmploymentService {

    public static final String SOURCE_TYPE = "PRE_EMPLOYMENT";
    static final Set<String> PURPOSES = Set.of("PRE_EMPLOYMENT_ASSESSMENT", "HR_STATUS_FEED");
    static final int MAX_DOCUMENTS = 10;
    private static final TypeReference<List<DocRef>> DOCS = new TypeReference<>() {
    };

    private final PreEmploymentRepository records;
    private final ProgrammeCatalog catalog;
    private final Population population;
    private final Booker booker;
    private final DocumentService documents;
    private final DisclosureLogService disclosures;
    private final SurveillanceSupport support;
    private final EventBus bus;
    private final AuditService audit;

    public PreEmploymentService(PreEmploymentRepository records, ProgrammeCatalog catalog, Population population, Booker booker,
                                DocumentService documents, DisclosureLogService disclosures, SurveillanceSupport support, EventBus bus,
                                AuditService audit) {
        this.records = records;
        this.catalog = catalog;
        this.population = population;
        this.booker = booker;
        this.documents = documents;
        this.disclosures = disclosures;
        this.support = support;
        this.bus = bus;
        this.audit = audit;
    }

    // ------------------------------------------------------------------ API types

    public record OfferCommand(@NotBlank @Pattern(regexp = "[A-Za-z0-9_\\-/]{1,60}") String offerRef,
                               @NotBlank @Pattern(regexp = "[A-Za-z0-9_\\-]{1,64}") String candidateId,
                               @NotBlank @Size(max = 200) String candidateName, @Email @Size(max = 320) String email,
                               @NotBlank @Pattern(regexp = "[A-Za-z0-9_\\-]{1,64}") String jobCode,
                               @Pattern(regexp = "[A-Za-z0-9_\\-]{1,20}") String subsidiaryCode,
                               @Pattern(regexp = "[A-Za-z0-9_\\-]{1,64}") String departmentCode,
                               @Pattern(regexp = "[A-Za-z0-9_\\-]{1,40}") String locationCode, @NotNull LocalDate proposedStart) {
    }

    public record DocRef(UUID id, String title) {
    }

    public record AppointmentRef(UUID id, Instant startsAt, String siteCode) {
    }

    public record GovernmentMedical(String type, String reference, String status, LocalDate expiresOn) {
    }

    public record PreEmploymentView(UUID id, String offerRef, String candidateId, String candidateName, String jobCode, String jobTitle,
                                    LocalDate proposedStart, List<String> panel, String status, String hrClearance,
                                    boolean questionnaireCompleted, boolean consentGiven, AppointmentRef appointment,
                                    GovernmentMedical governmentMedical, List<DocRef> documents, Instant slaDueAt, LocalDate retentionUntil,
                                    String outcome, String conditions, Map<String, String> questionnaire) {
    }

    public record HrRow(UUID id, String offerRef, String candidateName, String jobTitle, LocalDate proposedStart, String hrClearance,
                        Instant updatedAt) {
    }

    public record CandidateView(UUID id, String offerRef, String jobTitle, LocalDate proposedStart, String status,
                                boolean questionnaireCompleted, boolean consentGiven, AppointmentRef appointment, List<DocRef> documents) {
    }

    // ------------------------------------------------------------------ offer intake

    @Transactional
    public PreEmploymentView create(String actor, OfferCommand cmd) {
        Optional<PreEmployment> existing = records.findByOfferRef(cmd.offerRef());
        if (existing.isPresent()) {
            return view(existing.get(), false);
        }
        JobCode job = support.jobCode(cmd.jobCode())
                .orElseThrow(() -> new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "unknown_job", "Unknown job code"));
        Instant now = support.now();
        PreEmployment r = new PreEmployment();
        r.setId(UUID.randomUUID());
        r.setOfferRef(cmd.offerRef());
        r.setCandidateId(cmd.candidateId());
        r.setCandidateName(cmd.candidateName().strip());
        r.setEmail(cmd.email());
        r.setJobCode(job.getCode());
        r.setSubsidiaryCode(cmd.subsidiaryCode());
        r.setDepartmentCode(cmd.departmentCode());
        r.setLocationCode(cmd.locationCode());
        r.setProposedStart(cmd.proposedStart());
        r.setPanel(SurveillanceSupport.joinCsv(panelFor(job.getCode(), cmd.departmentCode(), cmd.subsidiaryCode(), cmd.locationCode())));
        if (r.getPanel() == null) {
            r.setPanel("");
        }
        r.setStatus("INVITED");
        r.setHrClearance("PENDING");
        r.setSlaDueAt(now.plus(Duration.ofDays(support.configInt("surveillance.pre-employment-sla-days", 10))));
        r.setCreatedBy(actor);
        r.setCreatedAt(now);
        r.setUpdatedAt(now);
        records.save(r);
        support.notifyAddress(r.getCandidateId(), r.getEmail(), SurveillanceSupport.firstName(r.getCandidateName()), "pre-employment.invitation",
                Map.of("dueDate", LocalDate.ofInstant(r.getSlaDueAt(), java.time.ZoneOffset.UTC).toString()),
                support.employeePortal() + "/#/pre-employment");
        audit.recordAs(actor, "MODULE", "PRE_EMPLOYMENT_CREATE", "PRE_EMPLOYMENT", r.getId().toString(), AuditService.Outcome.SUCCESS,
                "offer=" + r.getOfferRef() + " panel=" + r.getPanel());
        return view(r, false);
    }

    /** Programmes whose eligibility matches the job (age and person-level facts unknown for a candidate). */
    List<String> panelFor(String jobCode, String departmentCode, String subsidiaryCode, String locationCode) {
        Eligibility.Person person = population.forJob(jobCode, departmentCode, subsidiaryCode, locationCode);
        LocalDate today = support.today();
        List<String> out = new ArrayList<>();
        for (Programme p : catalog.active()) {
            Eligibility.Criteria c = catalog.criteria(p);
            Eligibility.Criteria noAge = new Eligibility.Criteria(c.jobCodes(), c.departmentCodes(), c.subsidiaryCodes(), c.hazardCodes(),
                    c.exposureGroups(), c.licenceTypes(), null);
            if (Eligibility.match(noAge, person, today).isPresent()) {
                out.add(p.getCode());
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ OH views

    @Transactional(readOnly = true)
    public List<PreEmploymentView> list(CurrentUser user, String status) {
        List<PreEmployment> list = status == null ? records.findAllByOrderByCreatedAtDesc(PageRequest.of(0, 1000))
                : records.findByStatusOrderByCreatedAtDesc(status);
        List<PreEmploymentView> out = list.stream().filter(r -> r.getPurgedAt() == null).map(r -> view(r, false)).toList();
        audit.record("PRE_EMPLOYMENT_LIST", "PRE_EMPLOYMENT", null, AuditService.Outcome.SUCCESS, "rows=" + out.size());
        return out;
    }

    @Transactional(readOnly = true)
    public PreEmploymentView get(CurrentUser user, UUID id) {
        PreEmployment r = load(id);
        audit.record("PRE_EMPLOYMENT_READ", "PRE_EMPLOYMENT", id.toString(), AuditService.Outcome.SUCCESS, "with questionnaire");
        return view(r, true);
    }

    private PreEmployment load(UUID id) {
        return records.findById(id).filter(r -> r.getPurgedAt() == null).orElseThrow(() -> ApiException.notFound("Pre-employment record"));
    }

    PreEmploymentView view(PreEmployment r, boolean detail) {
        String jobTitle = support.jobCode(r.getJobCode()).map(JobCode::getTitle).orElse(r.getJobCode());
        GovernmentMedical gov = r.getGovType() == null ? null
                : new GovernmentMedical(r.getGovType(), r.getGovReference(), r.getGovStatus(), r.getGovExpiresOn());
        Map<String, String> answers = null;
        if (detail && r.getQuestionnaire() != null) {
            answers = new LinkedHashMap<>();
            for (Map.Entry<String, Object> e : support.readMap(r.getQuestionnaire()).entrySet()) {
                answers.put(e.getKey(), String.valueOf(e.getValue()));
            }
        }
        return new PreEmploymentView(r.getId(), r.getOfferRef(), r.getCandidateId(), r.getCandidateName(), r.getJobCode(), jobTitle,
                r.getProposedStart(), SurveillanceSupport.csv(r.getPanel()), r.getStatus(), r.getHrClearance(),
                r.getQuestionnaireCompletedAt() != null, r.isConsentGiven(), appointment(r), gov, docs(r), r.getSlaDueAt(),
                r.getRetentionUntil(), detail ? r.getOutcome() : null, detail ? r.getConditions() : null, answers);
    }

    private static AppointmentRef appointment(PreEmployment r) {
        return r.getAppointmentId() == null ? null : new AppointmentRef(r.getAppointmentId(), r.getAppointmentStartsAt(), r.getAppointmentSite());
    }

    List<DocRef> docs(PreEmployment r) {
        return r.getDocuments() == null || r.getDocuments().isBlank() ? List.of() : support.read(r.getDocuments(), DOCS);
    }

    // ------------------------------------------------------------------ OH actions

    @Transactional
    public PreEmploymentView outcome(CurrentUser user, UUID id, String outcome, String conditions) {
        PreEmployment r = load(id);
        if ("WITHDRAWN".equals(r.getStatus())) {
            throw ApiException.conflict("withdrawn", "The offer was withdrawn");
        }
        if ("OUTCOME_RECORDED".equals(r.getStatus())) {
            throw ApiException.conflict("outcome_recorded", "An outcome is already recorded for this offer");
        }
        String cond = SurveillanceSupport.functional("conditions", conditions, 1000);
        String clearance = "UNFIT".equals(outcome) ? "UNFIT" : "FIT";
        Instant now = support.now();
        r.setOutcome(outcome);
        r.setConditions(cond);
        r.setHrClearance(clearance);
        r.setStatus("OUTCOME_RECORDED");
        r.setOutcomeBy(user.username());
        r.setOutcomeAt(now);
        r.setUpdatedAt(now);
        String jobTitle = support.jobCode(r.getJobCode()).map(JobCode::getTitle).orElse(r.getJobCode());
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("candidateName", r.getCandidateName());
        fields.put("offerRef", r.getOfferRef());
        fields.put("jobTitle", jobTitle);
        fields.put("outcome", switch (outcome) {
            case "FIT" -> "Fit for the role";
            case "FIT_WITH_ADJUSTMENTS" -> "Fit for the role with workplace adjustments";
            default -> "Not fit for the role";
        });
        if (cond != null) {
            fields.put("conditions", cond);
        }
        fields.put("issuedBy", Optional.ofNullable(support.staffName(user.username())).orElse(user.username()));
        DocumentService.DocumentView doc = documents.generateSigned(user.username(), "PRE_EMPLOYMENT_OUTCOME", r.getCandidateId(), null, fields);
        r.setOutcomeDocumentId(doc.id());
        if (support.employee(r.getCandidateId()).isPresent()) {
            documents.shareAsSystem(user.username(), doc.id(), DocumentShare.Target.HR);
        } else {
            // candidates are not in the HRIS master: record the disclosure to HR directly (the HR view lists the clearance only)
            disclosures.record(r.getCandidateId(), "HR", "HR onboarding", "PRE_EMPLOYMENT_OUTCOME", doc.id().toString(),
                    "Pre-employment health clearance (" + doc.documentNumber() + ")", "PRE_EMPLOYMENT", null, user.username());
        }
        String messageId = UUID.randomUUID().toString();
        r.setHrMessageId(messageId);
        bus.publish(new E07Events.PreEmploymentStatus(messageId, r.getOfferRef(), r.getCandidateId(), clearance, support.today()),
                E07Events.HR_DESTINATION);
        support.notifyConfigAddress("notifications.hr-onboarding.email", "pre-employment.status", Map.of("documentNumber", r.getOfferRef()),
                support.managerPortal() + "/#/pre-employment");
        audit.record("PRE_EMPLOYMENT_OUTCOME", "PRE_EMPLOYMENT", id.toString(), AuditService.Outcome.SUCCESS, clearance);
        return view(r, true);
    }

    public record GovCommand(String type, @Size(max = 100) String reference, String status, LocalDate expiresOn) {
    }

    @Transactional
    public PreEmploymentView governmentMedical(CurrentUser user, UUID id, GovCommand cmd) {
        PreEmployment r = load(id);
        r.setGovType(cmd.type());
        r.setGovReference(cmd.reference() == null ? null : cmd.reference().strip());
        r.setGovStatus(cmd.status());
        r.setGovExpiresOn(cmd.expiresOn());
        r.setUpdatedAt(support.now());
        audit.record("PRE_EMPLOYMENT_GOV_MEDICAL", "PRE_EMPLOYMENT", id.toString(), AuditService.Outcome.SUCCESS, cmd.type() + " " + cmd.status());
        return view(r, true);
    }

    @Transactional
    public PreEmploymentView withdraw(CurrentUser user, UUID id) {
        PreEmployment r = load(id);
        if ("WITHDRAWN".equals(r.getStatus())) {
            return view(r, false);
        }
        Instant now = support.now();
        r.setStatus("WITHDRAWN");
        r.setWithdrawnAt(now);
        r.setRetentionUntil(support.today().plusMonths(12));
        r.setUpdatedAt(now);
        audit.record("PRE_EMPLOYMENT_WITHDRAW", "PRE_EMPLOYMENT", id.toString(), AuditService.Outcome.SUCCESS,
                "retentionUntil=" + r.getRetentionUntil());
        return view(r, false);
    }

    // ------------------------------------------------------------------ HR status view

    @Transactional(readOnly = true)
    public List<HrRow> hr(CurrentUser user) {
        List<HrRow> out = new ArrayList<>();
        for (PreEmployment r : records.findAllByOrderByCreatedAtDesc(PageRequest.of(0, 1000))) {
            if (r.getPurgedAt() != null) {
                continue;
            }
            if (user.orgScope() != null && r.getDepartmentCode() != null
                    && !SurveillanceSupport.inScope(user.orgScope(), support.orgPath(r.getDepartmentCode()))) {
                continue;
            }
            out.add(new HrRow(r.getId(), r.getOfferRef(), r.getCandidateName(),
                    support.jobCode(r.getJobCode()).map(JobCode::getTitle).orElse(r.getJobCode()), r.getProposedStart(), r.getHrClearance(),
                    r.getUpdatedAt()));
        }
        audit.record("PRE_EMPLOYMENT_HR_LIST", "PRE_EMPLOYMENT", null, AuditService.Outcome.SUCCESS, "rows=" + out.size());
        return out;
    }

    // ------------------------------------------------------------------ candidate portal

    private PreEmployment mine(CurrentUser user) {
        if (!user.hasRole(Roles.CANDIDATE) || user.employeeId() == null) {
            throw ApiException.forbidden("Candidate portal only");
        }
        return records.findByCandidateIdOrderByCreatedAtDesc(user.employeeId()).stream()
                .filter(r -> r.getPurgedAt() == null && !"WITHDRAWN".equals(r.getStatus())).findFirst()
                .orElseThrow(() -> ApiException.notFound("Pre-employment record"));
    }

    private CandidateView candidateView(PreEmployment r) {
        return new CandidateView(r.getId(), r.getOfferRef(), support.jobCode(r.getJobCode()).map(JobCode::getTitle).orElse(r.getJobCode()),
                r.getProposedStart(), r.getStatus(), r.getQuestionnaireCompletedAt() != null, r.isConsentGiven(), appointment(r), docs(r));
    }

    @Transactional(readOnly = true)
    public CandidateView candidate(CurrentUser user) {
        PreEmployment r = mine(user);
        audit.record("PRE_EMPLOYMENT_SELF_READ", "PRE_EMPLOYMENT", r.getId().toString(), AuditService.Outcome.SUCCESS, null);
        return candidateView(r);
    }

    @Transactional
    public CandidateView answer(CurrentUser user, Map<String, String> answers) {
        PreEmployment r = mine(user);
        if (!Set.of("INVITED", "QUESTIONNAIRE_DONE", "BOOKED").contains(r.getStatus())) {
            throw ApiException.conflict("questionnaire_closed", "The questionnaire can no longer be changed");
        }
        Map<String, String> clean = Questionnaire.validate(answers);
        Instant now = support.now();
        r.setQuestionnaire(support.write(clean));
        r.setQuestionnaireCompletedAt(now);
        if ("INVITED".equals(r.getStatus())) {
            r.setStatus("QUESTIONNAIRE_DONE");
        }
        r.setUpdatedAt(now);
        audit.record("PRE_EMPLOYMENT_QUESTIONNAIRE", "PRE_EMPLOYMENT", r.getId().toString(), AuditService.Outcome.SUCCESS, "answers=" + clean.size());
        return candidateView(r);
    }

    @Transactional
    public CandidateView consent(CurrentUser user, boolean consentGiven, List<String> purposes) {
        PreEmployment r = mine(user);
        if (!consentGiven) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "consent_required",
                    "The pre-employment assessment needs your consent; contact HR if you do not wish to continue");
        }
        if (purposes == null || !purposes.contains("PRE_EMPLOYMENT_ASSESSMENT") || !PURPOSES.containsAll(purposes)) {
            throw ApiException.badRequest("invalid_purposes", "Consent purposes must include PRE_EMPLOYMENT_ASSESSMENT");
        }
        Instant now = support.now();
        r.setConsentGiven(true);
        r.setConsentPurposes(SurveillanceSupport.joinCsv(purposes.stream().distinct().sorted().toList()));
        r.setConsentAt(now);
        r.setUpdatedAt(now);
        audit.record("PRE_EMPLOYMENT_CONSENT", "PRE_EMPLOYMENT", r.getId().toString(), AuditService.Outcome.SUCCESS, r.getConsentPurposes());
        return candidateView(r);
    }

    @Transactional
    public DocRef upload(CurrentUser user, byte[] bytes, String title) {
        PreEmployment r = mine(user);
        List<DocRef> docs = new ArrayList<>(docs(r));
        if (docs.size() >= MAX_DOCUMENTS) {
            throw ApiException.conflict("too_many_documents", "At most " + MAX_DOCUMENTS + " documents can be uploaded");
        }
        String t = title == null || title.isBlank() ? "Pre-employment document" : title.strip();
        if (t.length() > 200) {
            throw ApiException.badRequest("too_long", "Title too long");
        }
        DocumentService.Stored stored = documents.storeUpload(user.username(), r.getCandidateId(), null, "PRE_EMPLOYMENT_DOCUMENT", t, bytes,
                DataClassification.CLINICAL);
        DocRef ref = new DocRef(stored.document().id(), t);
        docs.add(ref);
        r.setDocuments(support.write(docs));
        r.setUpdatedAt(support.now());
        audit.record("PRE_EMPLOYMENT_DOCUMENT", "PRE_EMPLOYMENT", r.getId().toString(), AuditService.Outcome.SUCCESS, "document=" + ref.id());
        return ref;
    }

    @Transactional
    public CandidateView book(CurrentUser user, String siteCode, LocalDate earliest) {
        PreEmployment r = mine(user);
        if (r.getQuestionnaireCompletedAt() == null || !r.isConsentGiven()) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "questionnaire_and_consent_required",
                    "Complete the questionnaire and give consent before booking");
        }
        if (!Set.of("QUESTIONNAIRE_DONE", "INVITED").contains(r.getStatus())) {
            throw ApiException.conflict("already_booked", "An appointment is already booked or the assessment is complete");
        }
        if (siteCode != null && !support.siteExists(siteCode)) {
            throw ApiException.badRequest("unknown_site", "Unknown or inactive clinic site");
        }
        LocalDate from = earliest == null || earliest.isBefore(support.today().plusDays(1)) ? support.today().plusDays(1) : earliest;
        String site = siteCode != null ? siteCode : support.homeSite(r.getLocationCode());
        AppointmentBooking.Booking b = booker.book(user.username(), new AppointmentBooking.BookingRequest(r.getCandidateId(), "PRE_EMPLOYMENT",
                site, from, null, SOURCE_TYPE, r.getId().toString(), null))
                .orElseThrow(() -> new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "booking_unavailable",
                        "No appointment is available – Occupational Health will contact you"));
        r.setAppointmentId(b.appointmentId());
        r.setAppointmentStartsAt(b.startsAt());
        r.setAppointmentSite(b.siteCode());
        r.setStatus("BOOKED");
        r.setUpdatedAt(support.now());
        audit.record("PRE_EMPLOYMENT_BOOK", "PRE_EMPLOYMENT", r.getId().toString(), AuditService.Outcome.SUCCESS, "appointment=" + b.appointmentId());
        return candidateView(r);
    }

    // ------------------------------------------------------------------ scheduling callbacks & retention

    @Transactional
    public void appointmentChanged(UUID appointmentId, String sourceRef, AppointmentStatusListener.Status status) {
        PreEmployment r;
        if (sourceRef == null) {
            return;
        }
        try {
            r = records.findById(UUID.fromString(sourceRef)).orElse(null);
        } catch (IllegalArgumentException e) {
            return;
        }
        if (r == null || Set.of("OUTCOME_RECORDED", "WITHDRAWN").contains(r.getStatus())) {
            return;
        }
        switch (status) {
            case BOOKED, CHECKED_IN -> {
                r.setAppointmentId(appointmentId);
                r.setStatus("BOOKED");
            }
            case ATTENDED -> r.setStatus("EXAMINED");
            case NO_SHOW, CANCELLED -> {
                r.setAppointmentId(null);
                r.setAppointmentStartsAt(null);
                r.setAppointmentSite(null);
                r.setStatus(r.getQuestionnaireCompletedAt() == null ? "INVITED" : "QUESTIONNAIRE_DONE");
            }
            default -> {
            }
        }
        r.setUpdatedAt(support.now());
        audit.recordAs("system", "MODULE", "PRE_EMPLOYMENT_APPOINTMENT_" + status.name(), "PRE_EMPLOYMENT", r.getId().toString(),
                AuditService.Outcome.SUCCESS, null);
    }

    /** Purges candidates not hired once the 12-month retention has passed (nightly {@code pre-employment-purge}). */
    @Transactional
    public int purge(LocalDate today) {
        int n = 0;
        for (PreEmployment r : records.findByRetentionUntilBeforeAndPurgedAtIsNull(today)) {
            r.setCandidateName("Purged candidate");
            r.setEmail(null);
            r.setQuestionnaire(null);
            r.setConditions(null);
            r.setDocuments(null);
            r.setGovReference(null);
            r.setPurgedAt(support.now());
            r.setUpdatedAt(support.now());
            audit.recordAs("system", "SYSTEM", "PRE_EMPLOYMENT_PURGE", "PRE_EMPLOYMENT", r.getId().toString(), AuditService.Outcome.SUCCESS, null);
            n++;
        }
        return n;
    }
}
