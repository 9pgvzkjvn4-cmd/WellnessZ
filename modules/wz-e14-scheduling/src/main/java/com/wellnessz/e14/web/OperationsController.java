package com.wellnessz.e14.web;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.wellnessz.e14.campaign.CampaignService;
import com.wellnessz.e14.capacity.CapacityService;
import com.wellnessz.e14.queue.ClinicQueueService;
import com.wellnessz.security.identity.CurrentUserProvider;

/** E14-F02 campaigns, E14-F03 check-in and queue, E14-F05 capacity and utilisation. */
@RestController
@RequestMapping("/api/v1/scheduling")
public class OperationsController {

    static final String SITE = CatalogueController.SITE;

    private final CampaignService campaigns;
    private final ClinicQueueService queue;
    private final CapacityService capacity;
    private final CurrentUserProvider users;

    public OperationsController(CampaignService campaigns, ClinicQueueService queue, CapacityService capacity, CurrentUserProvider users) {
        this.campaigns = campaigns;
        this.queue = queue;
        this.capacity = capacity;
        this.users = users;
    }

    // ------------------------------------------------------------------ campaigns

    @GetMapping("/campaigns")
    @PreAuthorize(E14Roles.CAMPAIGN_READ)
    public List<CampaignService.CampaignView> campaigns() {
        return campaigns.list();
    }

    public record CampaignRequest(@NotBlank @Size(max = 200) String name, @NotBlank @Pattern(regexp = "SURVEILLANCE|VACCINATION|MOBILE_CLINIC") String campaignType,
                                  @NotBlank @Pattern(regexp = SITE) String siteCode, @NotBlank @Pattern(regexp = CatalogueController.CODE) String appointmentType,
                                  @NotEmpty @Size(max = CampaignService.MAX_DAYS) List<@NotNull LocalDate> days, @NotNull LocalTime dayStart,
                                  @NotNull LocalTime dayEnd, @NotNull @Min(5) @Max(240) Integer slotMinutes,
                                  @NotNull @Min(1) @Max(CampaignService.MAX_STATIONS) Integer stations,
                                  @Size(max = 50) List<@Pattern(regexp = "[A-Za-z0-9._-]{1,100}") String> deviceKit,
                                  @Size(max = 100) List<@Pattern(regexp = AppointmentController.USERNAME) String> staff,
                                  @Min(0) @Max(100000) Integer targetCount) {
    }

    @PostMapping("/campaigns")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(E14Roles.CAMPAIGN_WRITE)
    public CampaignService.CampaignView create(@Valid @RequestBody CampaignRequest r) {
        return campaigns.create(users.require(), new CampaignService.CampaignCommand(r.name(), r.campaignType(), r.siteCode(), r.appointmentType(),
                r.days(), r.dayStart(), r.dayEnd(), r.slotMinutes(), r.stations(), r.deviceKit(), r.staff(),
                r.targetCount() == null ? 0 : r.targetCount()));
    }

    @GetMapping("/campaigns/{id}")
    @PreAuthorize(E14Roles.CAMPAIGN_READ)
    public CampaignService.CampaignView campaign(@PathVariable UUID id) {
        return campaigns.get(id);
    }

    @PostMapping("/campaigns/{id}/generate-slots")
    @PreAuthorize(E14Roles.CAMPAIGN_OPS)
    public CampaignService.GenerateResult generate(@PathVariable UUID id) {
        return campaigns.generateSlots(users.require(), id);
    }

    public record CampaignBookRequest(@NotEmpty @Size(max = CampaignService.MAX_BULK)
                                      List<@NotBlank @Pattern(regexp = AppointmentController.EMPLOYEE) String> employeeIds) {
    }

    @PostMapping("/campaigns/{id}/book")
    @PreAuthorize(E14Roles.CAMPAIGN_OPS)
    public CampaignService.BookResult book(@PathVariable UUID id, @Valid @RequestBody CampaignBookRequest r) {
        return campaigns.book(users.require(), id, r.employeeIds());
    }

    public record CampaignStatusRequest(@NotBlank @Pattern(regexp = "OPEN|IN_PROGRESS|CLOSED") String status) {
    }

