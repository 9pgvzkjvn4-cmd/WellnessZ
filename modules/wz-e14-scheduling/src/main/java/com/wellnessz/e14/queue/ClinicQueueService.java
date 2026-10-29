package com.wellnessz.e14.queue;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.wellnessz.common.error.ApiException;
import com.wellnessz.common.model.Roles;
import com.wellnessz.e14.booking.AppointmentService;
import com.wellnessz.e14.booking.StatusNotifier;
import com.wellnessz.e14.catalogue.CatalogueService;
import com.wellnessz.e14.engine.BookingRules;
import com.wellnessz.e14.engine.SlotEngine;
import com.wellnessz.e14.model.Appointment;
import com.wellnessz.e14.model.AppointmentRepository;
import com.wellnessz.e14.model.AppointmentType;
import com.wellnessz.e14.support.SchedulingSupport;
import com.wellnessz.masterdata.Employee;
import com.wellnessz.security.identity.CurrentUser;

/**
 * E14-F03 check-in and queue management. The queue board shows tickets, appointment type and waiting time only – never
 * names; the clinician's call-next returns the identity for the encounter hand-off.
 */
@Service
public class ClinicQueueService {

    private final AppointmentRepository appointments;
    private final AppointmentService appointmentService;
    private final CatalogueService catalogue;
    private final StatusNotifier status;
    private final SchedulingSupport support;

    public ClinicQueueService(AppointmentRepository appointments, AppointmentService appointmentService, CatalogueService catalogue,
                        StatusNotifier status, SchedulingSupport support) {
        this.appointments = appointments;
        this.appointmentService = appointmentService;
        this.catalogue = catalogue;
        this.status = status;
        this.support = support;
    }

    public record CheckInView(UUID appointmentId, String ticket, String appointmentTypeName, int position, int estimatedWaitMinutes) {
    }

    public record QueueEntry(String ticket, String appointmentTypeName, String status, Instant checkedInAt, long waitMinutes, String roomCode) {
    }

    public record QueueBoard(String siteCode, double averageWaitMinutesToday, List<QueueEntry> entries) {
    }

    public record CallView(UUID appointmentId, String ticket, String employeeId, String displayName, String appointmentType, UUID caseId,
                           String encounterLink) {
    }

    private Instant[] dayBounds(String siteCode) {
        ZoneId zone = support.zone(siteCode);
        LocalDate today = support.today(siteCode);
        return new Instant[] {today.atStartOfDay(zone).toInstant(), today.plusDays(1).atStartOfDay(zone).toInstant()};
    }

    // ------------------------------------------------------------------ check-in

    /** Reception / kiosk check-in by badge, employee id or appointment id: today's appointment at the site. */
    @Transactional
    public CheckInView checkIn(CurrentUser user, String siteCode, String badgeId, String employeeId, UUID appointmentId) {
        support.site(siteCode);
        Instant[] day = dayBounds(siteCode);
        Appointment target;
        if (appointmentId != null) {
            target = appointments.findById(appointmentId)
                    .filter(a -> a.getSiteCode().equals(siteCode) && !a.getStartsAt().isBefore(day[0]) && a.getStartsAt().isBefore(day[1]))
                    .orElseThrow(() -> ApiException.notFound("Appointment today at this site"));
        } else {
            String emp = employeeId;
            if (emp == null && badgeId != null) {
                emp = support.employeeByBadge(badgeId).map(Employee::getEmployeeId)
                        .orElseThrow(() -> ApiException.notFound("Appointment today at this site"));
            }
            if (emp == null) {
                throw ApiException.badRequest("identifier_required", "badgeId, employeeId or appointmentId is required");
            }
            String who = emp;
            Instant now = support.now();
            target = appointments.findAtSite(siteCode, day[0], day[1], PageRequest.of(0, AppointmentService.MAX_LIST)).stream()
                    .filter(a -> a.getEmployeeId().equals(who) && ("BOOKED".equals(a.getStatus()) || "CHECKED_IN".equals(a.getStatus())))
                    .min(Comparator.comparing((Appointment a) -> Duration.between(now, a.getStartsAt()).abs()))
                    .orElseThrow(() -> ApiException.notFound("Appointment today at this site"));
        }
        CheckInView v = checkInCore(target);
        support.audit("CHECK_IN", "APPOINTMENT", target.getId().toString());
        return v;
    }

