package com.wellnessz.app;

import static com.wellnessz.app.PlatformIntegrationTest.user;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;

import com.wellnessz.casekernel.support.AppointmentStatusListener;
import com.wellnessz.casekernel.support.CaseCostRecorder;
import com.wellnessz.casekernel.support.ExternalReferralGateway;
import com.wellnessz.casekernel.support.ReferralStatusListener;
import com.wellnessz.e04.plan.PlanService;
import com.wellnessz.e04.referral.EReferralService;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Acceptance tests for E04 – treatment plans, e-referrals incl. the partner portal (own organisation only, consent),
 * provider network, pre-authorisation with the TPA adjuster, invoices and the case cost ledger SPI, clinic medication,
 * outcomes with the closure guard, telehealth, the ExternalReferralGateway (IME) and role denials.
 */
@Import(Increment4TreatmentIntegrationTest.TestSpis.class)
class Increment4TreatmentIntegrationTest extends IntegrationTestBase {

    /** Ledger stand-in, registered only when no CaseCostRecorder implementation (E08) is on the classpath. */
    static class RecordingCostRecorder implements CaseCostRecorder {
        static final List<CostEntry> ENTRIES = new CopyOnWriteArrayList<>();

        @Override
        public UUID record(String actor, CostEntry entry) {
            ENTRIES.add(entry);
            return UUID.randomUUID();
        }
    }

    /** Records status changes of referrals raised with sourceType TEST_IME. */
    static class RecordingReferralListener implements ReferralStatusListener {
        record Change(UUID referralId, String sourceRef, Status status, UUID reportDocumentId) {
        }

        static final List<Change> CHANGES = new CopyOnWriteArrayList<>();

        @Override
        public boolean supports(String sourceType) {
            return "TEST_IME".equals(sourceType);
        }

        @Override
        public void referralStatusChanged(UUID referralId, String sourceType, String sourceRef, Status status, UUID reportDocumentId) {
            CHANGES.add(new Change(referralId, sourceRef, status, reportDocumentId));
        }
    }

    @TestConfiguration
    static class TestSpis {
        @Bean
        @ConditionalOnMissingBean(CaseCostRecorder.class)
        CaseCostRecorder testCaseCostRecorder() {
            return new RecordingCostRecorder();
        }

        @Bean
        ReferralStatusListener testReferralStatusListener() {
            return new RecordingReferralListener();
        }
    }

    @Autowired
    MockMvc mvc;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    JsonMapper json;
    @Autowired
    PlanService plans;
    @Autowired
    EReferralService referrals;
    @Autowired
    ExternalReferralGateway gateway;
    @Autowired
    List<AppointmentStatusListener> appointmentListeners;
    @Autowired
    List<CaseCostRecorder> costRecorders;

    static final JwtRequestPostProcessor NURSE = user("nurse1", "N1", null, "OH_CLINICIAN");
    static final JwtRequestPostProcessor PHYSICIAN = user("physician1", "PH1", null, "OH_PHYSICIAN");
    static final JwtRequestPostProcessor CMO = user("cmo1", "CM1", null, "CMO");
    static final JwtRequestPostProcessor NURSING_HEAD = user("nursinghead1", "NH1", null, "NURSING_HEAD", "OH_CLINICIAN");
    static final JwtRequestPostProcessor OH_ADMIN = user("ohadmin1", null, null, "OH_ADMIN");
    static final JwtRequestPostProcessor CLAIMS = user("claims1", "S8701", "/AIRLINE", "CLAIMS_COORDINATOR");
    static final JwtRequestPostProcessor FINANCE = user("finance1", "S8704", "/AIRLINE", "FINANCE");
    static final JwtRequestPostProcessor PROCUREMENT = user("procurement1", "S8705", "/AIRLINE", "PROCUREMENT");
    static final JwtRequestPostProcessor PARTNER = user("partner1", "P9101", "/PARTNERS/CITYHOSP", "PARTNER_PROVIDER");
    static final JwtRequestPostProcessor PHYSIO = user("physio1", "X8706", "/PARTNERS/PHYSIOPLUS", "PARTNER_PROVIDER");
    static final JwtRequestPostProcessor ADJUSTER = user("adjuster1", "X8702", "/PARTNERS/GULFTPA", "TPA_ADJUSTER");
    static final JwtRequestPostProcessor OTHER_ADJUSTER = user("adjuster9", "X8799", "/PARTNERS/AVIA_INSURE", "TPA_ADJUSTER");
    static final JwtRequestPostProcessor MANAGER = user("manager1", "M2001", "/AIRLINE/CATERING", "MANAGER");
    /** Partner hospital system bound to its organisation (org_scope /PARTNERS/CITYHOSP). */
    static final JwtRequestPostProcessor INTEGRATION = user("cityhosp-system", null, "/PARTNERS/CITYHOSP", "INTEGRATION");
    static final JwtRequestPostProcessor PHYSIO_SYSTEM = user("physioplus-system", null, "/PARTNERS/PHYSIOPLUS", "INTEGRATION");
    /** Insurer / TPA system of the payer GULFTPA. */
    static final JwtRequestPostProcessor PAYER_SYSTEM = user("gulftpa-system", null, "/PARTNERS/GULFTPA", "INTEGRATION");
    /** Machine client not bound to any partner organisation (e.g. the absence feed). */
    static final JwtRequestPostProcessor UNBOUND_SYSTEM = user("absence-feed", null, null, "INTEGRATION");
    static final LocalDate TODAY = LocalDate.now(ZoneOffset.UTC);
    static final byte[] PDF = "%PDF-1.4\n1 0 obj << >> endobj\ntrailer << >>\n%%EOF".getBytes(StandardCharsets.US_ASCII);

    // ------------------------------------------------------------------ helpers

