package com.wellnessz.app;

import static com.wellnessz.app.PlatformIntegrationTest.user;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.ByteArrayOutputStream;
import java.time.YearMonth;
import java.util.List;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Increment 2 platform services: document service (generate, e-sign, share, consent, search, scan, retention),
 * break-glass access and the disclosure log. Uses the demo people from db/demo.
 */
class Increment2PlatformIntegrationTest extends IntegrationTestBase {

    @Autowired
    MockMvc mvc;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    JsonMapper json;

    static final JwtRequestPostProcessor NURSE = user("nurse1", "N8001", "/AIRLINE/OH", "OH_CLINICIAN");
    static final JwtRequestPostProcessor PHYSICIAN = user("physician1", "D8101", "/AIRLINE/OH", "OH_PHYSICIAN");
    static final JwtRequestPostProcessor ERIN = user("employee1", "E1001", "/AIRLINE/CATERING", "EMPLOYEE");
    static final JwtRequestPostProcessor MAX = user("manager1", "M2001", "/AIRLINE/CATERING", "MANAGER");
    static final JwtRequestPostProcessor OTHER_MANAGER = user("manager9", "M9999", "/AIRLINE/OPS", "MANAGER");
    static final JwtRequestPostProcessor HR = user("hr1", "H3001", "/AIRLINE", "HR");
    static final JwtRequestPostProcessor PARTNER = user("partner1", "P9101", "/PARTNERS/CITYHOSP", "PARTNER_PROVIDER");
    static final JwtRequestPostProcessor DPO = user("dpo1", "X9001", "/AIRLINE", "DPO");

    JsonNode body(MvcResult r) throws Exception {
        return json.readTree(r.getResponse().getContentAsString());
    }

    String certificate(String restrictions) {
        return """
                {"templateCode":"FITNESS_CERTIFICATE","employeeId":"E1001","fields":{"employeeName":"Erin Employee","employeeId":"E1001",
                 "jobTitle":"Catering associate","standardTitle":"Food handler – fitness to handle food","outcome":"Fit with limitations",
                 "restrictions":"%s","validFrom":"2026-11-01","validUntil":"2026-12-01","issuedBy":"Nina Nurse"}}""".formatted(restrictions);
    }

