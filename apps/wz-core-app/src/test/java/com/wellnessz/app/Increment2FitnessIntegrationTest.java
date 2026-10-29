package com.wellnessz.app;

import static com.wellnessz.app.PlatformIntegrationTest.user;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

import com.wellnessz.casekernel.support.ChartContributor;
import com.wellnessz.e05.FitnessChartContributor;
import com.wellnessz.e05.FitnessJobs;
import com.wellnessz.integration.outbox.OutboxRelay;
import com.wellnessz.integration.outbox.OutboxRepository;
import com.wellnessz.security.identity.CurrentUser;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Acceptance tests for E05 – fitness for duty, restrictions and return to work (one or more per feature F01–F07) against the
 * real endpoints, including status-only / no-clinical-content assertions for managers, HR, hygiene and crew scheduling.
 */
class Increment2FitnessIntegrationTest extends IntegrationTestBase {

    @Autowired
    MockMvc mvc;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    OutboxRelay relay;
    @Autowired
    JsonMapper json;
    @Autowired
    FitnessJobs jobs;
    @Autowired
    FitnessChartContributor chart;
    @Autowired
    OutboxRepository outbox;

    static final JwtRequestPostProcessor NURSE = user("nurse1", "N1", null, "OH_CLINICIAN");
    static final JwtRequestPostProcessor PHYSICIAN = user("physician1", "PH1", null, "OH_PHYSICIAN");
    static final JwtRequestPostProcessor AME = user("ame1", "A1", null, "AME");
    static final JwtRequestPostProcessor CMO = user("cmo1", "CM1", null, "CMO");
    static final JwtRequestPostProcessor CMO2 = user("cmo2", "CM2", null, "CMO");
    static final JwtRequestPostProcessor HEAD = user("nursinghead1", "NH1", null, "NURSING_HEAD", "OH_CLINICIAN");
    static final JwtRequestPostProcessor MANAGER = user("manager1", "M2001", "/AIRLINE/CATERING", "MANAGER");
    static final JwtRequestPostProcessor OTHER_MANAGER = user("manager9", "M9999", "/AIRLINE/CARGO", "MANAGER");
    static final JwtRequestPostProcessor HR = user("hr1", "H1", "/AIRLINE/CATERING", "HR");
    static final JwtRequestPostProcessor HR_CARGO = user("hr9", "H9", "/AIRLINE/CARGO", "HR");
    static final JwtRequestPostProcessor HYGIENE = user("hygiene1", null, null, "HYGIENE_MANAGER");
    static final JwtRequestPostProcessor SCHED = user("sched", null, null, "CREW_SCHEDULING");
    static final JwtRequestPostProcessor INTEGRATION = user("rostering-system", null, null, "INTEGRATION");
    static final JwtRequestPostProcessor ERIN = user("employee1", "E1001", "/AIRLINE/CATERING", "EMPLOYEE");

    static final String SECRET = "Rationale-zq81 tendon repair";
    static final LocalDate TODAY = LocalDate.now(ZoneOffset.UTC);

    JsonNode body(MvcResult r) throws Exception {
        return json.readTree(r.getResponse().getContentAsString());
    }

    String text(MvcResult r) throws Exception {
        return r.getResponse().getContentAsString();
    }

    ResultActions postJson(String url, JwtRequestPostProcessor who, String content) throws Exception {
        return mvc.perform(post(url).with(who).contentType(MediaType.APPLICATION_JSON).content(content));
    }

    String openInjuryCase(String employeeId) throws Exception {
        String ref = "INC-" + UUID.randomUUID().toString().substring(0, 8);
        String incident = """
                {"externalRef":"%s","occurredAt":"%s","incidentClass":"LOST_TIME_INJURY","severity":"MEDIUM","bodyPart":"HAND",
                 "location":"LHR-T5","unitCode":"CAT","shift":"EARLY","employeeId":"%s","orgUnitCode":"CATERING",
                 "description":"Hand injury at work","reporterEmail":"hse.officer@wellnessz.local"}"""
                .formatted(ref, Instant.now().minus(1, ChronoUnit.HOURS), employeeId);
        mvc.perform(post("/api/v1/intake/ehs/incidents").with(user("ehs-system", null, null, "INTEGRATION"))
                .header("Idempotency-Key", "k-" + ref).contentType(MediaType.APPLICATION_JSON).content(incident))
                .andExpect(status().isAccepted());
        relay.relay();
        relay.relay();
        return jdbc.queryForObject("select id::text from casemgmt.oh_case where incident_ref = ?", String.class, ref);
    }

    String decide(String caseId, JwtRequestPostProcessor who, String request) throws Exception {
        MvcResult r = postJson("/api/v1/cases/" + caseId + "/fitness-decisions", who, request).andExpect(status().isCreated()).andReturn();
        return body(r).get("id").asString();
    }

    long notifications(String address, String template, String contains) {
        return jdbc.queryForObject("select count(*) from platform.notification where address = ? and template_key = ? and body like ?",
                Long.class, address, template, "%" + contains + "%");
    }

    /** Latest outbox payload (decrypted through the entity converter – payloads are encrypted at rest). */
    String outboxPayload(String destination, String eventType, String aggregateId) {
        String raw = jdbc.queryForObject("""
                select payload from platform.outbox_event where destination = ? and event_type = ? and aggregate_id = ?
                 order by created_at desc limit 1""", String.class, destination, eventType, aggregateId);
        assertThat(raw).startsWith("v1:");
        return outbox.findAllByOrderByCreatedAtDesc(PageRequest.of(0, 500)).stream()
                .filter(e -> e.getDestination().equals(destination) && e.getEventType().equals(eventType) && e.getAggregateId().equals(aggregateId))
                .findFirst().orElseThrow().getPayload();
    }

    String caseRef(String caseId) {
        return jdbc.queryForObject("select case_ref from casemgmt.oh_case where id = ?::uuid", String.class, caseId);
    }

    // =================================================================== F01 fitness decisions

