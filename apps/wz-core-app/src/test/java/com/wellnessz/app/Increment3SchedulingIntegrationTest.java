package com.wellnessz.app;

import static com.wellnessz.app.PlatformIntegrationTest.user;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.wellnessz.casekernel.support.AppointmentBooking;
import com.wellnessz.casekernel.support.AppointmentStatusListener;
import com.wellnessz.e14.booking.AppointmentService;
import com.wellnessz.e14.equipment.EquipmentService;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Acceptance tests for E14 – scheduling, clinic operations and resource management (F01–F05) against the real endpoints, including
 * the kernel SPIs (AppointmentBooking, AppointmentStatusListener, DeviceCalibrationCheck blocking an E03 device capture) and
 * role denials (schedulers / reception / biomedical never reach clinical data).
 */
class Increment3SchedulingIntegrationTest extends IntegrationTestBase {

    /** Records every status callback for appointments created with source type {@code E14_TEST}. */
    static final List<String> CALLS = new CopyOnWriteArrayList<>();

    @TestConfiguration
    static class ListenerConfig {
        @Bean
        AppointmentStatusListener e14TestListener() {
            return new AppointmentStatusListener() {
                @Override
                public void appointmentStatusChanged(UUID appointmentId, String employeeId, String sourceType, String sourceRef, Status status) {
                    CALLS.add(appointmentId + ":" + sourceRef + ":" + status);
                }

                @Override
                public boolean supports(String sourceType) {
                    return "E14_TEST".equals(sourceType);
                }
            };
        }
    }

    @Autowired
    MockMvc mvc;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    JsonMapper json;
    @Autowired
    AppointmentBooking booking;
    @Autowired
    AppointmentService appointments;
    @Autowired
    EquipmentService equipment;

    static final JwtRequestPostProcessor SCHEDULER = user("scheduler1", "S8601", null, "CLINIC_SCHEDULER");
    static final JwtRequestPostProcessor RECEPTION = user("reception1", null, null, "CLINIC_RECEPTION");
    static final JwtRequestPostProcessor BIOMED = user("biomed1", "S8602", null, "BIOMEDICAL");
    static final JwtRequestPostProcessor NURSE = user("nurse1", "N1", null, "OH_CLINICIAN");
    static final JwtRequestPostProcessor PHYSICIAN = user("physician1", "PH1", null, "OH_PHYSICIAN");
    static final JwtRequestPostProcessor HEAD = user("nursinghead1", "NH1", null, "NURSING_HEAD", "OH_CLINICIAN");
    static final JwtRequestPostProcessor ADMIN = user("ohadmin1", null, null, "OH_ADMIN");
    static final JwtRequestPostProcessor CMO = user("cmo1", "CM1", null, "CMO");
    static final JwtRequestPostProcessor MANAGER = user("manager1", "M2001", "/AIRLINE/CATERING", "MANAGER");
    static final JwtRequestPostProcessor INTEGRATION = user("iot-gateway", null, null, "INTEGRATION");
    static final ZoneId LONDON = ZoneId.of("Europe/London");

    JsonNode body(MvcResult r) throws Exception {
        String s = r.getResponse().getContentAsString();
        return s.isEmpty() ? json.readTree("{}") : json.readTree(s);
    }

    ResultActions call(MockHttpServletRequestBuilder req, JwtRequestPostProcessor who, String content) throws Exception {
        if (content != null) {
            req = req.contentType(MediaType.APPLICATION_JSON).content(content);
        }
        return mvc.perform(req.with(who));
    }

    JsonNode ok(MockHttpServletRequestBuilder req, JwtRequestPostProcessor who, String content) throws Exception {
        MvcResult r = call(req, who, content).andReturn();
        assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isBetween(200, 299);
        return body(r);
    }

    void fails(MockHttpServletRequestBuilder req, JwtRequestPostProcessor who, String content, int status, String code) throws Exception {
        MvcResult r = call(req, who, content).andReturn();
        assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isEqualTo(status);
        if (code != null) {
            assertThat(body(r).path("code").asString()).isEqualTo(code);
        }
    }

    String newEmployee(String location, boolean withEmail) {
        String id = "E" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        jdbc.update("""
                insert into masterdata.employee (employee_id, display_name, email, subsidiary_code, department_code, job_code, location_code, badge_id, updated_at)
                values (?, ?, ?, 'GND', 'GROUND', 'RAMP_AGENT', ?, ?, now())""", id, "Test " + id, withEmail ? id.toLowerCase() + "@wellnessz.local" : null,
                location, "B-" + id);
        return id;
    }

    static JwtRequestPostProcessor employee(String id) {
        return user("emp-" + id, id, "/AIRLINE/OPS/GROUND", "EMPLOYEE");
    }

    long notifications(String address, String template) {
        return jdbc.queryForObject("select count(*) from platform.notification where address = ? and template_key = ?", Long.class, address, template);
    }

    static LocalDate next(DayOfWeek dow, int minDaysAhead) {
        LocalDate d = LocalDate.now(LONDON).plusDays(minDaysAhead);
        while (d.getDayOfWeek() != dow) {
            d = d.plusDays(1);
        }
        return d;
    }

    static Instant at(LocalDate d, String time) {
        return ZonedDateTime.of(d, LocalTime.parse(time), LONDON).toInstant();
    }

    static String book(String employeeId, String type, String site, Instant startsAt, String extra) {
        return "{\"employeeId\":\"%s\",\"appointmentType\":\"%s\",\"siteCode\":\"%s\",\"startsAt\":\"%s\"%s}"
                .formatted(employeeId, type, site, startsAt, extra == null ? "" : "," + extra);
    }

    static List<String> texts(JsonNode array, String field) {
        List<String> out = new ArrayList<>();
        array.forEach(n -> out.add(n.path(field).asString()));
        return out;
    }

    // =================================================================== F01 booking

