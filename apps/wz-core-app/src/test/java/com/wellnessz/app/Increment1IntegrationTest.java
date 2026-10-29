package com.wellnessz.app;

import static com.wellnessz.app.PlatformIntegrationTest.user;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.wellnessz.integration.outbox.OutboxRelay;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Acceptance tests for increment 1: E01 intake and E02 triage / queues / SLA. Uses the demo people from db/demo. */
class Increment1IntegrationTest extends IntegrationTestBase {

    @Autowired
    MockMvc mvc;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    OutboxRelay relay;
    @Autowired
    JsonMapper json;

    static final JwtRequestPostProcessor NURSE = user("nurse1", "N1", null, "OH_CLINICIAN");
    static final JwtRequestPostProcessor PHYSICIAN = user("physician1", "PH1", null, "OH_PHYSICIAN");
    static final JwtRequestPostProcessor CMO = user("cmo1", "CM1", null, "CMO");
    static final JwtRequestPostProcessor HEAD = user("nursinghead1", "NH1", null, "NURSING_HEAD", "OH_CLINICIAN");
    static final JwtRequestPostProcessor INTEGRATION = user("ehs-system", null, null, "INTEGRATION");
    static final JwtRequestPostProcessor HSE = user("hse1", "H1", null, "HSE_OFFICER");

    JsonNode body(MvcResult r) throws Exception {
        return json.readTree(r.getResponse().getContentAsString());
    }

    String incident(String ref, String employeeId, String incidentClass) {
        return """
                {"externalRef":"%s","occurredAt":"%s","incidentClass":"%s","severity":"MEDIUM","bodyPart":"HAND",
                 "location":"LHR-T5","unitCode":"CAT","shift":"EARLY","employeeId":"%s","orgUnitCode":"CATERING",
                 "description":"Cut to right hand on galley trolley","reporterEmail":"hse.officer@wellnessz.local"}"""
                .formatted(ref, Instant.now().minus(1, ChronoUnit.HOURS), incidentClass, employeeId);
    }

