package com.wellnessz.e07.crew;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.wellnessz.casekernel.CaseService;
import com.wellnessz.casekernel.FitnessFeed;
import com.wellnessz.casekernel.model.CaseCategory;
import com.wellnessz.casekernel.model.CaseSource;
import com.wellnessz.casekernel.model.CaseType;
import com.wellnessz.casekernel.model.FitnessStatus;
import com.wellnessz.casekernel.model.OhCase;
import com.wellnessz.casekernel.model.OpenCaseCommand;
import com.wellnessz.casekernel.support.CaseTimeline;
import com.wellnessz.casekernel.support.EmployeeRecordAccess;
import com.wellnessz.common.error.ApiException;
import com.wellnessz.common.model.DataClassification;
import com.wellnessz.common.model.Roles;
import com.wellnessz.documents.DocumentService;
import com.wellnessz.documents.model.DocumentShare;
import com.wellnessz.e05.decision.LimitationCodes;
import com.wellnessz.e07.E07Events;
import com.wellnessz.e07.due.DueService;
import com.wellnessz.e07.model.CrewCertificate;
import com.wellnessz.e07.model.CrewCertificateRepository;
import com.wellnessz.e07.model.Enrolment;
import com.wellnessz.e07.model.EnrolmentRepository;
import com.wellnessz.e07.programme.Eligibility;
import com.wellnessz.e07.programme.EnrolmentEngine;
import com.wellnessz.e07.programme.ProgrammeCatalog;
import com.wellnessz.e07.support.SurveillanceSupport;
import com.wellnessz.integration.outbox.EventBus;
import com.wellnessz.masterdata.Employee;
import com.wellnessz.masterdata.JobCode;
import com.wellnessz.security.abac.AccessPolicy;
import com.wellnessz.security.audit.AuditService;
import com.wellnessz.security.identity.CurrentUser;

/**
 * Crew aeromedical examinations and medical certificate tracking (E07-F04): CAA-format examination with the medical report,
 * external AME / AeMC imports, status-only certificate views for crew licensing / scheduling, authority report, expiry
 * alerts at 90 / 60 / 30 days and roster status UNFIT on expiry (published through the kernel crew roster feed).
 */
@Service
public class CrewCertificateService {

    static final Set<String> PILOT_TYPES = Set.of("CLASS1", "CLASS2", "LAPL");
    static final int MAX_ANSWERS = 120;

    private final CrewCertificateRepository certificates;
    private final EnrolmentRepository enrolments;
    private final ProgrammeCatalog catalog;
    private final EnrolmentEngine engine;
    private final DueService due;
    private final CaseService cases;
    private final CaseTimeline timeline;
    private final EmployeeRecordAccess records;
    private final DocumentService documents;
    private final SurveillanceSupport support;
    private final EventBus bus;
    private final AuditService audit;

    public CrewCertificateService(CrewCertificateRepository certificates, EnrolmentRepository enrolments, ProgrammeCatalog catalog,
                                  EnrolmentEngine engine, DueService due, CaseService cases, CaseTimeline timeline, EmployeeRecordAccess records,
                                  DocumentService documents, SurveillanceSupport support, EventBus bus, AuditService audit) {
        this.certificates = certificates;
        this.enrolments = enrolments;
        this.catalog = catalog;
        this.engine = engine;
        this.due = due;
        this.cases = cases;
        this.timeline = timeline;
        this.records = records;
        this.documents = documents;
        this.support = support;
        this.bus = bus;
        this.audit = audit;
    }

    // ------------------------------------------------------------------ examination template (CAA format)

    public record TemplateItem(String code, String label, String type) {
    }

    public record TemplateSection(String section, List<TemplateItem> items) {
    }