    @Test
    void f01BookMoveCancelWithConflictsAndWaitlistOffer() throws Exception {
        LocalDate monday = next(DayOfWeek.MONDAY, 8);
        String a = newEmployee("HQ", true);
        String b = newEmployee("HQ", true);

        JsonNode slots = ok(get("/api/v1/scheduling/slots?type=NURSE_REVIEW&siteCode=HQ-MC&from=" + monday + "&to=" + monday), SCHEDULER, null);
        assertThat(slots.size()).isGreaterThan(5);
        assertThat(texts(slots, "clinicianUsername")).contains("nurse1");

        JsonNode first = ok(post("/api/v1/scheduling/appointments"), SCHEDULER, book(a, "NURSE_REVIEW", "HQ-MC", at(monday, "09:00"),
                "\"adminNote\":\"Bring glasses\""));
        assertThat(first.path("status").asString()).isEqualTo("BOOKED");
        assertThat(first.path("clinicianUsername").asString()).isEqualTo("nurse1");
        assertThat(first.path("roomCode").asString()).isEqualTo("HQ-C1");
        assertThat(first.path("endsAt").asString()).isEqualTo(at(monday, "09:20").toString());
        assertThat(first.path("displayName").asString()).isEqualTo("Test " + a);
        String id = first.path("id").asString();
        assertThat(notifications(a.toLowerCase() + "@wellnessz.local", "appointment.booked")).isEqualTo(1);

        // clinician / employee overlap → 409 resource_conflict
        fails(post("/api/v1/scheduling/appointments"), SCHEDULER, book(b, "NURSE_REVIEW", "HQ-MC", at(monday, "09:10"), "\"clinicianUsername\":\"nurse1\""),
                409, "resource_conflict");
        fails(post("/api/v1/scheduling/appointments"), SCHEDULER, book(a, "PHYSICIAN_REVIEW", "HQ-MC", at(monday, "09:00"), null), 409, "resource_conflict");
        // clinician of the wrong role for the type, no session at that time, clinical admin note
        fails(post("/api/v1/scheduling/appointments"), SCHEDULER, book(b, "PHYSICIAN_REVIEW", "HQ-MC", at(monday, "11:00"), "\"clinicianUsername\":\"nurse1\""),
                422, "clinician_not_qualified");
        fails(post("/api/v1/scheduling/appointments"), SCHEDULER, book(b, "NURSE_REVIEW", "HQ-MC", at(monday, "18:00"), null), 422, "no_session");
        fails(post("/api/v1/scheduling/appointments"), SCHEDULER, book(b, "NURSE_REVIEW", "HQ-MC", at(monday, "11:00"),
                "\"adminNote\":\"Diabetes follow-up\""), 422, "clinical_content_not_allowed");
        // a room of the wrong type is a room conflict
        fails(post("/api/v1/scheduling/appointments"), SCHEDULER, book(b, "VACCINATION", "HQ-MC", at(monday, "11:00"), "\"roomCode\":\"HQ-C2\""),
                409, "resource_conflict");

        // b waits for an earlier nurse review
        JsonNode wait = ok(post("/api/v1/scheduling/waitlist"), SCHEDULER, """
                {"employeeId":"%s","appointmentType":"NURSE_REVIEW","siteCode":"HQ-MC","earliest":"%s","latest":"%s","priority":1}"""
                .formatted(b, monday, monday.plusDays(5)));
        assertThat(wait.path("status").asString()).isEqualTo("WAITING");

        // drag-and-drop move with optimistic locking
        JsonNode moved = ok(put("/api/v1/scheduling/appointments/" + id + "/move"), SCHEDULER,
                "{\"startsAt\":\"%s\",\"version\":0}".formatted(at(monday, "10:00")));
        assertThat(moved.path("startsAt").asString()).isEqualTo(at(monday, "10:00").toString());
        assertThat(moved.path("version").asLong()).isEqualTo(1);
        fails(put("/api/v1/scheduling/appointments/" + id + "/move"), SCHEDULER, "{\"startsAt\":\"%s\",\"version\":0}".formatted(at(monday, "11:00")),
                409, "stale");

        // cancel → waitlist offer to b
        JsonNode cancelled = ok(post("/api/v1/scheduling/appointments/" + id + "/cancel"), SCHEDULER, "{\"reason\":\"Employee on leave\"}");
        assertThat(cancelled.path("status").asString()).isEqualTo("CANCELLED");
        assertThat(notifications(a.toLowerCase() + "@wellnessz.local", "appointment.cancelled")).isEqualTo(1);
        JsonNode list = ok(get("/api/v1/scheduling/waitlist?siteCode=HQ-MC"), SCHEDULER, null);
        JsonNode entry = null;
        for (JsonNode w : list) {
            if (w.path("id").asString().equals(wait.path("id").asString())) {
                entry = w;
            }
        }
        assertThat(entry).isNotNull();
        assertThat(entry.path("status").asString()).isEqualTo("OFFERED");
        assertThat(entry.path("offeredStartsAt").asString()).isEqualTo(at(monday, "10:00").toString());
        assertThat(notifications(b.toLowerCase() + "@wellnessz.local", "appointment.waitlist-offer")).isEqualTo(1);

        // b takes the offered slot → waitlist entry BOOKED
        ok(post("/api/v1/scheduling/appointments"), SCHEDULER, book(b, "NURSE_REVIEW", "HQ-MC", at(monday, "10:00"), null));
        assertThat(jdbc.queryForObject("select status from scheduling.waitlist_entry where id = ?::uuid", String.class, wait.path("id").asString()))
                .isEqualTo("BOOKED");
        JsonNode mine = ok(get("/api/v1/scheduling/appointments?employeeId=" + b), RECEPTION, null);
        assertThat(mine.size()).isEqualTo(1);

        // the diary shows sessions and the booking; reception can read it, the scheduler sees names + type only
        JsonNode diary = ok(get("/api/v1/scheduling/diary?siteCode=HQ-MC&date=" + monday), RECEPTION, null);
        assertThat(texts(diary.path("sessions"), "clinicianUsername")).contains("nurse1", "physician1");
        assertThat(texts(diary.path("appointments"), "employeeId")).contains(b);
        assertThat(diary.toString()).doesNotContainIgnoringCase("diagnos");
        assertThat(jdbc.queryForObject("select count(*) from platform.audit_event where action = 'DIARY_READ'", Long.class)).isPositive();
    }

