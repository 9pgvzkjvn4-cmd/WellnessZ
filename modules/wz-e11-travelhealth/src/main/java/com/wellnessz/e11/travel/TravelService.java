package com.wellnessz.e11.travel;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
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
import com.wellnessz.e07.immunisation.ImmunisationService;
import com.wellnessz.e07.model.VaccineAdministration;
import com.wellnessz.e07.model.VaccineAdministrationRepository;
import com.wellnessz.e11.model.Consultation;
import com.wellnessz.e11.model.Destination;
import com.wellnessz.e11.model.E11ConsultationRepository;
import com.wellnessz.e11.model.E11DestinationRepository;
import com.wellnessz.e11.support.TravelHealthSupport;
import com.wellnessz.masterdata.DutyAssignment;
import com.wellnessz.masterdata.DutyAssignmentRepository;
import com.wellnessz.masterdata.Employee;
import com.wellnessz.security.abac.AccessPolicy;
import com.wellnessz.security.audit.AuditService;
import com.wellnessz.security.identity.CurrentUser;

import tools.jackson.core.type.TypeReference;

/**
 * Travel & posting health (E11-F01): destination risk content (in-house), consultations that record vaccines in the E07
 * immunisation registry, recommendations from the destinations versus the traveller's immunisation status, the signed
 * travel health certificate shared to the employee, the employee's own travel view and the consultation-due reminder.
 */
@Service
public class TravelService {

    public static final String VACCINE = "[A-Z0-9_]{2,40}";
    static final String SITE = "LEFT_DELTOID|RIGHT_DELTOID|LEFT_THIGH|RIGHT_THIGH|ORAL";
    static final String ROUTE = "IM|SC|ORAL|ID";
    static final String EMPLOYEE_ID = "[A-Za-z0-9_\\-]{1,64}";

    private final E11DestinationRepository destinations;
    private final E11ConsultationRepository consultations;
    private final ImmunisationService immunisation;
    private final VaccineAdministrationRepository administrations;
    private final ImmunisationRepository recordImmunisations;
    private final DutyAssignmentRepository duties;
    private final EmployeeRecordAccess records;
    private final DocumentService documents;
    private final TravelHealthSupport support;
    private final JdbcTemplate jdbc;
    private final AuditService audit;

    public TravelService(E11DestinationRepository destinations, E11ConsultationRepository consultations, ImmunisationService immunisation,
                         VaccineAdministrationRepository administrations, ImmunisationRepository recordImmunisations, DutyAssignmentRepository duties,
                         EmployeeRecordAccess records, DocumentService documents, TravelHealthSupport support, JdbcTemplate jdbc, AuditService audit) {
        this.destinations = destinations;
        this.consultations = consultations;
        this.immunisation = immunisation;
        this.administrations = administrations;
        this.recordImmunisations = recordImmunisations;
        this.duties = duties;
        this.records = records;
        this.documents = documents;
        this.support = support;
        this.jdbc = jdbc;
        this.audit = audit;
    }

    // ------------------------------------------------------------------ destinations

    public record DestinationView(String countryCode, String name, List<String> stationCodes, List<String> risks, List<String> recommendedVaccines,
                                  List<String> prophylaxis, String advice, String source, LocalDate reviewedOn) {
    }

    public record DestinationCommand(@NotBlank @Size(max = 100) String name,
                                     @Size(max = 10) List<@NotNull @Pattern(regexp = "[A-Z]{3}") String> stationCodes,
                                     @NotNull @Size(max = 20) List<@NotBlank @Size(max = 200) String> risks,
                                     @NotNull @Size(max = 20) List<@NotNull @Pattern(regexp = VACCINE) String> recommendedVaccines,
                                     @NotNull @Size(max = 10) List<@NotBlank @Size(max = 300) String> prophylaxis,
                                     @NotBlank @Size(max = 4000) String advice,
                                     @NotBlank @Pattern(regexp = "IN_HOUSE|LICENSED") String source, @NotNull LocalDate reviewedOn) {
    }