    public List<TemplateSection> examTemplate(String type) {
        List<TemplateSection> out = new ArrayList<>();
        out.add(new TemplateSection("History", List.of(new TemplateItem("HISTORY_CHANGES", "Changes in medical history since last examination", "YES_NO"),
                new TemplateItem("HISTORY_NOTES", "History notes", "TEXT"), new TemplateItem("MEDICATION_REVIEWED", "Medication reviewed", "YES_NO"))));
        out.add(new TemplateSection("General examination", List.of(new TemplateItem("HEIGHT_CM", "Height (cm)", "NUMBER"),
                new TemplateItem("WEIGHT_KG", "Weight (kg)", "NUMBER"), new TemplateItem("BP_SYSTOLIC", "Blood pressure systolic (mmHg)", "NUMBER"),
                new TemplateItem("BP_DIASTOLIC", "Blood pressure diastolic (mmHg)", "NUMBER"), new TemplateItem("PULSE", "Pulse (/min)", "NUMBER"),
                new TemplateItem("SYSTEMS_NORMAL", "Systems examination normal", "YES_NO"))));
        out.add(new TemplateSection("Vision", List.of(new TemplateItem("DISTANT_VA_RIGHT", "Distant visual acuity right", "TEXT"),
                new TemplateItem("DISTANT_VA_LEFT", "Distant visual acuity left", "TEXT"), new TemplateItem("NEAR_VA", "Near vision N5 at 30-50 cm", "YES_NO"),
                new TemplateItem("COLOUR_SAFE", "Colour safe", "YES_NO"), new TemplateItem("CORRECTION", "Correction worn", "YES_NO"))));
        if (PILOT_TYPES.contains(type)) {
            out.add(new TemplateSection("Hearing & ENT", List.of(new TemplateItem("AUDIOGRAM_DONE", "Pure-tone audiogram performed", "YES_NO"),
                    new TemplateItem("HEARING_SATISFACTORY", "Hearing satisfactory for licence class", "YES_NO"))));
            out.add(new TemplateSection("Cardiovascular", List.of(new TemplateItem("ECG_DONE", "Resting ECG performed", "YES_NO"),
                    new TemplateItem("ECG_NORMAL", "ECG within normal limits", "YES_NO"))));
            out.add(new TemplateSection("Laboratory", List.of(new TemplateItem("HB_DONE", "Haemoglobin tested", "YES_NO"),
                    new TemplateItem("LIPIDS_DONE", "Lipids tested (when indicated)", "YES_NO"), new TemplateItem("URINE_NORMAL", "Urinalysis normal", "YES_NO"))));
        } else {
            out.add(new TemplateSection("Hearing", List.of(new TemplateItem("HEARING_SATISFACTORY", "Hearing satisfactory (conversational voice test)", "YES_NO"))));
            out.add(new TemplateSection("Cabin duties", List.of(new TemplateItem("EMERGENCY_DUTIES_FIT", "Able to perform emergency and safety duties", "YES_NO"))));
        }
        out.add(new TemplateSection("Assessment", List.of(new TemplateItem("AME_COMMENTS", "AME comments (not shared)", "TEXT"))));
        return out;
    }

    // ------------------------------------------------------------------ views

    public record CrewCertificateView(UUID id, String employeeId, String displayName, String certificateType, String certificateNumber,
                                      String issuingAuthority, LocalDate examinedOn, LocalDate validUntil, List<String> limitationCodes,
                                      String status, String source, String aemcName, String ameName, List<Integer> alertsSent,
                                      String outcome, UUID documentId) {
    }

    public record WithoutMedical(String employeeId, String displayName, String jobCode, String certificateType, LocalDate validUntil,
                                 String status) {
    }

    CrewCertificateView view(CrewCertificate c, String displayName, boolean ame, LocalDate today) {
        return new CrewCertificateView(c.getId(), c.getEmployeeId(), displayName, c.getCertificateType(), c.getCertificateNumber(),
                c.getIssuingAuthority(), c.getExaminedOn(), c.getValidUntil(), visibleLimitations(c, ame),
                CrewAlerts.status(c.getValidUntil(), c.isSuspended(), today), c.getSource(), c.getAemcName(), c.getAmeName(), sent(c),
                ame ? c.getOutcome() : null, ame ? c.getDocumentId() : null);
    }