    @Test
    void f01FitnessDecisionsSignoffCertificateAndDurationComparison() throws Exception {
        String caseId = openInjuryCase("E1004");
        // licensed standard: an OH nurse (and a non-AME physician) cannot decide
        postJson("/api/v1/cases/" + caseId + "/fitness-decisions", NURSE, "{\"standardCode\":\"CAA_CLASS1\",\"outcome\":\"FIT\"}")
                .andExpect(status().isForbidden());
        postJson("/api/v1/cases/" + caseId + "/fitness-decisions", PHYSICIAN, "{\"standardCode\":\"CAA_CLASS1\",\"outcome\":\"FIT\"}")
                .andExpect(status().isForbidden());
        // FIT_WITH_LIMITATIONS needs a restriction or limitation
        postJson("/api/v1/cases/" + caseId + "/fitness-decisions", NURSE, "{\"standardCode\":\"AIRSIDE_DRIVING\",\"outcome\":\"FIT_WITH_LIMITATIONS\"}")
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("limitation_required"));
        postJson("/api/v1/cases/" + caseId + "/fitness-decisions", NURSE,
                "{\"standardCode\":\"AIRSIDE_DRIVING\",\"outcome\":\"FIT_WITH_LIMITATIONS\",\"restrictions\":[{\"code\":\"LIFT_MAX_KG\"}]}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("restriction_value_required"));

        MvcResult r = postJson("/api/v1/cases/" + caseId + "/fitness-decisions", NURSE, """
                {"standardCode":"AIRSIDE_DRIVING","outcome":"FIT_WITH_LIMITATIONS",
                 "restrictions":[{"code":"LIFT_MAX_KG","value":10},{"code":"NO_NIGHT_DUTY"}],"rationale":"%s"}""".formatted(SECRET))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("FINAL"))
                .andExpect(jsonPath("$.standardVersion").value(1))
                .andExpect(jsonPath("$.kernelStatus").value("FIT_WITH_RESTRICTIONS"))
                .andExpect(jsonPath("$.validFrom").value(TODAY.toString()))
                .andExpect(jsonPath("$.validUntil").value(TODAY.plusMonths(36).minusDays(1).toString()))
                .andExpect(jsonPath("$.restrictions[0].label").value("No lifting above 10 kg"))
                .andExpect(jsonPath("$.certificateDocumentId").isNotEmpty())
                .andReturn();
        String decisionId = body(r).get("id").asString();
        String certificate = body(r).get("certificateDocumentId").asString();
        assertThat(jdbc.queryForObject("select fitness_status from casemgmt.oh_case where id = ?::uuid", String.class, caseId))
                .isEqualTo("FIT_WITH_RESTRICTIONS");
        assertThat(jdbc.queryForObject("select type || '/' || status from docs.document where id = ?::uuid", String.class, certificate))
                .isEqualTo("FITNESS_CERTIFICATE/DRAFT");
        assertThat(jdbc.queryForObject("select count(*) from fitness.restriction_instance where decision_id = ?::uuid and status = 'ACTIVE'",
                Long.class, decisionId)).isEqualTo(2);
        // rationale is encrypted at rest
        assertThat(jdbc.queryForObject("select rationale from fitness.fitness_decision where id = ?::uuid", String.class, decisionId))
                .doesNotContain("tendon");

