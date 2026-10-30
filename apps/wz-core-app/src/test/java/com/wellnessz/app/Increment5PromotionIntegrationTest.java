package com.wellnessz.app;

import static com.wellnessz.app.PlatformIntegrationTest.user;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.AbstractMockHttpServletRequestBuilder;

import com.wellnessz.e12.PromotionJobs;
import com.wellnessz.e12.ergonomics.ErgonomicsService;
import com.wellnessz.e12.screening.ScreeningService;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Acceptance tests for E12 – health promotion & prevention (contract §6): anonymised insights, campaign planner and
 * publication to the campaign app, screening events with follow-up into the case kernel, education attendance, ergonomic
 * assessments / actions, participation and wearable ingestion (internal clients only, consent-gated, purge on withdrawal),
 * the participation / outcome report and the manager / employee views – including the negative paths.
 */
class Increment5PromotionIntegrationTest extends IntegrationTestBase {

    @Autowired
    MockMvc mvc;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    JsonMapper json;
    @Autowired
    ScreeningService screening;
    @Autowired
    ErgonomicsService ergonomics;
    @Autowired
    PromotionJobs jobs;
    @Autowired
    com.wellnessz.integration.outbox.OutboxRepository outbox;

    static final JwtRequestPostProcessor PROMO = user("promo1", "S8807", null, "HEALTH_PROMOTION_LEAD");
    static final JwtRequestPostProcessor ERGO = user("ergo1", "S8808", null, "ERGONOMIST");
    static final JwtRequestPostProcessor HSE = user("hse1", null, null, "HSE_OFFICER");
    static final JwtRequestPostProcessor NURSE = user("nurse1", "N1", null, "OH_CLINICIAN");
    static final JwtRequestPostProcessor CMO = user("cmo1", "CM1", null, "CMO");
    static final JwtRequestPostProcessor NURSING_HEAD = user("nursinghead1", "NH1", null, "NURSING_HEAD");
    static final JwtRequestPostProcessor MANAGER = user("manager1", "M2001", "/AIRLINE/CATERING", "MANAGER");
    static final JwtRequestPostProcessor MANAGER_CARGO = user("manager9", "M9009", "/AIRLINE/CARGO", "MANAGER");
    static final JwtRequestPostProcessor HR = user("hr1", "H1", "/AIRLINE", "HR");
    static final JwtRequestPostProcessor CAMPAIGN_APP = user("campaign-app", null, null, "INTEGRATION");
    static final JwtRequestPostProcessor PARTNER_CLIENT = user("eap-system", null, "/PARTNERS/EAP_WELLBEING", "INTEGRATION");
    static final JwtRequestPostProcessor ERIN = user("employee1", "E1001", "/AIRLINE/CATERING", "EMPLOYEE");
    static final JwtRequestPostProcessor CARA = user("crew1", "E1003", "/AIRLINE/OPS/CABIN", "EMPLOYEE");
    static final JwtRequestPostProcessor PAUL = user("pilot1", "E1002", "/AIRLINE/OPS/FLIGHTOPS", "EMPLOYEE");

    static final String MSK_CAMPAIGN = "e12c0000-0000-4000-8000-000000000001";
    static final String DEMO_EVENT_TOMORROW = "e12de000-0000-4000-8000-000000000101";
    static final String DEMO_PAST_RESULT = "e12de000-0000-4000-8000-000000000221";
    static final String DEMO_CATERING_ASSESSMENT = "e12de000-0000-4000-8000-000000000301";
    static final String DEMO_OVERDUE_ACTION = "e12de000-0000-4000-8000-000000000311";
    static final LocalDate TODAY = LocalDate.now(ZoneOffset.UTC);
    static final byte[] PNG = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg==");

    // ------------------------------------------------------------------ helpers

    MvcResult call(AbstractMockHttpServletRequestBuilder<?> req, JwtRequestPostProcessor who, String content) throws Exception {
        if (content != null) {
            req.contentType(MediaType.APPLICATION_JSON).content(content);
        }
        return mvc.perform(req.with(who)).andReturn();
    }

    int status(AbstractMockHttpServletRequestBuilder<?> req, JwtRequestPostProcessor who, String content) throws Exception {
        return call(req, who, content).getResponse().getStatus();
    }

    JsonNode ok(AbstractMockHttpServletRequestBuilder<?> req, JwtRequestPostProcessor who, String content) throws Exception {
        MvcResult r = call(req, who, content);
        assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isBetween(200, 201);
        return json.readTree(r.getResponse().getContentAsString());
    }

    JsonNode problem(AbstractMockHttpServletRequestBuilder<?> req, JwtRequestPostProcessor who, String content, int expected) throws Exception {
        MvcResult r = call(req, who, content);
        assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isEqualTo(expected);
        String body = r.getResponse().getContentAsString();
        return body.isBlank() ? json.readTree("{}") : json.readTree(body);
    }

    String campaignJson(String title, String risk, String units, LocalDate start, LocalDate end) {
        return """
                {"title":"%s","risk":"%s","objective":"Prevention campaign for the targeted units","targetUnits":[%s],
                 "insightRef":"%s:CORP:ABNORMAL_BIOMETRIC_SCREENS","budget":{"amount":"1500.00","currency":"GBP"},
                 "startDate":"%s","endDate":"%s","owners":["promo1"],"kpis":[{"metric":"PARTICIPATION_PCT","target":40}]}"""
                .formatted(title, risk, units, risk, start, end);
    }

