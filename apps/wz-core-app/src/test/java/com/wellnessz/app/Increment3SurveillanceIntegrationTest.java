package com.wellnessz.app;

import static com.wellnessz.app.PlatformIntegrationTest.user;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.wellnessz.casekernel.support.AbsenceSignalListener;
import com.wellnessz.casekernel.support.AppointmentStatusListener;
import com.wellnessz.casekernel.support.CapacityDemandSource;
import com.wellnessz.e07.SurveillanceJobs;
import com.wellnessz.e07.SurveillanceSpi;
import com.wellnessz.e07.crew.CrewCertificateService;
import com.wellnessz.integration.outbox.OutboxRelay;
import com.wellnessz.integration.outbox.OutboxRepository;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Acceptance tests for E07 – medical surveillance (F01–F08) against the real endpoints, including the kernel SPI interplay
 * (E03 device result → follow-up + case, E05 FIT decision blocked by a return-after-illness medical, E14 appointment
 * callbacks) and the status-only views of schedulers, managers, HR, crew licensing, hygiene and auditors.
 */
class Increment3SurveillanceIntegrationTest extends IntegrationTestBase {

    @Autowired
    MockMvc mvc;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    JsonMapper json;
    @Autowired
    OutboxRelay relay;
    @Autowired
    OutboxRepository outbox;
    @Autowired
    SurveillanceSpi spi;
    @Autowired
    AbsenceSignalListener absenceSignals;
    @Autowired
    SurveillanceJobs jobs;
    @Autowired
    CrewCertificateService crew;
    @Autowired
    com.wellnessz.e07.preemployment.PreEmploymentService preEmployment;

    static final JwtRequestPostProcessor NURSE = user("nurse1", "N1", null, "OH_CLINICIAN");
    static final JwtRequestPostProcessor PHYSICIAN = user("physician1", "PH1", null, "OH_PHYSICIAN");
    static final JwtRequestPostProcessor AME = user("ame1", "A1", null, "AME");
    static final JwtRequestPostProcessor CMO = user("cmo1", "CM1", null, "CMO");
    static final JwtRequestPostProcessor OH_ADMIN = user("ohadmin1", null, null, "OH_ADMIN");
    static final JwtRequestPostProcessor SCHEDULER = user("scheduler1", "S8601", null, "CLINIC_SCHEDULER");
    static final JwtRequestPostProcessor LICENSING = user("crewlicensing1", "S8603", null, "CREW_LICENSING");
    static final JwtRequestPostProcessor CREW_SCHED = user("sched", null, null, "CREW_SCHEDULING");
    static final JwtRequestPostProcessor MANAGER = user("manager1", "M2001", "/AIRLINE/CATERING", "MANAGER");
    static final JwtRequestPostProcessor HR = user("hr1", "H1", "/AIRLINE", "HR");
    static final JwtRequestPostProcessor HR_CARGO = user("hr9", "H9", "/AIRLINE/CARGO", "HR");
    static final JwtRequestPostProcessor HSE = user("hse1", null, null, "HSE_OFFICER");
    static final JwtRequestPostProcessor AUDITOR = user("auditor1", null, null, "AUDITOR");
    static final JwtRequestPostProcessor HYGIENE = user("hygiene1", null, null, "HYGIENE_MANAGER");
    static final JwtRequestPostProcessor INTEGRATION = user("hris-system", null, null, "INTEGRATION");
    static final JwtRequestPostProcessor ERIN = user("employee1", "E1001", "/AIRLINE/CATERING", "EMPLOYEE");
    static final LocalDate TODAY = LocalDate.now(ZoneOffset.UTC);

    // ------------------------------------------------------------------ helpers

    JsonNode body(MvcResult r) throws Exception {
        return json.readTree(r.getResponse().getContentAsString());
    }

    ResultActions call(MockHttpServletRequestBuilder req, JwtRequestPostProcessor who, String content) throws Exception {
        if (content != null) {
            req.contentType(MediaType.APPLICATION_JSON).content(content);
        }
        return mvc.perform(req.with(who));
    }

    JsonNode ok(MockHttpServletRequestBuilder req, JwtRequestPostProcessor who, String content) throws Exception {
        MvcResult r = call(req, who, content).andReturn();
        assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isBetween(200, 201);
        return body(r);
    }

    String newEmployee(String jobCode, String subsidiary, String department, String manager) {
        String id = "T" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        jdbc.update("""
                insert into masterdata.employee (employee_id, display_name, email, subsidiary_code, department_code, job_code, location_code,
                                                 badge_id, manager_employee_id, updated_at)
                values (?, ?, ?, ?, ?, ?, 'LHR', ?, ?, now())""", id, "Test " + id, id.toLowerCase() + "@wellnessz.local", subsidiary, department,
                jobCode, "B-" + id, manager);
        return id;
    }

    String enrolmentId(String employeeId, String programme) {
        return jdbc.queryForObject("select id::text from surveillance.enrolment where employee_id = ? and programme_code = ? and active",
                String.class, employeeId, programme);
    }

    long notifications(String address, String template) {
        return jdbc.queryForObject("select count(*) from platform.notification where address = ? and template_key = ?", Long.class, address,
                template);
    }

    List<String> texts(JsonNode array, String field) {
        List<String> out = new ArrayList<>();
        array.forEach(n -> out.add(n.path(field).asString()));
        return out;
    }

    String openCase(String employeeId, String department, String unit) throws Exception {
        String ref = "INC-" + UUID.randomUUID().toString().substring(0, 8);
        String incident = """
                {"externalRef":"%s","occurredAt":"%s","incidentClass":"LOST_TIME_INJURY","severity":"MEDIUM","bodyPart":"HAND",
                 "location":"LHR-T5","unitCode":"%s","shift":"EARLY","employeeId":"%s","orgUnitCode":"%s",
                 "description":"Hand injury at work","reporterEmail":"hse.officer@wellnessz.local"}"""
                .formatted(ref, Instant.now().minus(1, ChronoUnit.HOURS), unit, employeeId, department);
        mvc.perform(post("/api/v1/intake/ehs/incidents").with(user("ehs-system", null, null, "INTEGRATION"))
                .header("Idempotency-Key", "k-" + ref).contentType(MediaType.APPLICATION_JSON).content(incident))
                .andExpect(status().isAccepted());
        relay.relay();
        relay.relay();
        return jdbc.queryForObject("select id::text from casemgmt.oh_case where incident_ref = ?", String.class, ref);
    }

    // =================================================================== F01 programmes

