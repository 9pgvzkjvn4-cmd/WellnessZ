package com.wellnessz.e14.engine;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Pure slot generation and conflict detection (E14-F01). Weekly diary templates are expressed in the site's local time and
 * expanded per date in the site's time zone; free slots are those where the clinician, a room of the required type, a
 * calibrated device of the required category and the employee are all free.
 */
public final class SlotEngine {

    private SlotEngine() {
    }

    /** A weekly template row (weekday 1 = Monday … 7 = Sunday, ISO). Empty {@code appointmentTypes} = any type. */
    public record TemplateRow(int weekday, LocalTime start, LocalTime end, String clinicianUsername, String roomCode,
                              Set<String> appointmentTypes, boolean telehealth) {
    }

    /** A concrete clinician session on a date (local site time). */
    public record Session(LocalDate date, LocalTime start, LocalTime end, String clinicianUsername, String roomCode,
                          Set<String> appointmentTypes, boolean telehealth) {

        public boolean offers(String type) {
            return appointmentTypes == null || appointmentTypes.isEmpty() || appointmentTypes.contains(type);
        }

        public Instant startInstant(ZoneId zone) {
            return ZonedDateTime.of(date, start, zone).toInstant();
        }

        public Instant endInstant(ZoneId zone) {
            return ZonedDateTime.of(date, end, zone).toInstant();
        }

        public int minutes() {
            return (int) Duration.between(start, end).toMinutes();
        }
    }

    /** An existing booking that occupies resources. Any field may be {@code null}. */
    public record Busy(Instant start, Instant end, String clinicianUsername, String roomCode, String deviceId, String employeeId) {

        boolean overlaps(Instant s, Instant e) {
            return start.isBefore(e) && end.isAfter(s);
        }
    }

    /** A device usable for the appointment type; {@code calibratedUntil == null} means no calibration expiry. */
    public record DeviceOption(String deviceId, LocalDate calibratedUntil) {

        boolean usableOn(LocalDate date) {
            return calibratedUntil == null || !calibratedUntil.isBefore(date);
        }
    }

    /**
     * @param zone           site time zone
     * @param durationMinutes appointment type duration
     * @param roomsOfType    room codes of the required room type at the site, or {@code null} when the type needs no room type
     * @param devices        usable devices of the required category, or {@code null} when the type needs no device
     * @param busy           occupying bookings (clinicians, rooms, devices, the employee)
     * @param employeeId     employee to book (employee overlap check), or {@code null} when listing generic free slots
     * @param notBefore      earliest start
     */
    public record Context(ZoneId zone, int durationMinutes, List<String> roomsOfType, List<DeviceOption> devices, List<Busy> busy,
                          String employeeId, Instant notBefore) {
    }

    public record Candidate(Instant startsAt, Instant endsAt, String clinicianUsername, String roomCode, String deviceId, boolean telehealth) {
    }

    /** Result of allocating an explicit start time: either a candidate or the resource that conflicts. */
    public record Allocation(Candidate candidate, String conflict) {

        public boolean ok() {
            return candidate != null;
        }
    }