    String signedCertificate() throws Exception {
        String id = body(mvc.perform(post("/api/v1/documents/generate").with(NURSE).contentType(MediaType.APPLICATION_JSON)
                        .content(certificate("No lifting above 10 kg")))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.documentNumber").value(org.hamcrest.Matchers.startsWith("DOC-"))).andReturn()).get("id").asString();
        mvc.perform(post("/api/v1/documents/" + id + "/sign").with(NURSE)).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SIGNED")).andExpect(jsonPath("$.signedBy").value("nurse1"));
        return id;
    }

    @Test
    void certificateIsGeneratedSignedAndSharedWithoutClinicalContent() throws Exception {
        // a diagnosis in a manager-shareable certificate is rejected and the field is named
        mvc.perform(post("/api/v1/documents/generate").with(NURSE).contentType(MediaType.APPLICATION_JSON)
                        .content(certificate("Lumbar disc prolapse – avoid lifting")))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("clinical_content_not_allowed"))
                .andExpect(jsonPath("$.field").value("restrictions"));
        String id = signedCertificate();
        // signed documents are immutable
        mvc.perform(post("/api/v1/documents/" + id + "/sign").with(NURSE)).andExpect(status().isConflict());
        // HR / managers cannot use the OH document APIs
        mvc.perform(get("/api/v1/documents").param("employeeId", "E1001").with(HR)).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/documents/" + id).with(MAX)).andExpect(status().isNotFound());

        mvc.perform(post("/api/v1/documents/" + id + "/share").with(NURSE).contentType(MediaType.APPLICATION_JSON)
                .content("{\"target\":\"MANAGER\"}")).andExpect(status().isCreated());
        mvc.perform(post("/api/v1/documents/" + id + "/share").with(NURSE).contentType(MediaType.APPLICATION_JSON)
                .content("{\"target\":\"EMPLOYEE\"}")).andExpect(status().isCreated());

        MvcResult ws = mvc.perform(get("/api/v1/workspace/documents").with(MAX)).andExpect(status().isOk()).andReturn();
        assertThat(ws.getResponse().getContentAsString()).contains(id).doesNotContain("\"shares\":[");
        MvcResult pdf = mvc.perform(get("/api/v1/documents/" + id + "/content").with(MAX)).andExpect(status().isOk()).andReturn();
        assertThat(new String(pdf.getResponse().getContentAsByteArray(), 0, 4)).isEqualTo("%PDF");
        assertThat(pdf.getResponse().getHeader("Cache-Control")).isEqualTo("no-store");
        mvc.perform(get("/api/v1/documents/" + id + "/content").with(OTHER_MANAGER)).andExpect(status().isNotFound());

        mvc.perform(get("/api/v1/me/documents").with(ERIN)).andExpect(jsonPath("$[?(@.id=='" + id + "')]").exists());
        mvc.perform(post("/api/v1/me/documents/" + id + "/acknowledge").with(ERIN)).andExpect(jsonPath("$.acknowledgedAt").exists());
        // the employee sees who received the certificate
        mvc.perform(get("/api/v1/me/disclosures").with(ERIN))
                .andExpect(jsonPath("$[?(@.recipientType=='MANAGER' && @.status=='ACTIVE')]").exists());

        // a new signed version supersedes the old one and its shares stop
        String v2 = body(mvc.perform(post("/api/v1/documents/" + id + "/new-version").with(NURSE).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fields\":" + json.readTree(certificate("No lifting above 5 kg")).get("fields") + "}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.version").value(2)).andReturn()).get("id").asString();
        mvc.perform(post("/api/v1/documents/" + v2 + "/sign").with(NURSE)).andExpect(status().isOk());
        mvc.perform(get("/api/v1/documents/" + id + "/content").with(MAX)).andExpect(status().isNotFound());
    }

    @Test
    void partnerSharingNeedsConsentAndStopsWhenConsentIsWithdrawn() throws Exception {
        String letter = body(mvc.perform(post("/api/v1/documents/generate").with(PHYSICIAN).contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"templateCode":"REFERRAL_LETTER","employeeId":"E1001","fields":{"employeeName":"Erin Employee","recipient":"City Hospital physiotherapy",
                         "reason":"Persistent left wrist pain after a fall","question":"Please assess and treat","referrer":"Dr Phil Physician"}}"""))
                .andExpect(status().isCreated()).andReturn()).get("id").asString();
        mvc.perform(post("/api/v1/documents/" + letter + "/sign").with(PHYSICIAN)).andExpect(status().isOk());
        // clinical documents never go to managers
        mvc.perform(post("/api/v1/documents/" + letter + "/share").with(PHYSICIAN).contentType(MediaType.APPLICATION_JSON)
                .content("{\"target\":\"MANAGER\"}")).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/documents/" + letter + "/share").with(PHYSICIAN).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"target\":\"PARTNER\",\"partnerOrg\":\"CITYHOSP\"}"))
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("consent_required"));
        String consent = body(mvc.perform(post("/api/v1/consents").with(ERIN).contentType(MediaType.APPLICATION_JSON)
                .content("{\"purpose\":\"TREATMENT_SHARING_PARTNER\"}")).andExpect(status().isCreated()).andReturn()).get("id").asString();
        mvc.perform(post("/api/v1/documents/" + letter + "/share").with(PHYSICIAN).contentType(MediaType.APPLICATION_JSON)
                .content("{\"target\":\"PARTNER\",\"partnerOrg\":\"CITYHOSP\",\"consentId\":\"" + consent + "\"}")).andExpect(status().isCreated());
        mvc.perform(get("/api/v1/partner/documents").with(PARTNER)).andExpect(jsonPath("$[?(@.id=='" + letter + "')]").exists());

        mvc.perform(delete("/api/v1/consents/" + consent).with(ERIN)).andExpect(status().is2xxSuccessful());
        mvc.perform(get("/api/v1/partner/documents").with(PARTNER)).andExpect(jsonPath("$[?(@.id=='" + letter + "')]").doesNotExist());
        mvc.perform(get("/api/v1/documents/" + letter + "/content").with(PARTNER)).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/me/disclosures").with(ERIN))
                .andExpect(jsonPath("$[?(@.recipientType=='PARTNER' && @.status=='REVOKED')]").exists());
    }

    @Test
    void scannedReportsAreClassifiedAndSearchableWithoutAPlaintextIndex() throws Exception {
        byte[] pdf = pdfWithText("Hospital discharge summary. Audiogram shows bilateral noise notch at 4 kHz.");
        MvcResult up = mvc.perform(multipart("/api/v1/documents/scan").file(new MockMultipartFile("file", "report.pdf", "application/pdf", pdf))
                        .param("employeeId", "E1004").with(NURSE))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.title").value("External document – Discharge summary")).andReturn();
        String id = body(up).get("id").asString();
        mvc.perform(multipart("/api/v1/documents/scan").file(new MockMultipartFile("file", "x.pdf", "application/pdf", "MZ not a pdf".getBytes()))
                .param("employeeId", "E1004").with(NURSE)).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/documents/search").param("employeeId", "E1004").param("q", "noise notch").with(NURSE))
                .andExpect(jsonPath("$[0].document.id").value(id))
                .andExpect(jsonPath("$[0].snippet").value(org.hamcrest.Matchers.containsString("noise")));
        String vector = jdbc.queryForObject("select search_vector::text from docs.document where id = ?::uuid", String.class, id);
        assertThat(vector).doesNotContain("noise").doesNotContain("audiogram").doesNotContain("discharge");
        String stored = jdbc.queryForObject("select extracted_text from docs.document where id = ?::uuid", String.class, id);
        assertThat(stored).startsWith("v1:").doesNotContain("Audiogram");
    }

    @Test
    void breakGlassOpensARestrictedRecordForFourHoursAndIsReviewedByTheDpo() throws Exception {
        String caseId = body(mvc.perform(post("/api/v1/cases/self-referral").with(user("crew1", "E1003", "/AIRLINE/OPS/CABIN", "EMPLOYEE"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"category\":\"PSYCHOLOGICAL\",\"description\":\"Struggling after a difficult flight\"}"))
                .andExpect(status().isCreated()).andReturn()).get("id").asString();
        mvc.perform(get("/api/v1/cases/" + caseId).with(NURSE)).andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/break-glass").with(NURSE).contentType(MediaType.APPLICATION_JSON)
                .content("{\"employeeId\":\"E1003\",\"reason\":\"too short\"}")).andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/break-glass").with(HR).contentType(MediaType.APPLICATION_JSON)
                .content("{\"employeeId\":\"E1003\",\"reason\":\"HR wants to see the psychological record now\"}")).andExpect(status().isForbidden());
        String grant = body(mvc.perform(post("/api/v1/break-glass").with(NURSE).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"employeeId\":\"E1003\",\"reason\":\"Collapsed in crew room, emergency team needs medication history\"}"))
                .andExpect(status().isCreated()).andReturn()).get("id").asString();
        mvc.perform(get("/api/v1/cases/" + caseId).with(NURSE)).andExpect(status().isOk())
                .andExpect(jsonPath("$.disclosure").value("RESTRICTED_CLINICAL"));
        // DPO: alerted (content-free notification), reviews within 24 h, cannot be the user who broke the glass
        assertThat(jdbc.queryForObject("select count(*) from platform.notification where template_key = 'break-glass.alert'", Integer.class))
                .isPositive();
        mvc.perform(get("/api/v1/break-glass").param("status", "PENDING").with(NURSE)).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/break-glass").param("status", "PENDING").with(DPO)).andExpect(jsonPath("$[?(@.id=='" + grant + "')]").exists());
        mvc.perform(post("/api/v1/break-glass/" + grant + "/review").with(DPO).contentType(MediaType.APPLICATION_JSON)
                .content("{\"review\":\"APPROPRIATE\",\"note\":\"Genuine emergency\"}")).andExpect(jsonPath("$.review").value("APPROPRIATE"));
        mvc.perform(get("/api/v1/break-glass/report").param("month", YearMonth.now().toString()).with(DPO))
                .andExpect(jsonPath("$.total").value(org.hamcrest.Matchers.greaterThanOrEqualTo(1)))
                .andExpect(jsonPath("$.appropriate").value(org.hamcrest.Matchers.greaterThanOrEqualTo(1)));
        // the DPO has no clinical access
        mvc.perform(get("/api/v1/cases/" + caseId).with(DPO)).andExpect(status().isNotFound());
    }

    @Test
    void masterDataStandardsAreVersionedAndRetentionIsApprovedByTheDpo() throws Exception {
        mvc.perform(get("/api/v1/masterdata/fitness-standards").with(NURSE))
                .andExpect(jsonPath("$[?(@.code=='CAA_CLASS1')].requiresSignoffRole").value(org.hamcrest.Matchers.hasItem("AME")));
        mvc.perform(put("/api/v1/masterdata/fitness-standards/NIGHT_WORK").with(user("cmo1", "D8301", "/AIRLINE/OH", "CMO"))
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"title":"Night work health assessment","roleCategory":"GENERAL","source":"Local labour law",
                                 "validityMonths":12,"limitationCodes":[]}"""))
                .andExpect(jsonPath("$.version").value(2));
        mvc.perform(put("/api/v1/masterdata/fitness-standards/NIGHT_WORK").with(NURSE).contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"x\",\"roleCategory\":\"GENERAL\",\"source\":\"x\",\"validityMonths\":12}")).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/masterdata/restrictions").with(MAX)).andExpect(jsonPath("$[?(@.code=='LIFT_MAX_KG')]").exists());
        mvc.perform(get("/api/v1/masterdata/job-demands").param("unit", "CAT").with(MAX))
                .andExpect(jsonPath("$[?(@.jobCode=='CATERING_PACKING')].demands.LIFT_KG").value(org.hamcrest.Matchers.hasItem(5)));
        mvc.perform(post("/api/v1/masterdata/job-demands/import").with(user("admin1", "X7001", "/AIRLINE", "OH_ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                [{"jobCode":"TEST_JOB","title":"Test job","unit":"CORP","demands":{"LIFT_KG":5,"NIGHT":false}}]"""))
                .andExpect(jsonPath("$.upserted").value(1));
        mvc.perform(post("/api/v1/masterdata/job-demands/import").with(user("admin1", "X7001", "/AIRLINE", "OH_ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                [{"jobCode":"TEST_JOB","title":"Test job","unit":"CORP","demands":{"DIAGNOSIS":"x"}}]"""))
                .andExpect(status().isBadRequest());

        mvc.perform(put("/api/v1/documents/retention/PHOTO").with(DPO).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"retentionYears\":8,\"trigger\":\"DOCUMENT_DATE\",\"legalBasis\":\"Approved by DPO and CMO\"}"))
                .andExpect(jsonPath("$.approvedBy").value("dpo1"));
        mvc.perform(get("/api/v1/documents/retention/due").with(NURSE)).andExpect(status().isForbidden());
        String id = signedCertificate();
        mvc.perform(post("/api/v1/documents/" + id + "/legal-hold").with(DPO).contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"Employment tribunal claim\"}")).andExpect(jsonPath("$.legalHold").value(true));
        jdbc.update("update docs.document set created_at = now() - interval '20 years' where id <> ?::uuid and type = 'FITNESS_CERTIFICATE'", id);
        jdbc.update("update docs.document set created_at = now() - interval '20 years' where id = ?::uuid", id);
        List<String> due = json.readTree(mvc.perform(get("/api/v1/documents/retention/due").with(DPO)).andReturn().getResponse().getContentAsString())
                .valueStream().map(n -> n.get("documentId").asString()).toList();
        assertThat(due).isNotEmpty().doesNotContain(id); // legal hold excludes the document from destruction review
    }

    static byte[] pdfWithText(String text) throws Exception {
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDPage page = new PDPage();
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.beginText();
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 11);
                cs.newLineAtOffset(50, 700);
                cs.showText(text);
                cs.endText();
            }
            doc.save(out);
            return out.toByteArray();
        }
    }
}