    @Test
    void f01EmployeeSelfBookingRules() throws Exception {
        LocalDate friday = next(DayOfWeek.FRIDAY, 3);
        String e = newEmployee("HQ", true);
        JwtRequestPostProcessor me = employee(e);

        fails(get("/api/v1/me/appointments/slots?type=NURSE_REVIEW&siteCode=HQ-MC&from=" + friday + "&to=" + friday), me, null, 422, "not_self_bookable");
        JsonNode slots = ok(get("/api/v1/me/appointments/slots?type=VACCINATION&siteCode=HQ-MC&from=" + friday + "&to=" + friday), me, null);
        assertThat(slots.size()).isGreaterThan(2);
        assertThat(slots.get(0).path("roomCode").asString()).isEqualTo("HQ-VAC");
        String s1 = slots.get(0).path("startsAt").asString();
        String s2 = slots.get(2).path("startsAt").asString();

        JsonNode booked = ok(post("/api/v1/me/appointments"), me, "{\"appointmentType\":\"VACCINATION\",\"siteCode\":\"HQ-MC\",\"startsAt\":\"%s\"}".formatted(s1));
        assertThat(booked.path("status").asString()).isEqualTo("BOOKED");
        assertThat(booked.path("siteName").asString()).isEqualTo("HQ Medical Centre");
        assertThat(booked.path("canReschedule").asBoolean()).isTrue();
        fails(post("/api/v1/me/appointments"), me, "{\"appointmentType\":\"VACCINATION\",\"siteCode\":\"HQ-MC\",\"startsAt\":\"%s\"}".formatted(s2),
                422, "already_booked");
        fails(post("/api/v1/me/appointments"), me, "{\"appointmentType\":\"NURSE_REVIEW\",\"siteCode\":\"HQ-MC\",\"startsAt\":\"%s\"}".formatted(s2),
                422, "not_self_bookable");
        Instant soon = Instant.now().plus(Duration.ofHours(3)).truncatedTo(ChronoUnit.MINUTES);
        fails(post("/api/v1/me/appointments"), employee(newEmployee("HQ", false)),
                "{\"appointmentType\":\"VACCINATION\",\"siteCode\":\"HQ-MC\",\"startsAt\":\"%s\"}".formatted(soon), 422, "insufficient_notice");
        fails(post("/api/v1/me/appointments"), employee(newEmployee("HQ", false)),
                "{\"appointmentType\":\"VACCINATION\",\"siteCode\":\"HQ-MC\",\"startsAt\":\"%s\"}".formatted(at(friday.plusWeeks(10), "09:00")),
                422, "too_far_ahead");

        String id = booked.path("id").asString();
        JsonNode moved = ok(post("/api/v1/me/appointments/" + id + "/reschedule"), me, "{\"startsAt\":\"%s\"}".formatted(s2));
        assertThat(moved.path("startsAt").asString()).isEqualTo(s2);
        // another employee cannot touch it; check-in days ahead is refused
        fails(post("/api/v1/me/appointments/" + id + "/cancel"), employee(newEmployee("HQ", false)), "{}", 404, null);
        fails(post("/api/v1/me/appointments/" + id + "/check-in"), me, null, 422, "check_in_window");
        JsonNode cancelled = ok(post("/api/v1/me/appointments/" + id + "/cancel"), me, "{\"reason\":\"Travelling\"}");
        assertThat(cancelled.path("status").asString()).isEqualTo("CANCELLED");
        assertThat(ok(get("/api/v1/me/appointments"), me, null).size()).isEqualTo(1);
    }

    @Test
    void f01RemindersAndNoShowJobsWithStatusListeners() throws Exception {
        String e = newEmployee("HQ", true);
        String address = e.toLowerCase() + "@wellnessz.local";
        Instant start = Instant.now().plus(Duration.ofHours(47)).truncatedTo(ChronoUnit.MINUTES);
        JsonNode appt = ok(post("/api/v1/scheduling/appointments"), SCHEDULER, book(e, "GENERAL_OH", "LHR-AIRSIDE", start,
                "\"clinicianUsername\":\"physician1\""));
        String id = appt.path("id").asString();
        jdbc.update("update scheduling.appointment set created_at = now() - interval '5 days' where id = ?::uuid", id);
        Instant now = Instant.now();
        appointments.sendReminders(now);
        assertThat(notifications(address, "appointment.reminder")).isEqualTo(1);
        appointments.sendReminders(now.plusSeconds(60));
        assertThat(notifications(address, "appointment.reminder")).isEqualTo(1);
        appointments.sendReminders(start.minus(Duration.ofMinutes(90)));
        assertThat(notifications(address, "appointment.reminder")).isEqualTo(2);
        String body = jdbc.queryForObject("select body from platform.notification where address = ? and template_key = 'appointment.reminder' limit 1",
                String.class, address);
        assertThat(body).contains("#/appointments");

        // SPI booking with a test source type → listener sees BOOKED, then the no-show job → NO_SHOW
        String f = newEmployee("LHR", true);
        Optional<AppointmentBooking.Booking> b = booking.bookNext("system", new AppointmentBooking.BookingRequest(f, "NURSE_REVIEW", "LHR-AIRSIDE",
                next(DayOfWeek.TUESDAY, 2), null, "E14_TEST", "ref-noshow-" + f, null));
        assertThat(b).isPresent();
        String bid = b.get().appointmentId().toString();
        assertThat(CALLS).contains(bid + ":ref-noshow-" + f + ":BOOKED");
        assertThat(booking.bookingsFor("E14_TEST", "ref-noshow-" + f)).hasSize(1);
        jdbc.update("update scheduling.appointment set starts_at = now() - interval '3 hours', ends_at = now() - interval '160 minutes' where id = ?::uuid", bid);
        AppointmentService.JobRun run = appointments.detectNoShows(Instant.now());
        assertThat(run.changed()).isPositive();
        assertThat(jdbc.queryForObject("select status from scheduling.appointment where id = ?::uuid", String.class, bid)).isEqualTo("NO_SHOW");
        assertThat(CALLS).contains(bid + ":ref-noshow-" + f + ":NO_SHOW");
        assertThat(jdbc.queryForObject("select count(*) from platform.outbox_event where event_type = 'appointment.status.v1' and aggregate_id = ?",
                Long.class, bid)).isEqualTo(2);

        // cancellation and attendance also reach the listener; an unknown service books nothing
        Optional<AppointmentBooking.Booking> c = booking.bookNext("system", new AppointmentBooking.BookingRequest(f, "NURSE_REVIEW", null,
                next(DayOfWeek.WEDNESDAY, 2), null, "E14_TEST", "ref-cancel-" + f, null));
        assertThat(c).isPresent();
        assertThat(c.get().siteCode()).isEqualTo("LHR-AIRSIDE"); // home site of an LHR employee
        ok(post("/api/v1/scheduling/appointments/" + c.get().appointmentId() + "/cancel"), SCHEDULER, "{\"reason\":\"Rebooked\"}");
        assertThat(CALLS).contains(c.get().appointmentId() + ":ref-cancel-" + f + ":CANCELLED");
        assertThat(booking.bookingsFor("E14_TEST", "ref-cancel-" + f)).isEmpty();
        assertThat(booking.bookNext("system", new AppointmentBooking.BookingRequest(f, "NO_SUCH_SERVICE", null, null, null, "E14_TEST", "x", null)))
                .isEmpty();
    }

