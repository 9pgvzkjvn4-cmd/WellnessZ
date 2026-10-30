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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

import com.wellnessz.casekernel.support.SupportProgrammeReferral;
import com.wellnessz.common.events.IntegrationEnvelope;
import com.wellnessz.e09.cism.CismIncidentListener;
import com.wellnessz.e09.cism.CismService;
import com.wellnessz.e09.peer.PeerSupportService;
import com.wellnessz.integration.outbox.OutboxEvent;
import com.wellnessz.integration.outbox.OutboxRepository;
import com.wellnessz.security.consent.ConsentPurpose;
import com.wellnessz.security.consent.ConsentService;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Acceptance tests for E09 – mental health, psychosocial risk & peer support (contract §3) against the real endpoints: the
 * restricted case type (named clinicians only, audited 404 for everyone else, placeholder counts, break-glass), self-referral,
 * screening and the urgent-risk rule, the EAP partner (own organisation, no free text), the sharing choice and the manager view,
 * pseudonymous peer support without employee ids, the quarterly SMS report with small-cell suppression, relief from duties and
 * safety-critical fitness (status-only feeds), CISM from the incident feed, the psychosocial register and the kernel SPI.
 */
class Increment5MentalHealthIntegrationTest extends IntegrationTestBase {

    @Autowired
    MockMvc mvc;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    JsonMapper json;
    @Autowired
    ConsentService consents;
    @Autowired
    SupportProgrammeReferral programmeReferral;
    @Autowired
    CismIncidentListener incidentListener;
    @Autowired
    CismService cismService;
    @Autowired
    PeerSupportService peerService;
    @Autowired
    OutboxRepository outbox;

    static final JwtRequestPostProcessor PSYCH = user("psych1", "P5001", "/AIRLINE/OH", "PSYCH_CLINICIAN");
    static final JwtRequestPostProcessor PSYCH_NOT_NAMED = user("psych9", "P5009", "/AIRLINE/OH", "PSYCH_CLINICIAN");
    static final JwtRequestPostProcessor NURSE = user("nurse1", "N8001", "/AIRLINE/OH", "OH_CLINICIAN", "CASE_MANAGER");
    static final JwtRequestPostProcessor AME = user("ame1", "D8201", "/AIRLINE/OH", "AME", "OH_PHYSICIAN", "OH_CLINICIAN");
    static final JwtRequestPostProcessor CMO = user("cmo1", "D8301", "/AIRLINE/OH", "CMO", "OH_PHYSICIAN", "OH_CLINICIAN");
    static final JwtRequestPostProcessor MANAGER = user("manager1", "M2001", "/AIRLINE/CATERING", "MANAGER");
    static final JwtRequestPostProcessor HR = user("hr1", "H3001", "/AIRLINE", "HR");
    static final JwtRequestPostProcessor HSE = user("hse1", "S8501", "/AIRLINE", "HSE_OFFICER");
    static final JwtRequestPostProcessor CREW_SCHED = user("crewsched1", "S8601", "/AIRLINE/OPS", "CREW_SCHEDULING");
    static final JwtRequestPostProcessor PEER = user("peer1", "S8801", "/AIRLINE/OPS", "PEER_SUPPORT_VOLUNTEER");
    static final JwtRequestPostProcessor COORDINATOR = user("peercoord1", "S8802", "/AIRLINE/OPS", "PEER_SUPPORT_COORDINATOR");
    static final JwtRequestPostProcessor SAFETY = user("safety1", "S8803", "/AIRLINE", "SAFETY_MANAGER");
    static final JwtRequestPostProcessor EAP = user("eap1", "X8809", "/PARTNERS/EAP_WELLBEING", "PARTNER_PROVIDER");
    static final JwtRequestPostProcessor OTHER_PARTNER = user("partner1", "P9101", "/PARTNERS/CITYHOSP", "PARTNER_PROVIDER");

    static final int YEAR = LocalDate.now(ZoneOffset.UTC).getYear();
    static final String DEMO_URGENT_CASE = "e09de000-0000-4000-a000-000000000002";
    static final String DEMO_OFFICE_CASE = "e09de000-0000-4000-a000-000000000001";

    // ------------------------------------------------------------------ helpers

    JsonNode body(MvcResult r) throws Exception {
        return json.readTree(r.getResponse().getContentAsString());
    }

    String text(MvcResult r) throws Exception {
        return r.getResponse().getContentAsString();
    }

    ResultActions postJson(String url, JwtRequestPostProcessor who, String content) throws Exception {
        return mvc.perform(post(url).with(who).contentType(MediaType.APPLICATION_JSON).content(content));
    }

    ResultActions putJson(String url, JwtRequestPostProcessor who, String content) throws Exception {
        return mvc.perform(put(url).with(who).contentType(MediaType.APPLICATION_JSON).content(content));
    }

    JsonNode ok(ResultActions a) throws Exception {
        return body(a.andExpect(status().is2xxSuccessful()).andReturn());
    }

    String toJson(Object o) {
        return json.writeValueAsString(o);
    }

    long count(String sql, Object... args) {
        Long n = jdbc.queryForObject(sql, Long.class, args);
        return n == null ? 0 : n;
    }

    /** A fresh employee in the given department (CATERING is inside manager1's scope). */
    String employee(String dept, String job) {
        String id = "T9" + ThreadLocalRandom.current().nextInt(100_000, 999_999);
        String sub = "CATERING".equals(dept) ? "CAT" : "FLT";
        jdbc.update("""
                insert into masterdata.employee (employee_id, display_name, email, subsidiary_code, department_code, cost_centre, job_code,
                                                 location_code, shift_pattern, preferred_language, badge_id, manager_employee_id, updated_at)
                values (?, ?, ?, ?, ?, 'CC-TST-100', ?, 'LHR', 'EARLY', 'en', ?, null, now())""",
                id, "Tess Tester " + id, id.toLowerCase() + "@wellnessz.local", sub, dept, job, "BT-" + id);
        return id;
    }

    static JwtRequestPostProcessor employeeUser(String id) {
        return user("u" + id.toLowerCase(), id, "/AIRLINE/CATERING", "EMPLOYEE");
    }

    long denied(String action, String resourceId) {
        return count("select count(*) from platform.audit_event where action = ? and outcome = 'DENIED' and coalesce(resource_id, '') = ?", action,
                resourceId == null ? "" : resourceId);
    }

    JsonNode clinicianCase(String employeeId) throws Exception {
        return ok(postJson("/api/v1/support/cases", PSYCH, toJson(Map.of("employeeId", employeeId, "concern", "WORK_STRESS", "risk", "MODERATE",
                "note", "Initial assessment ZK-SECRET-NOTE"))));
    }

    List<String> texts(JsonNode array, String field) {
        List<String> out = new ArrayList<>();
        array.forEach(n -> out.add(n.path(field).asString()));
        return out;
    }

    // ------------------------------------------------------------------ F01 restricted case type

