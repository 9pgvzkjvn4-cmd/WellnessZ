package com.wellnessz.e14.booking;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.wellnessz.common.error.ApiException;
import com.wellnessz.common.model.Roles;
import com.wellnessz.common.text.ClinicalContentException;
import com.wellnessz.e14.catalogue.CatalogueService;
import com.wellnessz.e14.engine.BookingRules;
import com.wellnessz.e14.engine.SlotEngine;
import com.wellnessz.e14.model.Appointment;
import com.wellnessz.e14.model.AppointmentRepository;
import com.wellnessz.e14.model.AppointmentType;
import com.wellnessz.e14.model.CampaignSlotRepository;
import com.wellnessz.e14.model.Equipment;
import com.wellnessz.e14.model.EquipmentRepository;
import com.wellnessz.e14.model.WaitlistEntry;
import com.wellnessz.e14.model.WaitlistEntryRepository;
import com.wellnessz.e14.support.SchedulingSupport;
import com.wellnessz.masterdata.ClinicSite;
import com.wellnessz.masterdata.Employee;
import com.wellnessz.masterdata.StaffMember;
import com.wellnessz.security.identity.CurrentUser;

/**
 * E14-F01 diary and booking: conflict-checked booking, drag-and-drop moves with optimistic locking, cancellation with
 * waitlist offers, attendance, telehealth links, employee self-booking and the reminder / no-show jobs. Every status change
 * goes through {@link StatusNotifier}.
 */
@Service
public class AppointmentService {

    public static final int MAX_LIST = 2000;
    public static final int MAX_SLOTS = 500;
    public static final int SEARCH_DAYS = 60;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final AppointmentRepository appointments;
    private final WaitlistEntryRepository waitlist;
    private final CampaignSlotRepository campaignSlots;
    private final EquipmentRepository equipment;
    private final CatalogueService catalogue;
    private final StatusNotifier status;
    private final SchedulingSupport support;
    private final String telehealthBaseUrl;

    public AppointmentService(AppointmentRepository appointments, WaitlistEntryRepository waitlist, CampaignSlotRepository campaignSlots,
                              EquipmentRepository equipment, CatalogueService catalogue, StatusNotifier status, SchedulingSupport support,
                              @Value("${wellnessz.scheduling.telehealth-base-url:https://telehealth.wellnessz.local/room/}") String telehealthBaseUrl) {
        this.appointments = appointments;
        this.waitlist = waitlist;
        this.campaignSlots = campaignSlots;
        this.equipment = equipment;
        this.catalogue = catalogue;
        this.status = status;
        this.support = support;
        this.telehealthBaseUrl = telehealthBaseUrl;
    }

    // ------------------------------------------------------------------ views

    public record AppointmentView(UUID id, String employeeId, String displayName, String appointmentType, String appointmentTypeName,
                                  String siteCode, Instant startsAt, Instant endsAt, String status, String clinicianUsername,
                                  String clinicianName, String roomCode, String deviceId, UUID campaignId, UUID caseId, String sourceType,
                                  String ticket, Instant checkedInAt, Instant calledAt, boolean telehealth, String adminNote, long version) {
    }

    public record MyAppointmentView(UUID id, String appointmentType, String appointmentTypeName, String siteCode, String siteName,
                                    Instant startsAt, Instant endsAt, String status, String ticket, Instant checkedInAt, boolean telehealth,
                                    boolean canCheckIn, boolean canReschedule) {
    }

    public record SlotView(Instant startsAt, Instant endsAt, String clinicianUsername, String clinicianName, String roomCode, String deviceId,
                           boolean telehealth) {
    }

    public record SessionView(String clinicianUsername, String clinicianName, String roomCode, String start, String end,
                              List<String> appointmentTypes, boolean telehealth) {
    }

    public record DiaryView(String siteCode, LocalDate date, List<SessionView> sessions, List<AppointmentView> appointments) {
    }

    public record TelehealthView(String joinUrl, String documentationLink) {
    }

    public record WaitlistView(UUID id, String employeeId, String displayName, String appointmentType, String siteCode, LocalDate earliest,
                               LocalDate latest, int priority, String status, UUID campaignId, UUID offeredAppointmentId,
                               Instant offeredStartsAt, Instant createdAt) {
    }

    public List<AppointmentView> views(List<Appointment> list) {
        Map<String, AppointmentType> types = catalogue.typeMap();
        Map<String, Employee> people = support.employees(list.stream().map(Appointment::getEmployeeId).toList());
        Map<String, String> staffNames = new LinkedHashMap<>();
        return list.stream().map(a -> {
            Employee e = people.get(a.getEmployeeId());
            AppointmentType t = types.get(a.getAppointmentType());
            String clinicianName = a.getClinicianUsername() == null ? null
                    : staffNames.computeIfAbsent(a.getClinicianUsername(), support::staffName);
            return new AppointmentView(a.getId(), a.getEmployeeId(), e == null ? a.getEmployeeId() : e.getDisplayName(), a.getAppointmentType(),
                    t == null ? a.getAppointmentType() : t.getName(), a.getSiteCode(), a.getStartsAt(), a.getEndsAt(), a.getStatus(),
                    a.getClinicianUsername(), clinicianName, a.getRoomCode(), a.getDeviceId(), a.getCampaignId(), a.getCaseId(), a.getSourceType(),
                    a.getTicket(), a.getCheckedInAt(), a.getCalledAt(), a.isTelehealth(), a.getAdminNote(), a.getVersion());
        }).toList();
    }

