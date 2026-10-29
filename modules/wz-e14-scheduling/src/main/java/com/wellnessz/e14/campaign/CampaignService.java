package com.wellnessz.e14.campaign;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.wellnessz.common.error.ApiException;
import com.wellnessz.e14.booking.AppointmentService;
import com.wellnessz.e14.booking.StatusNotifier;
import com.wellnessz.e14.catalogue.CatalogueService;
import com.wellnessz.e14.engine.CampaignPlanner;
import com.wellnessz.e14.engine.CapacityMath;
import com.wellnessz.e14.model.Appointment;
import com.wellnessz.e14.model.AppointmentRepository;
import com.wellnessz.e14.model.AppointmentType;
import com.wellnessz.e14.model.Campaign;
import com.wellnessz.e14.model.CampaignRepository;
import com.wellnessz.e14.model.CampaignSlot;
import com.wellnessz.e14.model.CampaignSlotRepository;
import com.wellnessz.e14.model.Equipment;
import com.wellnessz.e14.model.EquipmentRepository;
import com.wellnessz.e14.support.SchedulingSupport;
import com.wellnessz.masterdata.ClinicSite;
import com.wellnessz.security.identity.CurrentUser;

/** E14-F02 campaign and mobile-clinic scheduling: slot generation (days × stations × slots), bulk booking, performance. */
@Service
public class CampaignService {

    public static final int MAX_BULK = 5000;
    public static final int MAX_DAYS = 60;
    public static final int MAX_STATIONS = 50;
    private static final List<String> FLOW = List.of("PLANNED", "OPEN", "IN_PROGRESS", "CLOSED");

    private final CampaignRepository campaigns;
    private final CampaignSlotRepository slots;
    private final AppointmentRepository appointments;
    private final EquipmentRepository equipment;
    private final CatalogueService catalogue;
    private final AppointmentService appointmentService;
    private final StatusNotifier status;
    private final SchedulingSupport support;

    public CampaignService(CampaignRepository campaigns, CampaignSlotRepository slots, AppointmentRepository appointments,
                           EquipmentRepository equipment, CatalogueService catalogue, AppointmentService appointmentService,
                           StatusNotifier status, SchedulingSupport support) {
        this.campaigns = campaigns;
        this.slots = slots;
        this.appointments = appointments;
        this.equipment = equipment;
        this.catalogue = catalogue;
        this.appointmentService = appointmentService;
        this.status = status;
        this.support = support;
    }

    public record Performance(int slots, int booked, int attended, int noShow, double utilisationPct, double attendancePct) {
    }

    public record CampaignView(UUID id, String name, String campaignType, String siteCode, String siteName, String appointmentType,
                               List<LocalDate> days, String dayStart, String dayEnd, int slotMinutes, int stations, List<String> deviceKit,
                               List<String> staff, int targetCount, String status, String createdBy, Instant createdAt, long version,
                               Performance performance) {
    }

    public record CampaignCommand(String name, String campaignType, String siteCode, String appointmentType, List<LocalDate> days,
                                  LocalTime dayStart, LocalTime dayEnd, int slotMinutes, int stations, List<String> deviceKit,
                                  List<String> staff, int targetCount) {
    }

    public record BookResult(int booked, int waitlisted, int alreadyBooked, List<String> rejected) {
    }

    // ------------------------------------------------------------------ read

    @Transactional(readOnly = true)
    public List<CampaignView> list() {
        return campaigns.findAllByOrderByCreatedAtDesc().stream().map(c -> view(c, null)).toList();
    }

    @Transactional(readOnly = true)
    public CampaignView get(UUID id) {
        Campaign c = campaign(id);
        return view(c, performance(c));
    }

    private Campaign campaign(UUID id) {
        return campaigns.findById(id).orElseThrow(() -> ApiException.notFound("Campaign"));
    }

    private CampaignView view(Campaign c, Performance p) {
        return new CampaignView(c.getId(), c.getName(), c.getCampaignType(), c.getSiteCode(), support.siteName(c.getSiteCode()),
                c.getAppointmentType(), days(c), c.getDayStart().toString(), c.getDayEnd().toString(), c.getSlotMinutes(), c.getStations(),
                SchedulingSupport.csv(c.getDeviceKit()), SchedulingSupport.csv(c.getStaff()), c.getTargetCount(), c.getStatus(), c.getCreatedBy(),
                c.getCreatedAt(), c.getVersion(), p);
    }

    private static List<LocalDate> days(Campaign c) {
        return SchedulingSupport.csv(c.getDays()).stream().map(LocalDate::parse).toList();
    }

