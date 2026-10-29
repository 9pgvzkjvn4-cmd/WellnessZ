package com.wellnessz.e07.immunisation;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.wellnessz.casekernel.support.EmployeeRecordAccess;
import com.wellnessz.common.error.ApiException;
import com.wellnessz.common.model.DataClassification;
import com.wellnessz.common.model.Roles;
import com.wellnessz.documents.DocumentService;
import com.wellnessz.documents.model.DocumentShare;
import com.wellnessz.e03.model.Immunisation;
import com.wellnessz.e03.model.ImmunisationRepository;
import com.wellnessz.e07.model.ImmunisationRequirement;
import com.wellnessz.e07.model.ImmunisationRequirementRepository;
import com.wellnessz.e07.model.Titre;
import com.wellnessz.e07.model.TitreRepository;
import com.wellnessz.e07.model.VaccineAdministration;
import com.wellnessz.e07.model.VaccineAdministrationRepository;
import com.wellnessz.e07.support.SurveillanceSupport;
import com.wellnessz.masterdata.Employee;
import com.wellnessz.masterdata.JobCode;
import com.wellnessz.security.abac.AccessPolicy;
import com.wellnessz.security.audit.AuditService;
import com.wellnessz.security.disclosure.DisclosureLogService;
import com.wellnessz.security.identity.CurrentUser;

/**
 * Immunisation & titre registry (E07-F05): role / hazard matrix, due list, fast entry with GS1 lot scanning, campaign batch
 * entry, titres, the employee's own status and vaccination certificate. Administrations are mirrored into the E03
 * immunisation record.
 */
@Service
public class ImmunisationService {

    static final Map<String, String> VACCINES = Map.ofEntries(Map.entry("TYPHOID", "Typhoid"), Map.entry("HEP_A", "Hepatitis A"),
            Map.entry("HEP_B", "Hepatitis B"), Map.entry("MMR", "MMR"), Map.entry("INFLUENZA", "Influenza"),
            Map.entry("YELLOW_FEVER", "Yellow fever"), Map.entry("TDAP_IPV", "Tetanus, diphtheria and polio"), Map.entry("COVID_19", "COVID-19"),
            Map.entry("VARICELLA", "Varicella"), Map.entry("RABIES", "Rabies"), Map.entry("MENACWY", "Meningococcal ACWY"));
    static final int MAX_BATCH = 1000;

    private final ImmunisationRequirementRepository requirements;
    private final VaccineAdministrationRepository administrations;
    private final TitreRepository titres;
    private final ImmunisationRepository recordImmunisations;
    private final EmployeeRecordAccess records;
    private final DocumentService documents;
    private final DisclosureLogService disclosures;
    private final SurveillanceSupport support;
    private final JdbcTemplate jdbc;
    private final AuditService audit;

    public ImmunisationService(ImmunisationRequirementRepository requirements, VaccineAdministrationRepository administrations,
                               TitreRepository titres, ImmunisationRepository recordImmunisations, EmployeeRecordAccess records,
                               DocumentService documents, DisclosureLogService disclosures, SurveillanceSupport support, JdbcTemplate jdbc,
                               AuditService audit) {
        this.requirements = requirements;
        this.administrations = administrations;
        this.titres = titres;
        this.recordImmunisations = recordImmunisations;
        this.records = records;
        this.documents = documents;
        this.disclosures = disclosures;
        this.support = support;
        this.jdbc = jdbc;
        this.audit = audit;
    }

    // ------------------------------------------------------------------ matrix

    public record RequirementView(UUID id, String jobCode, String hazardCode, String vaccineCode, String vaccineName, int doses,
                                  String scheduleMonths, Integer boosterMonths, boolean titreRequired, boolean mandatory, boolean active) {
    }

    public record RequirementCommand(@Pattern(regexp = "[A-Za-z0-9_\\-]{1,64}") String jobCode,
                                     @Pattern(regexp = "[A-Za-z0-9_\\-]{1,64}") String hazardCode,
                                     @NotBlank @Pattern(regexp = "[A-Z0-9_]{2,40}") String vaccineCode, @Size(max = 200) String vaccineName,
                                     @NotNull @Min(1) @Max(10) Integer doses, @Pattern(regexp = "\\d{1,3}(,\\d{1,3}){0,9}") String scheduleMonths,
                                     @Min(1) @Max(240) Integer boosterMonths, Boolean titreRequired, Boolean mandatory, Boolean active) {
    }