    /** Crew scheduling / licensing see operational limitation codes only (the others describe a medical correction). */
    static List<String> visibleLimitations(CrewCertificate c, boolean ame) {
        List<String> all = SurveillanceSupport.csv(c.getLimitationCodes());
        return ame ? all : all.stream().filter(LimitationCodes::isOperational).toList();
    }

    static List<Integer> sent(CrewCertificate c) {
        return SurveillanceSupport.csv(c.getAlertsSent()).stream().map(Integer::valueOf).sorted(Comparator.reverseOrder()).toList();
    }

    @Transactional(readOnly = true)
    public List<CrewCertificateView> list(CurrentUser user, String status, Integer expiringWithinDays) {
        boolean ame = user.hasAnyRole(Roles.AME, Roles.CMO);
        LocalDate today = support.today();
        List<CrewCertificate> list = certificates.findBySupersededFalseOrderByValidUntilAsc();
        Map<String, Employee> emps = support.employeesById(list.stream().map(CrewCertificate::getEmployeeId).distinct().toList());
        List<CrewCertificateView> out = new ArrayList<>();
        for (CrewCertificate c : list) {
            Employee e = emps.get(c.getEmployeeId());
            CrewCertificateView v = view(c, e == null ? c.getEmployeeId() : e.getDisplayName(), ame, today);
            if (status != null && !status.equals(v.status())) {
                continue;
            }
            if (expiringWithinDays != null && (c.getValidUntil() == null || c.getValidUntil().isAfter(today.plusDays(expiringWithinDays)))) {
                continue;
            }
            out.add(v);
        }
        audit.record("CREW_CERTIFICATE_LIST", "CREW_CERTIFICATE", null, AuditService.Outcome.SUCCESS, "rows=" + out.size() + (ame ? "" : " status-only"));
        return out;
    }

    @Transactional(readOnly = true)
    public List<WithoutMedical> withoutValidMedical(CurrentUser user) {
        LocalDate today = support.today();
        Map<String, JobCode> jobs = support.jobCodes();
        List<WithoutMedical> out = new ArrayList<>();
        Map<String, List<CrewCertificate>> byEmployee = certificates.findBySupersededFalseOrderByValidUntilAsc().stream()
                .collect(Collectors.groupingBy(CrewCertificate::getEmployeeId));
        for (Employee e : support.activeEmployees()) {
            JobCode j = e.getJobCode() == null ? null : jobs.get(e.getJobCode());
            if (j == null || j.getSafetyCategory() == null || !SurveillanceSupport.CREW_CATEGORIES.contains(j.getSafetyCategory())) {
                continue;
            }
            List<CrewCertificate> certs = byEmployee.getOrDefault(e.getEmployeeId(), List.of());
            if (certs.isEmpty()) {
                out.add(new WithoutMedical(e.getEmployeeId(), e.getDisplayName(), e.getJobCode(), null, null, "NONE"));
                continue;
            }
            boolean anyValid = certs.stream().map(c -> CrewAlerts.status(c.getValidUntil(), c.isSuspended(), today))
                    .anyMatch(s -> "VALID".equals(s) || "EXPIRING".equals(s));
            if (!anyValid) {
                CrewCertificate latest = certs.stream().max(Comparator.comparing(CrewCertificate::getExaminedOn)).orElseThrow();
                out.add(new WithoutMedical(e.getEmployeeId(), e.getDisplayName(), e.getJobCode(), latest.getCertificateType(),
                        latest.getValidUntil(), CrewAlerts.status(latest.getValidUntil(), latest.isSuspended(), today)));
            }
        }
        audit.record("CREW_WITHOUT_VALID_MEDICAL", "CREW_CERTIFICATE", null, AuditService.Outcome.SUCCESS, "rows=" + out.size());
        return out;
    }

    // ------------------------------------------------------------------ examination (AME)