    JsonNode body(MvcResult r) throws Exception {
        String s = r.getResponse().getContentAsString();
        return s.isEmpty() ? json.readTree("null") : json.readTree(s);
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

    String problem(MockHttpServletRequestBuilder req, JwtRequestPostProcessor who, String content, int expected) throws Exception {
        MvcResult r = call(req, who, content).andReturn();
        assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isEqualTo(expected);
        return body(r).path("code").asString();
    }

    /** New employee (GROUND, LHR) with an INJURY case in TREATMENT. Returns {employeeId, caseId}. */
    String[] employeeWithCase() {
        String emp = "T4" + UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        jdbc.update("""
                insert into masterdata.employee (employee_id, display_name, email, phone, subsidiary_code, department_code, cost_centre, job_code,
                                                 location_code, badge_id, updated_at)
                values (?, ?, ?, '+44 7700 900123', 'GND', 'GROUND', 'CC-GND-400', 'RAMP_AGENT', 'LHR', ?, now())""",
                emp, "Test " + emp, emp.toLowerCase() + "@wellnessz.local", "B-" + emp);
        String caseId = UUID.randomUUID().toString();
        jdbc.update("""
                insert into casemgmt.oh_case (id, case_ref, employee_id, employee_display_name, org_unit_code, org_unit_path, subsidiary_code,
                                              cost_centre, job_code, location_code, category, case_type, source, status, classification,
                                              fitness_status, urgency, owner_username, early_intervention, occurred_on, created_at, updated_at)
                values (?::uuid, ?, ?, ?, 'GROUND', '/AIRLINE/OPS/GROUND', 'GND', 'CC-GND-400', 'RAMP_AGENT', 'LHR', 'INJURY', 'INJURY',
                        'WALK_IN', 'TREATMENT', 'CLINICAL', 'NOT_ASSESSED', 'ROUTINE', 'nurse1', false, ?, now(), now())""",
                caseId, "T4-" + emp, emp, "Test " + emp, TODAY.minusDays(3));
        return new String[] {emp, caseId};
    }

    String consent(String employeeId, String purpose) {
        String id = UUID.randomUUID().toString();
        jdbc.update("insert into platform.consent (id, employee_id, purpose, scope, status, notice_version, granted_at, captured_by) "
                + "values (?::uuid, ?, ?, 'test', 'GRANTED', '2026.1', now(), 'test')", id, employeeId, purpose);
        return id;
    }

    long notifications(String address, String template) {
        return jdbc.queryForObject("select count(*) from platform.notification where address = ? and template_key = ?", Long.class, address, template);
    }

    List<String> texts(JsonNode array, String field) {
        List<String> out = new ArrayList<>();
        array.forEach(n -> out.add(n.path(field).asString()));
        return out;
    }

    static JwtRequestPostProcessor employee(String employeeId) {
        return user("emp-" + employeeId, employeeId, "/AIRLINE/OPS/GROUND", "EMPLOYEE");
    }

    MockMultipartHttpServletRequestBuilder upload(String path) {
        MockMultipartHttpServletRequestBuilder b = multipart(path);
        b.file(new MockMultipartFile("file", "report.pdf", "application/pdf", PDF));
        return b;
    }

    String referralCommand(String service, String provider, String dataSet) {
        return """
                {"serviceCode":"%s","urgency":"URGENT","providerCode":"%s","reason":"Persistent low back pain after lifting","question":"Please assess and advise on management",
                 "dataSet":%s,"channel":"PORTAL","attachDocumentIds":[]}""".formatted(service, provider, dataSet);
    }

    // =================================================================== F01 treatment plans

    @Test
    void f01TreatmentPlanFollowUpsVarianceAndEmployeeView() throws Exception {
        String[] ec = employeeWithCase();
        String emp = ec[0];
        String caseId = ec[1];
        JsonNode templates = ok(get("/api/v1/treatment/plan-templates"), NURSE, null);
        assertThat(texts(templates, "code")).contains("BACK_STRAIN", "SHOULDER_STRAIN", "WRIST_SPRAIN", "KNEE_SPRAIN", "HAND_LACERATION",
                "CONCUSSION_MILD", "WORK_STRESS", "NOISE_HEARING");
        call(get("/api/v1/treatment/plan-templates"), MANAGER, null).andExpect(status().isForbidden());

        // template expansion when only the template is given
        JsonNode draft = ok(post("/api/v1/cases/" + caseId + "/treatment-plans"), NURSE, "{\"templateCode\":\"BACK_STRAIN\"}");
        assertThat(draft.path("status").asString()).isEqualTo("DRAFT");
        assertThat(draft.path("goals").size()).isEqualTo(3);
        assertThat(draft.path("followUps").size()).isEqualTo(3);
        assertThat(draft.path("guideline").path("expectedDays").asInt()).isEqualTo(21);
        ok(post("/api/v1/treatment-plans/" + draft.path("id").asString() + "/status"), NURSE, "{\"status\":\"CANCELLED\"}");

        String body = """
                {"goals":["Return to full ramp duties"],"interventions":[{"type":"PHYSIOTHERAPY","description":"Physio 1x weekly","frequency":"weekly",
                 "providerCode":"PHYSIOPLUS"}],"followUps":[{"dueDate":"%s","type":"OH_REVIEW","siteCode":"LHR-AIRSIDE","book":true},
                 {"dueDate":"%s","type":"PHONE"}],"expectedRecoveryDays":10,"varianceThresholdPct":20}"""
                .formatted(TODAY.plusDays(3), TODAY.plusDays(10));
        call(post("/api/v1/cases/" + caseId + "/treatment-plans"), CLAIMS, body).andExpect(status().isForbidden());
        JsonNode plan = ok(post("/api/v1/cases/" + caseId + "/treatment-plans?activate=true"), NURSE, body);
        String planId = plan.path("id").asString();
        assertThat(plan.path("status").asString()).isEqualTo("ACTIVE");
        assertThat(plan.path("caseRef").asString()).startsWith("T4-");
        assertThat(plan.path("expectedRecoveryDate").asString()).isEqualTo(TODAY.minusDays(3).plusDays(10).toString());
        assertThat(problem(post("/api/v1/cases/" + caseId + "/treatment-plans?activate=true"), NURSE, body, 409)).isEqualTo("active_plan_exists");
        assertThat(jdbc.queryForObject("select count(*) from casemgmt.case_task where case_id = ?::uuid and task_type = 'FOLLOW_UP'", Long.class,
                caseId)).isEqualTo(2L);
        assertThat(notifications(emp.toLowerCase() + "@wellnessz.local", "treatment.follow-up")).isEqualTo(1L);
        String fu1 = plan.path("followUps").get(0).path("id").asString();
        String fu2 = plan.path("followUps").get(1).path("id").asString();

        // employee: plain-language view without OH-only fields, confirm / decline once
        JsonNode mine = ok(get("/api/v1/me/treatment-plans"), employee(emp), null);
        assertThat(mine.size()).isEqualTo(1);
        assertThat(mine.get(0).has("caseId")).isFalse();
        assertThat(mine.get(0).has("variance")).isFalse();
        assertThat(mine.get(0).path("interventions").get(0).path("providerName").asString()).isEqualTo("PhysioPlus Rehabilitation");
        JsonNode resp = ok(post("/api/v1/me/treatment-plans/" + planId + "/follow-ups/" + fu2 + "/response"), employee(emp),
                "{\"response\":\"DECLINE\",\"reason\":\"On a training course that day\"}");
        assertThat(resp.path("status").asString()).isEqualTo("DECLINED");
        assertThat(problem(post("/api/v1/me/treatment-plans/" + planId + "/follow-ups/" + fu2 + "/response"), employee(emp),
                "{\"response\":\"CONFIRM\"}", 422)).isEqualTo("response_not_allowed");
        assertThat(problem(post("/api/v1/me/treatment-plans/" + planId + "/follow-ups/" + fu1 + "/response"), employee(emp),
                "{\"response\":\"DECLINE\",\"reason\":\"My diabetes clinic is that day\"}", 422)).isEqualTo("clinical_content_not_allowed");
        problem(post("/api/v1/me/treatment-plans/" + planId + "/follow-ups/" + fu1 + "/response"), employee("E1001"), "{\"response\":\"CONFIRM\"}", 404);

        // scheduling callback: a no-show of the booked follow-up marks it MISSED
        AppointmentStatusListener listener = appointmentListeners.stream().filter(l -> l.supports(PlanService.SOURCE_TYPE)).findFirst().orElseThrow();
        listener.appointmentStatusChanged(UUID.randomUUID(), emp, PlanService.SOURCE_TYPE, fu1, AppointmentStatusListener.Status.NO_SHOW);
        assertThat(jdbc.queryForObject("select status from treatment.follow_up where id = ?::uuid", String.class, fu1)).isEqualTo("MISSED");

        // new version supersedes the old one; follow-ups move along
        JsonNode v2 = ok(put("/api/v1/treatment-plans/" + planId), NURSE, "{\"goals\":[\"Return to full ramp duties\",\"Independent exercises\"]}");
        assertThat(v2.path("version").asInt()).isEqualTo(2);
        assertThat(v2.path("history").get(0).path("status").asString()).isEqualTo("SUPERSEDED");
        assertThat(v2.path("followUps").size()).isEqualTo(2);
        String planV2 = v2.path("id").asString();
        assertThat(ok(get("/api/v1/cases/" + caseId + "/treatment-plans"), NURSE, null).size()).isEqualTo(2); // cancelled draft + v2

        JsonNode doc = ok(post("/api/v1/treatment-plans/" + planV2 + "/print"), NURSE, null);
        assertThat(doc.path("type").asString()).isEqualTo("TREATMENT_PLAN");
        assertThat(doc.path("status").asString()).isEqualTo("SIGNED");
        assertThat(texts(ok(get("/api/v1/me/documents"), employee(emp), null), "id")).contains(doc.path("id").asString());

        // variance: injury 30 days ago vs 10 expected days → alert, acknowledge, escalate
        jdbc.update("update casemgmt.oh_case set occurred_on = ? where id = ?::uuid", TODAY.minusDays(30), caseId);
        assertThat(plans.runVariance()).isPositive();
        JsonNode alerts = ok(get("/api/v1/treatment/variance-alerts?status=OPEN"), NURSE, null);
        JsonNode alert = null;
        for (JsonNode a : alerts) {
            if (a.path("planId").asString().equals(planV2)) {
                alert = a;
            }
        }
        assertThat(alert).isNotNull();
        assertThat(alert.path("pctOver").decimalValue()).isEqualByComparingTo("200.0");
        assertThat(notifications("nurse1@wellnessz.local", "treatment.variance")).isPositive();
        assertThat(plans.runVariance()).as("one open alert per plan").isZero();
        String alertId = alert.path("id").asString();
        assertThat(ok(post("/api/v1/treatment/variance-alerts/" + alertId + "/acknowledge"), NURSE, "{}").path("status").asString())
                .isEqualTo("ACKNOWLEDGED");
        assertThat(ok(post("/api/v1/treatment/variance-alerts/" + alertId + "/escalate"), NURSE, "{\"note\":\"needs review\"}").path("status")
                .asString()).isEqualTo("ESCALATED");
        assertThat(notifications("cmo1@wellnessz.local", "treatment.variance")).isPositive();

        ok(post("/api/v1/treatment-plans/" + planV2 + "/follow-ups/" + fu2), NURSE, "{\"status\":\"DONE\",\"note\":\"Phone review done\"}");
        assertThat(ok(post("/api/v1/treatment-plans/" + planV2 + "/status"), NURSE, "{\"status\":\"COMPLETED\"}").path("status").asString())
                .isEqualTo("COMPLETED");
        assertThat(problem(post("/api/v1/treatment-plans/" + planV2 + "/status"), NURSE, "{\"status\":\"ACTIVE\"}", 422)).isEqualTo("invalid_transition");
    }

    // =================================================================== F02 referrals & partner portal

    @Test
    void f02ReferralSendPartnerPortalFlowAndDenials() throws Exception {
        String[] ec = employeeWithCase();
        String emp = ec[0];
        String caseId = ec[1];
        JsonNode services = ok(get("/api/v1/referrals/services"), CLAIMS, null);
        assertThat(texts(services, "code")).contains("ORTHOPAEDICS", "PHYSIOTHERAPY", "IMAGING_MRI", "IMAGING_XRAY", "CARDIOLOGY", "OPHTHALMOLOGY",
                "ENT_AUDIOLOGY", "DERMATOLOGY", "MENTAL_HEALTH_THERAPY", "PSYCHIATRY", "LAB_TESTS", "TELEHEALTH_SPECIALIST", "ASSISTANCE_LAYOVER", "IME");

        assertThat(problem(post("/api/v1/cases/" + caseId + "/referrals"), NURSE, referralCommand("ORTHOPAEDICS", "MINDCARE", "{}"), 422))
                .isEqualTo("provider_not_suitable");
        assertThat(problem(post("/api/v1/cases/" + caseId + "/referrals"), NURSE, referralCommand("ORTHOPAEDICS", "CITYHOSP", "{\"colour\":\"red\"}"), 400))
                .isEqualTo("unknown_field");
        JsonNode r = ok(post("/api/v1/cases/" + caseId + "/referrals"), NURSE, referralCommand("ORTHOPAEDICS", "CITYHOSP", "{\"bodyPart\":\"LOWER_BACK\"}"));
        String id = r.path("id").asString();
        assertThat(r.path("status").asString()).isEqualTo("DRAFT");
        assertThat(r.path("ref").asString()).matches("RFL-\\d{4}-\\d{6}");
        assertThat(r.path("contracted").asBoolean()).isTrue();
        assertThat(r.path("preferred").asBoolean()).isTrue();

        // consent first, then the data set
        MvcResult incomplete = call(post("/api/v1/referrals/" + id + "/send"), NURSE, null).andReturn();
        assertThat(incomplete.getResponse().getStatus()).isEqualTo(422);
        assertThat(body(incomplete).path("code").asString()).isEqualTo("data_set_incomplete");
        assertThat(body(incomplete).path("field").asString()).isEqualTo("injuryDate");
        ok(put("/api/v1/referrals/" + id), NURSE, referralCommand("ORTHOPAEDICS", "CITYHOSP",
                "{\"bodyPart\":\"LOWER_BACK\",\"injuryDate\":\"%s\"}".formatted(TODAY.minusDays(3))));
        assertThat(problem(post("/api/v1/referrals/" + id + "/send"), NURSE, null, 422)).isEqualTo("consent_required");
        String consentId = consent(emp, "TREATMENT_SHARING_PARTNER");
        assertThat(texts(ok(get("/api/v1/cases/" + caseId + "/consents?purpose=TREATMENT_SHARING_PARTNER"), NURSE, null), "id")).containsExactly(consentId);
        call(get("/api/v1/cases/" + caseId + "/consents?purpose=TREATMENT_SHARING_PARTNER"), CLAIMS, null).andExpect(status().isForbidden());
        assertThat(ok(get("/api/v1/cases/" + caseId + "/consents?purpose=INSURER_SHARING"), CLAIMS, null).size()).isZero();
        call(get("/api/v1/cases/" + caseId + "/consents?purpose=INSURER_SHARING"), PARTNER, null).andExpect(status().isForbidden());
        JsonNode sent = ok(post("/api/v1/referrals/" + id + "/send"), NURSE, null);
        assertThat(sent.path("status").asString()).isEqualTo("SENT");
        assertThat(sent.path("letterDocumentId").asString()).isNotBlank();
        assertThat(sent.path("reportDueAt").asString()).isNotBlank();
        assertThat(sent.path("fhir").path("resourceType").asString()).isEqualTo("ServiceRequest");
        assertThat(sent.path("consentId").asString()).isEqualTo(consentId);
        assertThat(notifications("referrals@cityhosp.example", "referral.sent")).isPositive();
        call(put("/api/v1/referrals/" + id), NURSE, referralCommand("ORTHOPAEDICS", "CITYHOSP", "{}")).andExpect(status().is(422));

        // partner of the addressed organisation: list (first read → disclosure log), documents
        JsonNode inbox = ok(get("/api/v1/partner/referrals?status=SENT"), PARTNER, null);
        JsonNode item = null;
        for (JsonNode n : inbox) {
            if (n.path("id").asString().equals(id)) {
                item = n;
            }
        }
        assertThat(item).isNotNull();
        assertThat(item.path("patient").path("employeeId").asString()).isEqualTo(emp);
        assertThat(item.path("patient").path("phone").asString()).isNotBlank();
        assertThat(item.path("dataSet").path("bodyPart").asString()).isEqualTo("LOWER_BACK");
        assertThat(item.has("caseId")).isFalse();
        assertThat(jdbc.queryForObject("select count(*) from platform.disclosure_log where resource_type = 'REFERRAL' and resource_id = ?",
                Long.class, id)).isEqualTo(1L);
        ok(get("/api/v1/partner/referrals"), PARTNER, null);
        assertThat(jdbc.queryForObject("select count(*) from platform.disclosure_log where resource_type = 'REFERRAL' and resource_id = ?",
                Long.class, id)).as("first read only").isEqualTo(1L);
        assertThat(texts(ok(get("/api/v1/partner/documents"), PARTNER, null), "id")).contains(sent.path("letterDocumentId").asString());

        // another organisation: 404 everywhere
        assertThat(texts(ok(get("/api/v1/partner/referrals"), PHYSIO, null), "id")).doesNotContain(id);
        problem(get("/api/v1/partner/referrals/" + id), PHYSIO, null, 404);
        problem(post("/api/v1/partner/referrals/" + id + "/accept"), PHYSIO, null, 404);
        call(get("/api/v1/partner/referrals"), NURSE, null).andExpect(status().isForbidden());
        call(get("/api/v1/referrals"), PARTNER, null).andExpect(status().isForbidden());
        call(get("/api/v1/partner/referrals"), ADJUSTER, null).andExpect(status().isForbidden());

        // accept → appointment (screened) → seen → report → invoice
        assertThat(ok(post("/api/v1/partner/referrals/" + id + "/accept"), PARTNER, null).path("status").asString()).isEqualTo("ACCEPTED");
        String at = Instant.now().plus(2, ChronoUnit.DAYS).truncatedTo(ChronoUnit.HOURS).toString();
        assertThat(problem(post("/api/v1/partner/referrals/" + id + "/appointment"), PARTNER,
                "{\"appointmentAt\":\"%s\",\"instructions\":\"Bring your MRI and medication list\"}".formatted(at), 422))
                .isEqualTo("clinical_content_not_allowed");
        JsonNode sched = ok(post("/api/v1/partner/referrals/" + id + "/appointment"), PARTNER,
                "{\"appointmentAt\":\"%s\",\"location\":\"Outpatients, level 2\",\"instructions\":\"Please arrive 15 minutes early\"}".formatted(at));
        assertThat(sched.path("status").asString()).isEqualTo("SCHEDULED");
        assertThat(notifications(emp.toLowerCase() + "@wellnessz.local", "referral.appointment")).isEqualTo(1L);
        assertThat(ok(post("/api/v1/partner/referrals/" + id + "/seen"), PARTNER, null).path("status").asString()).isEqualTo("SEEN");
        MvcResult rep = mvc.perform(upload("/api/v1/partner/referrals/" + id + "/report").param("summary", "Mechanical back pain").with(PARTNER)).andReturn();
        assertThat(rep.getResponse().getStatus()).as(rep.getResponse().getContentAsString()).isEqualTo(200);
        assertThat(body(rep).path("status").asString()).isEqualTo("REPORT_RECEIVED");
        MvcResult inv = mvc.perform(upload("/api/v1/partner/referrals/" + id + "/invoice").param("invoiceNumber", "CH-2026-77")
                .param("amount", "280.00").param("currency", "GBP").param("serviceDate", TODAY.toString()).with(PARTNER)).andReturn();
        assertThat(inv.getResponse().getStatus()).as(inv.getResponse().getContentAsString()).isEqualTo(201);
        assertThat(body(inv).path("status").asString()).isEqualTo("RECEIVED");
        JsonNode partnerDetail = ok(get("/api/v1/partner/referrals/" + id), PARTNER, null);
        assertThat(texts(partnerDetail.path("history"), "status")).contains("DRAFT", "SENT", "ACCEPTED", "SCHEDULED", "SEEN", "REPORT_RECEIVED");

        // OH side
        JsonNode detail = ok(get("/api/v1/referrals/" + id), NURSE, null);
        assertThat(detail.path("reportDocumentId").asString()).isNotBlank();
        assertThat(detail.path("reportSummary").asString()).isEqualTo("Mechanical back pain");
        assertThat(jdbc.queryForObject("select type from docs.document where id = ?::uuid", String.class, detail.path("reportDocumentId").asString()))
                .isEqualTo("PROVIDER_REPORT");
        assertThat(texts(ok(get("/api/v1/referrals?status=REPORT_RECEIVED&providerCode=CITYHOSP"), NURSE, null), "id")).contains(id);
        assertThat(texts(ok(get("/api/v1/cases/" + caseId + "/referrals"), NURSE, null), "id")).containsExactly(id);
        assertThat(ok(post("/api/v1/referrals/" + id + "/rating"), NURSE, "{\"rating\":4,\"comment\":\"Clear report\"}").path("rating").asInt())
                .isEqualTo(4);
        assertThat(ok(get("/api/v1/integration/referrals/" + id + "/fhir"), INTEGRATION, null).path("status").asString()).isEqualTo("completed");
        // other organisations' systems and unbound machine clients cannot read the referral
        call(get("/api/v1/integration/referrals/" + id + "/fhir"), PAYER_SYSTEM, null).andExpect(status().isNotFound());
        call(get("/api/v1/integration/referrals/" + id + "/fhir"), UNBOUND_SYSTEM, null).andExpect(status().isNotFound());
        call(get("/api/v1/integration/referrals/" + id + "/fhir"), NURSE, null).andExpect(status().isForbidden());
        assertThat(ok(get("/api/v1/cases/" + caseId + "/invoices"), CLAIMS, null).size()).isEqualTo(1);

        // consent withdrawn → the partner no longer sees the referral
        jdbc.update("update platform.consent set status = 'WITHDRAWN', withdrawn_at = now() where id = ?::uuid", consentId);
        problem(get("/api/v1/partner/referrals/" + id), PARTNER, null, 404);

        // chart contribution (treatment section)
        JsonNode chart = ok(get("/api/v1/employees/" + emp + "/chart"), NURSE, null);
        assertThat(chart.toString()).contains("openReferrals");
        assertThat(ok(get("/api/v1/treatment/kpis"), CMO, null).path("referralsSent").asInt()).isPositive();
        call(get("/api/v1/treatment/kpis"), NURSE, null).andExpect(status().isForbidden());
    }

    @Test
    void f02ChaseEscalationRemindCancelAndFhirReport() throws Exception {
        String[] ec = employeeWithCase();
        String emp = ec[0];
        String caseId = ec[1];
        consent(emp, "TREATMENT_SHARING_PARTNER");
        String ds = "{\"bodyPart\":\"KNEE\",\"sessions\":\"6\"}";
        String a = ok(post("/api/v1/cases/" + caseId + "/referrals"), NURSE, referralCommand("PHYSIOTHERAPY", "PHYSIOPLUS", ds)).path("id").asString();
        ok(post("/api/v1/referrals/" + a + "/send"), NURSE, null);
        String b = ok(post("/api/v1/cases/" + caseId + "/referrals"), NURSE, referralCommand("PHYSIOTHERAPY", "PHYSIOPLUS", ds)).path("id").asString();
        JsonNode sentB = ok(post("/api/v1/referrals/" + b + "/send"), NURSE, null);

        // report overdue → chase, then escalate after max chases
        jdbc.update("update treatment.referral set report_due_at = now() - interval '1 day' where id = ?::uuid", a);
        long before = notifications("bookings@physioplus.example", "referral.chase");
        referrals.chase();
        assertThat(jdbc.queryForObject("select chase_count from treatment.referral where id = ?::uuid", Integer.class, a)).isEqualTo(1);
        assertThat(notifications("bookings@physioplus.example", "referral.chase")).isEqualTo(before + 1);
        referrals.chase();
        assertThat(jdbc.queryForObject("select chase_count from treatment.referral where id = ?::uuid", Integer.class, a)).as("interval").isEqualTo(1);
        jdbc.update("update treatment.referral set chase_count = 2, last_chased_at = now() - interval '3 days' where id = ?::uuid", a);
        referrals.chase();
        assertThat(jdbc.queryForObject("select escalated from treatment.referral where id = ?::uuid", Boolean.class, a)).isTrue();
        assertThat(notifications("nurse1@wellnessz.local", "referral.overdue-team")).isPositive();
        JsonNode overdue = ok(get("/api/v1/referrals?overdue=true&mine=true"), NURSE, null);
        assertThat(texts(overdue, "id")).contains(a).doesNotContain(b);

        assertThat(ok(post("/api/v1/referrals/remind"), NURSE, "{\"referralIds\":[\"%s\",\"%s\"]}".formatted(a, b)).path("reminded").asInt()).isEqualTo(2);
        call(post("/api/v1/referrals/remind"), NURSE, "{\"referralIds\":[]}").andExpect(status().isBadRequest());

        // provider report through FHIR DocumentReference
        ok(post("/api/v1/partner/referrals/" + a + "/accept"), PHYSIO, null);
        String docRef = """
                {"resourceType":"DocumentReference","status":"current","description":"Physiotherapy discharge summary",
                 "context":{"related":[{"reference":"ServiceRequest/%s"}]},
                 "content":[{"attachment":{"contentType":"application/pdf","title":"Discharge","data":"%s"}}]}"""
                .formatted(a, Base64.getEncoder().encodeToString(PDF));
        // only the referral's own provider system may post the report (CITYHOSP's system gets 404)
        call(post("/api/v1/integration/referrals/fhir/document-reference"), INTEGRATION, docRef).andExpect(status().isNotFound());
        JsonNode res = ok(post("/api/v1/integration/referrals/fhir/document-reference"), PHYSIO_SYSTEM, docRef);
        assertThat(res.path("status").asString()).isEqualTo("REPORT_RECEIVED");
        assertThat(res.path("documentId").asString()).isNotBlank();
        problem(post("/api/v1/integration/referrals/fhir/document-reference"), PHYSIO_SYSTEM, "{\"resourceType\":\"Patient\"}", 400);

        // cancel revokes the partner shares
        String letter = sentB.path("letterDocumentId").asString();
        assertThat(texts(ok(get("/api/v1/partner/documents"), PHYSIO, null), "id")).contains(letter);
        assertThat(ok(post("/api/v1/referrals/" + b + "/cancel"), NURSE, "{\"reason\":\"Seen privately\"}").path("status").asString())
                .isEqualTo("CANCELLED");
        assertThat(texts(ok(get("/api/v1/partner/documents"), PHYSIO, null), "id")).doesNotContain(letter);
        assertThat(problem(post("/api/v1/partner/referrals/" + b + "/accept"), PHYSIO, null, 422)).isEqualTo("invalid_transition");
        call(post("/api/v1/referrals/" + b + "/cancel"), CLAIMS, "{\"reason\":\"x\"}").andExpect(status().isForbidden());
    }

    @Test
    void imeThroughExternalReferralGatewayNotifiesListeners() throws Exception {
        String[] ec = employeeWithCase();
        String emp = ec[0];
        String caseId = ec[1];
        consent(emp, "TREATMENT_SHARING_PARTNER");
        String claimRef = "CLM-TEST-" + UUID.randomUUID().toString().substring(0, 6);
        assertThat(gateway.requestReferral("claims1", new ExternalReferralGateway.ReferralRequest(UUID.fromString(caseId), emp, "NO_SUCH",
                null, "ROUTINE", "IME", "TEST_IME", claimRef))).isEmpty();
        Optional<ExternalReferralGateway.ReferralRef> ref = gateway.requestReferral("claims1", new ExternalReferralGateway.ReferralRequest(
                UUID.fromString(caseId), emp, "IME", "CITYHOSP", "ROUTINE", "Independent medical examination requested by the insurer",
                "TEST_IME", claimRef));
        assertThat(ref).isPresent();
        assertThat(ref.get().status()).isEqualTo("DRAFT");
        String id = ref.get().referralId().toString();
        JsonNode draft = ok(get("/api/v1/referrals/" + id), NURSE, null);
        assertThat(draft.path("sourceType").asString()).isEqualTo("TEST_IME");
        ok(put("/api/v1/referrals/" + id), NURSE, referralCommand("IME", "CITYHOSP",
                "{\"questions\":\"Prognosis for return to ramp duties?\",\"injuryDate\":\"%s\"}".formatted(TODAY.minusDays(3))));
        ok(post("/api/v1/referrals/" + id + "/send"), NURSE, null);
        ok(post("/api/v1/partner/referrals/" + id + "/accept"), PARTNER, null);
        mvc.perform(upload("/api/v1/partner/referrals/" + id + "/report").with(PARTNER)).andExpect(status().isOk());
        List<RecordingReferralListener.Change> changes = RecordingReferralListener.CHANGES.stream()
                .filter(c -> c.referralId().toString().equals(id)).toList();
        assertThat(changes.stream().map(c -> c.status().name()).toList()).containsExactly("DRAFT", "SENT", "ACCEPTED", "REPORT_RECEIVED");
        assertThat(changes.get(3).reportDocumentId()).isNotNull();
        assertThat(changes.get(0).sourceRef()).isEqualTo(claimRef);
    }

    // =================================================================== F03 providers

    @Test
    void f03ProviderDirectoryContractsGapsAndScorecards() throws Exception {
        JsonNode all = ok(get("/api/v1/providers"), PROCUREMENT, null);
        assertThat(all.size()).isGreaterThanOrEqualTo(20); // 20 seeded in V13, more partners added by later increments (V16 EAP, laboratory)
        assertThat(texts(all, "code")).doesNotContain("GULFTPA", "AUTH_OSH");
        java.util.Map<String, String> statuses = new java.util.HashMap<>();
        all.forEach(p -> statuses.put(p.path("code").asString(), p.path("contract").isMissingNode() || p.path("contract").isNull() ? "NONE"
                : p.path("contract").path("status").asString()));
        assertThat(statuses).containsEntry("CITYHOSP", "ACTIVE").containsEntry("REHAB_HUB", "EXPIRING").containsEntry("JFK_URGENT", "EXPIRED")
                .containsEntry("HKG_CLINIC", "NONE").containsEntry("OCC_PSYCH", "NONE");
        assertThat(texts(ok(get("/api/v1/providers?contractStatus=EXPIRING"), NURSE, null), "code")).containsExactly("REHAB_HUB");
        assertThat(texts(ok(get("/api/v1/providers?serviceCode=PHYSIOTHERAPY&city=Hounslow"), CLAIMS, null), "code")).containsExactly("PHYSIOPLUS");
        assertThat(ok(get("/api/v1/providers?preferredOnly=true"), NURSE, null).size()).isLessThan(20);
        call(get("/api/v1/providers"), user("emp", "E1001", null, "EMPLOYEE"), null).andExpect(status().isForbidden());

        JsonNode city = ok(get("/api/v1/providers/CITYHOSP"), NURSE, null);
        assertThat(city.path("rates").size()).isPositive();
        assertThat(city.path("sla").path("reportDays").asInt()).isEqualTo(7);
        assertThat(city.path("contracts").size()).isEqualTo(1);
        problem(get("/api/v1/providers/GULFTPA"), NURSE, null, 404);

        call(put("/api/v1/providers/HKG_CLINIC"), NURSE, "{\"preferred\":true}").andExpect(status().isForbidden());
        JsonNode upd = ok(put("/api/v1/providers/HKG_CLINIC"), PROCUREMENT,
                "{\"services\":[\"ASSISTANCE_LAYOVER\",\"IMAGING_XRAY\"],\"languages\":[\"en\",\"zh\"],\"preferred\":true,"
                        + "\"accreditation\":[{\"body\":\"HK DH\",\"validUntil\":\"2027-12-31\"}],\"coverage\":\"LAYOVER\"}");
        assertThat(upd.path("preferred").asBoolean()).isTrue();
        assertThat(upd.path("accreditation").get(0).path("body").asString()).isEqualTo("HK DH");
        call(post("/api/v1/providers/HKG_CLINIC/contracts"), OH_ADMIN, "{\"ref\":\"CT-X\",\"start\":\"%s\",\"end\":\"%s\"}"
                .formatted(TODAY, TODAY.plusDays(10))).andExpect(status().isForbidden());
        JsonNode contracted = ok(post("/api/v1/providers/HKG_CLINIC/contracts"), PROCUREMENT, """
                {"ref":"CT-HKG-2026","start":"%s","end":"%s","rates":[{"serviceCode":"ASSISTANCE_LAYOVER","price":{"amount":"175.00","currency":"GBP"}}],
                 "sla":{"appointmentDays":1,"urgentHours":12,"reportDays":3}}""".formatted(TODAY.minusDays(1), TODAY.plusYears(1)));
        assertThat(contracted.path("contract").path("status").asString()).isEqualTo("ACTIVE");
        ok(post("/api/v1/providers/HKG_CLINIC/contracts"), PROCUREMENT, """
                {"ref":"CT-HKG-2026B","start":"%s","end":"%s","rates":[]}""".formatted(TODAY.minusDays(1), TODAY.plusYears(2)));
        JsonNode hkg = ok(get("/api/v1/providers/HKG_CLINIC"), PROCUREMENT, null);
        assertThat(hkg.path("contract").path("ref").asString()).isEqualTo("CT-HKG-2026B");
        assertThat(texts(hkg.path("contracts"), "status")).containsExactly("ACTIVE", "SUPERSEDED");

        JsonNode gaps = ok(get("/api/v1/providers/network-gaps"), PROCUREMENT, null);
        assertThat(gaps.size()).isPositive();
        assertThat(gaps.toString()).contains("New York").contains("No active contract");
        call(get("/api/v1/providers/network-gaps"), NURSE, null).andExpect(status().isForbidden());

        String month = java.time.YearMonth.now(ZoneOffset.UTC).toString();
        ok(post("/api/v1/providers/scorecards/recompute?month=" + month), PROCUREMENT, null);
        ok(get("/api/v1/providers/scorecards?month=" + month), CMO, null);
        JsonNode last = ok(get("/api/v1/providers/scorecards"), NURSING_HEAD, null);
        assertThat(texts(last, "providerCode")).contains("CITYHOSP", "PHYSIOPLUS");
        MvcResult csv = call(get("/api/v1/providers/scorecards/export"), PROCUREMENT, null).andReturn();
        assertThat(csv.getResponse().getContentType()).startsWith("text/csv");
        assertThat(csv.getResponse().getContentAsString()).startsWith("providerCode,providerName,month").contains("CITYHOSP");
        call(get("/api/v1/providers/scorecards"), NURSE, null).andExpect(status().isForbidden());
    }

    // =================================================================== F04 pre-authorisation, invoices, costs

    @Test
    void f04PreauthAdjusterDecisionInvoicesAndCosts() throws Exception {
        String[] ec = employeeWithCase();
        String emp = ec[0];
        String caseId = ec[1];
        String body = """
                {"payerCode":"GULFTPA","serviceCode":"IMAGING_MRI","providerCode":"LDN_IMAGING","estimate":{"amount":"450.00","currency":"GBP"}%s}""";
        assertThat(problem(post("/api/v1/cases/" + caseId + "/preauthorisations"), CLAIMS, body.formatted(",\"justification\":\"Disc prolapse\""), 400))
                .isEqualTo("justification_oh_only");
        assertThat(problem(post("/api/v1/cases/" + caseId + "/preauthorisations"), NURSE, body.formatted(""), 400)).isEqualTo("justification_required");
        assertThat(problem(post("/api/v1/cases/" + caseId + "/preauthorisations"), CLAIMS,
                body.formatted("").replace("GULFTPA", "CITYHOSP"), 422)).isEqualTo("invalid_payer");
        JsonNode pa = ok(post("/api/v1/cases/" + caseId + "/preauthorisations"), CLAIMS, body.formatted(""));
        String paId = pa.path("id").asString();
        assertThat(pa.path("ref").asString()).matches("PA-\\d{4}-\\d{6}");
        assertThat(pa.path("status").asString()).isEqualTo("DRAFT");
        assertThat(problem(post("/api/v1/preauthorisations/" + paId + "/submit"), CLAIMS, null, 422)).isEqualTo("consent_required");
        consent(emp, "INSURER_SHARING");
        int costsBefore = RecordingCostRecorder.ENTRIES.size();
        JsonNode submitted = ok(post("/api/v1/preauthorisations/" + paId + "/submit"), CLAIMS, null);
        assertThat(submitted.path("status").asString()).isEqualTo("SUBMITTED");
        assertThat(submitted.path("slaDueAt").asString()).isNotBlank();
        String docId = submitted.path("documentId").asString();
        assertThat(jdbc.queryForObject("select count(*) from docs.document_share where document_id = ?::uuid and target = 'INSURER' and recipient = 'GULFTPA'",
                Long.class, docId)).isEqualTo(1L);
        boolean testLedger = costRecorders.stream().anyMatch(r -> r instanceof RecordingCostRecorder);
        if (testLedger) {
            assertThat(RecordingCostRecorder.ENTRIES.subList(costsBefore, RecordingCostRecorder.ENTRIES.size())).anySatisfy(e -> {
                assertThat(e.kind()).isEqualTo(CaseCostRecorder.Kind.ESTIMATE);
                assertThat(e.category()).isEqualTo(CaseCostRecorder.Category.MEDICAL);
                assertThat(e.amount()).isEqualByComparingTo("450.00");
                assertThat(e.sourceType()).isEqualTo("PREAUTH");
                assertThat(e.sourceRef()).isEqualTo(paId);
            });
        }

        // adjuster: own payer only, no justification; another insurer sees nothing
        assertThat(texts(ok(get("/api/v1/partner/preauthorisations"), OTHER_ADJUSTER, null), "id")).doesNotContain(paId);
        problem(post("/api/v1/partner/preauthorisations/" + paId + "/decision"), OTHER_ADJUSTER, "{\"status\":\"APPROVED\"}", 404);
        call(get("/api/v1/partner/preauthorisations"), PARTNER, null).andExpect(status().isForbidden());
        JsonNode adjList = ok(get("/api/v1/partner/preauthorisations?status=SUBMITTED"), ADJUSTER, null);
        assertThat(texts(adjList, "id")).contains(paId);
        assertThat(adjList.toString()).doesNotContain("justification");
        assertThat(texts(ok(get("/api/v1/partner/documents"), ADJUSTER, null), "id")).contains(docId);
        JsonNode decided = ok(post("/api/v1/partner/preauthorisations/" + paId + "/decision"), ADJUSTER,
                "{\"status\":\"PARTIALLY_APPROVED\",\"approvalRef\":\"GT-777\",\"approvedAmount\":{\"amount\":\"420.00\",\"currency\":\"GBP\"}}");
        assertThat(decided.path("status").asString()).isEqualTo("PARTIALLY_APPROVED");
        assertThat(decided.path("approvedAmount").path("amount").asString()).isEqualTo("420.00");
        assertThat(problem(post("/api/v1/partner/preauthorisations/" + paId + "/decision"), ADJUSTER, "{\"status\":\"DECLINED\"}", 422))
                .isEqualTo("invalid_transition");

        // clinician request with justification; coordinator decision; integration decision by reference
        JsonNode pa2 = ok(post("/api/v1/cases/" + caseId + "/preauthorisations"), NURSE,
                body.formatted(",\"justification\":\"Radicular pain not settling after 4 weeks\"").replace("IMAGING_MRI", "PHYSIOTHERAPY")
                        .replace("LDN_IMAGING", "PHYSIOPLUS"));
        assertThat(pa2.path("justification").asString()).contains("Radicular");
        ok(post("/api/v1/preauthorisations/" + pa2.path("id").asString() + "/submit"), NURSE, null);
        call(post("/api/v1/preauthorisations/" + pa2.path("id").asString() + "/decision"), NURSE, "{\"status\":\"APPROVED\"}")
                .andExpect(status().isForbidden());
        call(post("/api/v1/integration/preauthorisations/" + pa2.path("ref").asString() + "/decision"), INTEGRATION,
                "{\"status\":\"APPROVED\"}").andExpect(status().isNotFound()); // a provider system is not the payer
        assertThat(ok(post("/api/v1/integration/preauthorisations/" + pa2.path("ref").asString() + "/decision"), PAYER_SYSTEM,
                "{\"status\":\"DECLINED\",\"note\":\"Not covered by the policy\"}").path("status").asString()).isEqualTo("DECLINED");
        JsonNode claimsList = ok(get("/api/v1/preauthorisations"), CLAIMS, null);
        assertThat(texts(claimsList, "id")).contains(paId, pa2.path("id").asString());
        assertThat(claimsList.toString()).doesNotContain("Radicular");
        call(get("/api/v1/preauthorisations"), FINANCE, null).andExpect(status().isForbidden());
        assertThat(ok(get("/api/v1/cases/" + caseId + "/preauthorisations"), NURSE, null).size()).isEqualTo(2);

        // invoices: matched against the approved amount (tolerance 5 %), duplicates rejected, ledger entry
        String invoice = """
                {"providerCode":"LDN_IMAGING","invoiceNumber":"%s","amount":"%s","currency":"GBP","serviceDate":"%s"}""";
        call(post("/api/v1/cases/" + caseId + "/invoices"), NURSE, invoice.formatted("X-1", "430.00", TODAY)).andExpect(status().isForbidden());
        JsonNode inv = ok(post("/api/v1/cases/" + caseId + "/invoices"), FINANCE, invoice.formatted("LDI-T-" + emp, "430.00", TODAY));
        assertThat(inv.path("status").asString()).isEqualTo("MATCHED");
        assertThat(inv.path("matchedPreauthId").asString()).isEqualTo(paId);
        assertThat(inv.path("variance").path("amount").asString()).isEqualTo("10.00");
        assertThat(problem(post("/api/v1/cases/" + caseId + "/invoices"), CLAIMS, invoice.formatted("LDI-T-" + emp, "430.00", TODAY), 409))
                .isEqualTo("invoice_exists");
        JsonNode disputed = ok(post("/api/v1/cases/" + caseId + "/invoices"), CLAIMS, invoice.formatted("LDI-U-" + emp, "900.00", TODAY));
        assertThat(disputed.path("status").asString()).isEqualTo("DISPUTED");
        if (testLedger) {
            assertThat(RecordingCostRecorder.ENTRIES).anySatisfy(e -> {
                assertThat(e.kind()).isEqualTo(CaseCostRecorder.Kind.INVOICE);
                assertThat(e.sourceRef()).isEqualTo(inv.path("id").asString());
            });
        }
        assertThat(ok(post("/api/v1/invoices/" + inv.path("id").asString() + "/status"), FINANCE, "{\"status\":\"PAID\"}").path("status").asString())
                .isEqualTo("PAID");
        assertThat(problem(post("/api/v1/invoices/" + disputed.path("id").asString() + "/status"), FINANCE, "{\"status\":\"PAID\"}", 422))
                .isEqualTo("invalid_transition");

        // finance roll-up: units, never names
        JsonNode costs = ok(get("/api/v1/treatment/costs?groupBy=DEPARTMENT&from=" + TODAY.minusDays(1) + "&to=" + TODAY), FINANCE, null);
        assertThat(texts(costs, "key")).contains("GROUND");
        assertThat(costs.toString()).doesNotContain("Test " + emp).doesNotContain(emp);
        MvcResult csv = call(get("/api/v1/treatment/costs/export?groupBy=COST_CENTRE"), CMO, null).andReturn();
        assertThat(csv.getResponse().getContentAsString()).startsWith("key,label,cases");
        call(get("/api/v1/treatment/costs"), NURSE, null).andExpect(status().isForbidden());
        call(get("/api/v1/cases/" + caseId + "/preauthorisations"), PARTNER, null).andExpect(status().isForbidden());
    }

    // =================================================================== F05 medication

    @Test
    void f05FormularyStockAdministrationAllergyAndBatchChecks() throws Exception {
        String[] ec = employeeWithCase();
        String emp = ec[0];
        String caseId = ec[1];
        assertThat(ok(get("/api/v1/treatment/formulary"), NURSE, null).size()).isGreaterThanOrEqualTo(10);
        String naproxen = "{\"name\":\"Naproxen\",\"form\":\"TABLET\",\"strength\":\"250 mg\",\"unit\":\"tablet\",\"route\":[\"ORAL\"],\"reorderLevel\":10}";
        call(put("/api/v1/treatment/formulary/NAPROXEN_250"), NURSE, naproxen).andExpect(status().isForbidden());
        ok(put("/api/v1/treatment/formulary/NAPROXEN_250"), OH_ADMIN, naproxen);
        String batch = "IBU-T" + UUID.randomUUID().toString().substring(0, 6);
        JsonNode received = ok(post("/api/v1/treatment/stock"), NURSE,
                "{\"itemCode\":\"IBUPROFEN_400\",\"batchNumber\":\"%s\",\"expiry\":\"%s\",\"quantity\":5,\"siteCode\":\"LHR-CAT\"}"
                        .formatted(batch, TODAY.plusDays(100)));
        assertThat(received.path("quantity").asInt()).isEqualTo(5);
        call(post("/api/v1/treatment/stock"), CLAIMS, "{\"itemCode\":\"IBUPROFEN_400\",\"batchNumber\":\"X1\",\"expiry\":\"%s\",\"quantity\":1,\"siteCode\":\"LHR-CAT\"}"
                .formatted(TODAY.plusDays(10))).andExpect(status().isForbidden());
        String para = "PCM-T" + UUID.randomUUID().toString().substring(0, 6);
        ok(post("/api/v1/treatment/stock"), NURSING_HEAD, "{\"itemCode\":\"PARACETAMOL_500\",\"batchNumber\":\"%s\",\"expiry\":\"%s\",\"quantity\":3,\"siteCode\":\"LHR-CAT\"}"
                .formatted(para, TODAY.plusDays(100)));
        jdbc.update("insert into treatment.stock_batch (id, item_code, batch_number, expiry, quantity, site_code, received_by, received_at) "
                + "values (?::uuid, 'PARACETAMOL_500', ?, ?, 10, 'LHR-CAT', 'test', now())", UUID.randomUUID().toString(), "OLD-" + para, TODAY.minusDays(1));
        JsonNode stock = ok(get("/api/v1/treatment/stock?siteCode=LHR-CAT"), NURSE, null);
        assertThat(stock.toString()).contains("\"expired\":true").contains("\"belowReorder\":true");

        String admin = "{\"itemCode\":\"%s\",\"dose\":\"1 tablet\",\"route\":\"ORAL\",\"batchNumber\":\"%s\",\"siteCode\":\"LHR-CAT\"%s}";
        assertThat(problem(post("/api/v1/cases/" + caseId + "/medications/administer"), NURSE, admin.formatted("PARACETAMOL_500", "OLD-" + para, ""), 422))
                .isEqualTo("batch_expired");
        assertThat(problem(post("/api/v1/cases/" + caseId + "/medications/administer"), NURSE, admin.formatted("PARACETAMOL_500", "NOPE-1", ""), 422))
                .isEqualTo("batch_unknown");
        JsonNode given = ok(post("/api/v1/cases/" + caseId + "/medications/administer"), NURSE, admin.formatted("PARACETAMOL_500", para, ""));
        assertThat(given.path("name").asString()).isEqualTo("Paracetamol");
        assertThat(jdbc.queryForObject("select quantity from treatment.stock_batch where batch_number = ?", Integer.class, para)).isEqualTo(2);
        assertThat(jdbc.queryForObject("select count(*) from ohrecord.medication where employee_id = ? and source = 'ADMINISTERED'", Long.class, emp))
                .isEqualTo(1L);

        // allergy check against the E03 record
        jdbc.update("insert into ohrecord.allergy (id, employee_id, substance_system, substance_code, substance_display, category, criticality, status, "
                + "recorded_by, recorded_at, updated_at) values (?::uuid, ?, 'LOCAL', 'NSAID', 'Naproxen', 'MEDICATION', 'HIGH', 'ACTIVE', 'nurse1', now(), now())",
                UUID.randomUUID().toString(), emp);
        MvcResult blocked = call(post("/api/v1/cases/" + caseId + "/medications/administer"), NURSE, admin.formatted("IBUPROFEN_400", batch, "")).andReturn();
        assertThat(blocked.getResponse().getStatus()).isEqualTo(422);
        assertThat(body(blocked).path("code").asString()).isEqualTo("allergy_alert");
        assertThat(body(blocked).path("detail").asString()).contains("Naproxen");
        JsonNode overridden = ok(post("/api/v1/cases/" + caseId + "/medications/administer"), PHYSICIAN,
                admin.formatted("IBUPROFEN_400", batch, ",\"overrideAllergyReason\":\"Tolerated ibuprofen previously, physician reviewed\""));
        assertThat(overridden.path("allergyOverride").asString()).contains("Tolerated");
        assertThat(ok(get("/api/v1/cases/" + caseId + "/medications/administrations"), NURSE, null).size()).isEqualTo(2);
        assertThat(ok(get("/api/v1/treatment/medication-usage"), NURSING_HEAD, null).toString()).contains("PARACETAMOL_500");
        assertThat(ok(get("/api/v1/treatment/eprescribing/status"), NURSE, null).path("enabled").asBoolean()).isFalse();
        call(get("/api/v1/treatment/medication-usage"), NURSE, null).andExpect(status().isForbidden());
    }

    // =================================================================== F06 outcomes & closure guard

    @Test
    void f06OutcomesAndClosureGuard() throws Exception {
        String[] ec = employeeWithCase();
        String caseId = ec[1];
        ok(post("/api/v1/cases/" + caseId + "/treatment-plans?activate=true"), NURSE, "{\"templateCode\":\"WRIST_SPRAIN\"}");
        jdbc.update("update casemgmt.oh_case set primary_diagnosis_system = 'ICD_10', primary_diagnosis_code = 'S63.5', "
                + "primary_diagnosis_display = 'Sprain of wrist' where id = ?::uuid", caseId);
        ok(post("/api/v1/cases/" + caseId + "/outcomes"), NURSE, "{\"milestone\":\"BASELINE\",\"painScore\":6,\"functionScale\":\"QUICKDASH\",\"functionScore\":55}");
        assertThat(problem(post("/api/v1/cases/" + caseId + "/outcomes"), NURSE,
                "{\"milestone\":\"BASELINE\",\"painScore\":6,\"functionScale\":\"QUICKDASH\",\"functionScore\":55}", 409)).isEqualTo("baseline_exists");
        call(post("/api/v1/cases/" + caseId + "/outcomes"), NURSE, "{\"milestone\":\"REVIEW\",\"painScore\":11,\"functionScale\":\"ODI\",\"functionScore\":5}")
                .andExpect(status().isBadRequest());
        assertThat(problem(post("/api/v1/cases/" + caseId + "/transition"), NURSE, "{\"status\":\"CLOSED\"}", 422)).isEqualTo("outcome_required");
        JsonNode closure = ok(post("/api/v1/cases/" + caseId + "/outcomes"), NURSE,
                "{\"milestone\":\"CLOSURE\",\"painScore\":1,\"functionScale\":\"QUICKDASH\",\"functionScore\":10,\"rtwDate\":\"%s\",\"residualImpairmentPct\":0}"
                        .formatted(TODAY));
        assertThat(closure.path("improvement").path("painDelta").asInt()).isEqualTo(-5);
        JsonNode outcomeList = ok(get("/api/v1/cases/" + caseId + "/outcomes"), NURSE, null);
        JsonNode list = outcomeList.path("outcomes");
        assertThat(texts(list, "milestone")).containsExactly("BASELINE", "CLOSURE");
        assertThat(list.get(0).has("improvement")).isFalse();
        assertThat(outcomeList.path("improvement").path("painDelta").asInt()).isEqualTo(-5);
        assertThat(outcomeList.path("improvement").path("functionDelta").asInt()).isEqualTo(-45);
        ok(post("/api/v1/cases/" + caseId + "/transition"), NURSE, "{\"status\":\"CLOSED\"}");
        call(get("/api/v1/cases/" + caseId + "/outcomes"), CLAIMS, null).andExpect(status().isForbidden());
    }

    // =================================================================== F07 telehealth

    @Test
    void f07TelehealthConsentJoinTokensChatAndCompletion() throws Exception {
        String[] ec = employeeWithCase();
        String emp = ec[0];
        String create = """
                {"employeeId":"%s","scheduledAt":"%s","durationMinutes":30,"mode":"VIDEO","providerCode":"CITYHOSP","crewLayover":true,"layoverCity":"Singapore"}""";
        JsonNode s = ok(post("/api/v1/telehealth/sessions"), PHYSICIAN, create.formatted(emp, Instant.now().plus(5, ChronoUnit.MINUTES)));
        String id = s.path("id").asString();
        assertThat(s.path("ref").asString()).matches("TH-\\d{4}-\\d{6}");
        assertThat(s.path("consentGiven").asBoolean()).isFalse();
        assertThat(notifications(emp.toLowerCase() + "@wellnessz.local", "telehealth.invite")).isEqualTo(1L);
        call(post("/api/v1/telehealth/sessions"), CLAIMS, create.formatted(emp, Instant.now())).andExpect(status().isForbidden());

        assertThat(problem(post("/api/v1/me/telehealth/" + id + "/join"), employee(emp), "{}", 422)).isEqualTo("consent_required");
        JsonNode mine = ok(get("/api/v1/me/telehealth"), employee(emp), null);
        assertThat(mine.get(0).path("clinicianName").asString()).isEqualTo("Dr Phil Physician");
        assertThat(mine.get(0).path("providerName").asString()).isEqualTo("City General Hospital");
        assertThat(ok(post("/api/v1/me/telehealth/" + id + "/consent"), employee(emp), "{}").path("consentGiven").asBoolean()).isTrue();
        JsonNode join = ok(post("/api/v1/me/telehealth/" + id + "/join"), employee(emp), "{}");
        String url = join.path("joinUrl").asString();
        assertThat(url).startsWith("https://telehealth.wellnessz.local/room/").contains("token=");
        String token = url.substring(url.indexOf("token=") + 6);
        assertThat(jdbc.queryForObject("select count(*) from treatment.telehealth_token where token_hash = ?", Long.class, token)).isZero();
        assertThat(ok(post("/api/v1/integration/telehealth/tokens/redeem"), INTEGRATION, "{\"token\":\"%s\"}".formatted(token))
                .path("valid").asBoolean()).isTrue();
        assertThat(ok(post("/api/v1/integration/telehealth/tokens/redeem"), INTEGRATION, "{\"token\":\"%s\"}".formatted(token))
                .path("valid").asBoolean()).as("single use").isFalse();
        problem(post("/api/v1/me/telehealth/" + id + "/join"), employee("E1001"), "{}", 404);

        // partner specialist of the session's organisation only
        assertThat(texts(ok(get("/api/v1/partner/telehealth/sessions"), PARTNER, null), "id")).contains(id);
        ok(post("/api/v1/partner/telehealth/sessions/" + id + "/join"), PARTNER, null);
        problem(post("/api/v1/partner/telehealth/sessions/" + id + "/join"), PHYSIO, null, 404);
        ok(post("/api/v1/me/telehealth/" + id + "/messages"), employee(emp), "{\"text\":\"I am at the crew hotel, room 1204\"}");
        ok(post("/api/v1/partner/telehealth/sessions/" + id + "/messages"), PARTNER, "{\"text\":\"Connecting now\"}");
        JsonNode chat = ok(get("/api/v1/telehealth/sessions/" + id + "/messages"), PHYSICIAN, null);
        assertThat(texts(chat, "senderRole")).containsExactly("EMPLOYEE", "PARTNER");
        assertThat(jdbc.queryForObject("select count(*) from treatment.telehealth_message where session_id = ?::uuid and text like '%crew hotel%'",
                Long.class, id)).as("encrypted at rest").isZero();

        // outside the join window
        JsonNode later = ok(post("/api/v1/telehealth/sessions"), PHYSICIAN, create.formatted(emp, Instant.now().plus(3, ChronoUnit.DAYS)));
        ok(post("/api/v1/me/telehealth/" + later.path("id").asString() + "/consent"), employee(emp), null);
        assertThat(problem(post("/api/v1/telehealth/sessions/" + later.path("id").asString() + "/join"), PHYSICIAN, null, 422))
                .isEqualTo("outside_join_window");
        assertThat(ok(post("/api/v1/telehealth/sessions/" + later.path("id").asString() + "/cancel"), PHYSICIAN, "{\"reason\":\"Rebooked\"}")
                .path("status").asString()).isEqualTo("CANCELLED");

        JsonNode done = ok(post("/api/v1/telehealth/sessions/" + id + "/complete"), PHYSICIAN, "{\"note\":\"Advised rest, review in a week\"}");
        assertThat(done.path("status").asString()).isEqualTo("COMPLETED");
        String enc = done.path("encounterId").asString();
        assertThat(jdbc.queryForObject("select status from ohrecord.encounter where id = ?::uuid", String.class, enc)).isEqualTo("DRAFT");
        assertThat(texts(ok(get("/api/v1/telehealth/sessions?status=COMPLETED"), NURSE, null), "id")).contains(id);
        call(get("/api/v1/telehealth/sessions"), PARTNER, null).andExpect(status().isForbidden());
    }

    // =================================================================== demo data

    @Test
    void demoDataIsUsableByThePartnerPortal() throws Exception {
        String year = String.valueOf(TODAY.getYear());
        JsonNode inbox = ok(get("/api/v1/partner/referrals"), PARTNER, null);
        assertThat(texts(inbox, "ref")).contains("RFL-" + year + "-900001");
        assertThat(texts(ok(get("/api/v1/partner/referrals"), PHYSIO, null), "ref")).contains("RFL-" + year + "-900002")
                .doesNotContain("RFL-" + year + "-900001");
        assertThat(texts(ok(get("/api/v1/partner/preauthorisations"), ADJUSTER, null), "ref")).contains("PA-" + year + "-900001", "PA-" + year + "-900002");
        JsonNode plansDemo = ok(get("/api/v1/cases/e04de000-0000-4000-a000-000000000001/treatment-plans"), NURSE, null);
        assertThat(plansDemo.get(0).path("goals").size()).isEqualTo(3);
        assertThat(plansDemo.get(0).path("variance").path("alert").asBoolean()).isTrue();
        assertThat(texts(ok(get("/api/v1/me/telehealth"), user("crew1", "E1003", null, "EMPLOYEE"), null), "ref")).contains("TH-" + year + "-900001");
        assertThat(new BigDecimal(ok(get("/api/v1/cases/e04de000-0000-4000-a000-000000000001/invoices"), FINANCE, null).get(0).path("amount")
                .path("amount").asString())).isEqualByComparingTo("430.00");
    }
}
