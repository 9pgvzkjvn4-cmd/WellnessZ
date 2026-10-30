package com.wellnessz.e11.campaign;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.wellnessz.casekernel.support.AppointmentBooking;
import com.wellnessz.casekernel.support.EmployeeRecordAccess;
import com.wellnessz.common.error.ApiException;
import com.wellnessz.common.model.DataClassification;
import com.wellnessz.e07.immunisation.ImmunisationService;
import com.wellnessz.e07.model.VaccineAdministrationRepository;
import com.wellnessz.e07.support.Booker;
import com.wellnessz.e11.model.AdverseEvent;
import com.wellnessz.e11.model.Campaign;
import com.wellnessz.e11.model.Decline;
import com.wellnessz.e11.model.E11AdverseEventRepository;
import com.wellnessz.e11.model.E11CampaignRepository;
import com.wellnessz.e11.model.E11DeclineRepository;
import com.wellnessz.e11.model.E11InvitationRepository;
import com.wellnessz.e11.model.Invitation;
import com.wellnessz.e11.support.TravelHealthSupport;
import com.wellnessz.e11.travel.TravelRecommendations;
import com.wellnessz.masterdata.ClinicSiteRepository;
import com.wellnessz.masterdata.Employee;
import com.wellnessz.security.abac.AccessPolicy;
import com.wellnessz.security.audit.AuditService;
import com.wellnessz.security.identity.CurrentUser;

import tools.jackson.core.type.TypeReference;

/**
 * Vaccination campaign manager (E11-F04): campaign definition with eligibility, invitations ({@code campaign.invitation},
 * booking through the {@link AppointmentBooking} SPI), fast administration entry recorded in the E07 immunisation registry with
 * the campaign id (GS1 lot scanning), declines, adverse events and uptake by subsidiary / department with small-cell
 * suppression. Employees see their invitations and respond ACCEPT / DECLINE.
 */
@Service
public class VaccinationCampaignService {

    public static final String PLANNED = "PLANNED";
    public static final String ACTIVE = "ACTIVE";
    public static final String CLOSED = "CLOSED";
    static final String SOURCE_TYPE = "VACCINATION_CAMPAIGN";

    private final E11CampaignRepository campaigns;
    private final E11InvitationRepository invitations;
    private final E11DeclineRepository declines;
    private final E11AdverseEventRepository adverseEvents;
    private final ImmunisationService immunisation;
    private final VaccineAdministrationRepository administrations;
    private final ClinicSiteRepository sites;
    private final Booker booker;
    private final EmployeeRecordAccess records;
    private final TravelHealthSupport support;
    private final JdbcTemplate jdbc;
    private final AuditService audit;

    public VaccinationCampaignService(E11CampaignRepository campaigns, E11InvitationRepository invitations, E11DeclineRepository declines,
                                      E11AdverseEventRepository adverseEvents, ImmunisationService immunisation,
                                      VaccineAdministrationRepository administrations, ClinicSiteRepository sites, Booker booker,
                                      EmployeeRecordAccess records, TravelHealthSupport support, JdbcTemplate jdbc, AuditService audit) {
        this.campaigns = campaigns;
        this.invitations = invitations;
        this.declines = declines;
        this.adverseEvents = adverseEvents;
        this.immunisation = immunisation;
        this.administrations = administrations;
        this.sites = sites;
        this.booker = booker;
        this.records = records;
        this.support = support;
        this.jdbc = jdbc;
        this.audit = audit;
    }

    // ------------------------------------------------------------------ definition

    public record Eligibility(Boolean all, @Size(max = 50) List<@NotNull @Pattern(regexp = "[A-Za-z0-9_\\-]{1,64}") String> jobCodes,
                              @Size(max = 50) List<@NotNull @Pattern(regexp = "[A-Za-z0-9_\\-]{1,20}") String> subsidiaries) {

        public boolean includes(Employee e) {
            return Boolean.TRUE.equals(all) || jobCodes != null && e.getJobCode() != null && jobCodes.contains(e.getJobCode())
                    || subsidiaries != null && subsidiaries.contains(e.getSubsidiaryCode());
        }
    }

