package com.wellnessz.e14.web;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.wellnessz.e14.booking.AppointmentService;
import com.wellnessz.e14.queue.ClinicQueueService;
import com.wellnessz.security.identity.CurrentUserProvider;

/** E14-F01 diary, slots, booking, moves, cancellation, attendance, telehealth, waitlist and employee self-service. */
@RestController
@RequestMapping("/api/v1")
public class AppointmentController {

    static final String SITE = CatalogueController.SITE;
    static final String CODE = CatalogueController.CODE;
    static final String EMPLOYEE = "[A-Za-z0-9_-]{1,64}";
    static final String USERNAME = "[A-Za-z0-9._@-]{1,200}";
    static final String ROOM = "[A-Za-z0-9._-]{1,64}";
    static final String SOURCE_TYPE = "SURVEILLANCE_INVITATION|PRE_EMPLOYMENT|IMMUNISATION|CREW_RENEWAL|ABSENCE_CONTACT|RTW_REVIEW|MANUAL";

    private final AppointmentService appointments;
    private final ClinicQueueService queue;
    private final CurrentUserProvider users;

    public AppointmentController(AppointmentService appointments, ClinicQueueService queue, CurrentUserProvider users) {
        this.appointments = appointments;
        this.queue = queue;
        this.users = users;
    }

    // ------------------------------------------------------------------ diary & slots

    @GetMapping("/scheduling/diary")
    @PreAuthorize(E14Roles.FRONT_DESK)
    public AppointmentService.DiaryView diary(@RequestParam @Pattern(regexp = SITE) String siteCode,
                                              @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
                                              @RequestParam(required = false) @Pattern(regexp = USERNAME) String clinician) {
        return appointments.diary(siteCode, date, clinician);
    }

    @GetMapping("/scheduling/slots")
    @PreAuthorize(E14Roles.BOOKING)
    public List<AppointmentService.SlotView> slots(@RequestParam @Pattern(regexp = CODE) String type,
                                                   @RequestParam @Pattern(regexp = SITE) String siteCode,
                                                   @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                                   @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                                   @RequestParam(required = false) @Pattern(regexp = USERNAME) String clinician) {
        return appointments.slots(type, siteCode, from, to, clinician);
    }

    // ------------------------------------------------------------------ appointments

    public record BookRequest(@NotBlank @Pattern(regexp = EMPLOYEE) String employeeId, @NotBlank @Pattern(regexp = CODE) String appointmentType,
                              @NotBlank @Pattern(regexp = SITE) String siteCode, @NotNull Instant startsAt,
                              @Pattern(regexp = USERNAME) String clinicianUsername, @Pattern(regexp = ROOM) String roomCode,
                              @Pattern(regexp = "[A-Za-z0-9._-]{1,100}") String deviceId, UUID caseId, @Size(max = 500) String adminNote,
                              @Pattern(regexp = SOURCE_TYPE) String sourceType, @Size(max = 200) String sourceRef) {
    }

    @PostMapping("/scheduling/appointments")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(E14Roles.BOOKING)
    public AppointmentService.AppointmentView book(@Valid @RequestBody BookRequest r) {
        return appointments.book(users.require(), new AppointmentService.BookCommand(r.employeeId(), r.appointmentType(), r.siteCode(), r.startsAt(),
                r.clinicianUsername(), r.roomCode(), r.deviceId(), r.caseId(), r.adminNote(), r.sourceType(), r.sourceRef()));
    }

    @GetMapping("/scheduling/appointments")
    @PreAuthorize(E14Roles.FRONT_DESK)
    public List<AppointmentService.AppointmentView> list(@RequestParam(required = false) @Pattern(regexp = SITE) String siteCode,
                                                         @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
                                                         @RequestParam(required = false) @Pattern(regexp = EMPLOYEE) String employeeId,
                                                         @RequestParam(required = false)
                                                         @Pattern(regexp = "BOOKED|CHECKED_IN|IN_PROGRESS|ATTENDED|NO_SHOW|CANCELLED") String status) {
        return appointments.list(siteCode, date, employeeId, status);
    }

    public record MoveRequest(@NotNull Instant startsAt, @Pattern(regexp = USERNAME) String clinicianUsername, @Pattern(regexp = ROOM) String roomCode,
                              @NotNull @Min(0) Long version) {
    }

    @PutMapping("/scheduling/appointments/{id}/move")
    @PreAuthorize(E14Roles.BOOKING)
    public AppointmentService.AppointmentView move(@PathVariable UUID id, @Valid @RequestBody MoveRequest r) {
        return appointments.move(users.require(), id, r.startsAt(), r.clinicianUsername(), r.roomCode(), r.version());
    }

    public record CancelRequest(@NotBlank @Size(max = 500) String reason) {
    }

    @PostMapping("/scheduling/appointments/{id}/cancel")
    @PreAuthorize(E14Roles.BOOKING)
    public AppointmentService.AppointmentView cancel(@PathVariable UUID id, @Valid @RequestBody CancelRequest r) {
        return appointments.cancel(users.require(), id, r.reason());
    }

