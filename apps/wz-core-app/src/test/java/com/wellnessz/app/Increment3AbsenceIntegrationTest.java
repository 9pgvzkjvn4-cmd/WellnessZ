package com.wellnessz.app;

import static com.wellnessz.app.PlatformIntegrationTest.user;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import javax.imageio.ImageIO;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

import com.wellnessz.e06.lta.LtaService;
import com.wellnessz.e06.rules.AbsenceTriggerEngine;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Acceptance tests for E06 – absence & sickness management (F01–F06) against the real endpoints: feed reconciliation,
 * trigger rules and the early-intervention queue, certificates, the long-term absence pathway, management advice with
 * employee consent, analytics with small-group suppression, and the confidentiality rules for managers / HR / schedulers.
 */
class Increment3AbsenceIntegrationTest extends IntegrationTestBase {

    @Autowired
    MockMvc mvc;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    JsonMapper json;
    @Autowired
    LtaService lta;
    @Autowired
    AbsenceTriggerEngine triggers;

    static final JwtRequestPostProcessor NURSE = user("nurse1", "N1", null, "OH_CLINICIAN");
    static final JwtRequestPostProcessor PHYSICIAN = user("physician1", "PH1", null, "OH_PHYSICIAN");
    static final JwtRequestPostProcessor CASE_MANAGER = user("clinician1", "C1", null, "CASE_MANAGER");
    static final JwtRequestPostProcessor CMO = user("cmo1", "CM1", null, "CMO");
    static final JwtRequestPostProcessor MANAGER = user("manager1", "M2001", "/AIRLINE/CATERING", "MANAGER");
    static final JwtRequestPostProcessor OTHER_MANAGER = user("manager9", "M9999", "/AIRLINE/CARGO", "MANAGER");
    static final JwtRequestPostProcessor HR = user("hr1", "H1", "/AIRLINE/CATERING", "HR");
    static final JwtRequestPostProcessor HR_CARGO = user("hr9", "H9", "/AIRLINE/CARGO", "HR");
    static final JwtRequestPostProcessor OH_ADMIN = user("ohadmin1", null, null, "OH_ADMIN");
    static final JwtRequestPostProcessor INTEGRATION = user("hris-system", null, null, "INTEGRATION");
    static final JwtRequestPostProcessor SCHEDULER = user("scheduler1", "S8601", null, "CLINIC_SCHEDULER");
    static final JwtRequestPostProcessor RECEPTION = user("reception1", "R1", null, "CLINIC_RECEPTION");

    static final LocalDate TODAY = LocalDate.now(ZoneOffset.UTC);
    static final String HR_MAILBOX = "hr-absence@wellnessz.local";

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

    /** A fresh HRIS employee (catering food handler reporting to manager1 unless stated otherwise). */
    String employee(String department, String subsidiary, String job, String manager) {
        String id = "T6" + ThreadLocalRandom.current().nextInt(100_000, 999_999);
        jdbc.update("""
                insert into masterdata.employee (employee_id, display_name, email, subsidiary_code, department_code, cost_centre, job_code,
                                                 location_code, shift_pattern, preferred_language, badge_id, manager_employee_id, updated_at)
                values (?, ?, ?, ?, ?, 'CC-T6', ?, 'LHR', 'EARLY', 'en', ?, ?, now())""",
                id, "Tess " + id, id.toLowerCase() + "@wellnessz.local", subsidiary, department, job, "BT-" + id, manager);
        return id;
    }

    String cateringEmployee() {
        return employee("CATERING", "CAT", "CATERING_ASSOC", "M2001");
    }

    static JwtRequestPostProcessor self(String employeeId) {
        return user("emp-" + employeeId, employeeId, "/AIRLINE/CATERING", "EMPLOYEE");
    }

    JsonNode ingest(String source, String rows) throws Exception {
        return body(postJson("/api/v1/integration/absence/episodes", INTEGRATION, "{\"sourceSystem\":\"" + source + "\",\"episodes\":[" + rows + "]}")
                .andExpect(status().isOk()).andReturn());
    }

    static String row(String ref, String employeeRef, LocalDate start, LocalDate end, String type, String reason, boolean certified) {
        return "{\"sourceRef\":\"" + ref + "\",\"employeeRef\":\"" + employeeRef + "\",\"startDate\":\"" + start + "\""
                + (end == null ? "" : ",\"endDate\":\"" + end + "\"") + ",\"absenceType\":\"" + type + "\""
                + (reason == null ? "" : ",\"reasonCategory\":\"" + reason + "\"") + ",\"certified\":" + certified + "}";
    }

    static String ref() {
        return "T6-" + UUID.randomUUID();
    }

    long notifications(String address, String template, String contains) {
        return jdbc.queryForObject("select count(*) from platform.notification where address = ? and template_key = ? and body like ?",
                Long.class, address, template, "%" + contains + "%");
    }

    JsonNode queueItem(String employeeId, String action) throws Exception {
        JsonNode list = body(mvc.perform(get("/api/v1/absence/queue").param("action", action).with(NURSE)).andExpect(status().isOk()).andReturn());
        for (JsonNode n : list) {
            if (employeeId.equals(n.get("employeeId").asString())) {
                return n;
            }
        }
        return null;
    }

