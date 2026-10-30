package com.wellnessz.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.wellnessz.integration.outbox.OutboxRelay;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** End-to-end tests of the base framework against a real PostgreSQL database. */
class PlatformIntegrationTest extends IntegrationTestBase {

    @Autowired
    MockMvc mvc;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    OutboxRelay relay;
    @Autowired
    JsonMapper json;
    @Autowired
    com.wellnessz.integration.outbox.OutboxRepository outbox;

    static JwtRequestPostProcessor user(String username, String employeeId, String orgScope, String... roles) {
        return jwt().jwt(j -> {
            j.subject(UUID.randomUUID().toString()).claim("preferred_username", username).claim("name", username);
            if (employeeId != null) {
                j.claim("employee_id", employeeId);
            }
            if (orgScope != null) {
                j.claim("org_scope", orgScope);
            }
        }).authorities(Arrays.stream(roles).map(r -> (GrantedAuthority) new SimpleGrantedAuthority("ROLE_" + r)).toList());
    }

    static String uniqueEmp() {
        return "E" + UUID.randomUUID().toString().substring(0, 8);
    }

    JsonNode body(MvcResult r) throws Exception {
        return json.readTree(r.getResponse().getContentAsString());
    }

    @Test
    void unauthenticatedRequestsAreRejected() throws Exception {
        mvc.perform(get("/api/v1/me")).andExpect(status().isUnauthorized());
    }

    @Test
    void internalReferenceDataIsNotServedToExternalAccounts() throws Exception {
        for (String path : List.of("/api/v1/masterdata/org-units", "/api/v1/masterdata/job-codes", "/api/v1/masterdata/hazards",
                "/api/v1/masterdata/fitness-standards", "/api/v1/masterdata/restrictions")) {
            mvc.perform(get(path)).andExpect(status().isUnauthorized());
            mvc.perform(get(path).with(user("partner1", null, "/PARTNERS/CITYHOSP", "PARTNER_PROVIDER"))).andExpect(status().isForbidden());
            mvc.perform(get(path).with(user("adjuster1", null, "/PARTNERS/GULFTPA", "TPA_ADJUSTER"))).andExpect(status().isForbidden());
            mvc.perform(get(path).with(user("candidate1", null, null, "CANDIDATE"))).andExpect(status().isForbidden());
            mvc.perform(get(path).with(user("employee1", "E1001", "/AIRLINE/OPS/CABIN", "EMPLOYEE"))).andExpect(status().isOk());
        }
    }