    public record AttendanceRequest(@NotBlank @Pattern(regexp = "ATTENDED|NO_SHOW") String status) {
    }

    @PostMapping("/scheduling/appointments/{id}/attendance")
    @PreAuthorize(E14Roles.FRONT_DESK)
    public AppointmentService.AppointmentView attendance(@PathVariable UUID id, @Valid @RequestBody AttendanceRequest r) {
        return appointments.attendance(users.require(), id, r.status());
    }

    @PostMapping("/scheduling/appointments/{id}/telehealth")
    @PreAuthorize("isAuthenticated()")
    public AppointmentService.TelehealthView telehealth(@PathVariable UUID id) {
        return appointments.telehealth(users.require(), id);
    }

    @PostMapping("/scheduling/appointments/{id}/start")
    @PreAuthorize(E14Roles.CLINICIAN)
    public AppointmentService.AppointmentView start(@PathVariable UUID id) {
        return queue.start(users.require(), id);
    }

    @PostMapping("/scheduling/appointments/{id}/complete")
    @PreAuthorize(E14Roles.CLINICIAN)
    public AppointmentService.AppointmentView complete(@PathVariable UUID id) {
        return queue.complete(users.require(), id);
    }

    // ------------------------------------------------------------------ waitlist

    @GetMapping("/scheduling/waitlist")
    @PreAuthorize(E14Roles.BOOKING)
    public List<AppointmentService.WaitlistView> waitlist(@RequestParam(required = false) @Pattern(regexp = "WAITING|OFFERED|BOOKED|CANCELLED") String status,
                                                          @RequestParam(required = false) @Pattern(regexp = SITE) String siteCode) {
        return appointments.waitlist(status, siteCode);
    }

    public record WaitlistRequest(@NotBlank @Pattern(regexp = EMPLOYEE) String employeeId, @NotBlank @Pattern(regexp = CODE) String appointmentType,
                                  @NotBlank @Pattern(regexp = SITE) String siteCode, @NotNull LocalDate earliest, @NotNull LocalDate latest,
                                  @Min(1) @Max(5) Integer priority) {
    }

    @PostMapping("/scheduling/waitlist")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(E14Roles.BOOKING)
    public AppointmentService.WaitlistView addToWaitlist(@Valid @RequestBody WaitlistRequest r) {
        return appointments.addToWaitlist(users.require(), new AppointmentService.WaitlistCommand(r.employeeId(), r.appointmentType(), r.siteCode(),
                r.earliest(), r.latest(), r.priority() == null ? 3 : r.priority()));
    }

    // ------------------------------------------------------------------ employee self-service

    @GetMapping("/me/appointments")
    @PreAuthorize("hasAnyRole('EMPLOYEE','CANDIDATE')")
    public List<AppointmentService.MyAppointmentView> mine() {
        return appointments.mine(users.require());
    }

    @GetMapping("/me/appointments/slots")
    @PreAuthorize("hasRole('EMPLOYEE')")
    public List<AppointmentService.SlotView> mySlots(@RequestParam @Pattern(regexp = CODE) String type,
                                                     @RequestParam(required = false) @Pattern(regexp = SITE) String siteCode,
                                                     @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                                     @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return appointments.mySlots(users.require(), type, siteCode, from, to);
    }

    public record SelfBookRequest(@NotBlank @Pattern(regexp = CODE) String appointmentType, @NotBlank @Pattern(regexp = SITE) String siteCode,
                                  @NotNull Instant startsAt) {
    }

    @PostMapping("/me/appointments")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('EMPLOYEE')")
    public AppointmentService.MyAppointmentView selfBook(@Valid @RequestBody SelfBookRequest r) {
        return appointments.selfBook(users.require(), r.appointmentType(), r.siteCode(), r.startsAt());
    }

    public record RescheduleRequest(@NotNull Instant startsAt) {
    }

    @PostMapping("/me/appointments/{id}/reschedule")
    @PreAuthorize("hasAnyRole('EMPLOYEE','CANDIDATE')")
    public AppointmentService.MyAppointmentView reschedule(@PathVariable UUID id, @Valid @RequestBody RescheduleRequest r) {
        return appointments.myReschedule(users.require(), id, r.startsAt());
    }

    public record MyCancelRequest(@Size(max = 500) String reason) {
    }

    @PostMapping("/me/appointments/{id}/cancel")
    @PreAuthorize("hasAnyRole('EMPLOYEE','CANDIDATE')")
    public AppointmentService.MyAppointmentView myCancel(@PathVariable UUID id, @Valid @RequestBody(required = false) MyCancelRequest r) {
        return appointments.myCancel(users.require(), id, r == null ? null : r.reason());
    }

    @PostMapping("/me/appointments/{id}/check-in")
    @PreAuthorize("hasAnyRole('EMPLOYEE','CANDIDATE')")
    public ClinicQueueService.CheckInView myCheckIn(@PathVariable UUID id) {
        return queue.selfCheckIn(users.require(), id);
    }
}