    @Test
    void f01ProgrammesBuilderSimulationAndActivation() throws Exception {
        JsonNode list = ok(get("/api/v1/surveillance/programmes"), NURSE, null);
        assertThat(texts(list, "code")).contains("PILOT_CLASS1", "CABIN_CREW", "FOOD_HANDLER", "HEARING_CONSERVATION", "RESPIRATORY", "HAZMAT_DGR",
                "AIRSIDE_DRIVER", "FORKLIFT", "WORKING_AT_HEIGHT", "NIGHT_WORKER", "SECURITY_SCREENER", "CLINICAL_BBP", "VDU");
        for (JsonNode p : list) {
            if ("HEARING_CONSERVATION".equals(p.path("code").asString())) {
                assertThat(p.path("eligibleHeadcount").asInt()).isPositive();
                assertThat(p.path("panel").get(0).path("test").asString()).isEqualTo("AUDIOMETRY");
            }
        }
        ok(get("/api/v1/surveillance/programmes/PILOT_CLASS1"), HSE, null);
        assertThat(ok(get("/api/v1/surveillance/programmes/PILOT_CLASS1"), AUDITOR, null).path("frequencyMonthsOver40").asInt()).isEqualTo(6);
        call(get("/api/v1/surveillance/programmes"), MANAGER, null).andExpect(status().isForbidden());
        call(get("/api/v1/surveillance/programmes"), SCHEDULER, null).andExpect(status().isForbidden());

        String code = "VIB_" + UUID.randomUUID().toString().substring(0, 6).toUpperCase().replace('-', '_');
        String body = """
                {"code":"%s","title":"Hand-arm vibration","eligibility":{"jobCodes":["FORKLIFT_OP"],"hazardCodes":["VIBRATION"]},
                 "panel":[{"test":"QUESTIONNAIRE"},{"test":"PHYSICAL_EXAM"}],"frequencyMonths":12,"responsibleRole":"OH_CLINICIAN",
                 "serviceCode":"NURSE_REVIEW","regulatoryBasis":"Control of Vibration at Work Regulations 2005"}""".formatted(code);
        call(post("/api/v1/surveillance/programmes"), NURSE, body).andExpect(status().isForbidden());
        call(post("/api/v1/surveillance/programmes"), OH_ADMIN, body.replace("QUESTIONNAIRE", "XRAY")).andExpect(status().isBadRequest());
        JsonNode draft = ok(post("/api/v1/surveillance/programmes"), OH_ADMIN, body);
        assertThat(draft.path("status").asString()).isEqualTo("DRAFT");
        call(post("/api/v1/surveillance/programmes"), OH_ADMIN, body).andExpect(status().isConflict());
        JsonNode sim = ok(post("/api/v1/surveillance/programmes/" + code + "/simulate"), CMO, "{}");
        assertThat(sim.path("eligibleHeadcount").asInt()).isPositive();
        assertThat(sim.path("newlyEnrolled").asInt()).isEqualTo(sim.path("eligibleHeadcount").asInt());
        assertThat(sim.path("byUnit").get(0).path("subsidiaryCode").asString()).isNotBlank();
        assertThat(jdbc.queryForObject("select count(*) from surveillance.enrolment where programme_code = ?", Long.class, code)).isZero();

        call(post("/api/v1/surveillance/programmes/" + code + "/activate"), OH_ADMIN, "{}").andExpect(status().isForbidden());
        JsonNode active = ok(post("/api/v1/surveillance/programmes/" + code + "/activate"), CMO, "{}");
        assertThat(active.path("status").asString()).isEqualTo("ACTIVE");
        assertThat(jdbc.queryForObject("select count(*) from surveillance.enrolment where programme_code = ? and active", Long.class, code))
                .isEqualTo(sim.path("eligibleHeadcount").asLong());
        // edit → version 2 draft; activation retires version 1
        ok(put("/api/v1/surveillance/programmes/" + code), OH_ADMIN, body.replace("\"frequencyMonths\":12", "\"frequencyMonths\":24"));
        ok(post("/api/v1/surveillance/programmes/" + code + "/activate"), PHYSICIAN, "{}");
        JsonNode detail = ok(get("/api/v1/surveillance/programmes/" + code), NURSE, null);
        assertThat(detail.path("version").asInt()).isEqualTo(2);
        assertThat(detail.path("frequencyMonths").asInt()).isEqualTo(24);
        assertThat(texts(detail.path("versions"), "status")).containsExactly("ACTIVE", "RETIRED");
        ok(post("/api/v1/surveillance/programmes/" + code + "/retire"), CMO, null);
        assertThat(jdbc.queryForObject("select count(*) from surveillance.enrolment where programme_code = ? and active", Long.class, code)).isZero();
    }

    // =================================================================== F02 due list, invitations, no-show, completion