    @Test
    void meReturnsIdentityAndDisclosure() throws Exception {
        mvc.perform(get("/api/v1/me").with(user("employee1", "E1001", "/AIRLINE/OPS/CABIN", "EMPLOYEE")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.employeeId").value("E1001"))
                .andExpect(jsonPath("$.maxDisclosure").value("SELF"));
    }

    @Test
    void managersSeeStatusOnlyAndCliniciansSeeClinicalData() throws Exception {
        String emp = uniqueEmp();
        MvcResult created = mvc.perform(post("/api/v1/cases/self-referral")
                        .with(user("crew", emp, "/AIRLINE/OPS/CABIN", "EMPLOYEE"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"category\":\"INJURY\",\"description\":\"Twisted ankle during boarding\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.caseRef").exists())
                .andReturn();
        String id = body(created).get("id").asString();

        // manager inside scope: status-only projection, no clinical field present at all
        MvcResult managerView = mvc.perform(get("/api/v1/cases/" + id).with(user("mgr", "M1", "/AIRLINE/OPS", "MANAGER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.disclosure").value("STATUS_ONLY"))
                .andReturn();
        assertThat(managerView.getResponse().getContentAsString()).doesNotContain("Twisted").doesNotContain("clinicalSummary");

        // manager outside scope: not found (existence not revealed)
        mvc.perform(get("/api/v1/cases/" + id).with(user("mgr2", "M2", "/AIRLINE/CARGO", "MANAGER")))
                .andExpect(status().isNotFound());

        // clinician: full clinical view, decrypted transparently
        mvc.perform(get("/api/v1/cases/" + id).with(user("doc", "C1", null, "OH_CLINICIAN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.clinicalSummary").value("Twisted ankle during boarding"));

        // clinical text is encrypted at rest
        String stored = jdbc.queryForObject("select clinical_summary from casemgmt.oh_case where id = ?::uuid", String.class, id);
        assertThat(stored).startsWith("v1:").doesNotContain("ankle");
    }

    @Test
    void restrictedClassIsHiddenFromGeneralClinicians() throws Exception {
        MvcResult created = mvc.perform(post("/api/v1/cases/self-referral")
                        .with(user("crew", uniqueEmp(), "/AIRLINE/OPS/CABIN", "EMPLOYEE"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"category\":\"PSYCHOLOGICAL\",\"description\":\"Need peer support\"}"))
                .andExpect(status().isCreated()).andReturn();
        String id = body(created).get("id").asString();
        mvc.perform(get("/api/v1/cases/" + id).with(user("doc", "C1", null, "OH_CLINICIAN"))).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/cases/" + id).with(user("psy", "P1", null, "PSYCH_CLINICIAN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.disclosure").value("RESTRICTED_CLINICAL"));
    }

    @Test
    void excelImportCreatesIncidentsDeduplicatesAndOpensCases() throws Exception {
        String ref = "INC-" + UUID.randomUUID().toString().substring(0, 8);
        String emp = uniqueEmp();
        byte[] xlsx = workbook(List.of(
                List.of("Incident ID", "Incident Date", "Location", "Employee ID", "Employee Name", "Department", "Incident Type", "Severity", "Injury", "Description"),
                List.of(ref + "-1", "2026-10-01 08:30", "LHR T5", emp, "Alex Crew", "CABIN", "Slip/trip", "LOW", "Yes", "Slipped in galley"),
                List.of(ref + "-2", "2026-10-02 10:00", "LHR T5", "", "", "GROUND", "Near miss", "LOW", "No", "Near miss"),
                List.of("", "bad", "", "", "", "", "", "", "", "")));
        MockMultipartFile file = new MockMultipartFile("file", "incidents.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", xlsx);
        mvc.perform(multipart("/api/v1/intake/ehs/excel").file(file).with(user("ohadmin", null, null, "OH_ADMIN")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.rowsAccepted").value(2))
                .andExpect(jsonPath("$.rowsRejected").value(1));
        // re-upload: idempotent
        mvc.perform(multipart("/api/v1/intake/ehs/excel").file(file).with(user("ohadmin", null, null, "OH_ADMIN")))
                .andExpect(jsonPath("$.rowsDuplicate").value(2))
                .andExpect(jsonPath("$.rowsAccepted").value(0));

        relay.relay(); // deliver ehs.incident.received.v1 -> incident-to-case rule
        Integer cases = jdbc.queryForObject("select count(*) from casemgmt.oh_case where employee_id = ? and source = 'EHS_INCIDENT'",
                Integer.class, emp);
        assertThat(cases).isEqualTo(1);

        // non-xlsx content is rejected even with the right extension
        MockMultipartFile fake = new MockMultipartFile("file", "evil.xlsx", "application/octet-stream", "<xml/>".getBytes());
        mvc.perform(multipart("/api/v1/intake/ehs/excel").file(fake).with(user("ohadmin", null, null, "OH_ADMIN")))
                .andExpect(status().isBadRequest());
        // employees cannot import
        mvc.perform(multipart("/api/v1/intake/ehs/excel").file(file).with(user("crew", "E1", null, "EMPLOYEE")))
                .andExpect(status().isForbidden());
    }

    @Test
    void fhirAndHl7InboundAreValidatedAndAcknowledged() throws Exception {
        String obs = "{\"resourceType\":\"Observation\",\"id\":\"lab-1\",\"status\":\"final\",\"code\":{\"coding\":[{\"system\":\"http://loinc.org\",\"code\":\"718-7\"}]}}";
        mvc.perform(post("/api/v1/integration/fhir/Observation").with(user("lab", null, null, "INTEGRATION"))
                        .contentType("application/fhir+json").content(obs))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.resourceType").value("OperationOutcome"));
        mvc.perform(post("/api/v1/integration/fhir/Patient").with(user("lab", null, null, "INTEGRATION"))
                        .contentType("application/fhir+json").content(obs))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/integration/fhir/Observation").with(user("crew", "E1", null, "EMPLOYEE"))
                        .contentType("application/fhir+json").content(obs))
                .andExpect(status().isForbidden());

        String oru = "MSH|^~\\&|LABSYS|LAB|WELLNESSZ|OH|20261029101500||ORU^R01|MSG00001|P|2.5\r"
                + "PID|1||E1001^^^HR||Crew^Alex\r"
                + "OBR|1||ORD1|718-7^Hemoglobin^LN\r"
                + "OBX|1|NM|718-7^Hemoglobin^LN||14.2|g/dL|13-17|N|||F\r";
        MvcResult ack = mvc.perform(post("/api/v1/integration/hl7v2").with(user("lab", null, null, "INTEGRATION"))
                        .contentType("text/plain").content(oru))
                .andExpect(status().isOk()).andReturn();
        assertThat(ack.getResponse().getContentAsString()).contains("MSA|AA|MSG00001");
    }

    @Test
    void inboundApiIsIdempotent() throws Exception {
        String key = "key-" + UUID.randomUUID();
        var integration = user("hris", null, null, "INTEGRATION");
        mvc.perform(post("/api/v1/integration/inbound/hris").with(integration).header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"employeeId\":\"E1\"}"))
                .andExpect(status().isAccepted());
        mvc.perform(post("/api/v1/integration/inbound/hris").with(integration).header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"employeeId\":\"E1\"}"))
                .andExpect(status().isAccepted())
                .andExpect(header().string("Idempotent-Replayed", "true"));
        mvc.perform(post("/api/v1/integration/inbound/hris").with(integration).header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"employeeId\":\"E2\"}"))
                .andExpect(status().isUnprocessableContent());
        mvc.perform(post("/api/v1/integration/inbound/hris").with(integration)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void fitnessOutcomeIsPublishedToRosteringAsStatusOnly() throws Exception {
        MvcResult created = mvc.perform(post("/api/v1/cases/self-referral")
                        .with(user("crew", uniqueEmp(), "/AIRLINE/OPS/CABIN", "EMPLOYEE"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"category\":\"ILLNESS\",\"description\":\"Confidential clinical narrative\"}"))
                .andReturn();
        String id = body(created).get("id").asString();
        mvc.perform(post("/api/v1/cases/" + id + "/fitness").with(user("doc", "C1", null, "OH_CLINICIAN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fitnessStatus\":\"FIT_WITH_RESTRICTIONS\",\"restrictionUntil\":\"2026-12-31\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fitnessStatus").value("FIT_WITH_RESTRICTIONS"));
        String raw = jdbc.queryForObject("""
                select payload from platform.outbox_event where destination = 'crew-rostering'
                 order by created_at desc limit 1""", String.class);
        assertThat(raw).startsWith("v1:").doesNotContain("FIT_WITH_RESTRICTIONS"); // encrypted at rest
        String payload = outbox.findAllByOrderByCreatedAtDesc(org.springframework.data.domain.PageRequest.of(0, 50)).stream()
                .filter(e -> e.getDestination().equals("crew-rostering")).findFirst().orElseThrow().getPayload();
        JsonNode node = json.readTree(payload);
        assertThat(node.get("fitnessStatus").asString()).isEqualTo("FIT_WITH_RESTRICTIONS");
        assertThat(payload).doesNotContain("Confidential").doesNotContain("ILLNESS");
        // managers cannot record fitness
        mvc.perform(post("/api/v1/cases/" + id + "/fitness").with(user("mgr", "M1", "/AIRLINE", "MANAGER"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"fitnessStatus\":\"FIT\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void auditTrailIsAppendOnlyAndChainVerifies() throws Exception {
        mvc.perform(get("/api/v1/me").with(user("employee1", "E1001", null, "EMPLOYEE")));
        mvc.perform(post("/api/v1/consents").with(user("employee1", "E1001", null, "EMPLOYEE"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"purpose\":\"EAP_REFERRAL\"}"))
                .andExpect(status().isCreated());
        mvc.perform(get("/api/v1/audit/verify").with(user("aud", null, null, "AUDITOR")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.intact").value(true));
        mvc.perform(get("/api/v1/audit/verify").with(user("crew", "E1", null, "EMPLOYEE")))
                .andExpect(status().isForbidden());
        // blocked twice over: the runtime role has no UPDATE/DELETE grant, and a trigger rejects it for any role
        assertThatThrownBy(() -> jdbc.update("update platform.audit_event set actor = 'tampered'"))
                .rootCause().message().containsAnyOf("append-only", "permission denied");
        assertThatThrownBy(() -> jdbc.update("delete from platform.audit_event"))
                .rootCause().message().containsAnyOf("append-only", "permission denied");
    }

    @Test
    void analyticsSuppressesSmallGroups() throws Exception {
        MvcResult r = mvc.perform(get("/api/v1/analytics/kpis/cases-by-status").param("groupBy", "orgUnit")
                        .with(user("hr", "H1", "/AIRLINE", "HR")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.minGroupSize").value(3))
                .andReturn();
        for (JsonNode cell : body(r).get("cells")) {
            if (cell.get("suppressed").asBoolean()) {
                assertThat(cell.has("count") && !cell.get("count").isNull()).isFalse();
            } else {
                assertThat(cell.get("count").asLong()).isGreaterThanOrEqualTo(3);
            }
        }
        mvc.perform(get("/api/v1/analytics/kpis/cases-by-status").param("groupBy", "employeeId")
                        .with(user("hr", "H1", "/AIRLINE", "HR")))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/analytics/kpis/cases-by-status").with(user("crew", "E1", null, "EMPLOYEE")))
                .andExpect(status().isForbidden());
    }

    @Test
    void disabledModuleIsRefused() throws Exception {
        jdbc.update("insert into masterdata.config_entry(config_key, config_value, updated_by, updated_at) values ('modules.e13.enabled','false','test',now())");
        try {
            mvc.perform(post("/api/v1/cases/self-referral").with(user("crew", uniqueEmp(), null, "EMPLOYEE"))
                            .contentType(MediaType.APPLICATION_JSON).content("{\"category\":\"INJURY\",\"description\":\"x\"}"))
                    .andExpect(status().isServiceUnavailable());
        } finally {
            jdbc.update("update masterdata.config_entry set config_value = 'true' where config_key = 'modules.e13.enabled'");
        }
    }

    @Test
    void modulesCatalogueListsArchitecture() throws Exception {
        mvc.perform(get("/api/v1/modules").with(user("crew", "E1", null, "EMPLOYEE")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(18))
                .andExpect(jsonPath("$[14].code").value("E15"));
    }

    @Test
    void notificationContentRulesBlockClinicalContentAndValidationErrorsAreProblems() throws Exception {
        mvc.perform(post("/api/v1/notifications/test").with(user("admin", null, null, "PLATFORM_ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"address\":\"a@b.test\",\"channel\":\"EMAIL\"}"))
                .andExpect(status().isAccepted());
        mvc.perform(post("/api/v1/cases/self-referral").with(user("crew", "E1", null, "EMPLOYEE"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"category\":\"INJURY\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("validation_failed"));
    }

    private static byte[] workbook(List<List<String>> rows) throws Exception {
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            XSSFSheet sheet = wb.createSheet("Incidents");
            for (int i = 0; i < rows.size(); i++) {
                var row = sheet.createRow(i);
                for (int c = 0; c < rows.get(i).size(); c++) {
                    row.createCell(c).setCellValue(rows.get(i).get(c));
                }
            }
            wb.write(out);
            return out.toByteArray();
        }
    }

}