    DestinationView view(Destination d) {
        return new DestinationView(d.getCountryCode(), d.getName(), TravelHealthSupport.csv(d.getStationCodes()), support.readList(d.getRisks()),
                support.readList(d.getRecommendedVaccines()), support.readList(d.getProphylaxis()), d.getAdvice(), d.getSource(), d.getReviewedOn());
    }

    /** Employees see the advice only. */
    static DestinationView adviceOnly(DestinationView v) {
        return new DestinationView(v.countryCode(), v.name(), null, null, null, null, v.advice(), v.source(), v.reviewedOn());
    }

    static boolean ohReader(CurrentUser user) {
        return user.hasAnyRole(Roles.OH_TEAM);
    }

    @Transactional(readOnly = true)
    public List<DestinationView> destinations(CurrentUser user) {
        List<DestinationView> all = destinations.findAllByOrderByNameAsc().stream().map(this::view).toList();
        return ohReader(user) ? all : all.stream().map(TravelService::adviceOnly).toList();
    }

    @Transactional(readOnly = true)
    public DestinationView destination(CurrentUser user, String countryCode) {
        DestinationView v = destinations.findById(countryCode).map(this::view).orElseThrow(() -> ApiException.notFound("Destination"));
        return ohReader(user) ? v : adviceOnly(v);
    }

    @Transactional
    public DestinationView putDestination(CurrentUser user, String countryCode, DestinationCommand cmd) {
        for (String v : cmd.recommendedVaccines()) {
            if (!TravelRecommendations.RULES.containsKey(v)) {
                throw ApiException.badRequest("unknown_vaccine", "Unknown vaccine code " + v);
            }
        }
        if (cmd.reviewedOn().isAfter(support.today())) {
            throw ApiException.badRequest("invalid_reviewed_on", "The review date cannot be in the future");
        }
        Destination d = destinations.findById(countryCode).orElseGet(() -> {
            Destination n = new Destination();
            n.setCountryCode(countryCode);
            return n;
        });
        d.setName(cmd.name().strip());
        d.setStationCodes(TravelHealthSupport.joinCsv(cmd.stationCodes() == null ? List.of() : cmd.stationCodes()));
        d.setRisks(support.write(cmd.risks().stream().map(String::strip).toList()));
        d.setRecommendedVaccines(support.write(cmd.recommendedVaccines().stream().distinct().toList()));
        d.setProphylaxis(support.write(cmd.prophylaxis().stream().map(String::strip).toList()));
        d.setAdvice(cmd.advice().strip());
        d.setSource(cmd.source());
        d.setReviewedOn(cmd.reviewedOn());
        d.setUpdatedBy(user.username());
        d.setUpdatedAt(support.now());
        destinations.save(d);
        audit.record("TRAVEL_DESTINATION_PUT", "TRAVEL_DESTINATION", countryCode, AuditService.Outcome.SUCCESS, cmd.source());
        return view(d);
    }

    /** Station (airport) code → destination, from the destinations' station lists. */
    Map<String, Destination> byStation() {
        Map<String, Destination> out = new HashMap<>();
        for (Destination d : destinations.findAll()) {
            TravelHealthSupport.csv(d.getStationCodes()).forEach(s -> out.putIfAbsent(s, d));
        }
        return out;
    }

    // ------------------------------------------------------------------ recommendations

    public record RecommendationsView(String employeeId, List<DestinationView> destinations, List<TravelRecommendations.Recommendation> vaccines,
                                      List<String> prophylaxis, boolean lateConsultation) {
    }