    @Test
    void f02DueListInvitationsNoShowEscalationAndTeamStatus() throws Exception {
        // demo: E1001 food handler due in 15 days, E1010 overdue and escalated
        JsonNode due = ok(get("/api/v1/surveillance/due?programme=FOOD_HANDLER"), SCHEDULER, null);
        assertThat(texts(due, "employeeId")).contains("E1001", "E1010");
        for (JsonNode e : due) {
            if ("E1001".equals(e.path("employeeId").asString())) {
                assertThat(e.path("status").asString()).isEqualTo("DUE");
            }
            if ("E1010".equals(e.path("employeeId").asString())) {
                assertThat(e.path("status").asString()).isEqualTo("OVERDUE");
                assertThat(e.path("escalatedAt").isMissingNode()).isFalse();
            }
        }
        assertThat(texts(ok(get("/api/v1/surveillance/due?employeeId=E1004"), NURSE, null), "programmeCode")).contains("HEARING_CONSERVATION");
        assertThat(texts(ok(get("/api/v1/surveillance/due?status=OVERDUE"), NURSE, null), "status")).containsOnly("OVERDUE");
        call(get("/api/v1/surveillance/due"), MANAGER, null).andExpect(status().isForbidden());
        call(get("/api/v1/surveillance/due"), HR, null).andExpect(status().isForbidden());

        // new food handler reporting to M2001: enrolled by the recalculation
        String emp = newEmployee("CATERING_ASSOC", "CAT", "CATERING", "M2001");
        call(post("/api/v1/surveillance/due/recalculate"), NURSE, null).andExpect(status().isForbidden());
        assertThat(ok(post("/api/v1/surveillance/due/recalculate"), OH_ADMIN, null).path("enrolled").asInt()).isPositive();
        String enrolment = enrolmentId(emp, "FOOD_HANDLER");
        JsonNode invited = ok(post("/api/v1/surveillance/invitations"), SCHEDULER,
                "{\"enrolmentIds\":[\"" + enrolment + "\"],\"book\":true,\"siteCode\":\"LHR-CAT\"}");
        assertThat(invited.path("invited").asInt()).isEqualTo(1);
        assertThat(invited.path("booked").asInt() + invited.path("notBooked").asInt()).isEqualTo(1);
        String invitation = invited.path("invitationIds").get(0).asString();
        assertThat(notifications(emp.toLowerCase() + "@wellnessz.local", "surveillance.invitation")).isEqualTo(1);
        call(post("/api/v1/surveillance/invitations"), SCHEDULER, "{\"enrolmentIds\":[],\"book\":false}").andExpect(status().isBadRequest());

        // appointment missed → re-invite; second miss → line manager asked (status only) and enrolment escalated
        UUID appointment = UUID.randomUUID();
        assertThat(spi.supports("SURVEILLANCE_INVITATION")).isTrue();
        assertThat(spi.supports("ABSENCE_CONTACT")).isFalse();
        spi.appointmentStatusChanged(appointment, emp, "SURVEILLANCE_INVITATION", invitation, AppointmentStatusListener.Status.BOOKED);
        spi.appointmentStatusChanged(appointment, emp, "SURVEILLANCE_INVITATION", invitation, AppointmentStatusListener.Status.NO_SHOW);
        JsonNode log = ok(get("/api/v1/surveillance/invitations?enrolmentId=" + enrolment), SCHEDULER, null);
        assertThat(log.size()).isEqualTo(2);
        // the re-invitation is booked straight away when the scheduling module (E14) is deployed
        assertThat(log.get(0).path("status").asString()).isIn("INVITED", "BOOKED");
        assertThat(log.get(1).path("status").asString()).isEqualTo("NO_SHOW");
        spi.appointmentStatusChanged(UUID.randomUUID(), emp, "SURVEILLANCE_INVITATION", log.get(0).path("id").asString(),
                AppointmentStatusListener.Status.NO_SHOW);
        assertThat(jdbc.queryForObject("select escalated_at is not null from surveillance.enrolment where id = ?::uuid", Boolean.class, enrolment))
                .isTrue();
        assertThat(notifications("manager1@wellnessz.local", "surveillance.overdue-manager")).isPositive();
        assertThat(jdbc.queryForObject("select body from platform.notification where address = 'manager1@wellnessz.local' "
                + "and template_key = 'surveillance.overdue-manager' order by scheduled_at desc limit 1", String.class))
                .doesNotContain("Food").doesNotContain("FOOD");

        // manager / HR team view: status only
        MvcResult team = call(get("/api/v1/workspace/surveillance/compliance"), MANAGER, null).andExpect(status().isOk()).andReturn();
        JsonNode rows = body(team);
        assertThat(texts(rows, "employeeId")).contains(emp, "E1001", "E1010");
        assertThat(texts(rows, "status")).isSubsetOf("CURRENT", "DUE", "OVERDUE");
        assertThat(team.getResponse().getContentAsString()).doesNotContain("reason").doesNotContain("lastOutcome").doesNotContain("invitations");
        assertThat(texts(body(call(get("/api/v1/workspace/surveillance/compliance"), HR_CARGO, null).andReturn()), "employeeId"))
                .doesNotContain("E1001");
        assertThat(jdbc.queryForObject("select count(*) from platform.disclosure_log where employee_id = ? and resource_type = 'SURVEILLANCE_STATUS'",
                Long.class, emp)).isPositive();
        call(get("/api/v1/workspace/surveillance/compliance"), NURSE, null).andExpect(status().isForbidden());

        // completion by a clinician, exemption by a physician
        call(post("/api/v1/surveillance/enrolments/" + enrolment + "/complete"), SCHEDULER,
                "{\"performedOn\":\"" + TODAY + "\",\"outcome\":\"FIT\"}").andExpect(status().isForbidden());
        JsonNode done = ok(post("/api/v1/surveillance/enrolments/" + enrolment + "/complete"), NURSE,
                "{\"performedOn\":\"" + TODAY + "\",\"outcome\":\"FIT\"}");
        assertThat(done.path("nextDueOn").asString()).isEqualTo(TODAY.plusMonths(12).toString());
        assertThat(done.path("status").asString()).isEqualTo("CURRENT");
        assertThat(done.path("invitations").asInt()).isZero();
        call(post("/api/v1/surveillance/enrolments/" + enrolment + "/exempt"), NURSE, "{\"reason\":\"x\"}").andExpect(status().isForbidden());
        assertThat(ok(post("/api/v1/surveillance/enrolments/" + enrolment + "/exempt"), PHYSICIAN,
                "{\"reason\":\"Temporary redeployment away from food handling\",\"until\":\"" + TODAY.plusMonths(2) + "\"}")
                .path("status").asString()).isEqualTo("EXEMPT");

        // employee's own view
        JsonNode me = ok(get("/api/v1/me/surveillance"), ERIN, null);
        assertThat(texts(me.path("programmes"), "programmeTitle")).contains("Food handler fitness");
        assertThat(me.path("immunisations").isArray()).isTrue();

        // capacity demand (counts only)
        List<CapacityDemandSource.Demand> demand = spi.demand(TODAY, TODAY.plusMonths(3));
        assertThat(demand).isNotEmpty();
        assertThat(demand).allMatch(d -> "E07".equals(d.source()) && d.appointments() > 0);
    }

    // =================================================================== F03 pre-employment incl. candidate portal