    public AppointmentView view(Appointment a) {
        return views(List.of(a)).get(0);
    }

    private MyAppointmentView myView(Appointment a, Map<String, AppointmentType> types) {
        AppointmentType t = types.get(a.getAppointmentType());
        Instant now = support.now();
        boolean canCheckIn = "BOOKED".equals(a.getStatus())
                && BookingRules.withinCheckInWindow(a.getStartsAt(), a.getEndsAt(), now, support.intConfig("scheduling.check-in.window-minutes", 60));
        boolean canReschedule = "BOOKED".equals(a.getStatus()) && a.getCampaignId() == null && a.getStartsAt().isAfter(now);
        return new MyAppointmentView(a.getId(), a.getAppointmentType(), t == null ? a.getAppointmentType() : t.getName(), a.getSiteCode(),
                support.siteName(a.getSiteCode()), a.getStartsAt(), a.getEndsAt(), a.getStatus(), a.getTicket(), a.getCheckedInAt(),
                a.isTelehealth(), canCheckIn, canReschedule);
    }

    // ------------------------------------------------------------------ diary & slots

    @Transactional(readOnly = true)
    public DiaryView diary(String siteCode, LocalDate date, String clinician) {
        ClinicSite site = support.site(siteCode);
        ZoneId zone = ZoneId.of(site.getTimeZone());
        List<SessionView> sessions = SlotEngine.expand(catalogue.templateRows(siteCode), date, date).stream()
                .filter(s -> clinician == null || clinician.equals(s.clinicianUsername()))
                .map(s -> new SessionView(s.clinicianUsername(), support.staffName(s.clinicianUsername()), s.roomCode(), s.start().toString(),
                        s.end().toString(), s.appointmentTypes().stream().sorted().toList(), s.telehealth()))
                .toList();
        List<Appointment> list = appointments.findAtSite(siteCode, date.atStartOfDay(zone).toInstant(),
                date.plusDays(1).atStartOfDay(zone).toInstant(), PageRequest.of(0, MAX_LIST)).stream()
                .filter(a -> clinician == null || clinician.equals(a.getClinicianUsername())).toList();
        support.audit("DIARY_READ", "CLINIC_SITE", siteCode + ":" + date);
        return new DiaryView(siteCode, date, sessions, views(list));
    }

    @Transactional(readOnly = true)
    public List<SlotView> slots(String typeCode, String siteCode, LocalDate from, LocalDate to, String clinician) {
        AppointmentType type = catalogue.type(typeCode);
        support.site(siteCode);
        checkRange(from, to, 31);
        Instant notBefore = support.now();
        return toSlotViews(freeSlots(type, siteCode, from, to, clinician, null, notBefore, MAX_SLOTS));
    }

    private List<SlotView> toSlotViews(List<SlotEngine.Candidate> candidates) {
        Map<String, String> names = new LinkedHashMap<>();
        return candidates.stream().map(c -> new SlotView(c.startsAt(), c.endsAt(), c.clinicianUsername(),
                c.clinicianUsername() == null ? null : names.computeIfAbsent(c.clinicianUsername(), support::staffName),
                c.roomCode(), c.deviceId(), c.telehealth())).toList();
    }

    private static void checkRange(LocalDate from, LocalDate to, int maxDays) {
        if (to.isBefore(from)) {
            throw ApiException.badRequest("invalid_range", "'to' must not be before 'from'");
        }
        if (from.plusDays(maxDays).isBefore(to)) {
            throw ApiException.badRequest("range_too_large", "At most " + maxDays + " days per request");
        }
    }

    /** Sessions at the site in the date range offering the type, with clinicians qualified for it. */
    List<SlotEngine.Session> sessions(AppointmentType type, String siteCode, LocalDate from, LocalDate to, String clinician) {
        List<String> roles = SchedulingSupport.csv(type.getClinicianRoles());
        Map<String, Boolean> qualified = new LinkedHashMap<>();
        return SlotEngine.expand(catalogue.templateRows(siteCode), from, to).stream()
                .filter(s -> s.offers(type.getCode()))
                .filter(s -> clinician == null || clinician.equals(s.clinicianUsername()))
                .filter(s -> qualified.computeIfAbsent(s.clinicianUsername(), u -> isQualified(u, roles)))
                .toList();
    }

    private boolean isQualified(String username, List<String> roles) {
        Optional<StaffMember> s = support.staff(username);
        return s.isPresent() && s.get().isActive() && (roles.isEmpty() || roles.stream().anyMatch(s.get()::hasRole));
    }