    // =================================================================== F03 check-in & queue

    @Test
    void f03CheckInByBadgeQueueBoardCallNextAndComplete() throws Exception {
        String e = newEmployee("HQ", true);
        Instant start = Instant.now().plus(Duration.ofMinutes(2)).truncatedTo(ChronoUnit.MINUTES);
        JsonNode appt = ok(post("/api/v1/scheduling/appointments"), SCHEDULER, book(e, "NURSE_REVIEW", "HQ-MC", start,
                "\"clinicianUsername\":\"nursinghead1\",\"roomCode\":\"HQ-TR\",\"sourceType\":\"MANUAL\""));
        String id = appt.path("id").asString();

        JsonNode in = ok(post("/api/v1/scheduling/check-in"), RECEPTION, "{\"siteCode\":\"HQ-MC\",\"badgeId\":\"B-%s\"}".formatted(e));
        assertThat(in.path("appointmentId").asString()).isEqualTo(id);
        assertThat(in.path("ticket").asString()).matches("[A-Z]\\d{3}");
        assertThat(in.path("position").asInt()).isPositive();
        assertThat(in.path("appointmentTypeName").asString()).isEqualTo("OH nurse review");
        String ticket = in.path("ticket").asString();
        // idempotent
        assertThat(ok(post("/api/v1/scheduling/check-in"), RECEPTION, "{\"siteCode\":\"HQ-MC\",\"appointmentId\":\"%s\"}".formatted(id))
                .path("ticket").asString()).isEqualTo(ticket);
        fails(post("/api/v1/scheduling/check-in"), RECEPTION, "{\"siteCode\":\"LHR-CAT\",\"badgeId\":\"B-%s\"}".formatted(e), 404, null);

        // queue board: tickets, never names
        MvcResult boardResult = call(get("/api/v1/scheduling/queue?siteCode=HQ-MC"), RECEPTION, null).andExpect(status().isOk()).andReturn();
        String board = boardResult.getResponse().getContentAsString();
        assertThat(board).contains(ticket).doesNotContain(e).doesNotContain("Test ").doesNotContain("displayName");
        assertThat(body(boardResult).path("entries").get(0).path("status").asString()).isIn("WAITING", "CALLED", "IN_PROGRESS");

        // clinician calls next → encounter hand-off; schedulers and reception cannot call
        fails(post("/api/v1/scheduling/queue/call-next"), SCHEDULER, "{\"siteCode\":\"HQ-MC\"}", 403, null);
        JsonNode called = ok(post("/api/v1/scheduling/queue/call-next"), HEAD, "{\"siteCode\":\"HQ-MC\",\"roomCode\":\"HQ-TR\"}");
        assertThat(called.path("appointmentId").asString()).isEqualTo(id);
        assertThat(called.path("displayName").asString()).isEqualTo("Test " + e);
        assertThat(called.path("encounterLink").asString()).isEqualTo("#/employees/" + e + "/chart/encounters");
        assertThat(ok(get("/api/v1/scheduling/queue?siteCode=HQ-MC"), RECEPTION, null).toString()).contains("CALLED");

        assertThat(ok(post("/api/v1/scheduling/appointments/" + id + "/start"), HEAD, null).path("status").asString()).isEqualTo("IN_PROGRESS");
        fails(post("/api/v1/scheduling/appointments/" + id + "/complete"), RECEPTION, null, 403, null);
        assertThat(ok(post("/api/v1/scheduling/appointments/" + id + "/complete"), HEAD, null).path("status").asString()).isEqualTo("ATTENDED");

        // employee app check-in on the day
        String g = newEmployee("HQ", false);
        JsonNode own = ok(post("/api/v1/scheduling/appointments"), SCHEDULER, book(g, "GENERAL_OH", "HQ-MC",
                Instant.now().plus(Duration.ofMinutes(25)).truncatedTo(ChronoUnit.MINUTES), "\"clinicianUsername\":\"clinician1\""));
        JsonNode mine = ok(post("/api/v1/me/appointments/" + own.path("id").asString() + "/check-in"), employee(g), null);
        assertThat(mine.path("ticket").asString()).matches("[A-Z]\\d{3}");
        assertThat(ok(get("/api/v1/me/appointments"), employee(g), null).get(0).path("status").asString()).isEqualTo("CHECKED_IN");
        assertThat(ok(get("/api/v1/scheduling/queue?siteCode=HQ-MC"), RECEPTION, null).path("averageWaitMinutesToday").isNumber()).isTrue();
    }

    // =================================================================== F02 campaigns

