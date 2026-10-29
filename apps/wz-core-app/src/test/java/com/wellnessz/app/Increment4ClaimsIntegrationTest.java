package com.wellnessz.app;

import static com.wellnessz.app.PlatformIntegrationTest.user;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

import com.wellnessz.casekernel.support.CaseCostRecorder;
import com.wellnessz.casekernel.support.ClaimContextProvider;
import com.wellnessz.casekernel.support.ReferralStatusListener;
import com.wellnessz.common.model.DataClassification;
import com.wellnessz.documents.DocumentService;
import com.wellnessz.documents.PdfRenderer;
import com.wellnessz.documents.model.DocumentRepository;
import com.wellnessz.e08.report.ReportService;
import com.wellnessz.security.consent.ConsentPurpose;
import com.wellnessz.security.consent.ConsentService;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Acceptance tests for E08 – claims coordination (F01–F06) against the real endpoints: claim lifecycle and roles, statutory /
 * insurer e-reporting with consent and legal basis, consent-gated disclosure sets with redaction, the adjuster portal (own
 * payer only, never clinical content), nurse ↔ adjuster collaboration, the cost ledger incl. the E04 recorder SPI, finance
 * roll-ups without names, the ERP export, litigation hold / medico-legal requests and the HR status-only view.
 */
class Increment4ClaimsIntegrationTest extends IntegrationTestBase {

    @Autowired
    MockMvc mvc;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    JsonMapper json;
    @Autowired
    DocumentService documents;
    @Autowired
    DocumentRepository documentRepository;
    @Autowired
    PdfRenderer renderer;
    @Autowired
    ConsentService consents;
    @Autowired
    CaseCostRecorder costRecorder;
    @Autowired
    ClaimContextProvider claimContext;
    @Autowired
    List<ReferralStatusListener> referralListeners;
    @Autowired
    ReportService reportService;
    @Autowired
    com.wellnessz.integration.outbox.OutboxRepository outbox;

    static final JwtRequestPostProcessor CLAIMS = user("claims1", "S8701", "/AIRLINE", "CLAIMS_COORDINATOR");
    static final JwtRequestPostProcessor ADJUSTER = user("adjuster1", "X8702", "/PARTNERS/GULFTPA", "TPA_ADJUSTER");
    static final JwtRequestPostProcessor ADJUSTER_OTHER = user("adjuster9", "X8799", "/PARTNERS/AVIA_INSURE", "TPA_ADJUSTER");
    static final JwtRequestPostProcessor LEGAL = user("legal1", "S8703", "/AIRLINE", "LEGAL_COUNSEL");
    static final JwtRequestPostProcessor FINANCE = user("finance1", "S8704", "/AIRLINE", "FINANCE");
    static final JwtRequestPostProcessor NURSE = user("nurse1", "N1", null, "OH_CLINICIAN");
    static final JwtRequestPostProcessor CASE_MANAGER = user("clinician1", "C1", null, "OH_CLINICIAN", "CASE_MANAGER");
    static final JwtRequestPostProcessor NURSING_HEAD = user("nursinghead1", "NH1", null, "NURSING_HEAD", "OH_CLINICIAN");
    static final JwtRequestPostProcessor PHYSICIAN = user("physician1", "PH1", null, "OH_PHYSICIAN");
    static final JwtRequestPostProcessor CMO = user("cmo1", "CM1", null, "CMO");
    static final JwtRequestPostProcessor DPO = user("dpo1", "D1", null, "DPO");
    static final JwtRequestPostProcessor HSE = user("hse1", "HS1", "/AIRLINE", "HSE_OFFICER");
    static final JwtRequestPostProcessor HR_OPS = user("hr1", "H1", "/AIRLINE/OPS", "HR");
    static final JwtRequestPostProcessor HR_CARGO = user("hr9", "H9", "/AIRLINE/CARGO", "HR");
    /** The payer's system (org_scope /PARTNERS/GULFTPA). */
    static final JwtRequestPostProcessor INTEGRATION = user("insurer-system", null, "/PARTNERS/GULFTPA", "INTEGRATION");
    static final JwtRequestPostProcessor OTHER_INSURER_SYSTEM = user("groupmed-system", null, "/PARTNERS/GROUPMED", "INTEGRATION");

    static final int YEAR = LocalDate.now(ZoneOffset.UTC).getYear();
    static final String DEMO_WC_CLAIM = "8e080000-0000-4000-b000-000000000001";
    static final String DEMO_EL_CLAIM = "8e080000-0000-4000-b000-000000000002";
    static final String DEMO_SET = "8e080000-0000-4000-e000-000000000001";

    // ------------------------------------------------------------------ helpers

    JsonNode body(MvcResult r) throws Exception {
        return json.readTree(r.getResponse().getContentAsString());
    }

    ResultActions postJson(String url, JwtRequestPostProcessor who, String content) throws Exception {
        return mvc.perform(post(url).with(who).contentType(MediaType.APPLICATION_JSON).content(content));
    }

    ResultActions putJson(String url, JwtRequestPostProcessor who, String content) throws Exception {
        return mvc.perform(put(url).with(who).contentType(MediaType.APPLICATION_JSON).content(content));
    }

    String toJson(Object o) {
        return json.writeValueAsString(o);
    }

    /** A fresh ground-handling employee (inside the HR /AIRLINE/OPS scope). */
    String employee() {
        String id = "T8" + ThreadLocalRandom.current().nextInt(100_000, 999_999);
        jdbc.update("""
                insert into masterdata.employee (employee_id, display_name, email, subsidiary_code, department_code, cost_centre, job_code,
                                                 location_code, shift_pattern, preferred_language, badge_id, manager_employee_id, updated_at)
                values (?, ?, ?, 'GND', 'GROUND', 'CC-GND-400', 'RAMP_AGENT', 'LHR', 'LATE', 'en', ?, null, now())""",
                id, "Tom Tarmac " + id, id.toLowerCase() + "@wellnessz.local", "BT-" + id);
        return id;
    }