    static RequirementView view(ImmunisationRequirement r) {
        return new RequirementView(r.getId(), r.getJobCode(), r.getHazardCode(), r.getVaccineCode(), r.getVaccineName(), r.getDoses(),
                r.getScheduleMonths(), r.getBoosterMonths(), r.isTitreRequired(), r.isMandatory(), r.isActive());
    }

    @Transactional(readOnly = true)
    public List<RequirementView> requirements() {
        return requirements.findAllByOrderByVaccineCodeAscJobCodeAsc().stream().map(ImmunisationService::view).toList();
    }

    @Transactional
    public RequirementView putRequirement(CurrentUser user, UUID id, RequirementCommand cmd) {
        if (cmd.jobCode() == null && cmd.hazardCode() == null) {
            throw ApiException.badRequest("target_required", "A requirement applies to a job code or a hazard");
        }
        ImmunisationRequirement r = requirements.findById(id).orElseGet(() -> {
            ImmunisationRequirement n = new ImmunisationRequirement();
            n.setId(id);
            return n;
        });
        r.setJobCode(cmd.jobCode());
        r.setHazardCode(cmd.hazardCode());
        r.setVaccineCode(cmd.vaccineCode());
        r.setVaccineName(cmd.vaccineName() != null && !cmd.vaccineName().isBlank() ? cmd.vaccineName().strip()
                : VACCINES.getOrDefault(cmd.vaccineCode(), cmd.vaccineCode()));
        r.setDoses(cmd.doses());
        r.setScheduleMonths(cmd.scheduleMonths() == null ? "0" : cmd.scheduleMonths());
        r.setBoosterMonths(cmd.boosterMonths());
        r.setTitreRequired(Boolean.TRUE.equals(cmd.titreRequired()));
        r.setMandatory(cmd.mandatory() == null || cmd.mandatory());
        r.setActive(cmd.active() == null || cmd.active());
        r.setUpdatedBy(user.username());
        r.setUpdatedAt(support.now());
        requirements.save(r);
        audit.record("IMMUNISATION_REQUIREMENT_PUT", "IMMUNISATION_REQUIREMENT", id.toString(), AuditService.Outcome.SUCCESS, cmd.vaccineCode());
        return view(r);
    }

    // ------------------------------------------------------------------ status

    public record TitreView(BigDecimal value, boolean protective, LocalDate testedOn) {
    }

    public record VaccineStatus(String vaccineCode, String vaccineName, String status, Integer dosesGiven, Integer dosesRequired,
                                LocalDate lastDoseOn, LocalDate nextDueOn, Boolean mandatory, TitreView titre) {
    }

    public record DueRow(String employeeId, String displayName, String vaccineCode, String vaccineName, int dosesGiven, int dosesRequired,
                         LocalDate nextDueOn, String status) {
    }

    /** Requirements that apply to an employee (job code or a hazard of the job), one per vaccine (mandatory first). */
    Map<String, ImmunisationRequirement> applicable(Employee e, List<ImmunisationRequirement> all, Map<String, JobCode> jobs) {
        JobCode j = e.getJobCode() == null ? null : jobs.get(e.getJobCode());
        List<String> hazards = j == null ? List.of() : SurveillanceSupport.csv(j.getHazardCodes());
        Map<String, ImmunisationRequirement> out = new LinkedHashMap<>();
        all.stream().filter(ImmunisationRequirement::isActive)
                .filter(r -> r.getJobCode() != null && r.getJobCode().equals(e.getJobCode()) || r.getHazardCode() != null && hazards.contains(r.getHazardCode()))
                .sorted(Comparator.comparing((ImmunisationRequirement r) -> !r.isMandatory()).thenComparing(ImmunisationRequirement::getVaccineCode))
                .forEach(r -> out.putIfAbsent(r.getVaccineCode(), r));
        return out;
    }