    public record CampaignCommand(@NotBlank @Size(max = 200) String name, @NotBlank @Pattern(regexp = "[A-Z0-9_]{2,40}") String vaccineCode,
                                  @NotNull @Valid Eligibility eligibility, @NotNull LocalDate startDate, @NotNull LocalDate endDate,
                                  @Size(max = 20) List<@NotNull @Pattern(regexp = "[A-Za-z0-9_\\-]{1,40}") String> siteCodes,
                                  @NotNull @DecimalMin("0") @DecimalMax("100") BigDecimal targetUptakePct) {
    }

    public record CampaignView(UUID id, String ref, String name, String vaccineCode, String vaccineName, Eligibility eligibility, LocalDate startDate,
                               LocalDate endDate, List<String> siteCodes, BigDecimal targetUptakePct, String status, int invited,
                               UptakeMaths.Row overall, long adverseEvents, String createdBy, Instant createdAt) {
    }

    public static String status(Campaign c, LocalDate today) {
        if (today.isBefore(c.getStartDate())) {
            return PLANNED;
        }
        return today.isAfter(c.getEndDate()) ? CLOSED : ACTIVE;
    }

    Eligibility eligibility(Campaign c) {
        return support.read(c.getEligibility(), new TypeReference<Eligibility>() {
        }, new Eligibility(false, List.of(), List.of()));
    }

    CampaignView view(Campaign c) {
        Set<String> vaccinated = vaccinated(c.getId());
        Set<String> declined = declined(c.getId());
        Eligibility el = eligibility(c);
        List<UptakeMaths.Person> people = eligible(el).stream()
                .map(e -> new UptakeMaths.Person("ALL", vaccinated.contains(e.getEmployeeId()), declined.contains(e.getEmployeeId()))).toList();
        return new CampaignView(c.getId(), c.getCampaignRef(), c.getName(), c.getVaccineCode(), TravelRecommendations.rule(c.getVaccineCode()).name(),
                el, c.getStartDate(), c.getEndDate(), TravelHealthSupport.csv(c.getSiteCodes()), c.getTargetUptakePct(), status(c, support.today()),
                invitations.findByCampaignId(c.getId()).size(), UptakeMaths.row("ALL", people, support.minGroupSize()),
                adverseEvents.findByCampaignIdOrderByOnsetAtDesc(c.getId()).size(), c.getCreatedBy(), c.getCreatedAt());
    }

    @Transactional
    public CampaignView create(CurrentUser user, CampaignCommand cmd) {
        if (!TravelRecommendations.RULES.containsKey(cmd.vaccineCode())) {
            throw ApiException.badRequest("unknown_vaccine", "Unknown vaccine code");
        }
        Eligibility el = cmd.eligibility();
        boolean any = Boolean.TRUE.equals(el.all()) || el.jobCodes() != null && !el.jobCodes().isEmpty()
                || el.subsidiaries() != null && !el.subsidiaries().isEmpty();
        if (!any) {
            throw ApiException.badRequest("eligibility_required", "Choose all staff, job codes or subsidiaries");
        }
        if (cmd.endDate().isBefore(cmd.startDate()) || cmd.endDate().isBefore(support.today()) || cmd.endDate().isAfter(cmd.startDate().plusYears(1))) {
            throw ApiException.badRequest("invalid_dates", "The campaign must end after it starts, not in the past and within a year");
        }
        List<String> siteCodes = cmd.siteCodes() == null ? List.of() : cmd.siteCodes().stream().distinct().toList();
        for (String s : siteCodes) {
            if (sites.findById(s).filter(com.wellnessz.masterdata.ClinicSite::isActive).isEmpty()) {
                throw ApiException.badRequest("unknown_site", "Unknown clinic site " + s);
            }
        }
        Instant now = support.now();
        Campaign c = Campaign.create();
        c.setCampaignRef(support.nextRef("VC"));
        c.setName(cmd.name().strip());
        c.setVaccineCode(cmd.vaccineCode());
        c.setEligibility(support.write(new Eligibility(Boolean.TRUE.equals(el.all()), el.jobCodes() == null ? List.of() : el.jobCodes(),
                el.subsidiaries() == null ? List.of() : el.subsidiaries())));
        c.setStartDate(cmd.startDate());
        c.setEndDate(cmd.endDate());
        c.setSiteCodes(TravelHealthSupport.joinCsv(siteCodes));
        c.setTargetUptakePct(cmd.targetUptakePct());
        c.setCreatedBy(user.username());
        c.setCreatedAt(now);
        c.setUpdatedAt(now);
        campaigns.save(c);
        audit.record("CAMPAIGN_CREATE", "VACCINATION_CAMPAIGN", c.getId().toString(), AuditService.Outcome.SUCCESS, cmd.vaccineCode());
        return view(c);
    }