    UUID injuryCase(String employeeId, String owner) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                insert into casemgmt.oh_case (id, case_ref, employee_id, employee_display_name, org_unit_code, org_unit_path, subsidiary_code, cost_centre,
                                              job_code, location_code, category, case_type, source, status, classification, fitness_status,
                                              restriction_until, urgency, owner_username, occurred_on, body_part, created_at, updated_at)
                values (?, ?, ?, ?, 'GROUND', '/AIRLINE/OPS/GROUND', 'GND', 'CC-GND-400', 'RAMP_AGENT', 'LHR', 'INJURY', 'INJURY', 'SELF_REFERRAL',
                        'TREATMENT', 'CLINICAL', 'FIT_WITH_RESTRICTIONS', current_date + 14, 'ROUTINE', ?, current_date - 5, 'Left wrist', now(), now())""",
                id, "TCL-" + id.toString().substring(0, 8), employeeId, "Tom Tarmac " + employeeId, owner);
        return id;
    }

    JsonNode createClaim(UUID caseId, String type, String payer, boolean lostTime) throws Exception {
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("claimType", type);
        b.put("payerCode", payer);
        b.put("policyNumber", "POL-" + caseId.toString().substring(0, 6));
        b.put("adjusterName", "Adam Adjuster");
        b.put("adjusterEmail", "adjuster1@gulftpa.example");
        b.put("adjusterUsername", "adjuster1");
        b.put("lostTime", lostTime);
        return body(postJson("/api/v1/cases/" + caseId + "/claims", CLAIMS, toJson(b)).andExpect(status().isCreated()).andReturn());
    }

    long count(String sql, Object... args) {
        Long n = jdbc.queryForObject(sql, Long.class, args);
        return n == null ? 0 : n;
    }

    UUID fitnessCertificate(String employeeId, UUID caseId) {
        return documents.generateSigned("nurse1", "FITNESS_CERTIFICATE", employeeId, caseId, Map.of("employeeName", "Tom Tarmac",
                "employeeId", employeeId, "standardTitle", "Ramp agent", "outcome", "Fit with restrictions", "validFrom", LocalDate.now().toString(),
                "issuedBy", "Nina Nurse")).id();
    }

    UUID providerReport(String employeeId, UUID caseId) {
        byte[] pdf = renderer.render("Orthopaedic report", "EXT-1", 1, List.of(new PdfRenderer.Section(null,
                "Findings: lumbar strain after lifting. Confidential detail ZETA-77 noted.")), "External report").pdf();
        return documents.storeUpload("test", employeeId, caseId, "PROVIDER_REPORT", "Orthopaedic report", pdf, DataClassification.CLINICAL)
                .document().id();
    }

    /** Outbox payloads are encrypted at rest; read them through the repository. */
    String payload(String destination, String aggregateId) {
        return outbox.findAll().stream().filter(e -> aggregateId.equals(e.getAggregateId()) && destination.equals(e.getDestination()))
                .map(com.wellnessz.integration.outbox.OutboxEvent::getPayload).findFirst().orElseThrow();
    }

    UUID consent(String employeeId) {
        return consents.grantOnBehalf(employeeId, ConsentPurpose.INSURER_SHARING, "Claim", "test").getId();
    }

    // ------------------------------------------------------------------ F01 claim record & lifecycle

    @Test
    void claimLifecycleRolesAndCaseBadge() throws Exception {
        String emp = employee();
        UUID caseId = injuryCase(emp, "nurse1");
        JsonNode claim = createClaim(caseId, "WORKERS_COMP", "GULFTPA", true);
        String id = claim.get("id").asString();
        assertThat(claim.get("claimRef").asString()).matches("CLM-" + YEAR + "-\\d{6}");
        assertThat(claim.get("status").asString()).isEqualTo("NOTIFIED");
        assertThat(claim.get("payerName").asString()).contains("Gulf");
        // lost-time: the case owner (OH clinician) becomes the nurse case manager, notified content-free
        assertThat(claim.get("nurseCaseManager").asString()).isEqualTo("nurse1");
        assertThat(count("select count(*) from platform.notification where address = 'nurse1@wellnessz.local' and template_key = 'claim.assigned' and body like ?",
                "%" + claim.get("claimRef").asString() + "%")).isEqualTo(1);
        assertThat(count("select count(*) from platform.outbox_event where event_type = 'claim.nurse.assigned.v1' and aggregate_id = ?", id)).isEqualTo(1);
        assertThat(count("select count(*) from casemgmt.case_link where case_id = ? and link_type = 'CLAIM'", caseId)).isEqualTo(1);

        // one claim per case and type
        postJson("/api/v1/cases/" + caseId + "/claims", CLAIMS, "{\"claimType\":\"WORKERS_COMP\",\"payerCode\":\"GULFTPA\",\"lostTime\":false}")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("claim_exists"));
        // unknown payer / non-insurer, screened notes
        postJson("/api/v1/cases/" + caseId + "/claims", CLAIMS, "{\"claimType\":\"THIRD_PARTY\",\"payerCode\":\"CITYHOSP\",\"lostTime\":false}")
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("unknown_payer"));
        postJson("/api/v1/cases/" + caseId + "/claims", CLAIMS,
                "{\"claimType\":\"THIRD_PARTY\",\"payerCode\":\"GROUPMED\",\"lostTime\":false,\"notes\":\"Diagnosis: fracture of the wrist\"}")
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("clinical_content_not_allowed"))
                .andExpect(jsonPath("$.field").value("notes"));
        // roles that may not create claims
        postJson("/api/v1/cases/" + caseId + "/claims", FINANCE, "{\"claimType\":\"THIRD_PARTY\",\"payerCode\":\"GROUPMED\",\"lostTime\":false}")
                .andExpect(status().isForbidden());

        // workflow
        postJson("/api/v1/claims/" + id + "/status", CLAIMS, "{\"status\":\"SETTLED\"}")
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("invalid_transition"));
        postJson("/api/v1/claims/" + id + "/status", CLAIMS, "{\"status\":\"OPEN\"}").andExpect(jsonPath("$.status").value("OPEN"))
                .andExpect(jsonPath("$.openedAt").exists());
        postJson("/api/v1/claims/" + id + "/status", LEGAL, "{\"status\":\"UNDER_REVIEW\"}").andExpect(status().isForbidden());
        postJson("/api/v1/claims/" + id + "/status", CLAIMS, "{\"status\":\"UNDER_REVIEW\"}").andExpect(status().isOk());
        assertThat(count("select count(*) from platform.outbox_event where event_type = 'claim.status.v1' and aggregate_id = ?", id)).isEqualTo(3);

        // legal counsel sees litigated / held claims only
        mvc.perform(get("/api/v1/claims/" + id).with(LEGAL)).andExpect(status().isNotFound());
        postJson("/api/v1/claims/" + id + "/status", LEGAL, "{\"status\":\"LITIGATED\",\"reason\":\"Letter of claim received\"}")
                .andExpect(jsonPath("$.status").value("LITIGATED"));
        mvc.perform(get("/api/v1/claims/" + id).with(LEGAL)).andExpect(status().isOk()).andExpect(jsonPath("$.reserves").doesNotExist());
        mvc.perform(get("/api/v1/claims").with(LEGAL)).andExpect(jsonPath("$[*].id", Matchers.hasItem(id)))
                .andExpect(jsonPath("$[*].status", Matchers.everyItem(Matchers.anyOf(Matchers.is("LITIGATED"), Matchers.notNullValue()))));
        JsonNode legalList = body(mvc.perform(get("/api/v1/claims").with(LEGAL)).andReturn());
        for (JsonNode c : legalList) {
            assertThat(c.get("status").asString().equals("LITIGATED") || c.get("litigationHold").asBoolean()).isTrue();
        }

        // money only for the claims team / finance / CMO
        putJson("/api/v1/claims/" + id, CLAIMS, "{\"reserves\":{\"MEDICAL\":{\"amount\":\"2500.00\",\"currency\":\"GBP\"}},"
                + "\"paidTotal\":{\"amount\":\"300\",\"currency\":\"GBP\"}}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.reserves.MEDICAL.amount").value("2500.00"))
                .andExpect(jsonPath("$.paidTotal.amount").value("300.00"));
        mvc.perform(get("/api/v1/claims/" + id).with(NURSING_HEAD)).andExpect(status().isOk())
                .andExpect(jsonPath("$.reserves").doesNotExist()).andExpect(jsonPath("$.paidTotal").doesNotExist())
                .andExpect(jsonPath("$.history.length()").value(Matchers.greaterThanOrEqualTo(4)));
        mvc.perform(get("/api/v1/claims/" + id).with(CMO)).andExpect(jsonPath("$.reserves.MEDICAL.amount").value("2500.00"));
        mvc.perform(get("/api/v1/claims").with(FINANCE)).andExpect(status().isForbidden());

        // settle, close, reopen with reason
        postJson("/api/v1/claims/" + id + "/status", CLAIMS, "{\"status\":\"SETTLED\"}").andExpect(jsonPath("$.settledAt").exists());
        postJson("/api/v1/claims/" + id + "/status", CLAIMS, "{\"status\":\"CLOSED\"}").andExpect(jsonPath("$.closedAt").exists());
        postJson("/api/v1/claims/" + id + "/status", CLAIMS, "{\"status\":\"OPEN\"}")
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("reason_required"));
        postJson("/api/v1/claims/" + id + "/status", CLAIMS, "{\"status\":\"OPEN\",\"reason\":\"New invoice received\"}")
                .andExpect(jsonPath("$.status").value("OPEN")).andExpect(jsonPath("$.closedAt").doesNotExist());

        // case header badge: OH sees status, the claims team also the paid total; a case without a claim → 204
        mvc.perform(get("/api/v1/cases/" + caseId + "/claim-status").with(NURSE)).andExpect(status().isOk())
                .andExpect(jsonPath("$.claimRef").value(claim.get("claimRef").asString())).andExpect(jsonPath("$.paidTotal").doesNotExist());
        mvc.perform(get("/api/v1/cases/" + caseId + "/claim-status").with(CLAIMS)).andExpect(jsonPath("$.paidTotal.amount").value("300.00"));
        mvc.perform(get("/api/v1/cases/" + injuryCase(emp, null) + "/claim-status").with(CLAIMS)).andExpect(status().isNoContent());
        assertThat(claimContext.forCase(caseId)).hasValueSatisfying(c -> {
            assertThat(c.payerCode()).isEqualTo("GULFTPA");
            assertThat(c.lostTime()).isTrue();
        });

        // subrogation → legal task and content-free notice to the legal mailbox
        postJson("/api/v1/claims/" + id + "/subrogation", CLAIMS, "{\"thirdPartyName\":\"Tug Services Ltd\",\"thirdPartyType\":\"CONTRACTOR\","
                + "\"description\":\"Baggage tug driven by a contractor reversed into the stand\"}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.subrogation.thirdPartyType").value("CONTRACTOR"));
        assertThat(count("select count(*) from casemgmt.case_task where case_id = ? and assignee = 'legal'", caseId)).isEqualTo(1);
        assertThat(count("select count(*) from platform.notification where address = 'legal@wellnessz.local' and template_key = 'legal.request' and body like ?",
                "%" + claim.get("claimRef").asString() + "%")).isEqualTo(1);
    }

    @Test
    void nurseAssignmentFallsBackToLeastLoadedCaseManagerThroughE02() throws Exception {
        jdbc.update("""
                insert into masterdata.staff_member (username, display_name, email, roles, site_codes, languages, specialties, caseload_cap)
                values ('cmtest8', 'Casey Manager', 'cmtest8@wellnessz.local', 'CASE_MANAGER', 'LHR', 'en', 'CASE_MANAGEMENT', 50)
                on conflict (username) do nothing""");
        UUID caseId = injuryCase(employee(), "physician1");
        JsonNode claim = createClaim(caseId, "WORKERS_COMP", "GULFTPA", true);
        String nurse = claim.get("nurseCaseManager").asString();
        assertThat(nurse).isIn("clinician1", "cmtest8");
        assertThat(jdbc.queryForObject("select owner_username from casemgmt.oh_case where id = ?", String.class, caseId)).isEqualTo(nurse);
        assertThat(count("select count(*) from triage.case_assignment where case_id = ? and to_username = ?", caseId, nurse)).isEqualTo(1);

        // not lost-time → no nurse; flagging lost-time later assigns one
        UUID other = injuryCase(employee(), null);
        JsonNode noNurse = createClaim(other, "EMPLOYER_LIABILITY", "AVIA_INSURE", false);
        assertThat(noNurse.has("nurseCaseManager")).isFalse();
        putJson("/api/v1/claims/" + noNurse.get("id").asString(), CLAIMS, "{\"lostTime\":true}")
                .andExpect(jsonPath("$.nurseCaseManager").exists());

        mvc.perform(get("/api/v1/claims/nurse-assignment").with(NURSING_HEAD)).andExpect(status().isOk())
                .andExpect(jsonPath("$.lostTimeClaims").value(Matchers.greaterThanOrEqualTo(2)))
                .andExpect(jsonPath("$.assignedWithin48h").value(Matchers.greaterThanOrEqualTo(2)))
                .andExpect(jsonPath("$.pct").exists());
        mvc.perform(get("/api/v1/claims/nurse-assignment").with(FINANCE)).andExpect(status().isForbidden());
    }

    // ------------------------------------------------------------------ F04 adjuster portal & collaboration

    @Test
    void adjusterSeesOwnPayerClaimsOnlyWithRtwMilestonesTasksAndMessages() throws Exception {
        String emp = employee();
        UUID caseId = injuryCase(emp, "nurse1");
        JsonNode claim = createClaim(caseId, "WORKERS_COMP", "GULFTPA", true);
        String id = claim.get("id").asString();
        jdbc.update("""
                insert into fitness.rtw_plan (id, case_id, case_ref, employee_id, status, start_date, goal, phases, created_by, created_at, activated_at)
                values (?, ?, 'TCL', ?, 'ACTIVE', current_date, 'Return to ramp duties',
                        '[{"fromWeek":1,"toWeek":2,"hoursPerDay":4,"duties":"Desk duties only","restrictions":[]},{"fromWeek":3,"toWeek":3,"hoursPerDay":8,"duties":"Physiotherapy appointments twice weekly","restrictions":[]}]',
                        'nurse1', now(), now())""", UUID.randomUUID(), caseId, emp);

        // own organisation: list and detail with RTW status / milestones only
        mvc.perform(get("/api/v1/partner/claims").with(ADJUSTER)).andExpect(status().isOk()).andExpect(jsonPath("$[*].id", Matchers.hasItem(id)));
        MvcResult detail = mvc.perform(get("/api/v1/partner/claims/" + id).with(ADJUSTER)).andExpect(status().isOk())
                .andExpect(jsonPath("$.rtw.fitnessStatus").value("FIT_WITH_RESTRICTIONS"))
                .andExpect(jsonPath("$.rtw.restrictionUntil").exists())
                .andExpect(jsonPath("$.rtw.rtwPlan.status").value("ACTIVE"))
                .andExpect(jsonPath("$.rtw.rtwPlan.phases.length()").value(2))
                .andExpect(jsonPath("$.rtw.rtwPlan.phases[0].dutiesSummary").value("Desk duties only"))
                // clinical wording never reaches the adjuster
                .andExpect(jsonPath("$.rtw.rtwPlan.phases[1].dutiesSummary").doesNotExist())
                .andReturn();
        String text = detail.getResponse().getContentAsString();
        assertThat(text).doesNotContain("Left wrist").doesNotContain("clinicalSummary").doesNotContain("diagnosis").doesNotContain("reserves")
                .doesNotContain("notes");
        assertThat(count("select count(*) from platform.disclosure_log where resource_type = 'CLAIM_RTW_STATUS' and resource_id = ? and recipient_type = 'INSURER'",
                id)).isEqualTo(1);
        mvc.perform(get("/api/v1/claims/" + id + "/rtw").with(ADJUSTER)).andExpect(status().isOk());
        assertThat(count("select count(*) from platform.disclosure_log where resource_type = 'CLAIM_RTW_STATUS' and resource_id = ?", id)).isEqualTo(1);

        // another organisation's adjuster: existence not revealed
        mvc.perform(get("/api/v1/partner/claims/" + id).with(ADJUSTER_OTHER)).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/claims/" + id + "/tasks").with(ADJUSTER_OTHER)).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/claims/" + id + "/rtw").with(ADJUSTER_OTHER)).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/partner/claims").with(ADJUSTER_OTHER)).andExpect(jsonPath("$[*].id", Matchers.not(Matchers.hasItem(id))));
        mvc.perform(get("/api/v1/claims/" + id).with(ADJUSTER)).andExpect(status().isForbidden());

        // shared tasks
        JsonNode task = body(postJson("/api/v1/claims/" + id + "/tasks", CLAIMS, "{\"title\":\"Send the wage statement\",\"dueDate\":\""
                + LocalDate.now().plusDays(5) + "\",\"assigneeRole\":\"ADJUSTER\"}").andExpect(status().isCreated()).andReturn());
        assertThat(count("select count(*) from platform.notification where address = 'adjuster1@gulftpa.example' and template_key = 'claim.task'"))
                .isGreaterThanOrEqualTo(1);
        JsonNode nurseTask = body(postJson("/api/v1/claims/" + id + "/tasks", ADJUSTER, "{\"title\":\"Confirm phase 2 start date\",\"dueDate\":\""
                + LocalDate.now().plusDays(3) + "\",\"assigneeRole\":\"NURSE_CM\"}").andExpect(status().isCreated()).andReturn());
        postJson("/api/v1/claims/" + id + "/tasks", ADJUSTER, "{\"title\":\"Ask about the depression treatment\",\"dueDate\":\""
                + LocalDate.now().plusDays(3) + "\",\"assigneeRole\":\"NURSE_CM\"}")
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.field").value("title"));
        mvc.perform(post("/api/v1/claims/tasks/" + nurseTask.get("id").asString() + "/complete").with(ADJUSTER)).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/claims/tasks/" + nurseTask.get("id").asString() + "/complete").with(NURSE))
                .andExpect(jsonPath("$.status").value("DONE"));
        mvc.perform(post("/api/v1/claims/tasks/" + task.get("id").asString() + "/complete").with(ADJUSTER))
                .andExpect(jsonPath("$.status").value("DONE"));
        mvc.perform(get("/api/v1/claims/" + id + "/tasks").with(ADJUSTER)).andExpect(jsonPath("$.length()").value(2));

        // secure messages: screened, encrypted, content-free notification, unread counter for the adjuster
        postJson("/api/v1/claims/" + id + "/messages", ADJUSTER, "{\"text\":\"Was the diagnosis confirmed by MRI?\"}")
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("clinical_content_not_allowed"));
        postJson("/api/v1/claims/" + id + "/messages", ADJUSTER, "{\"text\":\"When is the planned return to full duties?\"}")
                .andExpect(status().isCreated()).andExpect(jsonPath("$.senderRole").value("ADJUSTER"));
        postJson("/api/v1/claims/" + id + "/messages", NURSE, "{\"text\":\"Full duties are planned from week 4.\"}")
                .andExpect(status().isCreated()).andExpect(jsonPath("$.senderRole").value("NURSE_CM"));
        assertThat(jdbc.queryForObject("select body from claims.claim_message where claim_id = ?::uuid and sender = 'nurse1'", String.class, id))
                .startsWith("v1:").doesNotContain("week 4");
        assertThat(count("select count(*) from platform.notification where template_key = 'claim.message' and body like ?",
                "%" + claim.get("claimRef").asString() + "%")).isGreaterThanOrEqualTo(2);
        mvc.perform(get("/api/v1/partner/claims").with(ADJUSTER)).andExpect(jsonPath("$[?(@.id == '" + id + "')].unreadMessages").value(Matchers.hasItem(1)));
        mvc.perform(get("/api/v1/claims/" + id + "/messages").with(ADJUSTER)).andExpect(jsonPath("$.length()").value(2));
        mvc.perform(get("/api/v1/partner/claims").with(ADJUSTER)).andExpect(jsonPath("$[?(@.id == '" + id + "')].unreadMessages").value(Matchers.hasItem(0)));
        mvc.perform(get("/api/v1/claims/" + id + "/messages").with(FINANCE)).andExpect(status().isForbidden());
    }

    // ------------------------------------------------------------------ F03 disclosure sets

    @Test
    void disclosureSetsAreConsentGatedRedactableAndRevocable() throws Exception {
        String emp = employee();
        UUID caseId = injuryCase(emp, "nurse1");
        JsonNode claim = createClaim(caseId, "WORKERS_COMP", "GULFTPA", true);
        String id = claim.get("id").asString();
        UUID cert = fitnessCertificate(emp, caseId);
        UUID report = providerReport(emp, caseId);
        UUID foreign = fitnessCertificate(employee(), null);

        mvc.perform(get("/api/v1/claims/" + id + "/documents").with(CLAIMS)).andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.documentId == '" + cert + "')].disclosable").value(Matchers.hasItem(true)))
                .andExpect(jsonPath("$[?(@.documentId == '" + report + "')].disclosable").value(Matchers.hasItem(false)));

        // claims team: provider reports only through OH; documents of other employees never
        postJson("/api/v1/claims/" + id + "/disclosure-sets", CLAIMS, toJson(Map.of("recipientCode", "GULFTPA", "purpose", "Claim assessment",
                "documentIds", List.of(cert, report)))).andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("document_not_disclosable"));
        postJson("/api/v1/claims/" + id + "/disclosure-sets", CLAIMS, toJson(Map.of("recipientCode", "GULFTPA", "purpose", "Claim assessment",
                "documentIds", List.of(foreign)))).andExpect(status().isUnprocessableContent());
        JsonNode set = body(postJson("/api/v1/claims/" + id + "/disclosure-sets", CLAIMS, toJson(Map.of("recipientCode", "GULFTPA",
                "purpose", "Claim assessment", "documentIds", List.of(cert)))).andExpect(status().isCreated()).andReturn());
        String setId = set.get("id").asString();

        // no INSURER_SHARING consent → 422
        postJson("/api/v1/claims/disclosure-sets/" + setId + "/send", CLAIMS, "{}")
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("consent_required"));
        postJson("/api/v1/claims/disclosure-sets/" + setId + "/send", CLAIMS, "{\"consentId\":\"" + UUID.randomUUID() + "\"}")
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("consent_required"));
        mvc.perform(get("/api/v1/claims/" + id + "/consents").with(CLAIMS)).andExpect(jsonPath("$.length()").value(0));
        UUID consentId = consent(emp);
        mvc.perform(get("/api/v1/claims/" + id + "/consents").with(NURSE)).andExpect(jsonPath("$[0].id").value(consentId.toString()))
                .andExpect(jsonPath("$[0].purpose").value("INSURER_SHARING")).andExpect(jsonPath("$[0].grantedAt").exists());
        mvc.perform(get("/api/v1/claims/" + id + "/consents").with(ADJUSTER)).andExpect(status().isForbidden());
        postJson("/api/v1/claims/disclosure-sets/" + setId + "/send", CLAIMS, "{\"consentId\":\"" + consentId + "\"}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("SENT")).andExpect(jsonPath("$.consentId").value(consentId.toString()));
        assertThat(count("select count(*) from platform.disclosure_log where employee_id = ? and recipient_type = 'INSURER' and purpose = 'CLAIM' and consent_id = ?",
                emp, consentId)).isEqualTo(1);
        mvc.perform(get("/api/v1/partner/claims/" + id).with(ADJUSTER)).andExpect(jsonPath("$.documents[*].documentId", Matchers.hasItem(cert.toString())));
        mvc.perform(get("/api/v1/partner/documents").with(ADJUSTER)).andExpect(jsonPath("$[*].id", Matchers.hasItem(cert.toString())));
        mvc.perform(get("/api/v1/partner/documents").with(ADJUSTER_OTHER)).andExpect(jsonPath("$[*].id", Matchers.not(Matchers.hasItem(cert.toString()))));
        postJson("/api/v1/claims/disclosure-sets/" + setId + "/send", CLAIMS, "{\"consentId\":\"" + consentId + "\"}").andExpect(status().isConflict());

        // OH adds the provider report and redacts it before sending: the REDACTED_COPY replaces it
        JsonNode ohSet = body(postJson("/api/v1/claims/" + id + "/disclosure-sets", NURSE, toJson(Map.of("recipientCode", "GULFTPA",
                "purpose", "Specialist opinion for liability", "documentIds", List.of(report)))).andExpect(status().isCreated()).andReturn());
        String ohSetId = ohSet.get("id").asString();
        postJson("/api/v1/claims/disclosure-sets/" + ohSetId + "/redactions", CLAIMS, toJson(Map.of("documentId", report, "phrases", List.of("ZETA-77"))))
                .andExpect(status().isForbidden());
        JsonNode redacted = body(postJson("/api/v1/claims/disclosure-sets/" + ohSetId + "/redactions", NURSE,
                toJson(Map.of("documentId", report, "phrases", List.of("lumbar strain", "ZETA-77")))).andExpect(status().isOk()).andReturn());
        JsonNode entry = redacted.get("documents").get(0);
        assertThat(entry.get("type").asString()).isEqualTo("REDACTED_COPY");
        assertThat(entry.get("redactedFromId").asString()).isEqualTo(report.toString());
        String copyText = documentRepository.findById(UUID.fromString(entry.get("documentId").asString())).orElseThrow().getExtractedText();
        assertThat(copyText).contains("[REDACTED]").doesNotContainIgnoringCase("lumbar strain").doesNotContain("ZETA-77");
        assertThat(documentRepository.findById(report).orElseThrow().getExtractedText()).contains("ZETA-77");
        postJson("/api/v1/claims/disclosure-sets/" + ohSetId + "/send", NURSE, "{\"consentId\":\"" + consentId + "\"}")
                .andExpect(jsonPath("$.status").value("SENT"));

        mvc.perform(get("/api/v1/claims/" + id + "/disclosure-sets").with(DPO)).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(2));
        mvc.perform(get("/api/v1/claims/" + id + "/disclosure-sets").with(FINANCE)).andExpect(status().isForbidden());

        // revoke: shares end, the adjuster no longer sees the documents, the log marks the disclosure revoked
        postJson("/api/v1/claims/disclosure-sets/" + setId + "/revoke", DPO, "{}").andExpect(jsonPath("$.status").value("REVOKED"));
        mvc.perform(get("/api/v1/partner/claims/" + id).with(ADJUSTER)).andExpect(jsonPath("$.documents[*].documentId",
                Matchers.not(Matchers.hasItem(cert.toString()))));
        assertThat(count("select count(*) from docs.document_share where document_id = ? and target = 'INSURER' and revoked_at is not null", cert)).isEqualTo(1);
        assertThat(count("select count(*) from platform.disclosure_log where employee_id = ? and purpose = 'CLAIM' and revoked_at is not null", emp))
                .isEqualTo(1);

        // DPO disclosure report lists claim disclosures
        mvc.perform(get("/api/v1/claims/disclosure-report").with(DPO)).andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.employeeId == '" + emp + "')].purpose", Matchers.hasItem("CLAIM")));
        mvc.perform(get("/api/v1/claims/disclosure-report").with(CLAIMS)).andExpect(status().isForbidden());
    }

    @Test
    void privilegedAndNeverDisclosedMaterialIsExcluded() throws Exception {
        String emp = employee();
        UUID caseId = injuryCase(emp, "nurse1");
        String id = createClaim(caseId, "EMPLOYER_LIABILITY", "AVIA_INSURE", false).get("id").asString();
        JsonNode lr = body(postJson("/api/v1/claims/" + id + "/legal-requests", LEGAL, toJson(Map.of("type", "MEDICO_LEGAL_REPORT",
                "questions", "Is the account consistent with the mechanism?", "dueDate", LocalDate.now().plusDays(10).toString())))
                .andExpect(status().isCreated()).andReturn());
        JsonNode written = body(postJson("/api/v1/legal-requests/" + lr.get("id").asString() + "/report", PHYSICIAN,
                "{\"opinion\":\"The account is consistent with a twisting mechanism.\"}").andExpect(status().isOk()).andReturn());
        String mlr = written.get("reports").get(0).get("documentId").asString();
        // medico-legal reports are never disclosed, even by OH
        postJson("/api/v1/claims/" + id + "/disclosure-sets", NURSE, toJson(Map.of("recipientCode", "AVIA_INSURE", "purpose", "Liability",
                "documentIds", List.of(mlr)))).andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("document_not_disclosable"));
        // a provider IME report answering a legal request becomes privileged material once legal marks the claim privileged
        UUID ime = providerReport(emp, caseId);
        referralListeners.stream().filter(l -> l.supports("CLAIM_IME")).findFirst().orElseThrow();
        JsonNode imeReq = body(postJson("/api/v1/claims/" + id + "/legal-requests", LEGAL, toJson(Map.of("type", "IME", "questions",
                "Independent examination of the right wrist function", "dueDate", LocalDate.now().plusDays(20).toString(), "providerCode", "STMARYS_ORTHO")))
                .andExpect(status().isCreated()).andReturn());
        referralListeners.stream().filter(l -> l.supports("CLAIM_IME")).forEach(l -> l.referralStatusChanged(UUID.randomUUID(), "CLAIM_IME",
                imeReq.get("id").asString(), ReferralStatusListener.Status.REPORT_RECEIVED, ime));
        postJson("/api/v1/claims/" + id + "/privilege", LEGAL, "{\"privileged\":true,\"basis\":\"Litigation privilege\"}")
                .andExpect(jsonPath("$.privileged").value(true));
        postJson("/api/v1/claims/" + id + "/disclosure-sets", NURSE, toJson(Map.of("recipientCode", "AVIA_INSURE", "purpose", "Liability",
                "documentIds", List.of(ime)))).andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.detail").value(Matchers.containsString("privileged")));
        postJson("/api/v1/claims/" + id + "/privilege", LEGAL, "{\"privileged\":false}").andExpect(jsonPath("$.privileged").value(false));
        postJson("/api/v1/claims/" + id + "/disclosure-sets", NURSE, toJson(Map.of("recipientCode", "AVIA_INSURE", "purpose", "Liability",
                "documentIds", List.of(ime)))).andExpect(status().isCreated());
    }

    // ------------------------------------------------------------------ F02 e-reporting

    @Test
    void insurerAndStatutoryReportsWithConsentLegalBasisAcknowledgementAndDeadlines() throws Exception {
        mvc.perform(get("/api/v1/claims/report-forms").with(HSE)).andExpect(status().isOk())
                .andExpect(jsonPath("$[*].code", Matchers.hasItems("GB_OSH_F2508", "AE_LABOUR_INJURY", "INSURER_FNOL")))
                .andExpect(jsonPath("$[?(@.code == 'GB_OSH_F2508')].deadlineHours").value(Matchers.hasItem(360)))
                .andExpect(jsonPath("$[*].signOffStatus", Matchers.everyItem(Matchers.is("PENDING_LEGAL_HSE_SIGN_OFF"))));
        mvc.perform(get("/api/v1/claims/report-forms").with(FINANCE)).andExpect(status().isForbidden());

        String emp = employee();
        UUID caseId = injuryCase(emp, "nurse1");
        JsonNode claim = createClaim(caseId, "WORKERS_COMP", "GULFTPA", true);
        String id = claim.get("id").asString();

        // insurer FNOL: auto-populated from HRIS / case, deadline 72 h from notification
        JsonNode fnol = body(postJson("/api/v1/claims/" + id + "/reports", CLAIMS, "{\"formCode\":\"INSURER_FNOL\"}").andExpect(status().isCreated()).andReturn());
        String fnolId = fnol.get("id").asString();
        assertThat(fnol.get("ref").asString()).matches("FROI-" + YEAR + "-\\d{6}");
        assertThat(fnol.get("status").asString()).isEqualTo("DRAFT");
        assertThat(fnol.get("recipientCode").asString()).isEqualTo("GULFTPA");
        assertThat(fnol.get("fields").get("employeeName").asString()).isEqualTo("Tom Tarmac " + emp);
        assertThat(fnol.get("fields").get("bodyPart").asString()).isEqualTo("Left wrist");
        assertThat(fnol.get("fields").get("jobTitle")).isNotNull();
        assertThat(fnol.get("hoursLeft").asLong()).isBetween(70L, 72L);
        postJson("/api/v1/claims/" + id + "/reports", CLAIMS, "{\"formCode\":\"INSURER_FNOL\"}").andExpect(status().isConflict());

        postJson("/api/v1/claims/reports/" + fnolId + "/submit", CLAIMS, "{\"channel\":\"API\"}")
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("report_incomplete"));
        putJson("/api/v1/claims/reports/" + fnolId, CLAIMS, "{\"fields\":{\"description\":\"Diagnosis: scaphoid fracture\"}}")
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.field").value("fields.description"));
        putJson("/api/v1/claims/reports/" + fnolId, CLAIMS, "{\"fields\":{\"unknownField\":\"x\"}}").andExpect(status().isBadRequest());
        putJson("/api/v1/claims/reports/" + fnolId, CLAIMS, toJson(Map.of("fields", Map.of("description",
                "Slipped on a wet stand while carrying a bag and fell onto the outstretched hand", "occurredAt", LocalDate.now().minusDays(5) + " 07:30 UTC",
                "location", "LHR T5 stand 507", "injuryNature", "Sprain")))).andExpect(status().isOk());
        postJson("/api/v1/claims/reports/" + fnolId + "/submit", CLAIMS, "{\"channel\":\"API\"}")
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("consent_required"));
        UUID consentId = consent(emp);
        JsonNode submitted = body(postJson("/api/v1/claims/reports/" + fnolId + "/submit", CLAIMS, "{\"channel\":\"API\",\"consentId\":\"" + consentId + "\"}")
                .andExpect(status().isOk()).andReturn());
        assertThat(submitted.get("status").asString()).isEqualTo("SUBMITTED");
        String docId = submitted.get("documentId").asString();
        assertThat(count("select count(*) from docs.document_share where document_id = ?::uuid and target = 'INSURER' and recipient = 'GULFTPA'", docId))
                .isEqualTo(1);
        String payload = payload("insurer-gateway", fnol.get("ref").asString());
        assertThat(payload).contains(claim.get("claimRef").asString()).doesNotContain("Slipped").doesNotContain("Tom Tarmac");
        postJson("/api/v1/claims/reports/" + fnolId + "/submit", CLAIMS, "{\"channel\":\"API\"}").andExpect(status().isConflict());

        // acknowledgement by the insurer system – another insurer's system cannot acknowledge it
        postJson("/api/v1/integration/claims/reports/" + fnol.get("ref").asString() + "/acknowledgement", OTHER_INSURER_SYSTEM,
                "{\"ackRef\":\"GM-ACK-1\",\"status\":\"ACKNOWLEDGED\"}").andExpect(status().isNotFound());
        postJson("/api/v1/integration/claims/reports/" + fnol.get("ref").asString() + "/acknowledgement", INTEGRATION,
                "{\"ackRef\":\"GULF-ACK-1\",\"status\":\"ACKNOWLEDGED\"}").andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACKNOWLEDGED")).andExpect(jsonPath("$.ackRef").value("GULF-ACK-1"));
        postJson("/api/v1/integration/claims/reports/" + fnol.get("ref").asString() + "/acknowledgement", CLAIMS,
                "{\"ackRef\":\"GULF-ACK-1\",\"status\":\"ACKNOWLEDGED\"}").andExpect(status().isForbidden());

        // statutory report to the authority: no consent needed, disclosure log with the legal basis, authority gateway without employee data
        JsonNode f2508 = body(postJson("/api/v1/claims/" + id + "/reports", HSE, "{\"formCode\":\"GB_OSH_F2508\",\"specifiedInjury\":false}")
                .andExpect(status().isCreated()).andReturn());
        assertThat(f2508.get("recipientType").asString()).isEqualTo("AUTHORITY");
        assertThat(f2508.get("recipientCode").asString()).isEqualTo("AUTH_OSH");
        putJson("/api/v1/claims/reports/" + f2508.get("id").asString(), HSE, toJson(Map.of("fields", Map.of("description", "Fall on a wet stand",
                "occurredAt", LocalDate.now().minusDays(5).toString(), "location", "LHR T5", "injuryNature", "Sprain"), "specifiedInjury", true)))
                .andExpect(status().isOk());
        postJson("/api/v1/claims/reports/" + f2508.get("id").asString() + "/submit", HSE, "{\"channel\":\"PORTAL\"}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("SUBMITTED"));
        assertThat(count("select count(*) from platform.disclosure_log where employee_id = ? and recipient_type = 'AUTHORITY' and purpose like 'LEGAL_OBLIGATION%' and consent_id is null",
                emp)).isEqualTo(1);
        String authority = payload("authority-gateway", f2508.get("ref").asString());
        assertThat(authority).contains("GB_OSH_F2508").doesNotContain(emp);

        // manual acknowledgement (JSON variant)
        postJson("/api/v1/claims/reports/" + f2508.get("id").asString() + "/acknowledgement", CLAIMS, "{\"ackRef\":\"OSH-778812\"}")
                .andExpect(jsonPath("$.status").value("ACKNOWLEDGED"));

        // deadline job: an overdue UAE notice becomes LATE and is escalated to the claims mailbox
        UUID uaeCase = injuryCase(emp, "nurse1");
        String uae = createClaim(uaeCase, "EMPLOYER_LIABILITY", "AVIA_INSURE", false).get("id").asString();
        jdbc.update("update claims.claim set incident_date = current_date - 5 where id = ?::uuid", uae);
        JsonNode late = body(postJson("/api/v1/claims/" + uae + "/reports", CLAIMS, "{\"formCode\":\"AE_LABOUR_INJURY\"}").andReturn());
        assertThat(late.get("hoursLeft").asLong()).isNegative();
        reportService.processDeadlines();
        mvc.perform(get("/api/v1/claims/reports/" + late.get("id").asString()).with(CLAIMS)).andExpect(jsonPath("$.status").value("LATE"));
        assertThat(count("select count(*) from platform.notification where address = 'claims@wellnessz.local' and template_key = 'claim.report-due'"))
                .isGreaterThanOrEqualTo(1);
        mvc.perform(get("/api/v1/claims/reports?status=LATE").with(CLAIMS)).andExpect(jsonPath("$[*].id", Matchers.hasItem(late.get("id").asString())));
        mvc.perform(get("/api/v1/claims/reports?dueWithinHours=24").with(HSE)).andExpect(status().isOk())
                .andExpect(jsonPath("$[*].status", Matchers.everyItem(Matchers.anyOf(Matchers.is("DRAFT"), Matchers.is("LATE"), Matchers.is("REJECTED")))));
        mvc.perform(get("/api/v1/claims/reports/kpis").with(CLAIMS)).andExpect(jsonPath("$.onTimeSubmissionPct").exists());
    }

    // ------------------------------------------------------------------ F05 costs

    @Test
    void costLedgerIncludesE04EntriesAndFinanceSeesUnitsWithoutNames() throws Exception {
        String emp = employee();
        UUID caseId = injuryCase(emp, "nurse1");
        String id = createClaim(caseId, "WORKERS_COMP", "GULFTPA", true).get("id").asString();

        // simulated E04 call: pre-authorisation estimate, idempotent on (sourceType, sourceRef, kind)
        String ref = UUID.randomUUID().toString();
        UUID first = costRecorder.record("physician1", new CaseCostRecorder.CostEntry(caseId, emp, CaseCostRecorder.Category.MEDICAL,
                CaseCostRecorder.Kind.ESTIMATE, new BigDecimal("400"), "GBP", LocalDate.now(), "PHYSIOPLUS", "Physiotherapy course", "PREAUTH", ref));
        UUID again = costRecorder.record("physician1", new CaseCostRecorder.CostEntry(caseId, emp, CaseCostRecorder.Category.MEDICAL,
                CaseCostRecorder.Kind.ESTIMATE, new BigDecimal("450"), "GBP", LocalDate.now(), "PHYSIOPLUS", "Diagnosis: tendinitis", "PREAUTH", ref));
        assertThat(again).isEqualTo(first);
        costRecorder.record("physician1", new CaseCostRecorder.CostEntry(caseId, emp, CaseCostRecorder.Category.MEDICAL, CaseCostRecorder.Kind.INVOICE,
                new BigDecimal("1000"), "AED", LocalDate.now(), "DXB_AMERICAN", null, "PROVIDER_INVOICE", UUID.randomUUID().toString()));
        // a case without a claim keeps a case-level entry
        UUID noClaimCase = injuryCase(emp, "nurse1");
        costRecorder.record("physician1", new CaseCostRecorder.CostEntry(noClaimCase, emp, CaseCostRecorder.Category.REHAB, CaseCostRecorder.Kind.INVOICE,
                new BigDecimal("120"), "GBP", LocalDate.now(), null, null, "PROVIDER_INVOICE", UUID.randomUUID().toString()));
        assertThat(count("select count(*) from claims.cost_entry where case_id = ? and claim_id is null", noClaimCase)).isEqualTo(1);

        postJson("/api/v1/claims/" + id + "/costs", FINANCE, toJson(Map.of("category", "INDEMNITY", "kind", "PAYMENT", "amount", "750.00",
                "currency", "GBP", "incurredOn", LocalDate.now().toString(), "description", "Weekly benefit"))).andExpect(status().isCreated())
                .andExpect(jsonPath("$.amount.amount").value("750.00")).andExpect(jsonPath("$.level").value("CLAIM"));
        postJson("/api/v1/claims/" + id + "/costs", CLAIMS, toJson(Map.of("category", "MEDICAL", "kind", "INVOICE", "amount", "10",
                "currency", "CHF", "incurredOn", LocalDate.now().toString()))).andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("currency_not_supported"));
        postJson("/api/v1/claims/" + id + "/costs", CLAIMS, toJson(Map.of("category", "MEDICAL", "kind", "INVOICE", "amount", "10",
                "currency", "GBP", "incurredOn", LocalDate.now().toString(), "description", "Antibiotics prescribed"))).andExpect(status().isUnprocessableContent());
        MvcResult ledger = mvc.perform(get("/api/v1/claims/" + id + "/costs").with(CLAIMS)).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[?(@.sourceType == 'PREAUTH')].amount.amount").value(Matchers.hasItem("450.00")))
                .andExpect(jsonPath("$[?(@.sourceType == 'PROVIDER_INVOICE')].amount.currency").value(Matchers.hasItem("AED"))).andReturn();
        assertThat(ledger.getResponse().getContentAsString()).doesNotContain("tendinitis");
        mvc.perform(get("/api/v1/claims/" + id + "/costs").with(NURSE)).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/claims/" + id + "/costs/summary").with(FINANCE)).andExpect(jsonPath("$.totalsByKind.INVOICE.amount").value("210.00"));

        // roll-up by unit: no names or employee ids; small groups suppressed (k = 3 in tests)
        MvcResult rollup = mvc.perform(get("/api/v1/claims/costs/rollup?groupBy=SUBSIDIARY").with(FINANCE)).andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.key == 'GND')].suppressed").value(Matchers.hasItem(false)))
                .andExpect(jsonPath("$[?(@.key == 'GND')].total.currency").value(Matchers.hasItem("GBP")))
                .andExpect(jsonPath("$[?(@.key == 'GND')].MEDICAL").exists())
                .andExpect(jsonPath("$[?(@.key == 'CGO')].suppressed").value(Matchers.hasItem(true)))
                .andExpect(jsonPath("$[?(@.key == 'CGO')].total").value(Matchers.empty()))
                .andReturn();
        String rollupText = rollup.getResponse().getContentAsString();
        assertThat(rollupText).doesNotContain(emp).doesNotContain("Tom Tarmac").doesNotContain("E1004").doesNotContain("Rami").doesNotContain("employeeId");
        mvc.perform(get("/api/v1/claims/costs/rollup?groupBy=DEPARTMENT").with(CMO)).andExpect(jsonPath("$[?(@.key == 'GROUND')].label")
                .value(Matchers.hasItem("Ground Handling")));
        mvc.perform(get("/api/v1/claims/costs/rollup?groupBy=PERSON").with(FINANCE)).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/claims/costs/rollup").with(NURSE)).andExpect(status().isForbidden());

        // budgets and budget vs actual
        putJson("/api/v1/claims/budgets/" + YEAR + "/GND", FINANCE, "{\"amount\":\"30000\",\"currency\":\"GBP\"}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.amount.amount").value("30000.00"));
        putJson("/api/v1/claims/budgets/" + YEAR + "/GND", CLAIMS, "{\"amount\":\"1\",\"currency\":\"GBP\"}").andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/claims/costs/budget-vs-actual?year=" + YEAR + "&groupBy=SUBSIDIARY").with(FINANCE)).andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.unitCode == 'GND')].budget.amount").value(Matchers.hasItem("30000.00")))
                .andExpect(jsonPath("$[?(@.unitCode == 'GND')].trend.length()").value(Matchers.hasItem(LocalDate.now(ZoneOffset.UTC).getMonthValue())));

        // ERP export with SHA-256 checksum over the CSV, event to erp-finance, reconciliation
        String month = YearMonth.now(ZoneOffset.UTC).toString();
        JsonNode export = body(postJson("/api/v1/claims/costs/exports", FINANCE, "{\"month\":\"" + month + "\"}").andExpect(status().isCreated()).andReturn());
        assertThat(export.get("status").asString()).isEqualTo("GENERATED");
        MvcResult csv = mvc.perform(get("/api/v1/claims/costs/exports/" + export.get("id").asString() + "/content").with(FINANCE))
                .andExpect(status().isOk()).andReturn();
        String content = csv.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(content).startsWith("month,cost_centre,subsidiary,category,currency,amount,entries").doesNotContain(emp).doesNotContain("Tom");
        assertThat(export.get("checksum").asString()).isEqualTo(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(content.getBytes(StandardCharsets.UTF_8))));
        assertThat(count("select count(*) from platform.outbox_event where destination = 'erp-finance' and aggregate_id = ?",
                export.get("batchId").asString())).isEqualTo(1);
        postJson("/api/v1/claims/costs/exports", FINANCE, "{\"month\":\"" + month + "\"}").andExpect(status().isConflict());
        postJson("/api/v1/claims/costs/exports/" + export.get("id").asString() + "/reconcile", FINANCE,
                "{\"erpTotal\":{\"amount\":\"1.00\",\"currency\":\"GBP\"},\"erpReference\":\"SAP-JE-1\"}").andExpect(jsonPath("$.status").value("MISMATCH"));
        postJson("/api/v1/claims/costs/exports/" + export.get("id").asString() + "/reconcile", FINANCE,
                "{\"erpTotal\":{\"amount\":\"1.00\",\"currency\":\"USD\"},\"erpReference\":\"SAP-JE-1\"}").andExpect(status().isUnprocessableContent());
        postJson("/api/v1/claims/costs/exports/" + export.get("id").asString() + "/reconcile", FINANCE,
                toJson(Map.of("erpTotal", Map.of("amount", export.get("total").get("amount").asString(), "currency", "GBP"), "erpReference", "SAP-JE-2")))
                .andExpect(jsonPath("$.status").value("RECONCILED"));
        mvc.perform(get("/api/v1/claims/costs/exports").with(FINANCE)).andExpect(jsonPath("$[*].id", Matchers.hasItem(export.get("id").asString())));
        mvc.perform(get("/api/v1/claims/costs/exports").with(CLAIMS)).andExpect(status().isForbidden());
        // the demo export of last month already exists
        postJson("/api/v1/claims/costs/exports", FINANCE, "{\"month\":\"" + YearMonth.now(ZoneOffset.UTC).minusMonths(1) + "\"}")
                .andExpect(status().isConflict());
        postJson("/api/v1/claims/costs/exports", FINANCE, "{\"month\":\"" + YearMonth.now(ZoneOffset.UTC).plusMonths(1) + "\"}")
                .andExpect(status().isUnprocessableContent());

        // closed-claim reconciliation: paid total vs PAYMENT entries
        putJson("/api/v1/claims/" + id, CLAIMS, "{\"paidTotal\":{\"amount\":\"750\",\"currency\":\"GBP\"}}").andExpect(status().isOk());
        for (String s : List.of("OPEN", "UNDER_REVIEW", "SETTLED", "CLOSED")) {
            postJson("/api/v1/claims/" + id + "/status", CLAIMS, "{\"status\":\"" + s + "\"}").andExpect(status().isOk());
        }
        String claimRef = jdbc.queryForObject("select claim_ref from claims.claim where id = ?::uuid", String.class, id);
        mvc.perform(get("/api/v1/claims/reconciliation?month=" + month).with(FINANCE)).andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.claimRef == '" + claimRef + "')].reconciled").value(Matchers.hasItem(true)))
                .andExpect(jsonPath("$[?(@.claimRef == '" + claimRef + "')].ledgerTotal.amount").value(Matchers.hasItem("750.00")));
    }

    // ------------------------------------------------------------------ F06 legal

    @Test
    void litigationHoldPutsCaseDocumentsOnHoldAndMedicoLegalReportsStayInTheLegalAccessSet() throws Exception {
        String emp = employee();
        UUID caseId = injuryCase(emp, "nurse1");
        String id = createClaim(caseId, "EMPLOYER_LIABILITY", "AVIA_INSURE", false).get("id").asString();
        UUID cert = fitnessCertificate(emp, caseId);
        providerReport(emp, caseId);

        postJson("/api/v1/claims/" + id + "/legal-hold", CLAIMS, "{\"reason\":\"x\"}").andExpect(status().isForbidden());
        postJson("/api/v1/claims/" + id + "/legal-hold", LEGAL, "{}").andExpect(status().isBadRequest());
        postJson("/api/v1/claims/" + id + "/legal-hold", LEGAL, "{\"reason\":\"Pre-action letter received\"}")
                .andExpect(jsonPath("$.litigationHold").value(true)).andExpect(jsonPath("$.documentsAffected").value(2));
        assertThat(count("select count(*) from docs.document where case_id = ? and legal_hold", caseId)).isEqualTo(2);
        mvc.perform(get("/api/v1/claims/" + id).with(LEGAL)).andExpect(status().isOk()).andExpect(jsonPath("$.litigationHold").value(true));
        mvc.perform(get("/api/v1/cases/" + caseId + "/claim-status").with(NURSE)).andExpect(jsonPath("$.litigationHold").value(true));

        // medico-legal request → physicians / CMO notified, physician writes versions, legal reads (audited, disclosure-logged)
        JsonNode lr = body(postJson("/api/v1/claims/" + id + "/legal-requests", CLAIMS, toJson(Map.of("type", "MEDICO_LEGAL_REPORT",
                "questions", "Please comment on causation and prognosis.", "dueDate", LocalDate.now().plusDays(14).toString())))
                .andExpect(status().isCreated()).andReturn());
        String lrId = lr.get("id").asString();
        assertThat(lr.get("ref").asString()).matches("LEG-" + YEAR + "-\\d{6}");
        assertThat(lr.get("status").asString()).isEqualTo("OPEN");
        assertThat(count("select count(*) from platform.notification where address = 'physician1@wellnessz.local' and template_key = 'legal.request' and body like ?",
                "%" + lr.get("ref").asString() + "%")).isEqualTo(1);
        postJson("/api/v1/legal-requests/" + lrId + "/report", CLAIMS, "{\"opinion\":\"x\"}").andExpect(status().isForbidden());
        postJson("/api/v1/legal-requests/" + lrId + "/report", PHYSICIAN, "{\"opinion\":\"Causation is probable on the balance of probabilities.\"}")
                .andExpect(jsonPath("$.status").value("REPORT_READY")).andExpect(jsonPath("$.reports.length()").value(1));
        JsonNode v2 = body(postJson("/api/v1/legal-requests/" + lrId + "/report", PHYSICIAN, "{\"opinion\":\"Revised after the new imaging.\"}")
                .andExpect(jsonPath("$.reports.length()").value(2)).andReturn());
        assertThat(v2.get("reports").get(1).get("version").asInt()).isEqualTo(2);
        String doc2 = v2.get("reports").get(1).get("documentId").asString();
        assertThat(jdbc.queryForObject("select status from docs.document where id = ?::uuid", String.class, doc2)).isEqualTo("SIGNED");
        mvc.perform(get("/api/v1/legal-requests/" + lrId + "/reports/1/content").with(LEGAL)).andExpect(status().isOk())
                .andExpect(result -> assertThat(result.getResponse().getContentType()).isEqualTo("application/pdf"));
        mvc.perform(get("/api/v1/legal-requests/" + lrId + "/reports/1/content").with(CLAIMS)).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/legal-requests/" + lrId + "/reports/3/content").with(LEGAL)).andExpect(status().isNotFound());
        assertThat(count("select count(*) from platform.disclosure_log where employee_id = ? and recipient_type = 'LEGAL' and purpose = 'LEGAL_PROCEEDINGS'",
                emp)).isEqualTo(1);
        mvc.perform(get("/api/v1/legal-requests?status=REPORT_READY").with(LEGAL)).andExpect(jsonPath("$[*].id", Matchers.hasItem(lrId)));
        mvc.perform(get("/api/v1/legal-requests").with(FINANCE)).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/legal-requests/" + lrId + "/close").with(LEGAL)).andExpect(jsonPath("$.status").value("CLOSED"));

        // IME: with E04 deployed a DRAFT referral is raised through ExternalReferralGateway; without it the request stays
        // OPEN with referralStatus NOT_AVAILABLE and OH gets a task to arrange the examination
        JsonNode ime = body(postJson("/api/v1/claims/" + id + "/legal-requests", LEGAL, toJson(Map.of("type", "IME", "questions",
                "Independent examination of current work capacity", "dueDate", LocalDate.now().plusDays(21).toString(), "providerCode", "STMARYS_ORTHO")))
                .andExpect(status().isCreated()).andReturn());
        assertThat(ime.get("status").asString()).isEqualTo("OPEN");
        if ("DRAFT".equals(ime.get("referralStatus").asString())) {
            assertThat(count("select count(*) from treatment.referral where source_type = 'CLAIM_IME' and source_ref = ?", ime.get("id").asString()))
                    .isEqualTo(1);
        } else {
            assertThat(ime.get("referralStatus").asString()).isEqualTo("NOT_AVAILABLE");
            assertThat(count("select count(*) from casemgmt.case_task where case_id = ? and title like ?", caseId, "%" + ime.get("ref").asString() + "%"))
                    .isEqualTo(1);
        }
        // a referral status update from E04 moves the request on
        referralListeners.stream().filter(l -> l.supports("CLAIM_IME")).forEach(l -> l.referralStatusChanged(UUID.randomUUID(), "CLAIM_IME",
                ime.get("id").asString(), ReferralStatusListener.Status.SCHEDULED, null));
        mvc.perform(get("/api/v1/legal-requests/" + ime.get("id").asString()).with(LEGAL)).andExpect(jsonPath("$.status").value("IN_PROGRESS"))
                .andExpect(jsonPath("$.referralStatus").value("SCHEDULED"));
        referralListeners.stream().filter(l -> l.supports("CLAIM_IME")).forEach(l -> l.referralStatusChanged(UUID.randomUUID(), "CLAIM_IME",
                ime.get("id").asString(), ReferralStatusListener.Status.REPORT_RECEIVED, cert));
        mvc.perform(get("/api/v1/legal-requests/" + ime.get("id").asString()).with(LEGAL)).andExpect(jsonPath("$.status").value("REPORT_READY"))
                .andExpect(jsonPath("$.reports[0].documentId").value(cert.toString()));

        // release the hold
        mvc.perform(delete("/api/v1/claims/" + id + "/legal-hold").with(LEGAL)).andExpect(jsonPath("$.litigationHold").value(false));
        assertThat(count("select count(*) from docs.document where case_id = ? and legal_hold", caseId)).isZero();
        mvc.perform(get("/api/v1/claims/" + id).with(LEGAL)).andExpect(status().isNotFound());
    }

    // ------------------------------------------------------------------ HR workspace

    @Test
    void hrSeesClaimStatusOnlyInsideScopeAndEveryDisclosureIsLogged() throws Exception {
        String emp = employee();
        UUID caseId = injuryCase(emp, "nurse1");
        JsonNode claim = createClaim(caseId, "WORKERS_COMP", "GULFTPA", true);
        putJson("/api/v1/claims/" + claim.get("id").asString(), CLAIMS, "{\"reserves\":{\"INDEMNITY\":{\"amount\":\"9000\",\"currency\":\"GBP\"}}}")
                .andExpect(status().isOk());
        MvcResult hr = mvc.perform(get("/api/v1/workspace/claims").with(HR_OPS)).andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.claimRef == '" + claim.get("claimRef").asString() + "')].status").value(Matchers.hasItem("NOTIFIED")))
                .andReturn();
        String text = hr.getResponse().getContentAsString();
        assertThat(text).doesNotContain("payerCode").doesNotContain("reserves").doesNotContain("9000.00").doesNotContain("GULFTPA")
                .doesNotContain("Left wrist").doesNotContain("nurse");
        assertThat(count("select count(*) from platform.disclosure_log where employee_id = ? and recipient_type = 'HR' and resource_type = 'CLAIM_STATUS'",
                emp)).isEqualTo(1);
        mvc.perform(get("/api/v1/workspace/claims").with(HR_OPS)).andExpect(status().isOk());
        assertThat(count("select count(*) from platform.disclosure_log where employee_id = ? and recipient_type = 'HR'", emp)).isEqualTo(1);
        mvc.perform(get("/api/v1/workspace/claims").with(HR_CARGO)).andExpect(jsonPath("$[*].employeeId", Matchers.not(Matchers.hasItem(emp))));
        mvc.perform(get("/api/v1/workspace/claims").with(CLAIMS)).andExpect(status().isForbidden());
    }

    // ------------------------------------------------------------------ demo data

    @Test
    void demoDataIsUsableByTheUis() throws Exception {
        mvc.perform(get("/api/v1/claims/" + DEMO_WC_CLAIM).with(CLAIMS)).andExpect(status().isOk())
                .andExpect(jsonPath("$.claimRef").value("CLM-" + YEAR + "-900001"))
                .andExpect(jsonPath("$.lostTime").value(true)).andExpect(jsonPath("$.nurseCaseManager").value("clinician1"))
                .andExpect(jsonPath("$.reports.length()").value(2));
        mvc.perform(get("/api/v1/claims/" + DEMO_WC_CLAIM + "/messages").with(ADJUSTER)).andExpect(jsonPath("$.length()").value(Matchers.greaterThanOrEqualTo(2)));
        mvc.perform(get("/api/v1/partner/claims/" + DEMO_WC_CLAIM).with(ADJUSTER)).andExpect(status().isOk())
                .andExpect(jsonPath("$.rtw.rtwPlan.status").value("ACTIVE")).andExpect(jsonPath("$.tasks.length()").value(Matchers.greaterThanOrEqualTo(3)));
        mvc.perform(get("/api/v1/claims/reports?dueWithinHours=24").with(CLAIMS))
                .andExpect(jsonPath("$[*].ref", Matchers.hasItem("FROI-" + YEAR + "-900001")));
        mvc.perform(get("/api/v1/claims/" + DEMO_WC_CLAIM + "/disclosure-sets").with(CLAIMS))
                .andExpect(jsonPath("$[?(@.id == '" + DEMO_SET + "')].documents.length()").value(Matchers.hasItem(1)));
        mvc.perform(get("/api/v1/claims/" + DEMO_EL_CLAIM).with(LEGAL)).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("LITIGATED")).andExpect(jsonPath("$.litigationHold").value(true))
                .andExpect(jsonPath("$.legalRequests[0].ref").value("LEG-" + YEAR + "-900001"));
        mvc.perform(get("/api/v1/claims/costs/exports").with(FINANCE))
                .andExpect(jsonPath("$[*].month", Matchers.hasItem(YearMonth.now(ZoneOffset.UTC).minusMonths(1).toString())));
        String demoCsv = jdbc.queryForObject("select content from claims.cost_export where id = '8e080000-0000-4000-8200-000000000001'", String.class);
        String demoSum = jdbc.queryForObject("select checksum from claims.cost_export where id = '8e080000-0000-4000-8200-000000000001'", String.class);
        assertThat(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(demoCsv.getBytes(StandardCharsets.UTF_8)))).isEqualTo(demoSum);
        mvc.perform(get("/api/v1/claims/reconciliation").with(FINANCE))
                .andExpect(jsonPath("$[?(@.claimRef == 'CLM-" + YEAR + "-900003')].reconciled").value(Matchers.hasItem(true)));
    }
}