    List<Destination> requireDestinations(List<String> codes) {
        List<Destination> out = new ArrayList<>();
        for (String c : codes.stream().distinct().toList()) {
            out.add(destinations.findById(c).orElseThrow(() -> new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "unknown_destination",
                    "No destination content for " + c)));
        }
        return out;
    }

    /** Immunisation history: the E07 registry plus E03 record entries that were not mirrored from it. */
    List<TravelRecommendations.Dose> history(String employeeId) {
        List<TravelRecommendations.Dose> out = new ArrayList<>();
        Set<UUID> mirrored = new HashSet<>();
        for (VaccineAdministration a : administrations.findByEmployeeIdOrderByAdministeredAtAsc(employeeId)) {
            out.add(new TravelRecommendations.Dose(a.getVaccineCode(), LocalDate.ofInstant(a.getAdministeredAt(), ZoneOffset.UTC)));
            if (a.getRecordId() != null) {
                mirrored.add(a.getRecordId());
            }
        }
        for (Immunisation i : recordImmunisations.findByEmployeeIdOrderByAdministeredOnDesc(employeeId)) {
            if (!mirrored.contains(i.getId()) && i.getCode() != null && i.getAdministeredOn() != null) {
                out.add(new TravelRecommendations.Dose(i.getCode(), i.getAdministeredOn()));
            }
        }
        return out;
    }

    TravelRecommendations.Result evaluate(String employeeId, List<Destination> dests, LocalDate depart, LocalDate ret) {
        List<TravelRecommendations.DestinationInfo> info = dests.stream().map(d -> new TravelRecommendations.DestinationInfo(d.getCountryCode(),
                d.getName(), support.readList(d.getRecommendedVaccines()), support.readList(d.getProphylaxis()))).toList();
        return TravelRecommendations.recommend(info, history(employeeId), support.today(), depart, ret);
    }

    @Transactional(readOnly = true)
    public RecommendationsView recommendations(CurrentUser user, String employeeId, List<String> countryCodes, LocalDate depart, LocalDate ret) {
        records.requireClinical(user, employeeId, DataClassification.CLINICAL, AccessPolicy.Action.READ, "TRAVEL_RECOMMENDATIONS");
        if (countryCodes.isEmpty() || countryCodes.size() > 10) {
            throw ApiException.badRequest("invalid_destinations", "Give one to ten destination country codes");
        }
        List<Destination> dests = requireDestinations(countryCodes);
        TravelRecommendations.Result r = evaluate(employeeId, dests, depart, ret);
        audit.record("TRAVEL_RECOMMENDATIONS", "EMPLOYEE", employeeId, AuditService.Outcome.SUCCESS, String.join(",", countryCodes));
        return new RecommendationsView(employeeId, dests.stream().map(this::view).toList(), r.vaccines(), r.prophylaxis(), r.lateConsultation());
    }

    // ------------------------------------------------------------------ consultations

    public record Trip(@NotEmpty @Size(max = 10) List<@NotNull @Pattern(regexp = "[A-Z]{2}") String> destinations, @NotNull LocalDate departDate,
                       @NotNull LocalDate returnDate, @NotBlank @Pattern(regexp = "LAYOVER|POSTING|BUSINESS") String purpose) {
    }

    public record VaccineGiven(@NotBlank @Pattern(regexp = VACCINE) String vaccineCode, @Min(1) @Max(10) Integer doseNumber,
                               @Pattern(regexp = "[A-Za-z0-9\\-./]{1,20}") String lotNumber, @Size(max = 120) String lotBarcode,
                               @NotBlank @Pattern(regexp = SITE) String site, @NotBlank @Pattern(regexp = ROUTE) String route) {
    }

    public record Prophylaxis(@NotBlank @Size(max = 100) String drug, @NotBlank @Size(max = 300) String regimen, @NotNull LocalDate startDate) {
    }

    public record ConsultationCommand(@NotBlank @Pattern(regexp = EMPLOYEE_ID) String employeeId, @NotNull @Valid Trip trip,
                                      @Size(max = 10) List<@NotNull @Valid VaccineGiven> vaccinesGiven,
                                      @Size(max = 10) List<@NotNull @Valid Prophylaxis> prophylaxis, @Size(max = 4000) String advice) {
    }

    public record GivenView(String vaccineCode, String vaccineName, int doseNumber, String lotNumber, String site, String route, UUID administrationId) {
    }

    public record TripView(List<String> destinations, LocalDate departDate, LocalDate returnDate, String purpose) {
    }

    public record ConsultationView(UUID id, String employeeId, String displayName, TripView trip, List<GivenView> vaccinesGiven,
                                   List<Prophylaxis> prophylaxis, String advice, List<TravelRecommendations.Recommendation> recommendations,
                                   List<String> recommendedProphylaxis, Boolean lateConsultation, UUID certificateDocumentId, String consultedBy,
                                   Instant createdAt) {
    }

    record Snapshot(List<TravelRecommendations.Recommendation> vaccines, List<String> prophylaxis, boolean lateConsultation) {
    }

    @Transactional
    public ConsultationView consult(CurrentUser user, ConsultationCommand cmd) {
        Employee e = records.requireClinical(user, cmd.employeeId(), DataClassification.CLINICAL, AccessPolicy.Action.CREATE, "TRAVEL_CONSULTATION");
        Trip trip = cmd.trip();
        LocalDate today = support.today();
        if (trip.returnDate().isBefore(trip.departDate())) {
            throw ApiException.badRequest("invalid_dates", "returnDate must not be before departDate");
        }
        if (trip.departDate().isBefore(today.minusDays(365)) || trip.returnDate().isAfter(trip.departDate().plusYears(5))) {
            throw ApiException.badRequest("invalid_dates", "The trip dates are out of range");
        }
        List<Destination> dests = requireDestinations(trip.destinations());
        List<Prophylaxis> prophylaxis = cmd.prophylaxis() == null ? List.of() : cmd.prophylaxis();
        // vaccines are recorded in the E07 registry (mirrored into the E03 record); consent is part of the consultation
        List<GivenView> given = new ArrayList<>();
        for (VaccineGiven v : cmd.vaccinesGiven() == null ? List.<VaccineGiven>of() : cmd.vaccinesGiven()) {
            int dose = v.doseNumber() != null ? v.doseNumber()
                    : (int) administrations.findByEmployeeIdOrderByAdministeredAtAsc(e.getEmployeeId()).stream()
                            .filter(a -> a.getVaccineCode().equals(v.vaccineCode())).count() + 1;
            ImmunisationService.AdministrationView a = immunisation.administer(user, new ImmunisationService.AdministrationCommand(e.getEmployeeId(),
                    null, v.vaccineCode(), dose, v.lotNumber(), v.lotBarcode(), null, v.site(), v.route(), Boolean.TRUE, null, null));
            given.add(new GivenView(a.vaccineCode(), a.vaccineName(), a.doseNumber(), a.lotNumber(), a.site(), a.route(), a.id()));
        }
        TravelRecommendations.Result rec = evaluate(e.getEmployeeId(), dests, trip.departDate(), trip.returnDate());
        Consultation c = Consultation.create();
        c.setEmployeeId(e.getEmployeeId());
        c.setDestinations(String.join(",", dests.stream().map(Destination::getCountryCode).toList()));
        c.setDepartDate(trip.departDate());
        c.setReturnDate(trip.returnDate());
        c.setPurpose(trip.purpose());
        c.setVaccinesGiven(support.write(given));
        c.setProphylaxis(prophylaxis.isEmpty() ? null : support.write(prophylaxis));
        c.setAdvice(TravelHealthSupport.clinical("advice", cmd.advice(), 4000));
        c.setRecommendations(support.write(new Snapshot(rec.vaccines(), rec.prophylaxis(), rec.lateConsultation())));
        c.setConsultedBy(user.username());
        c.setCreatedAt(support.now());
        c.setCertificateDocumentId(certificate(user.username(), e, dests, trip, given, prophylaxis, rec));
        consultations.save(c);
        audit.record("TRAVEL_CONSULTATION", "EMPLOYEE", e.getEmployeeId(), AuditService.Outcome.SUCCESS,
                "destinations=" + c.getDestinations() + " vaccines=" + given.size());
        return view(c, e.getDisplayName());
    }

    /** Signed travel health certificate, shared to the employee's portal. */
    UUID certificate(String actor, Employee e, List<Destination> dests, Trip trip, List<GivenView> given, List<Prophylaxis> prophylaxis,
                     TravelRecommendations.Result rec) {
        StringBuilder sb = new StringBuilder();
        for (GivenView g : given) {
            sb.append(sb.isEmpty() ? "" : "; ").append(g.vaccineName()).append(" dose ").append(g.doseNumber()).append(" given ").append(support.today())
                    .append(" (lot ").append(g.lotNumber()).append(')');
        }
        for (TravelRecommendations.Recommendation r : rec.vaccines()) {
            if ("UP_TO_DATE".equals(r.status()) && given.stream().noneMatch(g -> g.vaccineCode().equals(r.vaccineCode()))) {
                sb.append(sb.isEmpty() ? "" : "; ").append(r.vaccineName()).append(" up to date");
            }
        }
        for (Prophylaxis p : prophylaxis) {
            sb.append(sb.isEmpty() ? "" : "; ").append("Prophylaxis: ").append(p.drug()).append(" from ").append(p.startDate());
        }
        if (sb.isEmpty()) {
            sb.append("No vaccinations were required at this consultation");
        }
        String text = sb.length() > 3900 ? sb.substring(0, 3900) + "…" : sb.toString();
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("employeeName", e.getDisplayName());
        fields.put("employeeId", e.getEmployeeId());
        fields.put("destinations", String.join(", ", dests.stream().map(d -> d.getName() + " (" + d.getCountryCode() + ")").toList()));
        fields.put("vaccinations", text);
        fields.put("validUntil", trip.returnDate().toString());
        fields.put("issuedBy", support.staffName(actor));
        DocumentService.DocumentView doc = documents.generateSigned(actor, "TRAVEL_HEALTH_CERTIFICATE", e.getEmployeeId(), null, fields);
        documents.shareAsSystem(actor, doc.id(), DocumentShare.Target.EMPLOYEE);
        return doc.id();
    }

    /**
     * Completes a consultation recorded without a recommendation snapshot / certificate (demo data, data migration): computes
     * the snapshot from the current immunisation status and issues the signed certificate. No-op when both are present.
     */
    @Transactional
    public void completeConsultation(UUID consultationId, String actor) {
        Consultation c = consultations.findById(consultationId).orElse(null);
        Employee e = c == null ? null : support.employee(c.getEmployeeId()).orElse(null);
        if (e == null || c.getCertificateDocumentId() != null && c.getRecommendations() != null) {
            return;
        }
        List<Destination> dests = requireDestinations(TravelHealthSupport.csv(c.getDestinations()));
        TravelRecommendations.Result rec = evaluate(c.getEmployeeId(), dests, c.getDepartDate(), c.getReturnDate());
        if (c.getRecommendations() == null) {
            c.setRecommendations(support.write(new Snapshot(rec.vaccines(), rec.prophylaxis(), false)));
        }
        if (c.getCertificateDocumentId() == null) {
            List<GivenView> given = support.read(c.getVaccinesGiven(), new TypeReference<List<GivenView>>() {
            }, List.of());
            Trip trip = new Trip(TravelHealthSupport.csv(c.getDestinations()), c.getDepartDate(), c.getReturnDate(), c.getPurpose());
            c.setCertificateDocumentId(certificate(actor, e, dests, trip, given, List.of(), rec));
        }
        consultations.save(c);
    }

    ConsultationView view(Consultation c, String displayName) {
        List<GivenView> given = support.read(c.getVaccinesGiven(), new TypeReference<List<GivenView>>() {
        }, List.of());
        List<Prophylaxis> prophylaxis = support.read(c.getProphylaxis(), new TypeReference<List<Prophylaxis>>() {
        }, List.of());
        Snapshot rec = support.read(c.getRecommendations(), new TypeReference<Snapshot>() {
        }, new Snapshot(List.of(), List.of(), false));
        return new ConsultationView(c.getId(), c.getEmployeeId(), displayName, new TripView(TravelHealthSupport.csv(c.getDestinations()),
                c.getDepartDate(), c.getReturnDate(), c.getPurpose()), given, prophylaxis, c.getAdvice(), rec.vaccines(), rec.prophylaxis(),
                rec.lateConsultation(), c.getCertificateDocumentId(), c.getConsultedBy(), c.getCreatedAt());
    }

    @Transactional(readOnly = true)
    public List<ConsultationView> consultations(CurrentUser user, String employeeId) {
        if (employeeId != null) {
            Employee e = records.requireClinical(user, employeeId, DataClassification.CLINICAL, AccessPolicy.Action.READ, "TRAVEL_CONSULTATIONS_READ");
            List<ConsultationView> out = consultations.findByEmployeeIdOrderByCreatedAtDesc(employeeId).stream()
                    .map(c -> view(c, e.getDisplayName())).toList();
            audit.record("TRAVEL_CONSULTATIONS_READ", "EMPLOYEE", employeeId, AuditService.Outcome.SUCCESS, "rows=" + out.size());
            return out;
        }
        List<Consultation> recent = consultations.findTop200ByOrderByCreatedAtDesc();
        Map<String, Employee> people = support.employeesById(recent.stream().map(Consultation::getEmployeeId).distinct().toList());
        List<ConsultationView> out = recent.stream()
                .filter(c -> people.containsKey(c.getEmployeeId()) && records.canSeeClinical(user, people.get(c.getEmployeeId()), DataClassification.CLINICAL))
                .map(c -> view(c, people.get(c.getEmployeeId()).getDisplayName())).toList();
        audit.record("TRAVEL_CONSULTATIONS_READ", "TRAVEL_CONSULTATION", null, AuditService.Outcome.SUCCESS, "rows=" + out.size());
        return out;
    }

    // ------------------------------------------------------------------ employee view

    public record MyConsultation(UUID id, List<String> destinations, LocalDate departDate, LocalDate returnDate, String purpose, Instant createdAt,
                                 UUID certificateDocumentId) {
    }

    public record MyCertificate(UUID documentId, LocalDate issuedOn, List<String> destinations, LocalDate validUntil) {
    }

    public record UpcomingTrip(String dutyRef, LocalDate dutyDate, String destinationCode, String countryCode, String countryName, String advice,
                               List<String> risks) {
    }

    public record MyTravel(List<MyConsultation> consultations, List<MyCertificate> certificates, List<UpcomingTrip> upcoming) {
    }

    @Transactional(readOnly = true)
    public MyTravel mine(CurrentUser user) {
        String employeeId = user.employeeId();
        List<Consultation> mine = consultations.findByEmployeeIdOrderByCreatedAtDesc(employeeId);
        List<MyConsultation> cs = mine.stream().map(c -> new MyConsultation(c.getId(), TravelHealthSupport.csv(c.getDestinations()), c.getDepartDate(),
                c.getReturnDate(), c.getPurpose(), c.getCreatedAt(), c.getCertificateDocumentId())).toList();
        List<MyCertificate> certs = mine.stream().filter(c -> c.getCertificateDocumentId() != null)
                .map(c -> new MyCertificate(c.getCertificateDocumentId(), LocalDate.ofInstant(c.getCreatedAt(), ZoneOffset.UTC),
                        TravelHealthSupport.csv(c.getDestinations()), c.getReturnDate())).toList();
        Instant now = support.now();
        int lookahead = support.configInt("travelhealth.consultation.lookahead-days", 30);
        Map<String, Destination> stations = byStation();
        List<UpcomingTrip> upcoming = new ArrayList<>();
        for (DutyAssignment d : duties.findForEmployee(employeeId, now, now.plusSeconds(lookahead * 86_400L))) {
            if (d.getDestinationCode() == null) {
                continue;
            }
            Optional<Destination> dest = Optional.ofNullable(stations.get(d.getDestinationCode()));
            upcoming.add(new UpcomingTrip(d.getDutyRef(), d.getDutyDate(), d.getDestinationCode(), dest.map(Destination::getCountryCode).orElse(null),
                    dest.map(Destination::getName).orElse(null), dest.map(Destination::getAdvice).orElse(null),
                    dest.map(x -> support.readList(x.getRisks())).orElse(null)));
        }
        upcoming.sort(Comparator.comparing(UpcomingTrip::dutyDate));
        return new MyTravel(cs, certs, upcoming);
    }

    // ------------------------------------------------------------------ consultation-due reminders (daily job)

    /**
     * Crew rostered to a destination in the look-ahead window whose recommended vaccines are not up to date and who had no
     * consultation for that country in the last year receive {@code travel.consultation-due} (at most every 30 days per country).
     */
    @Transactional
    public int remindConsultations(LocalDate today) {
        Instant from = today.atStartOfDay(ZoneOffset.UTC).toInstant();
        int lookahead = support.configInt("travelhealth.consultation.lookahead-days", 30);
        Map<String, Destination> stations = byStation();
        Map<String, LocalDate> lastSent = new HashMap<>();
        jdbc.query("select employee_id, country_code, last_sent_on from travelhealth.consultation_reminder",
                (org.springframework.jdbc.core.RowCallbackHandler) rs -> lastSent.put(rs.getString(1) + "|" + rs.getString(2), rs.getDate(3).toLocalDate()));
        Map<String, DutyAssignment> firstTrip = new LinkedHashMap<>();
        for (Destination d : stations.values().stream().distinct().toList()) {
            for (String station : TravelHealthSupport.csv(d.getStationCodes())) {
                for (DutyAssignment duty : duties.findToDestination(station, from, from.plusSeconds(lookahead * 86_400L))) {
                    firstTrip.merge(duty.getEmployeeId() + "|" + d.getCountryCode(), duty, (a, b) -> a.getStartsAt().isBefore(b.getStartsAt()) ? a : b);
                }
            }
        }
        int sent = 0;
        for (Map.Entry<String, DutyAssignment> entry : firstTrip.entrySet()) {
            String employeeId = entry.getValue().getEmployeeId();
            String country = entry.getKey().substring(entry.getKey().indexOf('|') + 1);
            LocalDate last = lastSent.get(entry.getKey());
            if (last != null && last.isAfter(today.minusDays(30))) {
                continue;
            }
            boolean recentConsultation = consultations.findByEmployeeIdOrderByCreatedAtDesc(employeeId).stream()
                    .anyMatch(c -> TravelHealthSupport.csv(c.getDestinations()).contains(country)
                            && c.getCreatedAt().isAfter(from.minusSeconds(365L * 86_400)));
            if (recentConsultation) {
                continue;
            }
            Destination dest = destinations.findById(country).orElse(null);
            if (dest == null) {
                continue;
            }
            boolean actionNeeded = evaluate(employeeId, List.of(dest), entry.getValue().getDutyDate(), entry.getValue().getDutyDate()).vaccines()
                    .stream().anyMatch(r -> !"UP_TO_DATE".equals(r.status()));
            if (!actionNeeded) {
                continue;
            }
            support.notifyEmployee(employeeId, "travel.consultation-due", Map.of("dueDate", entry.getValue().getDutyDate().toString()), "/travel");
            jdbc.update("""
                    insert into travelhealth.consultation_reminder (employee_id, country_code, last_sent_on) values (?, ?, ?)
                    on conflict (employee_id, country_code) do update set last_sent_on = excluded.last_sent_on""",
                    employeeId, country, java.sql.Date.valueOf(today));
            sent++;
        }
        return sent;
    }
}