    List<VaccineStatus> statuses(Employee e, List<ImmunisationRequirement> all, Map<String, JobCode> jobs, List<VaccineAdministration> doses,
                                 List<Titre> titreList, LocalDate today) {
        Map<String, ImmunisationRequirement> req = applicable(e, all, jobs);
        List<VaccineStatus> out = new ArrayList<>();
        for (ImmunisationRequirement r : req.values()) {
            out.add(status(r, doses, titreList, today));
        }
        // vaccines given without a requirement (e.g. travel) are reported as COMPLETE records
        doses.stream().map(VaccineAdministration::getVaccineCode).distinct().filter(v -> !req.containsKey(v)).forEach(v -> {
            List<VaccineAdministration> given = doses.stream().filter(d -> d.getVaccineCode().equals(v)).toList();
            LocalDate last = given.stream().map(d -> date(d.getAdministeredAt())).max(LocalDate::compareTo).orElse(null);
            out.add(new VaccineStatus(v, VACCINES.getOrDefault(v, v), "COMPLETE", given.size(), null, last, null, false, null));
        });
        return out;
    }

    VaccineStatus status(ImmunisationRequirement r, List<VaccineAdministration> doses, List<Titre> titreList, LocalDate today) {
        List<ImmunisationRules.Dose> given = doses.stream().filter(d -> d.getVaccineCode().equals(r.getVaccineCode()))
                .map(d -> new ImmunisationRules.Dose(d.getDoseNumber(), date(d.getAdministeredAt()))).toList();
        Titre latest = titreList.stream().filter(t -> ImmunisationRules.titreFor(r.getVaccineCode(), t.getAntigen()))
                .max(Comparator.comparing(Titre::getTestedOn)).orElse(null);
        ImmunisationRules.Status s = ImmunisationRules.evaluate(new ImmunisationRules.Requirement(r.getVaccineCode(), r.getDoses(),
                ImmunisationRules.schedule(r.getScheduleMonths()), r.getBoosterMonths(), r.isTitreRequired()), given,
                latest == null ? null : new ImmunisationRules.Titre(latest.isProtective(), latest.getTestedOn()), today);
        return new VaccineStatus(r.getVaccineCode(), r.getVaccineName(), s.status(), s.dosesGiven(), r.getDoses(), s.lastDoseOn(), s.nextDueOn(),
                r.isMandatory(), latest == null ? null : new TitreView(latest.getValue(), latest.isProtective(), latest.getTestedOn()));
    }

    static LocalDate date(Instant at) {
        return LocalDate.ofInstant(at, ZoneOffset.UTC);
    }

    @Transactional(readOnly = true)
    public List<DueRow> due(CurrentUser user, String vaccine, String subsidiary, String department) {
        LocalDate today = support.today();
        List<ImmunisationRequirement> all = requirements.findByActiveTrue();
        Map<String, JobCode> jobs = support.jobCodes();
        List<Employee> population = support.activeEmployees().stream()
                .filter(e -> subsidiary == null || subsidiary.equals(e.getSubsidiaryCode()))
                .filter(e -> department == null || department.equals(e.getDepartmentCode())).toList();
        Map<String, List<VaccineAdministration>> doses = new HashMap<>();
        Map<String, List<Titre>> titreMap = new HashMap<>();
        List<String> ids = population.stream().map(Employee::getEmployeeId).toList();
        if (!ids.isEmpty()) {
            administrations.findByEmployeeIdInOrderByAdministeredAtAsc(ids).forEach(a -> doses.computeIfAbsent(a.getEmployeeId(), k -> new ArrayList<>()).add(a));
            titres.findByEmployeeIdInOrderByTestedOnDesc(ids).forEach(t -> titreMap.computeIfAbsent(t.getEmployeeId(), k -> new ArrayList<>()).add(t));
        }
        List<DueRow> out = new ArrayList<>();
        for (Employee e : population) {
            for (ImmunisationRequirement r : applicable(e, all, jobs).values()) {
                if (vaccine != null && !vaccine.equals(r.getVaccineCode()) || !r.isMandatory() && vaccine == null) {
                    continue;
                }
                VaccineStatus s = status(r, doses.getOrDefault(e.getEmployeeId(), List.of()), titreMap.getOrDefault(e.getEmployeeId(), List.of()), today);
                if (!"COMPLETE".equals(s.status())) {
                    out.add(new DueRow(e.getEmployeeId(), e.getDisplayName(), r.getVaccineCode(), r.getVaccineName(), s.dosesGiven(), r.getDoses(),
                            s.nextDueOn(), s.status()));
                }
            }
            if (out.size() >= 5000) {
                break;
            }
        }
        out.sort(Comparator.comparing((DueRow r) -> r.nextDueOn() == null ? LocalDate.MIN : r.nextDueOn()).thenComparing(DueRow::displayName));
        audit.record("IMMUNISATION_DUE_LIST", "IMMUNISATION", null, AuditService.Outcome.SUCCESS, "rows=" + out.size());
        return out;
    }