        // permanent unfitness awaits CMO sign-off (kernel UNDER_REVIEW until approved)
        String pu = decide(caseId, PHYSICIAN, "{\"standardCode\":\"GENERAL_DUTIES\",\"outcome\":\"PERMANENTLY_UNFIT\",\"rationale\":\"x\"}");
        assertThat(jdbc.queryForObject("select fitness_status from casemgmt.oh_case where id = ?::uuid", String.class, caseId))
                .isEqualTo("UNDER_REVIEW");
        assertThat(text(mvc.perform(get("/api/v1/fitness-decisions/awaiting-signoff").with(CMO)).andExpect(status().isOk()).andReturn()))
                .contains(pu);
        assertThat(text(mvc.perform(get("/api/v1/fitness-decisions/awaiting-signoff").with(AME)).andReturn())).doesNotContain(pu);
        assertThat(notifications("cmo1@wellnessz.local", "fitness.signoff-required", caseRef(caseId))).isPositive();
        postJson("/api/v1/fitness-decisions/" + pu + "/signoff", PHYSICIAN, "{\"approve\":true}").andExpect(status().isForbidden());
        postJson("/api/v1/fitness-decisions/" + pu + "/signoff", AME, "{\"approve\":true}").andExpect(status().isForbidden());
        postJson("/api/v1/fitness-decisions/" + pu + "/signoff", CMO, "{\"approve\":true,\"note\":\"agreed\"}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("FINAL"))
                .andExpect(jsonPath("$.kernelStatus").value("PERMANENTLY_UNFIT")).andExpect(jsonPath("$.signoffBy").value("cmo1"));
        assertThat(jdbc.queryForObject("select fitness_status from casemgmt.oh_case where id = ?::uuid", String.class, caseId))
                .isEqualTo("PERMANENTLY_UNFIT");

        mvc.perform(get("/api/v1/cases/" + caseId + "/fitness-decisions").with(NURSE))
                .andExpect(jsonPath("$.length()").value(2)).andExpect(jsonPath("$[1].rationale").value(SECRET));
        mvc.perform(get("/api/v1/employees/E1004/fitness").with(NURSE))
                .andExpect(jsonPath("$[*].standardCode", Matchers.hasItems("AIRSIDE_DRIVING", "GENERAL_DUTIES")));
        mvc.perform(get("/api/v1/employees/E1004/fitness").with(MANAGER)).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/cases/" + caseId + "/fitness-decisions").with(HR)).andExpect(status().isForbidden());

        // duration comparison (E05-BL-020) and override reason
        jdbc.update("update casemgmt.oh_case set primary_diagnosis_system = 'ICD_10', primary_diagnosis_code = 'S93.4', occurred_on = ? where id = ?::uuid",
                TODAY.minusDays(30), UUID.fromString(caseId));
        mvc.perform(get("/api/v1/cases/" + caseId + "/duration-comparison").with(NURSE))
                .andExpect(jsonPath("$.icdCode").value("S93.4")).andExpect(jsonPath("$.expectedDays").value(14))
                .andExpect(jsonPath("$.actualDays").value(30)).andExpect(jsonPath("$.varianceDays").value(16))
                .andExpect(jsonPath("$.exceeded").value(true));
        String unfit = "{\"standardCode\":\"GENERAL_DUTIES\",\"outcome\":\"TEMPORARILY_UNFIT\",\"validUntil\":\"" + TODAY.plusDays(7) + "\"%s}";
        postJson("/api/v1/cases/" + caseId + "/fitness-decisions", PHYSICIAN, unfit.formatted(""))
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("duration_override_required"));
        decide(caseId, PHYSICIAN, unfit.formatted(",\"durationOverrideReason\":\"Awaiting specialist review\""));
        String other = openInjuryCase("E1004");
        mvc.perform(get("/api/v1/cases/" + other + "/duration-comparison").with(NURSE)).andExpect(jsonPath("$.expectedDays").doesNotExist());
    }

    // =================================================================== F02 matching & modified duties

    @Test
    void f02MatchingProposalsManagerDecisionAndEscalationToHr() throws Exception {
        String caseId = openInjuryCase("E1001");
        decide(caseId, NURSE, """
                {"standardCode":"GENERAL_DUTIES","outcome":"FIT_WITH_LIMITATIONS","validUntil":"%s",
                 "restrictions":[{"code":"LIFT_MAX_KG","value":10},{"code":"NO_KNIFE_WORK"}],"rationale":"%s"}"""
                .formatted(TODAY.plusDays(40), SECRET));
        MvcResult m = postJson("/api/v1/fitness/match", NURSE,
                "{\"employeeId\":\"E1001\",\"unit\":\"CAT\",\"restrictions\":[{\"code\":\"LIFT_MAX_KG\",\"value\":10},{\"code\":\"NO_KNIFE_WORK\"}]}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].compatible").value(true)).andExpect(jsonPath("$[0].sameDepartment").value(true))
                .andExpect(jsonPath("$[0].jobCode").value(Matchers.oneOf("CATERING_PACKING", "CATERING_STORES_ADMIN")))
                .andReturn();
        JsonNode last = body(m).get(body(m).size() - 1);
        assertThat(last.get("compatible").asBoolean()).isFalse();
        assertThat(last.get("conflicts").get(0).get("dimension").asString()).isIn("LIFT_KG", "KNIFE");
        postJson("/api/v1/fitness/match", MANAGER, "{\"employeeId\":\"E1001\",\"restrictions\":[{\"code\":\"NO_KNIFE_WORK\"}]}")
                .andExpect(status().isForbidden());

        String proposal = "{\"jobCode\":\"%s\",\"duties\":\"%s\",\"hoursPerDay\":6,\"startDate\":\"" + TODAY + "\"}";
        postJson("/api/v1/cases/" + caseId + "/duty-proposals", NURSE, proposal.formatted("CATERING_PACKING", "Light work after the fracture"))
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("clinical_content_not_allowed"))
                .andExpect(jsonPath("$.field").value("duties"));
        MvcResult p1 = postJson("/api/v1/cases/" + caseId + "/duty-proposals", NURSE,
                proposal.formatted("CATERING_PACKING", "Seated tray-set packing, no knives")).andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PROPOSED")).andExpect(jsonPath("$.restrictions.length()").value(2)).andReturn();
        MvcResult p2 = postJson("/api/v1/cases/" + caseId + "/duty-proposals", NURSE,
                proposal.formatted("CATERING_STORES_ADMIN", "Stores administration")).andExpect(status().isCreated()).andReturn();
        String id1 = body(p1).get("id").asString();
        String id2 = body(p2).get("id").asString();
        assertThat(notifications("manager1@wellnessz.local", "duty.proposal", caseRef(caseId))).isPositive();

        // manager: own scope only, functional content only (audited)
        String ws = text(mvc.perform(get("/api/v1/workspace/duty-proposals").with(MANAGER)).andExpect(status().isOk()).andReturn());
        assertThat(ws).contains(id1, id2, "No lifting above 10 kg").doesNotContain("tendon").doesNotContain("rationale");
        assertThat(text(mvc.perform(get("/api/v1/workspace/duty-proposals").with(OTHER_MANAGER)).andReturn())).doesNotContain(id1);
        postJson("/api/v1/duty-proposals/" + id1 + "/decision", OTHER_MANAGER, "{\"accept\":true}").andExpect(status().isNotFound());
        postJson("/api/v1/duty-proposals/" + id1 + "/decision", MANAGER, "{\"accept\":false}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("reason_required"));
        postJson("/api/v1/duty-proposals/" + id1 + "/decision", HR, "{\"accept\":true}").andExpect(status().isForbidden());
        postJson("/api/v1/duty-proposals/" + id1 + "/decision", MANAGER, "{\"accept\":false,\"reasonCode\":\"OPERATIONAL\"}")
                .andExpect(jsonPath("$.status").value("DECLINED"));
        postJson("/api/v1/duty-proposals/" + id2 + "/decision", MANAGER, "{\"accept\":false,\"reasonCode\":\"NO_SUITABLE_WORK\",\"note\":\"No vacancy\"}")
                .andExpect(jsonPath("$.status").value("ESCALATED_TO_HR"));
        assertThat(jdbc.queryForObject("select count(*) from casemgmt.case_task where case_id = ?::uuid and assignee = 'HR' and status = 'OPEN'",
                Long.class, caseId)).isEqualTo(1);
        assertThat(text(mvc.perform(get("/api/v1/workspace/duty-proposals").param("status", "ESCALATED_TO_HR").with(HR)).andReturn()))
                .contains(id1, id2);
        mvc.perform(get("/api/v1/cases/" + caseId + "/duty-proposals").with(NURSE))
                .andExpect(jsonPath("$[*].status", Matchers.everyItem(Matchers.is("ESCALATED_TO_HR"))));
        assertThat(jdbc.queryForObject("select count(*) from platform.audit_event where action = 'DUTY_PROPOSALS_WORKSPACE_READ' and actor_roles like '%MANAGER%'",
                Long.class)).isPositive();
    }

    // =================================================================== F03 RTW plans & job offers

    @Test
    void f03RtwPlanAcknowledgementsReviewsAndJobOffer() throws Exception {
        String caseId = openInjuryCase("E1001");
        String plan = """
                {"startDate":"%s","goal":"%s","reviewDates":["%s","%s"],
                 "phases":[{"fromWeek":1,"toWeek":2,"hoursPerDay":4,"duties":"Seated tray packing","restrictions":[{"code":"LIFT_MAX_KG","value":10}]},
                           {"fromWeek":3,"toWeek":4,"hoursPerDay":6,"duties":"Packing and stores support"}]}""";
        postJson("/api/v1/cases/" + caseId + "/rtw-plans", NURSE, plan.formatted(TODAY, "Recover from depression", TODAY.plusDays(7), TODAY.plusDays(14)))
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.field").value("goal"));
        MvcResult created = postJson("/api/v1/cases/" + caseId + "/rtw-plans", NURSE,
                plan.formatted(TODAY, "Back to catering associate duties", TODAY.plusDays(7), TODAY.plusDays(14)))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.reviews.length()").value(2))
                .andExpect(jsonPath("$.phases[0].restrictions[0].label").value("No lifting above 10 kg")).andReturn();
        String planId = body(created).get("id").asString();
        mvc.perform(put("/api/v1/rtw-plans/" + planId).with(NURSE).contentType(MediaType.APPLICATION_JSON)
                        .content(plan.formatted(TODAY, "Full catering associate duties by week 5", TODAY.plusDays(7), TODAY.plusDays(14))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.goal").value("Full catering associate duties by week 5"));
        mvc.perform(post("/api/v1/rtw-plans/" + planId + "/activate").with(NURSE))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ACTIVE")).andExpect(jsonPath("$.documentId").isNotEmpty())
                .andExpect(jsonPath("$.nextReviewDate").value(TODAY.plusDays(7).toString()));
        mvc.perform(put("/api/v1/rtw-plans/" + planId).with(NURSE).contentType(MediaType.APPLICATION_JSON)
                .content(plan.formatted(TODAY, "Changed", TODAY.plusDays(7), TODAY.plusDays(14)))).andExpect(status().isConflict());
        String doc = jdbc.queryForObject("select document_id::text from fitness.rtw_plan where id = ?::uuid", String.class, planId);
        assertThat(jdbc.queryForObject("select status from docs.document where id = ?::uuid", String.class, doc)).isEqualTo("SIGNED");
        assertThat(jdbc.queryForList("select target from docs.document_share where document_id = ?::uuid", String.class, UUID.fromString(doc)))
                .contains("EMPLOYEE", "MANAGER");
        assertThat(notifications("employee1@wellnessz.local", "rtw.plan-ack", caseRef(caseId))).isPositive();
        assertThat(notifications("manager1@wellnessz.local", "rtw.plan-ack", caseRef(caseId))).isPositive();

        // employee agreement and manager acknowledgement
        assertThat(text(mvc.perform(get("/api/v1/me/rtw-plans").with(ERIN)).andExpect(status().isOk()).andReturn())).contains(planId);
        postJson("/api/v1/me/rtw-plans/" + planId + "/acknowledge", user("x", "E1004", null, "EMPLOYEE"), "{\"agree\":true}")
                .andExpect(status().isNotFound());
        postJson("/api/v1/me/rtw-plans/" + planId + "/acknowledge", ERIN, "{\"agree\":true,\"comment\":\"my back still hurts a bit\"}")
                .andExpect(jsonPath("$.employeeAck.agree").value(true));
        String ws = text(mvc.perform(get("/api/v1/workspace/rtw-plans").with(MANAGER)).andExpect(status().isOk()).andReturn());
        assertThat(ws).contains(planId).doesNotContain("back still hurts");
        postJson("/api/v1/rtw-plans/" + planId + "/manager-acknowledge", OTHER_MANAGER, "{}").andExpect(status().isNotFound());
        postJson("/api/v1/rtw-plans/" + planId + "/manager-acknowledge", MANAGER, "{\"comment\":\"Rota adjusted\"}")
                .andExpect(jsonPath("$.managerAck.by").value("manager1"));

        // overdue review → list and escalation to the Nursing Head after 2 days
        String case2 = openInjuryCase("E1001");
        MvcResult old = postJson("/api/v1/cases/" + case2 + "/rtw-plans", NURSE,
                plan.formatted(TODAY.minusDays(10), "Gradual return", TODAY.minusDays(5), TODAY.plusDays(5))).andReturn();
        String plan2 = body(old).get("id").asString();
        mvc.perform(post("/api/v1/rtw-plans/" + plan2 + "/activate").with(NURSE)).andExpect(status().isOk());
        MvcResult overdue = mvc.perform(get("/api/v1/rtw/reviews/overdue").with(HEAD)).andExpect(status().isOk()).andReturn();
        JsonNode row = null;
        for (JsonNode n : body(overdue)) {
            if (plan2.equals(n.get("planId").asString())) {
                row = n;
            }
        }
        assertThat(row).isNotNull();
        assertThat(row.get("daysOverdue").asLong()).isEqualTo(5);
        jobs.run(TODAY);
        assertThat(jdbc.queryForObject("select count(*) from fitness.rtw_review where plan_id = ?::uuid and escalated_at is not null",
                Long.class, plan2)).isEqualTo(1);
        assertThat(notifications("nursinghead1@wellnessz.local", "rtw.review-due", caseRef(case2))).isPositive();
        postJson("/api/v1/rtw-plans/" + plan2 + "/reviews/" + row.get("reviewId").asString() + "/complete", NURSE,
                "{\"outcome\":\"ON_TRACK\",\"note\":\"Hours increased as planned\"}")
                .andExpect(jsonPath("$.reviews[0].outcome").value("ON_TRACK"));

        // electronic alternative-duty job offer (HR) with employee e-acceptance and HRIS note
        String offer = """
                {"jobCode":"CATERING_PACKING","jobTitle":"Tray-set packer","duties":"%s","location":"LHR catering unit","shift":"Early",
                 "hoursPerWeek":30,"payBasis":"PROTECTED_PAY","payNote":"Current grade protected","restrictions":[{"code":"LIFT_MAX_KG","value":10}],
                 "startDate":"%s","contactName":"Hana HR","contactEmail":"hr.partner@wellnessz.local","respondBy":"%s"}""";
        postJson("/api/v1/cases/" + caseId + "/job-offers", NURSE, offer.formatted("Packing", TODAY, TODAY.plusDays(5))).andExpect(status().isForbidden());
        postJson("/api/v1/cases/" + caseId + "/job-offers", HR_CARGO, offer.formatted("Packing", TODAY, TODAY.plusDays(5)))
                .andExpect(status().isNotFound());
        postJson("/api/v1/cases/" + caseId + "/job-offers", HR, offer.formatted("Packing while on medication", TODAY, TODAY.plusDays(5)))
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.field").value("duties"));
        MvcResult issued = postJson("/api/v1/cases/" + caseId + "/job-offers", HR, offer.formatted("Seated packing of tray sets", TODAY, TODAY.plusDays(5)))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("ISSUED")).andExpect(jsonPath("$.documentId").isNotEmpty())
                .andReturn();
        String offerId = body(issued).get("id").asString();
        assertThat(notifications("employee1@wellnessz.local", "job-offer.issued", TODAY.plusDays(5).toString())).isPositive();
        assertThat(text(mvc.perform(get("/api/v1/me/job-offers").with(ERIN)).andReturn())).contains(offerId);
        mvc.perform(get("/api/v1/cases/" + caseId + "/job-offers").with(HR)).andExpect(jsonPath("$[0].payBasis").value("PROTECTED_PAY"));
        postJson("/api/v1/me/job-offers/" + offerId + "/respond", ERIN, "{\"accept\":true,\"comment\":\"Happy to start\"}")
                .andExpect(jsonPath("$.status").value("ACCEPTED"));
        postJson("/api/v1/me/job-offers/" + offerId + "/respond", ERIN, "{\"accept\":false}").andExpect(status().isConflict());
        String payload = outboxPayload("hr-systems", "fitness.job-offer.responded.v1", offerId);
        JsonNode p = json.readTree(payload);
        assertThat(p.get("accepted").asBoolean()).isTrue();
        assertThat(p.get("employeeId").asString()).isEqualTo("E1001");
        assertThat(payload).doesNotContain("Happy").doesNotContain("Tray");
        MvcResult second = postJson("/api/v1/cases/" + caseId + "/job-offers", HR, offer.formatted("Stores support", TODAY, TODAY.plusDays(5)))
                .andReturn();
        mvc.perform(post("/api/v1/job-offers/" + body(second).get("id").asString() + "/withdraw").with(HR))
                .andExpect(jsonPath("$.status").value("WITHDRAWN"));
    }

    // =================================================================== F04 crew fitness & roster feed

    @Test
    void f04CrewDeclarationAssessmentRosterFeedReconciliationAndDashboard() throws Exception {
        LocalDate unfitFrom = TODAY.minusDays(ThreadLocalRandom.current().nextInt(30, 3000));
        MvcResult decl = postJson("/api/v1/intake/triggers/fitness-declaration", user("pilot1", "E1002", null, "EMPLOYEE"),
                "{\"reason\":\"FATIGUE\",\"unfitFrom\":\"" + unfitFrom + "\"}")
                .andExpect(jsonPath("$.caseRef").value(Matchers.startsWith("FLT-")))
                .andExpect(jsonPath("$.nextSteps.length()").value(4))
                .andExpect(jsonPath("$.ameContact").isNotEmpty())
                .andExpect(jsonPath("$.acknowledgementDueBy").isNotEmpty()).andReturn();
        String caseId = body(decl).get("caseId").asString();
        Instant due = Instant.parse(body(decl).get("acknowledgementDueBy").asString());
        assertThat(due).isBetween(Instant.now().plus(23, ChronoUnit.HOURS), Instant.now().plus(25, ChronoUnit.HOURS));
        assertThat(jdbc.queryForObject("select count(*) from fitness.roster_feed_message where case_id = ?::uuid and roster_status = 'PENDING'",
                Long.class, caseId)).isEqualTo(1);

        // crew assessment by AME only, with limitation codes and the CAA-layout report
        String assessment = """
                {"standardCode":"CAA_CLASS1","outcome":"FIT_WITH_LIMITATIONS","limitationCodes":["OML","VDL"],"examinationDate":"%s",
                 "rationale":"%s"}""".formatted(TODAY, SECRET);
        postJson("/api/v1/cases/" + caseId + "/crew-assessment", NURSE, assessment).andExpect(status().isForbidden());
        postJson("/api/v1/cases/" + caseId + "/crew-assessment", PHYSICIAN, assessment).andExpect(status().isForbidden());
        postJson("/api/v1/cases/" + caseId + "/crew-assessment", AME,
                "{\"standardCode\":\"CAA_CLASS1\",\"outcome\":\"FIT\",\"limitationCodes\":[\"XYZ\"]}")
                .andExpect(status().isBadRequest());
        MvcResult a = postJson("/api/v1/cases/" + caseId + "/crew-assessment", AME, assessment).andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("FINAL")).andExpect(jsonPath("$.crewAssessment").value(true))
                .andExpect(jsonPath("$.validUntil").value(TODAY.plusMonths(12).minusDays(1).toString()))
                .andExpect(jsonPath("$.limitationCodes", Matchers.contains("OML", "VDL")))
                .andExpect(jsonPath("$.reportDocumentId").isNotEmpty()).andReturn();
        String report = body(a).get("reportDocumentId").asString();
        assertThat(jdbc.queryForObject("select type || '/' || status from docs.document where id = ?::uuid", String.class, report))
                .isEqualTo("CREW_MEDICAL_REPORT/SIGNED");

        // rostering payload: status + dates + operational codes only (allow-listed, flat)
        String payload = outboxPayload("crew-rostering", "crew.roster-status.changed.v1", "E1002");
        JsonNode msg = json.readTree(payload);
        assertThat(msg.get("rosterStatus").asString()).isEqualTo("LIMITED");
        assertThat(msg.get("fitnessStatus").asString()).isEqualTo("FIT_WITH_RESTRICTIONS");
        assertThat(msg.get("operationalLimitations").asString()).isEqualTo("OML");
        assertThat(msg.get("validUntil").asString()).isEqualTo(TODAY.plusMonths(12).minusDays(1).toString());
        Set<String> fields = new HashSet<>(msg.propertyNames());
        assertThat(fields).isSubsetOf("messageId", "employeeId", "fitnessStatus", "rosterStatus", "restrictionUntil", "validFrom", "validUntil",
                "operationalLimitations", "effectiveFrom", "eventType", "aggregateType", "aggregateId", "occurredAt");
        assertThat(payload).doesNotContain("VDL").doesNotContain("tendon").doesNotContain("FATIGUE");
        String messageId = msg.get("messageId").asString();

        // crew scheduling: status only, enriched with roster status, dates and operational limitations
        MvcResult crew = mvc.perform(get("/api/v1/status/crew").param("employeeIds", "E1002").with(SCHED)).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].rosterStatus").value("LIMITED"))
                .andExpect(jsonPath("$[0].operationalLimitations", Matchers.contains("OML")))
                .andExpect(jsonPath("$[0].validUntil").value(TODAY.plusMonths(12).minusDays(1).toString())).andReturn();
        assertThat(text(crew)).doesNotContain("VDL").doesNotContain("tendon").doesNotContain("rationale");
        mvc.perform(get("/api/v1/crew/limitation-codes").with(SCHED)).andExpect(jsonPath("$.length()").value(15))
                .andExpect(jsonPath("$[?(@.code=='OML')].operational").value(Matchers.contains(true)))
                .andExpect(jsonPath("$[?(@.code=='VDL')].operational").value(Matchers.contains(false)))
                .andExpect(jsonPath("$[?(@.code=='OML')].appliesTo[*]").value(Matchers.hasItem("CAA_CLASS1")));

        // roster feed: read, acknowledge, reconcile
        String since = Instant.now().minus(10, ChronoUnit.MINUTES).toString();
        assertThat(text(mvc.perform(get("/api/v1/integration/roster-feed").param("since", since).with(INTEGRATION)).andExpect(status().isOk())
                .andReturn())).contains(messageId);
        assertThat(text(mvc.perform(get("/api/v1/crew/roster-feed").param("since", since).with(SCHED)).andExpect(status().isOk())
                .andReturn())).contains(messageId).doesNotContain("VDL");
        mvc.perform(post("/api/v1/integration/roster-feed/" + messageId + "/ack").with(SCHED)).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/integration/roster-feed/" + messageId + "/ack").with(INTEGRATION))
                .andExpect(status().isOk()).andExpect(jsonPath("$.ackAt").isNotEmpty());
        String stale = "STALE-" + UUID.randomUUID().toString().substring(0, 8);
        jdbc.update("""
                insert into fitness.roster_feed_message (message_id, employee_id, fitness_status, roster_status, sent_at)
                values (?, 'E1003', 'TEMPORARILY_UNFIT', 'UNFIT', now() - interval '20 minutes')""", stale);
        mvc.perform(get("/api/v1/crew/roster-feed/reconciliation").with(SCHED)).andExpect(status().isOk())
                .andExpect(jsonPath("$.sent").value(Matchers.greaterThanOrEqualTo(2)))
                .andExpect(jsonPath("$.acknowledged").value(Matchers.greaterThanOrEqualTo(1)))
                .andExpect(jsonPath("$.p95LatencySeconds").isNumber())
                .andExpect(jsonPath("$.unacknowledgedOver15Min[*].messageId").value(Matchers.hasItem(stale)));
        mvc.perform(get("/api/v1/integration/roster-feed/reconciliation").with(CMO)).andExpect(status().isForbidden()); // platform filter
        mvc.perform(get("/api/v1/crew/roster-feed/reconciliation").with(CMO)).andExpect(status().isOk());

        // CMO dashboard: aggregates only, small cells suppressed
        for (int i = 0; i < 5; i++) {
            jdbc.update("""
                    insert into fitness.roster_feed_message (message_id, employee_id, fitness_status, roster_status, sent_at, ack_at)
                    values (?, ?, 'TEMPORARILY_UNFIT', 'UNFIT', now() - interval '3 days', now() - interval '3 days')""",
                    "DASH-" + UUID.randomUUID(), "ZQX" + i + UUID.randomUUID().toString().substring(0, 4));
        }
        MvcResult dash = mvc.perform(get("/api/v1/crew/dashboard").with(CMO)).andExpect(status().isOk())
                .andExpect(jsonPath("$.minimumCellSize").value(5))
                .andExpect(jsonPath("$.unfitByBase[?(@.base=='UNKNOWN')].suppressed").value(Matchers.contains(false))).andReturn();
        assertThat(text(dash)).doesNotContain("ZQX").doesNotContain("E1002").doesNotContain("E1003");
        mvc.perform(get("/api/v1/crew/dashboard").with(SCHED)).andExpect(status().isForbidden());

        // cabin crew report for a cabin crew member
        String cabinCase = body(postJson("/api/v1/intake/triggers/fitness-declaration", user("crew1", "E1003", null, "EMPLOYEE"),
                "{\"reason\":\"ILLNESS\",\"unfitFrom\":\"" + TODAY.minusDays(ThreadLocalRandom.current().nextInt(30, 3000)) + "\"}")
                .andReturn()).get("caseId").asString();
        MvcResult cabin = postJson("/api/v1/cases/" + cabinCase + "/crew-assessment", AME,
                "{\"standardCode\":\"CABIN_CREW\",\"outcome\":\"TEMPORARILY_UNFIT\",\"validUntil\":\"" + TODAY.plusDays(10) + "\"}")
                .andExpect(status().isCreated()).andReturn();
        assertThat(jdbc.queryForObject("select type from docs.document where id = ?::uuid", String.class,
                body(cabin).get("reportDocumentId").asString())).isEqualTo("CABIN_CREW_MEDICAL_REPORT");
        mvc.perform(get("/api/v1/status/crew").param("employeeIds", "E1003").with(SCHED)).andExpect(jsonPath("$[0].rosterStatus").value("UNFIT"));
    }

    // =================================================================== F05 food handlers

    @Test
    void f05FoodHandlerExclusionHygieneStatusOnlyAndClearance() throws Exception {
        String caseId = openInjuryCase("E1001");
        String exclude = """
                {"reasonCategory":"GI_ILLNESS","expectedReturn":"%s",
                 "checklistAnswers":{"GI_SYMPTOMS":true,"SKIN_LESIONS":false,"INFECTIONS":false,"EXCLUSION_DECISION":"EXCLUDE"}}"""
                .formatted(TODAY.plusDays(3));
        postJson("/api/v1/cases/" + caseId + "/food-handler/exclude", HYGIENE, exclude).andExpect(status().isForbidden());
        postJson("/api/v1/cases/" + caseId + "/food-handler/exclude", NURSE, exclude).andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("EXCLUDED")).andExpect(jsonPath("$.reasonCategory").value("GI_ILLNESS"));
        postJson("/api/v1/cases/" + caseId + "/food-handler/exclude", NURSE, exclude).andExpect(status().isConflict());
        assertThat(jdbc.queryForObject("select fitness_status from casemgmt.oh_case where id = ?::uuid", String.class, caseId))
                .isEqualTo("TEMPORARILY_UNFIT");
        assertThat(jdbc.queryForObject("select count(*) from triage.case_checklist where case_id = ?::uuid and code = 'FOOD_HANDLER'",
                Long.class, caseId)).isEqualTo(1);
        String hygienePayload = outboxPayload("catering-hygiene", "fitness.food-handler.status.changed.v1", "E1001");
        assertThat(json.readTree(hygienePayload).get("foodHandlerStatus").asString()).isEqualTo("EXCLUDED");
        assertThat(hygienePayload).doesNotContain("GI_").doesNotContain("SYMPTOMS");

        MvcResult view = mvc.perform(get("/api/v1/fitness/food-handlers").param("department", "CATERING").with(HYGIENE))
                .andExpect(status().isOk()).andExpect(jsonPath("$[?(@.employeeId=='E1001')].status").value(Matchers.contains("EXCLUDED")))
                .andReturn();
        assertThat(text(view)).doesNotContain("GI_ILLNESS").doesNotContain("SYMPTOMS").doesNotContain("reasonCategory");
        assertThat(jdbc.queryForObject("select count(*) from platform.audit_event where action = 'HYGIENE_FOOD_HANDLERS_READ' and resource_id = 'CATERING'",
                Long.class)).isPositive();
        mvc.perform(get("/api/v1/fitness/food-handlers").with(MANAGER)).andExpect(status().isForbidden());

        // return requires clearance by a nurse with the 48-hour symptom-free criterion
        postJson("/api/v1/cases/" + caseId + "/food-handler/clear", NURSE, "{\"criteria\":{\"symptomFree48h\":false}}")
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("clearance_criteria_not_met"));
        mvc.perform(post("/api/v1/cases/" + caseId + "/food-handler/return-request").with(NURSE))
                .andExpect(jsonPath("$.status").value("RETURN_PENDING_CLEARANCE"));
        postJson("/api/v1/cases/" + caseId + "/food-handler/clear", user("cm", "CM9", null, "CASE_MANAGER"), "{\"criteria\":{\"symptomFree48h\":true}}")
                .andExpect(status().isForbidden());
        MvcResult cleared = postJson("/api/v1/cases/" + caseId + "/food-handler/clear", NURSE,
                "{\"criteria\":{\"symptomFree48h\":true},\"note\":\"Symptom free since Monday\"}")
                .andExpect(jsonPath("$.status").value("FIT_TO_HANDLE_FOOD")).andExpect(jsonPath("$.clearanceDocumentId").isNotEmpty()).andReturn();
        assertThat(jdbc.queryForObject("select type || '/' || status from docs.document where id = ?::uuid", String.class,
                body(cleared).get("clearanceDocumentId").asString())).isEqualTo("FOOD_HANDLER_CLEARANCE/SIGNED");
        assertThat(jdbc.queryForObject("select fitness_status from casemgmt.oh_case where id = ?::uuid", String.class, caseId)).isEqualTo("FIT");
        mvc.perform(get("/api/v1/fitness/food-handlers").with(HYGIENE))
                .andExpect(jsonPath("$[?(@.employeeId=='E1001')].status").value(Matchers.contains("FIT_TO_HANDLE_FOOD")));
        // a non food handler cannot be excluded
        postJson("/api/v1/cases/" + openInjuryCase("E1005") + "/food-handler/exclude", NURSE, exclude)
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("not_food_handler"));
    }

    // =================================================================== F06 restriction expiry & manager view

    @Test
    void f06RestrictionExpiryAlertsLapsedListExtendCloseAndTeamView() throws Exception {
        String c1 = openInjuryCase("E1001");
        String d1 = decide(c1, NURSE, """
                {"standardCode":"WORKING_AT_HEIGHT","outcome":"FIT_WITH_LIMITATIONS","validUntil":"%s",
                 "restrictions":[{"code":"NO_LADDER_WORK"}],"rationale":"%s"}""".formatted(TODAY.plusDays(3), SECRET));
        String c2 = openInjuryCase("E1001");
        String d2 = decide(c2, NURSE, """
                {"standardCode":"NIGHT_WORK","outcome":"FIT_WITH_LIMITATIONS","validFrom":"%s","validUntil":"%s",
                 "restrictions":[{"code":"MAX_HOURS_PER_DAY","value":6}]}""".formatted(TODAY.minusDays(10), TODAY.minusDays(1)));
        String r1 = jdbc.queryForObject("select id::text from fitness.restriction_instance where decision_id = ?::uuid", String.class, d1);
        String r2 = jdbc.queryForObject("select id::text from fitness.restriction_instance where decision_id = ?::uuid", String.class, d2);

        jobs.run(TODAY);
        assertThat(jdbc.queryForObject("select alerted_at is not null from fitness.restriction_instance where id = ?::uuid", Boolean.class, r1)).isTrue();
        assertThat(jdbc.queryForObject("select status from fitness.restriction_instance where id = ?::uuid", String.class, r2)).isEqualTo("LAPSED");
        assertThat(notifications("employee1@wellnessz.local", "restriction.expiring", caseRef(c1))).isEqualTo(1);
        assertThat(notifications("manager1@wellnessz.local", "restriction.expiring", caseRef(c1))).isEqualTo(1);
        jobs.run(TODAY); // idempotent: no second alert
        assertThat(notifications("employee1@wellnessz.local", "restriction.expiring", caseRef(c1))).isEqualTo(1);

        assertThat(text(mvc.perform(get("/api/v1/restrictions/active").param("status", "EXPIRING").with(NURSE)).andExpect(status().isOk())
                .andReturn())).contains(r1).doesNotContain(r2);
        assertThat(text(mvc.perform(get("/api/v1/restrictions/active").param("status", "LAPSED").param("unit", "CAT").with(NURSE))
                .andReturn())).contains(r2);
        mvc.perform(get("/api/v1/restrictions/active").with(MANAGER)).andExpect(status().isForbidden());

        // manager team view: functional only, audited; other scopes excluded
        String team = text(mvc.perform(get("/api/v1/workspace/team-restrictions").with(MANAGER)).andExpect(status().isOk()).andReturn());
        assertThat(team).contains("E1001", "No ladder or work at height", "Maximum 6 hours per day")
                .doesNotContain("tendon").doesNotContain("rationale").doesNotContain("diagnos").doesNotContain("S93");
        assertThat(text(mvc.perform(get("/api/v1/workspace/team-restrictions").with(OTHER_MANAGER)).andReturn())).doesNotContain("E1001");
        assertThat(text(mvc.perform(get("/api/v1/workspace/team-restrictions").with(HR)).andReturn())).contains("E1001");
        assertThat(jdbc.queryForObject("select count(*) from platform.audit_event where action = 'TEAM_RESTRICTIONS_READ' and resource_id = 'E1001'",
                Long.class)).isPositive();

        // one-click extend / close with reason
        postJson("/api/v1/restrictions/" + r2 + "/extend", NURSE, "{\"validUntil\":\"" + TODAY.plusDays(30) + "\",\"reason\":\"Awaiting MRI of the knee\"}")
                .andExpect(status().isUnprocessableContent());
        postJson("/api/v1/restrictions/" + r2 + "/extend", NURSE, "{\"validUntil\":\"" + TODAY.plusDays(30) + "\",\"reason\":\"Review booked\"}")
                .andExpect(jsonPath("$.status").value("ACTIVE")).andExpect(jsonPath("$.validUntil").value(TODAY.plusDays(30).toString()));
        postJson("/api/v1/restrictions/" + r1 + "/close", NURSE, "{\"reason\":\"No longer required\"}")
                .andExpect(jsonPath("$.status").value("CLOSED"));
        postJson("/api/v1/restrictions/" + r1 + "/close", NURSE, "{\"reason\":\"again\"}").andExpect(status().isConflict());

        mvc.perform(get("/api/v1/restrictions/dashboard").with(HEAD)).andExpect(status().isOk())
                .andExpect(jsonPath("$.byUnit[?(@.unit=='CAT')].active").value(Matchers.contains(Matchers.greaterThanOrEqualTo(1))));
        mvc.perform(get("/api/v1/restrictions/dashboard").with(MANAGER)).andExpect(status().isForbidden());

        // chart contribution (E03 SPI)
        ChartContributor.Contribution contribution = chart.contribute(new CurrentUser("s", "nurse1", "N1", "Nina", null, Set.of("OH_CLINICIAN")),
                "E1001");
        assertThat(contribution.sections()).containsKeys("fitness", "restrictions");
        assertThat((List<?>) contribution.sections().get("restrictions")).isNotEmpty();
        assertThat(chart.contribute(new CurrentUser("s", "manager1", "M2001", "Max", "/AIRLINE/CATERING", Set.of("MANAGER")), "E1001").sections())
                .isEmpty();
    }

    // =================================================================== F07 ill-health retirement / redeployment

    @Test
    void f07IllHealthRetirementCmoDecisionAppealWindowAndIndependentAppeal() throws Exception {
        String caseId = openInjuryCase("E1001");
        String request = "{\"type\":\"ILL_HEALTH_RETIREMENT\",\"findings\":\"Chronic tendon failure, findings-zq77\","
                + "\"recommendation\":\"Retire on ill-health grounds\",\"policyReference\":\"HR-POL-12\"}";
        postJson("/api/v1/cases/" + caseId + "/ihr-assessments", NURSE, request).andExpect(status().isForbidden());
        MvcResult a = postJson("/api/v1/cases/" + caseId + "/ihr-assessments", PHYSICIAN, request).andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("AWAITING_CMO")).andReturn();
        String id = body(a).get("id").asString();
        postJson("/api/v1/ihr-assessments/" + id + "/cmo-decision", PHYSICIAN, "{\"approve\":true,\"decisionText\":\"ok\"}")
                .andExpect(status().isForbidden());
        postJson("/api/v1/ihr-assessments/" + id + "/cmo-decision", CMO, "{\"approve\":true,\"decisionText\":\"Approved due to the diagnosis\"}")
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.field").value("decisionText"));
        postJson("/api/v1/ihr-assessments/" + id + "/cmo-decision", CMO,
                "{\"approve\":true,\"decisionText\":\"Retirement on ill-health grounds is supported under policy HR-POL-12.\",\"note\":\"clinical note\"}")
                .andExpect(jsonPath("$.status").value("DECIDED")).andExpect(jsonPath("$.appealDeadline").value(TODAY.plusDays(28).toString()))
                .andExpect(jsonPath("$.letterDocumentId").isNotEmpty());

        MvcResult hr = mvc.perform(get("/api/v1/cases/" + caseId + "/ihr-assessments").with(HR)).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].status").value("DECIDED")).andExpect(jsonPath("$[0].findings").doesNotExist()).andReturn();
        assertThat(text(hr)).doesNotContain("zq77").doesNotContain("clinical note").doesNotContain("Retire on ill-health grounds");
        mvc.perform(get("/api/v1/cases/" + caseId + "/ihr-assessments").with(HR_CARGO)).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/cases/" + caseId + "/ihr-assessments").with(NURSE)).andExpect(jsonPath("$[0].findings").value(Matchers.containsString("zq77")));

        postJson("/api/v1/ihr-assessments/" + id + "/appeal", HR, "{\"grounds\":\"Employee requests a second opinion\",\"secondOpinionBy\":\"External OH consultant\"}")
                .andExpect(jsonPath("$.status").value("APPEALED")).andExpect(jsonPath("$.appealDueBy").value(TODAY.plusDays(30).toString()));
        postJson("/api/v1/ihr-assessments/" + id + "/appeal-decision", CMO, "{\"upheld\":false,\"decisionText\":\"Original decision confirmed\"}")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("independent_cmo_required"));
        postJson("/api/v1/ihr-assessments/" + id + "/appeal-decision", CMO2, "{\"upheld\":false,\"decisionText\":\"Original decision confirmed\"}")
                .andExpect(jsonPath("$.status").value("APPEAL_DECIDED")).andExpect(jsonPath("$.appealDecidedBy").value("cmo2"));

        // the 28-day appeal window is enforced
        String second = body(postJson("/api/v1/cases/" + caseId + "/ihr-assessments", PHYSICIAN,
                request.replace("ILL_HEALTH_RETIREMENT", "REDEPLOYMENT")).andReturn()).get("id").asString();
        postJson("/api/v1/ihr-assessments/" + second + "/cmo-decision", CMO, "{\"approve\":true,\"decisionText\":\"Redeployment to office duties\"}")
                .andExpect(status().isOk());
        jdbc.update("update fitness.ihr_assessment set appeal_deadline = ? where id = ?::uuid", TODAY.minusDays(1), UUID.fromString(second));
        postJson("/api/v1/ihr-assessments/" + second + "/appeal", HR, "{\"grounds\":\"Late appeal\"}")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("appeal_window_closed"));
    }
}