    static byte[] pdf(String... lines) throws Exception {
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDPage page = new PDPage();
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.beginText();
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 11);
                cs.newLineAtOffset(50, 700);
                for (String l : lines) {
                    cs.showText(l);
                    cs.newLineAtOffset(0, -16);
                }
                cs.endText();
            }
            doc.save(out);
            return out.toByteArray();
        }
    }

    static byte[] png() throws Exception {
        BufferedImage img = new BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB);
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            ImageIO.write(img, "png", out);
            return out.toByteArray();
        }
    }

    // =================================================================== F01 feed & history

    @Test
    void f01FeedReconciliationMismatchResolveAndStatusOnlyHistory() throws Exception {
        String e = cateringEmployee();
        String r1 = ref();
        String r2 = ref();
        String r3 = ref();
        JsonNode run = ingest("HRIS", String.join(",",
                row(r1, e, TODAY.minusDays(1), null, "SICKNESS", "MSK", false),
                row(r2, "BT-" + e, TODAY.minusDays(60), TODAY.minusDays(59), "SICKNESS", "GASTRO", false),
                row(r3, "NOBODY-" + e, TODAY.minusDays(2), TODAY.minusDays(1), "SICKNESS", "RESPIRATORY", true)));
        assertThat(run.get("received").asInt()).isEqualTo(3);
        assertThat(run.get("matched").asInt()).isEqualTo(2);
        assertThat(run.get("unmatched").asInt()).isEqualTo(1);
        assertThat(run.get("updated").asInt()).isZero();
        String runId = run.get("runId").asString();

        // idempotent on (sourceSystem, sourceRef): the end date / certified flag are updated, no duplicate
        JsonNode again = ingest("HRIS", row(r1, e, TODAY.minusDays(1), TODAY, "SICKNESS", "MSK", true));
        assertThat(again.get("updated").asInt()).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from absence.episode where source_ref = ?", Long.class, r1)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select end_date from absence.episode where source_ref = ?", LocalDate.class, r1)).isEqualTo(TODAY);
        // invalid payloads are rejected as a whole
        postJson("/api/v1/integration/absence/episodes", INTEGRATION, "{\"sourceSystem\":\"HRIS\",\"episodes\":[" +
                row(ref(), e, TODAY, null, "HOLIDAY", null, false) + "]}").andExpect(status().isBadRequest());
        postJson("/api/v1/integration/absence/episodes", NURSE, "{\"sourceSystem\":\"HRIS\",\"episodes\":[" +
                row(ref(), e, TODAY, null, "SICKNESS", null, false) + "]}").andExpect(status().isForbidden());

        // data-quality dashboard and mismatch list
        JsonNode runs = body(mvc.perform(get("/api/v1/absence/feed/runs").with(OH_ADMIN)).andExpect(status().isOk()).andReturn());
        JsonNode thisRun = null;
        for (JsonNode n : runs) {
            if (runId.equals(n.get("id").asString())) {
                thisRun = n;
            }
        }
        assertThat(thisRun).as("feed run listed: %s", runs).isNotNull();
        assertThat(thisRun.get("matchRatePct").asDouble()).isEqualTo(66.7);
        assertThat(thisRun.get("unmatched").asInt()).isEqualTo(1);
        mvc.perform(get("/api/v1/absence/feed/runs").with(HR)).andExpect(status().isOk());
        mvc.perform(get("/api/v1/absence/feed/runs").with(MANAGER)).andExpect(status().isForbidden());
        JsonNode mismatches = body(mvc.perform(get("/api/v1/absence/feed/mismatches").param("status", "OPEN").with(HR))
                .andExpect(status().isOk()).andReturn());
        String mismatchId = null;
        for (JsonNode m : mismatches) {
            if (r3.equals(m.get("sourceRef").asString())) {
                mismatchId = m.get("id").asString();
                assertThat(m.has("reasonCategory")).isFalse();
            }
        }
        assertThat(mismatchId).isNotNull();
        mvc.perform(get("/api/v1/absence/feed/mismatches").with(NURSE)).andExpect(status().isForbidden());
        // HR outside the employee's scope cannot attach the row to them
        postJson("/api/v1/absence/feed/mismatches/" + mismatchId + "/resolve", HR_CARGO, "{\"employeeId\":\"" + e + "\"}")
                .andExpect(status().isNotFound());
        postJson("/api/v1/absence/feed/mismatches/" + mismatchId + "/resolve", HR, "{\"employeeId\":\"" + e + "\"}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("RESOLVED"))
                .andExpect(jsonPath("$.resolvedEmployeeId").value(e));
        assertThat(jdbc.queryForObject("select employee_id from absence.episode where source_ref = ?", String.class, r3)).isEqualTo(e);
        postJson("/api/v1/absence/feed/mismatches/" + mismatchId + "/resolve", HR, "{\"employeeId\":\"" + e + "\"}").andExpect(status().isConflict());
        // ignore path
        String r4 = ref();
        ingest("TNA", row(r4, "GHOST-1", TODAY.minusDays(3), null, "SICKNESS", null, false));
        String ignoreId = jdbc.queryForObject("select id::text from absence.feed_mismatch where source_ref = ?", String.class, r4);
        postJson("/api/v1/absence/feed/mismatches/" + ignoreId + "/resolve", OH_ADMIN, "{\"ignore\":true}").andExpect(status().isBadRequest());
        postJson("/api/v1/absence/feed/mismatches/" + ignoreId + "/resolve", OH_ADMIN, "{\"ignore\":true,\"reason\":\"Contractor, not on HRIS\"}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("IGNORED"));

        // daily pull on demand
        mvc.perform(post("/api/v1/absence/feed/runs").with(OH_ADMIN)).andExpect(status().isCreated()).andExpect(jsonPath("$.runType").value("PULL"));
        mvc.perform(post("/api/v1/absence/feed/runs").with(HR)).andExpect(status().isForbidden());

        // history: OH sees the reason category, the line manager and HR status only, other managers nothing
        mvc.perform(get("/api/v1/absence/employees/" + e + "/episodes").with(NURSE)).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3)).andExpect(jsonPath("$[?(@.reasonCategory=='MSK')]").isNotEmpty());
        String managerView = text(mvc.perform(get("/api/v1/absence/employees/" + e + "/episodes").with(MANAGER))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(3)).andReturn());
        assertThat(managerView).doesNotContain("reasonCategory").doesNotContain("MSK").doesNotContain("GASTRO");
        assertThat(text(mvc.perform(get("/api/v1/absence/employees/" + e + "/episodes").with(HR)).andExpect(status().isOk()).andReturn()))
                .doesNotContain("reasonCategory");
        mvc.perform(get("/api/v1/absence/employees/" + e + "/episodes").with(OTHER_MANAGER)).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/absence/employees/" + e + "/episodes").with(SCHEDULER)).andExpect(status().isForbidden());
        assertThat(jdbc.queryForObject("select count(*) from platform.audit_event where action = 'ABSENCE_EPISODES_READ' and resource_id = ?",
                Long.class, e)).isGreaterThanOrEqualTo(3);
        // summary (OH, HR) – Bradford factor 3 spells² × 6 days = 54
        mvc.perform(get("/api/v1/absence/employees/" + e + "/summary").with(NURSE)).andExpect(status().isOk())
                .andExpect(jsonPath("$.episodes12m").value(3)).andExpect(jsonPath("$.daysLost12m").value(6))
                .andExpect(jsonPath("$.bradfordFactor").value(54));
        mvc.perform(get("/api/v1/absence/employees/" + e + "/summary").with(HR)).andExpect(status().isOk());
        mvc.perform(get("/api/v1/absence/employees/" + e + "/summary").with(MANAGER)).andExpect(status().isForbidden());
        // the employee sees their own episodes without the reason
        assertThat(text(mvc.perform(get("/api/v1/me/absence/episodes").with(self(e))).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3)).andReturn())).doesNotContain("reasonCategory");
    }

    // =================================================================== F02 triggers, queue, OH engagement

    @Test
    void f02TriggerQueueAcceptNotifiesManagerAndHrAndFirstContactKpi() throws Exception {
        String e = cateringEmployee();
        String r = ref();
        JsonNode run = ingest("HRIS", row(r, e, TODAY.minusDays(4), null, "SICKNESS", "MENTAL_HEALTH", false));
        assertThat(run.get("triggered").asInt()).isEqualTo(1);
        JsonNode item = queueItem(e, "EARLY_INTERVENTION");
        assertThat(item).isNotNull();
        // catering subsidiary rule overrides the default duration rule
        assertThat(item.get("ruleCode").asString()).isEqualTo("CAT_DURATION_2D");
        assertThat(item.get("status").asString()).isEqualTo("NEW");
        assertThat(item.get("history").get(0).get("reasonCategory").asString()).isEqualTo("MENTAL_HEALTH");
        String id = item.get("id").asString();
        // one open item per employee and action
        assertThat(ingest("HRIS", row(r, e, TODAY.minusDays(4), null, "SICKNESS", "MENTAL_HEALTH", true)).get("triggered").asInt()).isZero();
        assertThat(triggers.evaluate(e, TODAY)).isZero();

        mvc.perform(get("/api/v1/absence/queue").with(MANAGER)).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/absence/queue/" + id + "/accept").with(MANAGER)).andExpect(status().isForbidden());
        postJson("/api/v1/absence/queue/" + id + "/contact", NURSE, "{\"channel\":\"PHONE\",\"outcome\":\"REACHED\"}").andExpect(status().isConflict());
        JsonNode accepted = body(mvc.perform(post("/api/v1/absence/queue/" + id + "/accept").with(NURSE)).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACCEPTED")).andReturn());
        String caseRef = accepted.get("caseRef").asString();
        String caseId = accepted.get("caseId").asString();
        assertThat(caseRef).startsWith("CAT-");
        assertThat(jdbc.queryForObject("select case_type || '/' || early_intervention || '/' || owner_username from casemgmt.oh_case where id = ?::uuid",
                String.class, caseId)).isEqualTo("ABSENCE/true/nurse1");
        mvc.perform(post("/api/v1/absence/queue/" + id + "/accept").with(NURSE)).andExpect(status().isConflict());
        // OH engaged: line manager and HR are told – status only
        assertThat(notifications("manager1@wellnessz.local", "absence.oh-engaged", caseRef)).isEqualTo(1);
        assertThat(notifications(HR_MAILBOX, "absence.oh-engaged", caseRef)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select body from platform.notification where address = ? and template_key = 'absence.oh-engaged' and body like ?",
                String.class, HR_MAILBOX, "%" + caseRef + "%")).doesNotContainIgnoringCase("mental");
        assertThat(jdbc.queryForObject("select count(*) from platform.outbox_event where destination = 'hr-systems' and event_type = 'absence.hr-status.v1' and aggregate_id = ?",
                Long.class, e)).isEqualTo(1);

        // contacts: only a REACHED contact counts for the trigger-to-contact KPI
        postJson("/api/v1/absence/queue/" + id + "/contact", NURSE, "{\"channel\":\"PHONE\",\"outcome\":\"NO_ANSWER\"}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.firstContactAt").doesNotExist());
        postJson("/api/v1/absence/queue/" + id + "/contact", NURSE, "{\"channel\":\"FAX\",\"outcome\":\"REACHED\"}").andExpect(status().isBadRequest());
        postJson("/api/v1/absence/queue/" + id + "/contact", NURSE, "{\"channel\":\"VIDEO\",\"outcome\":\"REACHED\",\"note\":\"Discussed support options\"}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CONTACTED")).andExpect(jsonPath("$.firstContactAt").isNotEmpty());
        assertThat(jdbc.queryForObject("select note from absence.contact where queue_item_id = ?::uuid and outcome = 'REACHED'", String.class, id))
                .doesNotContain("support");
        mvc.perform(get("/api/v1/absence/analytics/kpis").with(CMO)).andExpect(status().isOk())
                .andExpect(jsonPath("$.medianDaysTriggerToContact").isNumber()).andExpect(jsonPath("$.medianDaysTriggerToContactTarget").value(5))
                .andExpect(jsonPath("$.trend").isArray());

        // manager team view: status only (OH engaged), no reason
        String team = text(mvc.perform(get("/api/v1/workspace/absence/team").with(MANAGER)).andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.employeeId=='" + e + "')].ohEngaged").value(Matchers.contains(true))).andReturn());
        assertThat(team).doesNotContain("MENTAL").doesNotContain("reasonCategory");
        assertThat(text(mvc.perform(get("/api/v1/workspace/absence/team").with(OTHER_MANAGER)).andReturn())).doesNotContain(e);
        postJson("/api/v1/absence/queue/" + id + "/close", NURSE, "{\"reason\":\"\"}").andExpect(status().isBadRequest());
        postJson("/api/v1/absence/queue/" + id + "/close", NURSE, "{\"reason\":\"Returned to work, no further action\"}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CLOSED"));
    }

    @Test
    void f02RuleConfigurationVersioningAndTestMode() throws Exception {
        String e = cateringEmployee();
        ingest("HRIS", row(ref(), e, TODAY.minusDays(2), null, "SICKNESS", "OTHER", false));
        mvc.perform(get("/api/v1/absence/rules").with(HR)).andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.code=='LTA_28D')].params.minDays").value(Matchers.contains(28)))
                .andExpect(jsonPath("$[?(@.code=='CAT_DURATION_2D')].subsidiaryCode").value(Matchers.contains("CAT")));
        mvc.perform(get("/api/v1/absence/rules").with(MANAGER)).andExpect(status().isForbidden());
        String code = "T6_" + ThreadLocalRandom.current().nextInt(10_000, 99_999);
        String rule = "{\"code\":\"" + code + "\",\"name\":\"Short test rule\",\"ruleType\":\"DURATION\",\"params\":{\"minDays\":2},\"action\":\"EARLY_INTERVENTION\",\"active\":false}";
        postJson("/api/v1/absence/rules", HR, rule).andExpect(status().isForbidden());
        postJson("/api/v1/absence/rules", OH_ADMIN, rule.replace("\"minDays\":2", "\"minDays\":0")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("invalid_params"));
        postJson("/api/v1/absence/rules", OH_ADMIN, rule.replace("DURATION", "PATTERN")).andExpect(status().isBadRequest());
        String id = body(postJson("/api/v1/absence/rules", OH_ADMIN, rule).andExpect(status().isCreated())
                .andExpect(jsonPath("$.version").value(1)).andExpect(jsonPath("$.active").value(false)).andReturn()).get("id").asString();
        postJson("/api/v1/absence/rules", OH_ADMIN, rule).andExpect(status().isConflict());
        putJson("/api/v1/absence/rules/" + id, OH_ADMIN, rule.replace("\"minDays\":2", "\"minDays\":3")).andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2)).andExpect(jsonPath("$.params.minDays").value(3)).andExpect(jsonPath("$.updatedBy").value("ohadmin1"));
        mvc.perform(delete("/api/v1/absence/rules/" + id).with(OH_ADMIN)).andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(false)).andExpect(jsonPath("$.version").value(3));
        assertThat(jdbc.queryForObject("select count(*) from absence.trigger_rule_version where rule_id = ?::uuid", Long.class, id)).isEqualTo(3);
        // test mode: an unsaved rule over the live population; nothing is raised
        long items = jdbc.queryForObject("select count(*) from absence.queue_item", Long.class);
        postJson("/api/v1/absence/rules/test", OH_ADMIN, "{\"rule\":{\"ruleType\":\"DURATION\",\"params\":{\"minDays\":2},\"action\":\"EARLY_INTERVENTION\"},\"subsidiaryCode\":\"CAT\"}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.matches[?(@.employeeId=='" + e + "')].subsidiaryCode").value(Matchers.contains("CAT")))
                .andExpect(jsonPath("$.count").value(Matchers.greaterThanOrEqualTo(1)));
        postJson("/api/v1/absence/rules/test", OH_ADMIN, "{\"ruleId\":\"" + id + "\",\"subsidiaryCode\":\"FLT\"}").andExpect(status().isOk())
                .andExpect(jsonPath("$.matches[?(@.employeeId=='" + e + "')]").isEmpty());
        assertThat(jdbc.queryForObject("select count(*) from absence.queue_item", Long.class)).isEqualTo(items);
        postJson("/api/v1/absence/rules/test", NURSE, "{\"ruleId\":\"" + id + "\"}").andExpect(status().isForbidden());
    }

    @Test
    void f02CrewSicknessOpensAmeReviewWithPendingRosterStatus() throws Exception {
        String crew = employee("CABIN", "CAB", "CABIN_CREW", null);
        JsonNode run = ingest("CREW", row(ref(), crew, TODAY.minusDays(8), null, "SICKNESS", "RESPIRATORY", false));
        assertThat(run.get("triggered").asInt()).isGreaterThanOrEqualTo(1);
        JsonNode ame = queueItem(crew, "AME_REVIEW");
        assertThat(ame).isNotNull();
        assertThat(ame.get("ruleCode").asString()).isEqualTo("CREW_SICKNESS");
        String caseId = ame.get("caseId").asString();
        assertThat(jdbc.queryForObject("select case_type || '/' || fitness_status from casemgmt.oh_case where id = ?::uuid", String.class, caseId))
                .isEqualTo("FITNESS/UNFIT_PENDING");
        assertThat(jdbc.queryForObject("select count(*) from platform.outbox_event where destination = 'crew-rostering' and aggregate_id = ?",
                Long.class, crew)).isPositive();
        mvc.perform(get("/api/v1/absence/queue").param("action", "AME_REVIEW").with(SCHEDULER)).andExpect(status().isForbidden());
    }

    // =================================================================== F03 certificates

    @Test
    void f03CertificatesUploadChecksValidationVerificationAndHrValidityOnly() throws Exception {
        String e = cateringEmployee();
        String r = ref();
        ingest("HRIS", row(r, e, TODAY.minusDays(3), null, "SICKNESS", "GASTRO", true));
        LocalDate from = TODAY.minusDays(3);
        LocalDate to = TODAY.plusDays(4);
        byte[] file = pdf("Statement of fitness for work", "Issued by Dr Jane Smith", "GMC 7012345",
                "Issue date: " + TODAY.minusDays(2), "Not fit for work from " + from + " to " + to);
        JsonNode upload = body(mvc.perform(multipart("/api/v1/me/absence/certificates").file(new MockMultipartFile("file", "note.pdf", "application/pdf", file))
                        .param("fromDate", from.toString()).param("toDate", to.toString()).with(self(e)))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("RECEIVED")).andReturn());
        String certId = upload.get("id").asString();
        assertThat(upload.has("issuerName")).isFalse();
        assertThat(notifications(e.toLowerCase() + "@wellnessz.local", "absence.certificate-received", "")).isEqualTo(1);
        // not a PDF / image → rejected by the document service
        mvc.perform(multipart("/api/v1/me/absence/certificates").file(new MockMultipartFile("file", "x.pdf", "application/pdf", "hello".getBytes()))
                .param("fromDate", from.toString()).param("toDate", to.toString()).with(self(e))).andExpect(status().isBadRequest());
        mvc.perform(multipart("/api/v1/me/absence/certificates").file(new MockMultipartFile("file", "n.pdf", "application/pdf", file))
                .param("fromDate", to.toString()).param("toDate", from.toString()).with(self(e))).andExpect(status().isBadRequest());

        // OH work list: OCR fields, checklist, flags; same file again → duplicate; dates not matching the text → flag
        JsonNode cert = body(mvc.perform(get("/api/v1/absence/certificates").param("employeeId", e).with(NURSE)).andExpect(status().isOk()).andReturn()).get(0);
        assertThat(cert.get("extracted").get("registration").asString()).isEqualTo("7012345");
        assertThat(cert.get("extracted").get("issuerName").asString()).contains("Jane Smith");
        assertThat(cert.get("extracted").get("fromDate").asString()).isEqualTo(from.toString());
        assertThat(cert.get("alterationFlags").size()).isZero();
        assertThat(cert.get("episodeId").isNull()).isFalse();
        JsonNode dup = body(mvc.perform(multipart("/api/v1/absence/certificates").file(new MockMultipartFile("file", "note.pdf", "application/pdf", file))
                        .param("employeeId", e).param("fromDate", from.toString()).param("toDate", to.plusDays(1).toString()).with(RECEPTION))
                .andExpect(status().isCreated()).andReturn());
        assertThat(dup.get("duplicateOfId").asString()).isEqualTo(certId);
        assertThat(dup.get("alterationFlags").toString()).contains("DATES_DIFFER_FROM_OCR");
        JsonNode scan = body(mvc.perform(multipart("/api/v1/absence/certificates").file(new MockMultipartFile("file", "scan.png", "image/png", png()))
                        .param("employeeId", e).param("fromDate", TODAY.minusDays(20).toString()).param("toDate", TODAY.minusDays(18).toString())
                        .with(RECEPTION)).andExpect(status().isCreated()).andReturn());
        assertThat(scan.get("alterationFlags").toString()).contains("UNREADABLE");
        mvc.perform(multipart("/api/v1/absence/certificates").file(new MockMultipartFile("file", "scan.png", "image/png", png()))
                .param("employeeId", "NOPE-1").param("fromDate", from.toString()).param("toDate", to.toString()).with(RECEPTION))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/absence/certificates/" + certId + "/content").with(NURSE)).andExpect(status().isOk())
                .andExpect(content().contentType("application/pdf"));
        mvc.perform(get("/api/v1/absence/certificates").with(SCHEDULER)).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/absence/certificates").with(HR)).andExpect(status().isForbidden());

        // validation by reception, verification call, HR sees validity only
        postJson("/api/v1/absence/certificates/" + certId + "/validate", RECEPTION, """
                {"checklist":{"issuerIdentified":true,"registrationValid":true,"datesConsistent":true,"signaturePresent":true,"coversAbsence":true},
                 "outcome":"VALID"}""").andExpect(status().isOk()).andExpect(jsonPath("$.status").value("VALID"))
                .andExpect(jsonPath("$.checklist.signaturePresent").value(true)).andExpect(jsonPath("$.validatedBy").value("reception1"));
        postJson("/api/v1/absence/certificates/" + certId + "/calls", RECEPTION, "{\"calledParty\":\"Riverside surgery\",\"outcome\":\"CONFIRMED\"}")
                .andExpect(status().isCreated()).andExpect(jsonPath("$.calls.length()").value(1));
        String hrView = text(mvc.perform(get("/api/v1/workspace/absence/certificates").param("employeeId", e).with(HR)).andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id=='" + certId + "')].validity").value(Matchers.contains("VALID"))).andReturn());
        assertThat(hrView).doesNotContain("Jane").doesNotContain("7012345").doesNotContain("extracted").doesNotContain("documentId");
        mvc.perform(get("/api/v1/workspace/absence/certificates").param("employeeId", e).with(OTHER_MANAGER)).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/me/absence/certificates").with(self(e))).andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id=='" + certId + "')].status").value(Matchers.contains("VALID")));
        mvc.perform(get("/api/v1/absence/employees/" + e + "/episodes").with(MANAGER))
                .andExpect(jsonPath("$[0].certificateValidity").value("VALID"));

        // verification by OH only; fraud escalated to HR (status only)
        postJson("/api/v1/absence/certificates/" + dup.get("id").asString() + "/verify", RECEPTION, "{\"outcome\":\"VERIFIED_FRAUDULENT\",\"escalateToHr\":true}")
                .andExpect(status().isForbidden());
        postJson("/api/v1/absence/certificates/" + dup.get("id").asString() + "/verify", NURSE, "{\"outcome\":\"VERIFIED_FRAUDULENT\",\"escalateToHr\":true}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("VERIFIED_FRAUDULENT")).andExpect(jsonPath("$.escalated").value(true));
        assertThat(notifications(HR_MAILBOX, "absence.certificate-escalation", "Tess " + e)).isEqualTo(1);
        mvc.perform(get("/api/v1/workspace/absence/certificates").param("employeeId", e).with(MANAGER))
                .andExpect(jsonPath("$[?(@.id=='" + dup.get("id").asString() + "')].validity").value(Matchers.contains("INVALID")))
                .andExpect(jsonPath("$[?(@.id=='" + dup.get("id").asString() + "')].escalated").value(Matchers.contains(true)));

        // partner clinic electronic certificate: verified source → VALID; same number again → duplicate (SUSPICIOUS)
        String partner = """
                {"employeeRef":"BT-%s","partnerOrg":"DXB_CREW_CLINIC","certificateNumber":"%s","issuerName":"Dr Omar Haddad",
                 "issuerRegistration":"DHA-55821","issueDate":"%s","fromDate":"%s","toDate":"%s","fitNote":"NOT_FIT","pdfBase64":"%s"}"""
                .formatted(e, "DXB-" + UUID.randomUUID().toString().substring(0, 8), from, from.plusDays(1), to.plusDays(2),
                        java.util.Base64.getEncoder().encodeToString(pdf("Electronic fit note " + UUID.randomUUID())));
        postJson("/api/v1/integration/absence/certificates", INTEGRATION, partner).andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("VALID")).andExpect(jsonPath("$.source").value("PARTNER_ELECTRONIC"));
        postJson("/api/v1/integration/absence/certificates", INTEGRATION, partner).andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("SUSPICIOUS")).andExpect(jsonPath("$.duplicateOfId").isNotEmpty());
        postJson("/api/v1/integration/absence/certificates", INTEGRATION, partner.replace("BT-" + e, "UNKNOWN-9"))
                .andExpect(status().isUnprocessableContent());
        postJson("/api/v1/integration/absence/certificates", NURSE, partner).andExpect(status().isForbidden());
    }

    // =================================================================== F04 long-term absence pathway

    @Test
    void f04LongTermAbsencePathwayMilestonesHrStageAndReturnToWork() throws Exception {
        String e = cateringEmployee();
        String r = ref();
        JsonNode run = ingest("HRIS", row(r, e, TODAY.minusDays(30), null, "SICKNESS", "MSK", true));
        assertThat(run.get("triggered").asInt()).isEqualTo(2); // LONG_TERM + EARLY_INTERVENTION
        JsonNode item = queueItem(e, "LONG_TERM");
        String caseId = item.get("caseId").asString();
        assertThat(jdbc.queryForObject("select lta_signalled from absence.episode where source_ref = ?", Boolean.class, r)).isTrue();
        assertThat(jdbc.queryForObject("select count(*) from platform.outbox_event where event_type = 'absence.lta.started.v1' and aggregate_id = ?",
                Long.class, caseId)).isEqualTo(1);
        JsonNode detail = body(mvc.perform(get("/api/v1/absence/lta/" + caseId).with(NURSE)).andExpect(status().isOk())
                .andExpect(jsonPath("$.milestones.length()").value(7)).andExpect(jsonPath("$.hrStage").value("NONE"))
                .andExpect(jsonPath("$.weeksAbsent").value(4)).andReturn());
        assertThat(detail.get("milestones").get(0).get("type").asString()).isEqualTo("CONTACT");
        mvc.perform(get("/api/v1/absence/lta").param("status", "ACTIVE").with(NURSE))
                .andExpect(jsonPath("$[?(@.caseId=='" + caseId + "')].employeeId").value(Matchers.contains(e)));
        mvc.perform(get("/api/v1/absence/lta/" + caseId).with(MANAGER)).andExpect(status().isForbidden());

        // nightly job: week-2 contact and week-4 advice report are overdue → alert to the case manager queue
        LtaService.OverdueRun overdue = lta.processOverdue(TODAY);
        assertThat(overdue.markedOverdue()).isGreaterThanOrEqualTo(2);
        mvc.perform(get("/api/v1/absence/lta/" + caseId).with(NURSE)).andExpect(jsonPath("$.overdueCount").value(2));
        assertThat(notifications("clinician1@wellnessz.local", "absence.milestone-overdue", item.get("caseRef").asString())).isEqualTo(2);

        // a REACHED contact completes the contact milestone; fortnightly next contact by default
        postJson("/api/v1/absence/lta/" + caseId + "/contacts", NURSE, "{\"channel\":\"PHONE\",\"outcome\":\"REACHED\",\"note\":\"Pain improving\"}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.contacts[0].nextContactDue").value(TODAY.plusDays(14).toString()))
                .andExpect(jsonPath("$.milestones[?(@.type=='CONTACT')].status").value(Matchers.contains("DONE")));
        JsonNode d2 = body(mvc.perform(get("/api/v1/absence/lta/" + caseId).with(NURSE)).andReturn());
        String adviceMilestone = null;
        String ihr = null;
        for (JsonNode m : d2.get("milestones")) {
            if ("ADVICE_REPORT".equals(m.get("type").asString()) && m.get("week").asInt() == 4) {
                adviceMilestone = m.get("id").asString();
            }
            if ("IHR_CONSIDERATION".equals(m.get("type").asString())) {
                ihr = m.get("id").asString();
            }
        }
        postJson("/api/v1/absence/lta/" + caseId + "/milestones/" + adviceMilestone + "/complete", NURSE, "{\"note\":\"Report sent\"}")
                .andExpect(status().isOk());
        postJson("/api/v1/absence/lta/" + caseId + "/milestones/" + adviceMilestone + "/complete", NURSE, "{}").andExpect(status().isConflict());
        postJson("/api/v1/absence/lta/" + caseId + "/milestones/" + ihr + "/skip", NURSE, "{\"reason\":\"\"}").andExpect(status().isBadRequest());
        postJson("/api/v1/absence/lta/" + caseId + "/conference", NURSE,
                "{\"scheduledFor\":\"" + TODAY.plusDays(7) + "T10:00:00Z\",\"participants\":[\"EMPLOYEE\",\"MANAGER\",\"OH\"]}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.milestones[?(@.type=='CASE_CONFERENCE')].status").value(Matchers.contains("DONE")))
                .andExpect(jsonPath("$.conferences.length()").value(1));
        postJson("/api/v1/absence/lta/" + caseId + "/conference", NURSE, "{\"scheduledFor\":\"soon\",\"participants\":[\"OH\"]}")
                .andExpect(status().isBadRequest());

        // HR stage (HR in scope, note screened); managers see status only
        postJson("/api/v1/workspace/absence/lta/" + caseId + "/hr-stage", HR, "{\"stage\":\"FORMAL_REVIEW_1\",\"note\":\"She has a disc prolapse\"}")
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("clinical_content_not_allowed"));
        postJson("/api/v1/workspace/absence/lta/" + caseId + "/hr-stage", MANAGER, "{\"stage\":\"FORMAL_REVIEW_1\"}").andExpect(status().isForbidden());
        postJson("/api/v1/workspace/absence/lta/" + caseId + "/hr-stage", HR_CARGO, "{\"stage\":\"FORMAL_REVIEW_1\"}").andExpect(status().isNotFound());
        postJson("/api/v1/workspace/absence/lta/" + caseId + "/hr-stage", HR, "{\"stage\":\"FORMAL_REVIEW_1\",\"note\":\"First formal review meeting booked\"}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.hrStage").value("FORMAL_REVIEW_1"));
        String workspace = text(mvc.perform(get("/api/v1/workspace/absence/lta").with(MANAGER)).andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.caseId=='" + caseId + "')].hrStage").value(Matchers.contains("FORMAL_REVIEW_1")))
                .andExpect(jsonPath("$[?(@.caseId=='" + caseId + "')].weeksAbsent").value(Matchers.contains(4))).andReturn());
        assertThat(workspace).doesNotContain("MSK").doesNotContain("Pain").doesNotContain("contacts");
        assertThat(text(mvc.perform(get("/api/v1/workspace/absence/lta").with(OTHER_MANAGER)).andReturn())).doesNotContain(caseId);
        mvc.perform(get("/api/v1/absence/lta/" + caseId).with(NURSE))
                .andExpect(jsonPath("$.milestones[?(@.type=='HR_REVIEW')].status").value(Matchers.contains("DONE")));

        // return to work: the feed closes the episode → RTW review milestone; completing it closes the pathway
        ingest("HRIS", row(r, e, TODAY.minusDays(30), TODAY.minusDays(1), "SICKNESS", "MSK", true));
        JsonNode d3 = body(mvc.perform(get("/api/v1/absence/lta/" + caseId).with(NURSE)).andExpect(jsonPath("$.rtwDate").value(TODAY.toString()))
                .andReturn());
        String rtwReview = null;
        for (JsonNode m : d3.get("milestones")) {
            if ("RTW_REVIEW".equals(m.get("type").asString())) {
                rtwReview = m.get("id").asString();
            }
        }
        assertThat(rtwReview).isNotNull();
        postJson("/api/v1/absence/lta/" + caseId + "/milestones/" + rtwReview + "/complete", NURSE, "{\"note\":\"Back on full duties\"}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CLOSED"));
        assertThat(jdbc.queryForObject("select status from absence.queue_item where id = ?::uuid", String.class, item.get("id").asString()))
                .isEqualTo("CLOSED");
    }

    // =================================================================== F05 management advice

    @Test
    void f05AdviceRequestReportScreeningConsentReleaseAndDecline() throws Exception {
        String e = cateringEmployee();
        mvc.perform(get("/api/v1/absence/advice/questions").with(self(e))).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(8));
        postJson("/api/v1/workspace/advice-requests", OTHER_MANAGER, "{\"employeeId\":\"" + e + "\",\"questionCodes\":[\"FIT_FOR_ROLE\"]}")
                .andExpect(status().isNotFound());
        postJson("/api/v1/workspace/advice-requests", MANAGER, "{\"employeeId\":\"" + e + "\",\"questionCodes\":[]}").andExpect(status().isBadRequest());
        postJson("/api/v1/workspace/advice-requests", MANAGER, "{\"employeeId\":\"" + e + "\",\"questionCodes\":[\"WHO_KNOWS\"]}").andExpect(status().isBadRequest());
        JsonNode req = body(postJson("/api/v1/workspace/advice-requests", MANAGER, """
                {"employeeId":"%s","questionCodes":["FIT_FOR_ROLE","ADJUSTMENTS","EXPECTED_RTW"],"context":"Finds the early shift hard since the back injury"}"""
                .formatted(e)).andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("SUBMITTED"))
                .andExpect(jsonPath("$.ref").value(Matchers.matchesPattern("ADV-\\d{4}-\\d{6}"))).andReturn());
        String id = req.get("id").asString();
        assertThat(jdbc.queryForObject("select context from absence.advice_request where id = ?::uuid", String.class, id)).doesNotContain("back injury");
        assertThat(notifications("clinician1@wellnessz.local", "absence.advice-requested", req.get("ref").asString())).isEqualTo(1);
        mvc.perform(get("/api/v1/absence/advice-requests").param("status", "SUBMITTED").with(NURSE))
                .andExpect(jsonPath("$[?(@.id=='" + id + "')].context").value(Matchers.contains("Finds the early shift hard since the back injury")));
        mvc.perform(get("/api/v1/absence/advice-requests").with(MANAGER)).andExpect(status().isForbidden());

        String report = """
                {"fitness":"FIT_WITH_ADJUSTMENTS","adjustments":"%s","restrictions":"No lifting above 10 kg for four weeks","prognosis":"RTW_WITHIN_4_WEEKS",
                 "expectedReturn":"%s","reviewDate":"%s","answers":{"FIT_FOR_ROLE":"%s","ADJUSTMENTS":"Later start time for four weeks"}}""";
        putJson("/api/v1/absence/advice-requests/" + id + "/report", PHYSICIAN, report.formatted("Later start", TODAY.plusDays(14), TODAY.plusDays(28), "Yes"))
                .andExpect(status().isConflict());
        mvc.perform(post("/api/v1/absence/advice-requests/" + id + "/accept").with(NURSE)).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACCEPTED")).andExpect(jsonPath("$.caseRef").value(Matchers.startsWith("CAT-")));
        // every text is screened: a diagnosis or a diagnosis code is rejected
        putJson("/api/v1/absence/advice-requests/" + id + "/report", PHYSICIAN,
                report.formatted("Reduced duties because of depression", TODAY.plusDays(14), TODAY.plusDays(28), "Yes"))
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("clinical_content_not_allowed"));
        putJson("/api/v1/absence/advice-requests/" + id + "/report", PHYSICIAN,
                report.formatted("Later start", TODAY.plusDays(14), TODAY.plusDays(28), "Yes once M54.5 settles"))
                .andExpect(status().isUnprocessableContent());
        putJson("/api/v1/absence/advice-requests/" + id + "/report", CASE_MANAGER,
                report.formatted("Later start", TODAY.plusDays(14), TODAY.plusDays(28), "Yes")).andExpect(status().isForbidden());
        putJson("/api/v1/absence/advice-requests/" + id + "/report", PHYSICIAN,
                report.formatted("Later start time and lighter trolley duties", TODAY.plusDays(14), TODAY.plusDays(28), "Yes, with adjustments"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("REPORT_DRAFTED")).andExpect(jsonPath("$.currentReport.version").value(1));
        mvc.perform(post("/api/v1/absence/advice-requests/" + id + "/report/sign").with(PHYSICIAN)).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("AWAITING_EMPLOYEE"));
        assertThat(notifications(e.toLowerCase() + "@wellnessz.local", "absence.advice-review", "")).isEqualTo(1);
        // the requester sees the status only until the employee consents
        mvc.perform(get("/api/v1/workspace/advice-requests").with(MANAGER))
                .andExpect(jsonPath("$[?(@.id=='" + id + "')].status").value(Matchers.contains("AWAITING_EMPLOYEE")))
                .andExpect(jsonPath("$[?(@.id=='" + id + "')].report").isEmpty());

        // employee review → consent → released document shared with the manager (disclosure logged)
        JsonNode mine = body(mvc.perform(get("/api/v1/me/advice-reports").with(self(e))).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1)).andExpect(jsonPath("$[0].requestedByRole").value("MANAGER"))
                .andExpect(jsonPath("$[0].report.fitness").value("FIT_WITH_ADJUSTMENTS")).andReturn());
        String reportId = mine.get(0).get("id").asString();
        String someoneElse = cateringEmployee();
        postJson("/api/v1/me/advice-reports/" + reportId + "/decision", self(someoneElse), "{\"decision\":\"CONSENT\"}").andExpect(status().isNotFound());
        postJson("/api/v1/me/advice-reports/" + reportId + "/decision", self(e), "{\"decision\":\"CONSENT\",\"comment\":\"I have diabetes\"}")
                .andExpect(status().isUnprocessableContent());
        postJson("/api/v1/me/advice-reports/" + reportId + "/decision", self(e), "{\"decision\":\"CONSENT\",\"comment\":\"Happy for my manager to see this\"}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("RELEASED")).andExpect(jsonPath("$.report.documentId").isNotEmpty());
        String documentId = jdbc.queryForObject("select document_id::text from absence.advice_report where id = ?::uuid", String.class, reportId);
        assertThat(jdbc.queryForObject("select template_code || '/' || status from docs.document where id = ?::uuid", String.class, documentId))
                .isEqualTo("OH_ADVICE_REPORT/SIGNED");
        assertThat(jdbc.queryForObject("select count(*) from docs.document_share where document_id = ?::uuid and target = 'MANAGER'", Long.class,
                documentId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from platform.disclosure_log where employee_id = ? and recipient_type = 'MANAGER'", Long.class, e))
                .isEqualTo(1);
        assertThat(notifications("manager1@wellnessz.local", "absence.advice-released", "Tess " + e)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from platform.audit_event where action = 'ADVICE_EMPLOYEE_DECISION' and resource_id = ?",
                Long.class, id)).isEqualTo(1);
        String released = text(mvc.perform(get("/api/v1/workspace/advice-requests").with(MANAGER)).andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id=='" + id + "')].report.fitness").value(Matchers.contains("FIT_WITH_ADJUSTMENTS")))
                .andExpect(jsonPath("$[?(@.id=='" + id + "')].report.answers.FIT_FOR_ROLE").value(Matchers.contains("Yes, with adjustments")))
                .andExpect(jsonPath("$[?(@.id=='" + id + "')].report.employeeComment").value(Matchers.contains("Happy for my manager to see this")))
                .andReturn());
        assertThat(released).doesNotContain("currentReport");
        // HR in scope sees the released report but not the requester's context
        assertThat(text(mvc.perform(get("/api/v1/workspace/advice-requests").with(HR)).andReturn())).contains(id).doesNotContain("back injury");
        postJson("/api/v1/workspace/advice-requests/" + id + "/withdraw", MANAGER, "{}").andExpect(status().isConflict());

        // decline path (HR requester): withheld, requester told without content; OH may issue a new version
        String hrReq = body(postJson("/api/v1/workspace/advice-requests", HR, "{\"employeeId\":\"" + e + "\",\"questionCodes\":[\"EXPECTED_RTW\"]}")
                .andExpect(status().isCreated()).andReturn()).get("id").asString();
        mvc.perform(post("/api/v1/absence/advice-requests/" + hrReq + "/accept").with(NURSE)).andExpect(status().isOk());
        String simple = "{\"fitness\":\"TEMPORARILY_UNFIT\",\"prognosis\":\"UNCERTAIN\",\"answers\":{\"EXPECTED_RTW\":\"Too early to say\"}}";
        putJson("/api/v1/absence/advice-requests/" + hrReq + "/report", NURSE, simple).andExpect(status().isOk());
        mvc.perform(post("/api/v1/absence/advice-requests/" + hrReq + "/report/sign").with(NURSE)).andExpect(status().isOk());
        String hrReport = jdbc.queryForObject("select id::text from absence.advice_report where request_id = ?::uuid", String.class, hrReq);
        postJson("/api/v1/me/advice-reports/" + hrReport + "/decision", self(e), "{\"decision\":\"DECLINE\",\"declineReason\":\"I want to talk to OH first\"}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("WITHHELD"));
        assertThat(notifications(HR_MAILBOX, "absence.advice-declined", "")).isPositive();
        mvc.perform(get("/api/v1/workspace/advice-requests").with(HR))
                .andExpect(jsonPath("$[?(@.id=='" + hrReq + "')].status").value(Matchers.contains("WITHHELD")))
                .andExpect(jsonPath("$[?(@.id=='" + hrReq + "')].report").isEmpty());
        assertThat(jdbc.queryForObject("select count(*) from docs.document_share s join absence.advice_report r on r.document_id = s.document_id where r.request_id = ?::uuid",
                Long.class, hrReq)).isZero();
        putJson("/api/v1/absence/advice-requests/" + hrReq + "/report", NURSE, simple).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REPORT_DRAFTED")).andExpect(jsonPath("$.currentReport.version").value(2));

        // withdrawal by the requester only
        String third = body(postJson("/api/v1/workspace/advice-requests", MANAGER, "{\"employeeId\":\"" + e + "\",\"questionCodes\":[\"PHASED_RETURN\"]}")
                .andExpect(status().isCreated()).andReturn()).get("id").asString();
        postJson("/api/v1/workspace/advice-requests/" + third + "/withdraw", HR, "{}").andExpect(status().isNotFound());
        postJson("/api/v1/workspace/advice-requests/" + third + "/withdraw", MANAGER, "{}").andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("WITHDRAWN"));
    }

    @Test
    void f05DemoAdviceReportAwaitsEmployee1() throws Exception {
        mvc.perform(get("/api/v1/me/advice-reports").with(user("employee1", "E1001", "/AIRLINE/CATERING", "EMPLOYEE"))).andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id=='6e060000-0000-4000-d100-000000000001')]").isNotEmpty());
        mvc.perform(get("/api/v1/absence/lta/6e060000-0000-4000-a000-000000000002").with(NURSE)).andExpect(status().isOk())
                .andExpect(jsonPath("$.employeeId").value("E1004")).andExpect(jsonPath("$.milestones.length()").value(7));
    }

    // =================================================================== F06 analytics

    @Test
    void f06AnalyticsSuppressionCategoriesAndExport() throws Exception {
        String e = cateringEmployee();
        ingest("HRIS", row(ref(), e, TODAY.minusDays(9), TODAY.minusDays(8), "SICKNESS", "RESPIRATORY", false));
        JsonNode units = body(mvc.perform(get("/api/v1/absence/analytics/units").param("groupBy", "DEPARTMENT").with(CMO)).andExpect(status().isOk()).andReturn());
        boolean sawSuppressed = false;
        boolean sawCatering = false;
        for (JsonNode u : units) {
            if (u.get("suppressed").asBoolean()) {
                sawSuppressed = true;
                assertThat(u.has("headcount")).isFalse();
                assertThat(u.has("episodes")).isFalse();
                assertThat(u.has("categories")).isFalse();
            }
            if ("CATERING".equals(u.get("key").asString())) {
                sawCatering = true;
                assertThat(u.get("suppressed").asBoolean()).isFalse();
                assertThat(u.get("headcount").asInt()).isGreaterThanOrEqualTo(3);
                assertThat(u.get("categories").get("RESPIRATORY").asInt()).isPositive();
            }
        }
        assertThat(sawSuppressed).isTrue();
        assertThat(sawCatering).isTrue();
        // HR: no categories (OH only)
        assertThat(text(mvc.perform(get("/api/v1/absence/analytics/units").param("groupBy", "JOB_FAMILY").with(HR)).andExpect(status().isOk()).andReturn()))
                .doesNotContain("categories").doesNotContain(e);
        mvc.perform(get("/api/v1/absence/analytics/units").param("groupBy", "PERSON").with(CMO)).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/absence/analytics/units").with(MANAGER)).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/absence/analytics/units").with(SCHEDULER)).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/absence/analytics/kpis").with(OH_ADMIN)).andExpect(status().isOk())
                .andExpect(jsonPath("$.ltaWithCaseTarget").value(95)).andExpect(jsonPath("$.openLta").value(Matchers.greaterThanOrEqualTo(1)));
        mvc.perform(get("/api/v1/absence/analytics/kpis").with(HR)).andExpect(status().isForbidden());
        String csv = text(mvc.perform(get("/api/v1/absence/analytics/export").param("month", TODAY.toString().substring(0, 7)).with(HR))
                .andExpect(status().isOk()).andExpect(content().contentTypeCompatibleWith("text/csv")).andReturn());
        assertThat(csv).startsWith("month,groupBy,key,label,headcount").contains("\"CATERING\"").doesNotContain(e).doesNotContain("RESPIRATORY");
        mvc.perform(get("/api/v1/absence/analytics/export").param("month", "2026-13").with(HR)).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/absence/analytics/export").param("month", "2026-10").with(OH_ADMIN)).andExpect(status().isForbidden());
    }

    @Test
    void schedulerAndOtherNonOhRolesCannotReadAbsenceData() throws Exception {
        for (String url : new String[]{"/api/v1/absence/queue", "/api/v1/absence/lta", "/api/v1/absence/advice-requests", "/api/v1/absence/certificates",
                "/api/v1/absence/employees/E1001/episodes", "/api/v1/absence/employees/E1001/summary", "/api/v1/workspace/absence/team",
                "/api/v1/absence/rules", "/api/v1/absence/feed/runs", "/api/v1/absence/feed/mismatches"}) {
            mvc.perform(get(url).with(SCHEDULER)).andExpect(status().isForbidden());
        }
        mvc.perform(get("/api/v1/me/absence/episodes").with(SCHEDULER)).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/absence/queue").with(user("crewsched", null, null, "CREW_SCHEDULING"))).andExpect(status().isForbidden());
    }
}
