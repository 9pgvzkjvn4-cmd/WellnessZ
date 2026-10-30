package com.wellnessz.app;

import static com.wellnessz.app.PlatformIntegrationTest.user;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

import com.wellnessz.common.events.IntegrationEnvelope;
import com.wellnessz.documents.PdfRenderer;
import com.wellnessz.e11.TravelHealthJobs;
import com.wellnessz.e11.exposure.ExposureIncidentListener;
import com.wellnessz.e11.exposure.ExposurePathwayService;
import com.wellnessz.e11.publichealth.PublicHealthReportService;
import com.wellnessz.integration.outbox.OutboxEvent;
import com.wellnessz.integration.outbox.OutboxRepository;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Acceptance tests for E11 (contract §5) against the real endpoints: destination content and the travel consultation that records
 * vaccines in the E07 registry and shares a signed certificate; communicable-disease cases with status-only exclusion (food
 * handlers via E05 to catering hygiene), roster-based contact tracing within the 4-hour KPI, content-free contact notices,
 * check-ins and clearance; exposure pathways with protocol timers, the timer job and the incident feed; vaccination campaigns
 * with fast entry, adverse events and suppressed uptake; advisories with acknowledgement; public-health reports with deadlines,
 * submission (disclosure log) and correspondence – including the negative cases (roles, other people's data, suppression).
 */
class Increment5TravelHealthIntegrationTest extends IntegrationTestBase {

    @Autowired
    MockMvc mvc;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    JsonMapper json;
    @Autowired
    TravelHealthJobs jobs;
    @Autowired
    ExposurePathwayService exposureService;
    @Autowired
    PublicHealthReportService reportService;
    @Autowired
    ExposureIncidentListener incidentListener;
    @Autowired
    OutboxRepository outbox;
    @Autowired
    PdfRenderer renderer;

    static final JwtRequestPostProcessor NURSE = user("nurse1", "N1", null, "OH_CLINICIAN");
    static final JwtRequestPostProcessor NURSING_HEAD = user("nursinghead1", "NH1", null, "NURSING_HEAD", "OH_CLINICIAN");
    static final JwtRequestPostProcessor PHYSICIAN = user("physician1", "PH1", null, "OH_PHYSICIAN");
    static final JwtRequestPostProcessor CMO = user("cmo1", "CM1", null, "CMO");
    static final JwtRequestPostProcessor AME = user("ame1", "A1", null, "AME");
    static final JwtRequestPostProcessor CASE_MANAGER = user("clinician1", "C1", null, "OH_CLINICIAN", "CASE_MANAGER");
    static final JwtRequestPostProcessor HR = user("hr1", "H1", "/AIRLINE", "HR");
    static final JwtRequestPostProcessor MANAGER = user("manager1", "M2001", "/AIRLINE/CATERING", "MANAGER");
    static final JwtRequestPostProcessor HYGIENE = user("hygiene1", "HY1", "/AIRLINE/CATERING", "HYGIENE_MANAGER");
    static final JwtRequestPostProcessor CREW_E1003 = user("crew1", "E1003", "/AIRLINE/OPS/CABIN", "EMPLOYEE");
    static final JwtRequestPostProcessor CREW_E1002 = user("pilot1", "E1002", "/AIRLINE/OPS/FLIGHTOPS", "EMPLOYEE");

    static final int YEAR = LocalDate.now(ZoneOffset.UTC).getYear();
    static final String DEMO_CD_CASE = "e11de000-0000-4000-8000-000000000101";
    static final String DEMO_NEEDLESTICK = "e11de000-0000-4000-8000-000000000201";
    static final String DEMO_FUME = "e11de000-0000-4000-8000-000000000202";
    static final String DEMO_CAMPAIGN = "e11de000-0000-4000-8000-000000000401";
    static final String DEMO_ADVISORY = "e11de000-0000-4000-8000-000000000501";
    static final String DEMO_REPORT = "e11de000-0000-4000-8000-000000000601";
    static final String DEMO_CONSULTATION = "e11de000-0000-4000-8000-000000000301";

    // ------------------------------------------------------------------ helpers

    JsonNode body(MvcResult r) throws Exception {
        return json.readTree(r.getResponse().getContentAsString());
    }

    ResultActions postJson(String url, JwtRequestPostProcessor who, Object content) throws Exception {
        return mvc.perform(post(url).with(who).contentType(MediaType.APPLICATION_JSON)
                .content(content instanceof String s ? s : json.writeValueAsString(content)));
    }

    long count(String sql, Object... args) {
        Long n = jdbc.queryForObject(sql, Long.class, args);
        return n == null ? 0 : n;
    }

    long notifications(String address, String template) {
        return count("select count(*) from platform.notification where address = ? and template_key = ?", address, template);
    }

    List<String> bodies(String address, String template) {
        return jdbc.queryForList("select body from platform.notification where address = ? and template_key = ?", String.class, address, template);
    }

    static String email(String id) {
        return id.toLowerCase() + "@wellnessz.local";
    }

    /** A fresh employee with the given job / department / subsidiary. */
    String employee(String prefix, String subsidiary, String department, String jobCode, String location) {
        String id = prefix + ThreadLocalRandom.current().nextInt(100_000, 999_999);
        jdbc.update("""
                insert into masterdata.employee (employee_id, display_name, email, subsidiary_code, department_code, cost_centre, job_code,
                                                 location_code, shift_pattern, preferred_language, badge_id, manager_employee_id, updated_at)
                values (?, ?, ?, ?, ?, 'CC-T', ?, ?, 'EARLY', 'en', ?, 'M2001', now())""",
                id, "Tess Traveller " + id, email(id), subsidiary, department, jobCode, location, "BT-" + id);
        return id;
    }

    void duty(String source, String employeeId, String dutyRef, String group, Instant from, Instant to, String destination) {
        jdbc.update("""
                insert into masterdata.duty_assignment (id, source, source_ref, duty_ref, duty_date, starts_at, ends_at, employee_id, duty_role,
                                                        location_code, destination_code, proximity_group, received_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, null, 'LHR', ?, ?, now())""",
                UUID.randomUUID(), source, "T-" + UUID.randomUUID(), dutyRef, LocalDate.ofInstant(from, ZoneOffset.UTC),
                java.sql.Timestamp.from(from), java.sql.Timestamp.from(to), employeeId, destination, group);
    }

    List<String> outboxPayloads(String destination, String needle) {
        List<String> out = new ArrayList<>();
        for (OutboxEvent e : outbox.findAll()) {
            if (destination.equals(e.getDestination()) && e.getPayload() != null && e.getPayload().contains(needle)) {
                out.add(e.getPayload());
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ F01 travel & posting consultation

    @Test
    void f01DestinationContentRolesAndRecommendations() throws Exception {
        JsonNode all = body(mvc.perform(get("/api/v1/travel/destinations").with(NURSE)).andExpect(status().isOk()).andReturn());
        assertThat(all.size()).isGreaterThanOrEqualTo(15);
        mvc.perform(get("/api/v1/travel/destinations/NG").with(NURSE)).andExpect(status().isOk())
                .andExpect(jsonPath("$.recommendedVaccines[0]").value("YELLOW_FEVER")).andExpect(jsonPath("$.source").value("IN_HOUSE"))
                .andExpect(jsonPath("$.prophylaxis[0]").exists());
        // employees see the advice only
        mvc.perform(get("/api/v1/travel/destinations/SG").with(CREW_E1003)).andExpect(status().isOk())
                .andExpect(jsonPath("$.advice").exists()).andExpect(jsonPath("$.risks").doesNotExist())
                .andExpect(jsonPath("$.recommendedVaccines").doesNotExist());
        mvc.perform(get("/api/v1/travel/destinations").with(HR)).andExpect(status().isForbidden());

        Map<String, Object> dest = new LinkedHashMap<>();
        dest.put("name", "Mauritius");
        dest.put("stationCodes", List.of("MRU"));
        dest.put("risks", List.of("Dengue"));
        dest.put("recommendedVaccines", List.of("HEP_A", "TYPHOID"));
        dest.put("prophylaxis", List.of());
        dest.put("advice", "Use insect repellent during the day.");
        dest.put("source", "IN_HOUSE");
        dest.put("reviewedOn", LocalDate.now(ZoneOffset.UTC).toString());
        mvc.perform(put("/api/v1/travel/destinations/MU").with(NURSE).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(dest)))
                .andExpect(status().isForbidden());
        mvc.perform(put("/api/v1/travel/destinations/MU").with(PHYSICIAN).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(dest)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.stationCodes[0]").value("MRU"));
        dest.put("recommendedVaccines", List.of("UNOBTAINIUM"));
        mvc.perform(put("/api/v1/travel/destinations/MU").with(CMO).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(dest)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("unknown_vaccine"));

        // recommendations from the destination vs the E07 immunisation status (demo: E1003 had hepatitis A dose 1 and typhoid)
        JsonNode rec = body(mvc.perform(get("/api/v1/travel/recommendations").param("employeeId", "E1003").param("destinations", "SG")
                .with(NURSE)).andExpect(status().isOk()).andReturn());
        Map<String, String> statuses = new LinkedHashMap<>();
        rec.get("vaccines").forEach(v -> statuses.put(v.get("vaccineCode").asString(), v.get("status").asString()));
        assertThat(statuses).containsEntry("HEP_A", "INCOMPLETE").containsEntry("TYPHOID", "UP_TO_DATE").containsEntry("TDAP_IPV", "NOT_GIVEN");
        mvc.perform(get("/api/v1/travel/recommendations").param("employeeId", "E1003").param("destinations", "SG").with(HR))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/travel/recommendations").param("employeeId", "E1003").param("destinations", "XX").with(NURSE))
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("unknown_destination"));
    }

    @Test
    void f01ConsultationRecordsVaccinesInTheRegistryAndSharesTheCertificate() throws Exception {
        String emp = employee("TC", "CAB", "CABIN", "CABIN_CREW", "LHR");
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        Map<String, Object> cmd = new LinkedHashMap<>();
        cmd.put("employeeId", emp);
        cmd.put("trip", Map.of("destinations", List.of("NG"), "departDate", today.plusDays(21).toString(), "returnDate", today.plusDays(25).toString(),
                "purpose", "LAYOVER"));
        cmd.put("vaccinesGiven", List.of(Map.of("vaccineCode", "YELLOW_FEVER", "lotNumber", "YF-T-01", "site", "LEFT_DELTOID", "route", "SC"),
                Map.of("vaccineCode", "TYPHOID", "lotNumber", "TY-T-01", "site", "RIGHT_DELTOID", "route", "IM")));
        cmd.put("prophylaxis", List.of(Map.of("drug", "Atovaquone-proguanil", "regimen", "1 tablet daily from 2 days before", "startDate",
                today.plusDays(19).toString())));
        cmd.put("advice", "Bite avoidance; report fever within 3 months.");
        mvc.perform(post("/api/v1/travel/consultations").with(CREW_E1003).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(cmd)))
                .andExpect(status().isForbidden());
        JsonNode c = body(postJson("/api/v1/travel/consultations", NURSE, cmd).andExpect(status().isCreated()).andReturn());
        assertThat(c.get("vaccinesGiven")).hasSize(2);
        assertThat(c.get("vaccinesGiven").get(0).get("administrationId").asString()).isNotBlank();
        Map<String, String> statuses = new LinkedHashMap<>();
        c.get("recommendations").forEach(v -> statuses.put(v.get("vaccineCode").asString(), v.get("status").asString()));
        assertThat(statuses).containsEntry("YELLOW_FEVER", "UP_TO_DATE").containsEntry("TYPHOID", "UP_TO_DATE").containsEntry("HEP_A", "NOT_GIVEN");
        assertThat(c.get("recommendedProphylaxis").get(0).asString()).contains("Malaria");
        String certificate = c.get("certificateDocumentId").asString();
        // vaccines are in the E07 registry (mirrored into the E03 record) and the signed certificate is shared to the employee
        assertThat(count("select count(*) from surveillance.vaccine_administration where employee_id = ?", emp)).isEqualTo(2);
        assertThat(count("select count(*) from ohrecord.immunisation where employee_id = ?", emp)).isEqualTo(2);
        assertThat(count("select count(*) from docs.document where id = ?::uuid and template_code = 'TRAVEL_HEALTH_CERTIFICATE' and status = 'SIGNED'",
                certificate)).isEqualTo(1);
        assertThat(count("select count(*) from docs.document_share where document_id = ?::uuid and target = 'EMPLOYEE'", certificate)).isEqualTo(1);
        // the advice is encrypted at rest
        assertThat(jdbc.queryForObject("select advice from travelhealth.consultation where id = ?::uuid", String.class, c.get("id").asString()))
                .doesNotContain("Bite avoidance");

        JwtRequestPostProcessor traveller = user("traveller", emp, "/AIRLINE/OPS/CABIN", "EMPLOYEE");
        JsonNode mine = body(mvc.perform(get("/api/v1/me/travel").with(traveller)).andExpect(status().isOk()).andReturn());
        assertThat(mine.get("consultations")).hasSize(1);
        assertThat(mine.get("certificates").get(0).get("documentId").asString()).isEqualTo(certificate);
        mvc.perform(get("/api/v1/travel/consultations").param("employeeId", emp).with(NURSE)).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].advice").value("Bite avoidance; report fever within 3 months."));
        mvc.perform(get("/api/v1/travel/consultations").param("employeeId", emp).with(HR)).andExpect(status().isForbidden());

        // validation
        cmd.put("trip", Map.of("destinations", List.of("NG"), "departDate", today.plusDays(25).toString(), "returnDate", today.plusDays(21).toString(),
                "purpose", "LAYOVER"));
        postJson("/api/v1/travel/consultations", NURSE, cmd).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("invalid_dates"));
        cmd.put("trip", Map.of("destinations", List.of("NG"), "departDate", today.toString(), "returnDate", today.toString(), "purpose", "HOLIDAY"));
        postJson("/api/v1/travel/consultations", NURSE, cmd).andExpect(status().isBadRequest());
    }

    @Test
    void f01DemoTravellerSeesCertificateAndUpcomingRosterDestinations() throws Exception {
        JsonNode mine = body(mvc.perform(get("/api/v1/me/travel").with(CREW_E1003)).andExpect(status().isOk()).andReturn());
        assertThat(mine.get("consultations").get(0).get("id").asString()).isEqualTo(DEMO_CONSULTATION);
        assertThat(mine.get("certificates").get(0).get("documentId").asString()).isNotBlank();
        JsonNode upcoming = mine.get("upcoming");
        assertThat(upcoming.size()).isGreaterThanOrEqualTo(1);
        assertThat(upcoming.get(0).get("destinationCode").asString()).isEqualTo("DXB");
        assertThat(upcoming.get(0).get("countryCode").asString()).isEqualTo("AE");
        assertThat(upcoming.get(0).get("advice").asString()).isNotBlank();
        mvc.perform(get("/api/v1/me/travel").with(NURSE)).andExpect(status().isForbidden());
    }

    // ------------------------------------------------------------------ F02 communicable disease & contact tracing

    @Test
    void f02CaseExclusionTracingNotificationCheckInsAndClearance() throws Exception {
        String index = employee("TF", "CAT", "CATERING", "CATERING_ASSOC", "LHR");
        String near = employee("TN", "CAT", "CATERING", "CATERING_ASSOC", "LHR");
        String brief = employee("TB", "CAT", "CATERING", "CATERING_ASSOC", "LHR");
        String elsewhere = employee("TE", "CAT", "CATERING", "CATERING_ASSOC", "LHR");
        Instant shiftStart = Instant.now().truncatedTo(ChronoUnit.HOURS).minus(30, ChronoUnit.HOURS);
        String line = "TLINE-" + ThreadLocalRandom.current().nextInt(1000, 9999);
        duty("SHIFT", index, line, line + "-A", shiftStart, shiftStart.plus(8, ChronoUnit.HOURS), null);
        duty("SHIFT", near, line, line + "-A", shiftStart.plus(2, ChronoUnit.HOURS), shiftStart.plus(10, ChronoUnit.HOURS), null);
        duty("SHIFT", brief, line, line + "-B", shiftStart.plus(470, ChronoUnit.MINUTES), shiftStart.plus(12, ChronoUnit.HOURS), null);
        duty("SHIFT", elsewhere, "OTHER-" + line, line + "-C", shiftStart, shiftStart.plus(8, ChronoUnit.HOURS), null);

        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        Map<String, Object> create = Map.of("employeeId", index, "conditionCode", "NOROVIRUS", "onsetDate", today.minusDays(1).toString(),
                "confirmedOn", today.toString());
        postJson("/api/v1/communicable/cases", HR, create).andExpect(status().isForbidden());
        postJson("/api/v1/communicable/cases", NURSE, Map.of("employeeId", index, "conditionCode", "PLAGUE", "onsetDate", today.toString()))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("unknown_condition"));
        JsonNode cd = body(postJson("/api/v1/communicable/cases", NURSE, create).andExpect(status().isCreated()).andReturn());
        String id = cd.get("id").asString();
        assertThat(cd.get("ref").asString()).matches("CD-" + YEAR + "-\\d{6}");
        assertThat(cd.get("status").asString()).isEqualTo("CONFIRMED");
        assertThat(cd.get("notifiable").asBoolean()).isTrue();
        assertThat(cd.get("reportDueAt").asString()).isNotBlank();
        assertThat(cd.get("foodHandler").asBoolean()).isTrue();
        String caseId = cd.get("caseId").asString();

        // exclusion: status-only feed; the food handler goes through the E05 exclusion to catering hygiene
        JsonNode ex = body(postJson("/api/v1/communicable/cases/" + id + "/exclusion", NURSE,
                Map.of("decision", "EXCLUDED", "from", today.toString(), "returnCriteria", List.of("SYMPTOM_FREE_48H")))
                .andExpect(status().isOk()).andReturn());
        assertThat(ex.get("status").asString()).isEqualTo("EXCLUDED");
        assertThat(ex.get("exclusion").get("until").asString()).isEqualTo(today.plusDays(2).toString());
        assertThat(jdbc.queryForObject("select fitness_status from casemgmt.oh_case where id = ?::uuid", String.class, caseId))
                .isEqualTo("TEMPORARILY_UNFIT");
        assertThat(jdbc.queryForObject("select status from fitness.food_handler_episode where case_id = ?::uuid", String.class, caseId))
                .isEqualTo("EXCLUDED");
        List<String> hygiene = outboxPayloads("catering-hygiene", index);
        assertThat(hygiene).isNotEmpty();
        assertThat(hygiene).allSatisfy(p -> assertThat(p.toLowerCase()).doesNotContain("norovirus").doesNotContain("gi_illness"));
        assertThat(count("select count(*) from platform.disclosure_log where employee_id = ? and recipient_type = 'HYGIENE_MANAGER'", index))
                .isPositive();
        // catering hygiene: status and dates only (E05 view); no access to the communicable case itself; manager neither
        JsonNode rows = body(mvc.perform(get("/api/v1/fitness/food-handlers").param("department", "CATERING").with(HYGIENE))
                .andExpect(status().isOk()).andReturn());
        JsonNode row = null;
        for (JsonNode r : rows) {
            if (index.equals(r.get("employeeId").asString())) {
                row = r;
            }
        }
        assertThat(row).isNotNull();
        assertThat(row.get("status").asString()).isEqualTo("EXCLUDED");
        assertThat(row.toString().toLowerCase()).doesNotContain("norovirus").doesNotContain("gi_illness");
        mvc.perform(get("/api/v1/communicable/cases/" + id).with(HYGIENE)).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/communicable/cases/" + id).with(MANAGER)).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/communicable/cases/" + id + "/contacts").with(HR)).andExpect(status().isForbidden());
        postJson("/api/v1/communicable/cases/" + id + "/exclusion", NURSE, Map.of("decision", "CLEARED", "from", today.toString()))
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("use_clear"));

        // contact tracing from the shift data, within the 4-hour KPI
        Map<String, Object> trace = Map.of("windowFrom", shiftStart.minus(1, ChronoUnit.HOURS).toString(), "windowTo", Instant.now().toString(),
                "include", List.of("SAME_DUTY", "PROXIMITY_GROUP", "FACILITY"));
        postJson("/api/v1/communicable/cases/" + id + "/trace", NURSE, Map.of("windowFrom", Instant.now().toString(),
                "windowTo", Instant.now().minus(1, ChronoUnit.DAYS).toString(), "include", List.of("SAME_DUTY")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("invalid_window"));
        JsonNode traced = body(postJson("/api/v1/communicable/cases/" + id + "/trace", NURSE, trace).andExpect(status().isOk()).andReturn());
        assertThat(traced.get("generatedAt").asString()).isNotBlank();
        assertThat(traced.get("hoursSinceConfirmation").asDouble()).isLessThan(4.0);
        JsonNode contacts = traced.get("contacts");
        assertThat(contacts).hasSize(2);
        assertThat(contacts.get(0).get("employeeId").asString()).isEqualTo(near);
        assertThat(contacts.get(0).get("riskLevel").asString()).isEqualTo("HIGH");
        assertThat(contacts.get(0).get("contactType").asString()).isEqualTo("PROXIMITY_GROUP");
        assertThat(contacts.get(0).get("overlapMinutes").asInt()).isEqualTo(360);
        assertThat(contacts.get(1).get("employeeId").asString()).isEqualTo(brief);
        assertThat(contacts.get(1).get("riskLevel").asString()).isEqualTo("LOW");
        assertThat(contacts.get(1).get("contactType").asString()).isEqualTo("SAME_DUTY");
        assertThat(jdbc.queryForObject("select trace_hours from travelhealth.cd_case where id = ?::uuid", Double.class, id)).isLessThan(4.0);
        mvc.perform(get("/api/v1/communicable/cases/" + id).with(NURSE)).andExpect(status().isOk())
                .andExpect(jsonPath("$.traceWithinKpi").value(true)).andExpect(jsonPath("$.contacts").value(2));

        // notification: content-free – never the index case or the condition
        String nearContact = contacts.get(0).get("id").asString();
        postJson("/api/v1/communicable/cases/" + id + "/contacts/notify", NURSE, Map.of("contactIds", List.of(UUID.randomUUID())))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("unknown_contact"));
        postJson("/api/v1/communicable/cases/" + id + "/contacts/notify", NURSE, Map.of("contactIds", List.of(nearContact)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.notified").value(1)).andExpect(jsonPath("$.checkInsScheduled").value(3));
        postJson("/api/v1/communicable/cases/" + id + "/contacts/notify", NURSE, Map.of("contactIds", List.of(nearContact)))
                .andExpect(jsonPath("$.alreadyNotified").value(1));
        assertThat(bodies(email(near), "tracing.contact-notice")).singleElement().satisfies(b -> {
            assertThat(b).contains("Occupational Health needs to speak with you");
            assertThat(b.toLowerCase()).doesNotContain("norovirus").doesNotContain(index.toLowerCase()).doesNotContain("tess traveller " + index.toLowerCase());
        });
        assertThat(notifications(email(brief), "tracing.contact-notice")).isZero();
        JsonNode listed = body(mvc.perform(get("/api/v1/communicable/cases/" + id + "/contacts").with(NURSE)).andExpect(status().isOk()).andReturn());
        assertThat(listed.get(0).get("notifiedAt").asString()).isNotBlank();
        assertThat(listed.get(0).get("monitoringUntil").asString()).isEqualTo(today.plusDays(2).toString());

        // check-ins: the contact's own list, symptomatic alert to the nurse, other people get 404
        JwtRequestPostProcessor contactUser = user("contact", near, "/AIRLINE/CATERING", "EMPLOYEE");
        JsonNode checkIns = body(mvc.perform(get("/api/v1/me/check-ins").with(contactUser)).andExpect(status().isOk()).andReturn());
        assertThat(checkIns).hasSize(1);
        assertThat(checkIns.toString().toLowerCase()).doesNotContain("norovirus");
        String checkIn = checkIns.get(0).get("id").asString();
        postJson("/api/v1/me/check-ins/" + checkIn, CREW_E1003, Map.of("symptoms", List.of("NONE"))).andExpect(status().isNotFound());
        postJson("/api/v1/me/check-ins/" + checkIn, contactUser, Map.of("symptoms", List.of("NONE", "COUGH")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("invalid_symptoms"));
        long alertsBefore = notifications("nurse1@wellnessz.local", "exposure.timer-alert");
        postJson("/api/v1/me/check-ins/" + checkIn, contactUser, Map.of("symptoms", List.of("VOMITING"), "temperatureC", 37.4))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("DONE"));
        postJson("/api/v1/me/check-ins/" + checkIn, contactUser, Map.of("symptoms", List.of("NONE")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("already_submitted"));
        assertThat(notifications("nurse1@wellnessz.local", "exposure.timer-alert")).isEqualTo(alertsBefore + 1);
        assertThat(count("select count(*) from platform.notification where address = 'nurse1@wellnessz.local' and template_key = "
                + "'exposure.timer-alert' and body like ? and body like '%symptomatic contact%'", "%" + cd.get("caseRef").asString() + "%")).isEqualTo(1);
        mvc.perform(get("/api/v1/communicable/cases/" + id + "/contacts").with(NURSE)).andExpect(jsonPath("$[0].symptomatic").value(true));

        // daily job: reminder for today's pending check-ins (once)
        String other = contacts.get(1).get("id").asString();
        postJson("/api/v1/communicable/cases/" + id + "/contacts/notify", NURSE, Map.of("contactIds", List.of(other))).andExpect(status().isOk());
        jobs.runDaily(today);
        jobs.runDaily(today);
        assertThat(notifications(email(brief), "tracing.check-in")).isEqualTo(1);

        // clearance against the return criteria (E05 clearance for the food handler)
        postJson("/api/v1/communicable/cases/" + id + "/clear", NURSE, Map.of("criteriaMet", List.of("OH_REVIEW")))
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("return_criteria_not_met"));
        postJson("/api/v1/communicable/cases/" + id + "/clear", NURSE, Map.of("criteriaMet", List.of("SYMPTOM_FREE_48H")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CLEARED"));
        assertThat(jdbc.queryForObject("select fitness_status from casemgmt.oh_case where id = ?::uuid", String.class, caseId)).isEqualTo("FIT");
        assertThat(jdbc.queryForObject("select status from fitness.food_handler_episode where case_id = ?::uuid", String.class, caseId))
                .isEqualTo("FIT_TO_HANDLE_FOOD");
        postJson("/api/v1/communicable/cases/" + id + "/clear", NURSE, Map.of("criteriaMet", List.of("SYMPTOM_FREE_48H")))
                .andExpect(status().isConflict());
    }

    @Test
    void f02DemoNorovirusCaseTracesCateringLine2() throws Exception {
        mvc.perform(get("/api/v1/communicable/conditions").with(CASE_MANAGER)).andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.code == 'NOROVIRUS')].foodHandlerExclusion").value(true));
        mvc.perform(get("/api/v1/communicable/conditions").with(HR)).andExpect(status().isForbidden());
        JsonNode cd = body(mvc.perform(get("/api/v1/communicable/cases/" + DEMO_CD_CASE).with(NURSE)).andExpect(status().isOk()).andReturn());
        assertThat(cd.get("status").asString()).isEqualTo("EXCLUDED");
        assertThat(cd.get("conditionCode").asString()).isEqualTo("NOROVIRUS");
        Instant from = LocalDate.now(ZoneOffset.UTC).minusDays(2).atStartOfDay(ZoneOffset.UTC).toInstant();
        JsonNode traced = body(postJson("/api/v1/communicable/cases/" + DEMO_CD_CASE + "/trace", NURSE, Map.of("windowFrom", from.toString(),
                "windowTo", Instant.now().toString(), "include", List.of("SAME_DUTY", "PROXIMITY_GROUP"))).andExpect(status().isOk()).andReturn());
        List<String> high = new ArrayList<>();
        traced.get("contacts").forEach(c -> {
            if ("HIGH".equals(c.get("riskLevel").asString())) {
                high.add(c.get("employeeId").asString());
            }
        });
        assertThat(high).contains("E1010", "M2001");
        assertThat(traced.get("hoursSinceConfirmation").asDouble()).isLessThan(4.0);
        JsonNode list = body(mvc.perform(get("/api/v1/communicable/cases").param("status", "EXCLUDED").with(NURSE)).andExpect(status().isOk()).andReturn());
        assertThat(list.toString()).contains(DEMO_CD_CASE);
    }

    // ------------------------------------------------------------------ F03 exposure pathways with protocol timers

    @Test
    void f03NeedlestickTimersCompletionAndTimerJob() throws Exception {
        mvc.perform(get("/api/v1/exposures/pathways").with(CASE_MANAGER)).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(5));
        mvc.perform(get("/api/v1/exposures/chemicals").with(NURSE)).andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.code == 'JET_A1')].sdsUrl").exists());
        mvc.perform(get("/api/v1/exposures/pathways").with(HR)).andExpect(status().isForbidden());

        String emp = employee("TX", "GND", "GROUND", "CLEANER", "LHR");
        Instant occurred = Instant.now().minus(100, ChronoUnit.MINUTES);
        Map<String, Object> cmd = new LinkedHashMap<>();
        cmd.put("employeeId", emp);
        cmd.put("pathway", "NEEDLESTICK_BBP");
        cmd.put("occurredAt", occurred.toString());
        cmd.put("details", "Sharps injury to the right thumb while emptying a bin.");
        cmd.put("sourceKnown", false);
        postJson("/api/v1/exposures", HR, cmd).andExpect(status().isForbidden());
        JsonNode x = body(postJson("/api/v1/exposures", NURSE, cmd).andExpect(status().isCreated()).andReturn());
        String id = x.get("id").asString();
        assertThat(x.get("ref").asString()).matches("EXP-" + YEAR + "-\\d{6}");
        Map<String, String> timers = new LinkedHashMap<>();
        x.get("timers").forEach(t -> timers.put(t.get("code").asString(), t.get("status").asString()));
        assertThat(timers).containsExactly(Map.entry("FIRST_AID", "OVERDUE"), Map.entry("RISK_ASSESSMENT", "OVERDUE"),
                Map.entry("PEP_DECISION", "PENDING"), Map.entry("SOURCE_TESTING", "PENDING"));
        assertThat(x.get("serology")).hasSize(3);
        assertThat(x.get("serology").get(0).get("dueOn").asString()).isEqualTo(LocalDate.ofInstant(occurred, ZoneOffset.UTC).plusDays(42).toString());
        assertThat(x.get("status").asString()).isEqualTo("OVERDUE");
        String caseId = x.get("caseId").asString();
        assertThat(count("select count(*) from casemgmt.case_task where case_id = ?::uuid and status = 'OPEN'", caseId)).isEqualTo(4);
        assertThat(jdbc.queryForObject("select case_type from casemgmt.oh_case where id = ?::uuid", String.class, caseId)).isEqualTo("EXPOSURE");
        assertThat(jdbc.queryForObject("select details from travelhealth.exposure where id = ?::uuid", String.class, id)).doesNotContain("thumb");

        // timer job: overdue first aid + risk assessment, PEP decision due within 30 minutes – once per task and level
        String caseRef = x.get("caseRef").asString();
        exposureService.alertTimers(Instant.now());
        long alerts = count("select count(*) from platform.notification where address = 'nurse1@wellnessz.local' and template_key = 'exposure.timer-alert' "
                + "and body like ?", "%" + caseRef + "%");
        assertThat(alerts).isEqualTo(3);
        exposureService.alertTimers(Instant.now());
        assertThat(count("select count(*) from platform.notification where address = 'nurse1@wellnessz.local' and template_key = 'exposure.timer-alert' "
                + "and body like ?", "%" + caseRef + "%")).isEqualTo(3);
        exposureService.alertTimers(Instant.now().plus(25, ChronoUnit.MINUTES));
        assertThat(count("select count(*) from platform.notification where address = 'nurse1@wellnessz.local' and template_key = 'exposure.timer-alert' "
                + "and body like ?", "%" + caseRef + "%")).isEqualTo(4);

        // completion inside the protocol window (KPI), kernel task closed, no double completion
        JsonNode done = body(postJson("/api/v1/exposures/" + id + "/tasks/PEP_DECISION/complete", NURSE, Map.of("note", "PEP offered and started"))
                .andExpect(status().isOk()).andReturn());
        JsonNode pep = null;
        for (JsonNode t : done.get("timers")) {
            if ("PEP_DECISION".equals(t.get("code").asString())) {
                pep = t;
            }
        }
        assertThat(pep.get("status").asString()).isEqualTo("DONE");
        assertThat(pep.get("withinWindow").asBoolean()).isTrue();
        assertThat(count("select count(*) from casemgmt.case_task where case_id = ?::uuid and status = 'DONE'", caseId)).isEqualTo(1);
        postJson("/api/v1/exposures/" + id + "/tasks/PEP_DECISION/complete", NURSE, "{}").andExpect(status().isConflict());
        postJson("/api/v1/exposures/" + id + "/tasks/NOPE/complete", NURSE, "{}").andExpect(status().isNotFound());
        postJson("/api/v1/exposures/" + id + "/tasks/SEROLOGY_6W/complete", NURSE, "{}").andExpect(status().isOk())
                .andExpect(jsonPath("$.serology[0].done").value(true));
        mvc.perform(get("/api/v1/exposures").param("status", "OPEN").with(NURSE)).andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == '" + id + "')]").exists());
        mvc.perform(get("/api/v1/exposures/" + id).with(HR)).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/exposures/" + UUID.randomUUID()).with(NURSE)).andExpect(status().isNotFound());

        // chemical exposure links the SDS from the register
        cmd.put("pathway", "CHEMICAL_DGR");
        cmd.put("occurredAt", Instant.now().minus(5, ChronoUnit.MINUTES).toString());
        cmd.put("chemicalCode", "NOPE_CHEM");
        postJson("/api/v1/exposures", NURSE, cmd).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("unknown_chemical"));
        cmd.put("chemicalCode", "JET_A1");
        postJson("/api/v1/exposures", NURSE, cmd).andExpect(status().isCreated()).andExpect(jsonPath("$.chemical.sdsUrl").exists())
                .andExpect(jsonPath("$.timers[0].code").value("SDS_REVIEW"));
        cmd.put("pathway", "MAGIC");
        postJson("/api/v1/exposures", NURSE, cmd).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("unknown_pathway"));
    }

    @Test
    void f03IncidentFeedStartsThePathwayOnce() throws Exception {
        String emp = employee("TI", "CAB", "CABIN", "CABIN_CREW", "LHR");
        UUID incident = UUID.randomUUID();
        Map<String, Object> facts = new LinkedHashMap<>();
        facts.put("incidentId", incident.toString());
        facts.put("externalRef", "EHS-T-" + incident.toString().substring(0, 6));
        facts.put("employeeId", emp);
        facts.put("incidentType", "Fume event");
        facts.put("injury", false);
        facts.put("exposureType", "FUME");
        facts.put("incidentOccurredAt", Instant.now().minus(30, ChronoUnit.MINUTES).toString());
        String payload = json.writeValueAsString(facts);
        for (int i = 0; i < 2; i++) {
            incidentListener.onIncident(new IntegrationEnvelope(UUID.randomUUID(), ExposureIncidentListener.EVENT_TYPE, "EHS_INCIDENT", incident.toString(),
                    payload, Map.of(), Instant.now()));
        }
        assertThat(count("select count(*) from travelhealth.exposure where incident_id = ? and pathway = 'CABIN_FUME'", incident)).isEqualTo(1);
        assertThat(count("select count(*) from casemgmt.oh_case where source = 'EHS_INCIDENT' and source_ref = ?", incident.toString())).isEqualTo(1);
        // not an exposure → nothing
        facts.put("incidentId", UUID.randomUUID().toString());
        facts.put("exposureType", null);
        incidentListener.onIncident(new IntegrationEnvelope(UUID.randomUUID(), ExposureIncidentListener.EVENT_TYPE, "EHS_INCIDENT", "x",
                json.writeValueAsString(facts), Map.of(), Instant.now()));
        assertThat(count("select count(*) from travelhealth.exposure where employee_id = ?", emp)).isEqualTo(1);
    }

    @Test
    void f03DemoExposures() throws Exception {
        JsonNode needle = body(mvc.perform(get("/api/v1/exposures/" + DEMO_NEEDLESTICK).with(NURSE)).andExpect(status().isOk()).andReturn());
        assertThat(needle.get("pathway").asString()).isEqualTo("NEEDLESTICK_BBP");
        assertThat(needle.get("details").asString()).contains("Needle-stick");
        assertThat(needle.toString()).contains("\"code\":\"PEP_DECISION\"");
        mvc.perform(get("/api/v1/exposures/" + DEMO_FUME).with(AME)).andExpect(status().isOk()).andExpect(jsonPath("$.flightRef").value("WZ101"));
    }

    // ------------------------------------------------------------------ F04 vaccination campaigns

    @Test
    void f04CampaignInviteFastEntryAdverseEventsAndSuppressedUptake() throws Exception {
        String sub = "T" + ThreadLocalRandom.current().nextInt(100, 999);
        List<String> staff = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            staff.add(employee("TV", sub, "GROUND", "RAMP_AGENT", "LHR"));
        }
        String small = "S" + ThreadLocalRandom.current().nextInt(100, 999);
        String loner = employee("TS", small, "GROUND", "RAMP_AGENT", "LHR");
        String outsider = employee("TO", "CORP", "CORP", "OFFICE_ADMIN", "HQ");
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        Map<String, Object> cmd = new LinkedHashMap<>();
        cmd.put("name", "Test influenza " + sub);
        cmd.put("vaccineCode", "INFLUENZA");
        cmd.put("eligibility", Map.of("all", false, "subsidiaries", List.of(sub, small)));
        cmd.put("startDate", today.minusDays(1).toString());
        cmd.put("endDate", today.plusDays(30).toString());
        cmd.put("siteCodes", List.of("LHR-CARGO"));
        cmd.put("targetUptakePct", 75);
        postJson("/api/v1/vaccination-campaigns", PHYSICIAN, cmd).andExpect(status().isForbidden());
        Map<String, Object> bad = new LinkedHashMap<>(cmd);
        bad.put("eligibility", Map.of("all", false));
        postJson("/api/v1/vaccination-campaigns", NURSING_HEAD, bad).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("eligibility_required"));
        JsonNode c = body(postJson("/api/v1/vaccination-campaigns", NURSING_HEAD, cmd).andExpect(status().isCreated()).andReturn());
        String id = c.get("id").asString();
        assertThat(c.get("status").asString()).isEqualTo("ACTIVE");

        // invitations: content-free, never the vaccine or the disease
        postJson("/api/v1/vaccination-campaigns/" + id + "/invite", NURSE, "{}").andExpect(status().isForbidden());
        postJson("/api/v1/vaccination-campaigns/" + id + "/invite", NURSING_HEAD, "{}").andExpect(status().isOk())
                .andExpect(jsonPath("$.invited").value(5));
        postJson("/api/v1/vaccination-campaigns/" + id + "/invite", NURSING_HEAD, Map.of("employeeIds", List.of(staff.get(0), outsider)))
                .andExpect(jsonPath("$.alreadyInvited").value(1)).andExpect(jsonPath("$.notEligible").value(1));
        assertThat(bodies(email(staff.get(0)), "campaign.invitation")).singleElement()
                .satisfies(b -> assertThat(b.toLowerCase()).doesNotContain("influenza").contains("vaccination campaign"));

        // fast entry into the E07 registry with the campaign id (GS1 barcode), consent and eligibility enforced
        Map<String, Object> admin = new LinkedHashMap<>();
        admin.put("badgeId", "BT-" + staff.get(0));
        admin.put("lotBarcode", "(01)05000456012345(17)" + today.plusYears(1).format(java.time.format.DateTimeFormatter.ofPattern("yyMMdd")) + "(10)FLU-T-9");
        admin.put("site", "LEFT_DELTOID");
        admin.put("route", "IM");
        admin.put("consentConfirmed", false);
        postJson("/api/v1/vaccination-campaigns/" + id + "/administrations", NURSE, admin).andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("consent_required"));
        admin.put("consentConfirmed", true);
        postJson("/api/v1/vaccination-campaigns/" + id + "/administrations", HR, admin).andExpect(status().isForbidden());
        postJson("/api/v1/vaccination-campaigns/" + id + "/administrations", NURSE, admin).andExpect(status().isCreated())
                .andExpect(jsonPath("$.lotNumber").value("FLU-T-9")).andExpect(jsonPath("$.vaccineCode").value("INFLUENZA"));
        postJson("/api/v1/vaccination-campaigns/" + id + "/administrations", NURSE, admin).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("already_vaccinated"));
        for (String e : List.of(staff.get(1), staff.get(2))) {
            postJson("/api/v1/vaccination-campaigns/" + id + "/administrations", NURSE, Map.of("employeeId", e, "lotNumber", "FLU-T-9",
                    "site", "RIGHT_DELTOID", "route", "IM", "consentConfirmed", true)).andExpect(status().isCreated());
        }
        postJson("/api/v1/vaccination-campaigns/" + id + "/administrations", NURSE, Map.of("employeeId", outsider, "lotNumber", "FLU-T-9",
                "site", "RIGHT_DELTOID", "route", "IM", "consentConfirmed", true)).andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("not_eligible"));
        assertThat(count("select count(*) from surveillance.vaccine_administration where campaign_id = ?::uuid", id)).isEqualTo(3);

        // decline (reason encrypted) and adverse event (only after a vaccination in the campaign)
        postJson("/api/v1/vaccination-campaigns/" + id + "/declines", NURSE, Map.of("employeeId", staff.get(3), "reason", "Egg allergy history"))
                .andExpect(status().isCreated());
        postJson("/api/v1/vaccination-campaigns/" + id + "/declines", NURSE, Map.of("employeeId", staff.get(3)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("already_declined"));
        assertThat(jdbc.queryForObject("select reason from travelhealth.campaign_decline where campaign_id = ?::uuid", String.class, id))
                .doesNotContain("Egg");
        Map<String, Object> ae = Map.of("employeeId", staff.get(3), "event", "FEVER", "severity", "MILD", "onsetAt", Instant.now().toString(),
                "actions", "Paracetamol advised");
        postJson("/api/v1/vaccination-campaigns/" + id + "/adverse-events", NURSE, ae).andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("not_vaccinated_in_campaign"));
        Map<String, Object> ae2 = new LinkedHashMap<>(ae);
        ae2.put("employeeId", staff.get(0));
        ae2.put("event", "SYNCOPE");
        postJson("/api/v1/vaccination-campaigns/" + id + "/adverse-events", NURSE, ae2).andExpect(status().isCreated())
                .andExpect(jsonPath("$.administrationId").exists());
        ae2.put("event", "SNEEZING");
        postJson("/api/v1/vaccination-campaigns/" + id + "/adverse-events", NURSE, ae2).andExpect(status().isBadRequest());

        // uptake by subsidiary: 3 of 4 vaccinated, 1 declined; the single-person subsidiary is suppressed (min group 3 in tests)
        mvc.perform(get("/api/v1/vaccination-campaigns/" + id + "/uptake").with(NURSE)).andExpect(status().isForbidden());
        JsonNode rows = body(mvc.perform(get("/api/v1/vaccination-campaigns/" + id + "/uptake").param("groupBy", "SUBSIDIARY").with(CMO))
                .andExpect(status().isOk()).andReturn());
        Map<String, JsonNode> byKey = new LinkedHashMap<>();
        rows.forEach(r -> byKey.put(r.get("key").asString(), r));
        assertThat(byKey.get(sub).get("eligible").asInt()).isEqualTo(4);
        assertThat(byKey.get(sub).get("vaccinated").asInt()).isEqualTo(3);
        assertThat(byKey.get(sub).get("declined").asInt()).isEqualTo(1);
        assertThat(byKey.get(sub).get("uptakePct").asDouble()).isEqualTo(75.0);
        assertThat(byKey.get(small).get("suppressed").asBoolean()).isTrue();
        assertThat(byKey.get(small).has("eligible")).isFalse();
        mvc.perform(get("/api/v1/vaccination-campaigns/" + id + "/uptake").param("groupBy", "UNIT").with(CMO)).andExpect(status().isBadRequest());

        // employee view and response
        JwtRequestPostProcessor me = user("loner", loner, "/AIRLINE/OPS/GROUND", "EMPLOYEE");
        JsonNode mine = body(mvc.perform(get("/api/v1/me/vaccination-campaigns").with(me)).andExpect(status().isOk()).andReturn());
        assertThat(mine.toString()).contains(id);
        postJson("/api/v1/me/vaccination-campaigns/" + id + "/response", me, Map.of("response", "DECLINE")).andExpect(status().isOk())
                .andExpect(jsonPath("$.response").value("DECLINE"));
        JwtRequestPostProcessor notEligible = user("outsider", outsider, "/AIRLINE/CORP", "EMPLOYEE");
        postJson("/api/v1/me/vaccination-campaigns/" + id + "/response", notEligible, Map.of("response", "ACCEPT")).andExpect(status().isNotFound());
        JwtRequestPostProcessor vaccinated = user("vacc", staff.get(0), "/AIRLINE/OPS/GROUND", "EMPLOYEE");
        postJson("/api/v1/me/vaccination-campaigns/" + id + "/response", vaccinated, Map.of("response", "ACCEPT")).andExpect(status().isConflict());
    }

    @Test
    void f04DemoCampaignIsActiveWithAdverseEvent() throws Exception {
        JsonNode c = body(mvc.perform(get("/api/v1/vaccination-campaigns/" + DEMO_CAMPAIGN).with(NURSE)).andExpect(status().isOk()).andReturn());
        assertThat(c.get("status").asString()).isEqualTo("ACTIVE");
        assertThat(c.get("adverseEvents").asInt()).isEqualTo(1);
        mvc.perform(get("/api/v1/vaccination-campaigns").with(CMO)).andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == '" + DEMO_CAMPAIGN + "')]").exists());
        mvc.perform(get("/api/v1/vaccination-campaigns").with(HR)).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/vaccination-campaigns/" + DEMO_CAMPAIGN + "/adverse-events").with(NURSE)).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].actions").value(org.hamcrest.Matchers.containsString("cold compress")));
        JsonNode rows = body(mvc.perform(get("/api/v1/vaccination-campaigns/" + DEMO_CAMPAIGN + "/uptake").param("groupBy", "DEPARTMENT")
                .with(NURSING_HEAD)).andExpect(status().isOk()).andReturn());
        assertThat(rows.size()).isPositive();
    }

    // ------------------------------------------------------------------ F05 advisories

    @Test
    void f05AdvisoryTargetsRosteredCrewAndBaseStaffWithAcknowledgement() throws Exception {
        String station = "T" + (char) ('A' + ThreadLocalRandom.current().nextInt(26)) + (char) ('A' + ThreadLocalRandom.current().nextInt(26))
                + ThreadLocalRandom.current().nextInt(10, 99);
        String crew = employee("TR", "CAB", "CABIN", "CABIN_CREW", "LHR");
        String baseAgent = employee("TA", "GND", "GROUND", "RAMP_AGENT", station);
        String baseOther = employee("TO", "GND", "GROUND", "CLEANER", station);
        Instant flight = Instant.now().plus(2, ChronoUnit.DAYS);
        duty("CREW_ROSTER", crew, "WZ9" + ThreadLocalRandom.current().nextInt(10, 99), "G-TEST", flight, flight.plus(7, ChronoUnit.HOURS), station);
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        Map<String, Object> cmd = new LinkedHashMap<>();
        cmd.put("title", "Water supply interruption at " + station);
        cmd.put("destinationCode", station);
        cmd.put("baseCode", station);
        cmd.put("roles", List.of("RAMP_AGENT"));
        cmd.put("severity", "WARNING");
        cmd.put("body", "Drink bottled water only during your stay.");
        cmd.put("validFrom", today.toString());
        cmd.put("validUntil", today.plusDays(7).toString());
        postJson("/api/v1/advisories", NURSE, cmd).andExpect(status().isForbidden());
        Map<String, Object> noTarget = new LinkedHashMap<>(cmd);
        noTarget.remove("destinationCode");
        noTarget.remove("baseCode");
        postJson("/api/v1/advisories", AME, noTarget).andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("target_required"));
        JsonNode a = body(postJson("/api/v1/advisories", AME, cmd).andExpect(status().isCreated()).andReturn());
        String id = a.get("id").asString();
        // two targets: below the minimum group size the statistics are suppressed
        assertThat(a.get("suppressed").asBoolean()).isTrue();
        assertThat(a.has("ratePct")).isFalse();
        assertThat(notifications(email(crew), "advisory.published")).isEqualTo(1);
        assertThat(notifications(email(baseAgent), "advisory.published")).isEqualTo(1);
        assertThat(notifications(email(baseOther), "advisory.published")).isZero();

        JwtRequestPostProcessor crewUser = user("crewT", crew, "/AIRLINE/OPS/CABIN", "EMPLOYEE");
        JsonNode mine = body(mvc.perform(get("/api/v1/me/advisories").with(crewUser)).andExpect(status().isOk()).andReturn());
        assertThat(mine.toString()).contains(id);
        mvc.perform(post("/api/v1/me/advisories/" + id + "/acknowledge").with(crewUser)).andExpect(status().isOk())
                .andExpect(jsonPath("$.acknowledgedAt").exists());
        mvc.perform(post("/api/v1/me/advisories/" + id + "/acknowledge").with(user("x", baseOther, null, "EMPLOYEE"))).andExpect(status().isNotFound());
        JsonNode acks = body(mvc.perform(get("/api/v1/advisories/" + id + "/acknowledgements").with(NURSE)).andExpect(status().isOk()).andReturn());
        assertThat(acks.get("targeted").asInt()).isEqualTo(2);
        assertThat(acks.get("suppressed").asBoolean()).isTrue();
        assertThat(acks.has("ratePct")).isFalse();
        assertThat(acks.get("pending")).hasSize(1);
        assertThat(acks.get("pending").get(0).get("employeeId").asString()).isEqualTo(baseAgent);
        mvc.perform(get("/api/v1/advisories").with(HR)).andExpect(status().isForbidden());

        // a third target (newly rostered crew, picked up by the daily job) lifts the suppression: 1 of 3 acknowledged
        String later = employee("TL", "CAB", "CABIN", "CABIN_CREW", "LHR");
        duty("CREW_ROSTER", later, "WZ8" + ThreadLocalRandom.current().nextInt(10, 99), "G-TEST", flight, flight.plus(7, ChronoUnit.HOURS), station);
        jobs.runDaily(today);
        acks = body(mvc.perform(get("/api/v1/advisories/" + id + "/acknowledgements").with(NURSE)).andExpect(status().isOk()).andReturn());
        assertThat(acks.get("targeted").asInt()).isEqualTo(3);
        assertThat(acks.get("acknowledged").asInt()).isEqualTo(1);
        assertThat(acks.get("ratePct").asDouble()).isEqualTo(33.3);
        assertThat(notifications(email(later), "advisory.published")).isEqualTo(1);
    }

    @Test
    void f05DemoDxbAdvisory() throws Exception {
        JsonNode mine = body(mvc.perform(get("/api/v1/me/advisories").with(CREW_E1002)).andExpect(status().isOk()).andReturn());
        assertThat(mine.toString()).contains(DEMO_ADVISORY);
        JsonNode acks = body(mvc.perform(get("/api/v1/advisories/" + DEMO_ADVISORY + "/acknowledgements").with(PHYSICIAN))
                .andExpect(status().isOk()).andReturn());
        assertThat(acks.get("targeted").asInt()).isGreaterThanOrEqualTo(2);
        assertThat(acks.get("pending").toString()).contains("E1002");
    }

    // ------------------------------------------------------------------ F06 public-health reporting

    @Test
    void f06ReportDeadlineSubmissionCorrespondenceAndJob() throws Exception {
        String emp = employee("TP", "CAT", "CATERING", "CATERING_ASSOC", "LHR");
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        JsonNode cd = body(postJson("/api/v1/communicable/cases", PHYSICIAN, Map.of("employeeId", emp, "conditionCode", "HEP_A",
                "onsetDate", today.minusDays(3).toString(), "confirmedOn", today.toString())).andExpect(status().isCreated()).andReturn());
        String cdId = cd.get("id").asString();
        postJson("/api/v1/public-health/reports", NURSE, Map.of("communicableCaseId", cdId, "authorityCode", "UKHSA_LOCAL"))
                .andExpect(status().isForbidden());
        postJson("/api/v1/public-health/reports", PHYSICIAN, Map.of("communicableCaseId", cdId, "authorityCode", "TOXLAB"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("unknown_authority"));
        postJson("/api/v1/public-health/reports", PHYSICIAN, Map.of("authorityCode", "UKHSA_LOCAL"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("subject_required"));
        JsonNode r = body(postJson("/api/v1/public-health/reports", PHYSICIAN, Map.of("communicableCaseId", cdId, "authorityCode", "UKHSA_LOCAL"))
                .andExpect(status().isCreated()).andReturn());
        String id = r.get("id").asString();
        assertThat(r.get("ref").asString()).matches("PHR-" + YEAR + "-\\d{6}");
        assertThat(r.get("status").asString()).isEqualTo("PENDING");
        // hepatitis A: report within 24 hours of confirmation
        assertThat(r.get("hoursLeft").asDouble()).isBetween(23.0, 24.1);
        assertThat(Instant.parse(r.get("dueAt").asString()).truncatedTo(ChronoUnit.MILLIS))
                .isEqualTo(Instant.parse(cd.get("reportDueAt").asString()).truncatedTo(ChronoUnit.MILLIS));
        assertThat(count("select count(*) from docs.document where id = ?::uuid and template_code = 'PUBLIC_HEALTH_REPORT'", r.get("documentId").asString()))
                .isEqualTo(1);
        postJson("/api/v1/public-health/reports", PHYSICIAN, Map.of("communicableCaseId", cdId, "authorityCode", "UKHSA_LOCAL"))
                .andExpect(status().isConflict());

        // deadline job: due soon (inside 12 h) and overdue, once each
        reportService.alertDeadlines(Instant.now().plus(13, ChronoUnit.HOURS));
        reportService.alertDeadlines(Instant.now().plus(14, ChronoUnit.HOURS));
        assertThat(count("select count(*) from platform.notification where address = 'physician1@wellnessz.local' and template_key = 'public-health.report-due' "
                + "and body like ?", "%" + r.get("ref").asString() + "%")).isEqualTo(1);
        reportService.alertDeadlines(Instant.now().plus(25, ChronoUnit.HOURS));
        assertThat(count("select count(*) from platform.notification where address = 'physician1@wellnessz.local' and template_key = 'public-health.report-due' "
                + "and body like ?", "%" + r.get("ref").asString() + "%")).isEqualTo(2);
        mvc.perform(get("/api/v1/public-health/reports").param("status", "PENDING").with(CASE_MANAGER)).andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == '" + id + "')].hoursLeft").exists());
        mvc.perform(get("/api/v1/public-health/reports").with(HR)).andExpect(status().isForbidden());

        // submission → disclosure log to the authority; correspondence (JSON and uploaded letter)
        postJson("/api/v1/public-health/reports/" + id + "/submit", PHYSICIAN, Map.of("channel", "FAX", "submittedAt", Instant.now().toString()))
                .andExpect(status().isBadRequest());
        postJson("/api/v1/public-health/reports/" + id + "/submit", PHYSICIAN, Map.of("channel", "PORTAL",
                "submittedAt", Instant.now().plus(1, ChronoUnit.DAYS).toString())).andExpect(status().isBadRequest());
        postJson("/api/v1/public-health/reports/" + id + "/submit", CMO, Map.of("channel", "PORTAL", "submittedAt", Instant.now().toString(),
                "reference", "HPZone 12345")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("SUBMITTED"))
                .andExpect(jsonPath("$.submittedOnTime").value(true)).andExpect(jsonPath("$.hoursLeft").doesNotExist());
        postJson("/api/v1/public-health/reports/" + id + "/submit", CMO, Map.of("channel", "PORTAL", "submittedAt", Instant.now().toString()))
                .andExpect(status().isConflict());
        assertThat(count("select count(*) from platform.disclosure_log where employee_id = ? and recipient_type = 'AUTHORITY' "
                + "and resource_type = 'PUBLIC_HEALTH_REPORT'", emp)).isEqualTo(1);
        postJson("/api/v1/public-health/reports/" + id + "/correspondence", PHYSICIAN, Map.of("direction", "IN",
                "summary", "Authority acknowledged; asked for the household contact list")).andExpect(status().isCreated())
                .andExpect(jsonPath("$.correspondence[0].direction").value("IN"));
        byte[] pdf = renderer.render("Letter", "HPT-1", 1, List.of(new PdfRenderer.Section(null, "Thank you for the notification.")), "Letter").pdf();
        mvc.perform(multipart("/api/v1/public-health/reports/" + id + "/correspondence/upload")
                .file(new MockMultipartFile("file", "letter.pdf", "application/pdf", pdf)).param("direction", "IN").param("summary", "Closure letter")
                .with(PHYSICIAN)).andExpect(status().isCreated()).andExpect(jsonPath("$.correspondence.length()").value(2))
                .andExpect(jsonPath("$.correspondence[1].documentId").exists());
        assertThat(count("select count(*) from docs.document where employee_id = ? and type = 'AUTHORITY_CORRESPONDENCE'", emp)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select summary from travelhealth.ph_correspondence where report_id = ?::uuid order by recorded_at limit 1",
                String.class, id)).doesNotContain("household");
        mvc.perform(multipart("/api/v1/public-health/reports/" + id + "/correspondence/upload")
                .file(new MockMultipartFile("file", "x.txt", "text/plain", "hello".getBytes(StandardCharsets.UTF_8))).param("direction", "IN")
                .param("summary", "x").with(PHYSICIAN)).andExpect(status().isBadRequest());
    }

    @Test
    void f06ExposureReportAndDemoReport() throws Exception {
        JsonNode r = body(postJson("/api/v1/public-health/reports", PHYSICIAN, Map.of("exposureId", DEMO_FUME, "authorityCode", "UKHSA_LOCAL"))
                .andExpect(status().isCreated()).andReturn());
        assertThat(r.get("subject").asString()).isEqualTo("Cabin air / fume event");
        mvc.perform(get("/api/v1/public-health/reports/" + DEMO_REPORT).with(NURSE)).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING")).andExpect(jsonPath("$.documentId").exists())
                .andExpect(jsonPath("$.authorityName").value("Local Health Protection Team"));
    }
}