    @Test
    void f02CampaignGeneratesSlotsAndBooksFiveHundredInOneCall() throws Exception {
        LocalDate day = LocalDate.now(LONDON).plusDays(12);
        String campaign = """
                {"name":"Cargo hearing day","campaignType":"SURVEILLANCE","siteCode":"MOBILE-1","appointmentType":"AUDIOMETRY","days":["%s"],
                 "dayStart":"08:00","dayEnd":"16:30","slotMinutes":10,"stations":10,"deviceKit":["%s"],"staff":["nurse2","clinician1"],"targetCount":500}""";
        fails(post("/api/v1/scheduling/campaigns"), SCHEDULER, campaign.formatted(day, "AUD-LHR-02"), 422, "device_out_of_calibration");
        fails(post("/api/v1/scheduling/campaigns"), RECEPTION, campaign.formatted(day, "AUD-LHR-01"), 403, null);
        JsonNode c = ok(post("/api/v1/scheduling/campaigns"), SCHEDULER, campaign.formatted(day, "AUD-LHR-01"));
        String id = c.path("id").asString();
        assertThat(c.path("status").asString()).isEqualTo("PLANNED");
        assertThat(c.path("deviceKit").get(0).asString()).isEqualTo("e14e0000-0000-4000-8000-000000000001");

        assertThat(ok(post("/api/v1/scheduling/campaigns/" + id + "/generate-slots"), SCHEDULER, null).path("slots").asInt()).isEqualTo(510);
        assertThat(ok(post("/api/v1/scheduling/campaigns/" + id + "/generate-slots"), SCHEDULER, null).path("created").asInt()).isZero();

        // 520 employees generated on the fly (no e-mail address, so no notification fan-out in the test) + one unknown id
        String prefix = "EC" + UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        List<Object[]> rows = new ArrayList<>();
        StringBuilder ids = new StringBuilder();
        for (int i = 0; i < 520; i++) {
            String emp = prefix + "-" + i;
            rows.add(new Object[] {emp, "Campaign " + emp});
            ids.append(i == 0 ? "" : ",").append('"').append(emp).append('"');
        }
        jdbc.batchUpdate("""
                insert into masterdata.employee (employee_id, display_name, subsidiary_code, department_code, job_code, location_code, updated_at)
                values (?, ?, 'CGO', 'CARGO', 'FORKLIFT_OP', 'LHR', now())""", rows);
        ids.append(",\"NOPE-404\"");
        long t0 = System.nanoTime();
        JsonNode result = ok(post("/api/v1/scheduling/campaigns/" + id + "/book"), SCHEDULER, "{\"employeeIds\":[" + ids + "]}");
        long millis = (System.nanoTime() - t0) / 1_000_000;
        assertThat(result.path("booked").asInt()).isEqualTo(510);
        assertThat(result.path("waitlisted").asInt()).isEqualTo(10);
        assertThat(result.path("rejected").size()).isEqualTo(1);
        assertThat(result.path("rejected").get(0).asString()).isEqualTo("NOPE-404");
        assertThat(millis).as("bulk booking time").isLessThan(60_000);
        // booking again is idempotent per employee
        JsonNode again = ok(post("/api/v1/scheduling/campaigns/" + id + "/book"), SCHEDULER, "{\"employeeIds\":[\"" + prefix + "-0\"]}");
        assertThat(again.path("alreadyBooked").asInt()).isEqualTo(1);

        JsonNode detail = ok(get("/api/v1/scheduling/campaigns/" + id), NURSE, null);
        assertThat(detail.path("performance").path("slots").asInt()).isEqualTo(510);
        assertThat(detail.path("performance").path("booked").asInt()).isEqualTo(510);
        assertThat(detail.path("performance").path("utilisationPct").asDouble()).isEqualTo(100.0);

        fails(post("/api/v1/scheduling/campaigns/" + id + "/status"), SCHEDULER, "{\"status\":\"PLANNED\"}", 400, null);
        assertThat(ok(post("/api/v1/scheduling/campaigns/" + id + "/status"), SCHEDULER, "{\"status\":\"OPEN\"}").path("status").asString())
                .isEqualTo("OPEN");

        List<String> apptIds = jdbc.queryForList("select id::text from scheduling.appointment where campaign_id = ?::uuid order by starts_at, id limit 3",
                String.class, id);
        JsonNode att = ok(post("/api/v1/scheduling/campaigns/" + id + "/attendance"), RECEPTION, """
                [{"appointmentId":"%s","status":"ATTENDED"},{"appointmentId":"%s","status":"NO_SHOW"}]""".formatted(apptIds.get(0), apptIds.get(1)));
        assertThat(att.path("updated").asInt()).isEqualTo(2);
        JsonNode perf = ok(get("/api/v1/scheduling/campaigns/" + id), SCHEDULER, null).path("performance");
        assertThat(perf.path("attended").asInt()).isEqualTo(1);
        assertThat(perf.path("noShow").asInt()).isEqualTo(1);
        assertThat(perf.path("attendancePct").asDouble()).isEqualTo(50.0);

        // full campaign → the SPI books nothing; a cancellation frees a slot that the SPI then fills (listener called)
        String extra = newEmployee("LHR", false);
        assertThat(booking.bookNext("system", new AppointmentBooking.BookingRequest(extra, "AUDIOMETRY", null, day, UUID.fromString(id),
                "E14_TEST", "campaign-" + extra, null))).isEmpty();
        ok(post("/api/v1/scheduling/appointments/" + apptIds.get(2) + "/cancel"), SCHEDULER, "{\"reason\":\"Shift change\"}");
        Optional<AppointmentBooking.Booking> filled = booking.bookNext("system", new AppointmentBooking.BookingRequest(extra, "AUDIOMETRY", null, day,
                UUID.fromString(id), "E14_TEST", "campaign-" + extra, null));
        assertThat(filled).isPresent();
        assertThat(filled.get().siteCode()).isEqualTo("MOBILE-1");
        assertThat(CALLS).contains(filled.get().appointmentId() + ":campaign-" + extra + ":BOOKED");

        // the demo campaign carries attendance from yesterday
        JsonNode demo = ok(get("/api/v1/scheduling/campaigns/e14c0000-0000-4000-8000-000000000001"), SCHEDULER, null);
        assertThat(demo.path("name").asString()).isEqualTo("LHR ramp audiometry day");
        assertThat(demo.path("performance").path("slots").asInt()).isEqualTo(192);
        assertThat(demo.path("performance").path("attended").asInt()).isEqualTo(1);
    }

    // =================================================================== F04 equipment & cold chain

