package com.wellnessz.app;

import static com.wellnessz.app.PlatformIntegrationTest.user;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ThreadLocalRandom;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.RequestBuilder;

import com.wellnessz.casekernel.support.SupportProgrammeReferral;
import com.wellnessz.integration.outbox.OutboxRelay;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Acceptance tests for E10 – drug & alcohol testing (F01–F05) against the real endpoints: policies and pools, the auditable
 * random selection with reproducible verification, post-incident tests from the incident feed, for-cause requests, electronic
 * chain of custody, the laboratory interface bound to TOXLAB, MRO review, the positive pathway (restricted case, duty removal,
 * support-programme referral, return-to-duty plan), HR / crew scheduling status-only view and suppressed statistics –
 * including the confidentiality negatives.
 */
class Increment5SubstanceIntegrationTest extends IntegrationTestBase {

    @Autowired
    MockMvc mvc;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    JsonMapper json;
    @Autowired
    OutboxRelay relay;
    @Autowired
    ObjectProvider<SupportProgrammeReferral> referrals;

    static final JwtRequestPostProcessor DAM = user("damanager1", "S8804", "/AIRLINE", "DA_PROGRAMME_MANAGER");
    static final JwtRequestPostProcessor MRO = user("mro1", "S8805", "/AIRLINE", "MRO");
    static final JwtRequestPostProcessor COLLECTOR = user("collector1", "S8806", "/AIRLINE", "COLLECTOR");
    static final JwtRequestPostProcessor SAFETY = user("safety1", "S8803", "/AIRLINE", "SAFETY_MANAGER");
    static final JwtRequestPostProcessor CMO = user("cmo1", "D8301", "/AIRLINE/OH", "CMO", "OH_PHYSICIAN", "OH_CLINICIAN");
    static final JwtRequestPostProcessor PHYSICIAN = user("physician1", "D8101", "/AIRLINE/OH", "OH_PHYSICIAN", "OH_CLINICIAN");
    static final JwtRequestPostProcessor NURSE = user("nurse1", "N1", null, "OH_CLINICIAN");
    static final JwtRequestPostProcessor AUDITOR = user("auditor1", "A1", null, "AUDITOR");
    static final JwtRequestPostProcessor HR_OPS = user("hr1", "H3001", "/AIRLINE/OPS", "HR");
    static final JwtRequestPostProcessor HR_CARGO = user("hr9", "H9", "/AIRLINE/CARGO", "HR");
    static final JwtRequestPostProcessor CREW_SCHEDULING = user("crewsched1", "S8601", "/AIRLINE/OPS", "CREW_SCHEDULING");
    static final JwtRequestPostProcessor MANAGER_GROUND = user("manager7", "M7", "/AIRLINE/OPS/GROUND", "MANAGER");
    static final JwtRequestPostProcessor MANAGER_CATERING = user("manager1", "M2001", "/AIRLINE/CATERING", "MANAGER");
    /** The laboratory system (org_scope /PARTNERS/TOXLAB) and two clients that must not reach it. */
    static final JwtRequestPostProcessor LAB = user("toxlab-system", null, "/PARTNERS/TOXLAB", "INTEGRATION");
    static final JwtRequestPostProcessor OTHER_LAB = user("eap-system", null, "/PARTNERS/EAP_WELLBEING", "INTEGRATION");
    static final JwtRequestPostProcessor UNBOUND = user("rostering-system", null, null, "INTEGRATION");

    static final int YEAR = LocalDate.now(ZoneOffset.UTC).getYear();
    static final String DEMO_FLIGHT_SELECTION = "e10de000-0000-4000-c000-000000000001";
    static final String DEMO_POST_INCIDENT = "e10de000-0000-4000-b000-000000000005";
    static final String DEMO_MRO_REVIEW = "e10de000-0000-4000-b000-000000000003";

    // ------------------------------------------------------------------ support-programme SPI (only when E09 is absent)

    /** Records referrals; registered only when no other SupportProgrammeReferral bean (E09) exists. */
    public static class RecordingReferral implements SupportProgrammeReferral {
        static final List<List<String>> CALLS = new CopyOnWriteArrayList<>();

        @Override
        public Optional<UUID> refer(String actor, String employeeId, UUID relatedCaseId, String reason, String sourceType, String sourceRef) {
            CALLS.add(List.of(actor, employeeId, String.valueOf(relatedCaseId), reason, sourceType, sourceRef));
            return Optional.of(UUID.randomUUID());
        }
    }