    @Transactional(readOnly = true)
    public List<CampaignView> list() {
        return campaigns.findAllByOrderByStartDateDesc().stream().map(this::view).toList();
    }

    @Transactional(readOnly = true)
    public CampaignView get(UUID id) {
        return view(require(id));
    }

    Campaign require(UUID id) {
        return campaigns.findById(id).orElseThrow(() -> ApiException.notFound("Campaign"));
    }

    List<Employee> eligible(Eligibility el) {
        return support.activeEmployees().stream().filter(el::includes).toList();
    }

    Set<String> vaccinated(UUID campaignId) {
        return new HashSet<>(jdbc.queryForList("select distinct employee_id from surveillance.vaccine_administration where campaign_id = ?", String.class,
                campaignId));
    }

    Set<String> declined(UUID campaignId) {
        Set<String> out = new HashSet<>();
        declines.findByCampaignId(campaignId).forEach(d -> out.add(d.getEmployeeId()));
        invitations.findByCampaignId(campaignId).stream().filter(i -> "DECLINE".equals(i.getResponse())).forEach(i -> out.add(i.getEmployeeId()));
        return out;
    }

    // ------------------------------------------------------------------ invitations

    public record InviteCommand(@Size(max = 5000) List<@NotNull @Pattern(regexp = "[A-Za-z0-9_\\-]{1,64}") String> employeeIds) {
    }

    public record InviteResult(int invited, int alreadyInvited, int notEligible) {
    }

    /** Neutral wording – never the vaccine or the disease in a notification. */
    static String reference(Campaign c) {
        return "the vaccination campaign " + c.getCampaignRef();
    }

