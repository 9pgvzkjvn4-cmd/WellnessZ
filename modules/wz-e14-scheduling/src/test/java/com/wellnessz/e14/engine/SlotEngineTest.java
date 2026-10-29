package com.wellnessz.e14.engine;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

class SlotEngineTest {

    static final ZoneId LONDON = ZoneId.of("Europe/London");
    static final ZoneId DUBAI = ZoneId.of("Asia/Dubai");
    /** A Monday in British Summer Time and a Monday in winter time. */
    static final LocalDate SUMMER_MONDAY = LocalDate.of(2026, 6, 1);
    static final LocalDate WINTER_MONDAY = LocalDate.of(2026, 11, 2);

    static SlotEngine.TemplateRow row(int weekday, String from, String to, String clinician, String room, String... types) {
        return new SlotEngine.TemplateRow(weekday, LocalTime.parse(from), LocalTime.parse(to), clinician, room, Set.of(types), false);
    }

    static Instant at(LocalDate d, String time, ZoneId zone) {
        return ZonedDateTime.of(d, LocalTime.parse(time), zone).toInstant();
    }

    static SlotEngine.Context ctx(ZoneId zone, int minutes, List<String> rooms, List<SlotEngine.DeviceOption> devices, List<SlotEngine.Busy> busy,
                                  String employee) {
        return new SlotEngine.Context(zone, minutes, rooms, devices, busy, employee, null);
    }

    @Test
    void expandsWeeklyTemplatesOnMatchingWeekdaysOnly() {
        List<SlotEngine.TemplateRow> rows = List.of(row(1, "08:00", "12:00", "nurse1", "C1"), row(3, "09:00", "10:00", "nurse2", null),
                row(6, "09:00", "10:00", "weekend", null));
        List<SlotEngine.Session> s = SlotEngine.expand(rows, SUMMER_MONDAY, SUMMER_MONDAY.plusDays(4));
        assertThat(s).extracting(SlotEngine.Session::clinicianUsername).containsExactly("nurse1", "nurse2");
        assertThat(s.get(1).date()).isEqualTo(SUMMER_MONDAY.plusDays(2));
        assertThat(s.get(0).minutes()).isEqualTo(240);
    }

    @Test
    void slotsFollowTheSiteTimeZoneAcrossDaylightSaving() {
        List<SlotEngine.TemplateRow> rows = List.of(row(1, "08:00", "09:00", "nurse1", "C1"));
        List<SlotEngine.Candidate> summer = SlotEngine.freeSlots(SlotEngine.expand(rows, SUMMER_MONDAY, SUMMER_MONDAY),
                ctx(LONDON, 20, null, null, List.of(), null), 10);
        assertThat(summer).hasSize(3);
        assertThat(summer.get(0).startsAt()).isEqualTo(Instant.parse("2026-06-01T07:00:00Z"));
        List<SlotEngine.Candidate> winter = SlotEngine.freeSlots(SlotEngine.expand(rows, WINTER_MONDAY, WINTER_MONDAY),
                ctx(LONDON, 20, null, null, List.of(), null), 10);
        assertThat(winter.get(0).startsAt()).isEqualTo(Instant.parse("2026-11-02T08:00:00Z"));
        List<SlotEngine.Candidate> dubai = SlotEngine.freeSlots(SlotEngine.expand(rows, WINTER_MONDAY, WINTER_MONDAY),
                ctx(DUBAI, 30, null, null, List.of(), null), 10);
        assertThat(dubai).hasSize(2);
        assertThat(dubai.get(0).startsAt()).isEqualTo(Instant.parse("2026-11-02T04:00:00Z"));
    }

    @Test
    void sessionTypeFilterAndPartialSlotsAreRespected() {
        SlotEngine.Session s = SlotEngine.expand(List.of(row(1, "08:00", "08:50", "nurse1", null, "VACCINATION")), SUMMER_MONDAY, SUMMER_MONDAY).get(0);
        assertThat(s.offers("VACCINATION")).isTrue();
        assertThat(s.offers("AUDIOMETRY")).isFalse();
        // 50 minutes hold two 20-minute slots; the 10-minute remainder is not offered
        assertThat(SlotEngine.freeSlots(List.of(s), ctx(LONDON, 20, null, null, List.of(), null), 10)).hasSize(2);
    }

    @Test
    void busyClinicianRoomDeviceAndEmployeeRemoveSlots() {
        List<SlotEngine.Session> sessions = SlotEngine.expand(List.of(row(1, "08:00", "09:00", "nurse1", "BOOTH1")), SUMMER_MONDAY, SUMMER_MONDAY);
        Instant eight = at(SUMMER_MONDAY, "08:00", LONDON);
        Instant eightTwenty = at(SUMMER_MONDAY, "08:20", LONDON);
        Instant eightForty = at(SUMMER_MONDAY, "08:40", LONDON);
        List<SlotEngine.Busy> busy = List.of(
                new SlotEngine.Busy(eight, eightTwenty, "nurse1", null, null, "E1"),
                new SlotEngine.Busy(eightTwenty, eightForty, null, null, null, "E2"));
        assertThat(SlotEngine.freeSlots(sessions, ctx(LONDON, 20, null, null, busy, null), 10)).extracting(SlotEngine.Candidate::startsAt)
                .containsExactly(eightTwenty, eightForty);
        // the employee E2 is busy at 08:20
        assertThat(SlotEngine.freeSlots(sessions, ctx(LONDON, 20, null, null, busy, "E2"), 10)).extracting(SlotEngine.Candidate::startsAt)
                .containsExactly(eightForty);
        // room of the required type: the session's booth is taken at 08:40 → another booth of the type is used
        List<SlotEngine.Busy> booth = List.of(new SlotEngine.Busy(eightForty, eightForty.plusSeconds(1200), "other", "BOOTH1", null, "E3"));
        List<SlotEngine.Candidate> c = SlotEngine.freeSlots(sessions, ctx(LONDON, 20, List.of("BOOTH1", "BOOTH2"), null, booth, null), 10);
        assertThat(c).extracting(SlotEngine.Candidate::roomCode).containsExactly("BOOTH1", "BOOTH1", "BOOTH2");
        // no room of the type at all → no slots
        assertThat(SlotEngine.freeSlots(sessions, ctx(LONDON, 20, List.of(), null, List.of(), null), 10)).isEmpty();
    }