    SlotEngine.Context context(AppointmentType type, String siteCode, List<SlotEngine.Busy> busy, String employeeId, Instant notBefore) {
        List<String> rooms = type.getRoomType() == null ? null : catalogue.roomsOfType(siteCode, type.getRoomType());
        List<SlotEngine.DeviceOption> devices = type.getDeviceCategory() == null ? null : catalogue.devices(siteCode, type.getDeviceCategory());
        return new SlotEngine.Context(support.zone(siteCode), type.getDurationMinutes(), rooms, devices, busy, employeeId, notBefore);
    }

    List<SlotEngine.Busy> busy(String siteCode, Set<String> clinicians, String employeeId, Instant from, Instant to, UUID exclude) {
        Set<String> c = new LinkedHashSet<>(clinicians);
        c.add("");
        return appointments.findOccupying(from, to, AppointmentRepository.OCCUPYING, siteCode, c, employeeId == null ? "" : employeeId).stream()
                .filter(a -> exclude == null || !exclude.equals(a.getId()))
                .map(a -> new SlotEngine.Busy(a.getStartsAt(), a.getEndsAt(), a.getClinicianUsername(),
                        siteCode.equals(a.getSiteCode()) ? a.getRoomCode() : null, siteCode.equals(a.getSiteCode()) ? a.getDeviceId() : null,
                        a.getEmployeeId()))
                .toList();
    }

    List<SlotEngine.Candidate> freeSlots(AppointmentType type, String siteCode, LocalDate from, LocalDate to, String clinician, String employeeId,
                                         Instant notBefore, int limit) {
        List<SlotEngine.Session> sessions = sessions(type, siteCode, from, to, clinician);
        if (sessions.isEmpty()) {
            return List.of();
        }
        ZoneId zone = support.zone(siteCode);
        Set<String> clinicians = new HashSet<>();
        sessions.forEach(s -> clinicians.add(s.clinicianUsername()));
        List<SlotEngine.Busy> busy = busy(siteCode, clinicians, employeeId, from.atStartOfDay(zone).toInstant(),
                to.plusDays(1).atStartOfDay(zone).toInstant(), null);
        return SlotEngine.freeSlots(sessions, context(type, siteCode, busy, employeeId, notBefore), limit);
    }

    // ------------------------------------------------------------------ booking

    public record BookCommand(String employeeId, String appointmentType, String siteCode, Instant startsAt, String clinicianUsername,
                              String roomCode, String deviceId, UUID caseId, String adminNote, String sourceType, String sourceRef) {
    }