    @PostMapping("/campaigns/{id}/status")
    @PreAuthorize(E14Roles.CAMPAIGN_OPS)
    public CampaignService.CampaignView status(@PathVariable UUID id, @Valid @RequestBody CampaignStatusRequest r) {
        return campaigns.setStatus(users.require(), id, r.status());
    }

    public record CampaignAttendanceItem(@NotNull UUID appointmentId, @NotBlank @Pattern(regexp = "ATTENDED|NO_SHOW") String status) {
    }

    @PostMapping("/campaigns/{id}/attendance")
    @PreAuthorize(E14Roles.FRONT_DESK)
    public Map<String, Integer> attendance(@PathVariable UUID id,
                                           @RequestBody @NotEmpty @Size(max = CampaignService.MAX_BULK) List<@Valid CampaignAttendanceItem> items) {
        return campaigns.attendance(users.require(), id,
                items.stream().map(i -> new CampaignService.AttendanceItem(i.appointmentId(), i.status())).toList());
    }

    // ------------------------------------------------------------------ check-in & queue

    public record CheckInRequest(@NotBlank @Pattern(regexp = SITE) String siteCode, @Pattern(regexp = "[A-Za-z0-9_-]{1,64}") String badgeId,
                                 @Pattern(regexp = AppointmentController.EMPLOYEE) String employeeId, UUID appointmentId) {
    }

    @PostMapping("/check-in")
    @PreAuthorize(E14Roles.FRONT_DESK)
    public ClinicQueueService.CheckInView checkIn(@Valid @RequestBody CheckInRequest r) {
        return queue.checkIn(users.require(), r.siteCode(), r.badgeId(), r.employeeId(), r.appointmentId());
    }

    @GetMapping("/queue")
    @PreAuthorize(E14Roles.FRONT_DESK)
    public ClinicQueueService.QueueBoard board(@RequestParam @Pattern(regexp = SITE) String siteCode) {
        return queue.board(siteCode);
    }

    public record CallNextRequest(@NotBlank @Pattern(regexp = SITE) String siteCode, @Pattern(regexp = AppointmentController.ROOM) String roomCode) {
    }

    @PostMapping("/queue/call-next")
    @PreAuthorize(E14Roles.CLINICIAN)
    public ClinicQueueService.CallView callNext(@Valid @RequestBody CallNextRequest r) {
        return queue.callNext(users.require(), r.siteCode(), r.roomCode());
    }

    // ------------------------------------------------------------------ capacity & utilisation

    @GetMapping("/capacity")
    @PreAuthorize(E14Roles.CAPACITY)
    public List<CapacityService.CapacityRow> capacity(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                                      @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                                      @RequestParam(required = false) @Pattern(regexp = SITE) String siteCode) {
        return capacity.capacity(from, to, siteCode, null);
    }

    public record WhatIfRequest(@Pattern(regexp = SITE) String siteCode, @Min(0) @Max(100) Integer extraClinicianSessionsPerWeek,
                                @Min(0) @Max(365) Integer extraCampaignDays, @NotNull LocalDate from, @NotNull LocalDate to) {
    }

    @PostMapping("/capacity/what-if")
    @PreAuthorize(E14Roles.CAPACITY)
    public List<CapacityService.CapacityRow> whatIf(@Valid @RequestBody WhatIfRequest r) {
        return capacity.capacity(r.from(), r.to(), r.siteCode(), new CapacityService.WhatIf(r.siteCode(),
                r.extraClinicianSessionsPerWeek() == null ? 0 : r.extraClinicianSessionsPerWeek(),
                r.extraCampaignDays() == null ? 0 : r.extraCampaignDays()));
    }

    @GetMapping("/utilisation")
    @PreAuthorize(E14Roles.CAPACITY)
    public List<CapacityService.UtilisationRow> utilisation(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                                            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                                            @RequestParam(required = false, defaultValue = "SITE") @Pattern(regexp = "SITE|CLINICIAN") String groupBy) {
        return capacity.utilisation(from, to, groupBy);
    }
}