    @Test
    void incidentWebhookAutoCreatesPrefilledCaseRoutesAndStartsSla() throws Exception {
        String ref = "INC-" + UUID.randomUUID().toString().substring(0, 8);
        mvc.perform(post("/api/v1/intake/ehs/incidents").with(INTEGRATION).header("Idempotency-Key", "k-" + ref)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"incidents\":[" + incident(ref, "E1001", "INJURY_MEDICAL_TREATMENT") + ",{\"externalRef\":\"\"}]}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.accepted").value(1))
                .andExpect(jsonPath("$.deadLettered").value(1));
        relay.relay(); // incident -> rule engine -> case.opened -> routing + SLA + notifications
        relay.relay();
        var row = jdbc.queryForMap("select * from casemgmt.oh_case where incident_ref = ?", ref);
        assertThat(row.get("case_ref").toString()).matches("CAT-\\d{4}-\\d{6}");
        assertThat(row.get("subsidiary_code")).isEqualTo("CAT");
        assertThat(row.get("cost_centre")).isEqualTo("CC-CAT-100");
        assertThat(row.get("job_code")).isEqualTo("CATERING_ASSOC");
        assertThat(row.get("safety_category")).isEqualTo("FOOD_HANDLER");
        assertThat(row.get("queue_code")).isEqualTo("FOOD_SAFETY");     // safety-critical → physician food-safety queue
        assertThat(row.get("owner_username")).isEqualTo("physician1");
        assertThat(row.get("urgency")).isEqualTo("SEMI_URGENT");
        Long timers = jdbc.queryForObject("select count(*) from triage.sla_timer where case_id = ? and status = 'RUNNING'",
                Long.class, row.get("id"));
        assertThat(timers).isEqualTo(1);
        // notification matrix: OH queue + HSE reporter + line manager + hygiene manager, all content-free
        var bodies = jdbc.queryForList("select body from platform.notification where body like ?", String.class,
                "%" + row.get("case_ref") + "%");
        assertThat(bodies).isNotEmpty().allSatisfy(b -> assertThat(b).doesNotContainIgnoringCase("cut").doesNotContain("hand"));
        // HSE reconciliation shows the incident → case without clinical text
        MvcResult rec = mvc.perform(get("/api/v1/intake/reconciliation").with(HSE)).andExpect(status().isOk()).andReturn();
        assertThat(rec.getResponse().getContentAsString()).contains(ref).contains("AUTO_CREATED").doesNotContain("galley");
        mvc.perform(get("/api/v1/intake/reconciliation/export").with(HSE))
                .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.containsString("attachment")))
                .andExpect(content().contentTypeCompatibleWith("text/csv"));
        // DLQ holds the invalid item
        mvc.perform(get("/api/v1/intake/dlq").with(user("admin", null, null, "OH_ADMIN")))
                .andExpect(jsonPath("$[0].reason").exists());
    }

    @Test
    void suggestedIncidentsAreReviewedAndNearMissIgnored() throws Exception {
        String ref = "INC-" + UUID.randomUUID().toString().substring(0, 8);
        String ref2 = ref + "-NM";
        mvc.perform(post("/api/v1/intake/ehs/incidents").with(INTEGRATION).header("Idempotency-Key", "k-" + ref)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"incidents\":[" + incident(ref, "E1004", "INJURY_FIRST_AID") + "," + incident(ref2, "E1004", "NEAR_MISS") + "]}"))
                .andExpect(status().isAccepted());
        relay.relay();
        MvcResult s = mvc.perform(get("/api/v1/intake/suggestions").with(NURSE)).andExpect(status().isOk()).andReturn();
        String incidentId = null;
        for (JsonNode n : body(s)) {
            if (ref.equals(n.get("externalRef").asString())) {
                incidentId = n.get("incidentId").asString();
            }
        }
        assertThat(incidentId).isNotNull();
        mvc.perform(post("/api/v1/intake/suggestions/" + incidentId + "/accept").with(NURSE).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.caseRef").value(org.hamcrest.Matchers.startsWith("GND-")));
        String outcome = jdbc.queryForObject("""
                select e.outcome from intake.incident_evaluation e join intake.ehs_incident i on i.id = e.incident_id
                 where i.external_ref = ?""", String.class, ref2);
        assertThat(outcome).isEqualTo("IGNORED");
    }

    @Test
    void selfReferralWithTemplateBodyMapConsentAndAttachment() throws Exception {
        mvc.perform(get("/api/v1/intake/templates").param("audience", "EMPLOYEE").param("lang", "ar").with(user("e", "E1001", null, "EMPLOYEE")))
                .andExpect(jsonPath("$.length()").value(6))
                .andExpect(jsonPath("$[0].title").value(org.hamcrest.Matchers.containsString("التموين")));
        String req = """
                {"templateCode":"CATERING","category":"INJURY","description":"Burnt my left hand on the oven door",
                 "bodyParts":["HAND_L"],"symptoms":["BURN","PAIN"],"answers":{"atWork":"YES","handledFood":"YES"},
                 "preferredContact":"PHONE","language":"en","consent":{"assessment":true,"purposes":["TREATMENT_SHARING_PARTNER"]}}""";
        MvcResult r = mvc.perform(post("/api/v1/intake/referrals/self").with(user("employee1", "E1001", "/AIRLINE/CATERING", "EMPLOYEE"))
                        .contentType(MediaType.APPLICATION_JSON).content(req))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.caseRef").value(org.hamcrest.Matchers.startsWith("CAT-")))
                .andReturn();
        String caseId = body(r).get("caseId").asString();
        byte[] png = {(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10, 0, 0, 0, 0};
        mvc.perform(multipart("/api/v1/cases/" + caseId + "/attachments").file(new MockMultipartFile("file", "photo.png", "image/png", png))
                        .with(user("employee1", "E1001", null, "EMPLOYEE")))
                .andExpect(status().isCreated());
        mvc.perform(multipart("/api/v1/cases/" + caseId + "/attachments")
                        .file(new MockMultipartFile("file", "evil.png", "image/png", "<script>".getBytes()))
                        .with(user("employee1", "E1001", null, "EMPLOYEE")))
                .andExpect(status().isBadRequest());
        String stored = jdbc.queryForObject("select content from casemgmt.case_attachment where case_id = ?::uuid", String.class, caseId);
        assertThat(stored).startsWith("v1:");
        // consent captured
        Long consents = jdbc.queryForObject("select count(*) from platform.consent where employee_id = 'E1001' and purpose = 'TREATMENT_SHARING_PARTNER'", Long.class);
        assertThat(consents).isPositive();
        // missing consent rejected
        mvc.perform(post("/api/v1/intake/referrals/self").with(user("employee1", "E1001", null, "EMPLOYEE"))
                        .contentType(MediaType.APPLICATION_JSON).content(req.replace("\"assessment\":true", "\"assessment\":false")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("consent_required"));
    }

    @Test
    void managerReferralBlocksClinicalContentAndOutOfScopeEmployees() throws Exception {
        var manager = user("manager1", "M2001", "/AIRLINE/CATERING", "MANAGER");
        mvc.perform(post("/api/v1/intake/referrals/manager").with(manager).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"employeeId\":\"E1001\",\"dutiesAffected\":\"I think she has depression\",\"startDate\":\"2026-10-20\",\"safetyConcern\":false}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("clinical_content_not_allowed"))
                .andExpect(jsonPath("$.field").value("dutiesAffected"));
        mvc.perform(post("/api/v1/intake/referrals/manager").with(manager).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"employeeId\":\"E1001\",\"dutiesAffected\":\"Cannot lift trolleys above 10 kg\",\"startDate\":\"2026-10-20\",\"safetyConcern\":true}"))
                .andExpect(status().isCreated());
        mvc.perform(post("/api/v1/intake/referrals/manager").with(manager).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"employeeId\":\"E1002\",\"dutiesAffected\":\"Missed shifts\",\"safetyConcern\":false}"))
                .andExpect(status().isNotFound()); // pilot is outside the catering manager's scope
        mvc.perform(get("/api/v1/intake/employees/lookup").param("q", "Paul").with(manager))
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void walkInEncounterIsIdempotentHidesClinicalFieldsFromReceptionAndConverts() throws Exception {
        var reception = user("reception1", null, null, "CLINIC_RECEPTION");
        mvc.perform(get("/api/v1/intake/employees/by-badge/B-1005").with(reception)).andExpect(jsonPath("$.employeeId").value("E1005"));
        String clientRef = UUID.randomUUID().toString();
        String enc = "{\"clientRef\":\"" + clientRef + "\",\"mode\":\"EMERGENCY\",\"employeeId\":\"E1005\",\"siteCode\":\"HQ\","
                + "\"location\":\"Floor 3\",\"chiefComplaint\":\"Twisted ankle on stairs\",\"actionTaken\":\"Ice pack\"}";
        MvcResult first = mvc.perform(post("/api/v1/intake/encounters").with(reception).contentType(MediaType.APPLICATION_JSON).content(enc))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.chiefComplaint").doesNotExist())
                .andExpect(jsonPath("$.enrichmentDue").value(true)).andReturn();
        MvcResult retry = mvc.perform(post("/api/v1/intake/encounters").with(reception).contentType(MediaType.APPLICATION_JSON).content(enc))
                .andExpect(status().isCreated()).andReturn();
        assertThat(body(first).get("id")).isEqualTo(body(retry).get("id"));
        mvc.perform(post("/api/v1/intake/encounters").with(reception).contentType(MediaType.APPLICATION_JSON)
                        .content(enc.replace(clientRef, UUID.randomUUID().toString()).replace("E1005", "NOPE1")))
                .andExpect(status().isUnprocessableContent());
        mvc.perform(post("/api/v1/intake/encounters/" + body(first).get("id").asString() + "/convert").with(reception))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/intake/encounters/" + body(first).get("id").asString() + "/convert").with(NURSE))
                .andExpect(status().isOk()).andExpect(jsonPath("$.caseRef").value(org.hamcrest.Matchers.startsWith("CORP-")));
    }

    @Test
    void recordabilityIsMandatoryBeforeLeavingIntakeAndChangesNeedReason() throws Exception {
        String caseId = openInjuryCase("E1005");
        mvc.perform(post("/api/v1/cases/" + caseId + "/transition").with(NURSE).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"TRIAGE\"}"))
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("recordability_required"));
        mvc.perform(post("/api/v1/cases/" + caseId + "/recordability").with(NURSE).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"answers\":{\"fatality\":false,\"daysAwayFromWork\":2},\"workRelated\":\"YES\",\"rationale\":\"r\"}"))
                .andExpect(jsonPath("$.recordability").value("LOST_TIME")).andExpect(jsonPath("$.caaReportable").value(true));
        mvc.perform(post("/api/v1/cases/" + caseId + "/recordability").with(NURSE).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"answers\":{\"medicalTreatmentBeyondFirstAid\":true},\"workRelated\":\"YES\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("reason_required"));
        mvc.perform(post("/api/v1/cases/" + caseId + "/transition").with(NURSE).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"TRIAGE\"}"))
                .andExpect(status().isOk());
    }

    @Test
    void triageEngineRedFlagOverrideTelephoneCallBackAndSafetyClosureGuard() throws Exception {
        String caseId = openInjuryCase("E1004"); // ramp agent = airside driver (safety-critical)
        mvc.perform(get("/api/v1/triage/protocols").with(NURSE)).andExpect(jsonPath("$.length()").value(org.hamcrest.Matchers.greaterThanOrEqualTo(12)));
        // red flag → EMERGENCY, cannot be lowered
        mvc.perform(post("/api/v1/cases/" + caseId + "/triage").with(NURSE).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"protocolCode\":\"MSK\",\"mode\":\"IN_PERSON\",\"answers\":{\"q1\":true},\"overrideUrgency\":\"ROUTINE\",\"overrideReason\":\"x\"}"))
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("red_flag_hard_stop"));
        // telephone triage without consent rejected
        mvc.perform(post("/api/v1/cases/" + caseId + "/triage").with(NURSE).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"protocolCode\":\"MSK\",\"mode\":\"TELEPHONE\",\"answers\":{\"q1\":false,\"q2\":false,\"q3\":3,\"q4\":true}}"))
                .andExpect(status().isBadRequest());
        MvcResult t = mvc.perform(post("/api/v1/cases/" + caseId + "/triage").with(NURSE).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"protocolCode":"MSK","mode":"TELEPHONE","answers":{"q1":false,"q2":false,"q3":3,"q4":true},
                                 "call":{"caller":"Rami","consentToRemoteAssessment":true,"callBackAt":"%s"}}""".formatted(Instant.now().plus(2, ChronoUnit.HOURS))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.computedUrgency").value("ROUTINE"))
                .andExpect(jsonPath("$.finalUrgency").value("SEMI_URGENT")) // safety-critical floor
                .andExpect(jsonPath("$.callBackTaskId").exists())
                .andReturn();
        assertThat(body(t).get("protocolVersion").asInt()).isEqualTo(1);
        // closing a safety-critical case requires fitness decision and driving checklist
        mvc.perform(post("/api/v1/cases/" + caseId + "/transition").with(NURSE).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"CLOSED\"}"))
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("fitness_required"));
        mvc.perform(post("/api/v1/cases/" + caseId + "/fitness").with(PHYSICIAN).contentType(MediaType.APPLICATION_JSON)
                .content("{\"fitnessStatus\":\"FIT\"}")).andExpect(status().isOk());
        mvc.perform(post("/api/v1/cases/" + caseId + "/transition").with(NURSE).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"CLOSED\"}"))
                .andExpect(jsonPath("$.code").value("checklist_required"));
        mvc.perform(post("/api/v1/cases/" + caseId + "/checklists/DRIVING").with(PHYSICIAN).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"answers\":{\"VISION\":false,\"SEIZURE_HISTORY\":false,\"SEDATING_MEDICATION\":false,\"FITNESS_TO_DRIVE\":\"CLEAR\"},\"outcome\":\"CLEAR\"}"))
                .andExpect(status().isOk());
        mvc.perform(post("/api/v1/cases/" + caseId + "/transition").with(NURSE).contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"CLOSED\"}")).andExpect(status().isOk());
    }

    @Test
    void queuesAssignmentEscalationComplexityAndCrewStatusOnly() throws Exception {
        String caseId = openInjuryCase("E1005");
        mvc.perform(get("/api/v1/queues/counts").with(NURSE)).andExpect(jsonPath("$.UNASSIGNED").exists());
        MvcResult page = mvc.perform(get("/api/v1/queues/TEAM").param("size", "200").with(HEAD)).andExpect(status().isOk()).andReturn();
        assertThat(page.getResponse().getContentAsString()).contains(caseId);
        mvc.perform(post("/api/v1/cases/" + caseId + "/assign").with(HEAD).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"toUsername\":\"nurse2\",\"reason\":\"rebalance\"}"))
                .andExpect(jsonPath("$.toUsername").value("nurse2"));
        mvc.perform(post("/api/v1/cases/" + caseId + "/assign").with(NURSE).contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"take\"}"))
                .andExpect(jsonPath("$.toUsername").value("nurse1"));
        mvc.perform(get("/api/v1/cases/" + caseId + "/assignments").with(NURSE)).andExpect(jsonPath("$.length()").value(org.hamcrest.Matchers.greaterThanOrEqualTo(2)));
        // waiting state pauses SLA
        mvc.perform(post("/api/v1/cases/" + caseId + "/waiting-state").with(NURSE).contentType(MediaType.APPLICATION_JSON)
                .content("{\"waitingState\":\"AWAITING_EMPLOYEE\"}")).andExpect(status().isOk());
        mvc.perform(get("/api/v1/cases/" + caseId + "/header").with(NURSE)).andExpect(jsonPath("$.sla.state").value("PAUSED"));
        // escalation to physician with response SLA, decision by physician only
        MvcResult esc = mvc.perform(post("/api/v1/cases/" + caseId + "/escalations").with(NURSE).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"level\":\"PHYSICIAN\",\"reasonCode\":\"SECOND_OPINION\",\"note\":\"please review\"}"))
                .andExpect(status().isCreated()).andReturn();
        String escId = body(esc).get("id").asString();
        mvc.perform(post("/api/v1/escalations/" + escId + "/decision").with(NURSE).contentType(MediaType.APPLICATION_JSON)
                .content("{\"decision\":\"x\"}")).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/escalations/" + escId + "/decision").with(PHYSICIAN).contentType(MediaType.APPLICATION_JSON)
                .content("{\"decision\":\"Agree with plan\"}")).andExpect(jsonPath("$.status").value("DECIDED"));
        // complexity with drivers
        mvc.perform(put("/api/v1/cases/" + caseId + "/risk-flags").with(NURSE).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"comorbidity\":true,\"psychosocialScreenPositive\":true,\"claimOrLitigation\":true}"))
                .andExpect(jsonPath("$.score").value(org.hamcrest.Matchers.greaterThanOrEqualTo(55)))
                .andExpect(jsonPath("$.drivers[0].label").exists());
        // crew scheduling: status only, audited
        String crewCase = openInjuryCase("E1003");
        mvc.perform(post("/api/v1/cases/" + crewCase + "/fitness").with(user("ame1", "A1", null, "AME")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"fitnessStatus\":\"TEMPORARILY_UNFIT\",\"restrictionUntil\":\"" + LocalDate.now().plusDays(5) + "\"}"))
                .andExpect(status().isOk());
        MvcResult crew = mvc.perform(get("/api/v1/status/crew").param("employeeIds", "E1003").with(user("sched", null, null, "CREW_SCHEDULING")))
                .andExpect(jsonPath("$[0].fitnessStatus").value("UNFIT")).andReturn();
        assertThat(crew.getResponse().getContentAsString()).doesNotContain("INJURY").doesNotContain("summary");
        mvc.perform(get("/api/v1/status/crew").param("employeeIds", "E1003").with(NURSE)).andExpect(status().isForbidden());
    }

    @Test
    void absenceTriggerAndFitnessDeclarationAndDuplicateDetection() throws Exception {
        String emp = "E1002";
        String day = LocalDate.now().minusDays(6).toString();
        mvc.perform(post("/api/v1/intake/absence-episodes").with(INTEGRATION).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sourceRef\":\"ABS-" + UUID.randomUUID() + "\",\"employeeId\":\"" + emp + "\",\"startDate\":\"" + day + "\",\"reasonCategory\":\"SICKNESS\"}"))
                .andExpect(jsonPath("$.outcome").value(org.hamcrest.Matchers.oneOf("CASE_CREATED", "CASE_EXISTS")));
        mvc.perform(post("/api/v1/intake/triggers/fitness-declaration").with(user("pilot1", "E1002", null, "EMPLOYEE"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"MEDICATION\",\"unfitFrom\":\"" + LocalDate.now() + "\"}"))
                .andExpect(jsonPath("$.caseRef").value(org.hamcrest.Matchers.startsWith("FLT-")));
        String rostering = jdbc.queryForObject("""
                select count(*)::text from platform.outbox_event where destination = 'crew-rostering' and created_at > now() - interval '1 minute'""",
                String.class);
        assertThat(Integer.parseInt(rostering)).isPositive();
        // duplicate: two reports of the same event
        String a = openInjuryCase("E1005");
        String b = openInjuryCase("E1005");
        relay.relay();
        mvc.perform(get("/api/v1/cases/" + b + "/duplicates").with(NURSE))
                .andExpect(jsonPath("$[0].reasons").value(org.hamcrest.Matchers.hasItem("SAME_BODY_PART")));
        mvc.perform(post("/api/v1/cases/" + b + "/merge").with(NURSE).contentType(MediaType.APPLICATION_JSON)
                .content("{\"intoCaseId\":\"" + a + "\",\"reason\":\"same event\"}")).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("select status from casemgmt.oh_case where id = ?::uuid", String.class, b)).isEqualTo("MERGED");
    }

    private String openInjuryCase(String employeeId) throws Exception {
        String ref = "INC-" + UUID.randomUUID().toString().substring(0, 8);
        mvc.perform(post("/api/v1/intake/ehs/incidents").with(INTEGRATION).header("Idempotency-Key", "k-" + ref)
                .contentType(MediaType.APPLICATION_JSON).content(incident(ref, employeeId, "LOST_TIME_INJURY"))).andExpect(status().isAccepted());
        relay.relay();
        relay.relay();
        return jdbc.queryForObject("select id::text from casemgmt.oh_case where incident_ref = ?", String.class, ref);
    }
}