    @Test
    void f04EquipmentCalibrationBlocksE03DeviceCapture() throws Exception {
        JsonNode register = ok(get("/api/v1/scheduling/equipment?category=AUDIOMETER"), BIOMED, null);
        assertThat(register.toString()).contains("AUD-LHR-01", "AUD-LHR-02");
        for (JsonNode n : register) {
            if ("AUD-LHR-02".equals(n.path("assetTag").asString())) {
                assertThat(n.path("calibrationState").asString()).isEqualTo("OVERDUE");
            }
            if ("AUD-LHR-01".equals(n.path("assetTag").asString())) {
                assertThat(n.path("calibrationState").asString()).isEqualTo("VALID");
            }
        }
        assertThat(texts(ok(get("/api/v1/scheduling/equipment/calibration-due?withinDays=30"), BIOMED, null), "assetTag"))
                .contains("AUD-LHR-02", "SPIRO-HQ-01").doesNotContain("AUD-LHR-01");

        String audiogram = """
                {"category":"AUDIOMETRY","deviceId":"%s","deviceModel":"Demo audiometer 300","calibrationId":"CAL-X",
                 "measurements":{"left":{"500":10,"1000":10,"2000":15,"3000":15,"4000":20},"right":{"500":10,"1000":10,"2000":15,"3000":15,"4000":20}}}""";
        fails(post("/api/v1/employees/E1004/results/device"), NURSE, audiogram.formatted("AUD-LHR-02"), 422, "device_out_of_calibration");

        String tag = "AUD-T-" + UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        JsonNode created = ok(post("/api/v1/scheduling/equipment"), BIOMED, """
                {"assetTag":"%s","category":"AUDIOMETER","model":"Test audiometer","serialNumber":"SN-1","siteCode":"LHR-CARGO",
                 "calibrationIntervalMonths":12,"lastCalibratedOn":"%s"}""".formatted(tag, LocalDate.now().minusDays(10)));
        assertThat(created.path("deviceId").asString()).isEqualTo(tag);
        assertThat(created.path("calibrationState").asString()).isEqualTo("VALID");
        assertThat(created.path("calibrationDueOn").asString()).isEqualTo(LocalDate.now().minusDays(10).plusMonths(12).toString());
        String eqId = created.path("id").asString();
        fails(post("/api/v1/scheduling/equipment"), BIOMED, "{\"assetTag\":\"%s\",\"category\":\"AUDIOMETER\",\"siteCode\":\"LHR-CARGO\"}".formatted(tag),
                409, "duplicate");
        fails(post("/api/v1/scheduling/equipment"), SCHEDULER, "{\"assetTag\":\"X-1\",\"category\":\"AUDIOMETER\",\"siteCode\":\"LHR-CARGO\"}", 403, null);

        JsonNode failed = ok(post("/api/v1/scheduling/equipment/" + eqId + "/calibrations"), BIOMED, """
                {"performedOn":"%s","vendor":"Acme Calibration","certificateNumber":"FAIL-1","result":"FAIL"}""".formatted(LocalDate.now()));
        assertThat(failed.path("result").asString()).isEqualTo("FAIL");
        assertThat(ok(get("/api/v1/scheduling/equipment?siteCode=LHR-CARGO"), BIOMED, null).toString()).contains("OUT_OF_SERVICE");
        fails(post("/api/v1/employees/E1004/results/device"), NURSE, audiogram.formatted(tag), 422, "device_out_of_service");

        String pdf = Base64.getEncoder().encodeToString("%PDF-1.4\n% calibration certificate\n".getBytes(StandardCharsets.US_ASCII));
        JsonNode passed = ok(post("/api/v1/scheduling/equipment/" + eqId + "/calibrations"), BIOMED, """
                {"performedOn":"%s","vendor":"Acme Calibration","certificateNumber":"PASS-2","result":"PASS","nextDueOn":"%s","documentBase64":"%s"}"""
                .formatted(LocalDate.now(), LocalDate.now().plusMonths(6), pdf));
        assertThat(passed.path("documentAttached").asBoolean()).isTrue();
        assertThat(passed.path("documentSha256").asString()).hasSize(64);
        fails(post("/api/v1/scheduling/equipment/" + eqId + "/calibrations"), BIOMED, """
                {"performedOn":"%s","vendor":"Acme","certificateNumber":"P-3","result":"PASS","documentBase64":"%s"}"""
                .formatted(LocalDate.now(), Base64.getEncoder().encodeToString("not a pdf".getBytes(StandardCharsets.US_ASCII))), 400, "invalid_document");
        assertThat(ok(get("/api/v1/scheduling/equipment/" + eqId + "/calibrations"), NURSE, null).size()).isEqualTo(2);
        MvcResult capture = call(post("/api/v1/employees/E1004/results/device"), NURSE, audiogram.formatted(tag)).andReturn();
        assertThat(capture.getResponse().getStatus()).as(capture.getResponse().getContentAsString()).isBetween(200, 299);
        assertThat(body(capture).path("calibrationId").asString()).isEqualTo("CAL-X");
        // the increment-2 demo device stays accepted
        MvcResult demoDevice = call(post("/api/v1/employees/E1004/results/device"), NURSE, audiogram.formatted("AUD-LHR-01")).andReturn();
        assertThat(demoDevice.getResponse().getStatus()).as(demoDevice.getResponse().getContentAsString()).isBetween(200, 299);

        fails(post("/api/v1/scheduling/equipment/" + eqId + "/status"), SCHEDULER, "{\"status\":\"RETIRED\",\"reason\":\"End of life\"}", 403, null);
        assertThat(ok(post("/api/v1/scheduling/equipment/" + eqId + "/status"), BIOMED, "{\"status\":\"OUT_OF_SERVICE\",\"reason\":\"Dropped in transit\"}")
                .path("status").asString()).isEqualTo("OUT_OF_SERVICE");
        fails(post("/api/v1/employees/E1004/results/device"), NURSE, audiogram.formatted(tag), 422, "device_out_of_service");

        // daily calibration job: one alert per due date to the biomedical distribution list
        long before = notifications("biomedical@wellnessz.local", "equipment.calibration-due");
        int sent = equipment.calibrationAlerts(LocalDate.now());
        assertThat(notifications("biomedical@wellnessz.local", "equipment.calibration-due")).isEqualTo(before + sent);
        assertThat(equipment.calibrationAlerts(LocalDate.now())).isZero();
        assertThat(jdbc.queryForObject("select count(*) from scheduling.equipment where asset_tag in ('AUD-LHR-02','SPIRO-HQ-01') and due_alerted_for is not null",
                Long.class)).isEqualTo(2);
    }