    Performance performance(Campaign c) {
        List<CampaignSlot> all = slots.findByCampaignIdOrderByStartsAtAscStationAsc(c.getId());
        int booked = (int) all.stream().filter(s -> s.getAppointmentId() != null).count();
        int attended = 0;
        int noShow = 0;
        for (Appointment a : appointments.findByCampaignId(c.getId())) {
            if ("ATTENDED".equals(a.getStatus())) {
                attended++;
            } else if ("NO_SHOW".equals(a.getStatus())) {
                noShow++;
            }
        }
        return new Performance(all.size(), booked, attended, noShow, CapacityMath.utilisationPct(booked, all.size()),
                CapacityMath.pct(attended, (long) attended + noShow));
    }

    // ------------------------------------------------------------------ create / status

    @Transactional
    public CampaignView create(CurrentUser user, CampaignCommand c) {
        ClinicSite site = support.site(c.siteCode());
        AppointmentType type = catalogue.type(c.appointmentType());
        TreeSet<LocalDate> days = new TreeSet<>(c.days());
        if (days.isEmpty() || days.size() > MAX_DAYS) {
            throw ApiException.badRequest("invalid_days", "Between 1 and " + MAX_DAYS + " campaign days");
        }
        if (days.first().isBefore(support.today(site.getCode()))) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "in_the_past", "Campaign days cannot be in the past");
        }
        if (!c.dayStart().isBefore(c.dayEnd()) || CampaignPlanner.slotsPerDay(c.dayStart(), c.dayEnd(), c.slotMinutes()) == 0) {
            throw ApiException.badRequest("invalid_day", "The campaign day must hold at least one slot");
        }
        List<UUID> kit = resolveKit(c.deviceKit());
        checkKit(kit, days);
        List<String> staff = new ArrayList<>(new LinkedHashSet<>(c.staff() == null ? List.of() : c.staff()));
        for (String u : staff) {
            if (support.staff(u).isEmpty()) {
                throw ApiException.badRequest("unknown_clinician", "Unknown staff member " + u);
            }
        }
        Instant now = support.now();
        Campaign x = Campaign.create();
        x.setName(c.name().strip());
        x.setCampaignType(c.campaignType());
        x.setSiteCode(site.getCode());
        x.setAppointmentType(type.getCode());
        x.setDays(String.join(",", days.stream().map(LocalDate::toString).toList()));
        x.setDayStart(c.dayStart());
        x.setDayEnd(c.dayEnd());
        x.setSlotMinutes(c.slotMinutes());
        x.setStations(c.stations());
        x.setDeviceKit(SchedulingSupport.csv(kit.stream().map(UUID::toString).toList()));
        x.setStaff(SchedulingSupport.csv(staff));
        x.setTargetCount(c.targetCount());
        x.setStatus("PLANNED");
        x.setCreatedBy(user.username());
        x.setCreatedAt(now);
        x.setUpdatedAt(now);
        campaigns.saveAndFlush(x);
        support.audit("CAMPAIGN_CREATE", "CAMPAIGN", x.getId().toString());
        return view(x, null);
    }

    @Transactional
    public CampaignView setStatus(CurrentUser user, UUID id, String newStatus) {
        Campaign c = campaign(id);
        if (FLOW.indexOf(newStatus) <= FLOW.indexOf(c.getStatus())) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "invalid_transition", "Campaign status can only move forward");
        }
        if (!"CLOSED".equals(newStatus)) {
            checkKit(resolveKit(SchedulingSupport.csv(c.getDeviceKit())), new TreeSet<>(days(c)));
        }
        c.setStatus(newStatus);
        c.setUpdatedAt(support.now());
        support.audit("CAMPAIGN_STATUS", "CAMPAIGN", id.toString());
        return view(c, performance(c));
    }

    /** Device kit entries may be equipment ids, asset tags or device ids. */
    List<UUID> resolveKit(List<String> refs) {
        List<UUID> out = new ArrayList<>();
        if (refs == null) {
            return out;
        }
        for (String ref : new LinkedHashSet<>(refs)) {
            Optional<Equipment> e = Optional.empty();
            try {
                e = equipment.findById(UUID.fromString(ref));
            } catch (IllegalArgumentException notUuid) {
                // fall through to tag / device id
            }
            if (e.isEmpty()) {
                e = equipment.findByAssetTag(ref).or(() -> equipment.findByDeviceId(ref));
            }
            out.add(e.orElseThrow(() -> ApiException.badRequest("unknown_equipment", "Unknown equipment " + ref)).getId());
        }
        return out;
    }

    /** Every kit device must be IN_SERVICE and in calibration on every campaign day (422). */
    void checkKit(List<UUID> kit, TreeSet<LocalDate> days) {
        for (UUID id : kit) {
            Equipment e = equipment.findById(id).orElseThrow(() -> ApiException.badRequest("unknown_equipment", "Unknown equipment"));
            if (!"IN_SERVICE".equals(e.getStatus())) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "device_out_of_service", "Device " + e.getAssetTag() + " is out of service");
            }
            if (e.getCalibrationDueOn() != null && !days.isEmpty() && e.getCalibrationDueOn().isBefore(days.last())) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "device_out_of_calibration",
                        "Device " + e.getAssetTag() + " is out of calibration on a campaign day (due " + e.getCalibrationDueOn() + ")");
            }
        }
    }

    // ------------------------------------------------------------------ slots

    public record GenerateResult(int slots, int created) {
    }

    @Transactional
    public GenerateResult generateSlots(CurrentUser user, UUID id) {
        Campaign c = campaign(id);
        if ("CLOSED".equals(c.getStatus())) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "campaign_closed", "The campaign is closed");
        }
        TreeSet<LocalDate> days = new TreeSet<>(days(c));
        checkKit(resolveKit(SchedulingSupport.csv(c.getDeviceKit())), days);
        support.lock(List.of("campaign:" + id));
        ZoneId zone = support.zone(c.getSiteCode());
        Set<String> existing = new HashSet<>();
        List<CampaignSlot> current = slots.findByCampaignIdOrderByStartsAtAscStationAsc(id);
        current.forEach(s -> existing.add(s.getStartsAt() + "#" + s.getStation()));
        List<CampaignSlot> created = new ArrayList<>();
        for (CampaignPlanner.SlotDef d : CampaignPlanner.slots(List.copyOf(days), c.getDayStart(), c.getDayEnd(), c.getSlotMinutes(),
                c.getStations(), zone)) {
            if (existing.contains(d.startsAt() + "#" + d.station())) {
                continue;
            }
            CampaignSlot s = CampaignSlot.create();
            s.setCampaignId(id);
            s.setDay(d.day());
            s.setStation(d.station());
            s.setStartsAt(d.startsAt());
            s.setEndsAt(d.endsAt());
            created.add(s);
        }
        slots.saveAll(created);
        support.audit("CAMPAIGN_SLOTS", "CAMPAIGN", id.toString());
        return new GenerateResult(current.size() + created.size(), created.size());
    }

    // ------------------------------------------------------------------ booking

    /** Bulk booking (≤ 5000): each employee gets the first free slot that does not clash with their other bookings. */
    @Transactional
    public BookResult book(CurrentUser user, UUID id, List<String> employeeIds) {
        if (employeeIds.size() > MAX_BULK) {
            throw ApiException.badRequest("too_many", "At most " + MAX_BULK + " employees per call");
        }
        Campaign c = campaign(id);
        if ("CLOSED".equals(c.getStatus())) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "campaign_closed", "The campaign is closed");
        }
        checkKit(resolveKit(SchedulingSupport.csv(c.getDeviceKit())), new TreeSet<>(days(c)));
        support.lock(List.of("site:" + c.getSiteCode(), "campaign:" + id));
        List<String> ids = new ArrayList<>(new LinkedHashSet<>(employeeIds));
        var people = support.employees(ids);
        List<String> rejected = ids.stream().filter(e -> !people.containsKey(e)).toList();
        List<String> valid = ids.stream().filter(people::containsKey).toList();
        BulkOutcome out = assign(user.username(), c, valid, null, null, null, null);
        support.audit("CAMPAIGN_BOOK", "CAMPAIGN", id.toString());
        return new BookResult(out.booked().size(), out.waitlisted(), out.alreadyBooked(), rejected);
    }

    /** SPI: one employee into the first free campaign slot on / after {@code earliest}; empty when full or not bookable. */
    @Transactional
    public Optional<Appointment> bookOne(String actor, UUID campaignId, String employeeId, LocalDate earliest, String sourceType, String sourceRef,
                                        UUID caseId) {
        Optional<Campaign> found = campaigns.findById(campaignId);
        if (found.isEmpty() || "CLOSED".equals(found.get().getStatus())) {
            return Optional.empty();
        }
        Campaign c = found.get();
        try {
            checkKit(resolveKit(SchedulingSupport.csv(c.getDeviceKit())), new TreeSet<>(days(c)));
        } catch (ApiException kitProblem) {
            return Optional.empty();
        }
        support.lock(List.of("site:" + c.getSiteCode(), "campaign:" + campaignId));
        BulkOutcome out = assign(actor, c, List.of(employeeId), earliest, sourceType, sourceRef, caseId);
        return out.booked().stream().findFirst();
    }

    private record BulkOutcome(List<Appointment> booked, int waitlisted, int alreadyBooked) {
    }

    private BulkOutcome assign(String actor, Campaign c, List<String> employeeIds, LocalDate earliest, String sourceType, String sourceRef,
                               UUID caseId) {
        Instant now = support.now();
        ZoneId zone = support.zone(c.getSiteCode());
        Instant from = earliest == null ? now : earliest.atStartOfDay(zone).toInstant();
        if (from.isBefore(now)) {
            from = now;
        }
        List<CampaignSlot> free = new ArrayList<>(slots.findFree(c.getId(), from));
        Set<String> inCampaign = new HashSet<>();
        for (Appointment a : appointments.findByCampaignId(c.getId())) {
            if (AppointmentRepository.OCCUPYING.contains(a.getStatus())) {
                inCampaign.add(a.getEmployeeId());
            }
        }
        Map<String, List<Instant[]>> busy = new HashMap<>();
        if (!free.isEmpty() && !employeeIds.isEmpty()) {
            Instant lo = free.get(0).getStartsAt();
            Instant hi = free.get(free.size() - 1).getEndsAt();
            for (int i = 0; i < employeeIds.size(); i += 1000) {
                List<String> chunk = employeeIds.subList(i, Math.min(employeeIds.size(), i + 1000));
                for (Appointment a : appointments.findForEmployeesBetween(chunk, AppointmentRepository.OCCUPYING, lo, hi)) {
                    busy.computeIfAbsent(a.getEmployeeId(), k -> new ArrayList<>()).add(new Instant[] {a.getStartsAt(), a.getEndsAt()});
                }
            }
        }
        List<String> kitDevices = new ArrayList<>();
        for (UUID kid : resolveKit(SchedulingSupport.csv(c.getDeviceKit()))) {
            equipment.findById(kid).ifPresent(e -> kitDevices.add(e.getDeviceId()));
        }
        boolean telehealth = catalogue.type(c.getAppointmentType()).isTelehealth();
        List<Appointment> booked = new ArrayList<>();
        int waitlisted = 0;
        int already = 0;
        List<LocalDate> days = days(c);
        for (String emp : employeeIds) {
            if (inCampaign.contains(emp)) {
                already++;
                continue;
            }
            List<Instant[]> mine = busy.getOrDefault(emp, List.of());
            CampaignSlot chosen = null;
            for (CampaignSlot s : free) {
                if (mine.stream().noneMatch(b -> b[0].isBefore(s.getEndsAt()) && b[1].isAfter(s.getStartsAt()))) {
                    chosen = s;
                    break;
                }
            }
            if (chosen == null) {
                appointmentService.waitlistEntry(actor, emp, c.getAppointmentType(), c.getSiteCode(), days.get(0), days.get(days.size() - 1), 3,
                        c.getId());
                waitlisted++;
                continue;
            }
            free.remove(chosen);
            Appointment a = Appointment.create();
            a.setEmployeeId(emp);
            a.setAppointmentType(c.getAppointmentType());
            a.setSiteCode(c.getSiteCode());
            a.setStartsAt(chosen.getStartsAt());
            a.setEndsAt(chosen.getEndsAt());
            a.setStatus("BOOKED");
            a.setCampaignId(c.getId());
            a.setDeviceId(kitDevices.isEmpty() ? null : kitDevices.get((chosen.getStation() - 1) % kitDevices.size()));
            a.setSourceType(sourceType == null ? "MANUAL" : sourceType);
            a.setSourceRef(sourceRef);
            a.setCaseId(caseId);
            a.setTelehealth(telehealth);
            a.setBookedBy(actor);
            a.setCreatedAt(now);
            a.setUpdatedAt(now);
            chosen.setAppointmentId(a.getId());
            booked.add(a);
            inCampaign.add(emp);
        }
        appointments.saveAll(booked);
        appointments.flush();
        slots.flush();
        for (Appointment a : booked) {
            support.notifyEmployee(a.getEmployeeId(), "appointment.booked", support.appointmentParams(a.getSiteCode(), a.getStartsAt()));
            status.changed(a);
        }
        return new BulkOutcome(booked, waitlisted, already);
    }

    // ------------------------------------------------------------------ attendance

    public record AttendanceItem(UUID appointmentId, String status) {
    }

    @Transactional
    public Map<String, Integer> attendance(CurrentUser user, UUID id, List<AttendanceItem> items) {
        if (items.size() > MAX_BULK) {
            throw ApiException.badRequest("too_many", "At most " + MAX_BULK + " items per call");
        }
        campaign(id);
        int updated = 0;
        int skipped = 0;
        for (AttendanceItem i : items) {
            Optional<Appointment> a = appointments.findById(i.appointmentId()).filter(x -> id.equals(x.getCampaignId()));
            if (a.isEmpty() || !AppointmentRepository.OPEN.contains(a.get().getStatus())) {
                skipped++;
                continue;
            }
            appointmentService.markAttendance(a.get(), i.status());
            updated++;
        }
        support.audit("CAMPAIGN_ATTENDANCE", "CAMPAIGN", id.toString());
        return Map.of("updated", updated, "skipped", skipped);
    }
}