    @Test
    void devicesMustBeFreeAndCalibratedOnTheDay() {
        List<SlotEngine.Session> sessions = SlotEngine.expand(List.of(row(1, "08:00", "08:40", "nurse1", null)), SUMMER_MONDAY, SUMMER_MONDAY);
        Instant eight = at(SUMMER_MONDAY, "08:00", LONDON);
        List<SlotEngine.DeviceOption> devices = List.of(new SlotEngine.DeviceOption("AUD-OLD", SUMMER_MONDAY.minusDays(1)),
                new SlotEngine.DeviceOption("AUD-1", SUMMER_MONDAY.plusDays(100)));
        List<SlotEngine.Busy> busy = List.of(new SlotEngine.Busy(eight, eight.plusSeconds(1200), "x", null, "AUD-1", "E9"));
        List<SlotEngine.Candidate> c = SlotEngine.freeSlots(sessions, ctx(LONDON, 20, null, devices, busy, null), 10);
        assertThat(c).hasSize(1);
        assertThat(c.get(0).deviceId()).isEqualTo("AUD-1");
        assertThat(c.get(0).startsAt()).isEqualTo(eight.plusSeconds(1200));
        assertThat(SlotEngine.freeSlots(sessions, ctx(LONDON, 20, null, List.of(), List.of(), null), 10)).isEmpty();
    }

    @Test
    void allocateAtReportsTheConflictingResource() {
        List<SlotEngine.Session> sessions = SlotEngine.expand(List.of(row(1, "08:00", "12:00", "nurse1", "C1")), SUMMER_MONDAY, SUMMER_MONDAY);
        Instant nine = at(SUMMER_MONDAY, "09:00", LONDON);
        List<SlotEngine.Busy> busy = List.of(new SlotEngine.Busy(nine, nine.plusSeconds(1800), "nurse1", "C1", null, "E1"));
        assertThat(SlotEngine.allocateAt(sessions, ctx(LONDON, 20, null, null, busy, "E5"), nine.plusSeconds(600), null).conflict()).isEqualTo("clinician");
        assertThat(SlotEngine.allocateAt(sessions, ctx(LONDON, 20, null, null, busy, "E1"), nine.plusSeconds(600), null).conflict()).isEqualTo("employee");
        assertThat(SlotEngine.allocateAt(sessions, ctx(LONDON, 20, null, null, busy, "E5"), at(SUMMER_MONDAY, "13:00", LONDON), null).conflict())
                .isEqualTo("no_session");
        SlotEngine.Allocation ok = SlotEngine.allocateAt(sessions, ctx(LONDON, 20, null, null, busy, "E5"), nine.plusSeconds(1800), null);
        assertThat(ok.ok()).isTrue();
        assertThat(ok.candidate().clinicianUsername()).isEqualTo("nurse1");
        assertThat(ok.candidate().endsAt()).isEqualTo(nine.plusSeconds(3000));
        // an explicitly requested room of the wrong type is a room conflict
        assertThat(SlotEngine.allocateAt(sessions, ctx(LONDON, 20, List.of("BOOTH"), null, List.of(), "E5"), nine, "C1").conflict()).isEqualTo("room");
    }

    @Test
    void explicitConflictCheckCoversEveryResource() {
        Instant t = Instant.parse("2026-06-01T08:00:00Z");
        List<SlotEngine.Busy> busy = List.of(new SlotEngine.Busy(t, t.plusSeconds(1200), "nurse1", "R1", "D1", "E1"));
        Instant s = t.plusSeconds(600);
        Instant e = t.plusSeconds(1800);
        assertThat(SlotEngine.conflict(s, e, "nurse1", null, null, "E2", busy)).contains("clinician");
        assertThat(SlotEngine.conflict(s, e, "nurse2", "R1", null, "E2", busy)).contains("room");
        assertThat(SlotEngine.conflict(s, e, "nurse2", "R2", "D1", "E2", busy)).contains("device");
        assertThat(SlotEngine.conflict(s, e, "nurse2", "R2", "D2", "E1", busy)).contains("employee");
        assertThat(SlotEngine.conflict(s, e, "nurse2", "R2", "D2", "E2", busy)).isEmpty();
        // back-to-back is not an overlap
        assertThat(SlotEngine.conflict(t.plusSeconds(1200), e, "nurse1", "R1", "D1", "E1", busy)).isEmpty();
    }
}