    @Test
    void restrictedCaseIsVisibleToNamedCliniciansOnlyWithPlaceholderAndBreakGlass() throws Exception {
        String emp = employee("CATERING", "CATERING_ASSOC");
        JsonNode c = clinicianCase(emp);
        String id = c.get("id").asString();
        assertThat(c.get("ref").asString()).matches("SUP-" + YEAR + "-\\d{6}");
        assertThat(c.get("status").asString()).isEqualTo("OPEN");
        assertThat(c.get("source").asString()).isEqualTo("CLINICIAN");
        assertThat(c.get("sharing").asString()).isEqualTo("NOTHING");
        assertThat(c.get("notes").get(0).get("text").asString()).contains("ZK-SECRET-NOTE");
        // backed by a restricted kernel case; the note is encrypted at rest
        assertThat(count("select count(*) from casemgmt.oh_case where employee_id = ? and classification = 'RESTRICTED_CLINICAL' and category = 'PSYCHOLOGICAL'",
                emp)).isEqualTo(1);
        assertThat(count("select count(*) from mentalhealth.support_note where support_case_id = ?::uuid and text like '%ZK-SECRET%'", id)).isZero();
        // second open case for the same employee
        postJson("/api/v1/support/cases", PSYCH, toJson(Map.of("employeeId", emp, "concern", "OTHER", "risk", "LOW")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("open_case_exists"));

        ok(postJson("/api/v1/support/cases/" + id + "/notes", PSYCH, "{\"text\":\"Follow-up call ZK-SECOND\"}"));
        JsonNode detail = ok(mvc.perform(get("/api/v1/support/cases/" + id).with(PSYCH)));
        assertThat(detail.get("notes")).hasSize(2);
        assertThat(detail.get("status").asString()).isEqualTo("ACTIVE");
        assertThat(detail.get("accessBasis").asString()).isEqualTo("NAMED_CLINICIAN");
        assertThat(texts(ok(mvc.perform(get("/api/v1/support/cases").param("risk", "MODERATE").with(PSYCH))), "id")).contains(id);

        // everyone else: 404 and an audited DENIED – general OH, a PSYCH clinician who is not named, AME, HR, manager, the employee
        for (JwtRequestPostProcessor who : List.of(NURSE, PSYCH_NOT_NAMED, AME, HR, MANAGER, employeeUser(emp))) {
            mvc.perform(get("/api/v1/support/cases/" + id).with(who)).andExpect(status().isNotFound());
        }
        assertThat(denied("SUPPORT_CASE_READ", id)).isGreaterThanOrEqualTo(6);
        mvc.perform(get("/api/v1/support/cases").with(NURSE)).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/support/cases").with(PSYCH_NOT_NAMED)).andExpect(status().isNotFound());
        assertThat(denied("SUPPORT_CASE_LIST", null)).isGreaterThanOrEqualTo(2);
        postJson("/api/v1/support/cases/" + id + "/notes", NURSE, "{\"text\":\"x\"}").andExpect(status().isNotFound());
        postJson("/api/v1/support/cases", NURSE, toJson(Map.of("employeeId", emp, "concern", "OTHER", "risk", "LOW"))).andExpect(status().isNotFound());
        // the kernel case is restricted for general OH as well
        String kernelId = jdbc.queryForObject("select kernel_case_id::text from mentalhealth.support_case where id = ?::uuid", String.class, id);
        mvc.perform(get("/api/v1/cases/" + kernelId).with(NURSE)).andExpect(status().isNotFound());
        assertThat(text(mvc.perform(get("/api/v1/cases").with(NURSE)).andReturn())).doesNotContain(kernelId);

        // general-OH placeholder: count only
        JsonNode ph = ok(mvc.perform(get("/api/v1/support/placeholder").param("employeeId", emp).with(NURSE)));
        assertThat(ph.get("restrictedCases").asLong()).isEqualTo(1);
        assertThat(ph.size()).isEqualTo(1);
        mvc.perform(get("/api/v1/support/placeholder").param("employeeId", emp).with(MANAGER)).andExpect(status().isNotFound());
        assertThat(denied("SUPPORT_PLACEHOLDER_READ", emp)).isEqualTo(1);

        // break-glass (increment 2): an OH clinician with an active grant may read this employee's support cases
        ok(postJson("/api/v1/break-glass", NURSE, toJson(Map.of("employeeId", emp, "reason", "Collapsed at work, emergency team needs the history now"))));
        JsonNode bg = ok(mvc.perform(get("/api/v1/support/cases/" + id).with(NURSE)));
        assertThat(bg.get("accessBasis").asString()).isEqualTo("BREAK_GLASS");
        postJson("/api/v1/support/cases/" + id + "/notes", NURSE, "{\"text\":\"x\"}").andExpect(status().isNotFound()); // read only

        // closing
        JsonNode closed = ok(postJson("/api/v1/support/cases/" + id + "/close", PSYCH, "{\"reason\":\"Recovered, no further support needed\"}"));
        assertThat(closed.get("status").asString()).isEqualTo("CLOSED");
        assertThat(ok(mvc.perform(get("/api/v1/support/placeholder").param("employeeId", emp).with(NURSE))).get("restrictedCases").asLong()).isZero();
        postJson("/api/v1/support/cases/" + id + "/close", PSYCH, "{\"reason\":\"again\"}").andExpect(status().isConflict());
    }

    // ------------------------------------------------------------------ F02 self-referral, screening, urgent risk

    @Test
    void selfReferralScreensAndTheUrgentRiskProtocol() throws Exception {
        String emp = employee("CATERING", "CATERING_ASSOC");
        JwtRequestPostProcessor me = employeeUser(emp);

        JsonNode sign = ok(mvc.perform(get("/api/v1/me/support/signposting").with(me)));
        assertThat(texts(sign.get("items"), "code")).contains("CONFIDENTIAL_LINE", "EAP", "PEER_SUPPORT");
        assertThat(sign.get("emergency").get("phone").asString()).isEqualTo("999");
        mvc.perform(get("/api/v1/me/support/signposting").with(NURSE)).andExpect(status().isForbidden());

        // a non-urgent screen without a case is stored but opens nothing
        JsonNode def = ok(mvc.perform(get("/api/v1/me/support/screens/GAD7").with(me)));
        assertThat(def.get("questions")).hasSize(7);
        assertThat(def.get("options")).hasSize(4);
        JsonNode gad = ok(postJson("/api/v1/me/support/screens", me, "{\"instrument\":\"GAD7\",\"answers\":[1,1,1,1,0,0,0]}"));
        assertThat(gad.get("score").asInt()).isEqualTo(4);
        assertThat(gad.get("band").asString()).isEqualTo("MINIMAL");
        assertThat(gad.get("urgent").asBoolean()).isFalse();
        assertThat(gad.has("caseRef")).isFalse();
        assertThat(count("select count(*) from mentalhealth.support_case where employee_id = ?", emp)).isZero();
        postJson("/api/v1/me/support/screens", me, "{\"instrument\":\"PHQ9\",\"answers\":[1,1,1,1,1,1,1,1]}")
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("invalid_answers"));
        postJson("/api/v1/me/support/screens", me, "{\"instrument\":\"PHQ9\",\"answers\":[1,1,1,1,1,1,1,1,4]}").andExpect(status().isBadRequest());

        // self-referral
        JsonNode ref = ok(postJson("/api/v1/me/support/self-referral", me, toJson(Map.of("preferredContact", "PHONE", "phone", "+44 7700 900999",
                "bestTime", "Mornings", "concern", "LOW_MOOD", "message", "I have been struggling ZK-MESSAGE"))));
        String caseRef = ref.get("caseRef").asString();
        assertThat(caseRef).matches("SUP-" + YEAR + "-\\d{6}");
        assertThat(Instant.parse(ref.get("nextContactBy").asString())).isAfter(Instant.now());
        assertThat(count("select count(*) from platform.notification where address = ? and template_key = 'support.request-received'",
                emp.toLowerCase() + "@wellnessz.local")).isEqualTo(1);
        assertThat(count("select count(*) from mentalhealth.support_case where case_ref = ? and (message like '%ZK-MESSAGE%' or contact_phone like '%900999%')",
                caseRef)).isZero();
        String caseId = jdbc.queryForObject("select id::text from mentalhealth.support_case where case_ref = ?", String.class, caseRef);

        // PHQ-9 with item 9 > 0 → URGENT: same-day kernel task + content-free alert to the named clinicians
        long alertsBefore = count("select count(*) from platform.notification where address = 'psych1@wellnessz.local' and template_key = 'support.urgent-callback'");
        JsonNode phq = ok(postJson("/api/v1/me/support/screens", me, "{\"instrument\":\"PHQ9\",\"answers\":[1,1,0,1,0,0,0,0,1]}"));
        assertThat(phq.get("score").asInt()).isEqualTo(4);
        assertThat(phq.get("urgent").asBoolean()).isTrue();
        assertThat(phq.get("caseRef").asString()).isEqualTo(caseRef);
        assertThat(phq.get("nextSteps").toString()).contains("today");
        assertThat(count("select count(*) from platform.notification where address = 'psych1@wellnessz.local' and template_key = 'support.urgent-callback'"))
                .isEqualTo(alertsBefore + 1);
        String alert = jdbc.queryForObject("""
                select body from platform.notification where address = 'psych1@wellnessz.local' and template_key = 'support.urgent-callback'
                 and body like ? order by scheduled_at desc limit 1""", String.class, "%" + caseRef + "%");
        assertThat(alert).contains("same day").doesNotContain("PHQ").doesNotContain(emp);
        assertThat(count("""
                select count(*) from casemgmt.case_task t join mentalhealth.support_case s on s.kernel_case_id = t.case_id
                 where s.id = ?::uuid and t.task_type = 'CALL_BACK' and t.status = 'OPEN'""", caseId)).isEqualTo(1);
        JsonNode urgent = ok(mvc.perform(get("/api/v1/support/urgent").with(PSYCH)));
        JsonNode item = null;
        for (JsonNode u : urgent) {
            if (caseId.equals(u.get("caseId").asString())) {
                item = u;
            }
        }
        assertThat(item).isNotNull();
        assertThat(item.get("trigger").asString()).isEqualTo("PHQ9_ITEM9");
        assertThat(item.get("callBackBy").asString()).isNotBlank();
        assertThat(texts(urgent, "caseId")).contains(DEMO_URGENT_CASE);
        mvc.perform(get("/api/v1/support/urgent").with(NURSE)).andExpect(status().isNotFound());

        // a second urgent screen does not duplicate the item
        ok(postJson("/api/v1/support/cases/" + caseId + "/screens", PSYCH, "{\"instrument\":\"PHQ9\",\"answers\":[3,3,3,3,3,3,2,0,0]}"));
        assertThat(count("select count(*) from mentalhealth.urgent_item where support_case_id = ?::uuid and status = 'OPEN'", caseId)).isEqualTo(1);
        postJson("/api/v1/support/cases/" + caseId + "/screens", NURSE, "{\"instrument\":\"GAD7\",\"answers\":[0,0,0,0,0,0,0]}")
                .andExpect(status().isNotFound());

        JsonNode protocol = ok(mvc.perform(get("/api/v1/support/protocols/urgent-risk").with(PSYCH)));
        assertThat(protocol.get("version").asString()).isEqualTo("2026.1");
        assertThat(protocol.get("steps").size()).isGreaterThanOrEqualTo(5);
        mvc.perform(get("/api/v1/support/protocols/urgent-risk").with(AME)).andExpect(status().isNotFound());

        // urgent call: retry keeps the item open, reached safe resolves it and completes the kernel task
        ok(postJson("/api/v1/support/cases/" + caseId + "/urgent-contact", PSYCH, "{\"outcome\":\"NOT_REACHED_RETRY\",\"note\":\"Voicemail\"}"));
        assertThat(count("select count(*) from mentalhealth.urgent_item where support_case_id = ?::uuid and status = 'OPEN'", caseId)).isEqualTo(1);
        JsonNode after = ok(postJson("/api/v1/support/cases/" + caseId + "/urgent-contact", PSYCH,
                "{\"outcome\":\"REACHED_SAFE\",\"note\":\"Safety plan agreed\"}"));
        assertThat(after.get("urgent")).isEmpty();
        assertThat(after.get("status").asString()).isEqualTo("ACTIVE");
        assertThat(after.get("contactPhone").asString()).isEqualTo("+44 7700 900999");
        assertThat(after.get("screens").size()).isEqualTo(2);
        assertThat(count("""
                select count(*) from casemgmt.case_task t join mentalhealth.support_case s on s.kernel_case_id = t.case_id
                 where s.id = ?::uuid and t.task_type = 'CALL_BACK' and t.status = 'OPEN'""", caseId)).isZero();
        postJson("/api/v1/support/cases/" + caseId + "/urgent-contact", PSYCH, "{\"outcome\":\"MAYBE\"}").andExpect(status().isBadRequest());

        // governance: named clinicians and the CMO
        JsonNode gov = ok(mvc.perform(get("/api/v1/support/governance").with(CMO)));
        assertThat(texts(gov.get("instruments"), "code")).containsExactly("GAD7", "PHQ9");
        assertThat(gov.get("signOff").asString()).isEqualTo("PENDING");
        ok(mvc.perform(get("/api/v1/support/governance").with(PSYCH)));
        mvc.perform(get("/api/v1/support/governance").with(NURSE)).andExpect(status().isNotFound());
    }