    @Transactional
    public AppointmentView book(CurrentUser user, BookCommand c) {
        if (support.employee(c.employeeId()).isEmpty()) {
            throw ApiException.notFound("Employee");
        }
        if (c.adminNote() != null) {
            ClinicalContentException.screen("adminNote", c.adminNote());
        }
        if (c.startsAt().isBefore(support.now().minus(Duration.ofHours(1)))) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "in_the_past", "Appointments cannot be booked in the past");
        }
        Appointment a = bookCore(user.username(), c);
        support.audit("APPOINTMENT_BOOK", "EMPLOYEE", c.employeeId());
        return view(a);
    }

    /** Conflict-checked booking under advisory locks; throws 409 {@code resource_conflict} / 422 {@code no_session}. */
    Appointment bookCore(String actor, BookCommand c) {
        AppointmentType type = catalogue.type(c.appointmentType());
        ClinicSite site = support.site(c.siteCode());
        if (!site.isActive()) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "site_inactive", "The clinic site is not active");
        }
        Set<String> lockKeys = new LinkedHashSet<>(List.of("site:" + site.getCode(), "emp:" + c.employeeId()));
        if (c.clinicianUsername() != null) {
            lockKeys.add("clin:" + c.clinicianUsername());
        }
        support.lock(lockKeys);
        SlotEngine.Candidate slot = allocate(type, site.getCode(), c.startsAt(), c.clinicianUsername(), c.roomCode(), c.deviceId(),
                c.employeeId(), null);
        Instant now = support.now();
        Appointment a = Appointment.create();
        a.setEmployeeId(c.employeeId());
        a.setAppointmentType(type.getCode());
        a.setSiteCode(site.getCode());
        a.setStartsAt(slot.startsAt());
        a.setEndsAt(slot.endsAt());
        a.setStatus("BOOKED");
        a.setClinicianUsername(slot.clinicianUsername());
        a.setRoomCode(slot.roomCode());
        a.setDeviceId(slot.deviceId());
        a.setCaseId(c.caseId());
        a.setSourceType(c.sourceType() == null ? "MANUAL" : c.sourceType());
        a.setSourceRef(c.sourceRef());
        a.setAdminNote(c.adminNote() == null || c.adminNote().isBlank() ? null : c.adminNote().strip());
        a.setTelehealth(type.isTelehealth() || slot.telehealth());
        a.setBookedBy(actor);
        a.setCreatedAt(now);
        a.setUpdatedAt(now);
        appointments.saveAndFlush(a);
        afterBooked(a);
        return a;
    }

    /** Booking confirmation, waitlist housekeeping and status listeners for a new booking. */
    void afterBooked(Appointment a) {
        support.notifyEmployee(a.getEmployeeId(), "appointment.booked", support.appointmentParams(a.getSiteCode(), a.getStartsAt()));
        for (WaitlistEntry w : waitlist.findByEmployeeIdAndAppointmentTypeAndStatusIn(a.getEmployeeId(), a.getAppointmentType(),
                List.of("WAITING", "OFFERED"))) {
            w.setStatus("BOOKED");
            w.setUpdatedAt(support.now());
        }
        status.changed(a);
    }

    /**
     * Resolves clinician / room / device for a start time. With sessions covering the time the session grid is used; an
     * explicitly named clinician without a session is an administrative override (still conflict-checked).
     */
    SlotEngine.Candidate allocate(AppointmentType type, String siteCode, Instant startsAt, String clinician, String room, String deviceId,
                                  String employeeId, UUID exclude) {
        ZoneId zone = support.zone(siteCode);
        LocalDate date = startsAt.atZone(zone).toLocalDate();
        Instant endsAt = startsAt.plus(Duration.ofMinutes(type.getDurationMinutes()));
        if (room != null && !catalogue.roomExists(siteCode, room)) {
            throw ApiException.badRequest("unknown_room", "Room is not a resource of the site");
        }
        if (deviceId != null) {
            requireUsableDevice(siteCode, deviceId, date);
        }
        if (clinician != null) {
            StaffMember s = support.staff(clinician).filter(StaffMember::isActive)
                    .orElseThrow(() -> ApiException.badRequest("unknown_clinician", "Clinician is not in the staff directory"));
            List<String> roles = SchedulingSupport.csv(type.getClinicianRoles());
            if (!roles.isEmpty() && roles.stream().noneMatch(s::hasRole)) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "clinician_not_qualified",
                        "The clinician's role does not match the appointment type");
            }
        }
        List<SlotEngine.Session> sessions = sessions(type, siteCode, date, date, clinician);
        Set<String> clinicians = new HashSet<>();
        sessions.forEach(s -> clinicians.add(s.clinicianUsername()));
        if (clinician != null) {
            clinicians.add(clinician);
        }
        List<SlotEngine.Busy> busy = busy(siteCode, clinicians, employeeId, startsAt.minus(Duration.ofHours(12)), endsAt.plus(Duration.ofHours(12)), exclude);
        SlotEngine.Context ctx = context(type, siteCode, busy, employeeId, null);
        if (deviceId != null) {
            ctx = new SlotEngine.Context(ctx.zone(), ctx.durationMinutes(), ctx.roomsOfType(),
                    List.of(new SlotEngine.DeviceOption(deviceId, null)), busy, employeeId, null);
        }
        SlotEngine.Allocation alloc = SlotEngine.allocateAt(sessions, ctx, startsAt, room);
        if (alloc.ok()) {
            return alloc.candidate();
        }
        if ("no_session".equals(alloc.conflict()) && clinician != null) {
            // administrative override outside the published sessions: explicit resources, conflict-checked
            String useRoom = room;
            if (useRoom == null && ctx.roomsOfType() != null) {
                useRoom = ctx.roomsOfType().stream()
                        .filter(r -> SlotEngine.conflict(startsAt, endsAt, null, r, null, null, busy).isEmpty()).findFirst()
                        .orElseThrow(() -> conflict("room"));
            }
            String useDevice = deviceId;
            if (useDevice == null && ctx.devices() != null) {
                useDevice = ctx.devices().stream().filter(d -> d.calibratedUntil() == null || !d.calibratedUntil().isBefore(date))
                        .map(SlotEngine.DeviceOption::deviceId)
                        .filter(d -> SlotEngine.conflict(startsAt, endsAt, null, null, d, null, busy).isEmpty()).findFirst()
                        .orElseThrow(() -> conflict("device"));
            }
            Optional<String> clash = SlotEngine.conflict(startsAt, endsAt, clinician, useRoom, useDevice, employeeId, busy);
            if (clash.isPresent()) {
                throw conflict(clash.get());
            }
            return new SlotEngine.Candidate(startsAt, endsAt, clinician, useRoom, useDevice, type.isTelehealth());
        }
        if ("no_session".equals(alloc.conflict())) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "no_session",
                    "No clinic session offers this appointment type at that time");
        }
        throw conflict(alloc.conflict());
    }

    static ApiException conflict(String resource) {
        return ApiException.conflict("resource_conflict", "The " + resource + " is not available at that time");
    }

    private void requireUsableDevice(String siteCode, String deviceId, LocalDate date) {
        Equipment e = equipment.findByDeviceId(deviceId).filter(x -> x.getSiteCode().equals(siteCode))
                .orElseThrow(() -> ApiException.badRequest("unknown_device", "Device is not registered at the site"));
        if (!"IN_SERVICE".equals(e.getStatus())) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "device_out_of_service", "The device is out of service");
        }
        if (e.getCalibrationDueOn() != null && e.getCalibrationDueOn().isBefore(date)) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "device_out_of_calibration", "The device is out of calibration on that date");
        }
    }

    // ------------------------------------------------------------------ list / move / cancel / attendance

    @Transactional(readOnly = true)
    public List<AppointmentView> list(String siteCode, LocalDate date, String employeeId, String statusFilter) {
        List<Appointment> list;
        if (employeeId != null) {
            list = appointments.findForEmployee(employeeId, PageRequest.of(0, MAX_LIST)).stream()
                    .filter(a -> siteCode == null || siteCode.equals(a.getSiteCode()))
                    .filter(a -> date == null || date.equals(a.getStartsAt().atZone(support.zone(a.getSiteCode())).toLocalDate()))
                    .toList();
            support.audit("APPOINTMENT_LIST", "EMPLOYEE", employeeId);
        } else {
            if (siteCode == null) {
                throw ApiException.badRequest("filter_required", "siteCode or employeeId is required");
            }
            ZoneId zone = ZoneId.of(support.site(siteCode).getTimeZone());
            LocalDate day = date == null ? support.today(siteCode) : date;
            list = appointments.findAtSite(siteCode, day.atStartOfDay(zone).toInstant(), day.plusDays(1).atStartOfDay(zone).toInstant(),
                    PageRequest.of(0, MAX_LIST));
            support.audit("APPOINTMENT_LIST", "CLINIC_SITE", siteCode + ":" + day);
        }
        return views(list.stream().filter(a -> statusFilter == null || statusFilter.equals(a.getStatus())).toList());
    }

    public Appointment get(UUID id) {
        return appointments.findById(id).orElseThrow(() -> ApiException.notFound("Appointment"));
    }

    @Transactional
    public AppointmentView move(CurrentUser user, UUID id, Instant startsAt, String clinician, String room, long version) {
        Appointment a = get(id);
        if (a.getVersion() != version) {
            throw ApiException.conflict("stale", "The appointment was changed by someone else – reload and try again");
        }
        reschedule(user.username(), a, startsAt, clinician, room);
        support.audit("APPOINTMENT_MOVE", "APPOINTMENT", id.toString());
        return view(a);
    }

    private void reschedule(String actor, Appointment a, Instant startsAt, String clinician, String room) {
        if (!"BOOKED".equals(a.getStatus())) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "invalid_status", "Only booked appointments can be moved");
        }
        if (a.getCampaignId() != null) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "campaign_appointment", "Campaign appointments are managed in the campaign");
        }
        AppointmentType type = catalogue.type(a.getAppointmentType());
        Set<String> keys = new LinkedHashSet<>(List.of("site:" + a.getSiteCode(), "emp:" + a.getEmployeeId()));
        String preferred = clinician != null ? clinician : a.getClinicianUsername();
        if (preferred != null) {
            keys.add("clin:" + preferred);
        }
        support.lock(keys);
        SlotEngine.Candidate slot;
        try {
            slot = allocate(type, a.getSiteCode(), startsAt, preferred, room, null, a.getEmployeeId(), a.getId());
        } catch (ApiException e) {
            if (clinician != null || preferred == null || e.status() == HttpStatus.BAD_REQUEST) {
                throw e;
            }
            slot = allocate(type, a.getSiteCode(), startsAt, null, room, null, a.getEmployeeId(), a.getId());
        }
        a.setStartsAt(slot.startsAt());
        a.setEndsAt(slot.endsAt());
        a.setClinicianUsername(slot.clinicianUsername());
        a.setRoomCode(slot.roomCode());
        a.setDeviceId(slot.deviceId());
        a.setReminder48SentAt(null);
        a.setReminder2SentAt(null);
        a.setUpdatedAt(support.now());
        a.setBookedBy(actor);
        try {
            appointments.saveAndFlush(a);
        } catch (org.springframework.orm.ObjectOptimisticLockingFailureException concurrent) {
            throw ApiException.conflict("stale", "The appointment was changed by someone else – reload and try again");
        }
        support.notifyEmployee(a.getEmployeeId(), "appointment.booked", support.appointmentParams(a.getSiteCode(), a.getStartsAt()));
    }

    @Transactional
    public AppointmentView cancel(CurrentUser user, UUID id, String reason) {
        Appointment a = get(id);
        cancelCore(a, reason);
        support.audit("APPOINTMENT_CANCEL", "APPOINTMENT", id.toString());
        return view(a);
    }

    void cancelCore(Appointment a, String reason) {
        if (!"BOOKED".equals(a.getStatus()) && !"CHECKED_IN".equals(a.getStatus())) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "invalid_status", "Only booked or checked-in appointments can be cancelled");
        }
        if (reason != null) {
            ClinicalContentException.screen("reason", reason);
        }
        Instant now = support.now();
        a.setStatus("CANCELLED");
        a.setCancelledAt(now);
        a.setCancelReason(reason == null || reason.isBlank() ? null : reason.strip());
        a.setUpdatedAt(now);
        campaignSlots.findByAppointmentId(a.getId()).ifPresent(s -> s.setAppointmentId(null));
        appointments.saveAndFlush(a);
        support.notifyEmployee(a.getEmployeeId(), "appointment.cancelled", support.appointmentParams(a.getSiteCode(), a.getStartsAt()));
        status.changed(a);
        if (a.getStartsAt().isAfter(now)) {
            offerToWaitlist(a);
        }
    }

    /** Offers the freed slot to the highest-priority waiting entry whose window covers the date (E14-F01 waitlist). */
    void offerToWaitlist(Appointment freed) {
        LocalDate day = freed.getStartsAt().atZone(support.zone(freed.getSiteCode())).toLocalDate();
        waitlist.findByAppointmentTypeAndSiteCodeAndStatusOrderByPriorityAscCreatedAtAsc(freed.getAppointmentType(), freed.getSiteCode(), "WAITING")
                .stream()
                .filter(w -> !w.getEmployeeId().equals(freed.getEmployeeId()))
                .filter(w -> !day.isBefore(w.getEarliest()) && !day.isAfter(w.getLatest()))
                .filter(w -> w.getCampaignId() == null || w.getCampaignId().equals(freed.getCampaignId()))
                .findFirst().ifPresent(w -> {
                    Instant now = support.now();
                    w.setStatus("OFFERED");
                    w.setOfferedAppointmentId(freed.getId());
                    w.setOfferedStartsAt(freed.getStartsAt());
                    w.setOfferedAt(now);
                    w.setUpdatedAt(now);
                    waitlist.save(w);
                    support.notifyEmployee(w.getEmployeeId(), "appointment.waitlist-offer",
                            support.appointmentParams(freed.getSiteCode(), freed.getStartsAt()));
                });
    }

    @Transactional
    public AppointmentView attendance(CurrentUser user, UUID id, String newStatus) {
        Appointment a = get(id);
        markAttendance(a, newStatus);
        support.audit("APPOINTMENT_ATTENDANCE", "APPOINTMENT", id.toString());
        return view(a);
    }

    public void markAttendance(Appointment a, String newStatus) {
        if (!AppointmentRepository.OPEN.contains(a.getStatus())) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "invalid_status", "Attendance can only be recorded for open appointments");
        }
        Instant now = support.now();
        a.setStatus(newStatus);
        if ("ATTENDED".equals(newStatus)) {
            a.setCompletedAt(now);
        }
        a.setUpdatedAt(now);
        appointments.save(a);
        status.changed(a);
    }

    // ------------------------------------------------------------------ telehealth

    @Transactional
    public TelehealthView telehealth(CurrentUser user, UUID id) {
        Appointment a = get(id);
        boolean clinician = user.username() != null && user.username().equals(a.getClinicianUsername()) && user.hasAnyRole(Roles.CLINICIANS);
        boolean employee = user.employeeId() != null && user.employeeId().equals(a.getEmployeeId())
                && user.hasAnyRole(Roles.EMPLOYEE, Roles.CANDIDATE);
        if (!clinician && !employee) {
            throw ApiException.notFound("Appointment");
        }
        if (!a.isTelehealth()) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "not_telehealth", "This is not a telehealth appointment");
        }
        if (!AppointmentRepository.OPEN.contains(a.getStatus())) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "invalid_status", "The appointment is not open");
        }
        if (a.getTelehealthRoom() == null) {
            byte[] b = new byte[18];
            RANDOM.nextBytes(b);
            a.setTelehealthRoom(Base64.getUrlEncoder().withoutPadding().encodeToString(b));
            a.setUpdatedAt(support.now());
        }
        String base = support.config("scheduling.telehealth-base-url").orElse(telehealthBaseUrl);
        support.audit("TELEHEALTH_JOIN", "APPOINTMENT", id.toString());
        return new TelehealthView(base + a.getTelehealthRoom(),
                clinician ? "#/employees/" + a.getEmployeeId() + "/chart/encounters" : "#/appointments");
    }

    // ------------------------------------------------------------------ waitlist

    public record WaitlistCommand(String employeeId, String appointmentType, String siteCode, LocalDate earliest, LocalDate latest, int priority) {
    }

    @Transactional(readOnly = true)
    public List<WaitlistView> waitlist(String statusFilter, String siteCode) {
        List<WaitlistEntry> list = waitlist.search(statusFilter, siteCode);
        Map<String, Employee> people = support.employees(list.stream().map(WaitlistEntry::getEmployeeId).toList());
        support.audit("WAITLIST_READ", "CLINIC_SITE", siteCode);
        return list.stream().limit(MAX_LIST).map(w -> waitlistView(w, people)).toList();
    }

    private static WaitlistView waitlistView(WaitlistEntry w, Map<String, Employee> people) {
        Employee e = people.get(w.getEmployeeId());
        return new WaitlistView(w.getId(), w.getEmployeeId(), e == null ? w.getEmployeeId() : e.getDisplayName(), w.getAppointmentType(),
                w.getSiteCode(), w.getEarliest(), w.getLatest(), w.getPriority(), w.getStatus(), w.getCampaignId(), w.getOfferedAppointmentId(),
                w.getOfferedStartsAt(), w.getCreatedAt());
    }

    @Transactional
    public WaitlistView addToWaitlist(CurrentUser user, WaitlistCommand c) {
        if (support.employee(c.employeeId()).isEmpty()) {
            throw ApiException.notFound("Employee");
        }
        catalogue.type(c.appointmentType());
        support.site(c.siteCode());
        if (c.latest().isBefore(c.earliest())) {
            throw ApiException.badRequest("invalid_range", "'latest' must not be before 'earliest'");
        }
        WaitlistEntry w = waitlistEntry(user.username(), c.employeeId(), c.appointmentType(), c.siteCode(), c.earliest(), c.latest(), c.priority(), null);
        support.audit("WAITLIST_ADD", "EMPLOYEE", c.employeeId());
        return waitlistView(w, support.employees(List.of(c.employeeId())));
    }

    public WaitlistEntry waitlistEntry(String actor, String employeeId, String type, String siteCode, LocalDate earliest, LocalDate latest,
                                       int priority, UUID campaignId) {
        Instant now = support.now();
        WaitlistEntry w = WaitlistEntry.create();
        w.setEmployeeId(employeeId);
        w.setAppointmentType(type);
        w.setSiteCode(siteCode);
        w.setEarliest(earliest);
        w.setLatest(latest);
        w.setPriority(priority);
        w.setStatus("WAITING");
        w.setCampaignId(campaignId);
        w.setCreatedBy(actor);
        w.setCreatedAt(now);
        w.setUpdatedAt(now);
        return waitlist.save(w);
    }

    // ------------------------------------------------------------------ employee self-service

    private String self(CurrentUser user) {
        if (user.employeeId() == null) {
            throw ApiException.forbidden("No employee identity");
        }
        return user.employeeId();
    }

    private Appointment own(CurrentUser user, UUID id) {
        Appointment a = get(id);
        if (!a.getEmployeeId().equals(self(user))) {
            throw ApiException.notFound("Appointment");
        }
        return a;
    }

    @Transactional(readOnly = true)
    public List<MyAppointmentView> mine(CurrentUser user) {
        Map<String, AppointmentType> types = catalogue.typeMap();
        return appointments.findForEmployee(self(user), PageRequest.of(0, 200)).stream().map(a -> myView(a, types)).toList();
    }

    @Transactional(readOnly = true)
    public List<SlotView> mySlots(CurrentUser user, String typeCode, String siteCode, LocalDate from, LocalDate to) {
        AppointmentType type = catalogue.type(typeCode);
        if (!type.isSelfBookable()) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "not_self_bookable", "This appointment type cannot be self-booked");
        }
        String site = siteCode != null ? support.site(siteCode).getCode() : support.homeSites(self(user)).get(0).getCode();
        checkRange(from, to, 31);
        Instant now = support.now();
        Instant notBefore = now.plus(Duration.ofHours(support.intConfig("scheduling.self-booking.min-notice-hours", 24)));
        Instant horizon = now.plus(Duration.ofDays(support.intConfig("scheduling.self-booking.max-days-ahead", 60)));
        return toSlotViews(freeSlots(type, site, from, to, null, self(user), notBefore, MAX_SLOTS).stream()
                .filter(c -> !c.startsAt().isAfter(horizon)).toList());
    }

    @Transactional
    public MyAppointmentView selfBook(CurrentUser user, String typeCode, String siteCode, Instant startsAt) {
        String employeeId = self(user);
        AppointmentType type = catalogue.type(typeCode);
        boolean hasOpen = appointments.findOpenForEmployee(employeeId, List.of("BOOKED", "CHECKED_IN"), support.now()).stream()
                .anyMatch(a -> a.getAppointmentType().equals(typeCode));
        BookingRules.selfBookingViolation(type.isSelfBookable(), startsAt, support.now(),
                support.intConfig("scheduling.self-booking.min-notice-hours", 24), support.intConfig("scheduling.self-booking.max-days-ahead", 60),
                hasOpen, support.flag("scheduling.self-booking.one-open-per-type", true)).ifPresent(code -> {
                    throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, code, "Self-booking rule: " + code);
                });
        Appointment a = bookCore(user.username(), new BookCommand(employeeId, typeCode, siteCode, startsAt, null, null, null, null, null,
                "MANUAL", null));
        return myView(a, catalogue.typeMap());
    }

    @Transactional
    public MyAppointmentView myReschedule(CurrentUser user, UUID id, Instant startsAt) {
        Appointment a = own(user, id);
        BookingRules.timingViolation(startsAt, support.now(), support.intConfig("scheduling.self-booking.min-notice-hours", 24),
                support.intConfig("scheduling.self-booking.max-days-ahead", 60)).ifPresent(code -> {
                    throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, code, "Rescheduling rule: " + code);
                });
        reschedule(user.username(), a, startsAt, null, null);
        return myView(a, catalogue.typeMap());
    }

    @Transactional
    public MyAppointmentView myCancel(CurrentUser user, UUID id, String reason) {
        Appointment a = own(user, id);
        if (!"BOOKED".equals(a.getStatus())) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "invalid_status", "Only booked appointments can be cancelled");
        }
        cancelCore(a, reason);
        return myView(a, catalogue.typeMap());
    }

    public Appointment ownForCheckIn(CurrentUser user, UUID id) {
        Appointment a = own(user, id);
        if (!"BOOKED".equals(a.getStatus()) && !"CHECKED_IN".equals(a.getStatus())) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "invalid_status", "This appointment cannot be checked in");
        }
        if (!BookingRules.withinCheckInWindow(a.getStartsAt(), a.getEndsAt(), support.now(),
                support.intConfig("scheduling.check-in.window-minutes", 60))) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "check_in_window",
                    "Check-in opens 60 minutes before the appointment on the day");
        }
        return a;
    }

    // ------------------------------------------------------------------ SPI support

    /** First free slot for the request (home site(s) when no site is given), booked under lock. */
    @Transactional
    public Optional<Appointment> bookFirstFree(String actor, String employeeId, String typeCode, String siteCode, LocalDate earliest,
                                               String sourceType, String sourceRef, UUID caseId) {
        AppointmentType type = catalogue.type(typeCode);
        List<ClinicSite> candidatesSites = siteCode != null ? List.of(support.site(siteCode)) : support.homeSites(employeeId);
        Instant minStart = support.now().plus(Duration.ofHours(1));
        for (ClinicSite site : candidatesSites) {
            ZoneId zone = ZoneId.of(site.getTimeZone());
            LocalDate from = earliest == null ? support.today(site.getCode()) : earliest;
            if (from.isBefore(support.today(site.getCode()))) {
                from = support.today(site.getCode());
            }
            Instant notBefore = ZonedDateTime.of(from.atStartOfDay(), zone).toInstant();
            if (notBefore.isBefore(minStart)) {
                notBefore = minStart;
            }
            List<SlotEngine.Candidate> free = freeSlots(type, site.getCode(), from, from.plusDays(SEARCH_DAYS), null, employeeId, notBefore, 20);
            for (SlotEngine.Candidate c : free) {
                try {
                    return Optional.of(bookCore(actor, new BookCommand(employeeId, typeCode, site.getCode(), c.startsAt(), c.clinicianUsername(),
                            c.roomCode(), c.deviceId(), caseId, null, sourceType, sourceRef)));
                } catch (ApiException raced) {
                    if (raced.status() != HttpStatus.CONFLICT) {
                        throw raced;
                    }
                }
            }
        }
        return Optional.empty();
    }

    @Transactional(readOnly = true)
    public List<Appointment> bySource(String sourceType, String sourceRef) {
        return appointments.findBySourceTypeAndSourceRefAndStatusNotOrderByStartsAt(sourceType, sourceRef, "CANCELLED");
    }

    // ------------------------------------------------------------------ jobs

    public record JobRun(int processed, int changed) {
    }

    /** T−48 h / T−2 h reminders ({@code appointment.reminder}, link to the employee app appointments page). */
    @Transactional
    public JobRun sendReminders(Instant now) {
        List<Appointment> due = appointments.findBookedStartingBetween(now, now.plus(Duration.ofHours(48)), PageRequest.of(0, 5000));
        int sent = 0;
        for (Appointment a : due) {
            List<BookingRules.Reminder> r = BookingRules.remindersDue(a.getStartsAt(), a.getCreatedAt(), now,
                    a.getReminder48SentAt() != null, a.getReminder2SentAt() != null);
            if (r.isEmpty()) {
                continue;
            }
            support.notifyEmployee(a.getEmployeeId(), "appointment.reminder", support.appointmentParams(a.getSiteCode(), a.getStartsAt()));
            if (r.contains(BookingRules.Reminder.T48)) {
                a.setReminder48SentAt(now);
            }
            if (r.contains(BookingRules.Reminder.T2)) {
                a.setReminder2SentAt(now);
                if (a.getReminder48SentAt() == null) {
                    a.setReminder48SentAt(now);
                }
            }
            sent++;
        }
        return new JobRun(due.size(), sent);
    }

    /** BOOKED / CHECKED_IN appointments not started 30 minutes after their end become NO_SHOW (listeners notified). */
    @Transactional
    public JobRun detectNoShows(Instant now) {
        int grace = support.intConfig("scheduling.no-show.grace-minutes", 30);
        List<Appointment> candidates = appointments.findNoShowCandidates(now.minus(Duration.ofMinutes(grace)), PageRequest.of(0, 5000));
        int changed = 0;
        for (Appointment a : candidates) {
            if (BookingRules.isNoShow(a.getStatus(), a.getEndsAt(), a.getStartedAt(), now, grace)) {
                a.setStatus("NO_SHOW");
                a.setUpdatedAt(now);
                status.changed(a);
                changed++;
            }
        }
        return new JobRun(candidates.size(), changed);
    }

    /** Mean duration of the appointment types (used by queue estimates). */
    public double meanDuration(List<Appointment> list) {
        Map<String, AppointmentType> types = catalogue.typeMap();
        List<Integer> d = new ArrayList<>();
        for (Appointment a : list) {
            AppointmentType t = types.get(a.getAppointmentType());
            d.add(t == null ? 20 : t.getDurationMinutes());
        }
        return d.isEmpty() ? 20.0 : d.stream().mapToInt(Integer::intValue).average().orElse(20.0);
    }

    public static Comparator<Appointment> byStart() {
        return Comparator.comparing(Appointment::getStartsAt).thenComparing(Appointment::getId);
    }
}