    String activeCampaign(String risk, String units) throws Exception {
        JsonNode c = ok(post("/api/v1/promotion/campaigns"), PROMO, campaignJson("Test campaign " + UUID.randomUUID().toString().substring(0, 6), risk,
                units, TODAY.minusDays(1), TODAY.plusDays(90)));
        ok(post("/api/v1/promotion/campaigns/" + c.get("id").asText() + "/publish"), PROMO, null);
        return c.get("id").asText();
    }

    String newEmployee(String department, String jobCode) {
        String id = "T" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        jdbc.update("""
                insert into masterdata.employee (employee_id, display_name, email, subsidiary_code, department_code, job_code, location_code,
                                                 badge_id, updated_at) values (?, ?, ?, 'CORP', ?, ?, 'HQ', ?, now())""",
                id, "Test " + id, id.toLowerCase() + "@wellnessz.local", department, jobCode, "B-" + id);
        return id;
    }

    String newEvent(String type) throws Exception {
        return ok(post("/api/v1/promotion/screening-events"), PROMO, """
                {"type":"%s","siteCode":"HQ-MC","date":"%s"}""".formatted(type, TODAY)).get("id").asText();
    }

    // ------------------------------------------------------------------ insights (anonymised, suppressed)

    @Test
    void insightsAreAnonymisedAndSuppressedAndRoleRestricted() throws Exception {
        JsonNode rows = ok(get("/api/v1/promotion/insights"), PROMO, null);
        assertThat(rows.isArray()).isTrue();
        assertThat(rows.size()).isPositive();
        for (JsonNode r : rows) {
            assertThat(r.has("employeeId")).isFalse();
            assertThat(r.get("risk").asText()).isIn("MSK", "HEARING", "METABOLIC", "FATIGUE", "MENTAL_WELLBEING");
            assertThat(r.get("suppressed").asBoolean()).isEqualTo(!r.has("value"));
            if (r.has("value")) {
                assertThat(r.get("value").asLong()).isGreaterThanOrEqualTo(3);
            }
        }
        // demo kernel cases: GROUND and CATERING have several MSK injury cases in the last 12 months
        assertThat(rows.toString()).contains("MSK_INJURY_CASES");
        assertThat(rows.toString()).doesNotContain("E1004").doesNotContain("Rami");
        JsonNode msk = ok(get("/api/v1/promotion/insights?risk=MSK&months=12"), CMO, null);
        for (JsonNode r : msk) {
            assertThat(r.get("risk").asText()).isEqualTo("MSK");
        }
        ok(get("/api/v1/promotion/insights"), NURSING_HEAD, null);
        problem(get("/api/v1/promotion/insights"), MANAGER, null, 403);
        problem(get("/api/v1/promotion/insights"), ERIN, null, 403);
        problem(get("/api/v1/promotion/insights"), NURSE, null, 403);
        problem(get("/api/v1/promotion/insights?months=99"), PROMO, null, 400);
    }

    // ------------------------------------------------------------------ campaign planner & publication

    @Test
    void portfolioIsSeededAndCampaignsArePlannedAndPublished() throws Exception {
        JsonNode list = ok(get("/api/v1/promotion/campaigns"), PROMO, null);
        assertThat(list.toString()).contains("CMP-PORTFOLIO-MSK", "CMP-PORTFOLIO-HEAR", "CMP-PORTFOLIO-FATG", "CMP-PORTFOLIO-META",
                "CMP-PORTFOLIO-WELL");
        JsonNode msk = ok(get("/api/v1/promotion/campaigns/" + MSK_CAMPAIGN), PROMO, null);
        assertThat(msk.get("status").asText()).isEqualTo("ACTIVE");
        assertThat(msk.get("targetUnits").toString()).contains("GROUND", "CARGO", "CATERING");
        assertThat(msk.get("kpis").size()).isEqualTo(3);
        assertThat(ok(get("/api/v1/promotion/campaigns?status=PLANNED&risk=HEARING"), PROMO, null).toString()).contains("CMP-PORTFOLIO-HEAR");

        // negatives: role, validation, clinical content, unknown unit, dates
        problem(post("/api/v1/promotion/campaigns"), MANAGER, campaignJson("x", "METABOLIC", "\"CORP\"", TODAY, TODAY.plusDays(5)), 403);
        ok(get("/api/v1/promotion/campaigns"), CMO, null);
        problem(get("/api/v1/promotion/campaigns"), NURSE, null, 403);
        problem(post("/api/v1/promotion/campaigns"), PROMO, campaignJson("x", "DIET", "\"CORP\"", TODAY, TODAY.plusDays(5)), 400);
        JsonNode clinical = problem(post("/api/v1/promotion/campaigns"), PROMO,
                campaignJson("Diabetes screening for corporate", "METABOLIC", "\"CORP\"", TODAY, TODAY.plusDays(5)), 422);
        assertThat(clinical.get("code").asText()).isEqualTo("clinical_content_not_allowed");
        assertThat(problem(post("/api/v1/promotion/campaigns"), PROMO, campaignJson("Healthy hearts", "METABOLIC", "\"NOPE\"", TODAY,
                TODAY.plusDays(5)), 422).get("code").asText()).isEqualTo("unknown_unit");
        assertThat(problem(post("/api/v1/promotion/campaigns"), PROMO, campaignJson("Healthy hearts", "METABOLIC", "\"CORP\"", TODAY,
                TODAY.minusDays(5)), 422).get("code").asText()).isEqualTo("invalid_dates");

        // create → update → publish
        String employee = newEmployee("CORP", "OFFICE_ADMIN");
        JsonNode created = ok(post("/api/v1/promotion/campaigns"), PROMO, campaignJson("Healthy hearts at HQ", "METABOLIC", "\"CORP\"", TODAY,
                TODAY.plusDays(60)));
        String id = created.get("id").asText();
        assertThat(created.get("status").asText()).isEqualTo("PLANNED");
        assertThat(created.get("ref").asText()).startsWith("CMP-");
        assertThat(created.get("budget").get("amount").asText()).isEqualTo("1500.00");
        JsonNode updated = ok(put("/api/v1/promotion/campaigns/" + id), PROMO, campaignJson("Healthy hearts at head office", "METABOLIC", "\"CORP\"",
                TODAY, TODAY.plusDays(90)));
        assertThat(updated.get("title").asText()).isEqualTo("Healthy hearts at head office");
        JsonNode published = ok(post("/api/v1/promotion/campaigns/" + id + "/publish"), PROMO, null);
        assertThat(published.get("campaign").get("status").asText()).isEqualTo("ACTIVE");
        assertThat(published.get("newlyInvited").asInt()).isGreaterThanOrEqualTo(2);
        assertThat(jdbc.queryForObject("select count(*) from promotion.campaign_invitation where campaign_id = ?::uuid and employee_id in ('E1005', ?)",
                Integer.class, id, employee)).isEqualTo(2);
        assertThat(jdbc.queryForObject("select count(*) from platform.notification where recipient_id = ? and template_key = 'campaign.invitation'",
                Integer.class, employee)).isEqualTo(1);
        String payload = outbox.findAll().stream().filter(o -> "campaign-app".equals(o.getDestination()) && id.equals(o.getAggregateId()))
                .findFirst().orElseThrow().getPayload();
        JsonNode event = json.readTree(payload);
        assertThat(event.get("campaignId").asText()).isEqualTo(id);
        assertThat(event.get("targetUnits").asText()).isEqualTo("CORP");
        assertThat(event.get("risk").asText()).isEqualTo("METABOLIC");
        // republish only invites new staff; a completed campaign is closed for changes
        assertThat(ok(post("/api/v1/promotion/campaigns/" + id + "/publish"), PROMO, null).get("newlyInvited").asInt()).isZero();
        String completed = campaignJson("Healthy hearts at head office", "METABOLIC", "\"CORP\"", TODAY, TODAY.plusDays(90))
                .replace("\"kpis\"", "\"status\":\"COMPLETED\",\"kpis\"");
        ok(put("/api/v1/promotion/campaigns/" + id), PROMO, completed);
        assertThat(problem(post("/api/v1/promotion/campaigns/" + id + "/publish"), PROMO, null, 409).get("code").asText()).isEqualTo("campaign_closed");
        problem(put("/api/v1/promotion/campaigns/" + id), PROMO, completed, 409);
        problem(get("/api/v1/promotion/campaigns/" + UUID.randomUUID()), PROMO, null, 404);
    }