    public record ExamCommand(@NotBlank @Pattern(regexp = "[A-Za-z0-9_\\-]{1,64}") String employeeId,
                              @NotBlank @Pattern(regexp = "CLASS1|CLASS2|LAPL|CABIN_CREW") String certificateType, @NotNull LocalDate examinedOn,
                              @Size(max = MAX_ANSWERS) Map<String, Object> answers,
                              @NotBlank @Pattern(regexp = "FIT|FIT_WITH_LIMITATIONS|UNFIT|REFERRED_TO_AUTHORITY") String outcome,
                              @Size(max = 15) List<String> limitationCodes, LocalDate validUntil) {
    }

    @Transactional
    public CrewCertificateView examine(CurrentUser user, ExamCommand cmd) {
        Employee e = records.requireClinical(user, cmd.employeeId(), DataClassification.CLINICAL, AccessPolicy.Action.CREATE, "CREW_EXAMINATION");
        LocalDate today = support.today();
        if (cmd.examinedOn().isAfter(today) || cmd.examinedOn().isBefore(today.minusDays(90))) {
            throw ApiException.badRequest("invalid_examination_date", "The examination date must be within the last 90 days");
        }
        List<String> codes = limitations(cmd.limitationCodes());
        boolean fit = "FIT".equals(cmd.outcome()) || "FIT_WITH_LIMITATIONS".equals(cmd.outcome());
        if ("FIT_WITH_LIMITATIONS".equals(cmd.outcome()) && codes.isEmpty()) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "limitation_required", "Record at least one limitation code");
        }
        if ("FIT".equals(cmd.outcome()) && !codes.isEmpty()) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "limitations_need_outcome",
                    "Limitation codes require the outcome FIT_WITH_LIMITATIONS");
        }
        LocalDate validUntil = null;
        if (fit) {
            validUntil = cmd.validUntil() != null ? cmd.validUntil() : defaultValidity(cmd.certificateType(), e.getEmployeeId(), cmd.examinedOn());
            if (!validUntil.isAfter(cmd.examinedOn()) || validUntil.isAfter(cmd.examinedOn().plusMonths(60))) {
                throw ApiException.badRequest("invalid_valid_until", "Validity must end after the examination and within 60 months");
            }
        }
        String answers = answersJson(cmd.answers());
        CrewCertificate c = newCertificate(e.getEmployeeId(), cmd.certificateType(), cmd.examinedOn(), validUntil, codes, cmd.outcome(),
                "INTERNAL", user.username());
        c.setCertificateNumber(number(cmd.certificateType(), cmd.examinedOn(), c.getId()));
        c.setIssuingAuthority(support.configString("surveillance.crew.issuing-authority").orElse("UK CAA"));
        c.setAmeName(Optional.ofNullable(support.staffName(user.username())).orElse(user.username()));
        c.setSuspended(!fit);
        c.setAnswers(answers);
        supersede(e.getEmployeeId(), cmd.certificateType());
        certificates.save(c);
        OhCase kase = publishRoster(e.getEmployeeId(), rosterFitness(cmd.outcome()), cmd.examinedOn(), validUntil,
                codes.stream().filter(LimitationCodes::isOperational).toList(), "Crew medical examination: " + cmd.outcome());
        c.setCaseId(kase.getId());
        c.setDocumentId(report(user.username(), e, c, kase.getId()));
        completeProgramme(e.getEmployeeId(), cmd.certificateType(), cmd.examinedOn(), cmd.outcome(), user.username());
        bus.publish(new E07Events.CrewCertificateStatus(e.getEmployeeId(), c.getCertificateType(), c.getValidUntil(),
                CrewAlerts.status(c.getValidUntil(), c.isSuspended(), today)));
        audit.record("CREW_EXAMINATION", "CREW_CERTIFICATE", c.getId().toString(), AuditService.Outcome.SUCCESS,
                cmd.certificateType() + " " + cmd.outcome());
        return view(c, e.getDisplayName(), true, today);
    }

    LocalDate defaultValidity(String type, String employeeId, LocalDate examinedOn) {
        LocalDate dob = engine.dateOfBirth(employeeId);
        int months = switch (type) {
            case "CLASS1" -> catalog.active("PILOT_CLASS1").map(p -> Eligibility.frequencyMonths(p.getFrequencyMonths(),
                    p.getFrequencyMonthsOver40(), dob, examinedOn)).orElse(12);
            case "CABIN_CREW" -> catalog.active("CABIN_CREW").map(p -> p.getFrequencyMonths()).orElse(24);
            case "CLASS2" -> Eligibility.frequencyMonths(24, 12, dob, examinedOn);
            default -> Eligibility.frequencyMonths(60, 24, dob, examinedOn);
        };
        return examinedOn.plusMonths(months).minusDays(1);
    }

    private static List<String> limitations(List<String> input) {
        if (input == null) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (String code : input) {
            if (code == null || !LimitationCodes.isKnown(code)) {
                throw ApiException.badRequest("unknown_limitation_code", "Unknown limitation code");
            }
            if (!out.contains(code)) {
                out.add(code);
            }
        }
        return out;
    }

    private String answersJson(Map<String, Object> answers) {
        if (answers == null || answers.isEmpty()) {
            return null;
        }
        Map<String, String> clean = new LinkedHashMap<>();
        for (Map.Entry<String, Object> a : answers.entrySet()) {
            if (a.getKey() == null || !a.getKey().matches("[A-Z0-9_]{1,40}")) {
                throw ApiException.badRequest("invalid_answers", "Answer codes are upper-case identifiers");
            }
            Object v = a.getValue();
            if (v != null && !(v instanceof String || v instanceof Number || v instanceof Boolean)) {
                throw ApiException.badRequest("invalid_answers", "Answers must be text, numbers or yes / no");
            }
            String s = v == null ? null : String.valueOf(v);
            if (s != null && s.length() > 1000) {
                throw ApiException.badRequest("invalid_answers", "Answer too long");
            }
            clean.put(a.getKey(), s);
        }
        return support.write(clean);
    }

    static String number(String type, LocalDate examinedOn, UUID id) {
        String prefix = switch (type) {
            case "CLASS1" -> "C1";
            case "CLASS2" -> "C2";
            case "LAPL" -> "LAPL";
            default -> "CC";
        };
        return prefix + "-" + examinedOn.getYear() + "-" + id.toString().substring(0, 8).toUpperCase(Locale.ROOT);
    }

    private CrewCertificate newCertificate(String employeeId, String type, LocalDate examinedOn, LocalDate validUntil, List<String> codes,
                                           String outcome, String source, String actor) {
        CrewCertificate c = new CrewCertificate();
        c.setId(UUID.randomUUID());
        c.setEmployeeId(employeeId);
        c.setCertificateType(type);
        c.setExaminedOn(examinedOn);
        c.setValidUntil(validUntil);
        c.setLimitationCodes(SurveillanceSupport.joinCsv(codes));
        c.setOutcome(outcome);
        c.setSource(source);
        c.setCreatedBy(actor);
        c.setCreatedAt(support.now());
        return c;
    }

    private void supersede(String employeeId, String type) {
        certificates.findByEmployeeIdAndCertificateTypeAndSupersededFalse(employeeId, type).forEach(old -> old.setSuperseded(true));
        certificates.flush();
    }

    private UUID report(String actor, Employee e, CrewCertificate c, UUID caseId) {
        boolean pilot = PILOT_TYPES.contains(c.getCertificateType());
        Map<String, String> f = new LinkedHashMap<>();
        f.put("employeeName", e.getDisplayName());
        f.put("employeeId", e.getEmployeeId());
        if (pilot) {
            f.put("medicalClass", switch (c.getCertificateType()) {
                case "CLASS1" -> "Class 1";
                case "CLASS2" -> "Class 2";
                default -> "LAPL";
            });
        }
        f.put("examinationDate", c.getExaminedOn().toString());
        f.put("assessment", switch (c.getOutcome()) {
            case "FIT" -> "Fit";
            case "FIT_WITH_LIMITATIONS" -> "Fit with limitations";
            case "UNFIT" -> "Unfit";
            default -> "Referred to the licensing authority";
        });
        List<String> codes = SurveillanceSupport.csv(c.getLimitationCodes());
        if (!codes.isEmpty()) {
            f.put("limitations", String.join("; ", codes.stream().map(x -> x + " – " + LimitationCodes.labels().get(x)).toList()));
        }
        f.put("validUntil", c.getValidUntil() == null ? "Not applicable" : c.getValidUntil().toString());
        f.put("ameName", c.getAmeName());
        DocumentService.DocumentView doc = documents.generateSigned(actor, pilot ? "CREW_MEDICAL_REPORT" : "CABIN_CREW_MEDICAL_REPORT",
                e.getEmployeeId(), caseId, f);
        documents.shareAsSystem(actor, doc.id(), DocumentShare.Target.EMPLOYEE);
        return doc.id();
    }

    private void completeProgramme(String employeeId, String type, LocalDate examinedOn, String outcome, String actor) {
        String code = "CABIN_CREW".equals(type) ? "CABIN_CREW" : "CLASS1".equals(type) ? "PILOT_CLASS1" : null;
        if (code == null) {
            return;
        }
        Optional<Enrolment> e = enrolments.findByEmployeeIdAndProgrammeCode(employeeId, code).filter(Enrolment::isActive);
        if (e.isPresent() && (e.get().getLastCompletedOn() == null || !examinedOn.isBefore(e.get().getLastCompletedOn()))) {
            catalog.current(code).ifPresent(p -> due.recordCompletion(e.get(), p, examinedOn,
                    "REFERRED_TO_AUTHORITY".equals(outcome) ? "REFER" : outcome, null, null, actor));
        }
    }

    static FitnessStatus rosterFitness(String outcome) {
        return switch (outcome) {
            case "FIT" -> FitnessStatus.FIT;
            case "FIT_WITH_LIMITATIONS" -> FitnessStatus.FIT_WITH_RESTRICTIONS;
            case "UNFIT", "EXPIRED" -> FitnessStatus.TEMPORARILY_UNFIT;
            default -> FitnessStatus.UNDER_REVIEW;
        };
    }

    /**
     * Publishes the crew roster status (FIT / LIMITED / UNFIT / PENDING) through the kernel: one standing crew-medical case per
     * crew member carries the status; the E05 roster feed log records the message for acknowledgement and reconciliation.
     */
    OhCase publishRoster(String employeeId, FitnessStatus status, LocalDate validFrom, LocalDate validUntil, List<String> operational,
                         String timelineNote) {
        OhCase c = cases.openIdempotent(OpenCaseCommand.builder(employeeId, CaseCategory.DECREASE_IN_FITNESS, CaseSource.SURVEILLANCE_TRIGGER)
                .caseType(CaseType.FITNESS).sourceRef("surveillance:crew:" + employeeId).build());
        Instant now = support.now();
        c.recordFitness(status, status == FitnessStatus.FIT_WITH_RESTRICTIONS || status == FitnessStatus.FIT ? validUntil : null, now);
        cases.publishFitness(c, new FitnessFeed(validFrom, validUntil, null, operational));
        timeline.record(c.getId(), "CREW_CERTIFICATE", timelineNote);
        return c;
    }

    // ------------------------------------------------------------------ external import (AME / AeMC)

    public record ImportCommand(@NotBlank @Pattern(regexp = "[A-Za-z0-9_\\-]{1,64}") String employeeId,
                                @NotBlank @Pattern(regexp = "CLASS1|CLASS2|LAPL|CABIN_CREW") String certificateType,
                                @NotBlank @Pattern(regexp = "[A-Za-z0-9/\\-]{3,60}") String certificateNumber,
                                @NotBlank @Size(max = 100) String issuingAuthority, @NotBlank @Size(max = 200) String aemcName,
                                @Size(max = 200) String ameName, @NotNull LocalDate examinedOn, @NotNull LocalDate validUntil,
                                @Size(max = 15) List<String> limitationCodes, @Size(max = 14_000_000) String pdfBase64) {
    }

    @Transactional
    public CrewCertificateView importExternal(String actor, ImportCommand cmd) {
        Employee e = support.employee(cmd.employeeId()).orElseThrow(() -> ApiException.notFound("Employee"));
        LocalDate today = support.today();
        if (cmd.examinedOn().isAfter(today) || !cmd.validUntil().isAfter(cmd.examinedOn())
                || cmd.validUntil().isAfter(cmd.examinedOn().plusMonths(60))) {
            throw ApiException.badRequest("invalid_dates", "Examination date must not be in the future and validity must end within 60 months after it");
        }
        List<String> codes = limitations(cmd.limitationCodes());
        String outcome = codes.isEmpty() ? "FIT" : "FIT_WITH_LIMITATIONS";
        CrewCertificate c = newCertificate(e.getEmployeeId(), cmd.certificateType(), cmd.examinedOn(), cmd.validUntil(), codes, outcome,
                "EXTERNAL_AME", actor);
        c.setCertificateNumber(cmd.certificateNumber());
        c.setIssuingAuthority(cmd.issuingAuthority().strip());
        c.setAemcName(cmd.aemcName().strip());
        c.setAmeName(cmd.ameName() == null ? null : cmd.ameName().strip());
        if (cmd.pdfBase64() != null && !cmd.pdfBase64().isBlank()) {
            byte[] pdf;
            try {
                pdf = Base64.getDecoder().decode(cmd.pdfBase64());
            } catch (IllegalArgumentException ex) {
                throw ApiException.badRequest("invalid_pdf", "pdfBase64 is not valid Base64");
            }
            c.setDocumentId(documents.storeUpload(actor, e.getEmployeeId(), null, "EXTERNAL_AEROMEDICAL_REPORT",
                    "External crew medical report " + cmd.certificateNumber(), pdf, DataClassification.CLINICAL).document().id());
        }
        supersede(e.getEmployeeId(), cmd.certificateType());
        certificates.save(c);
        boolean current = !cmd.validUntil().isBefore(today);
        OhCase kase = publishRoster(e.getEmployeeId(), current ? rosterFitness(outcome) : FitnessStatus.TEMPORARILY_UNFIT, cmd.examinedOn(),
                cmd.validUntil(), codes.stream().filter(LimitationCodes::isOperational).toList(), "External crew medical certificate imported");
        c.setCaseId(kase.getId());
        c.setExpiredPublished(!current);
        completeProgramme(e.getEmployeeId(), cmd.certificateType(), cmd.examinedOn(), outcome, actor);
        bus.publish(new E07Events.CrewCertificateStatus(e.getEmployeeId(), c.getCertificateType(), c.getValidUntil(),
                CrewAlerts.status(c.getValidUntil(), false, today)));
        audit.recordAs(actor, "MODULE", "CREW_CERTIFICATE_IMPORT", "CREW_CERTIFICATE", c.getId().toString(), AuditService.Outcome.SUCCESS,
                cmd.certificateType() + " source=EXTERNAL_AME");
        return view(c, e.getDisplayName(), true, today);
    }

    // ------------------------------------------------------------------ authority report

    @Transactional(readOnly = true)
    public String authorityReport(CurrentUser user, LocalDate from, LocalDate to) {
        LocalDate end = to == null ? support.today() : to;
        LocalDate start = from == null ? end.minusMonths(1) : from;
        if (start.isAfter(end) || start.plusDays(400).isBefore(end)) {
            throw ApiException.badRequest("invalid_window", "The report window must be between 1 and 400 days");
        }
        StringBuilder sb = new StringBuilder("employeeId,certificateType,certificateNumber,issuingAuthority,examinedOn,outcome,validUntil,"
                + "limitationCodes,source,aemcName,ameName\n");
        for (CrewCertificate c : certificates.findByExaminedOnBetweenOrderByExaminedOnAsc(start, end)) {
            sb.append(String.join(",", csvCell(c.getEmployeeId()), c.getCertificateType(), csvCell(c.getCertificateNumber()),
                    csvCell(c.getIssuingAuthority()), c.getExaminedOn().toString(), c.getOutcome(),
                    c.getValidUntil() == null ? "" : c.getValidUntil().toString(), csvCell(String.join(" ", SurveillanceSupport.csv(c.getLimitationCodes()))),
                    c.getSource(), csvCell(c.getAemcName()), csvCell(c.getAmeName()))).append('\n');
        }
        audit.record("CREW_AUTHORITY_REPORT", "CREW_CERTIFICATE", null, AuditService.Outcome.SUCCESS, start + ".." + end);
        return sb.toString();
    }

    /** CSV cell with formula-injection protection. */
    public static String csvCell(String v) {
        if (v == null) {
            return "";
        }
        String s = v;
        if (!s.isEmpty() && "=+-@\t\r".indexOf(s.charAt(0)) >= 0) {
            s = "'" + s;
        }
        if (s.contains(",") || s.contains("\"") || s.contains("\n")) {
            s = "\"" + s.replace("\"", "\"\"") + "\"";
        }
        return s;
    }

    // ------------------------------------------------------------------ employee view & nightly job

    public record MyCertificate(String certificateType, LocalDate validUntil, String status) {
    }

    @Transactional(readOnly = true)
    public Optional<MyCertificate> mine(String employeeId) {
        LocalDate today = support.today();
        return certificates.findByEmployeeIdAndSupersededFalse(employeeId).stream()
                .max(Comparator.comparing(CrewCertificate::getExaminedOn))
                .map(c -> new MyCertificate(c.getCertificateType(), c.getValidUntil(), CrewAlerts.status(c.getValidUntil(), c.isSuspended(), today)));
    }

    public record CrewRun(int alerts, int expired) {
    }

    /** Nightly {@code crew-certificates}: 90 / 60 / 30-day alerts and roster status UNFIT on expiry. */
    @Transactional
    public CrewRun nightly(LocalDate today) {
        int alerts = 0;
        int expired = 0;
        for (CrewCertificate c : certificates.findBySupersededFalseOrderByValidUntilAsc()) {
            if (c.isSuspended() || c.getValidUntil() == null) {
                continue;
            }
            Employee e = support.employee(c.getEmployeeId()).orElse(null);
            if (c.getValidUntil().isBefore(today)) {
                if (!c.isExpiredPublished()) {
                    c.setExpiredPublished(true);
                    OhCase kase = publishRoster(c.getEmployeeId(), FitnessStatus.TEMPORARILY_UNFIT, today, c.getValidUntil(), List.of(),
                            "Crew medical certificate expired");
                    if (c.getCaseId() == null) {
                        c.setCaseId(kase.getId());
                    }
                    bus.publish(new E07Events.CrewCertificateStatus(c.getEmployeeId(), c.getCertificateType(), c.getValidUntil(), "EXPIRED"));
                    audit.recordAs("system", "SYSTEM", "CREW_CERTIFICATE_EXPIRED", "CREW_CERTIFICATE", c.getId().toString(),
                            AuditService.Outcome.SUCCESS, "roster UNFIT");
                    expired++;
                }
                continue;
            }
            List<Integer> sent = sent(c);
            Optional<Integer> band = CrewAlerts.dueBand(c.getValidUntil(), today, sent);
            if (band.isEmpty()) {
                continue;
            }
            c.setAlertsSent(SurveillanceSupport.joinCsv(CrewAlerts.markSent(sent, band.get()).stream().map(String::valueOf).toList()));
            String level = band.get() + "-day notice";
            support.notifyEmployee(c.getEmployeeId(), "crew.medical-expiring", Map.of("dueDate", c.getValidUntil().toString(), "level", level));
            support.notifyConfigAddress("notifications.crew-licensing.email", "crew.medical-expiring-licensing",
                    Map.of("employeeName", e == null ? c.getEmployeeId() : e.getDisplayName(), "dueDate", c.getValidUntil().toString(),
                            "level", level), null);
            alerts++;
        }
        return new CrewRun(alerts, expired);
    }
}