    @Transactional
    public List<VaccineStatus> employee(CurrentUser user, String employeeId) {
        LocalDate today = support.today();
        if (user.hasAnyRole(Roles.OH_TEAM)) {
            Employee e = records.requireClinical(user, employeeId, DataClassification.CLINICAL, AccessPolicy.Action.READ, "IMMUNISATION_READ");
            List<VaccineStatus> out = statuses(e, requirements.findByActiveTrue(), support.jobCodes(),
                    administrations.findByEmployeeIdOrderByAdministeredAtAsc(employeeId), titres.findByEmployeeIdOrderByTestedOnDesc(employeeId), today);
            audit.record("IMMUNISATION_READ", "EMPLOYEE", employeeId, AuditService.Outcome.SUCCESS, "rows=" + out.size());
            return out;
        }
        // catering hygiene: food handlers only, status and dates only
        Employee e = support.employee(employeeId).orElse(null);
        if (!user.hasRole(Roles.HYGIENE_MANAGER) || e == null || !support.isFoodHandler(e)) {
            audit.record("IMMUNISATION_READ", "EMPLOYEE", employeeId, AuditService.Outcome.DENIED, "Not a food handler / no OH role");
            throw ApiException.notFound("Employee");
        }
        List<VaccineStatus> out = statuses(e, requirements.findByActiveTrue(), support.jobCodes(),
                administrations.findByEmployeeIdOrderByAdministeredAtAsc(employeeId), titres.findByEmployeeIdOrderByTestedOnDesc(employeeId), today)
                .stream().map(s -> new VaccineStatus(s.vaccineCode(), s.vaccineName(), statusOnly(s.status()), null, null, null, s.nextDueOn(), null, null))
                .toList();
        disclosures.record(employeeId, "HYGIENE_MANAGER", user.displayName() == null ? user.username() : user.displayName(), "IMMUNISATION_STATUS",
                employeeId, "Food handler immunisation status", "FITNESS_FOR_WORK", null, user.username());
        audit.record("IMMUNISATION_STATUS_READ", "EMPLOYEE", employeeId, AuditService.Outcome.SUCCESS, "status-only rows=" + out.size());
        return out;
    }

    /** Non-OH readers never learn that a titre is missing – only whether the requirement is met. */
    static String statusOnly(String status) {
        return "TITRE_NEEDED".equals(status) ? "DUE" : status;
    }

    @Transactional(readOnly = true)
    public List<VaccineStatus> mine(String employeeId) {
        Employee e = support.employee(employeeId).orElse(null);
        if (e == null) {
            return List.of();
        }
        return statuses(e, requirements.findByActiveTrue(), support.jobCodes(), administrations.findByEmployeeIdOrderByAdministeredAtAsc(employeeId),
                titres.findByEmployeeIdOrderByTestedOnDesc(employeeId), support.today()).stream()
                .map(s -> new VaccineStatus(s.vaccineCode(), s.vaccineName(), s.status(), s.dosesGiven(), s.dosesRequired(), s.lastDoseOn(),
                        s.nextDueOn(), s.mandatory(), null))
                .toList();
    }

    // ------------------------------------------------------------------ administrations