    // ------------------------------------------------------------------ F01 sharing choice & manager view

    @Test
    void sharingChoiceDrivesTheManagerViewAndIsDisclosureLogged() throws Exception {
        String emp = employee("CATERING", "CATERING_ASSOC");
        JwtRequestPostProcessor me = employeeUser(emp);
        ok(postJson("/api/v1/me/support/self-referral", me, "{\"preferredContact\":\"APP_CHAT\",\"concern\":\"WORK_STRESS\"}"));
        JsonNode sharing = ok(mvc.perform(get("/api/v1/me/support/sharing").with(me)));
        String caseId = sharing.get("caseId").asString();
        assertThat(sharing.get("choice").asString()).isEqualTo("NOTHING");
        assertThat(texts(ok(mvc.perform(get("/api/v1/workspace/psychosocial/shared-outcomes").with(MANAGER))), "employeeId")).doesNotContain(emp)
                .contains("E1010"); // demo: Cathy Cook shares FITNESS_ONLY

        putJson("/api/v1/me/support/sharing", me, toJson(Map.of("caseId", caseId, "choice", "FITNESS_AND_ADJUSTMENTS",
                "adjustments", "Diagnosis of depression, needs time off"))).andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("clinical_content_not_allowed")).andExpect(jsonPath("$.field").value("adjustments"));
        putJson("/api/v1/me/support/sharing", me, toJson(Map.of("caseId", caseId, "choice", "FITNESS_AND_ADJUSTMENTS")))
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("adjustments_required"));
        JsonNode set = ok(putJson("/api/v1/me/support/sharing", me, toJson(Map.of("caseId", caseId, "choice", "FITNESS_AND_ADJUSTMENTS",
                "adjustments", "Later start times for four weeks"))));
        assertThat(set.get("choice").asString()).isEqualTo("FITNESS_AND_ADJUSTMENTS");
        // another employee cannot change it
        putJson("/api/v1/me/support/sharing", employeeUser("E1001"), toJson(Map.of("caseId", caseId, "choice", "NOTHING")))
                .andExpect(status().isNotFound());

        JsonNode shared = ok(mvc.perform(get("/api/v1/workspace/psychosocial/shared-outcomes").with(MANAGER)));
        JsonNode mine = null;
        for (JsonNode s : shared) {
            if (emp.equals(s.get("employeeId").asString())) {
                mine = s;
            }
        }
        assertThat(mine).isNotNull();
        assertThat(mine.get("adjustments").asString()).isEqualTo("Later start times for four weeks");
        assertThat(mine.get("fitness").asString()).isEqualTo("NOT_ASSESSED");
        assertThat(mine.toString()).doesNotContain("WORK_STRESS").doesNotContain("risk").doesNotContain("concern");
        assertThat(count("""
                select count(*) from platform.disclosure_log where employee_id = ? and recipient_type = 'MANAGER' and recipient_name = 'manager1'
                 and resource_type = 'SUPPORT_SHARING' and revoked_at is null""", emp)).isEqualTo(1);
        ok(mvc.perform(get("/api/v1/workspace/psychosocial/shared-outcomes").with(MANAGER))); // unchanged: not logged twice
        assertThat(count("select count(*) from platform.disclosure_log where employee_id = ? and resource_type = 'SUPPORT_SHARING'", emp)).isEqualTo(1);

        // revocation
        ok(putJson("/api/v1/me/support/sharing", me, toJson(Map.of("caseId", caseId, "choice", "NOTHING"))));
        assertThat(texts(ok(mvc.perform(get("/api/v1/workspace/psychosocial/shared-outcomes").with(MANAGER))), "employeeId")).doesNotContain(emp);
        assertThat(count("select count(*) from platform.disclosure_log where employee_id = ? and resource_type = 'SUPPORT_SHARING' and revoked_at is not null",
                emp)).isEqualTo(1);
        assertThat(count("select count(*) from platform.audit_event where action = 'SUPPORT_SHARING_CHANGE' and resource_id = ? and outcome = 'SUCCESS'",
                caseId)).isEqualTo(2);
        mvc.perform(get("/api/v1/workspace/psychosocial/shared-outcomes").with(HR)).andExpect(status().isForbidden());
    }

    // ------------------------------------------------------------------ F02 EAP partner

    @Test
    void eapReferralWithConsentAndPartnerFeedbackWithoutClinicalContent() throws Exception {
        String emp = employee("CATERING", "CATERING_ASSOC");
        String caseId = clinicianCase(emp).get("id").asString();
        UUID wrongConsent = consents.grantOnBehalf(emp, ConsentPurpose.INSURER_SHARING, "Claim", "test").getId();
        UUID consent = consents.grantOnBehalf(emp, ConsentPurpose.TREATMENT_SHARING_PARTNER, "EAP counselling", "test").getId();

        JsonNode picker = ok(mvc.perform(get("/api/v1/support/cases/" + caseId + "/consents").param("purpose", "TREATMENT_SHARING_PARTNER").with(PSYCH)));
        assertThat(texts(picker, "id")).containsExactly(consent.toString());
        mvc.perform(get("/api/v1/support/cases/" + caseId + "/consents").with(NURSE)).andExpect(status().isNotFound());

        postJson("/api/v1/support/cases/" + caseId + "/eap-referrals", PSYCH, toJson(Map.of("partnerCode", "EAP_WELLBEING", "sessionsAuthorised", 6,
                "urgency", "ROUTINE", "consentId", wrongConsent))).andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("consent_required"));
        postJson("/api/v1/support/cases/" + caseId + "/eap-referrals", PSYCH, toJson(Map.of("partnerCode", "GULFTPA", "sessionsAuthorised", 6,
                "urgency", "ROUTINE", "consentId", consent))).andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("unknown_partner"));
        postJson("/api/v1/support/cases/" + caseId + "/eap-referrals", PSYCH, toJson(Map.of("partnerCode", "EAP_WELLBEING", "sessionsAuthorised", 13,
                "urgency", "ROUTINE", "consentId", consent))).andExpect(status().isBadRequest());
        JsonNode r = ok(postJson("/api/v1/support/cases/" + caseId + "/eap-referrals", PSYCH, toJson(Map.of("partnerCode", "EAP_WELLBEING",
                "sessionsAuthorised", 6, "urgency", "PRIORITY", "consentId", consent))));
        String refId = r.get("id").asString();
        String ref = r.get("ref").asString();
        assertThat(ref).matches("EAP-" + YEAR + "-\\d{6}");
        assertThat(r.get("status").asString()).isEqualTo("SENT");
        LocalDate due = LocalDate.parse(r.get("firstAppointmentDueBy").asString());
        assertThat(due).isAfter(LocalDate.now(ZoneOffset.UTC).plusDays(4)).isBefore(LocalDate.now(ZoneOffset.UTC).plusDays(10));
        assertThat(count("select count(*) from platform.notification where address = 'referrals@wellbeingassist.example' and template_key = 'support.partner-referral' and body like ?",
                "%" + ref + "%")).isEqualTo(1);
        postJson("/api/v1/support/cases/" + caseId + "/eap-referrals", PSYCH, toJson(Map.of("partnerCode", "EAP_WELLBEING", "sessionsAuthorised", 2,
                "urgency", "ROUTINE", "consentId", consent))).andExpect(status().isConflict());
        assertThat(texts(ok(mvc.perform(get("/api/v1/support/eap-referrals").param("status", "SENT").with(PSYCH))), "ref")).contains(ref);
        mvc.perform(get("/api/v1/support/eap-referrals").with(NURSE)).andExpect(status().isNotFound());

        // the partner: own organisation only, minimal data (no employee id, no concern, no notes), disclosure-logged
        MvcResult list = mvc.perform(get("/api/v1/partner/eap-referrals").with(EAP)).andExpect(status().isOk()).andReturn();
        JsonNode mine = null;
        for (JsonNode x : body(list)) {
            if (ref.equals(x.get("ref").asString())) {
                mine = x;
            }
        }
        assertThat(mine).isNotNull();
        assertThat(mine.get("displayName").asString()).isEqualTo("Tess Tester " + emp);
        assertThat(mine.has("employeeId")).isFalse();
        assertThat(mine.toString()).doesNotContain("\"" + emp + "\"").doesNotContain("WORK_STRESS").doesNotContain("ZK-SECRET");
        assertThat(texts(body(list), "ref")).contains("EAP-" + YEAR + "-900001"); // demo referral
        assertThat(count("select count(*) from platform.disclosure_log where employee_id = ? and recipient_type = 'PARTNER' and resource_type = 'EAP_REFERRAL'",
                emp)).isGreaterThanOrEqualTo(2);
        assertThat(texts(ok(mvc.perform(get("/api/v1/partner/eap-referrals").with(OTHER_PARTNER))), "ref")).doesNotContain(ref);
        postJson("/api/v1/partner/eap-referrals/" + refId + "/feedback", OTHER_PARTNER, "{\"status\":\"ACCEPTED\"}").andExpect(status().isNotFound());
        assertThat(denied("EAP_FEEDBACK", refId)).isEqualTo(1);
        mvc.perform(get("/api/v1/partner/eap-referrals").with(PSYCH)).andExpect(status().isForbidden());

        // feedback: status, attendance, closure reason only – free text is refused
        postJson("/api/v1/partner/eap-referrals/" + refId + "/feedback", EAP, "{\"status\":\"ACCEPTED\",\"note\":\"Client discussed her marriage\"}")
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("field_not_allowed"));
        String today = LocalDate.now(ZoneOffset.UTC).toString();
        JsonNode fb = ok(postJson("/api/v1/partner/eap-referrals/" + refId + "/feedback", EAP,
                "{\"status\":\"IN_PROGRESS\",\"attendance\":[{\"date\":\"" + today + "\",\"attended\":true}]}"));
        assertThat(fb.get("status").asString()).isEqualTo("IN_PROGRESS");
        assertThat(fb.get("attendance")).hasSize(1);
        postJson("/api/v1/partner/eap-referrals/" + refId + "/feedback", EAP, "{\"status\":\"ACCEPTED\"}").andExpect(status().isConflict());
        postJson("/api/v1/partner/eap-referrals/" + refId + "/feedback", EAP, "{\"status\":\"CLOSED\"}")
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("closure_reason_required"));
        postJson("/api/v1/partner/eap-referrals/" + refId + "/feedback", EAP, "{\"status\":\"IN_PROGRESS\",\"attendance\":[{\"date\":\""
                + LocalDate.now(ZoneOffset.UTC).plusDays(3) + "\",\"attended\":true}]}").andExpect(status().isUnprocessableContent());
        JsonNode closed = ok(postJson("/api/v1/partner/eap-referrals/" + refId + "/feedback", EAP, "{\"status\":\"CLOSED\",\"closureReason\":\"COMPLETED\"}"));
        assertThat(closed.get("closureReason").asString()).isEqualTo("COMPLETED");
        postJson("/api/v1/partner/eap-referrals/" + refId + "/feedback", EAP, "{\"status\":\"CLOSED\",\"closureReason\":\"COMPLETED\"}")
                .andExpect(status().isConflict());
        JsonNode c = ok(mvc.perform(get("/api/v1/support/cases/" + caseId).with(PSYCH)));
        assertThat(c.get("eapReferral").get("status").asString()).isEqualTo("CLOSED");
        assertThat(c.get("status").asString()).isEqualTo("ACTIVE");
    }

    // ------------------------------------------------------------------ F03 peer support

    @Test
    void peerSupportIsPseudonymousAndNeverExposesEmployeeIds() throws Exception {
        String volunteer = "vol" + ThreadLocalRandom.current().nextInt(100_000, 999_999);
        JwtRequestPostProcessor vol = user(volunteer, "S" + volunteer, "/AIRLINE/OPS", "PEER_SUPPORT_VOLUNTEER");
        String today = LocalDate.now(ZoneOffset.UTC).toString();
        JsonNode v = ok(postJson("/api/v1/peer-support/volunteers", COORDINATOR, toJson(Map.of("username", volunteer, "displayName", "Zara Zurich",
                "email", volunteer + "@wellnessz.local", "base", "ZRH", "languages", List.of("de", "en"), "available", true,
                "trainingRecords", List.of(Map.of("course", "Peer support foundation", "completedOn", today))))));
        assertThat(v.get("trained").asBoolean()).isTrue();
        postJson("/api/v1/peer-support/volunteers", COORDINATOR, toJson(Map.of("username", volunteer, "displayName", "Dup", "base", "ZRH",
                "available", true))).andExpect(status().isConflict());
        postJson("/api/v1/peer-support/volunteers", PEER, toJson(Map.of("username", "x1", "displayName", "X", "base", "LHR", "available", true)))
                .andExpect(status().isForbidden());
        JsonNode all = ok(mvc.perform(get("/api/v1/peer-support/volunteers").with(COORDINATOR)));
        assertThat(texts(all, "username")).contains("peer1", "crewpeer2", "crewpeer3", volunteer);

        // crew: available trained peers – first names only (the expired volunteer is not offered)
        String emp = employee("CATERING", "CATERING_ASSOC");
        JwtRequestPostProcessor me = employeeUser(emp);
        MvcResult peers = mvc.perform(get("/api/v1/me/peer-support/peers").with(me)).andExpect(status().isOk()).andReturn();
        assertThat(texts(body(peers), "alias")).contains("Pete", "Anna", "Zara").doesNotContain("Sam");
        assertThat(text(peers)).doesNotContain("Peer\"").doesNotContain("Zurich").doesNotContain("username").doesNotContain("peer1");

        // pseudonymous contact request, routed by base
        JsonNode req = ok(postJson("/api/v1/me/peer-support/contact-request", me, toJson(Map.of("alias", "Night Owl", "callbackPhone", "+41 79 555 0101",
                "base", "ZRH", "language", "de"))));
        String code = req.get("contactCode").asString();
        assertThat(code).matches("PS-[A-Z0-9]{4}-[A-Z0-9]{4}");
        assertThat(jdbc.queryForObject("select volunteer_username from mentalhealth.peer_contact_request where contact_code = ?", String.class, code))
                .isEqualTo(volunteer);
        assertThat(count("select count(*) from mentalhealth.peer_contact_request where contact_code = ? and callback_phone like '%555%'", code)).isZero();
        assertThat(count("select count(*) from platform.notification where address = ? and template_key = 'support.urgent-callback' and body like ?",
                volunteer + "@wellnessz.local", "%" + code + "%")).isEqualTo(1);
        // no employee identifier anywhere in the peer-support tables
        assertThat(count("""
                select count(*) from information_schema.columns where table_schema = 'mentalhealth'
                   and table_name in ('peer_volunteer','peer_training','peer_contact_request','peer_contact','trusted_referral','sms_report')
                   and column_name like '%employee%'""")).isZero();
        assertThat(count("select count(*) from platform.audit_event where action = 'PEER_CONTACT_REQUEST' and coalesce(resource_id, '') like ?",
                "%" + code + "%")).isZero();

        MvcResult myReq = mvc.perform(get("/api/v1/peer-support/my-requests").with(vol)).andExpect(status().isOk()).andReturn();
        assertThat(texts(body(myReq), "contactCode")).containsExactly(code);
        assertThat(body(myReq).get(0).get("callbackPhone").asString()).isEqualTo("+41 79 555 0101");
        assertThat(body(myReq).get(0).get("alias").asString()).isEqualTo("Night Owl");
        assertThat(text(myReq)).doesNotContain(emp).doesNotContain("employeeId").doesNotContain("Tess");
        // demo request routed to peer1
        JsonNode peerReqs = ok(mvc.perform(get("/api/v1/peer-support/my-requests").with(PEER)));
        assertThat(texts(peerReqs, "contactCode")).contains("PS-DEMQ-7K2R").doesNotContain(code);

        // contact log & referral to the trusted professional
        postJson("/api/v1/peer-support/contacts", PEER, toJson(Map.of("contactCode", code, "date", today, "durationMinutes", 30, "category", "STRESS",
                "outcome", "SUPPORTED"))).andExpect(status().isNotFound()); // not routed to peer1
        postJson("/api/v1/peer-support/contacts", vol, toJson(Map.of("contactCode", emp, "date", today, "durationMinutes", 30, "category", "STRESS",
                "outcome", "SUPPORTED"))).andExpect(status().isBadRequest()); // an employee id is not a contact code
        JsonNode contact = ok(postJson("/api/v1/peer-support/contacts", vol, toJson(Map.of("contactCode", code, "date", today, "durationMinutes", 40,
                "category", "FATIGUE", "outcome", "REFERRED_TO_TRUSTED_PROFESSIONAL"))));
        assertThat(contact.get("base").asString()).isEqualTo("ZRH");
        assertThat(contact.has("employeeId")).isFalse();
        JsonNode walkUp = ok(postJson("/api/v1/peer-support/contacts", vol, toJson(Map.of("date", today, "durationMinutes", 15, "category", "OTHER",
                "outcome", "SIGNPOSTED"))));
        assertThat(walkUp.get("contactCode").asString()).matches("PS-[A-Z0-9]{4}-[A-Z0-9]{4}");
        assertThat(ok(mvc.perform(get("/api/v1/peer-support/contacts").with(vol)))).hasSize(2);
        mvc.perform(post("/api/v1/peer-support/contacts/" + contact.get("id").asString() + "/refer").with(PEER)).andExpect(status().isNotFound());
        ok(mvc.perform(post("/api/v1/peer-support/contacts/" + contact.get("id").asString() + "/refer").with(vol)));
        mvc.perform(post("/api/v1/peer-support/contacts/" + contact.get("id").asString() + "/refer").with(vol)).andExpect(status().isConflict());
        JsonNode queue = ok(mvc.perform(get("/api/v1/support/peer-referrals").with(PSYCH)));
        assertThat(texts(queue, "contactCode")).contains(code);
        assertThat(queue.toString()).doesNotContain(emp);
        mvc.perform(get("/api/v1/support/peer-referrals").with(NURSE)).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/peer-support/my-requests").with(me)).andExpect(status().isForbidden());

        // retention: callback numbers older than 30 days are purged by the job
        jdbc.update("update mentalhealth.peer_contact_request set requested_at = now() - interval '31 days' where contact_code = ?", code);
        assertThat(peerService.purgeCallbackPhones()).isGreaterThanOrEqualTo(1);
        assertThat(count("select count(*) from mentalhealth.peer_contact_request where contact_code = ? and callback_phone is null and phone_purged_at is not null",
                code)).isEqualTo(1);

        // no volunteer available for the requester's own registration: the volunteer cannot be routed to themselves
        assertThat(texts(ok(mvc.perform(get("/api/v1/me/peer-support/peers").with(user(volunteer, emp, "/AIRLINE/OPS", "EMPLOYEE")))), "alias"))
                .doesNotContain("Zara");
    }

    @Test
    void quarterlySmsReportIsAggregatedWithSmallCellsSuppressed() throws Exception {
        LocalDate prev = LocalDate.now(ZoneOffset.UTC).minusMonths(3);
        String quarter = prev.getYear() + "-Q" + ((prev.getMonthValue() - 1) / 3 + 1);
        JsonNode r = ok(mvc.perform(get("/api/v1/peer-support/reports/quarterly").param("quarter", quarter).with(SAFETY)));
        assertThat(r.get("quarter").asString()).isEqualTo(quarter);
        JsonNode stress = null;
        JsonNode fatigue = null;
        for (JsonNode row : r.get("rows")) {
            if ("LHR".equals(row.get("base").asString()) && "STRESS".equals(row.get("category").asString())) {
                stress = row;
            }
            if ("LHR".equals(row.get("base").asString()) && "FATIGUE".equals(row.get("category").asString())) {
                fatigue = row;
            }
        }
        assertThat(stress).isNotNull();
        assertThat(stress.get("contacts").asLong()).isEqualTo(6);
        assertThat(stress.get("suppressed").asBoolean()).isFalse();
        assertThat(fatigue).isNotNull();
        assertThat(fatigue.get("suppressed").asBoolean()).isTrue();
        assertThat(fatigue.has("contacts")).isFalse(); // test profile: min-group-size 3, two contacts are withheld
        assertThat(r.toString()).doesNotContain("PS-QDEM").doesNotContain("peer1").doesNotContain("crewpeer2");
        ok(mvc.perform(get("/api/v1/peer-support/reports/quarterly").param("quarter", quarter).with(CMO)));
        mvc.perform(get("/api/v1/peer-support/reports/quarterly").param("quarter", quarter).with(PEER)).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/peer-support/reports/quarterly").param("quarter", "2026-Q9").with(SAFETY)).andExpect(status().isBadRequest());

        // send to the SMS: only published cells, flat allow-listed payloads
        mvc.perform(post("/api/v1/peer-support/reports/quarterly/send").param("quarter", quarter).with(CMO)).andExpect(status().isForbidden());
        String thisQuarter = LocalDate.now(ZoneOffset.UTC).getYear() + "-Q" + ((LocalDate.now(ZoneOffset.UTC).getMonthValue() - 1) / 3 + 1);
        mvc.perform(post("/api/v1/peer-support/reports/quarterly/send").param("quarter", thisQuarter).with(SAFETY))
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("quarter_not_ended"));
        JsonNode sent = ok(mvc.perform(post("/api/v1/peer-support/reports/quarterly/send").param("quarter", quarter).with(SAFETY)));
        String reportRef = sent.get("reportRef").asString();
        assertThat(reportRef).matches("SMS-" + YEAR + "-\\d{6}");
        assertThat(sent.get("suppressed").asInt()).isGreaterThanOrEqualTo(2);
        List<OutboxEvent> events = outbox.findAll().stream().filter(e -> reportRef.equals(e.getAggregateId()) && "sms-safety".equals(e.getDestination()))
                .toList();
        assertThat(events).hasSize(sent.get("published").asInt()).isNotEmpty();
        for (OutboxEvent e : events) {
            JsonNode p = json.readTree(e.getPayload());
            assertThat(p.get("count").asLong()).isGreaterThanOrEqualTo(3);
            assertThat(e.getPayload()).doesNotContain("PS-").doesNotContain("volunteer");
        }
        mvc.perform(post("/api/v1/peer-support/reports/quarterly/send").param("quarter", quarter).with(SAFETY)).andExpect(status().isConflict());
        assertThat(ok(mvc.perform(get("/api/v1/peer-support/reports/quarterly").param("quarter", quarter).with(SAFETY))).get("reportRef").asString())
                .isEqualTo(reportRef);
    }

    // ------------------------------------------------------------------ F03 relief & F06 safety-critical fitness

    @Test
    void reliefFromDutiesAndSafetyCriticalFitnessFeedStatusOnly() throws Exception {
        String pilot = employee("FLIGHTOPS", "PILOT");
        String from = LocalDate.now(ZoneOffset.UTC).toString();
        String until = LocalDate.now(ZoneOffset.UTC).plusDays(10).toString();
        postJson("/api/v1/support/relief", NURSE, toJson(Map.of("employeeId", pilot, "from", from))).andExpect(status().isNotFound());
        JsonNode relief = ok(postJson("/api/v1/support/relief", AME, toJson(Map.of("employeeId", pilot, "from", from, "expectedUntil", until))));
        String reliefId = relief.get("id").asString();
        assertThat(relief.get("status").asString()).isEqualTo("ACTIVE");
        assertThat(relief.get("rosterStatus").asString()).isEqualTo("UNFIT");
        postJson("/api/v1/support/relief", PSYCH, toJson(Map.of("employeeId", pilot, "from", from))).andExpect(status().isConflict());
        // status-only roster feed: no reason, no concern
        List<OutboxEvent> roster = outbox.findAll().stream().filter(e -> "crew-rostering".equals(e.getDestination()) && e.getPayload().contains(pilot))
                .toList();
        assertThat(roster).isNotEmpty();
        assertThat(roster.get(roster.size() - 1).getPayload()).contains("UNFIT").doesNotContain("RELIEF").doesNotContain("SUP-")
                .doesNotContain("PSYCH");
        assertThat(texts(ok(mvc.perform(get("/api/v1/support/relief").param("status", "ACTIVE").with(AME))), "id")).contains(reliefId);
        mvc.perform(get("/api/v1/support/relief").with(CREW_SCHED)).andExpect(status().isNotFound());
        // crew scheduling sees the duty status only
        MvcResult crew = mvc.perform(get("/api/v1/status/crew").param("employeeIds", pilot).with(CREW_SCHED)).andExpect(status().isOk()).andReturn();
        assertThat(text(crew)).contains("UNFIT").doesNotContain("PSYCH").doesNotContain("MENTAL");

        postJson("/api/v1/support/relief/" + reliefId + "/return", PSYCH, "{\"decision\":\"FIT\"}").andExpect(status().isNotFound());
        postJson("/api/v1/support/relief/" + reliefId + "/return", AME, "{\"decision\":\"EXTEND\"}")
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("until_required"));
        JsonNode ext = ok(postJson("/api/v1/support/relief/" + reliefId + "/return", AME, "{\"decision\":\"EXTEND\",\"until\":\""
                + LocalDate.now(ZoneOffset.UTC).plusDays(20) + "\"}"));
        assertThat(ext.get("status").asString()).isEqualTo("ACTIVE");
        JsonNode fit = ok(postJson("/api/v1/support/relief/" + reliefId + "/return", AME, "{\"decision\":\"FIT\"}"));
        assertThat(fit.get("status").asString()).isEqualTo("RETURNED");
        assertThat(fit.get("rosterStatus").asString()).isEqualTo("FIT");
        postJson("/api/v1/support/relief/" + reliefId + "/return", AME, "{\"decision\":\"FIT\"}").andExpect(status().isConflict());

        // safety-critical mental-health fitness assessment
        String review = LocalDate.now(ZoneOffset.UTC).plusMonths(3).toString();
        Map<String, Object> base = new java.util.LinkedHashMap<>(Map.of("employeeId", pilot, "standardCode", "CAA_CLASS1",
                "medications", List.of(Map.of("name", "Sertraline", "drugClass", "SSRI", "dose", "50 mg")), "findings", "Stable mood ZK-FINDINGS",
                "decision", "FIT_WITH_LIMITATIONS", "reviewDate", review, "authorityReportRequired", false));
        postJson("/api/v1/support/fitness-assessments", AME, toJson(base)).andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("limitations_required"));
        Map<String, Object> unknown = new java.util.LinkedHashMap<>(base);
        unknown.put("standardCode", "NO_SUCH_STANDARD");
        unknown.put("limitations", "Multi-crew operations only");
        postJson("/api/v1/support/fitness-assessments", AME, toJson(unknown)).andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("unknown_standard"));
        postJson("/api/v1/support/fitness-assessments", PSYCH, toJson(unknown)).andExpect(status().isNotFound());
        Map<String, Object> refer = new java.util.LinkedHashMap<>(base);
        refer.put("decision", "REFER_TO_AUTHORITY");
        JsonNode a = ok(postJson("/api/v1/support/fitness-assessments", AME, toJson(refer)));
        assertThat(a.get("authorityReportRequired").asBoolean()).isTrue();
        assertThat(a.get("rosterStatus").asString()).isEqualTo("PENDING");
        String docId = a.get("documentId").asString();
        assertThat(jdbc.queryForObject("select classification from docs.document where id = ?::uuid", String.class, docId))
                .isEqualTo("RESTRICTED_CLINICAL");
        assertThat(jdbc.queryForObject("select template_code from docs.document where id = ?::uuid", String.class, docId))
                .isEqualTo("AUTHORITY_FITNESS_REPORT");
        assertThat(count("select count(*) from casemgmt.case_task where id = ?::uuid and task_type = 'FOLLOW_UP' and status = 'OPEN'",
                a.get("reviewTaskId").asString())).isEqualTo(1);
        assertThat(count("select count(*) from mentalhealth.fitness_assessment where employee_id = ? and findings like '%ZK-FINDINGS%'", pilot)).isZero();
        JsonNode history = ok(mvc.perform(get("/api/v1/support/fitness-assessments").param("employeeId", pilot).with(PSYCH)));
        assertThat(history.get(0).get("findings").asString()).contains("ZK-FINDINGS");
        assertThat(history.get(0).get("medications").get(0).get("name").asString()).isEqualTo("Sertraline");
        mvc.perform(get("/api/v1/support/fitness-assessments").param("employeeId", pilot).with(NURSE)).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/support/fitness-assessments").param("employeeId", pilot).with(HR)).andExpect(status().isNotFound());
        assertThat(denied("MH_FITNESS_HISTORY", pilot)).isEqualTo(2);
    }

    // ------------------------------------------------------------------ F04 CISM

    IntegrationEnvelope incident(String incidentId, String employeeId, String severity, String incidentClass, Instant at) {
        String payload = toJson(Map.of("incidentId", incidentId, "externalRef", "EHS-" + incidentId.substring(0, 8), "employeeId", employeeId,
                "incidentType", "Occurrence", "severity", severity, "incidentClass", incidentClass, "injury", false, "location", "Test stand 9",
                "incidentOccurredAt", at.toString()));
        return new IntegrationEnvelope(UUID.randomUUID(), "ehs.incident.received.v1", "EHS_INCIDENT", incidentId, payload, Map.of(), Instant.now());
    }

    void duty(String employeeId, String dutyRef, String group, String role, Instant from, Instant to) {
        jdbc.update("""
                insert into masterdata.duty_assignment (id, source, source_ref, duty_ref, duty_date, starts_at, ends_at, employee_id, duty_role,
                                                        location_code, proximity_group, received_at)
                values (?, 'SHIFT', ?, ?, ?, ?, ?, ?, ?, 'LHR', ?, now())""",
                UUID.randomUUID(), "TST-" + UUID.randomUUID(), dutyRef, java.sql.Date.valueOf(from.atZone(ZoneOffset.UTC).toLocalDate()),
                java.sql.Timestamp.from(from), java.sql.Timestamp.from(to), employeeId, role, group);
    }

    @Test
    void cismEventFromTheIncidentFeedWithAffectedListOffersAndFollowUps() throws Exception {
        Instant at = Instant.now().minus(5, ChronoUnit.HOURS).truncatedTo(ChronoUnit.MINUTES);
        String a = employee("CATERING", "CATERING_ASSOC");
        String b = employee("CATERING", "CATERING_ASSOC");
        String c = employee("CATERING", "CATERING_ASSOC");
        String outsider = employee("CATERING", "CATERING_ASSOC");
        String dutyRef = "TST-" + ThreadLocalRandom.current().nextInt(10_000, 99_999);
        String group = "GRP-" + dutyRef;
        duty(a, dutyRef, group, "SUPERVISOR", at.minus(3, ChronoUnit.HOURS), at.plus(3, ChronoUnit.HOURS));
        duty(b, dutyRef, group, "CATERING", at.minus(1, ChronoUnit.HOURS), at.plus(5, ChronoUnit.HOURS));
        duty(c, "OTHER-" + dutyRef, group, "DRIVER", at.plus(1, ChronoUnit.HOURS), at.plus(2, ChronoUnit.HOURS)); // same area
        duty(outsider, dutyRef, group, "CATERING", at.minus(10, ChronoUnit.HOURS), at.minus(4, ChronoUnit.HOURS)); // left long before

        String incidentId = UUID.randomUUID().toString();
        IntegrationEnvelope env = incident(incidentId, a, "LOW", "FUME_EVENT", at);
        incidentListener.onIncident(env);
        incidentListener.onIncident(env); // redelivery
        incidentListener.onIncident(incident(incidentId, a, "LOW", "FUME_EVENT", at)); // same incident, new message
        assertThat(count("select count(*) from mentalhealth.cism_event where incident_id = ?", incidentId)).isEqualTo(1);
        // not serious: no event
        String minor = UUID.randomUUID().toString();
        incidentListener.onIncident(incident(minor, a, "LOW", "SLIP_TRIP", at));
        assertThat(count("select count(*) from mentalhealth.cism_event where incident_id = ?", minor)).isZero();

        String eventId = jdbc.queryForObject("select id::text from mentalhealth.cism_event where incident_id = ?", String.class, incidentId);
        JsonNode e = ok(mvc.perform(get("/api/v1/cism/events/" + eventId).with(NURSE)));
        assertThat(e.get("category").asString()).isEqualTo("FUME_EVENT");
        assertThat(texts(e.get("affected"), "employeeId")).containsExactlyInAnyOrder(a, b, c);
        assertThat(Instant.parse(e.get("offerDueBy").asString())).isEqualTo(at.plus(72, ChronoUnit.HOURS));
        assertThat(e.has("slaMet")).isFalse(); // pending within the window
        JsonNode first = e.get("affected").get(0);
        LocalDate eventDay = at.atZone(ZoneOffset.UTC).toLocalDate();
        assertThat(first.get("followUp1w").get("dueOn").asString()).isEqualTo(eventDay.plusWeeks(1).toString());
        assertThat(first.get("followUp4w").get("dueOn").asString()).isEqualTo(eventDay.plusWeeks(4).toString());

        // safety manager: counts only
        MvcResult safetyList = mvc.perform(get("/api/v1/cism/events").with(SAFETY)).andExpect(status().isOk()).andReturn();
        JsonNode summary = null;
        for (JsonNode s : body(safetyList)) {
            if (eventId.equals(s.get("id").asString())) {
                summary = s;
            }
        }
        assertThat(summary).isNotNull();
        assertThat(summary.get("affected").asInt()).isEqualTo(3);
        assertThat(text(safetyList)).doesNotContain(a).doesNotContain("employeeId").doesNotContain("Tess");
        assertThat(texts(body(safetyList), "ref")).contains("CISM-" + YEAR + "-900001"); // demo fume event on WZ101
        mvc.perform(get("/api/v1/cism/events/" + eventId).with(SAFETY)).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/cism/events").with(HR)).andExpect(status().isForbidden());

        // offers within the SLA, content-free
        JsonNode offers = ok(mvc.perform(post("/api/v1/cism/events/" + eventId + "/offers").with(PSYCH)));
        assertThat(offers.get("sent").asInt()).isEqualTo(3);
        assertThat(offers.get("event").get("slaMet").asBoolean()).isTrue();
        assertThat(ok(mvc.perform(post("/api/v1/cism/events/" + eventId + "/offers").with(PSYCH))).get("sent").asInt()).isZero();
        assertThat(count("select count(*) from platform.notification where address = ? and template_key = 'cism.offer'", b.toLowerCase() + "@wellnessz.local"))
                .isEqualTo(1);

        // the employee responds to their own offer only
        JsonNode myOffers = ok(mvc.perform(get("/api/v1/me/cism/offers").with(employeeUser(b))));
        assertThat(myOffers).hasSize(1);
        String offerId = myOffers.get(0).get("id").asString();
        postJson("/api/v1/me/cism/offers/" + offerId + "/response", employeeUser(outsider), "{\"response\":\"ACCEPTED\"}").andExpect(status().isNotFound());
        JsonNode resp = ok(postJson("/api/v1/me/cism/offers/" + offerId + "/response", employeeUser(b), "{\"response\":\"ACCEPTED\",\"preferredContact\":\"PHONE\"}"));
        assertThat(resp.get("response").asString()).isEqualTo("ACCEPTED");

        // tracking and referral into the confidential pathway
        JsonNode upd = ok(putJson("/api/v1/cism/events/" + eventId + "/affected/" + c, NURSE, toJson(Map.of("sessionType", "GROUP",
                "attendedAt", Instant.now().minusSeconds(600).toString(), "referToSupport", true))));
        assertThat(upd.get("referred").asBoolean()).isTrue();
        assertThat(upd.get("supportCaseRef").asString()).matches("SUP-" + YEAR + "-\\d{6}");
        assertThat(count("select count(*) from mentalhealth.support_case where employee_id = ? and source = 'CISM'", c)).isEqualTo(1);
        putJson("/api/v1/cism/events/" + eventId + "/affected/" + outsider, NURSE, "{\"followUp1wDone\":true}").andExpect(status().isNotFound());

        // follow-up job: 1-week follow-up due → cism.follow-up (not for declined)
        jdbc.update("update mentalhealth.cism_affected set follow_up_1w_due = current_date - 1 where event_id = ?::uuid", eventId);
        putJson("/api/v1/cism/events/" + eventId + "/affected/" + a, NURSE, "{\"response\":\"DECLINED\"}").andExpect(status().isOk());
        assertThat(cismService.sendFollowUps()).isGreaterThanOrEqualTo(2);
        assertThat(count("select count(*) from platform.notification where address = ? and template_key = 'cism.follow-up'", b.toLowerCase() + "@wellnessz.local"))
                .isEqualTo(1);
        assertThat(count("select count(*) from platform.notification where address = ? and template_key = 'cism.follow-up'", a.toLowerCase() + "@wellnessz.local"))
                .isZero();
        cismService.sendFollowUps();
        assertThat(count("select count(*) from platform.notification where address = ? and template_key = 'cism.follow-up'", b.toLowerCase() + "@wellnessz.local"))
                .isEqualTo(1);

        // manual event by the safety manager: affected list from the duty, response without names
        MvcResult manual = postJson("/api/v1/cism/events", SAFETY, toJson(Map.of("title", "Assault on crew bus", "category", "ASSAULT",
                "occurredAt", at.toString(), "location", "Crew bus stop", "dutyRefs", List.of(dutyRef)))).andExpect(status().isCreated()).andReturn();
        assertThat(body(manual).get("affected").asInt()).isEqualTo(2);
        assertThat(text(manual)).doesNotContain(a).doesNotContain(b);
        postJson("/api/v1/cism/events", SAFETY, toJson(Map.of("title", "Suicide of a colleague", "category", "FATALITY", "occurredAt", at.toString())))
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("clinical_content_not_allowed"));
    }

    // ------------------------------------------------------------------ F05 psychosocial register

    @Test
    void psychosocialAssessmentsActionsDashboardAndManagerView() throws Exception {
        String reviewOn = LocalDate.now(ZoneOffset.UTC).plusMonths(6).toString();
        String assessedOn = LocalDate.now(ZoneOffset.UTC).toString();
        JsonNode a = ok(postJson("/api/v1/psychosocial/assessments", HSE, toJson(Map.of("unitCode", "CARGO", "assessedOn", assessedOn,
                "reviewDate", reviewOn, "factors", List.of(
                        Map.of("factor", "WORKLOAD", "likelihood", 4, "severity", 4, "controls", "Night-shift staffing review"),
                        Map.of("factor", "CHANGE", "likelihood", 2, "severity", 2, "controls", "Monthly briefings"))))));
        assertThat(a.get("overallScore").asInt()).isEqualTo(16);
        assertThat(a.get("rag").asString()).isEqualTo("RED");
        assertThat(a.get("factors").get(1).get("score").asInt()).isEqualTo(4);
        assertThat(a.get("factors").get(1).get("rag").asString()).isEqualTo("GREEN");
        postJson("/api/v1/psychosocial/assessments", HSE, toJson(Map.of("unitCode", "CARGO", "assessedOn", assessedOn, "reviewDate", reviewOn,
                "factors", List.of(Map.of("factor", "WORKLOAD", "likelihood", 4, "severity", 4), Map.of("factor", "WORKLOAD", "likelihood", 1, "severity", 1)))))
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("duplicate_factor"));
        postJson("/api/v1/psychosocial/assessments", HSE, toJson(Map.of("unitCode", "CARGO", "assessedOn", assessedOn, "reviewDate", reviewOn,
                "factors", List.of(Map.of("factor", "SUPPORT", "likelihood", 6, "severity", 1))))).andExpect(status().isBadRequest());
        postJson("/api/v1/psychosocial/assessments", HSE, toJson(Map.of("unitCode", "CARGO", "assessedOn", assessedOn, "reviewDate", reviewOn,
                "factors", List.of(Map.of("factor", "SUPPORT", "likelihood", 2, "severity", 2, "controls", "Refer staff with depression to counselling")))))
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("clinical_content_not_allowed"));
        postJson("/api/v1/psychosocial/assessments", MANAGER, toJson(Map.of("unitCode", "CARGO", "assessedOn", assessedOn, "reviewDate", reviewOn,
                "factors", List.of(Map.of("factor", "SUPPORT", "likelihood", 2, "severity", 2))))).andExpect(status().isForbidden());

        String id = a.get("id").asString();
        JsonNode act = ok(postJson("/api/v1/psychosocial/assessments/" + id + "/actions", HSE, toJson(Map.of("title", "Add a second night supervisor",
                "owner", "Cargo operations manager", "dueDate", LocalDate.now(ZoneOffset.UTC).minusDays(1).toString()))));
        assertThat(act.get("status").asString()).isEqualTo("OPEN");
        assertThat(act.get("overdue").asBoolean()).isTrue();
        JsonNode done = ok(putJson("/api/v1/psychosocial/assessments/" + id + "/actions/" + act.get("id").asString(), CMO, toJson(Map.of(
                "title", "Add a second night supervisor", "owner", "Cargo operations manager", "dueDate", LocalDate.now(ZoneOffset.UTC).toString(),
                "status", "DONE"))));
        assertThat(done.get("status").asString()).isEqualTo("DONE");
        assertThat(done.get("completedAt").asString()).isNotBlank();
        assertThat(ok(mvc.perform(get("/api/v1/psychosocial/assessments").param("unitCode", "CARGO").with(HR))).get(0).get("id").asString()).isEqualTo(id);

        JsonNode dash = ok(mvc.perform(get("/api/v1/psychosocial/dashboard").with(HSE)));
        Map<String, JsonNode> byUnit = new java.util.HashMap<>();
        dash.forEach(row -> byUnit.put(row.get("unitCode").asString(), row));
        assertThat(byUnit.get("CATERING").get("current").get("rag").asString()).isEqualTo("RED");
        assertThat(byUnit.get("CATERING").get("overdueActions").asInt()).isEqualTo(1);
        assertThat(byUnit.get("GROUND").get("reviewOverdue").asBoolean()).isTrue();
        assertThat(byUnit.get("CARGO").get("current").get("id").asString()).isEqualTo(id);
        // E1005 is the only CORP employee: the support-case rate is suppressed
        assertThat(byUnit.get("CORP").get("suppressed").asBoolean()).isTrue();
        assertThat(byUnit.get("CORP").has("supportCaseRatePer100")).isFalse();
        assertThat(dash.toString()).doesNotContain("E1005").doesNotContain("Olivia");
        mvc.perform(get("/api/v1/psychosocial/dashboard").with(MANAGER)).andExpect(status().isForbidden());

        JsonNode mgr = ok(mvc.perform(get("/api/v1/workspace/psychosocial").with(MANAGER)));
        assertThat(mgr.get("unitCode").asString()).isEqualTo("CATERING");
        assertThat(mgr.get("assessment").get("rag").asString()).isEqualTo("RED");
        assertThat(mgr.get("actions")).hasSize(2);
        mvc.perform(get("/api/v1/workspace/psychosocial").with(employeeUser("E1001"))).andExpect(status().isForbidden());
    }

    // ------------------------------------------------------------------ kernel SPI & demo data

    @Test
    void supportProgrammeReferralSpiOpensOneRestrictedCasePerSource() {
        String emp = employee("FLIGHTOPS", "PILOT");
        String sourceRef = "DA-" + UUID.randomUUID();
        Optional<UUID> id = programmeReferral.refer("mro1", emp, null, "SUBSTANCE_POSITIVE", "DA_TEST", sourceRef);
        assertThat(id).isPresent();
        assertThat(programmeReferral.refer("mro1", emp, null, "SUBSTANCE_POSITIVE", "DA_TEST", sourceRef)).isEqualTo(id);
        assertThat(jdbc.queryForObject("select source || '/' || concern from mentalhealth.support_case where id = ?", String.class, id.get()))
                .isEqualTo("PROGRAMME_REFERRAL/SUBSTANCE");
        assertThat(count("""
                select count(*) from casemgmt.oh_case k join mentalhealth.support_case s on s.kernel_case_id = k.id
                 where s.id = ? and k.source = 'DA_TEST' and k.classification = 'RESTRICTED_CLINICAL'""", id.get())).isEqualTo(1);
        String ref = jdbc.queryForObject("select case_ref from mentalhealth.support_case where id = ?", String.class, id.get());
        String alert = jdbc.queryForObject("select body from platform.notification where address = 'psych1@wellnessz.local' and body like ?",
                String.class, "%" + ref + "%");
        assertThat(alert).contains("programme referral").doesNotContain("SUBSTANCE").doesNotContain("positive");
        assertThat(programmeReferral.refer("mro1", "NO-SUCH-EMP", null, "OTHER", "DA_TEST", "x")).isEmpty();
    }

    @Test
    void demoDataIsReachableForTheEndToEndJourneys() throws Exception {
        JsonNode cases = ok(mvc.perform(get("/api/v1/support/cases").with(PSYCH)));
        assertThat(texts(cases, "ref")).contains("SUP-" + YEAR + "-900001", "SUP-" + YEAR + "-900002", "SUP-" + YEAR + "-900003");
        JsonNode office = ok(mvc.perform(get("/api/v1/support/cases/" + DEMO_OFFICE_CASE).with(PSYCH)));
        assertThat(office.get("employeeId").asString()).isEqualTo("E1005");
        assertThat(office.get("screens").get(0).get("band").asString()).isEqualTo("MODERATE");
        assertThat(office.get("eapReferral").get("ref").asString()).isEqualTo("EAP-" + YEAR + "-900001");
        assertThat(office.get("contactPhone").asString()).isEqualTo("+44 7700 900105"); // encrypted value written by the demo seeder
        JsonNode urgent = ok(mvc.perform(get("/api/v1/support/cases/" + DEMO_URGENT_CASE).with(PSYCH)));
        assertThat(urgent.get("risk").asString()).isEqualTo("URGENT");
        assertThat(urgent.get("urgent")).isNotEmpty();
        assertThat(texts(ok(mvc.perform(get("/api/v1/support/relief").with(AME))), "employeeId")).contains("E7001");
        JsonNode peerReq = ok(mvc.perform(get("/api/v1/peer-support/my-requests").with(PEER)));
        for (JsonNode r : peerReq) {
            if ("PS-DEMQ-7K2R".equals(r.get("contactCode").asString())) {
                assertThat(r.get("callbackPhone").asString()).isEqualTo("+44 7700 900777");
            }
        }
        assertThat(ok(mvc.perform(get("/api/v1/support/placeholder").param("employeeId", "E1010").with(NURSE))).get("restrictedCases").asLong())
                .isGreaterThanOrEqualTo(1);
    }
}