    @TestConfiguration
    static class ReferralConfig {
        @Bean
        static BeanDefinitionRegistryPostProcessor recordingReferralRegistrar() {
            return new BeanDefinitionRegistryPostProcessor() {
                @Override
                public void postProcessBeanDefinitionRegistry(BeanDefinitionRegistry registry) {
                    if (registry instanceof ListableBeanFactory f && f.getBeanNamesForType(SupportProgrammeReferral.class, true, false).length == 0) {
                        registry.registerBeanDefinition("recordingSupportReferral", new RootBeanDefinition(RecordingReferral.class));
                    }
                }

                @Override
                public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) {
                    // nothing to do
                }
            };
        }
    }

    // ------------------------------------------------------------------ helpers

    JsonNode body(MvcResult r) throws Exception {
        return json.readTree(r.getResponse().getContentAsString());
    }

    JsonNode ok(RequestBuilder rb) throws Exception {
        return body(mvc.perform(rb).andExpect(status().isOk()).andReturn());
    }

    MvcResult postJson(String url, JwtRequestPostProcessor who, Object content, int expected) throws Exception {
        return mvc.perform(post(url).with(who).contentType(MediaType.APPLICATION_JSON)
                        .content(content instanceof String s ? s : json.writeValueAsString(content)))
                .andExpect(status().is(expected)).andReturn();
    }

    JsonNode postOk(String url, JwtRequestPostProcessor who, Object content, int expected) throws Exception {
        return body(postJson(url, who, content, expected));
    }

    /** A fresh employee with the given job code and department. */
    String employee(String jobCode, String department, String subsidiary) {
        String id = "T5" + ThreadLocalRandom.current().nextInt(100_000, 999_999);
        jdbc.update("""
                insert into masterdata.employee (employee_id, display_name, email, subsidiary_code, department_code, cost_centre, job_code,
                                                 location_code, shift_pattern, preferred_language, badge_id, manager_employee_id, updated_at)
                values (?, ?, ?, ?, ?, 'CC-X', ?, 'LHR', 'LATE', 'en', ?, null, now())""",
                id, "Tess Tester " + id, id.toLowerCase() + "@wellnessz.local", subsidiary, department, jobCode, "BT-" + id);
        return id;
    }

    String ramp() {
        return employee("RAMP_AGENT", "GROUND", "GND");
    }

    void incident(String employeeId, String department, String incidentClass, String severity, Instant occurredAt) throws Exception {
        String ref = "INC-" + UUID.randomUUID().toString().substring(0, 8);
        String incident = """
                {"externalRef":"%s","occurredAt":"%s","incidentClass":"%s","severity":"%s","bodyPart":"HAND","location":"LHR-STAND-5",
                 "unitCode":"GND","shift":"EARLY","employeeId":"%s","orgUnitCode":"%s","description":"Event on the ramp",
                 "reporterEmail":"hse.officer@wellnessz.local"}""".formatted(ref, occurredAt, incidentClass, severity, employeeId, department);
        mvc.perform(post("/api/v1/intake/ehs/incidents").with(user("ehs-system", null, null, "INTEGRATION"))
                .header("Idempotency-Key", "k-" + ref).contentType(MediaType.APPLICATION_JSON).content(incident)).andExpect(status().isAccepted());
        relay.relay();
        relay.relay();
    }

    JsonNode requirement(JwtRequestPostProcessor who, String status, String employeeId) throws Exception {
        JsonNode list = ok(get("/api/v1/testing/requirements" + (status == null ? "" : "?status=" + status)).with(who));
        for (JsonNode r : list) {
            if (employeeId.equals(r.path("employeeId").asString())) {
                return r;
            }
        }
        return null;
    }

    long notifications(String address, String template) {
        return jdbc.queryForObject("select count(*) from platform.notification where address = ? and template_key = ?", Long.class, address, template);
    }

    long audit(String action, String outcome) {
        return jdbc.queryForObject("select count(*) from platform.audit_event where action = ? and outcome = ?", Long.class, action, outcome);
    }

    void collect(String testId, String specimenId, String type, Double breath) throws Exception {
        Map<String, Object> b = new java.util.LinkedHashMap<>();
        b.put("specimenId", specimenId);
        b.put("specimenType", type);
        b.put("collectedAt", Instant.now().minusSeconds(60).toString());
        b.put("siteCode", "LHR-OH-CLINIC");
        b.put("sealIntact", true);
        b.put("donorIdentityChecked", true);
        b.put("splitSpecimen", !"BREATH".equals(type));
        if ("URINE".equals(type)) {
            b.put("temperatureInRange", true);
        }
        if (breath != null) {
            b.put("breathScreenResult", breath);
        }
        postJson("/api/v1/testing/tests/" + testId + "/collection", COLLECTOR, b, 200);
    }

    void labResults(String specimenId, String outcomeThc) throws Exception {
        postJson("/api/v1/testing/specimens/" + specimenId + "/custody", COLLECTOR,
                Map.of("event", "SEALED", "at", Instant.now().minusSeconds(50).toString(), "by", "Colin Collector"), 200);
        postOk("/api/v1/integration/testing/lab-results", LAB, Map.of("specimenId", specimenId, "labRef", "TOX-" + specimenId,
                "receivedAt", Instant.now().minusSeconds(10).toString(), "validity", "VALID", "results", List.of(
                        Map.of("analyte", "CANNABINOIDS", "cutoff", "50", "value", "NON_NEGATIVE".equals(outcomeThc) ? "88" : "0", "unit", "ng/mL",
                                "outcome", outcomeThc),
                        Map.of("analyte", "OPIATES", "cutoff", "2000", "value", "0", "unit", "ng/mL", "outcome", "NEGATIVE"))), 200);
    }

    static String specimenId() {
        return "SPC-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
    }

    // =================================================================== F01 policies, pools, random selection

    @Test
    void f01PoliciesPoolsSelectionVerifyAndNotify() throws Exception {
        JsonNode policies = ok(get("/api/v1/testing/policies").with(DAM));
        assertThat(policies.size()).isEqualTo(6);
        JsonNode flight = ok(get("/api/v1/testing/policies/FLIGHT_CREW").with(CMO));
        assertThat(flight.path("jobCodes").get(0).asString()).isEqualTo("PILOT");
        assertThat(flight.path("alcoholWindowHours").asInt()).isEqualTo(8);
        assertThat(flight.path("drugWindowHours").asInt()).isEqualTo(32);
        assertThat(ok(get("/api/v1/testing/policies/SECURITY").with(SAFETY)).path("approvalStatus").asString()).isEqualTo("PENDING");
        mvc.perform(get("/api/v1/testing/policies").with(COLLECTOR)).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/testing/policies").with(HR_OPS)).andExpect(status().isForbidden());

        // approval (E10-BL-010) and back to PENDING on a parameter change
        long version = ok(get("/api/v1/testing/policies/ENGINEER").with(DAM)).path("version").asLong();
        JsonNode approved = body(mvc.perform(put("/api/v1/testing/policies/ENGINEER").with(DAM).contentType(MediaType.APPLICATION_JSON)
                .content("{\"approvalStatus\":\"APPROVED\",\"approvedBy\":\"Legal / Safety / HR policy board 2026-10\"}"))
                .andExpect(status().isOk()).andReturn());
        assertThat(approved.path("approvalStatus").asString()).isEqualTo("APPROVED");
        assertThat(approved.path("version").asLong()).isGreaterThan(version);
        mvc.perform(put("/api/v1/testing/policies/ENGINEER").with(DAM).contentType(MediaType.APPLICATION_JSON).content("{\"approvalStatus\":\"APPROVED\"}"))
                .andExpect(status().isUnprocessableContent());
        mvc.perform(put("/api/v1/testing/policies/ENGINEER").with(DAM).contentType(MediaType.APPLICATION_JSON).content("{\"randomDrugRatePct\":150}"))
                .andExpect(status().isBadRequest());
        JsonNode changed = body(mvc.perform(put("/api/v1/testing/policies/ENGINEER").with(DAM).contentType(MediaType.APPLICATION_JSON)
                .content("{\"randomDrugRatePct\":50}")).andExpect(status().isOk()).andReturn());
        assertThat(changed.path("approvalStatus").asString()).isEqualTo("PENDING");
        mvc.perform(put("/api/v1/testing/policies/ENGINEER").with(CMO).contentType(MediaType.APPLICATION_JSON).content("{\"randomDrugRatePct\":25}"))
                .andExpect(status().isForbidden());

        // pool from HRIS job codes, exclusions
        List<String> engineers = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            engineers.add(employee(i % 2 == 0 ? "ENGINEER" : "ENG_HEIGHT", "ENG", "ENG"));
        }
        JsonNode pools = ok(get("/api/v1/testing/pools?roleGroup=ENGINEER").with(DAM));
        assertThat(pools.get(0).path("members").toString()).contains(engineers.get(0), engineers.get(1));
        postOk("/api/v1/testing/pools/ENGINEER/exclusions", DAM, Map.of("employeeId", engineers.get(5), "reason", "LONG_TERM_ABSENCE",
                "until", LocalDate.now(ZoneOffset.UTC).plusMonths(3).toString()), 201);
        JsonNode afterExclusion = ok(get("/api/v1/testing/pools?roleGroup=ENGINEER").with(DAM)).get(0);
        assertThat(afterExclusion.path("members").toString()).doesNotContain(engineers.get(5));
        assertThat(afterExclusion.path("exclusions").toString()).contains(engineers.get(5), "LONG_TERM_ABSENCE");
        postJson("/api/v1/testing/pools/ENGINEER/exclusions", DAM, Map.of("employeeId", "E1001", "reason", "LEAVE",
                "until", LocalDate.now(ZoneOffset.UTC).plusDays(3).toString()), 422);
        mvc.perform(get("/api/v1/testing/pools").with(COLLECTOR)).andExpect(status().isForbidden());

        // auditable random selection
        String period = YearMonth.now(ZoneOffset.UTC).plusMonths(1).toString();
        JsonNode sel = postOk("/api/v1/testing/selections", DAM, Map.of("roleGroup", "ENGINEER", "period", period, "testType", "DRUGS"), 201);
        int pool = sel.path("poolSize").asInt();
        assertThat(pool).isEqualTo(afterExclusion.path("size").asInt());
        assertThat(sel.path("selectedCount").asInt()).isEqualTo((int) Math.ceil(pool * 50 / 1200.0)).isEqualTo(sel.path("selected").size());
        assertThat(sel.path("seedCommitment").asString()).matches("[0-9a-f]{64}");
        assertThat(sel.path("poolHash").asString()).matches("[0-9a-f]{64}");
        assertThat(sel.path("ref").asString()).matches("RSL-\\d{4}-\\d{6}");
        assertThat(sel.toString()).doesNotContain(engineers.get(5));
        postJson("/api/v1/testing/selections", DAM, Map.of("roleGroup", "ENGINEER", "period", period, "testType", "DRUGS"), 409);
        postJson("/api/v1/testing/selections", DAM, Map.of("roleGroup", "ENGINEER", "period", "2026-13", "testType", "DRUGS"), 400);
        postJson("/api/v1/testing/selections", SAFETY, Map.of("roleGroup", "ENGINEER", "period", period, "testType", "ALCOHOL"), 403);
        String selId = sel.path("id").asString();
        JsonNode verify = ok(get("/api/v1/testing/selections/" + selId + "/verify").with(AUDITOR));
        assertThat(verify.path("reproducible").asBoolean()).isTrue();
        assertThat(verify.path("seedCommitmentMatches").asBoolean()).isTrue();
        assertThat(ok(get("/api/v1/testing/selections?period=" + period).with(DAM)).toString()).contains(selId);
        mvc.perform(get("/api/v1/testing/selections").with(COLLECTOR)).andExpect(status().isForbidden());

        // tampering with the stored draw is detected
        JsonNode other = postOk("/api/v1/testing/selections", DAM, Map.of("roleGroup", "ENGINEER", "period", period, "testType", "ALCOHOL"), 201);
        jdbc.update("update substance.selection set pool_snapshot = ? where id = ?::uuid", "[\"X0001\",\"X0002\"]", other.path("id").asString());
        assertThat(ok(get("/api/v1/testing/selections/" + other.path("id").asString() + "/verify").with(DAM)).path("reproducible").asBoolean()).isFalse();

        // notify: REQUIRED → NOTIFIED with short lead time, notices to the employee and the collectors
        long dispatchBefore = notifications("collector1@wellnessz.local", "testing.collection-dispatch");
        JsonNode tests = postOk("/api/v1/testing/selections/" + selId + "/notify", DAM, "{}", 200);
        assertThat(tests.size()).isEqualTo(sel.path("selectedCount").asInt());
        String selected = tests.get(0).path("employeeId").asString();
        assertThat(tests.get(0).path("status").asString()).isEqualTo("NOTIFIED");
        assertThat(tests.get(0).path("reason").asString()).isEqualTo("RANDOM");
        assertThat(tests.get(0).path("ref").asString()).matches("DAT-\\d{4}-\\d{6}");
        assertThat(Instant.parse(tests.get(0).path("dueAt").asString())).isBetween(Instant.now().plus(110, ChronoUnit.MINUTES),
                Instant.now().plus(125, ChronoUnit.MINUTES));
        // the draw is random: read the selected employee's address from the HRIS master (demo e-mails are not always <id>@…)
        String selectedEmail = jdbc.queryForObject("select email from masterdata.employee where employee_id = ?", String.class, selected);
        assertThat(notifications(selectedEmail, "testing.selection-notice")).isEqualTo(1);
        assertThat(notifications("collector1@wellnessz.local", "testing.collection-dispatch")).isGreaterThan(dispatchBefore);
        String noticeBody = jdbc.queryForObject("select body from platform.notification where address = ? and template_key = 'testing.selection-notice'",
                String.class, selectedEmail);
        assertThat(noticeBody).doesNotContainIgnoringCase("random").doesNotContainIgnoringCase("drug");
        postJson("/api/v1/testing/selections/" + selId + "/notify", DAM, "{}", 409);

        // the employee sees where and when to report – nothing else
        JsonNode mine = ok(get("/api/v1/me/testing").with(user("emp", selected, "/AIRLINE/ENG", "EMPLOYEE")));
        assertThat(mine.size()).isEqualTo(1);
        assertThat(mine.get(0).path("status").asString()).isEqualTo("NOTIFIED");
        assertThat(mine.get(0).has("reportBy")).isTrue();
        assertThat(mine.get(0).has("reason")).isFalse();

        // coverage vs policy target
        JsonNode coverage = ok(get("/api/v1/testing/coverage?year=" + YEAR).with(CMO));
        assertThat(coverage.size()).isEqualTo(6);
        for (JsonNode row : coverage) {
            if ("AIRSIDE_DRIVER".equals(row.path("roleGroup").asString())) {
                assertThat(row.path("completed").asInt()).isGreaterThanOrEqualTo(6);
                assertThat(row.has("coveragePct")).isTrue();
                assertThat(row.has("behindPlan")).isTrue();
            }
        }
        mvc.perform(get("/api/v1/testing/coverage").with(SAFETY)).andExpect(status().isForbidden());
    }

    @Test
    void demoSelectionIsReproducibleAndDemoRequirementsAreInPlace() throws Exception {
        JsonNode verify = ok(get("/api/v1/testing/selections/" + DEMO_FLIGHT_SELECTION + "/verify").with(DAM));
        assertThat(verify.path("reproducible").asBoolean()).isTrue();
        assertThat(verify.path("poolHashMatches").asBoolean()).isTrue();
        assertThat(verify.path("selectionMatches").asBoolean()).isTrue();

        JsonNode e1004 = requirement(COLLECTOR, null, "E1004");
        assertThat(e1004).isNotNull();
        assertThat(e1004.path("id").asString()).isEqualTo(DEMO_POST_INCIDENT);
        assertThat(e1004.path("status").asString()).isEqualTo("DISPATCHED");
        assertThat(e1004.path("reason").asString()).isEqualTo("POST_INCIDENT");
        assertThat(e1004.path("hoursLeft").asDouble()).isBetween(4.5, 5.1);
        assertThat(e1004.has("observations")).isFalse();

        // E1009: verified positive, removed from duty, plan in progress (status only for HR / crew scheduling)
        JsonNode status = ok(get("/api/v1/workspace/testing/status").with(HR_OPS));
        assertThat(status.toString()).contains("E1009", "REMOVED");
        JsonNode plans = ok(get("/api/v1/testing/rtd-plans?status=ACTIVE").with(MRO));
        assertThat(plans.toString()).contains("E1009");

        // the demo MRO case carries laboratory results for the MRO only
        JsonNode queue = ok(get("/api/v1/testing/mro/queue").with(MRO));
        JsonNode demo = null;
        for (JsonNode q : queue) {
            if (DEMO_MRO_REVIEW.equals(q.path("testId").asString())) {
                demo = q;
            }
        }
        assertThat(demo).isNotNull();
        assertThat(demo.path("results").toString()).contains("OPIATES", "NON_NEGATIVE");
    }

    // =================================================================== F02–F04 post-incident → lab → MRO → positive pathway → RTD

    @Test
    void f02ToF04PostIncidentChainOfCustodyLaboratoryMroAndPositivePathway() throws Exception {
        String emp = ramp();
        Instant occurred = Instant.now().minus(1, ChronoUnit.HOURS).truncatedTo(ChronoUnit.SECONDS);
        incident(emp, "GROUND", "LOST_TIME_INJURY", "MEDIUM", occurred);
        JsonNode req = requirement(DAM, null, emp);
        assertThat(req).isNotNull();
        assertThat(req.path("reason").asString()).isEqualTo("POST_INCIDENT");
        assertThat(req.path("status").asString()).isEqualTo("DISPATCHED");
        assertThat(Instant.parse(req.path("alcoholDueAt").asString())).isEqualTo(occurred.plus(8, ChronoUnit.HOURS));
        assertThat(Instant.parse(req.path("drugDueAt").asString())).isEqualTo(occurred.plus(32, ChronoUnit.HOURS));
        assertThat(req.path("hoursLeft").asDouble()).isBetween(6.8, 7.0);
        String testId = req.path("id").asString();
        relay.relay(); // a repeated delivery does not create a second requirement
        assertThat(jdbc.queryForObject("select count(*) from substance.test where employee_id = ?", Long.class, emp)).isEqualTo(1);

        // non-qualifying incident / employee not in a pool → no test
        String other = ramp();
        incident(other, "GROUND", "NEAR_MISS", "HIGH", Instant.now().minusSeconds(600));
        String caterer = employee("CATERING_ASSOC", "CATERING", "CAT");
        incident(caterer, "CATERING", "LOST_TIME_INJURY", "MEDIUM", Instant.now().minusSeconds(600));
        assertThat(jdbc.queryForObject("select count(*) from substance.test where employee_id in (?, ?)", Long.class, other, caterer)).isZero();

        // collector: work list without observations, electronic chain-of-custody form in the restricted class
        assertThat(requirement(COLLECTOR, null, emp)).isNotNull();
        String breath = specimenId();
        collect(testId, breath, "BREATH", 0.0);
        String urine = specimenId();
        collect(testId, urine, "URINE", null);
        postJson("/api/v1/testing/tests/" + testId + "/collection", COLLECTOR, Map.of("specimenId", specimenId(), "specimenType", "URINE",
                "collectedAt", Instant.now().toString(), "siteCode", "LHR", "temperatureInRange", true, "sealIntact", true,
                "donorIdentityChecked", true, "splitSpecimen", false), 409);
        assertThat(jdbc.queryForObject("select status from substance.test where id = ?::uuid", String.class, testId)).isEqualTo("COLLECTED");
        assertThat(jdbc.queryForObject("""
                select count(*) from docs.document d join substance.specimen s on s.document_id = d.id
                 where s.test_id = ?::uuid and d.template_code = 'CHAIN_OF_CUSTODY_FORM' and d.classification = 'RESTRICTED_CLINICAL'
                   and d.signed_by = 'collector1'""", Long.class, testId)).isEqualTo(2);
        assertThat(jdbc.queryForObject("select breath_result from substance.test where id = ?::uuid", String.class, testId)).startsWith("v1:");

        // chain of custody: ordered, append-only, signer and time recorded
        postJson("/api/v1/testing/specimens/" + urine + "/custody", COLLECTOR,
                Map.of("event", "SEALED", "at", Instant.now().minusSeconds(30).toString(), "by", "Colin Collector"), 200);
        postJson("/api/v1/testing/specimens/" + urine + "/custody", COLLECTOR,
                Map.of("event", "SEALED", "at", Instant.now().minusSeconds(20).toString(), "by", "Colin Collector"), 409);
        JsonNode custody = postOk("/api/v1/testing/specimens/" + urine + "/custody", COLLECTOR,
                Map.of("event", "COURIER_PICKUP", "at", Instant.now().minusSeconds(20).toString(), "by", "MedCourier – J. Swift"), 200);
        assertThat(custody.size()).isEqualTo(3);
        assertThat(custody.get(0).path("event").asString()).isEqualTo("COLLECTED");
        assertThat(custody.get(2).path("signer").asString()).isEqualTo("collector1");
        // the runtime role has no update / delete grant, and a trigger rejects changes even for the schema owner
        assertThatThrownBy(() -> jdbc.update("update substance.custody_event set by_name = 'x' where specimen_id = ?", urine))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update("delete from substance.custody_event where specimen_id = ?", urine))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThat(jdbc.queryForObject("select count(*) from pg_trigger where tgname = 'custody_event_append_only'", Long.class)).isEqualTo(1);

        // laboratory interface bound to TOXLAB: other organisations / unbound clients see nothing (404 + audit)
        long denied = audit("DA_LAB_ORDERS_READ", "DENIED");
        mvc.perform(get("/api/v1/integration/testing/lab-orders").with(OTHER_LAB)).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/integration/testing/lab-orders").with(UNBOUND)).andExpect(status().isNotFound());
        assertThat(audit("DA_LAB_ORDERS_READ", "DENIED")).isEqualTo(denied + 2);
        mvc.perform(get("/api/v1/integration/testing/lab-orders").with(COLLECTOR)).andExpect(status().isForbidden());
        MvcResult orders = mvc.perform(get("/api/v1/integration/testing/lab-orders").with(LAB)).andExpect(status().isOk()).andReturn();
        String ordersJson = orders.getResponse().getContentAsString();
        assertThat(ordersJson).contains(urine, "CANNABINOIDS").doesNotContain(emp).doesNotContain(breath);
        postJson("/api/v1/testing/specimens/" + urine + "/custody", OTHER_LAB,
                Map.of("event", "LAB_RECEIVED", "at", Instant.now().minusSeconds(10).toString(), "by", "Other lab"), 404);
        postJson("/api/v1/testing/specimens/" + urine + "/custody", LAB,
                Map.of("event", "LAB_RECEIVED", "at", Instant.now().minusSeconds(10).toString(), "by", "TOXLAB reception"), 200);
        assertThat(jdbc.queryForObject("select status from substance.test where id = ?::uuid", String.class, testId)).isEqualTo("IN_LAB");
        Map<String, Object> result = Map.of("specimenId", urine, "labRef", "TOX-" + urine, "receivedAt", Instant.now().minusSeconds(5).toString(),
                "validity", "VALID", "results", List.of(Map.of("analyte", "CANNABINOIDS", "cutoff", "50", "value", "88", "unit", "ng/mL",
                        "outcome", "NON_NEGATIVE"), Map.of("analyte", "OPIATES", "cutoff", "2000", "value", "0", "unit", "ng/mL", "outcome", "NEGATIVE")));
        postJson("/api/v1/integration/testing/lab-results", OTHER_LAB, result, 404);
        postJson("/api/v1/integration/testing/lab-results", LAB, Map.of("specimenId", urine, "labRef", "x", "receivedAt", Instant.now().toString(),
                "validity", "VALID", "results", List.of(Map.of("analyte", "THC; drop table", "cutoff", "50", "value", "1", "unit", "ng/mL",
                        "outcome", "NEGATIVE"))), 400);
        long mroNotices = notifications("mro1@wellnessz.local", "testing.review-queue");
        assertThat(postOk("/api/v1/integration/testing/lab-results", LAB, result, 200).path("status").asString()).isEqualTo("RECEIVED");
        postJson("/api/v1/integration/testing/lab-results", LAB, result, 409);
        assertThat(notifications("mro1@wellnessz.local", "testing.review-queue")).isGreaterThan(mroNotices);
        assertThat(jdbc.queryForObject("select results from substance.test where id = ?::uuid", String.class, testId)).startsWith("v1:");

        // results are for the MRO only
        JsonNode pmRow = requirement(DAM, "MRO_REVIEW", emp);
        assertThat(pmRow).isNotNull();
        assertThat(pmRow.toString()).doesNotContain("NON_NEGATIVE").doesNotContain("CANNABINOIDS");
        mvc.perform(get("/api/v1/testing/mro/queue").with(DAM)).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/testing/mro/queue").with(COLLECTOR)).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/testing/mro/queue").with(PHYSICIAN)).andExpect(status().isForbidden()); // not designated as acting MRO
        mvc.perform(get("/api/v1/testing/mro/queue").with(HR_OPS)).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/testing/requirements").with(HR_OPS)).andExpect(status().isForbidden());
        postJson("/api/v1/testing/tests/" + testId + "/mro-decision", DAM, Map.of("verdict", "NEGATIVE"), 403);
        long resultReads = audit("DA_RESULT_READ", "SUCCESS");
        JsonNode queue = ok(get("/api/v1/testing/mro/queue").with(MRO));
        assertThat(queue.toString()).contains(testId, "NON_NEGATIVE", "CANNABINOIDS");
        assertThat(audit("DA_RESULT_READ", "SUCCESS")).isGreaterThan(resultReads);

        // MRO state machine
        postOk("/api/v1/testing/tests/" + testId + "/mro-decision", MRO, Map.of("verdict", "NEGATIVE"), 422);
        postOk("/api/v1/testing/tests/" + testId + "/mro-decision", MRO, Map.of("verdict", "NEGATIVE_LEGITIMATE_PRESCRIPTION",
                "prescriptionVerified", false), 422);
        int referralsBefore = RecordingReferral.CALLS.size();
        JsonNode decision = postOk("/api/v1/testing/tests/" + testId + "/mro-decision", MRO, Map.of("verdict", "POSITIVE",
                "interviewNote", "Donor interviewed – no legitimate medical explanation."), 200);
        assertThat(decision.path("status").asString()).isEqualTo("VERIFIED_POSITIVE");
        String caseId = decision.path("caseId").asString();
        String planId = decision.path("rtdPlanId").asString();
        assertThat(caseId).isNotBlank();
        assertThat(planId).isNotBlank();
        postOk("/api/v1/testing/tests/" + testId + "/mro-decision", MRO, Map.of("verdict", "POSITIVE"), 409);

        // positive pathway: restricted case, duty removed via the status-only roster feed, support referral, HR / crew scheduling notices
        Map<String, Object> kase = jdbc.queryForMap("select category, classification, fitness_status, source from casemgmt.oh_case where id = ?::uuid",
                caseId);
        assertThat(kase).containsEntry("category", "SUBSTANCE").containsEntry("classification", "RESTRICTED_CLINICAL")
                .containsEntry("fitness_status", "TEMPORARILY_UNFIT").containsEntry("source", "DA_TEST");
        assertThat(jdbc.queryForObject("select status from substance.duty_status where employee_id = ?", String.class, emp)).isEqualTo("REMOVED");
        // status-only fitness feed (HR; crew rostering additionally for aeromedical safety categories – ramp staff are not rostered crew)
        String caseRef = jdbc.queryForObject("select case_ref from casemgmt.oh_case where id = ?::uuid", String.class, caseId);
        assertThat(jdbc.queryForObject("select count(*) from platform.outbox_event where destination = 'hr-systems' and aggregate_id = ?",
                Long.class, caseRef)).isPositive();
        assertThat(jdbc.queryForObject("""
                select count(*) from platform.notification where template_key = 'testing.status' and body like ?""", Long.class,
                "%Tess Tester " + emp + "%")).isEqualTo(2);
        assertThat(jdbc.queryForObject("""
                select count(*) from platform.disclosure_log where employee_id = ? and resource_type = 'DUTY_STATUS'""", Long.class, emp)).isEqualTo(2);
        if (referrals.orderedStream().findFirst().orElse(null) instanceof RecordingReferral) {
            assertThat(RecordingReferral.CALLS.size()).isEqualTo(referralsBefore + 1);
            List<String> call = RecordingReferral.CALLS.stream().filter(c -> c.get(1).equals(emp)).findFirst().orElseThrow();
            assertThat(call).containsExactly("mro1", emp, caseId, "SUBSTANCE_POSITIVE", "DA_TEST", req.path("ref").asString());
            assertThat(jdbc.queryForObject("select support_case_id from substance.test where id = ?::uuid", String.class, testId)).isNotNull();
        } else {
            assertThat(jdbc.queryForObject("select count(*) from platform.audit_event where action = 'DA_SUPPORT_REFERRAL' and resource_id = ?",
                    Long.class, testId)).isEqualTo(1);
        }

        // HR / crew scheduling: status only (scope-limited, disclosure-logged); general OH never sees the restricted case
        MvcResult hr = mvc.perform(get("/api/v1/workspace/testing/status").with(HR_OPS)).andExpect(status().isOk()).andReturn();
        String hrJson = hr.getResponse().getContentAsString();
        assertThat(hrJson).contains(emp, "REMOVED").doesNotContain("POSITIVE").doesNotContain("POST_INCIDENT").doesNotContain("CANNABINOIDS")
                .doesNotContain(req.path("ref").asString());
        assertThat(jdbc.queryForObject("""
                select count(*) from platform.disclosure_log where employee_id = ? and recipient_name = 'HR hr1'""", Long.class, emp)).isEqualTo(1);
        mvc.perform(get("/api/v1/workspace/testing/status").with(HR_OPS)).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("""
                select count(*) from platform.disclosure_log where employee_id = ? and recipient_name = 'HR hr1'""", Long.class, emp)).isEqualTo(1);
        assertThat(ok(get("/api/v1/workspace/testing/status").with(HR_CARGO)).toString()).doesNotContain(emp);
        assertThat(ok(get("/api/v1/workspace/testing/status").with(CREW_SCHEDULING)).toString()).contains(emp, "REMOVED");
        mvc.perform(get("/api/v1/workspace/testing/status").with(DAM)).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/cases/" + caseId).with(NURSE)).andExpect(status().isNotFound());
        assertThat(ok(get("/api/v1/me/testing").with(user("emp", emp, "/AIRLINE/OPS/GROUND", "EMPLOYEE"))).get(0).path("status").asString())
                .isEqualTo("COMPLETED");

        // return-to-duty plan with unannounced follow-ups; READY needs a verified negative RETURN_TO_DUTY test
        postJson("/api/v1/testing/rtd-plans", COLLECTOR, Map.of("employeeId", emp, "evaluator", "x", "followUpTests", 1, "followUpMonths", 1), 403);
        JsonNode plan = postOk("/api/v1/testing/rtd-plans", PHYSICIAN, Map.of("employeeId", emp, "caseId", caseId, "evaluator",
                "Dr Max MRO (SAP)", "treatmentProvider", "Wellbeing Assist EAP", "followUpTests", 4, "followUpMonths", 6), 200);
        assertThat(plan.path("id").asString()).isEqualTo(planId);
        assertThat(plan.path("status").asString()).isEqualTo("ACTIVE");
        assertThat(plan.path("followUps").size()).isEqualTo(4);
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        for (JsonNode f : plan.path("followUps")) {
            LocalDate d = LocalDate.parse(f.path("scheduledFor").asString());
            assertThat(d).isAfter(today).isBeforeOrEqualTo(today.plusMonths(6));
            assertThat(f.path("status").asString()).isEqualTo("REQUIRED");
        }
        assertThat(requirement(COLLECTOR, null, emp)).isNull(); // unannounced follow-ups are not visible to collectors in advance
        postJson("/api/v1/testing/rtd-plans", MRO, Map.of("employeeId", emp, "caseId", UUID.randomUUID().toString(), "evaluator", "x",
                "followUpTests", 1, "followUpMonths", 1), 422);

        JsonNode notReady = postOk("/api/v1/testing/rtd-plans/" + planId + "/evaluation", MRO, Map.of("outcome", "NOT_READY", "note", "Programme ongoing"), 200);
        assertThat(notReady.path("status").asString()).isEqualTo("ACTIVE");
        JsonNode ready = postOk("/api/v1/testing/rtd-plans/" + planId + "/evaluation", MRO, Map.of("outcome", "READY"), 200);
        assertThat(ready.path("status").asString()).isEqualTo("RTD_PENDING");
        assertThat(ready.path("dutyStatus").asString()).isEqualTo("RETURN_PENDING");
        assertThat(ready.path("rtdTest").path("status").asString()).isEqualTo("NOTIFIED");
        assertThat(ok(get("/api/v1/workspace/testing/status").with(HR_OPS)).toString()).contains("RETURN_PENDING");
        String rtdTestId = ready.path("rtdTest").path("id").asString();
        collect(rtdTestId, specimenId(), "BREATH", 0.0);
        String rtdUrine = specimenId();
        collect(rtdTestId, rtdUrine, "URINE", null);
        labResults(rtdUrine, "NEGATIVE");
        JsonNode cleared = postOk("/api/v1/testing/tests/" + rtdTestId + "/mro-decision", MRO, Map.of("verdict", "NEGATIVE"), 200);
        assertThat(cleared.path("status").asString()).isEqualTo("VERIFIED_NEGATIVE");
        assertThat(jdbc.queryForObject("select status from substance.duty_status where employee_id = ?", String.class, emp)).isEqualTo("AVAILABLE");
        assertThat(jdbc.queryForObject("select fitness_status from casemgmt.oh_case where id = ?::uuid", String.class, caseId)).isEqualTo("FIT");
        JsonNode monitoring = ok(get("/api/v1/testing/rtd-plans/" + planId).with(MRO));
        assertThat(monitoring.path("status").asString()).isEqualTo("MONITORING");
        assertThat(monitoring.path("dutyStatus").asString()).isEqualTo("AVAILABLE");
        assertThat(monitoring.path("followUps").size()).isEqualTo(4);
        mvc.perform(get("/api/v1/testing/rtd-plans").with(HR_OPS)).andExpect(status().isForbidden());
    }

    // =================================================================== F02 for-cause, F03 refusal

    @Test
    void f02ForCauseRequestAndF03RefusalPathway() throws Exception {
        String emp = ramp();
        Map<String, Object> request = Map.of("employeeId", emp, "observedAt", Instant.now().minusSeconds(1800).toString(), "location", "LHR stand 512",
                "observations", List.of("ODOUR_OF_ALCOHOL", "UNSTEADY_GAIT"), "description", "Smelled of alcohol at the start of the shift.",
                "secondObserverName", "Duty manager R. Lee");
        postJson("/api/v1/workspace/testing/for-cause", MANAGER_CATERING, request, 404); // outside the manager's org scope
        postJson("/api/v1/workspace/testing/for-cause", MANAGER_GROUND, Map.of("employeeId", emp, "observedAt", Instant.now().toString(),
                "location", "x", "observations", List.of(), "secondObserverName", "y"), 400);
        postJson("/api/v1/workspace/testing/for-cause", MANAGER_GROUND, Map.of("employeeId", emp, "observedAt", Instant.now().toString(),
                "location", "x", "observations", List.of("SMELLS_FUNNY"), "secondObserverName", "y"), 400);
        postJson("/api/v1/workspace/testing/for-cause", COLLECTOR, request, 403);
        JsonNode created = postOk("/api/v1/workspace/testing/for-cause", MANAGER_GROUND, request, 201);
        assertThat(created.path("status").asString()).isEqualTo("DISPATCHED");
        assertThat(created.has("observations")).isFalse();
        assertThat(created.has("description")).isFalse();
        String testId = created.path("id").asString();
        assertThat(jdbc.queryForObject("select description from substance.test where id = ?::uuid", String.class, testId)).startsWith("v1:");
        JsonNode mine = ok(get("/api/v1/workspace/testing/for-cause").with(MANAGER_GROUND));
        assertThat(mine.toString()).contains(testId).doesNotContain("ODOUR_OF_ALCOHOL").doesNotContain("Smelled");

        // programme manager sees the checklist, collectors do not
        assertThat(requirement(DAM, null, emp).path("observations").toString()).contains("ODOUR_OF_ALCOHOL");
        assertThat(requirement(COLLECTOR, null, emp).has("observations")).isFalse();

        // safety manager may request for anyone; observedAt in the future is rejected
        postJson("/api/v1/workspace/testing/for-cause", SAFETY, Map.of("employeeId", ramp(), "observedAt", Instant.now().plusSeconds(3600).toString(),
                "location", "x", "observations", List.of("DROWSINESS"), "secondObserverName", "y"), 422);

        // refusal at collection → MRO review → REFUSAL pathway
        postJson("/api/v1/testing/tests/" + testId + "/refusal", DAM, Map.of("reason", "REFUSED"), 403);
        postJson("/api/v1/testing/tests/" + testId + "/refusal", COLLECTOR, Map.of("reason", "REFUSED", "observations",
                "Employee declined to provide a specimen after explanation."), 200);
        assertThat(jdbc.queryForObject("select status from substance.test where id = ?::uuid", String.class, testId)).isEqualTo("MRO_REVIEW");
        assertThat(ok(get("/api/v1/workspace/testing/for-cause").with(MANAGER_GROUND)).toString()).contains("COLLECTED").doesNotContain("REFUSAL");
        JsonNode queue = ok(get("/api/v1/testing/mro/queue").with(MRO));
        assertThat(queue.toString()).contains(testId, "REFUSED");
        postOk("/api/v1/testing/tests/" + testId + "/mro-decision", MRO, Map.of("verdict", "NEGATIVE"), 422);
        JsonNode decision = postOk("/api/v1/testing/tests/" + testId + "/mro-decision", MRO, Map.of("verdict", "REFUSAL_ADULTERATED"), 200);
        assertThat(decision.path("status").asString()).isEqualTo("REFUSAL");
        assertThat(jdbc.queryForObject("select status from substance.duty_status where employee_id = ?", String.class, emp)).isEqualTo("REMOVED");
        if (referrals.orderedStream().findFirst().orElse(null) instanceof RecordingReferral) {
            assertThat(RecordingReferral.CALLS.stream().anyMatch(c -> c.get(1).equals(emp) && c.get(3).equals("SUBSTANCE_REFUSAL"))).isTrue();
        }
        assertThat(ok(get("/api/v1/workspace/testing/for-cause").with(MANAGER_GROUND)).toString()).contains("COMPLETED")
                .doesNotContain("REFUSAL").doesNotContain("REMOVED");
    }

    // =================================================================== F03 designated physician as MRO

    @Test
    void f03DesignatedPhysicianActsAsMro() throws Exception {
        mvc.perform(get("/api/v1/testing/mro/queue").with(PHYSICIAN)).andExpect(status().isForbidden());
        jdbc.update("update masterdata.config_entry set config_value = 'physician1' where config_key = 'substance.acting-mro-physicians'");
        try {
            mvc.perform(get("/api/v1/testing/mro/queue").with(PHYSICIAN)).andExpect(status().isOk());
            mvc.perform(get("/api/v1/testing/mro/queue").with(CMO)).andExpect(status().isForbidden()); // cmo1 is not designated
        } finally {
            jdbc.update("update masterdata.config_entry set config_value = '' where config_key = 'substance.acting-mro-physicians'");
        }
    }

    // =================================================================== F05 statistics & annual report

    @Test
    void f05StatisticsAreSuppressedAndTheAnnualReportIsAnonymised() throws Exception {
        JsonNode stats = ok(get("/api/v1/testing/statistics?year=" + YEAR).with(SAFETY));
        boolean randomAirside = false;
        boolean preEmployment = false;
        for (JsonNode c : stats) {
            if ("RANDOM".equals(c.path("reason").asString()) && "AIRSIDE_DRIVER".equals(c.path("roleGroup").asString())) {
                randomAirside = true;
                assertThat(c.path("suppressed").asBoolean()).isFalse();
                assertThat(c.path("tests").asInt()).isGreaterThanOrEqualTo(6);
                assertThat(c.has("positiveRatePct")).isTrue();
            }
            if ("PRE_EMPLOYMENT".equals(c.path("reason").asString())) {
                preEmployment = true;
                assertThat(c.path("suppressed").asBoolean()).isTrue();
                assertThat(c.has("tests")).isFalse();
            }
        }
        assertThat(randomAirside).isTrue();
        assertThat(preEmployment).isTrue();
        assertThat(stats.toString()).doesNotContain("E1004").doesNotContain("E1009");
        mvc.perform(get("/api/v1/testing/statistics").with(COLLECTOR)).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/testing/statistics").with(HR_OPS)).andExpect(status().isForbidden());

        MvcResult csv = mvc.perform(get("/api/v1/testing/reports/annual?year=" + YEAR + "&format=csv").with(DAM)).andExpect(status().isOk()).andReturn();
        String text = csv.getResponse().getContentAsString();
        assertThat(text).startsWith("year,reason,roleGroup,tests,verifiedPositive,refusals,positiveRatePct,suppressed")
                .contains("RANDOM,AIRSIDE_DRIVER").doesNotContain("E1004").doesNotContain("Rami");
        MvcResult pdf = mvc.perform(get("/api/v1/testing/reports/annual?year=" + YEAR).with(CMO)).andExpect(status().isOk()).andReturn();
        assertThat(pdf.getResponse().getContentType()).isEqualTo("application/pdf");
        assertThat(new String(pdf.getResponse().getContentAsByteArray(), 0, 4, java.nio.charset.StandardCharsets.US_ASCII)).isEqualTo("%PDF");
        mvc.perform(get("/api/v1/testing/reports/annual").with(SAFETY)).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/testing/reports/annual?format=xml").with(CMO)).andExpect(status().isBadRequest());
    }
}