    /** Employee app check-in on the day (within the check-in window). */
    @Transactional
    public CheckInView selfCheckIn(CurrentUser user, UUID id) {
        Appointment a = appointmentService.ownForCheckIn(user, id);
        return checkInCore(a);
    }

    private CheckInView checkInCore(Appointment a) {
        if (!"BOOKED".equals(a.getStatus()) && !"CHECKED_IN".equals(a.getStatus())) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "invalid_status", "This appointment cannot be checked in");
        }
        Instant[] day = dayBounds(a.getSiteCode());
        if ("BOOKED".equals(a.getStatus())) {
            support.lock(List.of("site:" + a.getSiteCode()));
            Instant now = support.now();
            long n = appointments.countTickets(a.getSiteCode(), day[0], day[1]) + 1;
            a.setTicket(BookingRules.ticket(n));
            a.setCheckedInAt(now);
            a.setStatus("CHECKED_IN");
            a.setUpdatedAt(now);
            appointments.saveAndFlush(a);
            status.changed(a);
        }
        List<Appointment> queue = appointments.findQueue(a.getSiteCode(), day[0], day[1]);
        List<Appointment> waiting = queue.stream().filter(q -> "CHECKED_IN".equals(q.getStatus()) && q.getCalledAt() == null).toList();
        int position = 1;
        for (Appointment q : waiting) {
            if (q.getId().equals(a.getId())) {
                break;
            }
            position++;
        }
        int clinicians = cliniciansOnDuty(a.getSiteCode());
        AppointmentType t = catalogue.typeMap().get(a.getAppointmentType());
        return new CheckInView(a.getId(), a.getTicket(), t == null ? a.getAppointmentType() : t.getName(), position,
                BookingRules.estimatedWaitMinutes(position - 1, appointmentService.meanDuration(waiting), clinicians));
    }

    private int cliniciansOnDuty(String siteCode) {
        LocalDate today = support.today(siteCode);
        Set<String> c = new HashSet<>();
        SlotEngine.expand(catalogue.templateRows(siteCode), today, today).forEach(s -> c.add(s.clinicianUsername()));
        return Math.max(1, c.size());
    }

    // ------------------------------------------------------------------ queue board

    @Transactional(readOnly = true)
    public QueueBoard board(String siteCode) {
        support.site(siteCode);
        Instant[] day = dayBounds(siteCode);
        Instant now = support.now();
        Map<String, AppointmentType> types = catalogue.typeMap();
        List<QueueEntry> entries = appointments.findQueue(siteCode, day[0], day[1]).stream().map(a -> {
            String s = "IN_PROGRESS".equals(a.getStatus()) ? "IN_PROGRESS" : a.getCalledAt() != null ? "CALLED" : "WAITING";
            Instant until = a.getCalledAt() != null ? a.getCalledAt() : now;
            AppointmentType t = types.get(a.getAppointmentType());
            return new QueueEntry(a.getTicket(), t == null ? a.getAppointmentType() : t.getName(), s, a.getCheckedInAt(),
                    Math.max(0, Duration.between(a.getCheckedInAt(), until).toMinutes()), a.getRoomCode());
        }).toList();
        return new QueueBoard(siteCode, averageWait(siteCode, day), entries);
    }

    double averageWait(String siteCode, Instant[] day) {
        List<Appointment> called = appointments.findCalledBetween(siteCode, day[0], day[1]);
        if (called.isEmpty()) {
            return 0.0;
        }
        double total = called.stream().mapToLong(a -> Duration.between(a.getCheckedInAt(), a.getCalledAt()).toSeconds()).sum() / 60.0;
        return Math.round(total * 10.0 / called.size()) / 10.0;
    }

    // ------------------------------------------------------------------ clinician actions

    /** Next waiting appointment matching the clinician (assigned to them, or unassigned and of a type their role may take). */
    @Transactional
    public CallView callNext(CurrentUser user, String siteCode, String roomCode) {
        support.site(siteCode);
        support.lock(List.of("site:" + siteCode));
        Instant[] day = dayBounds(siteCode);
        Map<String, AppointmentType> types = catalogue.typeMap();
        List<Appointment> waiting = appointments.findQueue(siteCode, day[0], day[1]).stream()
                .filter(a -> "CHECKED_IN".equals(a.getStatus()) && a.getCalledAt() == null).toList();
        // own patients first, then unassigned ones the clinician may see, then (cover) anyone of a type they may see
        Optional<Appointment> next = waiting.stream().filter(a -> user.username().equals(a.getClinicianUsername())).findFirst()
                .or(() -> waiting.stream().filter(a -> a.getClinicianUsername() == null && qualifies(user, types.get(a.getAppointmentType())))
                        .findFirst())
                .or(() -> waiting.stream().filter(a -> qualifies(user, types.get(a.getAppointmentType()))).findFirst());
        Appointment a = next.orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "queue_empty", "No one is waiting for you"));
        Instant now = support.now();
        a.setCalledAt(now);
        a.setClinicianUsername(user.username());
        if (roomCode != null) {
            a.setRoomCode(roomCode);
        }
        a.setUpdatedAt(now);
        support.audit("QUEUE_CALL", "EMPLOYEE", a.getEmployeeId());
        return new CallView(a.getId(), a.getTicket(), a.getEmployeeId(), support.displayName(a.getEmployeeId()), a.getAppointmentType(),
                a.getCaseId(), "#/employees/" + a.getEmployeeId() + "/chart/encounters");
    }

    private static boolean qualifies(CurrentUser user, AppointmentType t) {
        if (t == null) {
            return false;
        }
        List<String> roles = SchedulingSupport.csv(t.getClinicianRoles());
        return roles.isEmpty() || roles.stream().anyMatch(user::hasRole) || user.hasAnyRole(Roles.CMO, Roles.NURSING_HEAD);
    }

    @Transactional
    public AppointmentService.AppointmentView start(CurrentUser user, UUID id) {
        Appointment a = appointmentService.get(id);
        if (!"CHECKED_IN".equals(a.getStatus()) && !"BOOKED".equals(a.getStatus())) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "invalid_status", "Only checked-in appointments can be started");
        }
        Instant now = support.now();
        a.setStatus("IN_PROGRESS");
        a.setStartedAt(now);
        if (a.getCalledAt() == null) {
            a.setCalledAt(now);
        }
        if (a.getCheckedInAt() == null) {
            a.setCheckedInAt(now);
        }
        if (a.getClinicianUsername() == null) {
            a.setClinicianUsername(user.username());
        }
        a.setUpdatedAt(now);
        appointments.save(a);
        status.changed(a);
        support.audit("APPOINTMENT_START", "APPOINTMENT", id.toString());
        return appointmentService.view(a);
    }

    @Transactional
    public AppointmentService.AppointmentView complete(CurrentUser user, UUID id) {
        Appointment a = appointmentService.get(id);
        if (!"IN_PROGRESS".equals(a.getStatus()) && !"CHECKED_IN".equals(a.getStatus())) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "invalid_status", "Only appointments in progress can be completed");
        }
        if (a.getStartedAt() == null) {
            a.setStartedAt(support.now());
        }
        appointmentService.markAttendance(a, "ATTENDED");
        support.audit("APPOINTMENT_COMPLETE", "APPOINTMENT", id.toString());
        return appointmentService.view(a);
    }
}