    @Test
    void f04ColdChainIotReadingExcursionAcknowledgeAndReport() throws Exception {
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        JsonNode result = ok(post("/api/v1/integration/cold-chain/readings"), INTEGRATION, """
                [{"deviceId":"FRIDGE-LHR-01","temperatureC":5.0,"recordedAt":"%s"},
                 {"deviceId":"FRIDGE-LHR-01","temperatureC":10.2,"recordedAt":"%s"},
                 {"deviceId":"FRIDGE-LHR-01","temperatureC":9.6,"recordedAt":"%s"},
                 {"deviceId":"UNKNOWN-FRIDGE","temperatureC":5.0,"recordedAt":"%s"}]"""
                .formatted(now.minusSeconds(1800), now.minusSeconds(900), now.minusSeconds(600), now));
        assertThat(result.path("accepted").asInt()).isEqualTo(3);
        assertThat(result.path("rejected").asInt()).isEqualTo(1);
        assertThat(result.path("excursionsOpened").asInt()).isEqualTo(1);
        fails(post("/api/v1/integration/cold-chain/readings"), BIOMED, "[{\"deviceId\":\"FRIDGE-LHR-01\",\"temperatureC\":5,\"recordedAt\":\"%s\"}]"
                .formatted(now), 403, null);

        JsonNode open = ok(get("/api/v1/scheduling/excursions?status=OPEN"), NURSE, null);
        JsonNode excursion = null;
        for (JsonNode x : open) {
            if ("FRIDGE-LHR-01".equals(x.path("assetTag").asString())) {
                excursion = x;
            }
        }
        assertThat(excursion).isNotNull();
        assertThat(excursion.path("maxC").asDouble()).isEqualTo(10.2);
        assertThat(excursion.path("siteCode").asString()).isEqualTo("LHR-AIRSIDE");
        assertThat(texts(open, "assetTag")).contains("FRIDGE-HQ-01"); // demo excursion
        assertThat(notifications("nurse2@wellnessz.local", "coldchain.excursion")).isPositive();
        assertThat(notifications("nursinghead1@wellnessz.local", "coldchain.excursion")).isPositive();
        assertThat(notifications("psych1@wellnessz.local", "coldchain.excursion")).isZero(); // HQ only

        String xid = excursion.path("id").asString();
        fails(post("/api/v1/scheduling/excursions/" + xid + "/close"), NURSE, null, 422, "invalid_status");
        fails(post("/api/v1/scheduling/excursions/" + xid + "/acknowledge"), BIOMED, "{\"action\":\"x\"}", 403, null);
        JsonNode ack = ok(post("/api/v1/scheduling/excursions/" + xid + "/acknowledge"), NURSE, "{\"action\":\"Stock quarantined, fridge reset\"}");
        assertThat(ack.path("status").asString()).isEqualTo("ACKNOWLEDGED");
        assertThat(ack.path("acknowledgedBy").asString()).isEqualTo("nurse1");
        // back in range ends the excursion; closing records who closed it
        ok(post("/api/v1/integration/cold-chain/readings"), INTEGRATION, "[{\"deviceId\":\"FRIDGE-LHR-01\",\"temperatureC\":5.5,\"recordedAt\":\"%s\"}]"
                .formatted(now.plusSeconds(1)));
        JsonNode closed = ok(post("/api/v1/scheduling/excursions/" + xid + "/close"), HEAD, null);
        assertThat(closed.path("status").asString()).isEqualTo("CLOSED");
        assertThat(closed.path("endedAt").asString()).isEqualTo(now.plusSeconds(1).toString());

        JsonNode manual = ok(post("/api/v1/scheduling/equipment/e14e0000-0000-4000-8000-000000000006/temperatures"), BIOMED, "{\"temperatureC\":4.8}");
        assertThat(manual.path("source").asString()).isEqualTo("MANUAL");
        assertThat(manual.path("excursion").asBoolean()).isFalse();
        fails(post("/api/v1/scheduling/equipment/e14e0000-0000-4000-8000-000000000001/temperatures"), BIOMED, "{\"temperatureC\":4.8}", 422,
                "not_temperature_monitored");
        fails(post("/api/v1/scheduling/equipment/e14e0000-0000-4000-8000-000000000006/temperatures"), SCHEDULER, "{\"temperatureC\":4.8}", 403, null);

        JsonNode temps = ok(get("/api/v1/scheduling/equipment/e14e0000-0000-4000-8000-000000000007/temperatures"), BIOMED, null);
        assertThat(texts(temps, "source")).contains("IOT");
        assertThat(temps.toString()).contains("\"excursion\":true");
        JsonNode fridge = ok(get("/api/v1/scheduling/equipment?siteCode=LHR-AIRSIDE&category=VACCINE_FRIDGE"), SCHEDULER, null).get(0);
        assertThat(fridge.path("lastReading").path("temperatureC").asDouble()).isEqualTo(5.5);
        assertThat(fridge.path("openExcursions").asInt()).isZero();

        JsonNode report = ok(get("/api/v1/scheduling/cold-chain/report?equipmentId=e14e0000-0000-4000-8000-000000000007&month=" + YearMonth.now(LONDON)),
                NURSE, null);
        assertThat(report.path("days").size()).isPositive();
        assertThat(report.path("excursions").asInt()).isGreaterThanOrEqualTo(0);
        fails(get("/api/v1/scheduling/cold-chain/report?equipmentId=e14e0000-0000-4000-8000-000000000007&month=2026-13"), NURSE, null, 400, null);
    }

    // =================================================================== F05 capacity & charging

    @Test
    void f05CapacityWhatIfUtilisationAndCharging() throws Exception {
        LocalDate from = LocalDate.now(LONDON);
        LocalDate to = from.plusDays(60);
        JsonNode rows = ok(get("/api/v1/scheduling/capacity?from=" + from + "&to=" + to + "&siteCode=HQ-MC"), HEAD, null);
        assertThat(rows.size()).isBetween(2, 4);
        int base = 0;
        for (JsonNode r : rows) {
            assertThat(r.path("siteCode").asString()).isEqualTo("HQ-MC");
            assertThat(r.path("demandBySource").has("RUN_RATE")).isTrue();
            assertThat(r.path("gap").asInt()).isEqualTo(r.path("capacityAppointments").asInt() - r.path("forecastDemand").asInt());
            base += r.path("capacityAppointments").asInt();
        }
        assertThat(base).isPositive();
        JsonNode whatIf = ok(post("/api/v1/scheduling/capacity/what-if"), SCHEDULER, """
                {"siteCode":"HQ-MC","extraClinicianSessionsPerWeek":2,"extraCampaignDays":1,"from":"%s","to":"%s"}""".formatted(from, to));
        int extended = 0;
        for (JsonNode r : whatIf) {
            extended += r.path("capacityAppointments").asInt();
        }
        assertThat(extended).isGreaterThan(base);
        assertThat(ok(get("/api/v1/scheduling/capacity?from=" + from + "&to=" + to), CMO, null).size()).isGreaterThan(rows.size());
        fails(get("/api/v1/scheduling/capacity?from=" + from + "&to=" + to), RECEPTION, null, 403, null);

        JsonNode util = ok(get("/api/v1/scheduling/utilisation?from=" + from.minusDays(7) + "&to=" + to + "&groupBy=CLINICIAN"), ADMIN, null);
        assertThat(texts(util, "key")).contains("nurse1", "physician1");
        assertThat(ok(get("/api/v1/scheduling/utilisation?from=" + from + "&to=" + to), ADMIN, null).toString()).contains("HQ-MC");

        String month = YearMonth.now(java.time.ZoneOffset.UTC).toString();
        JsonNode usage = ok(get("/api/v1/scheduling/charging/usage?month=" + month), CMO, null);
        assertThat(usage.isArray()).isTrue();
        MvcResult csv = call(get("/api/v1/scheduling/charging/export?month=" + month), ADMIN, null).andExpect(status().isOk()).andReturn();
        assertThat(csv.getResponse().getContentType()).startsWith("text/csv");
        assertThat(csv.getResponse().getContentAsString()).startsWith("month,subsidiaryCode,chargeCode,service,appointments,amount");
        fails(get("/api/v1/scheduling/charging/usage?month=" + month), SCHEDULER, null, 403, null);
    }