    // ------------------------------------------------------------------ screening events, results & follow-up

    @Test
    void screeningEventResultsOpenFollowUpWithinFortyEightHours() throws Exception {
        String event = newEvent("BIOMETRIC");
        String unknown = "ZZ" + UUID.randomUUID().toString().substring(0, 6);
        JsonNode added = ok(post("/api/v1/promotion/screening-events/" + event + "/participants"), PROMO,
                "{\"employeeIds\":[\"E1004\",\"E1007\",\"%s\"]}".formatted(unknown));
        assertThat(added.get("added").asInt()).isEqualTo(2);
        assertThat(added.get("unknown").toString()).contains(unknown);
        assertThat(ok(post("/api/v1/promotion/screening-events/" + event + "/participants"), NURSE, "{\"employeeIds\":[\"E1004\"]}")
                .get("alreadyRegistered").asInt()).isEqualTo(1);
        problem(post("/api/v1/promotion/screening-events/" + event + "/participants"), MANAGER, "{\"employeeIds\":[\"E1004\"]}", 403);

        // results are clinical: the promotion lead cannot record them
        String abnormal = """
                {"employeeId":"E1004","measures":{"systolic":162,"diastolic":101,"bmi":27.5,"glucoseMmol":5.2}}""";
        problem(post("/api/v1/promotion/screening-events/" + event + "/results"), PROMO, abnormal, 403);
        problem(post("/api/v1/promotion/screening-events/" + event + "/results"), MANAGER, abnormal, 403);
        problem(post("/api/v1/promotion/screening-events/" + event + "/results"), NURSE, """
                {"employeeId":"E1004","measures":{"systolic":900}}""", 400);
        problem(post("/api/v1/promotion/screening-events/" + event + "/results"), NURSE, """
                {"employeeId":"E1004","measures":{}}""", 400);
        assertThat(problem(post("/api/v1/promotion/screening-events/" + event + "/results"), NURSE, """
                {"employeeId":"E1009","measures":{"systolic":120}}""", 422).get("code").asText()).isEqualTo("not_registered");

        Instant before = Instant.now().minusSeconds(5);
        JsonNode result = ok(post("/api/v1/promotion/screening-events/" + event + "/results"), NURSE, abnormal);
        assertThat(result.get("outcome").asText()).isEqualTo("ABNORMAL");
        assertThat(result.get("flags").toString()).contains("SYSTOLIC_HIGH", "DIASTOLIC_HIGH");
        String caseId = result.get("followUpCaseId").asText();
        assertThat(result.get("abnormal").asBoolean()).isTrue();
        assertThat(result.get("followUpCaseRef").asText()).isNotBlank();
        assertThat(result.get("caseRef").asText()).isNotBlank();
        Instant due = Instant.parse(result.get("followUpDueAt").asText());
        assertThat(due).isBefore(before.plus(48, ChronoUnit.HOURS).plusSeconds(60));
        assertThat(jdbc.queryForObject("select case_type from casemgmt.oh_case where id = ?::uuid", String.class, caseId))
                .isEqualTo("SURVEILLANCE_FOLLOW_UP");
        assertThat(jdbc.queryForObject("select count(*) from casemgmt.case_task where case_id = ?::uuid and task_type = 'FOLLOW_UP' and due_at <= ?",
                Integer.class, caseId, java.sql.Timestamp.from(before.plus(48, ChronoUnit.HOURS).plusSeconds(60)))).isEqualTo(1);
        String letter = result.get("letterDocumentId").asText();
        assertThat(jdbc.queryForObject("select template_code from docs.document where id = ?::uuid", String.class, letter)).isEqualTo("RESULT_LETTER");
        assertThat(jdbc.queryForObject("select count(*) from docs.document_share where document_id = ?::uuid and target = 'EMPLOYEE'", Integer.class,
                letter)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from platform.notification where recipient_id = 'E1004' and template_key = 'screening.follow-up'",
                Integer.class)).isPositive();
        // measures are encrypted at rest
        String stored = jdbc.queryForObject("select measures from promotion.screening_result where id = ?::uuid", String.class,
                result.get("id").asText());
        assertThat(stored).doesNotContain("162").doesNotContain("systolic");