    public record AdministrationCommand(@Pattern(regexp = "[A-Za-z0-9_\\-]{1,64}") String employeeId,
                                        @Pattern(regexp = "[A-Za-z0-9_\\-]{1,64}") String badgeId,
                                        @NotBlank @Pattern(regexp = "[A-Z0-9_]{2,40}") String vaccineCode,
                                        @NotNull @Min(1) @Max(10) Integer doseNumber, @Pattern(regexp = "[A-Za-z0-9\\-./]{1,20}") String lotNumber,
                                        @Size(max = 120) String lotBarcode, LocalDate lotExpiry,
                                        @NotBlank @Pattern(regexp = "LEFT_DELTOID|RIGHT_DELTOID|LEFT_THIGH|RIGHT_THIGH|ORAL") String site,
                                        @NotBlank @Pattern(regexp = "IM|SC|ORAL|ID") String route, Boolean consentConfirmed, UUID campaignId,
                                        Instant administeredAt) {
    }

    public record AdministrationView(UUID id, String employeeId, String vaccineCode, String vaccineName, int doseNumber, String lotNumber,
                                     LocalDate lotExpiry, String site, String route, Instant administeredAt, UUID recordId) {
    }

    record Lot(String number, LocalDate expiry) {
    }

    Lot lot(String lotNumber, String lotBarcode, LocalDate lotExpiry) {
        if (lotBarcode != null && !lotBarcode.isBlank()) {
            Gs1Lot.Lot parsed = Gs1Lot.parse(lotBarcode);
            return new Lot(parsed.lotNumber(), parsed.expiry() != null ? parsed.expiry() : lotExpiry);
        }
        if (lotNumber == null || lotNumber.isBlank()) {
            throw ApiException.badRequest("lot_required", "Enter or scan the lot number");
        }
        return new Lot(lotNumber.strip(), lotExpiry);
    }

    String vaccineName(String code) {
        String name = VACCINES.get(code);
        if (name != null) {
            return name;
        }
        return requirements.findByActiveTrue().stream().filter(r -> r.getVaccineCode().equals(code)).map(ImmunisationRequirement::getVaccineName)
                .findFirst().orElseThrow(() -> ApiException.badRequest("unknown_vaccine", "Unknown vaccine code"));
    }

    Employee resolve(String employeeId, String badgeId) {
        if (employeeId != null) {
            return support.employee(employeeId).orElse(null);
        }
        return support.byBadge(badgeId).orElse(null);
    }