    @Test
    void f03PreEmploymentCandidatePortalOutcomeAndHrStatusOnly() throws Exception {
        String candidate = "C" + (100000 + new java.util.Random().nextInt(800000));
        String offer = "OFF-T-" + UUID.randomUUID().toString().substring(0, 8);
        String body = """
                {"offerRef":"%s","candidateId":"%s","candidateName":"Pat Prospect","email":"%s@candidates.local","jobCode":"CATERING_ASSOC",
                 "subsidiaryCode":"CAT","departmentCode":"CATERING","locationCode":"LHR","proposedStart":"%s"}"""
                .formatted(offer, candidate, candidate.toLowerCase(), TODAY.plusDays(30));
        call(post("/api/v1/integration/pre-employment"), NURSE, body).andExpect(status().isForbidden());
        JsonNode created = ok(post("/api/v1/integration/pre-employment"), INTEGRATION, body);
        String id = created.path("id").asString();
        assertThat(created.path("status").asString()).isEqualTo("INVITED");
        assertThat(created.path("panel").toString()).contains("FOOD_HANDLER");
        assertThat(ok(post("/api/v1/integration/pre-employment"), INTEGRATION, body).path("id").asString()).isEqualTo(id); // idempotent
        assertThat(notifications(candidate.toLowerCase() + "@candidates.local", "pre-employment.invitation")).isEqualTo(1);

        JwtRequestPostProcessor cand = user("cand-" + candidate, candidate, null, "CANDIDATE");
        assertThat(ok(get("/api/v1/me/pre-employment"), cand, null).path("offerRef").asString()).isEqualTo(offer);
        call(get("/api/v1/surveillance/pre-employment"), cand, null).andExpect(status().isForbidden());
        call(get("/api/v1/me/pre-employment"), ERIN, null).andExpect(status().isForbidden());
        call(post("/api/v1/me/pre-employment/book"), cand, "{}").andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("questionnaire_and_consent_required"));
        JsonNode questions = ok(get("/api/v1/me/pre-employment/questionnaire"), cand, null);
        assertThat(texts(questions, "code")).contains("ONGOING_TREATMENT", "DECLARATION");
        call(put("/api/v1/me/pre-employment/questionnaire"), cand, "{\"answers\":{\"ONGOING_TREATMENT\":\"MAYBE\"}}")
                .andExpect(status().isBadRequest());
        String answers = """
                {"answers":{"ONGOING_TREATMENT":"YES","ONGOING_TREATMENT_DETAILS":"Secret-qx77 asthma inhaler","TIME_OFF_12M":"NO",
                 "ADJUSTMENTS_NEEDED":"NO","HEARING_VISION":"NO","SKIN_OR_CHEST":"NO","SHIFT_WORK":"NO","DECLARATION":"YES"}}""";
        assertThat(ok(put("/api/v1/me/pre-employment/questionnaire"), cand, answers).path("questionnaireCompleted").asBoolean()).isTrue();
        assertThat(jdbc.queryForObject("select questionnaire from surveillance.pre_employment where id = ?::uuid", String.class, id))
                .doesNotContain("Secret-qx77");
        call(post("/api/v1/me/pre-employment/consent"), cand, "{\"consentGiven\":true,\"purposes\":[\"MARKETING\"]}")
                .andExpect(status().isBadRequest());
        assertThat(ok(post("/api/v1/me/pre-employment/consent"), cand,
                "{\"consentGiven\":true,\"purposes\":[\"PRE_EMPLOYMENT_ASSESSMENT\",\"HR_STATUS_FEED\"]}").path("consentGiven").asBoolean()).isTrue();
        byte[] png = {(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10, 0, 0, 0, 13, 'I', 'H', 'D', 'R'};
        MvcResult up = mvc.perform(multipart("/api/v1/me/pre-employment/documents").file(new MockMultipartFile("file", "vacc.png", "image/png", png))
                .param("title", "Vaccination card").with(cand)).andExpect(status().isCreated()).andReturn();
        assertThat(body(up).path("title").asString()).isEqualTo("Vaccination card");
        assertThat(ok(get("/api/v1/me/pre-employment"), cand, null).path("documents").size()).isEqualTo(1);

        // OH sees the questionnaire; HR sees the clearance status only
        call(get("/api/v1/surveillance/pre-employment"), SCHEDULER, null).andExpect(status().isForbidden());
        assertThat(texts(ok(get("/api/v1/surveillance/pre-employment?status=QUESTIONNAIRE_DONE"), NURSE, null), "id")).contains(id);
        assertThat(ok(get("/api/v1/surveillance/pre-employment/" + id), NURSE, null).path("questionnaire").path("ONGOING_TREATMENT_DETAILS")
                .asString()).isEqualTo("Secret-qx77 asthma inhaler");
        call(get("/api/v1/surveillance/pre-employment/" + id), HR, null).andExpect(status().isForbidden());
        MvcResult hr = call(get("/api/v1/workspace/pre-employment"), HR, null).andExpect(status().isOk()).andReturn();
        assertThat(hr.getResponse().getContentAsString()).contains(offer).doesNotContain("Secret-qx77").doesNotContain("questionnaire")
                .doesNotContain("conditions");
        for (JsonNode r : body(hr)) {
            assertThat(r.path("hrClearance").asString()).isIn("PENDING", "FIT", "UNFIT");
        }
        call(get("/api/v1/workspace/pre-employment"), MANAGER, null).andExpect(status().isForbidden());

        // outcome: conditions screened, HR feed (status only), clearance document
        call(post("/api/v1/surveillance/pre-employment/" + id + "/outcome"), SCHEDULER, "{\"outcome\":\"FIT\"}").andExpect(status().isForbidden());
        call(post("/api/v1/surveillance/pre-employment/" + id + "/outcome"), NURSE,
                "{\"outcome\":\"FIT_WITH_ADJUSTMENTS\",\"conditions\":\"Asthma - avoid flour dust\"}")
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("clinical_content_not_allowed"));
        JsonNode outcome = ok(post("/api/v1/surveillance/pre-employment/" + id + "/outcome"), NURSE,
                "{\"outcome\":\"FIT_WITH_ADJUSTMENTS\",\"conditions\":\"Avoid tasks in the flour room during induction\"}");
        assertThat(outcome.path("hrClearance").asString()).isEqualTo("FIT");
        assertThat(outcome.path("status").asString()).isEqualTo("OUTCOME_RECORDED");
        assertThat(jdbc.queryForObject("select count(*) from platform.outbox_event where destination = 'hr-systems' "
                + "and event_type = 'preemployment.status.v1' and aggregate_id = ?", Long.class, offer)).isEqualTo(1);
        String payload = outbox.findAll().stream().filter(e -> offer.equals(e.getAggregateId()) && "hr-systems".equals(e.getDestination()))
                .findFirst().orElseThrow().getPayload();
        assertThat(payload).contains("\"preEmploymentStatus\":\"FIT\"").contains(candidate).doesNotContain("flour").doesNotContain("asthma");
        String docId = jdbc.queryForObject("select outcome_document_id::text from surveillance.pre_employment where id = ?::uuid", String.class, id);
        assertThat(jdbc.queryForObject("select template_code from docs.document where id = ?::uuid", String.class, docId))
                .isEqualTo("PRE_EMPLOYMENT_OUTCOME");
        assertThat(jdbc.queryForObject("select count(*) from platform.disclosure_log where employee_id = ? and recipient_type = 'HR'", Long.class,
                candidate)).isPositive();
        for (JsonNode r : ok(get("/api/v1/workspace/pre-employment"), HR, null)) {
            if (offer.equals(r.path("offerRef").asString())) {
                assertThat(r.path("hrClearance").asString()).isEqualTo("FIT");
            }
        }
        call(post("/api/v1/surveillance/pre-employment/" + id + "/outcome"), NURSE, "{\"outcome\":\"UNFIT\"}").andExpect(status().isConflict());
        ok(post("/api/v1/surveillance/pre-employment/" + id + "/government-medical"), NURSE,
                "{\"type\":\"VISA\",\"reference\":\"V-123\",\"status\":\"PASSED\",\"expiresOn\":\"" + TODAY.plusYears(1) + "\"}");

        // withdrawal → 12-month retention → purge
        String offer2 = offer + "-W";
        String id2 = ok(post("/api/v1/surveillance/pre-employment"), HR, body.replace(offer, offer2)).path("id").asString();
        assertThat(ok(post("/api/v1/surveillance/pre-employment/" + id2 + "/withdraw"), HR, null).path("retentionUntil").asString())
                .isEqualTo(TODAY.plusMonths(12).toString());
        jdbc.update("update surveillance.pre_employment set retention_until = ? where id = ?::uuid", TODAY.minusDays(1), UUID.fromString(id2));
        assertThat(preEmployment.purge(TODAY)).isPositive();
        call(get("/api/v1/surveillance/pre-employment/" + id2), NURSE, null).andExpect(status().isNotFound());