    /** Expands weekly template rows into sessions for every date in {@code [from, to]}. */
    public static List<Session> expand(List<TemplateRow> rows, LocalDate from, LocalDate to) {
        List<Session> out = new ArrayList<>();
        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
            int wd = d.getDayOfWeek().getValue();
            for (TemplateRow r : rows) {
                if (r.weekday() == wd) {
                    out.add(new Session(d, r.start(), r.end(), r.clinicianUsername(), r.roomCode(), r.appointmentTypes(), r.telehealth()));
                }
            }
        }
        out.sort(Comparator.comparing(Session::date).thenComparing(Session::start).thenComparing(Session::clinicianUsername));
        return out;
    }

    /** Free, conflict-checked slots on the session grid (steps of the type duration from the session start). */
    public static List<Candidate> freeSlots(List<Session> sessions, Context ctx, int limit) {
        List<Candidate> out = new ArrayList<>();
        Duration step = Duration.ofMinutes(ctx.durationMinutes());
        for (Session s : sessions) {
            Instant end = s.endInstant(ctx.zone());
            for (Instant t = s.startInstant(ctx.zone()); !t.plus(step).isAfter(end); t = t.plus(step)) {
                if (ctx.notBefore() != null && t.isBefore(ctx.notBefore())) {
                    continue;
                }
                Allocation a = allocate(s, t, t.plus(step), ctx);
                if (a.ok()) {
                    out.add(a.candidate());
                }
            }
        }
        out.sort(Comparator.comparing(Candidate::startsAt).thenComparing(c -> Objects.toString(c.clinicianUsername(), "")));
        return out.size() > limit ? List.copyOf(out.subList(0, limit)) : out;
    }

    /**
     * Allocates resources for an explicit start time inside one of the sessions (any session clinician unless
     * {@code clinician} is given). Returns the first conflicting resource when no session can take the booking.
     */
    public static Allocation allocateAt(List<Session> sessions, Context ctx, Instant startsAt, String preferredRoom) {
        Instant endsAt = startsAt.plus(Duration.ofMinutes(ctx.durationMinutes()));
        if (ctx.employeeId() != null && employeeBusy(ctx, startsAt, endsAt)) {
            return new Allocation(null, "employee");
        }
        if (preferredRoom != null && ctx.roomsOfType() != null) {
            // an explicitly requested room must itself be of the required type
            ctx = new Context(ctx.zone(), ctx.durationMinutes(), ctx.roomsOfType().contains(preferredRoom) ? List.of(preferredRoom) : List.of(),
                    ctx.devices(), ctx.busy(), ctx.employeeId(), ctx.notBefore());
        }
        String conflict = null;
        for (Session s : sessions) {
            if (startsAt.isBefore(s.startInstant(ctx.zone())) || endsAt.isAfter(s.endInstant(ctx.zone()))) {
                continue;
            }
            Session use = preferredRoom == null ? s : new Session(s.date(), s.start(), s.end(), s.clinicianUsername(), preferredRoom,
                    s.appointmentTypes(), s.telehealth());
            Allocation a = allocate(use, startsAt, endsAt, ctx);
            if (a.ok()) {
                return a;
            }
            conflict = conflict == null ? a.conflict() : conflict;
        }
        return new Allocation(null, conflict == null ? "no_session" : conflict);
    }

    /** Conflict check of a fully specified booking (explicit clinician / room / device); empty when free. */
    public static Optional<String> conflict(Instant startsAt, Instant endsAt, String clinician, String room, String device, String employeeId,
                                            List<Busy> busy) {
        for (Busy b : busy) {
            if (!b.overlaps(startsAt, endsAt)) {
                continue;
            }
            if (employeeId != null && employeeId.equals(b.employeeId())) {
                return Optional.of("employee");
            }
            if (clinician != null && clinician.equals(b.clinicianUsername())) {
                return Optional.of("clinician");
            }
            if (room != null && room.equals(b.roomCode())) {
                return Optional.of("room");
            }
            if (device != null && device.equals(b.deviceId())) {
                return Optional.of("device");
            }
        }
        return Optional.empty();
    }

    static Allocation allocate(Session s, Instant start, Instant end, Context ctx) {
        if (ctx.employeeId() != null && employeeBusy(ctx, start, end)) {
            return new Allocation(null, "employee");
        }
        if (s.clinicianUsername() != null && ctx.busy().stream()
                .anyMatch(b -> b.overlaps(start, end) && s.clinicianUsername().equals(b.clinicianUsername()))) {
            return new Allocation(null, "clinician");
        }
        String room = s.roomCode();
        if (ctx.roomsOfType() != null) {
            // the session's own room when it is of the required type, otherwise any room of that type at the site
            List<String> rooms = new ArrayList<>(ctx.roomsOfType());
            if (room != null && rooms.remove(room)) {
                rooms.add(0, room);
            }
            room = rooms.stream().filter(r -> !roomBusy(ctx, r, start, end)).findFirst().orElse(null);
            if (room == null) {
                return new Allocation(null, "room");
            }
        } else if (room != null && roomBusy(ctx, room, start, end)) {
            return new Allocation(null, "room");
        }
        String device = null;
        if (ctx.devices() != null) {
            device = ctx.devices().stream().filter(d -> d.usableOn(s.date()))
                    .map(DeviceOption::deviceId)
                    .filter(d -> ctx.busy().stream().noneMatch(b -> b.overlaps(start, end) && d.equals(b.deviceId())))
                    .findFirst().orElse(null);
            if (device == null) {
                return new Allocation(null, "device");
            }
        }
        return new Allocation(new Candidate(start, end, s.clinicianUsername(), room, device, s.telehealth()), null);
    }

    private static boolean employeeBusy(Context ctx, Instant start, Instant end) {
        return ctx.busy().stream().anyMatch(b -> b.overlaps(start, end) && ctx.employeeId().equals(b.employeeId()));
    }

    private static boolean roomBusy(Context ctx, String room, Instant start, Instant end) {
        return ctx.busy().stream().anyMatch(b -> b.overlaps(start, end) && room.equals(b.roomCode()));
    }
}