    @Transactional
    public AdministrationView administer(CurrentUser user, AdministrationCommand cmd) {
        if (cmd.employeeId() == null && cmd.badgeId() == null) {
            throw ApiException.badRequest("employee_required", "Give the employee id or scan the badge");
        }
        Employee e = resolve(cmd.employeeId(), cmd.badgeId());
        if (e == null) {
            throw ApiException.notFound("Employee");
        }
        records.requireClinical(user, e.getEmployeeId(), DataClassification.CLINICAL, AccessPolicy.Action.CREATE, "IMMUNISATION_ADMINISTER");
        if (!Boolean.TRUE.equals(cmd.consentConfirmed())) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "consent_required", "Confirm the employee's consent before vaccinating");
        }
        String name = vaccineName(cmd.vaccineCode());
        Lot lot = lot(cmd.lotNumber(), cmd.lotBarcode(), cmd.lotExpiry());
        Instant at = cmd.administeredAt() == null ? support.now() : cmd.administeredAt();
        if (at.isAfter(support.now().plusSeconds(300)) || at.isBefore(support.now().minusSeconds(366L * 24 * 3600))) {
            throw ApiException.badRequest("invalid_administered_at", "The administration time must be within the last year and not in the future");
        }
        if (lot.expiry() != null && lot.expiry().isBefore(date(at))) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "lot_expired", "The vaccine lot has expired – do not administer");
        }
        if (administrations.existsByEmployeeIdAndVaccineCodeAndDoseNumber(e.getEmployeeId(), cmd.vaccineCode(), cmd.doseNumber())) {
            throw ApiException.conflict("duplicate_dose", "This dose is already recorded");
        }
        VaccineAdministration a = save(user.username(), e, cmd.vaccineCode(), name, cmd.doseNumber(), lot, cmd.site(), cmd.route(),
                cmd.campaignId(), at);
        audit.record("IMMUNISATION_ADMINISTER", "EMPLOYEE", e.getEmployeeId(), AuditService.Outcome.SUCCESS,
                cmd.vaccineCode() + " dose " + cmd.doseNumber());
        return view(a, name);
    }

    private VaccineAdministration save(String actor, Employee e, String vaccineCode, String name, int dose, Lot lot, String site, String route,
                                       UUID campaignId, Instant at) {
        Instant now = support.now();
        VaccineAdministration a = new VaccineAdministration();
        a.setId(UUID.randomUUID());
        a.setEmployeeId(e.getEmployeeId());
        a.setVaccineCode(vaccineCode);
        a.setDoseNumber(dose);
        a.setLotNumber(lot.number());
        a.setLotExpiry(lot.expiry());
        a.setBodySite(site);
        a.setRoute(route);
        a.setCampaignId(campaignId);
        a.setAdministeredAt(at);
        a.setConsentConfirmed(true);
        a.setRecordedBy(actor);
        a.setRecordedAt(now);
        // mirror into the E03 immunisation record (FHIR Immunization)
        Optional<ImmunisationRequirement> req = applicable(e, requirements.findByActiveTrue(), support.jobCodes()).values().stream()
                .filter(r -> r.getVaccineCode().equals(vaccineCode)).findFirst();
        Immunisation i = new Immunisation();
        i.setId(UUID.randomUUID());
        i.setEmployeeId(e.getEmployeeId());
        i.setVaccine(name);
        i.setCodeSystem("LOCAL");
        i.setCode(vaccineCode);
        i.setAdministeredOn(date(at));
        i.setDoseNumber(dose);
        i.setLotNumber(lot.number());
        i.setStatus(req.map(r -> dose >= r.getDoses() ? "COMPLETE" : "PARTIAL").orElse("COMPLETE"));
        req.filter(r -> dose < r.getDoses()).ifPresent(r -> {
            List<Integer> s = ImmunisationRules.schedule(r.getScheduleMonths());
            i.setNextDueDate(dose < s.size() && dose >= 1 ? date(at).plusMonths(Math.max(1, s.get(dose) - s.get(dose - 1))) : date(at).plusMonths(1));
        });
        i.setRecordedBy(actor);
        i.setRecordedAt(now);
        recordImmunisations.save(i);
        a.setRecordId(i.getId());
        administrations.save(a);
        return a;
    }

    static AdministrationView view(VaccineAdministration a, String name) {
        return new AdministrationView(a.getId(), a.getEmployeeId(), a.getVaccineCode(), name, a.getDoseNumber(), a.getLotNumber(), a.getLotExpiry(),
                a.getBodySite(), a.getRoute(), a.getAdministeredAt(), a.getRecordId());
    }

    public record BatchEntry(@Pattern(regexp = "[A-Za-z0-9_\\-]{1,64}") String employeeId, @Pattern(regexp = "[A-Za-z0-9_\\-]{1,64}") String badgeId,
                             Boolean consentConfirmed, @Min(1) @Max(10) Integer doseNumber) {
    }

    public record BatchCommand(@NotBlank @Pattern(regexp = "[A-Z0-9_]{2,40}") String vaccineCode,
                               @Pattern(regexp = "[A-Za-z0-9\\-./]{1,20}") String lotNumber, @Size(max = 120) String lotBarcode, LocalDate lotExpiry,
                               @NotBlank @Pattern(regexp = "LEFT_DELTOID|RIGHT_DELTOID|LEFT_THIGH|RIGHT_THIGH|ORAL") String site,
                               @NotBlank @Pattern(regexp = "IM|SC|ORAL|ID") String route, UUID campaignId,
                               @NotEmpty @Size(max = MAX_BATCH) List<@Valid BatchEntry> entries) {
    }

    public record Rejected(String ref, String reason) {
    }

    public record BatchResult(int recorded, List<Rejected> rejected) {
    }

    @Transactional
    public BatchResult batch(CurrentUser user, BatchCommand cmd) {
        String name = vaccineName(cmd.vaccineCode());
        Lot lot = lot(cmd.lotNumber(), cmd.lotBarcode(), cmd.lotExpiry());
        Instant at = support.now();
        if (lot.expiry() != null && lot.expiry().isBefore(date(at))) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "lot_expired", "The vaccine lot has expired – do not administer");
        }
        int recorded = 0;
        List<Rejected> rejected = new ArrayList<>();
        Set<String> seen = new java.util.HashSet<>();
        for (BatchEntry entry : cmd.entries()) {
            String ref = entry.employeeId() != null ? entry.employeeId() : entry.badgeId() == null ? "?" : entry.badgeId();
            if (entry.employeeId() == null && entry.badgeId() == null) {
                rejected.add(new Rejected(ref, "employee_required"));
                continue;
            }
            Employee e = resolve(entry.employeeId(), entry.badgeId());
            if (e == null || !records.canSeeClinical(user, e, DataClassification.CLINICAL)) {
                rejected.add(new Rejected(ref, "unknown_employee"));
                continue;
            }
            if (!Boolean.TRUE.equals(entry.consentConfirmed())) {
                rejected.add(new Rejected(ref, "consent_required"));
                continue;
            }
            if (!seen.add(e.getEmployeeId())) {
                rejected.add(new Rejected(ref, "duplicate_entry"));
                continue;
            }
            int dose = entry.doseNumber() != null ? entry.doseNumber()
                    : (int) administrations.findByEmployeeIdOrderByAdministeredAtAsc(e.getEmployeeId()).stream()
                            .filter(a -> a.getVaccineCode().equals(cmd.vaccineCode())).count() + 1;
            if (dose > 10 || administrations.existsByEmployeeIdAndVaccineCodeAndDoseNumber(e.getEmployeeId(), cmd.vaccineCode(), dose)) {
                rejected.add(new Rejected(ref, "duplicate_dose"));
                continue;
            }
            save(user.username(), e, cmd.vaccineCode(), name, dose, lot, cmd.site(), cmd.route(), cmd.campaignId(), at);
            recorded++;
        }
        audit.record("IMMUNISATION_BATCH", "IMMUNISATION", cmd.campaignId() == null ? null : cmd.campaignId().toString(),
                AuditService.Outcome.SUCCESS, cmd.vaccineCode() + " recorded=" + recorded + " rejected=" + rejected.size());
        return new BatchResult(recorded, rejected);
    }

    public record TitreCommand(@NotBlank @Pattern(regexp = "[A-Za-z0-9_\\-]{1,64}") String employeeId,
                               @NotBlank @Pattern(regexp = "[A-Z0-9_]{2,40}") String antigen, @NotNull BigDecimal value,
                               @NotBlank @Size(max = 30) String unit, @NotNull Boolean protective, @NotNull LocalDate testedOn) {
    }

    public record TitreResult(UUID id, String employeeId, String antigen, BigDecimal value, String unit, boolean protective, LocalDate testedOn) {
    }

    @Transactional
    public TitreResult titre(CurrentUser user, TitreCommand cmd) {
        records.requireClinical(user, cmd.employeeId(), DataClassification.CLINICAL, AccessPolicy.Action.CREATE, "IMMUNISATION_TITRE");
        if (cmd.testedOn().isAfter(support.today()) || cmd.value().signum() < 0 || cmd.value().compareTo(BigDecimal.valueOf(1_000_000)) > 0) {
            throw ApiException.badRequest("invalid_titre", "Invalid titre value or date");
        }
        Titre t = new Titre();
        t.setId(UUID.randomUUID());
        t.setEmployeeId(cmd.employeeId());
        t.setAntigen(cmd.antigen());
        t.setValue(cmd.value());
        t.setUnit(cmd.unit().strip());
        t.setProtective(cmd.protective());
        t.setTestedOn(cmd.testedOn());
        t.setRecordedBy(user.username());
        t.setRecordedAt(support.now());
        titres.save(t);
        audit.record("IMMUNISATION_TITRE", "EMPLOYEE", cmd.employeeId(), AuditService.Outcome.SUCCESS, cmd.antigen());
        return new TitreResult(t.getId(), t.getEmployeeId(), t.getAntigen(), t.getValue(), t.getUnit(), t.isProtective(), t.getTestedOn());
    }

    // ------------------------------------------------------------------ certificate & reminders

    @Transactional
    public DocumentService.DocumentView certificate(CurrentUser user) {
        if (user.employeeId() == null || !user.hasRole(Roles.EMPLOYEE)) {
            throw ApiException.forbidden("Employees only");
        }
        Employee e = support.requireEmployee(user.employeeId());
        List<VaccineAdministration> doses = administrations.findByEmployeeIdOrderByAdministeredAtAsc(e.getEmployeeId());
        if (doses.isEmpty()) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "no_vaccinations", "No vaccinations are recorded by Occupational Health");
        }
        StringBuilder sb = new StringBuilder();
        for (VaccineAdministration d : doses) {
            if (sb.length() > 3500) {
                sb.append("…");
                break;
            }
            sb.append(sb.isEmpty() ? "" : "; ").append(VACCINES.getOrDefault(d.getVaccineCode(), d.getVaccineCode())).append(" dose ")
                    .append(d.getDoseNumber()).append(" on ").append(date(d.getAdministeredAt())).append(" (lot ").append(d.getLotNumber()).append(')');
        }
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("employeeName", e.getDisplayName());
        fields.put("employeeId", e.getEmployeeId());
        fields.put("vaccinations", sb.toString());
        fields.put("issuedOn", support.today().toString());
        fields.put("issuedBy", "Occupational Health");
        DocumentService.DocumentView doc = documents.generateSigned(user.username(), "VACCINATION_CERTIFICATE", e.getEmployeeId(), null, fields);
        documents.shareAsSystem(user.username(), doc.id(), DocumentShare.Target.EMPLOYEE);
        audit.record("IMMUNISATION_CERTIFICATE", "EMPLOYEE", e.getEmployeeId(), AuditService.Outcome.SUCCESS, "document=" + doc.id());
        return doc;
    }

    /** Nightly: content-free {@code immunisation.due} reminder for mandatory vaccinations due / overdue (at most every 30 days). */
    @Transactional
    public int remind(LocalDate today) {
        List<ImmunisationRequirement> all = requirements.findByActiveTrue();
        Map<String, JobCode> jobs = support.jobCodes();
        Map<String, LocalDate> lastSent = new HashMap<>();
        jdbc.query("select employee_id, vaccine_code, last_sent_on from surveillance.immunisation_reminder",
                (org.springframework.jdbc.core.RowCallbackHandler) rs -> lastSent.put(rs.getString(1) + "|" + rs.getString(2),
                        rs.getDate(3).toLocalDate()));
        int sent = 0;
        for (Employee e : support.activeEmployees()) {
            Map<String, ImmunisationRequirement> req = applicable(e, all, jobs);
            if (req.isEmpty()) {
                continue;
            }
            List<VaccineAdministration> doses = administrations.findByEmployeeIdOrderByAdministeredAtAsc(e.getEmployeeId());
            List<Titre> ts = titres.findByEmployeeIdOrderByTestedOnDesc(e.getEmployeeId());
            for (ImmunisationRequirement r : req.values()) {
                if (!r.isMandatory()) {
                    continue;
                }
                VaccineStatus s = status(r, doses, ts, today);
                boolean due = "OVERDUE".equals(s.status()) || "DUE".equals(s.status()) && (s.nextDueOn() == null || !s.nextDueOn().isAfter(today.plusDays(30)));
                LocalDate last = lastSent.get(e.getEmployeeId() + "|" + r.getVaccineCode());
                if (!due || last != null && last.isAfter(today.minusDays(30))) {
                    continue;
                }
                support.notifyEmployee(e.getEmployeeId(), "immunisation.due",
                        Map.of("dueDate", (s.nextDueOn() == null || s.nextDueOn().isBefore(today) ? today : s.nextDueOn()).toString()));
                jdbc.update("""
                        insert into surveillance.immunisation_reminder (employee_id, vaccine_code, last_sent_on) values (?, ?, ?)
                        on conflict (employee_id, vaccine_code) do update set last_sent_on = excluded.last_sent_on""",
                        e.getEmployeeId(), r.getVaccineCode(), java.sql.Date.valueOf(today));
                sent++;
            }
        }
        return sent;
    }
}