        JsonNode normal = ok(post("/api/v1/promotion/screening-events/" + event + "/results"), NURSE, """
                {"employeeId":"E1007","measures":{"systolic":121,"diastolic":79,"bmi":23.1}}""");
        assertThat(normal.get("outcome").asText()).isEqualTo("NORMAL");
        assertThat(normal.has("caseId")).isFalse();
        assertThat(problem(post("/api/v1/promotion/screening-events/" + event + "/results"), NURSE, abnormal, 409).get("code").asText())
                .isEqualTo("result_exists");

        // OH sees results; the promotion lead sees logistics only (no measures, outcome or case)
        JsonNode ohView = ok(get("/api/v1/promotion/screening-events/" + event), NURSE, null);
        assertThat(ohView.get("abnormalCount").asInt()).isEqualTo(1);
        assertThat(ohView.get("followedUpCount").asInt()).isEqualTo(1);
        assertThat(ohView.toString()).contains("SYSTOLIC_HIGH", "measures");
        JsonNode leadView = ok(get("/api/v1/promotion/screening-events/" + event), PROMO, null);
        assertThat(leadView.get("participantCount").asInt()).isEqualTo(2);
        assertThat(leadView.get("screenedCount").asInt()).isEqualTo(2);
        assertThat(leadView.has("participants")).isFalse();
        assertThat(ohView.get("participants").get(0).has("resultRecorded")).isTrue();
        assertThat(leadView.toString()).doesNotContain("measures").doesNotContain("SYSTOLIC").doesNotContain("caseRef").doesNotContain("abnormalCount");
        problem(get("/api/v1/promotion/screening-events/" + event), MANAGER, null, 403);
        problem(get("/api/v1/promotion/screening-events/" + event), HR, null, 403);
        assertThat(ok(get("/api/v1/promotion/screening-events?type=BIOMETRIC"), PROMO, null).toString()).contains(event);
        problem(post("/api/v1/promotion/screening-events"), PROMO, "{\"type\":\"BIOMETRIC\",\"siteCode\":\"NOWHERE\",\"date\":\"%s\"}"
                .formatted(TODAY), 422);
    }

    @Test
    void demoScreeningEventTomorrowAcceptsResultsAndPastAbnormalHasFollowUp() throws Exception {
        JsonNode demo = ok(get("/api/v1/promotion/screening-events/" + DEMO_EVENT_TOMORROW), NURSE, null);
        assertThat(ok(get("/api/v1/promotion/screening-events/" + DEMO_EVENT_TOMORROW), PROMO, null).get("participantCount").asInt()).isEqualTo(3);
        assertThat(demo.get("type").asText()).isEqualTo("BIOMETRIC");
        assertThat(demo.get("siteCode").asText()).isEqualTo("HQ-MC");
        assertThat(demo.get("date").asText()).isEqualTo(TODAY.plusDays(1).toString());
        assertThat(demo.get("participants").toString()).contains("E1001", "E1005", "M2001");
        JsonNode r = ok(post("/api/v1/promotion/screening-events/" + DEMO_EVENT_TOMORROW + "/results"), NURSE, """
                {"employeeId":"M2001","measures":{"systolic":158,"diastolic":96}}""");
        assertThat(r.get("outcome").asText()).isEqualTo("ABNORMAL");
        assertThat(r.get("caseId").asText()).isNotBlank();
        // past MSK event: seeded measures (encrypted) and the follow-up case of the abnormal result
        assertThat(jdbc.queryForObject("select case_id is not null and measures is not null and letter_document_id is not null "
                + "from promotion.screening_result where id = ?::uuid", Boolean.class, DEMO_PAST_RESULT)).isTrue();
    }

    @Test
    void followUpSweepOpensMissingCases() throws Exception {
        String event = newEvent("MSK");
        ok(post("/api/v1/promotion/screening-events/" + event + "/participants"), PROMO, "{\"employeeIds\":[\"E1009\"]}");
        String participant = jdbc.queryForObject("select id::text from promotion.screening_participant where event_id = ?::uuid", String.class, event);
        String resultId = UUID.randomUUID().toString();
        jdbc.update("""
                insert into promotion.screening_result (id, event_id, participant_id, employee_id, unit_code, outcome, recorded_by, recorded_at)
                values (?::uuid, ?::uuid, ?::uuid, 'E1009', 'GROUND', 'ABNORMAL', 'nurse1', now() - interval '1 hour')""", resultId, event, participant);
        assertThat(screening.sweepFollowUps()).isGreaterThanOrEqualTo(1);
        assertThat(jdbc.queryForObject("select case_id is not null from promotion.screening_result where id = ?::uuid", Boolean.class, resultId))
                .isTrue();
        jobs.screeningFollowUpJob(); // guarded, idempotent
    }

    // ------------------------------------------------------------------ education attendance

    @Test
    void hearingEducationAttendanceAndAggregatedReport() throws Exception {
        String employee = newEmployee("ENG", "ENGINEER");
        JsonNode a = ok(post("/api/v1/promotion/education-attendance"), NURSE, """
                {"employeeId":"%s","programme":"HEARING_CONSERVATION","sessionDate":"%s"}""".formatted(employee, TODAY.minusDays(2)));
        assertThat(a.get("unitCode").asText()).isEqualTo("ENG");
        problem(post("/api/v1/promotion/education-attendance"), NURSE, """
                {"employeeId":"%s","programme":"HEARING_CONSERVATION","sessionDate":"%s"}""".formatted(employee, TODAY.minusDays(2)), 409);
        problem(post("/api/v1/promotion/education-attendance"), PROMO, """
                {"employeeId":"%s","programme":"HEARING_CONSERVATION","sessionDate":"%s"}""".formatted(employee, TODAY.plusDays(2)), 422);
        problem(post("/api/v1/promotion/education-attendance"), PROMO, """
                {"employeeId":"%s","programme":"YOGA","sessionDate":"%s"}""".formatted(employee, TODAY), 400);
        problem(post("/api/v1/promotion/education-attendance"), MANAGER, """
                {"employeeId":"%s","programme":"HEARING_CONSERVATION","sessionDate":"%s"}""".formatted(employee, TODAY), 403);
        assertThat(ok(get("/api/v1/promotion/education-attendance?programme=HEARING_CONSERVATION"), PROMO, null).toString()).contains(employee, "E1004");
        JsonNode report = ok(get("/api/v1/promotion/education-attendance/report"), PROMO, null);
        assertThat(report.get("programme").asText()).isEqualTo("HEARING_CONSERVATION");
        assertThat(report.toString()).doesNotContain(employee).doesNotContain("E1004");
        for (JsonNode u : report.get("units")) {
            assertThat(u.get("suppressed").asBoolean()).isEqualTo(!u.has("population"));
        }
        assertThat(report.get("total").get("unitCode").asText()).isEqualTo("ALL");
        problem(get("/api/v1/promotion/education-attendance/report"), MANAGER, null, 403);
    }

    // ------------------------------------------------------------------ ergonomics

    @Test
    void ergonomicAssessmentsScoringPhotosAndActions() throws Exception {
        JsonNode checklists = ok(get("/api/v1/ergonomics/checklists"), ERGO, null);
        assertThat(checklists.toString()).contains("DSE_WORKSTATION", "MANUAL_HANDLING", "TASK");
        String full = """
                {"type":"TASK","unitCode":"CARGO","jobCode":"FORKLIFT_OPERATOR","location":"LHR cargo shed – ULD build-up",
                 "checklist":{"FORCE":3,"REPETITION":2,"AWKWARD_POSTURE":3,"DURATION":2,"VIBRATION":2,"ENVIRONMENT":1,"WORK_ORGANISATION":1},
                 "findings":"ULD straps tensioned overhead several times per shift."}""";
        JsonNode a = ok(post("/api/v1/ergonomics/assessments"), HSE, full);
        // (3*2 + 2*2 + 3*2 + 2 + 2 + 1 + 1) / 30 = 22 / 30 = 73 %
        assertThat(a.get("riskScore").asInt()).isEqualTo(73);
        assertThat(a.get("rag").asText()).isEqualTo("RED");
        assertThat(a.get("jobDemandProfile").get("jobCode").asText()).isEqualTo("FORKLIFT_OPERATOR");
        String id = a.get("id").asText();
        assertThat(problem(post("/api/v1/ergonomics/assessments"), ERGO, full.replace("\"WORK_ORGANISATION\":1", "\"COFFEE\":1"), 422)
                .get("code").asText()).isEqualTo("unknown_checklist_item");
        assertThat(problem(post("/api/v1/ergonomics/assessments"), ERGO, full.replace(",\"WORK_ORGANISATION\":1", ""), 422).get("code").asText())
                .isEqualTo("checklist_incomplete");
        problem(post("/api/v1/ergonomics/assessments"), ERGO, full.replace("\"FORCE\":3", "\"FORCE\":4"), 400);
        assertThat(problem(post("/api/v1/ergonomics/assessments"), ERGO,
                full.replace("ULD straps tensioned overhead several times per shift.", "Operator has sciatica"), 422).get("code").asText())
                .isEqualTo("clinical_content_not_allowed");
        problem(post("/api/v1/ergonomics/assessments"), MANAGER, full, 403);
        problem(post("/api/v1/ergonomics/assessments"), NURSE, full, 403);

        // photos (non-clinical document), images only
        JsonNode photo = ok(multipart("/api/v1/ergonomics/assessments/" + id + "/photos").file(new MockMultipartFile("file", "uld.png", "image/png", PNG)),
                ERGO, null);
        assertThat(jdbc.queryForObject("select type from docs.document where id = ?::uuid", String.class, photo.get("documentId").asText()))
                .isEqualTo("ERGONOMIC_PHOTO");
        problem(multipart("/api/v1/ergonomics/assessments/" + id + "/photos").file(new MockMultipartFile("file", "x.png", "image/png",
                "not an image".getBytes())), ERGO, null, 400);
        problem(multipart("/api/v1/ergonomics/assessments/" + id + "/photos").file(new MockMultipartFile("file", "uld.png", "image/png", PNG)),
                HSE, null, 403);

        // actions: specialist creates, cargo manager updates status only, catering manager cannot see it
        JsonNode action = ok(post("/api/v1/ergonomics/assessments/" + id + "/actions"), ERGO, """
                {"title":"Install a strap-tensioning aid at the build-up station","owner":"ergo1","dueDate":"%s"}""".formatted(TODAY.plusDays(30)));
        String actionId = action.get("id").asText();
        assertThat(action.get("status").asText()).isEqualTo("OPEN");
        problem(post("/api/v1/ergonomics/assessments/" + id + "/actions"), ERGO, """
                {"title":"Late","owner":"ergo1","dueDate":"%s"}""".formatted(TODAY.minusDays(1)), 422);
        problem(post("/api/v1/ergonomics/assessments/" + id + "/actions"), MANAGER_CARGO, """
                {"title":"x","owner":"ergo1","dueDate":"%s"}""".formatted(TODAY.plusDays(3)), 403);
        problem(put("/api/v1/ergonomics/assessments/" + id + "/actions/" + actionId), MANAGER, "{\"status\":\"DONE\"}", 404);
        problem(put("/api/v1/ergonomics/assessments/" + id + "/actions/" + actionId), MANAGER_CARGO, "{\"title\":\"Changed\"}", 403);
        JsonNode done = ok(put("/api/v1/ergonomics/assessments/" + id + "/actions/" + actionId), MANAGER_CARGO, "{\"status\":\"DONE\"}");
        assertThat(done.get("status").asText()).isEqualTo("DONE");
        assertThat(done.has("closedAt")).isTrue();
        JsonNode reopened = ok(put("/api/v1/ergonomics/assessments/" + id + "/actions/" + actionId), ERGO, "{\"status\":\"OPEN\",\"owner\":\"hse1\"}");
        assertThat(reopened.get("owner").asText()).isEqualTo("hse1");
        assertThat(ok(get("/api/v1/ergonomics/assessments/" + id), ERGO, null).get("photos").size()).isEqualTo(1);
        assertThat(ok(get("/api/v1/ergonomics/assessments?unitCode=CARGO&rag=RED"), HSE, null).toString()).contains(id);
        JsonNode kpi = ok(get("/api/v1/ergonomics/kpi"), ERGO, null);
        assertThat(kpi.get("targetDays").asInt()).isEqualTo(60);
        problem(get("/api/v1/ergonomics/assessments/" + id), MANAGER, null, 403);
    }

    @Test
    void managerWorkspaceShowsOwnUnitActionsAndSuppressedParticipation() throws Exception {
        JsonNode ws = ok(get("/api/v1/workspace/ergonomics"), MANAGER, null);
        assertThat(ws.get("units").toString()).contains("CATERING");
        assertThat(ws.get("units").toString()).doesNotContain("CARGO");
        JsonNode overdue = null;
        for (JsonNode a : ws.get("actions")) {
            assertThat(a.get("unitCode").asText()).isEqualTo("CATERING");
            if (a.get("id").asText().equals(DEMO_OVERDUE_ACTION)) {
                overdue = a;
            }
        }
        assertThat(overdue).isNotNull();
        assertThat(overdue.get("overdue").asBoolean()).isTrue();
        assertThat(ws.toString()).doesNotContain("E1010").doesNotContain("findings");
        // the manager may close their unit's action
        JsonNode closed = ok(put("/api/v1/ergonomics/assessments/" + DEMO_CATERING_ASSESSMENT + "/actions/" + DEMO_OVERDUE_ACTION), MANAGER,
                "{\"status\":\"DONE\"}");
        assertThat(closed.get("overdue").asBoolean()).isFalse();
        ok(put("/api/v1/ergonomics/assessments/" + DEMO_CATERING_ASSESSMENT + "/actions/" + DEMO_OVERDUE_ACTION), ERGO, "{\"status\":\"OPEN\"}");

        JsonNode campaigns = ok(get("/api/v1/workspace/campaigns"), MANAGER, null);
        JsonNode catering = null;
        for (JsonNode c : campaigns) {
            assertThat(c.get("unitCode").asText()).isEqualTo("CATERING");
            assertThat(c.get("suppressed").asBoolean()).isEqualTo(!c.has("pct"));
            if (c.get("campaignId").asText().equals(MSK_CAMPAIGN)) {
                catering = c;
            }
        }
        assertThat(catering).isNotNull();
        assertThat(campaigns.toString()).doesNotContain("E1001").doesNotContain("employeeId");
        problem(get("/api/v1/workspace/campaigns"), PROMO, null, 403);
        problem(get("/api/v1/workspace/ergonomics"), ERIN, null, 403);
    }

    @Test
    void ergonomicActionReminderJobNotifiesOwners() {
        jdbc.update("update promotion.ergonomic_action set last_reminded_on = null where id = ?::uuid", DEMO_OVERDUE_ACTION);
        int sent = ergonomics.remind(TODAY);
        assertThat(sent).isPositive();
        assertThat(jdbc.queryForObject("select count(*) from platform.notification where template_key = 'ergonomic.action-due' and recipient_id = 'M2001'",
                Integer.class)).isPositive();
        assertThat(ergonomics.remind(TODAY)).isZero();
        jobs.ergonomicActionsJob();
    }

    // ------------------------------------------------------------------ campaign-app participation (internal only)

    @Test
    void participationFromTheInternalCampaignAppOnly() throws Exception {
        String campaign = activeCampaign("FATIGUE", "\"FLIGHTOPS\",\"CABIN\"");
        String now = Instant.now().minusSeconds(3600).truncatedTo(ChronoUnit.SECONDS).toString();
        String rows = """
                [{"campaignId":"%s","employeeRef":"E1002","activity":"SLEEP_WEBINAR","occurredAt":"%s"},
                 {"campaignId":"%s","employeeRef":"B-1003","activity":"SLEEP_WEBINAR","occurredAt":"%s","value":1},
                 {"campaignId":"%s","employeeRef":"NOBODY1","activity":"SLEEP_WEBINAR","occurredAt":"%s"},
                 {"campaignId":"%s","employeeRef":"E1002","activity":"SLEEP_WEBINAR","occurredAt":"%s"}]"""
                .formatted(campaign, now, campaign, now, campaign, now, UUID.randomUUID(), now);
        JsonNode r = ok(post("/api/v1/integration/promotion/participation"), CAMPAIGN_APP, rows);
        assertThat(r.get("received").asInt()).isEqualTo(4);
        assertThat(r.get("accepted").asInt()).isEqualTo(2);
        assertThat(r.get("unmatched").toString()).contains("NOBODY1");
        assertThat(r.get("rejected").get(0).get("reason").asText()).isEqualTo("UNKNOWN_CAMPAIGN");
        assertThat(ok(post("/api/v1/integration/promotion/participation"), CAMPAIGN_APP, rows).get("duplicates").asInt()).isEqualTo(2);
        problem(post("/api/v1/integration/promotion/participation"), PARTNER_CLIENT, rows, 403);
        problem(post("/api/v1/integration/promotion/participation"), PROMO, rows, 403);
        problem(post("/api/v1/integration/promotion/participation"), CAMPAIGN_APP, "[]", 400);
        problem(post("/api/v1/integration/promotion/participation"), CAMPAIGN_APP, """
                [{"campaignId":"%s","employeeRef":"E1002","activity":"drop table;","occurredAt":"%s"}]""".formatted(campaign, now), 400);
        // report: FLIGHTOPS / CABIN groups are small → suppressed; CSV export
        JsonNode report = ok(get("/api/v1/promotion/campaigns/" + campaign + "/report"), CMO, null);
        for (JsonNode p : report.get("participation")) {
            assertThat(p.get("suppressed").asBoolean()).isEqualTo(!p.has("pct"));
        }
        MvcResult csv = call(get("/api/v1/promotion/campaigns/" + campaign + "/report?format=csv&groupBy=subsidiary"), PROMO, null);
        assertThat(csv.getResponse().getStatus()).isEqualTo(200);
        assertThat(csv.getResponse().getContentType()).startsWith("text/csv");
        assertThat(csv.getResponse().getContentAsString()).startsWith("section,key,invited,participated,pct,suppressed");
        problem(get("/api/v1/promotion/campaigns/" + campaign + "/report"), MANAGER, null, 403);
        problem(get("/api/v1/promotion/campaigns/" + campaign + "/report"), NURSE, null, 403);
    }

    @Test
    void mskCampaignReportShowsParticipationAndOutcomesWithSuppression() throws Exception {
        JsonNode report = ok(get("/api/v1/promotion/campaigns/" + MSK_CAMPAIGN + "/report?groupBy=unit"), PROMO, null);
        assertThat(report.get("minGroupSize").asInt()).isEqualTo(3);
        JsonNode catering = null;
        JsonNode cargo = null;
        for (JsonNode p : report.get("participation")) {
            if (p.get("key").asText().equals("CATERING")) {
                catering = p;
            }
            if (p.get("key").asText().equals("CARGO")) {
                cargo = p;
            }
        }
        assertThat(catering).isNotNull();
        assertThat(catering.get("suppressed").asBoolean()).isFalse();
        assertThat(catering.get("invited").asInt()).isEqualTo(3);
        assertThat(catering.get("pct").decimalValue()).isEqualByComparingTo("100.0");
        assertThat(cargo.get("suppressed").asBoolean()).isTrue();
        assertThat(cargo.has("invited")).isFalse();
        assertThat(report.get("total").get("invited").asInt()).isGreaterThanOrEqualTo(7);
        JsonNode msk = null;
        for (JsonNode o : report.get("outcomes")) {
            if (o.get("metric").asText().equals("mskScore")) {
                msk = o;
            }
            if (o.get("metric").asText().equals("STEPS")) {
                assertThat(o.get("suppressed").asBoolean()).isTrue(); // one wearable participant only
            }
        }
        assertThat(msk).isNotNull();
        assertThat(msk.get("baseline").decimalValue()).isEqualByComparingTo("5.00");
        assertThat(report.get("kpis").toString()).contains("PARTICIPATION_PCT");
        assertThat(report.toString()).doesNotContain("E1004").doesNotContain("E1001");
    }

    // ------------------------------------------------------------------ wearables (consent-gated, purge on withdrawal)

    String grantWearableConsent(JwtRequestPostProcessor who) throws Exception {
        return ok(post("/api/v1/consents"), who, "{\"purpose\":\"WEARABLE_DATA\",\"scope\":\"campaign\"}").get("id").asText();
    }

    @Test
    void wearableDataIsConsentGatedMinimisedAndPurgedOnWithdrawal() throws Exception {
        String campaign = activeCampaign("FATIGUE", "\"CABIN\"");
        String other = ok(post("/api/v1/consents"), CARA, "{\"purpose\":\"HEALTH_CAMPAIGNS\"}").get("id").asText();
        assertThat(problem(post("/api/v1/me/wearables/enrolments"), CARA, """
                {"campaignId":"%s","deviceType":"GARMIN","consentId":"%s"}""".formatted(campaign, other), 422).get("code").asText())
                .isEqualTo("consent_required");
        String paulsConsent = grantWearableConsent(PAUL);
        problem(post("/api/v1/me/wearables/enrolments"), CARA, """
                {"campaignId":"%s","deviceType":"GARMIN","consentId":"%s"}""".formatted(campaign, paulsConsent), 422);
        assertThat(problem(post("/api/v1/me/wearables/enrolments"), CARA, """
                {"campaignId":"%s","deviceType":"GARMIN","consentId":"%s"}""".formatted(MSK_CAMPAIGN.replace("001", "002"), other), 422)
                .get("code").asText()).isEqualTo("campaign_not_active");

        String consent = grantWearableConsent(CARA);
        JsonNode enrolment = ok(post("/api/v1/me/wearables/enrolments"), CARA, """
                {"campaignId":"%s","deviceType":"GARMIN","consentId":"%s"}""".formatted(campaign, consent));
        String enrolmentId = enrolment.get("id").asText();
        assertThat(enrolment.get("status").asText()).isEqualTo("ACTIVE");
        problem(post("/api/v1/me/wearables/enrolments"), CARA, """
                {"campaignId":"%s","deviceType":"GARMIN","consentId":"%s"}""".formatted(campaign, consent), 409);
        problem(post("/api/v1/me/wearables/enrolments"), PROMO, """
                {"campaignId":"%s","deviceType":"GARMIN","consentId":"%s"}""".formatted(campaign, consent), 403);

        String data = """
                [{"employeeRef":"E1003","campaignId":"%1$s","metric":"STEPS","date":"%2$s","value":8123},
                 {"employeeRef":"E1003","campaignId":"%1$s","metric":"SLEEP_MINUTES","date":"%2$s","value":402},
                 {"employeeRef":"E1003","campaignId":"%1$s","metric":"STEPS","date":"%3$s","value":5000},
                 {"employeeRef":"E1002","campaignId":"%1$s","metric":"STEPS","date":"%2$s","value":4000},
                 {"employeeRef":"E1003","campaignId":"%1$s","metric":"ACTIVE_MINUTES","date":"%2$s","value":5000}]"""
                .formatted(campaign, TODAY.minusDays(1), TODAY.plusDays(1));
        JsonNode in = ok(post("/api/v1/integration/wearables/data"), CAMPAIGN_APP, data);
        assertThat(in.get("accepted").asInt()).isEqualTo(2);
        assertThat(in.get("rejected").toString()).contains("DATE_OUT_OF_RANGE", "NOT_ENROLLED", "VALUE_OUT_OF_RANGE");
        assertThat(jdbc.queryForObject("select count(*) from promotion.wearable_daily where enrolment_id = ?::uuid", Integer.class, enrolmentId))
                .isEqualTo(2);
        assertThat(jdbc.queryForObject("select value from promotion.wearable_daily where enrolment_id = ?::uuid and metric = 'STEPS'", String.class,
                enrolmentId)).isNotEqualTo("8123");
        problem(post("/api/v1/integration/wearables/data"), CAMPAIGN_APP, """
                [{"employeeRef":"E1003","campaignId":"%s","metric":"HEART_RATE","date":"%s","value":70}]""".formatted(campaign, TODAY), 400);
        problem(post("/api/v1/integration/wearables/data"), PARTNER_CLIENT, data, 403);
        problem(post("/api/v1/integration/wearables/data"), CARA, data, 403);

        // /me/campaigns lists the enrolment; another employee cannot withdraw it
        assertThat(ok(get("/api/v1/me/campaigns"), CARA, null).get("enrolments").toString()).contains(enrolmentId);
        problem(delete("/api/v1/me/wearables/enrolments/" + enrolmentId), PAUL, null, 404);
        JsonNode withdrawn = ok(delete("/api/v1/me/wearables/enrolments/" + enrolmentId), CARA, null);
        assertThat(withdrawn.get("status").asText()).isEqualTo("WITHDRAWN");
        assertThat(withdrawn.get("purgedRows").asInt()).isGreaterThanOrEqualTo(2);
        assertThat(jdbc.queryForObject("select count(*) from promotion.wearable_daily where enrolment_id = ?::uuid", Integer.class, enrolmentId))
                .isZero();
        JsonNode after = ok(post("/api/v1/integration/wearables/data"), CAMPAIGN_APP, data);
        assertThat(after.get("accepted").asInt()).isZero();

        // withdrawing the consent itself also withdraws and purges
        JsonNode again = ok(post("/api/v1/me/wearables/enrolments"), CARA, """
                {"campaignId":"%s","deviceType":"GARMIN","consentId":"%s"}""".formatted(campaign, consent));
        assertThat(ok(post("/api/v1/integration/wearables/data"), CAMPAIGN_APP, data).get("accepted").asInt()).isEqualTo(2);
        ok(delete("/api/v1/consents/" + consent), CARA, null);
        assertThat(jdbc.queryForObject("select status from promotion.wearable_enrolment where id = ?::uuid", String.class,
                again.get("id").asText())).isEqualTo("WITHDRAWN");
        assertThat(jdbc.queryForObject("select count(*) from promotion.wearable_daily where enrolment_id = ?::uuid", Integer.class,
                again.get("id").asText())).isZero();
    }

    // ------------------------------------------------------------------ employee view

    @Test
    void employeeSeesInvitationsScreeningsAndEnrolments() throws Exception {
        JsonNode me = ok(get("/api/v1/me/campaigns"), ERIN, null);
        assertThat(me.get("invitations").toString()).contains(MSK_CAMPAIGN);
        assertThat(me.get("screenings").toString()).contains(DEMO_EVENT_TOMORROW);
        assertThat(me.get("enrolments").toString()).contains("e12de000-0000-4000-8000-000000000502", "FITBIT");
        assertThat(me.toString()).doesNotContain("measures").doesNotContain("outcome");
        problem(get("/api/v1/me/campaigns"), PROMO, null, 403);
    }
}