        // demo candidate C9001 is waiting for the questionnaire (E2E)
        JsonNode demo = ok(get("/api/v1/me/pre-employment"), user("candidate1", "C9001", null, "CANDIDATE"), null);
        assertThat(demo.path("offerRef").asString()).isEqualTo("OFF-2026-0001");
    }

    // =================================================================== F04 crew certificates & roster status

    @Test
    void f04CrewExaminationCertificatesRosterStatusAndExpiry() throws Exception {
        String pilot = newEmployee("PILOT", "FLT", "FLIGHTOPS", null);
        call(get("/api/v1/surveillance/crew/exam-template?type=CLASS1"), NURSE, null).andExpect(status().isForbidden());
        assertThat(texts(ok(get("/api/v1/surveillance/crew/exam-template?type=CLASS1"), AME, null), "section")).contains("Vision", "Cardiovascular");
        String exam = """
                {"employeeId":"%s","certificateType":"CLASS1","examinedOn":"%s","answers":{"BP_SYSTOLIC":128,"AME_COMMENTS":"Secret-zz41 note"},
                 "outcome":"FIT_WITH_LIMITATIONS","limitationCodes":["OML","VDL"]}""".formatted(pilot, TODAY);
        call(post("/api/v1/surveillance/crew/examinations"), CMO, exam).andExpect(status().isForbidden());
        call(post("/api/v1/surveillance/crew/examinations"), AME, exam.replace("\"OML\",", "\"XYZ\","))
                .andExpect(status().isBadRequest());
        JsonNode cert = ok(post("/api/v1/surveillance/crew/examinations"), AME, exam);
        String certId = cert.path("id").asString();
        assertThat(cert.path("status").asString()).isEqualTo("VALID");
        assertThat(cert.path("validUntil").asString()).isEqualTo(TODAY.plusMonths(12).minusDays(1).toString());
        assertThat(cert.path("documentId").asString()).isNotBlank();
        assertThat(jdbc.queryForObject("select template_code from docs.document where id = ?::uuid", String.class, cert.path("documentId").asString()))
                .isEqualTo("CREW_MEDICAL_REPORT");
        assertThat(jdbc.queryForObject("select answers from surveillance.crew_certificate where id = ?::uuid", String.class, certId))
                .doesNotContain("Secret-zz41");
        // roster status LIMITED via the kernel crew feed (E05 roster feed log), operational limitations only
        assertThat(jdbc.queryForObject("select roster_status || '|' || coalesce(operational_limitations, '') from fitness.roster_feed_message "
                + "where employee_id = ? order by sent_at desc limit 1", String.class, pilot)).isEqualTo("LIMITED|OML");

        // crew licensing: status only (operational codes, no outcome / document / answers)
        MvcResult lic = call(get("/api/v1/surveillance/crew/certificates"), LICENSING, null).andExpect(status().isOk()).andReturn();
        String licBody = lic.getResponse().getContentAsString();
        assertThat(licBody).contains(certId).doesNotContain("VDL").doesNotContain("Secret-zz41").doesNotContain("documentId")
                .doesNotContain("FIT_WITH_LIMITATIONS");
        assertThat(texts(body(lic), "employeeId")).contains("E1002", "E1003");
        for (JsonNode c : body(lic)) {
            if ("E1002".equals(c.path("employeeId").asString())) {
                assertThat(c.path("status").asString()).isEqualTo("EXPIRING");
            }
        }
        assertThat(texts(ok(get("/api/v1/surveillance/crew/certificates?expiringWithinDays=60"), CREW_SCHED, null), "employeeId")).contains("E1002")
                .doesNotContain(pilot);
        call(get("/api/v1/surveillance/crew/certificates"), NURSE, null).andExpect(status().isForbidden());
        assertThat(texts(ok(get("/api/v1/surveillance/crew/without-valid-medical"), LICENSING, null), "employeeId")).contains("E7001")
                .doesNotContain(pilot);

        // nightly job: 60-day band alert and expiry → roster UNFIT
        jdbc.update("update surveillance.crew_certificate set valid_until = ? where id = ?::uuid", TODAY.plusDays(45), UUID.fromString(certId));
        CrewCertificateService.CrewRun run = crew.nightly(TODAY);
        assertThat(run.alerts()).isPositive();
        assertThat(notifications(pilot.toLowerCase() + "@wellnessz.local", "crew.medical-expiring")).isEqualTo(1);
        assertThat(notifications("crew-licensing@wellnessz.local", "crew.medical-expiring-licensing")).isPositive();
        assertThat(jdbc.queryForObject("select alerts_sent from surveillance.crew_certificate where id = ?::uuid", String.class, certId))
                .isEqualTo("60,90");
        crew.nightly(TODAY);
        assertThat(notifications(pilot.toLowerCase() + "@wellnessz.local", "crew.medical-expiring")).isEqualTo(1);
        jdbc.update("update surveillance.crew_certificate set valid_until = ? where id = ?::uuid", TODAY.minusDays(1), UUID.fromString(certId));
        assertThat(crew.nightly(TODAY).expired()).isPositive();
        assertThat(jdbc.queryForObject("select roster_status from fitness.roster_feed_message where employee_id = ? order by sent_at desc limit 1",
                String.class, pilot)).isEqualTo("UNFIT");
        assertThat(texts(ok(get("/api/v1/surveillance/crew/without-valid-medical"), CREW_SCHED, null), "employeeId")).contains(pilot);

        // external AME import (integration) → new valid certificate; authority report CSV
        String imp = """
                {"employeeId":"%s","certificateType":"CLASS1","certificateNumber":"UK-AEMC-%s","issuingAuthority":"UK CAA",
                 "aemcName":"Heathrow Aeromedical Centre","examinedOn":"%s","validUntil":"%s","limitationCodes":[]}"""
                .formatted(pilot, pilot, TODAY.minusDays(2), TODAY.plusMonths(6));
        assertThat(ok(post("/api/v1/surveillance/crew/certificates/import"), INTEGRATION, imp).path("source").asString()).isEqualTo("EXTERNAL_AME");
        assertThat(jdbc.queryForObject("select roster_status from fitness.roster_feed_message where employee_id = ? order by sent_at desc limit 1",
                String.class, pilot)).isEqualTo("FIT");
        MvcResult csv = call(get("/api/v1/surveillance/crew/authority-report?from=" + TODAY.minusDays(5) + "&to=" + TODAY), AME, null)
                .andExpect(status().isOk()).andReturn();
        assertThat(csv.getResponse().getContentType()).startsWith("text/csv");
        assertThat(csv.getResponse().getContentAsString()).contains(pilot).contains("EXTERNAL_AME");
        call(get("/api/v1/surveillance/crew/authority-report"), LICENSING, null).andExpect(status().isForbidden());

        // crew pregnancy declaration → AME review (restricted to AME / CMO)
        JwtRequestPostProcessor pilotUser = user("pilot-" + pilot, pilot, null, "EMPLOYEE");
        assertThat(ok(post("/api/v1/me/pregnancy-declaration"), pilotUser, "{\"declaredOn\":\"" + TODAY + "\"}").path("status").asString())
                .isEqualTo("RECEIVED");
        call(post("/api/v1/me/pregnancy-declaration"), ERIN, "{}").andExpect(status().isUnprocessableContent());
        ok(post("/api/v1/surveillance/cosmic-dose/import"), AME,
                "[{\"employeeId\":\"" + pilot + "\",\"year\":" + (TODAY.getYear() - 1) + ",\"doseMsv\":6.8,\"source\":\"CARI-7\"}]");
        JsonNode doses = ok(get("/api/v1/surveillance/cosmic-dose?aboveThreshold=true"), CMO, null);
        boolean found = false;
        for (JsonNode d : doses) {
            if (pilot.equals(d.path("employeeId").asString())) {
                found = true;
                assertThat(d.path("pregnancyDeclared").asBoolean()).isTrue();
            }
        }
        assertThat(found).isTrue();
        call(get("/api/v1/surveillance/cosmic-dose"), NURSE, null).andExpect(status().isForbidden());
        assertThat(ok(get("/api/v1/surveillance/employees/" + pilot + "/exposure-history"), NURSE, null).path("cosmicDose").size()).isZero();
        JsonNode history = ok(get("/api/v1/surveillance/employees/" + pilot + "/exposure-history"), AME, null);
        assertThat(history.path("cosmicDose").size()).isEqualTo(1);
        assertThat(history.path("pregnancyDeclared").asBoolean()).isTrue();
    }

    // =================================================================== F05 immunisation

    @Test
    void f05ImmunisationFastEntryBatchTitreAndStatusViews() throws Exception {
        String cleaner = newEmployee("CLEANER", "GND", "GROUND", null);
        String exp = TODAY.plusMonths(6).format(java.time.format.DateTimeFormatter.ofPattern("yyMMdd"));
        String admin = """
                {"badgeId":"B-%s","vaccineCode":"HEP_B","doseNumber":1,"lotBarcode":"(01)05012345678900(17)%s(10)HB2611A","site":"LEFT_DELTOID",
                 "route":"IM","consentConfirmed":true}""".formatted(cleaner, exp);
        call(post("/api/v1/surveillance/immunisation/administrations"), SCHEDULER, admin).andExpect(status().isForbidden());
        call(post("/api/v1/surveillance/immunisation/administrations"), NURSE, admin.replace("\"consentConfirmed\":true", "\"consentConfirmed\":false"))
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("consent_required"));
        String expired = TODAY.minusDays(3).format(java.time.format.DateTimeFormatter.ofPattern("yyMMdd"));
        call(post("/api/v1/surveillance/immunisation/administrations"), NURSE, admin.replace("(17)" + exp, "(17)" + expired))
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("lot_expired"));
        JsonNode dose = ok(post("/api/v1/surveillance/immunisation/administrations"), NURSE, admin);
        assertThat(dose.path("lotNumber").asString()).isEqualTo("HB2611A");
        assertThat(dose.path("lotExpiry").asString()).isEqualTo(TODAY.plusMonths(6).toString());
        assertThat(jdbc.queryForObject("select code || '|' || status from ohrecord.immunisation where id = ?::uuid", String.class,
                dose.path("recordId").asString())).isEqualTo("HEP_B|PARTIAL");
        call(post("/api/v1/surveillance/immunisation/administrations"), NURSE, admin).andExpect(status().isConflict());

        // campaign batch
        JsonNode batch = ok(post("/api/v1/surveillance/immunisation/administrations/batch"), NURSE, """
                {"vaccineCode":"INFLUENZA","lotNumber":"FLU-26-01","site":"LEFT_DELTOID","route":"IM",
                 "entries":[{"employeeId":"%s","consentConfirmed":true},{"employeeId":"NOPE999","consentConfirmed":true},
                            {"employeeId":"E1005","consentConfirmed":false}]}""".formatted(cleaner));
        assertThat(batch.path("recorded").asInt()).isEqualTo(1);
        assertThat(texts(batch.path("rejected"), "reason")).containsExactlyInAnyOrder("unknown_employee", "consent_required");

        // titres and status
        ok(post("/api/v1/surveillance/immunisation/titres"), NURSE,
                "{\"employeeId\":\"" + cleaner + "\",\"antigen\":\"HEP_B\",\"value\":8,\"unit\":\"mIU/mL\",\"protective\":false,\"testedOn\":\"" + TODAY + "\"}");
        JsonNode st = ok(get("/api/v1/surveillance/immunisation/employees/" + cleaner), NURSE, null);
        assertThat(texts(st, "vaccineCode")).contains("HEP_B");
        assertThat(ok(get("/api/v1/surveillance/immunisation/employees/E1009"), NURSE, null).toString()).contains("\"protective\":true");
        JsonNode dueList = ok(get("/api/v1/surveillance/immunisation/due?vaccine=HEP_B"), SCHEDULER, null);
        assertThat(texts(dueList, "employeeId")).contains(cleaner).doesNotContain("E1009");
        assertThat(ok(get("/api/v1/surveillance/immunisation/requirements"), OH_ADMIN, null).size()).isGreaterThanOrEqualTo(11);
        call(get("/api/v1/surveillance/immunisation/requirements"), SCHEDULER, null).andExpect(status().isForbidden());
        ok(put("/api/v1/surveillance/immunisation/requirements/" + UUID.randomUUID()), OH_ADMIN,
                "{\"jobCode\":\"FORKLIFT_OP\",\"vaccineCode\":\"TDAP_IPV\",\"doses\":1,\"boosterMonths\":120,\"mandatory\":false}");

        // hygiene manager: food handlers only, status only
        MvcResult hyg = call(get("/api/v1/surveillance/immunisation/employees/E1001"), HYGIENE, null).andExpect(status().isOk()).andReturn();
        assertThat(hyg.getResponse().getContentAsString()).contains("TYPHOID").doesNotContain("titre").doesNotContain("lastDoseOn")
                .doesNotContain("dosesGiven");
        call(get("/api/v1/surveillance/immunisation/employees/E1004"), HYGIENE, null).andExpect(status().isNotFound());
        call(get("/api/v1/surveillance/immunisation/employees/E1001"), MANAGER, null).andExpect(status().isForbidden());

        // employee: own status and certificate
        assertThat(texts(ok(get("/api/v1/me/immunisations"), ERIN, null), "vaccineCode")).contains("TYPHOID", "HEP_A");
        JsonNode certificate = ok(post("/api/v1/me/immunisations/certificate"), ERIN, null);
        assertThat(certificate.path("templateCode").asString()).isEqualTo("VACCINATION_CERTIFICATE");
        assertThat(certificate.path("status").asString()).isEqualTo("SIGNED");
    }

    // =================================================================== F06 exposure & IH linkage

    @Test
    void f06IhImportEnrolsSegMembersAboveActionLevel() throws Exception {
        String forklift = newEmployee("FORKLIFT_OP", "CGO", "CARGO", null);
        JsonNode segs = ok(get("/api/v1/surveillance/exposure-groups"), HSE, null);
        for (JsonNode s : segs) {
            if ("LHR-RAMP-NOISE".equals(s.path("code").asString())) {
                assertThat(s.path("aboveActionLevel").asBoolean()).isTrue();
                assertThat(s.path("enrolledProgrammes").toString()).contains("HEARING_CONSERVATION");
            }
        }
        call(get("/api/v1/surveillance/exposure-groups"), MANAGER, null).andExpect(status().isForbidden());
        String ref = "HSE-T-" + UUID.randomUUID();
        String rows = """
                [{"sourceRef":"%s","segCode":"LHR-CARGO-NOISE","hazardCode":"NOISE","measurementType":"NOISE_DOSIMETRY","value":86.0,
                  "unit":"dB(A)","measuredOn":"%s"}]""".formatted(ref, TODAY);
        call(post("/api/v1/surveillance/ih-measurements/import"), NURSE, rows).andExpect(status().isForbidden());
        JsonNode r = ok(post("/api/v1/surveillance/ih-measurements/import"), HSE, rows);
        assertThat(r.path("imported").asInt()).isEqualTo(1);
        assertThat(r.path("segsAboveActionLevel").asInt()).isEqualTo(1);
        assertThat(r.path("employeesEnrolled").asInt()).isPositive();
        assertThat(jdbc.queryForObject("select reason from surveillance.enrolment where employee_id = ? and programme_code = 'HEARING_CONSERVATION'",
                String.class, forklift)).isIn("EXPOSURE_GROUP", "HAZARD");
        assertThat(ok(post("/api/v1/integration/ih-measurements"), INTEGRATION, rows).path("duplicates").asInt()).isEqualTo(1);
        call(post("/api/v1/surveillance/ih-measurements/import"), HSE, rows.replace("LHR-CARGO-NOISE", "NOPE")).andExpect(status().isBadRequest());
        assertThat(texts(ok(get("/api/v1/surveillance/ih-measurements?segCode=LHR-CARGO-NOISE"), HSE, null), "sourceRef")).contains(ref);
        JsonNode history = ok(get("/api/v1/surveillance/employees/" + forklift + "/exposure-history"), NURSE, null);
        assertThat(texts(history.path("exposures"), "segCode")).contains("LHR-CARGO-NOISE");
        call(get("/api/v1/surveillance/employees/" + forklift + "/exposure-history"), HSE, null).andExpect(status().isForbidden());
        ok(post("/api/v1/surveillance/exposure-groups"), HSE, """
                {"code":"LHR-T-VIB","name":"Test vibration","locationCode":"LHR","jobCodes":["FORKLIFT_OP"],"hazardCode":"VIBRATION",
                 "unit":"m/s2","actionLevel":2.5,"limitValue":5.0}""");
    }

    // =================================================================== F07 result evaluation (E03 SPI) & return after illness (E05 guard)

    @Test
    void f07DeviceStsResultRaisesFollowUpAndCase() throws Exception {
        String engineer = newEmployee("ENGINEER", "ENG", "ENG", null);
        ok(post("/api/v1/surveillance/due/recalculate"), OH_ADMIN, null);
        assertThat(enrolmentId(engineer, "HEARING_CONSERVATION")).isNotNull();
        String device = """
                {"category":"AUDIOMETRY","deviceId":"AUD-LHR-01","calibrationId":"CAL-T","measurements":
                 {"left":{"2000":%d,"3000":%d,"4000":%d},"right":{"2000":10,"3000":10,"4000":10}}}""";
        JsonNode base = ok(post("/api/v1/employees/" + engineer + "/results/device"), NURSE, device.formatted(10, 10, 10));
        assertThat(base.path("baseline").asBoolean()).isTrue();
        assertThat(jdbc.queryForObject("select count(*) from surveillance.follow_up where employee_id = ?", Long.class, engineer)).isZero();
        JsonNode sts = ok(post("/api/v1/employees/" + engineer + "/results/device"), NURSE, device.formatted(25, 30, 35));
        assertThat(sts.path("flag").asString()).isEqualTo("ABNORMAL");
        String resultId = sts.path("id").asString();
        assertThat(jdbc.queryForObject("select rule_code || '|' || programme_code || '|' || status || '|' || regulatory_notification_required "
                + "from surveillance.follow_up where result_id = ?::uuid", String.class, resultId)).isEqualTo("STS_HEARING|HEARING_CONSERVATION|OPEN|true");
        String caseId = jdbc.queryForObject("select case_id::text from surveillance.follow_up where result_id = ?::uuid", String.class, resultId);
        assertThat(jdbc.queryForObject("select case_type || '|' || source || '|' || source_ref from casemgmt.oh_case where id = ?::uuid",
                String.class, caseId)).isEqualTo("SURVEILLANCE_FOLLOW_UP|SURVEILLANCE_TRIGGER|surveillance:" + resultId);
        assertThat(jdbc.queryForObject("select count(*) from casemgmt.case_task where case_id = ?::uuid", Long.class, caseId)).isEqualTo(2);
        assertThat(jdbc.queryForObject("select count(*) from platform.outbox_event where event_type = 'surveillance.followup.created.v1' "
                + "and payload is not null and aggregate_id in (select id::text from surveillance.follow_up where result_id = ?::uuid)", Long.class,
                resultId)).isEqualTo(1);
        assertThat(notifications("nurse1@wellnessz.local", "surveillance.follow-up")).isPositive();

        JsonNode list = ok(get("/api/v1/surveillance/follow-ups?status=OPEN"), NURSE, null);
        String fuId = null;
        for (JsonNode f : list) {
            if (resultId.equals(f.path("resultId").asString())) {
                fuId = f.path("id").asString();
                assertThat(f.path("recheckDueOn").asString()).isEqualTo(TODAY.plusDays(30).toString());
            }
        }
        assertThat(fuId).isNotNull();
        assertThat(texts(list, "employeeId")).contains("E1006"); // demo open follow-up
        call(get("/api/v1/surveillance/follow-ups"), SCHEDULER, null).andExpect(status().isForbidden());
        call(get("/api/v1/surveillance/follow-ups"), MANAGER, null).andExpect(status().isForbidden());
        call(post("/api/v1/surveillance/follow-ups/" + fuId + "/review"), NURSE, "{\"decision\":\"CONFIRMED\"}").andExpect(status().isForbidden());
        assertThat(ok(post("/api/v1/surveillance/follow-ups/" + fuId + "/review"), PHYSICIAN,
                "{\"decision\":\"WORK_RELATED\",\"note\":\"Noise-induced notch\",\"regulatoryNotificationRequired\":true}").path("status").asString())
                .isEqualTo("REVIEWED");

        // evaluation rules
        assertThat(texts(ok(get("/api/v1/surveillance/evaluation-rules"), NURSE, null), "code")).contains("STS_HEARING", "SPIRO_DECLINE", "BM_BLOOD_LEAD");
        call(put("/api/v1/surveillance/evaluation-rules/BM_ZINC"), NURSE, """
                {"test":"BIOMONITORING:5763-8","ruleType":"BM_LIMIT","params":{"limit":200},"followUpDays":14,"regulatoryNotification":false}""")
                .andExpect(status().isForbidden());
        assertThat(ok(put("/api/v1/surveillance/evaluation-rules/BM_ZINC"), CMO, """
                {"test":"BIOMONITORING:5763-8","ruleType":"BM_LIMIT","params":{"limit":200},"followUpDays":14,"regulatoryNotification":false}""")
                .path("params").path("limit").asInt()).isEqualTo(200);

        // a normal result of a non-enrolled person raises nothing
        String office = newEmployee("OFFICE_ADMIN", "CORP", "CORP", null);
        ok(post("/api/v1/employees/" + office + "/results/device"), NURSE, """
                {"category":"ECG","measurements":{"interpretation":"ABNORMAL"}}""");
        assertThat(jdbc.queryForObject("select count(*) from surveillance.follow_up where employee_id = ?", Long.class, office)).isZero();
    }

    @Test
    void f07ReturnAfterIllnessBlocksFitDecisionUntilCompleted() throws Exception {
        String ramp = newEmployee("RAMP_AGENT", "GND", "GROUND", null);
        String office = newEmployee("OFFICE_ADMIN", "CORP", "CORP", null);
        absenceSignals.longTermAbsence(office, null, TODAY.minusDays(40), 40); // not safety-critical → nothing
        absenceSignals.longTermAbsence(ramp, null, TODAY.minusDays(40), 40);
        absenceSignals.longTermAbsence(ramp, null, TODAY.minusDays(40), 41); // once
        JsonNode rai = ok(get("/api/v1/surveillance/return-after-illness?status=REQUIRED"), NURSE, null);
        assertThat(texts(rai, "employeeId")).contains(ramp).doesNotContain(office);
        assertThat(texts(rai, "employeeId").stream().filter(ramp::equals).count()).isEqualTo(1);
        String raiId = null;
        for (JsonNode r : rai) {
            if (ramp.equals(r.path("employeeId").asString())) {
                raiId = r.path("id").asString();
                assertThat(r.path("source").asString()).isEqualTo("E06");
            }
        }
        String caseId = openCase(ramp, "GROUND", "GND");
        call(post("/api/v1/cases/" + caseId + "/fitness-decisions"), NURSE, "{\"standardCode\":\"AIRSIDE_DRIVING\",\"outcome\":\"FIT\"}")
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("clearance_required"));
        call(post("/api/v1/surveillance/return-after-illness"), NURSE, "{\"employeeId\":\"" + ramp + "\",\"reason\":\"SURGERY\"}")
                .andExpect(status().isConflict());
        call(post("/api/v1/surveillance/return-after-illness/" + raiId + "/complete"), SCHEDULER, "{}").andExpect(status().isForbidden());
        assertThat(ok(post("/api/v1/surveillance/return-after-illness/" + raiId + "/complete"), NURSE, "{\"note\":\"Cleared\"}")
                .path("status").asString()).isEqualTo("COMPLETED");
        call(post("/api/v1/cases/" + caseId + "/fitness-decisions"), NURSE, "{\"standardCode\":\"AIRSIDE_DRIVING\",\"outcome\":\"FIT\"}")
                .andExpect(status().isCreated());

        // manual RAI and physician waiver
        String id = ok(post("/api/v1/surveillance/return-after-illness"), NURSE, "{\"employeeId\":\"" + ramp + "\",\"reason\":\"CARDIAC_EVENT\"}")
                .path("id").asString();
        call(post("/api/v1/surveillance/return-after-illness/" + id + "/waive"), NURSE, "{\"reason\":\"x\"}").andExpect(status().isForbidden());
        assertThat(ok(post("/api/v1/surveillance/return-after-illness/" + id + "/waive"), PHYSICIAN, "{\"reason\":\"Specialist clearance on file\"}")
                .path("status").asString()).isEqualTo("WAIVED");
    }

    // =================================================================== F08 compliance & audit packs

    @Test
    void f08ComplianceSnapshotTrendOverdueAndAuditPacks() throws Exception {
        jobs.runDue(TODAY);
        JsonNode c = ok(get("/api/v1/surveillance/compliance?groupBy=PROGRAMME"), AUDITOR, null);
        assertThat(c.path("refreshedAt").asString()).isNotBlank();
        assertThat(c.path("rows").size()).isPositive();
        for (JsonNode r : c.path("rows")) {
            if (r.path("suppressed").asBoolean()) {
                assertThat(r.has("enrolled")).isFalse();
                assertThat(r.has("overdue")).isFalse();
            } else {
                assertThat(r.path("enrolled").asInt()).isGreaterThanOrEqualTo(3);
            }
        }
        assertThat(ok(get("/api/v1/surveillance/compliance?groupBy=DEPARTMENT&programme=HEARING_CONSERVATION"), HSE, null).path("rows").isArray())
                .isTrue();
        call(get("/api/v1/surveillance/compliance"), MANAGER, null).andExpect(status().isForbidden());
        call(get("/api/v1/surveillance/compliance?groupBy=COUNTRY"), CMO, null).andExpect(status().isBadRequest());
        assertThat(ok(get("/api/v1/surveillance/compliance/trend"), CMO, null).size()).isPositive();
        JsonNode overdue = ok(get("/api/v1/surveillance/compliance/overdue?programme=HEARING_CONSERVATION"), NURSE, null);
        assertThat(texts(overdue, "employeeId")).contains("E1004");
        call(get("/api/v1/surveillance/compliance/overdue"), AUDITOR, null).andExpect(status().isForbidden());
        call(get("/api/v1/surveillance/compliance/overdue"), HSE, null).andExpect(status().isForbidden());

        assertThat(texts(ok(get("/api/v1/surveillance/audit-packs/templates"), AUDITOR, null), "code"))
                .containsExactly("IOSA", "CAA", "FOOD_SAFETY", "LABOUR_INSPECTORATE");
        call(get("/api/v1/surveillance/audit-packs/templates"), NURSE, null).andExpect(status().isForbidden());
        String req = "{\"template\":\"LABOUR_INSPECTORATE\",\"programmeCode\":\"HEARING_CONSERVATION\",\"from\":\"" + TODAY.minusYears(2)
                + "\",\"to\":\"" + TODAY + "\",\"anonymise\":false}";
        JsonNode pack = ok(post("/api/v1/surveillance/audit-packs"), AUDITOR, req);
        assertThat(pack.path("anonymised").asBoolean()).isTrue(); // auditors always get anonymised packs
        assertThat(pack.path("files").size()).isEqualTo(2);
        String id = pack.path("id").asString();
        MvcResult csv = call(get("/api/v1/surveillance/audit-packs/" + id + "/content?format=csv"), AUDITOR, null).andExpect(status().isOk()).andReturn();
        assertThat(csv.getResponse().getContentAsString()).contains("EXAMINATION").doesNotContain("E1004").doesNotContain("E1006");
        MvcResult pdf = call(get("/api/v1/surveillance/audit-packs/" + id + "/content?format=pdf"), AUDITOR, null).andExpect(status().isOk()).andReturn();
        assertThat(new String(pdf.getResponse().getContentAsByteArray(), 0, 4, java.nio.charset.StandardCharsets.US_ASCII)).isEqualTo("%PDF");
        JsonNode identifiable = ok(post("/api/v1/surveillance/audit-packs"), CMO, req);
        assertThat(identifiable.path("anonymised").asBoolean()).isFalse();
        call(get("/api/v1/surveillance/audit-packs/" + identifiable.path("id").asString() + "/content?format=csv"), AUDITOR, null)
                .andExpect(status().isNotFound());
        assertThat(call(get("/api/v1/surveillance/audit-packs/" + identifiable.path("id").asString() + "/content?format=csv"), CMO, null)
                .andReturn().getResponse().getContentAsString()).contains("E1004");
    }
}