    // =================================================================== catalogue, templates, telehealth, denials

    @Test
    void catalogueTemplatesTelehealthAndRoleDenials() throws Exception {
        JsonNode types = ok(get("/api/v1/scheduling/appointment-types"), employee("E1001"), null);
        assertThat(texts(types, "code")).contains("GENERAL_OH", "AUDIOMETRY", "VACCINATION", "TELEHEALTH_CONSULT", "SURVEILLANCE_PANEL");
        for (JsonNode t : types) {
            if ("AUDIOMETRY".equals(t.path("code").asString())) {
                assertThat(t.path("roomType").asString()).isEqualTo("AUDIO_BOOTH");
                assertThat(t.path("deviceCategory").asString()).isEqualTo("AUDIOMETER");
                assertThat(t.path("chargeCode").asString()).isEqualTo("OH-AUD");
            }
        }
        assertThat(ok(get("/api/v1/scheduling/service-catalogue"), MANAGER, null).size()).isGreaterThanOrEqualTo(15);
        String type = "{\"name\":\"Hearing re-test\",\"durationMinutes\":15,\"clinicianRoles\":[\"OH_CLINICIAN\"],\"roomType\":\"AUDIO_BOOTH\",\"chargeCode\":\"OH-AUD2\",\"internalPrice\":30}";
        fails(put("/api/v1/scheduling/appointment-types/AUDIO_RETEST"), SCHEDULER, type, 403, null);
        assertThat(ok(put("/api/v1/scheduling/appointment-types/AUDIO_RETEST"), ADMIN, type).path("durationMinutes").asInt()).isEqualTo(15);

        assertThat(ok(get("/api/v1/scheduling/sites/HQ-MC/resources"), BIOMED, null).toString()).contains("HQ-VAC", "SPIRO-HQ-01");
        JsonNode templates = ok(get("/api/v1/scheduling/sites/HQ-MC/templates"), SCHEDULER, null);
        assertThat(templates.size()).isEqualTo(10);
        // nurse1 is at HQ-MC on Tuesday mornings: an overlapping LHR-CAT session is refused
        fails(put("/api/v1/scheduling/sites/LHR-CAT/templates"), SCHEDULER, """
                [{"weekday":2,"start":"11:00","end":"16:00","clinicianUsername":"nurse1","roomCode":"LHRC-C1","appointmentTypes":["VACCINATION"]}]""",
                409, "resource_conflict");
        JsonNode saved = ok(put("/api/v1/scheduling/sites/LHR-CAT/templates"), SCHEDULER, """
                [{"weekday":2,"start":"13:00","end":"16:00","clinicianUsername":"nurse1","roomCode":"LHRC-C1","appointmentTypes":["FOOD_HANDLER_CLEARANCE","VACCINATION","NURSE_REVIEW"]},
                 {"weekday":4,"start":"13:00","end":"16:00","clinicianUsername":"nurse1","roomCode":"LHRC-C1","appointmentTypes":["FOOD_HANDLER_CLEARANCE","VACCINATION","NURSE_REVIEW"]}]""");
        assertThat(saved.size()).isEqualTo(2);
        fails(put("/api/v1/scheduling/sites/LHR-CAT/templates"), RECEPTION, "[]", 403, null);
        fails(put("/api/v1/scheduling/sites/LHR-CAT/templates"), SCHEDULER, """
                [{"weekday":8,"start":"13:00","end":"16:00","clinicianUsername":"nurse1"}]""", 400, null);

        // telehealth: the appointment's clinician and the employee get the join link; nobody else
        String tele = "e14a0000-0000-4000-8000-000000000004";
        JsonNode join = ok(post("/api/v1/scheduling/appointments/" + tele + "/telehealth"), PHYSICIAN, null);
        assertThat(join.path("joinUrl").asString()).startsWith("https://telehealth.wellnessz.local/room/");
        assertThat(join.path("documentationLink").asString()).isEqualTo("#/employees/E1003/chart/encounters");
        JsonNode employeeJoin = ok(post("/api/v1/scheduling/appointments/" + tele + "/telehealth"), user("crew1", "E1003", "/AIRLINE/OPS/CABIN", "EMPLOYEE"), null);
        assertThat(employeeJoin.path("joinUrl").asString()).isEqualTo(join.path("joinUrl").asString());
        fails(post("/api/v1/scheduling/appointments/" + tele + "/telehealth"), NURSE, null, 404, null);
        fails(post("/api/v1/scheduling/appointments/" + tele + "/telehealth"), SCHEDULER, null, 404, null);

        // role denials: biomedical / reception / employees / managers stay out of what is not theirs
        fails(get("/api/v1/scheduling/appointments?siteCode=HQ-MC"), BIOMED, null, 403, null);
        fails(post("/api/v1/scheduling/appointments"), RECEPTION, book("E1005", "GENERAL_OH", "HQ-MC", Instant.now().plus(Duration.ofDays(3)), null), 403, null);
        fails(get("/api/v1/scheduling/diary?siteCode=HQ-MC&date=" + LocalDate.now()), employee("E1001"), null, 403, null);
        fails(get("/api/v1/scheduling/queue?siteCode=HQ-MC"), MANAGER, null, 403, null);
        fails(get("/api/v1/scheduling/equipment"), RECEPTION, null, 403, null);
        fails(get("/api/v1/scheduling/excursions"), SCHEDULER, null, 403, null);
        fails(post("/api/v1/scheduling/campaigns/e14c0000-0000-4000-8000-000000000001/book"), NURSE, "{\"employeeIds\":[\"E1004\"]}", 403, null);
        fails(get("/api/v1/me/appointments"), SCHEDULER, null, 403, null);
        // the scheduler never reaches clinical records
        MvcResult chart = call(get("/api/v1/employees/E1004/results"), SCHEDULER, null).andReturn();
        assertThat(chart.getResponse().getStatus()).isIn(403, 404);
        MvcResult encounters = call(get("/api/v1/employees/E1004/chart"), SCHEDULER, null).andReturn();
        assertThat(encounters.getResponse().getStatus()).isIn(403, 404);

        // the scheduler's appointment list carries names and types only
        JsonNode today = ok(get("/api/v1/scheduling/appointments?siteCode=HQ-MC"), SCHEDULER, null);
        assertThat(today.toString()).contains("e14a0000-0000-4000-8000-000000000001", "Vaccination");
        call(get("/api/v1/scheduling/appointments"), SCHEDULER, null).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("filter_required"));
    }
}