    @Transactional
    public InviteResult invite(CurrentUser user, UUID id, InviteCommand cmd) {
        Campaign c = require(id);
        if (CLOSED.equals(status(c, support.today()))) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "campaign_closed", "The campaign has ended");
        }
        List<Employee> eligible = eligible(eligibility(c));
        Set<String> eligibleIds = new HashSet<>();
        eligible.forEach(e -> eligibleIds.add(e.getEmployeeId()));
        List<String> wanted = cmd == null || cmd.employeeIds() == null || cmd.employeeIds().isEmpty()
                ? eligible.stream().map(Employee::getEmployeeId).toList() : cmd.employeeIds().stream().distinct().toList();
        Set<String> already = new HashSet<>();
        invitations.findByCampaignId(id).forEach(i -> already.add(i.getEmployeeId()));
        int invited = 0;
        int dup = 0;
        int notEligible = 0;
        Instant now = support.now();
        for (String employeeId : wanted) {
            if (!eligibleIds.contains(employeeId)) {
                notEligible++;
                continue;
            }
            if (!already.add(employeeId)) {
                dup++;
                continue;
            }
            Invitation i = Invitation.create();
            i.setCampaignId(id);
            i.setEmployeeId(employeeId);
            i.setInvitedAt(now);
            invitations.save(i);
            support.notifyEmployee(employeeId, "campaign.invitation", Map.of("reference", reference(c)), "/campaigns");
            invited++;
        }
        audit.record("CAMPAIGN_INVITE", "VACCINATION_CAMPAIGN", id.toString(), AuditService.Outcome.SUCCESS,
                "invited=" + invited + " already=" + dup + " notEligible=" + notEligible);
        return new InviteResult(invited, dup, notEligible);
    }

    // ------------------------------------------------------------------ administrations, declines, adverse events

    public record AdministrationCommand(@Pattern(regexp = "[A-Za-z0-9_\\-]{1,64}") String employeeId,
                                        @Pattern(regexp = "[A-Za-z0-9_\\-]{1,64}") String badgeId,
                                        @Pattern(regexp = "[A-Za-z0-9\\-./]{1,20}") String lotNumber, @Size(max = 120) String lotBarcode,
                                        LocalDate lotExpiry,
                                        @NotBlank @Pattern(regexp = "LEFT_DELTOID|RIGHT_DELTOID|LEFT_THIGH|RIGHT_THIGH|ORAL") String site,
                                        @NotBlank @Pattern(regexp = "IM|SC|ORAL|ID") String route, Boolean consentConfirmed) {
    }

    Employee resolve(String employeeId, String badgeId) {
        if (employeeId == null && badgeId == null) {
            throw ApiException.badRequest("employee_required", "Give the employee id or scan the badge");
        }
        Optional<Employee> e = employeeId != null ? support.employee(employeeId) : support.byBadge(badgeId);
        return e.orElseThrow(() -> ApiException.notFound("Employee"));
    }

    @Transactional
    public ImmunisationService.AdministrationView administer(CurrentUser user, UUID id, AdministrationCommand cmd) {
        Campaign c = require(id);
        Employee e = resolve(cmd.employeeId(), cmd.badgeId());
        records.requireClinical(user, e.getEmployeeId(), DataClassification.CLINICAL, AccessPolicy.Action.CREATE, "CAMPAIGN_ADMINISTER");
        if (!ACTIVE.equals(status(c, support.today()))) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "campaign_not_active", "The campaign is not running today");
        }
        if (!eligibility(c).includes(e)) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "not_eligible", "The employee is not eligible for this campaign");
        }
        if (vaccinated(id).contains(e.getEmployeeId())) {
            throw ApiException.conflict("already_vaccinated", "The employee has already been vaccinated in this campaign");
        }
        if (!Boolean.TRUE.equals(cmd.consentConfirmed())) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "consent_required", "Confirm the employee's consent before vaccinating");
        }
        int dose = (int) administrations.findByEmployeeIdOrderByAdministeredAtAsc(e.getEmployeeId()).stream()
                .filter(a -> a.getVaccineCode().equals(c.getVaccineCode())).count() + 1;
        ImmunisationService.AdministrationView a = immunisation.administer(user, new ImmunisationService.AdministrationCommand(e.getEmployeeId(), null,
                c.getVaccineCode(), Math.min(dose, 10), cmd.lotNumber(), cmd.lotBarcode(), cmd.lotExpiry(), cmd.site(), cmd.route(), Boolean.TRUE, id, null));
        audit.record("CAMPAIGN_ADMINISTER", "VACCINATION_CAMPAIGN", id.toString(), AuditService.Outcome.SUCCESS, "employee=" + e.getEmployeeId());
        return a;
    }

    public record DeclineCommand(@NotBlank @Pattern(regexp = "[A-Za-z0-9_\\-]{1,64}") String employeeId, @Size(max = 500) String reason) {
    }

    public record DeclineView(UUID id, UUID campaignId, String employeeId, String reason, String recordedBy, Instant recordedAt) {
    }

    @Transactional
    public DeclineView decline(CurrentUser user, UUID id, DeclineCommand cmd) {
        require(id);
        records.requireClinical(user, cmd.employeeId(), DataClassification.CLINICAL, AccessPolicy.Action.CREATE, "CAMPAIGN_DECLINE");
        if (vaccinated(id).contains(cmd.employeeId())) {
            throw ApiException.conflict("already_vaccinated", "The employee has already been vaccinated in this campaign");
        }
        if (declines.existsByCampaignIdAndEmployeeId(id, cmd.employeeId())) {
            throw ApiException.conflict("already_declined", "The decline is already recorded");
        }
        Decline d = Decline.create();
        d.setCampaignId(id);
        d.setEmployeeId(cmd.employeeId());
        d.setReason(TravelHealthSupport.clinical("reason", cmd.reason(), 500));
        d.setRecordedBy(user.username());
        d.setRecordedAt(support.now());
        declines.save(d);
        audit.record("CAMPAIGN_DECLINE", "VACCINATION_CAMPAIGN", id.toString(), AuditService.Outcome.SUCCESS, "employee=" + cmd.employeeId());
        return new DeclineView(d.getId(), id, d.getEmployeeId(), d.getReason(), d.getRecordedBy(), d.getRecordedAt());
    }

    public record AdverseEventCommand(@NotBlank @Pattern(regexp = "[A-Za-z0-9_\\-]{1,64}") String employeeId,
                                      @NotBlank @Pattern(regexp = "LOCAL_REACTION|FEVER|SYNCOPE|ANAPHYLAXIS|OTHER") String event,
                                      @NotBlank @Pattern(regexp = "MILD|MODERATE|SEVERE") String severity, @NotNull Instant onsetAt,
                                      @Size(max = 2000) String actions) {
    }

    public record AdverseEventView(UUID id, UUID campaignId, String employeeId, String displayName, UUID administrationId, String event,
                                   String severity, Instant onsetAt, String actions, String recordedBy, Instant recordedAt) {
    }

    AdverseEventView view(AdverseEvent a) {
        return new AdverseEventView(a.getId(), a.getCampaignId(), a.getEmployeeId(), support.displayName(a.getEmployeeId()), a.getAdministrationId(),
                a.getEvent(), a.getSeverity(), a.getOnsetAt(), a.getActions(), a.getRecordedBy(), a.getRecordedAt());
    }

    @Transactional
    public AdverseEventView adverseEvent(CurrentUser user, UUID id, AdverseEventCommand cmd) {
        require(id);
        records.requireClinical(user, cmd.employeeId(), DataClassification.CLINICAL, AccessPolicy.Action.CREATE, "CAMPAIGN_ADVERSE_EVENT");
        List<Map<String, Object>> given = jdbc.queryForList("""
                select id, administered_at from surveillance.vaccine_administration where campaign_id = ? and employee_id = ?
                order by administered_at desc""", id, cmd.employeeId());
        if (given.isEmpty()) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "not_vaccinated_in_campaign",
                    "No vaccination is recorded for this employee in the campaign");
        }
        if (cmd.onsetAt().isAfter(support.now().plusSeconds(300))) {
            throw ApiException.badRequest("invalid_onset", "The onset cannot be in the future");
        }
        AdverseEvent a = AdverseEvent.create();
        a.setCampaignId(id);
        a.setEmployeeId(cmd.employeeId());
        a.setAdministrationId((UUID) given.get(0).get("id"));
        a.setEvent(cmd.event());
        a.setSeverity(cmd.severity());
        a.setOnsetAt(cmd.onsetAt());
        a.setActions(TravelHealthSupport.clinical("actions", cmd.actions(), 2000));
        a.setRecordedBy(user.username());
        a.setRecordedAt(support.now());
        adverseEvents.save(a);
        audit.record("CAMPAIGN_ADVERSE_EVENT", "VACCINATION_CAMPAIGN", id.toString(), AuditService.Outcome.SUCCESS, cmd.event() + " " + cmd.severity());
        return view(a);
    }

    @Transactional(readOnly = true)
    public List<AdverseEventView> adverseEvents(CurrentUser user, UUID id) {
        require(id);
        List<AdverseEventView> out = new ArrayList<>();
        for (AdverseEvent a : adverseEvents.findByCampaignIdOrderByOnsetAtDesc(id)) {
            Optional<Employee> e = support.employee(a.getEmployeeId());
            if (e.isPresent() && records.canSeeClinical(user, e.get(), DataClassification.CLINICAL)) {
                out.add(view(a));
            }
        }
        audit.record("CAMPAIGN_ADVERSE_EVENTS_READ", "VACCINATION_CAMPAIGN", id.toString(), AuditService.Outcome.SUCCESS, "rows=" + out.size());
        return out;
    }

    // ------------------------------------------------------------------ uptake

    @Transactional(readOnly = true)
    public List<UptakeMaths.Row> uptake(UUID id, String groupBy) {
        Campaign c = require(id);
        Set<String> vaccinated = vaccinated(id);
        Set<String> declined = declined(id);
        boolean bySubsidiary = !"DEPARTMENT".equals(groupBy);
        List<UptakeMaths.Person> people = eligible(eligibility(c)).stream()
                .map(e -> new UptakeMaths.Person(bySubsidiary ? e.getSubsidiaryCode() : e.getDepartmentCode(), vaccinated.contains(e.getEmployeeId()),
                        declined.contains(e.getEmployeeId()))).toList();
        List<UptakeMaths.Row> rows = UptakeMaths.rows(people, support.minGroupSize());
        audit.record("CAMPAIGN_UPTAKE", "VACCINATION_CAMPAIGN", id.toString(), AuditService.Outcome.SUCCESS, "groupBy=" + (bySubsidiary ? "SUBSIDIARY" : "DEPARTMENT"));
        return rows;
    }

    // ------------------------------------------------------------------ employee

    public record AppointmentView(UUID appointmentId, Instant startsAt, String siteCode) {
    }

    public record MyCampaign(UUID id, String ref, String name, String vaccineCode, String vaccineName, LocalDate startDate, LocalDate endDate,
                             List<String> siteCodes, String status, boolean invited, String response, boolean vaccinated, AppointmentView appointment) {
    }

    public record ResponseCommand(@NotBlank @Pattern(regexp = "ACCEPT|DECLINE") String response) {
    }

    MyCampaign mine(Campaign c, String employeeId, Optional<Invitation> inv, boolean vaccinated) {
        AppointmentView appt = inv.filter(i -> i.getAppointmentId() != null)
                .map(i -> new AppointmentView(i.getAppointmentId(), i.getAppointmentAt(), i.getAppointmentSite())).orElse(null);
        return new MyCampaign(c.getId(), c.getCampaignRef(), c.getName(), c.getVaccineCode(), TravelRecommendations.rule(c.getVaccineCode()).name(),
                c.getStartDate(), c.getEndDate(), TravelHealthSupport.csv(c.getSiteCodes()), status(c, support.today()), inv.isPresent(),
                inv.map(Invitation::getResponse).orElse(null), vaccinated, appt);
    }

    @Transactional(readOnly = true)
    public List<MyCampaign> mine(CurrentUser user) {
        Employee e = support.employee(user.employeeId()).orElse(null);
        if (e == null) {
            return List.of();
        }
        LocalDate today = support.today();
        Map<UUID, Invitation> invs = new HashMap<>();
        invitations.findByEmployeeId(e.getEmployeeId()).forEach(i -> invs.put(i.getCampaignId(), i));
        List<MyCampaign> out = new ArrayList<>();
        for (Campaign c : campaigns.findAllByOrderByStartDateDesc()) {
            if (c.getEndDate().isBefore(today) || !eligibility(c).includes(e)) {
                continue;
            }
            out.add(mine(c, e.getEmployeeId(), Optional.ofNullable(invs.get(c.getId())), vaccinated(c.getId()).contains(e.getEmployeeId())));
        }
        return out;
    }

    @Transactional
    public MyCampaign respond(CurrentUser user, UUID id, ResponseCommand cmd) {
        Employee e = support.employee(user.employeeId()).orElseThrow(() -> ApiException.notFound("Campaign"));
        Campaign c = campaigns.findById(id).filter(x -> eligibility(x).includes(e)).orElseThrow(() -> ApiException.notFound("Campaign"));
        LocalDate today = support.today();
        if (CLOSED.equals(status(c, today))) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "campaign_closed", "The campaign has ended");
        }
        if (vaccinated(id).contains(e.getEmployeeId())) {
            throw ApiException.conflict("already_vaccinated", "You have already been vaccinated in this campaign");
        }
        Instant now = support.now();
        Invitation i = invitations.findByCampaignIdAndEmployeeId(id, e.getEmployeeId()).orElseGet(() -> {
            Invitation n = Invitation.create();
            n.setCampaignId(id);
            n.setEmployeeId(e.getEmployeeId());
            n.setInvitedAt(now);
            return n;
        });
        i.setResponse(cmd.response());
        i.setRespondedAt(now);
        if ("ACCEPT".equals(cmd.response()) && i.getAppointmentId() == null && booker.available()) {
            List<String> siteCodes = TravelHealthSupport.csv(c.getSiteCodes());
            String site = siteCodes.stream().filter(s -> sites.findById(s).map(cs -> e.getLocationCode() != null
                    && e.getLocationCode().equals(cs.getLocationCode())).orElse(false)).findFirst().orElse(siteCodes.isEmpty() ? null : siteCodes.get(0));
            LocalDate earliest = c.getStartDate().isAfter(today) ? c.getStartDate() : today;
            booker.book(user.username(), new AppointmentBooking.BookingRequest(e.getEmployeeId(), "VACCINATION", site, earliest, null, SOURCE_TYPE,
                    i.getId().toString(), null)).ifPresent(b -> {
                        i.setAppointmentId(b.appointmentId());
                        i.setAppointmentAt(b.startsAt());
                        i.setAppointmentSite(b.siteCode());
                    });
        }
        invitations.save(i);
        audit.record("CAMPAIGN_RESPONSE", "VACCINATION_CAMPAIGN", id.toString(), AuditService.Outcome.SUCCESS, cmd.response());
        return mine(c, e.getEmployeeId(), Optional.of(i), false);
    }
}
