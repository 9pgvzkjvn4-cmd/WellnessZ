package com.wellnessz.app;

import static com.wellnessz.app.PlatformIntegrationTest.user;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
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

import com.wellnessz.e03.results.ResultService;
import com.wellnessz.integration.hl7.Hl7v2Service;
import com.wellnessz.integration.outbox.OutboxRelay;

import ca.uhn.fhir.context.FhirContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Acceptance tests for increment 2 – E03 OH record and clinical encounter documentation (features F01–F08), including
 * segregation (HR / manager / employee / reception negative tests), the restricted class and break-glass.
 */
class Increment2RecordIntegrationTest extends IntegrationTestBase {

    @Autowired
    MockMvc mvc;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    JsonMapper json;
    @Autowired
    OutboxRelay relay;
    @Autowired
    ResultService resultService;
    @Autowired
    Hl7v2Service hl7;
    @Autowired
    FhirContext fhir;

    static final JwtRequestPostProcessor NURSE = user("nurse1", "N1", null, "OH_CLINICIAN");
    static final JwtRequestPostProcessor NURSE2 = user("nurse2", "N2", null, "OH_CLINICIAN");
    static final JwtRequestPostProcessor PHYSICIAN = user("physician1", "PH1", null, "OH_PHYSICIAN");
    static final JwtRequestPostProcessor CMO = user("cmo1", "CM1", null, "CMO");
    static final JwtRequestPostProcessor HEAD = user("nursinghead1", "NH1", null, "NURSING_HEAD", "OH_CLINICIAN");
    static final JwtRequestPostProcessor PSYCH = user("psych1", "PS1", null, "PSYCH_CLINICIAN");
    static final JwtRequestPostProcessor HR = user("hr1", "HR1", "/AIRLINE", "HR");
    static final JwtRequestPostProcessor MANAGER = user("mgr1", "M9", "/AIRLINE/OPS", "MANAGER");
    static final JwtRequestPostProcessor RECEPTION = user("reception1", "R1", null, "CLINIC_RECEPTION");
    static final JwtRequestPostProcessor INTEGRATION = user("lis-gateway", null, null, "INTEGRATION");
    static final JwtRequestPostProcessor ADMIN = user("ohadmin1", null, null, "OH_ADMIN");

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
        assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isBetween(200, 299);
        return body(r);
    }

    /** A fresh employee in the HRIS master (ground handling, ramp agent unless stated). */
    String newEmployee(String jobCode) {
        String id = "E" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        jdbc.update("""
                insert into masterdata.employee (employee_id, display_name, email, subsidiary_code, department_code, job_code, location_code, updated_at)
                values (?, ?, ?, 'GND', 'GROUND', ?, 'LHR', now())""", id, "Test " + id, id.toLowerCase() + "@wellnessz.local", jobCode);
        return id;
    }

    String selfReferralCase(String employeeId, String category) throws Exception {
        JsonNode c = ok(post("/api/v1/cases/self-referral"), user("emp-" + employeeId, employeeId, "/AIRLINE/OPS/GROUND", "EMPLOYEE"),
                "{\"category\":\"" + category + "\",\"description\":\"Pain after loading bags\"}");
        return c.get("id").asString();
    }

    static List<String> texts(JsonNode array, String field) {
        List<String> out = new ArrayList<>();
        array.forEach(n -> out.add(n.path(field).asString()));
        return out;
    }

    // ================================================================== F01 – chart

    @Test
    void f01ChartSummaryJobsOdhAndFhirForDemoEmployee() throws Exception {
        long start = System.nanoTime();
        JsonNode chart = ok(get("/api/v1/employees/E1004/chart"), NURSE, null);
        long ms = (System.nanoTime() - start) / 1_000_000;
        assertThat(ms).as("chart summary latency").isLessThan(2000);
        assertThat(chart.path("employee").path("jobCode").asString()).isEqualTo("RAMP_AGENT");
        assertThat(chart.path("employee").path("safetyCategory").asString()).isEqualTo("AIRSIDE_DRIVER");
        assertThat(texts(chart.path("alerts"), "kind")).contains("ALLERGY", "OVERDUE_SURVEILLANCE");
        assertThat(chart.has("fitness")).isTrue();
        assertThat(chart.path("restrictions").isArray()).isTrue();
        JsonNode noise = null;
        JsonNode fuel = null;
        for (JsonNode d : chart.path("surveillanceDue")) {
            if ("NOISE".equals(d.path("hazardCode").asString())) {
                noise = d;
            }
            if ("JET_FUEL".equals(d.path("hazardCode").asString())) {
                fuel = d;
            }
        }
        assertThat(noise).isNotNull();
        assertThat(noise.path("lastDone").asString()).isNotBlank(); // demo baseline audiogram
        assertThat(noise.path("programme").asString()).startsWith("Audiometry");
        assertThat(fuel).isNotNull();
        assertThat(fuel.path("overdue").asBoolean()).isTrue();
        assertThat(texts(chart.path("immunisations"), "vaccine")).contains("Hepatitis B");
        assertThat(chart.path("counts").path("allergies").asInt()).isGreaterThanOrEqualTo(1);
        assertThat(chart.path("counts").path("problems").asInt()).isGreaterThanOrEqualTo(1);

        JsonNode jobs = ok(get("/api/v1/employees/E1004/jobs"), NURSE, null);
        JsonNode current = jobs.get(0);
        assertThat(current.path("current").asBoolean()).isTrue();
        assertThat(current.path("source").asString()).isEqualTo("HRIS");
        assertThat(current.path("jobCode").asString()).isEqualTo("RAMP_AGENT");
        assertThat(texts(current.path("hazards"), "code")).contains("NOISE", "JET_FUEL");
        assertThat(texts(jobs, "jobCode")).contains("BAGGAGE_HANDLER");

        MvcResult odh = call(get("/api/v1/employees/E1004/odh"), NURSE, null).andExpect(status().isOk()).andReturn();
        String odhJson = odh.getResponse().getContentAsString();
        assertThat(odhJson).contains("\"resourceType\":\"Bundle\"", "11341-5", "21843-8", "Ramp agent", "Occupational noise");
        fhir.newJsonParser().parseResource(odhJson); // structurally valid R4

        String record = call(get("/api/v1/employees/E1004/fhir"), PHYSICIAN, null).andExpect(status().isOk()).andReturn()
                .getResponse().getContentAsString();
        assertThat(record).contains("\"Patient\"", "AllergyIntolerance", "Condition", "Immunization", "91936005", "28539-6");
        fhir.newJsonParser().parseResource(record);

        JsonNode timeline = ok(get("/api/v1/employees/E1004/record-timeline"), NURSE, null);
        assertThat(texts(timeline, "kind")).contains("RESULT", "PROBLEM");
    }

    @Test
    void f01ProblemsAllergiesMedicationsImmunisationsAndJobsWithCds() throws Exception {
        String emp = newEmployee("RAMP_AGENT");
        call(post("/api/v1/employees/" + emp + "/problems"), NURSE, "{\"code\":{\"system\":\"ICD_10\",\"code\":\"Q99.99\"}}")
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("unknown_code"));
        JsonNode problem = ok(post("/api/v1/employees/" + emp + "/problems"), NURSE,
                "{\"code\":{\"system\":\"ICD_10\",\"code\":\"S93.4\"},\"workRelated\":\"YES\",\"note\":\"Twisted on aircraft steps\"}");
        assertThat(problem.path("code").path("display").asString()).isEqualTo("Sprain of ankle");
        String stored = jdbc.queryForObject("select note from ohrecord.problem where id = ?::uuid", String.class, problem.get("id").asString());
        assertThat(stored).startsWith("v1:").doesNotContain("aircraft");
        ok(put("/api/v1/problems/" + problem.get("id").asString()), NURSE, "{\"status\":\"RESOLVED\"}");
        JsonNode problems = ok(get("/api/v1/employees/" + emp + "/problems"), PHYSICIAN, null);
        assertThat(problems.get(0).path("status").asString()).isEqualTo("RESOLVED");
        assertThat(problems.get(0).path("resolvedDate").asString()).isNotBlank();

        ok(post("/api/v1/employees/" + emp + "/allergies"), NURSE, """
                {"substance":{"system":"SNOMED_CT","code":"91936005","display":"Penicillin"},"category":"MEDICATION","criticality":"HIGH",
                 "reaction":"Anaphylaxis"}""");
        // allergy-class alert blocks until overridden with a reason
        call(post("/api/v1/employees/" + emp + "/medications"), NURSE,
                "{\"name\":\"Amoxicillin 500 mg capsules\",\"dose\":\"500 mg\",\"route\":\"ORAL\",\"source\":\"ADMINISTERED\"}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("cds_alert"))
                .andExpect(jsonPath("$.alerts[0].code").value("ALLERGY_CLASS"))
                .andExpect(jsonPath("$.alerts[0].severity").value("CRITICAL"));
        call(post("/api/v1/employees/" + emp + "/medications"), NURSE, """
                {"name":"Amoxicillin 500 mg capsules","source":"ADMINISTERED","overrides":[{"alertCode":"ALLERGY_CLASS","reason":""}]}""")
                .andExpect(status().isBadRequest());
        JsonNode amox = ok(post("/api/v1/employees/" + emp + "/medications"), NURSE, """
                {"name":"Amoxicillin 500 mg capsules","source":"ADMINISTERED",
                 "overrides":[{"alertCode":"ALLERGY_CLASS","reason":"Allergy disproven by specialist testing in 2025"}]}""");
        assertThat(amox.path("overrides").get(0).path("alertCode").asString()).isEqualTo("ALLERGY_CLASS");
        ok(post("/api/v1/employees/" + emp + "/medications"), NURSE, "{\"name\":\"Warfarin 3 mg\",\"source\":\"REPORTED\"}");
        call(post("/api/v1/employees/" + emp + "/medications"), NURSE, "{\"name\":\"Ibuprofen 400 mg\",\"source\":\"ADMINISTERED\"}")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.alerts[*].code", hasItem("INTERACTION")));
        JsonNode meds = ok(get("/api/v1/employees/" + emp + "/medications"), NURSE, null);
        assertThat(meds.size()).isEqualTo(2);
        ok(put("/api/v1/medications/" + meds.get(0).get("id").asString()), NURSE, "{\"status\":\"STOPPED\"}");

        JsonNode stats = ok(get("/api/v1/cds/statistics"), CMO, null);
        assertThat(stats.path("fired").asLong()).isGreaterThanOrEqualTo(3);
        assertThat(stats.path("overridden").asLong()).isGreaterThanOrEqualTo(1);
        assertThat(stats.path("overriddenWithoutReason").asLong()).isZero();
        call(get("/api/v1/cds/statistics"), NURSE, null).andExpect(status().isForbidden());

        JsonNode imm = ok(post("/api/v1/employees/" + emp + "/immunisations"), NURSE,
                "{\"vaccine\":\"Tetanus/diphtheria\",\"date\":\"2026-10-01\",\"doseNumber\":1,\"status\":\"COMPLETE\",\"nextDueDate\":\"2036-10-01\"}");
        assertThat(imm.path("status").asString()).isEqualTo("COMPLETE");

        JsonNode job = ok(post("/api/v1/employees/" + emp + "/jobs"), NURSE, """
                {"jobTitle":"Aircraft refueller","employer":"Fuel services contractor","industry":"Aviation fuel","startDate":"2015-01-01",
                 "endDate":"2018-12-31","duties":"Refuelling aircraft","weeklyHours":45,"hazardCodes":["JET_FUEL","NOISE"]}""");
        assertThat(texts(job.path("hazards"), "code")).containsExactly("JET_FUEL", "NOISE");
        call(post("/api/v1/employees/" + emp + "/jobs"), NURSE, "{\"jobTitle\":\"X\",\"hazardCodes\":[\"NOT_A_HAZARD\"]}")
                .andExpect(status().isBadRequest());
        JsonNode jobs = ok(get("/api/v1/employees/" + emp + "/jobs"), NURSE, null);
        assertThat(jobs.size()).isEqualTo(2);
        assertThat(jobs.get(0).path("source").asString()).isEqualTo("HRIS");
        // HRIS job change closes the previous HRIS entry
        jdbc.update("update masterdata.employee set job_code = 'GSE_DRIVER' where employee_id = ?", emp);
        jdbc.update("insert into masterdata.job_code (code, title, safety_critical, hazard_codes, safety_category) values "
                + "('GSE_DRIVER', 'GSE driver', true, 'NOISE', 'AIRSIDE_DRIVER') on conflict (code) do nothing");
        JsonNode after = ok(get("/api/v1/employees/" + emp + "/jobs"), NURSE, null);
        assertThat(after.size()).isEqualTo(3);
        assertThat(after.get(0).path("jobCode").asString()).isEqualTo("GSE_DRIVER");
        assertThat(texts(after, "current")).containsOnlyOnce("true");
    }

    // ================================================================== F02 – encounters

    @Test
    void f02InjuryEncounterWithBodyMapSignatureAddendumAndCaseDiagnosis() throws Exception {
        String emp = newEmployee("RAMP_AGENT");
        String caseId = selfReferralCase(emp, "INJURY");
        JsonNode templates = ok(get("/api/v1/encounter-templates"), NURSE, null);
        assertThat(templates.size()).isEqualTo(8);
        assertThat(texts(templates, "type")).contains("INJURY", "ILLNESS", "FITNESS_ASSESSMENT", "SURVEILLANCE_EXAM", "MENTAL_HEALTH",
                "TRAVEL", "CREW_AEROMEDICAL", "FOOD_HANDLER");
        assertThat(ok(get("/api/v1/body-map/mechanisms"), NURSE, null).size()).isEqualTo(12);

        // closing the clinical case without a coded diagnosis is refused
        call(post("/api/v1/cases/" + caseId + "/transition"), NURSE, "{\"status\":\"CLOSED\"}")
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("diagnosis_required"));

        JsonNode enc = ok(post("/api/v1/employees/" + emp + "/encounters"), NURSE, "{\"type\":\"INJURY\",\"caseId\":\"" + caseId + "\"}");
        String id = enc.get("id").asString();
        assertThat(enc.path("status").asString()).isEqualTo("DRAFT");
        assertThat(texts(enc.path("cdsAlerts"), "code")).contains("PROTOCOL_PROMPT");
        call(post("/api/v1/encounters/" + id + "/sign"), NURSE, null)
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("mandatory_fields_missing"))
                .andExpect(jsonPath("$.fields", hasItem("incidentDate")));
        call(put("/api/v1/encounters/" + id), NURSE, "{\"answers\":{\"notATemplateField\":\"x\"}}").andExpect(status().isBadRequest());
        call(put("/api/v1/encounters/" + id), NURSE, "{\"answers\":{\"workRelated\":\"MAYBE\"}}").andExpect(status().isBadRequest());
        call(put("/api/v1/encounters/" + id), NURSE2, "{\"answers\":{\"workRelated\":\"YES\"}}").andExpect(status().isForbidden());
        JsonNode saved = ok(put("/api/v1/encounters/" + id), NURSE, """
                {"answers":{"incidentDate":"%s","workRelated":"YES","activity":"Loading bags into hold","painScore":6,
                            "treatment":["COLD_COMPRESS","ANALGESIA"],"workStatus":"RETURN_MODIFIED"},
                 "soap":{"subjective":"Felt a pull in the lower back lifting a 30 kg bag","objective":"Paraspinal tenderness L4-L5",
                         "assessment":"Lumbar strain","plan":"Modified duties, review in 7 days"},
                 "bodyMap":[{"region":"LOWER_BACK","side":"MIDLINE","view":"BACK","mechanism":"LIFTING"}],
                 "diagnoses":[{"system":"ICD_10","code":"S33.5","primary":true}]}""".formatted(LocalDate.now()));
        assertThat(saved.path("primaryDiagnosis").path("code").asString()).isEqualTo("S33.5");
        assertThat(saved.path("bodyMap").get(0).path("mechanism").asString()).isEqualTo("LIFTING");
        assertThat(jdbc.queryForObject("select primary_diagnosis_code from casemgmt.oh_case where id = ?::uuid", String.class, caseId))
                .isEqualTo("S33.5");
        assertThat(jdbc.queryForObject("""
                select count(*) from ohrecord.encounter_observation where encounter_id = ?::uuid and active and site_code = 'LOWER_BACK'
                   and mechanism_code = 'LIFTING' and code_system = 'WZ_BODY_MAP'""", Integer.class, id)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select soap from ohrecord.encounter where id = ?::uuid", String.class, id))
                .startsWith("v1:").doesNotContain("Lumbar");

        call(post("/api/v1/encounters/" + id + "/sign"), NURSE2, null).andExpect(status().isForbidden());
        JsonNode signed = ok(post("/api/v1/encounters/" + id + "/sign"), NURSE, null);
        assertThat(signed.path("status").asString()).isEqualTo("SIGNED");
        assertThat(signed.path("signature").path("signedBy").asString()).isEqualTo("nurse1");
        assertThat(signed.path("signature").path("sha256").asString()).matches("[0-9a-f]{64}");
        call(put("/api/v1/encounters/" + id), NURSE, "{\"soap\":{\"plan\":\"changed\"}}")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("encounter_locked"));
        JsonNode withAddendum = ok(post("/api/v1/encounters/" + id + "/addenda"), PHYSICIAN, "{\"text\":\"X-ray not required.\"}");
        assertThat(withAddendum.path("addenda").size()).isEqualTo(1);
        assertThat(withAddendum.path("addenda").get(0).path("sha256").asString()).matches("[0-9a-f]{64}");
        assertThat(jdbc.queryForObject("select count(*) from platform.outbox_event where event_type = 'encounter.signed.v1' and aggregate_id = ?",
                Integer.class, id)).isEqualTo(1);

        // the case can be closed now that a primary diagnosis is coded
        assertThat(call(post("/api/v1/cases/" + caseId + "/transition"), NURSE, "{\"status\":\"CLOSED\"}").andReturn().getResponse()
                .getContentAsString()).doesNotContain("diagnosis_required");

        // case handover summary (rule based)
        JsonNode summary = ok(get("/api/v1/cases/" + caseId + "/summary"), PHYSICIAN, null);
        assertThat(summary.path("generator").asString()).isEqualTo("RULE_BASED");
        assertThat(summary.path("primaryDiagnosis").path("code").asString()).isEqualTo("S33.5");
        assertThat(summary.path("lastEncounter").path("id").asString()).isEqualTo(id);
    }

    @Test
    void f02UnsignedRemindersEnteredInErrorPhotosAndTemplateApproval() throws Exception {
        String emp = newEmployee("CATERING_ASSOC");
        String draft = ok(post("/api/v1/employees/" + emp + "/encounters"), NURSE, "{\"type\":\"ILLNESS\"}").get("id").asString();
        jdbc.update("update ohrecord.encounter set created_at = now() - interval '2 hours' where id = ?::uuid", draft);
        assertThat(texts(ok(get("/api/v1/encounters/unsigned?mine=true"), NURSE, null), "id")).contains(draft);
        assertThat(texts(ok(get("/api/v1/encounters/unsigned?mine=false"), HEAD, null), "id")).contains(draft);
        assertThat(texts(ok(get("/api/v1/encounters/unsigned?mine=true"), PHYSICIAN, null), "id")).doesNotContain(draft);

        // photo capture requires recorded consent
        byte[] png = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0x0D, 'I', 'H', 'D', 'R'};
        MockMultipartFile file = new MockMultipartFile("file", "wound.png", "image/png", png);
        mvc.perform(multipart("/api/v1/encounters/" + draft + "/photos").file(file).with(NURSE))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("consent_required"));
        MvcResult photo = mvc.perform(multipart("/api/v1/encounters/" + draft + "/photos").file(file).param("consent", "true")
                        .param("bodyRegion", "HAND").with(NURSE))
                .andExpect(status().isCreated()).andReturn();
        String documentId = body(photo).get("documentId").asString();
        JsonNode viewed = ok(get("/api/v1/encounters/" + draft), NURSE, null);
        assertThat(viewed.path("photos").get(0).path("documentId").asString()).isEqualTo(documentId);
        assertThat(jdbc.queryForObject("select type from docs.document where id = ?::uuid", String.class, documentId)).isEqualTo("PHOTO");

        // entered in error: author or CMO only
        call(post("/api/v1/encounters/" + draft + "/entered-in-error"), NURSE2, "{\"reason\":\"Wrong employee selected\"}")
                .andExpect(status().isForbidden());
        JsonNode eie = ok(post("/api/v1/encounters/" + draft + "/entered-in-error"), CMO, "{\"reason\":\"Wrong employee selected\"}");
        assertThat(eie.path("status").asString()).isEqualTo("ENTERED_IN_ERROR");
        assertThat(eie.path("enteredInError").path("by").asString()).isEqualTo("cmo1");
        assertThat(texts(ok(get("/api/v1/encounters/unsigned?mine=true"), NURSE, null), "id")).doesNotContain(draft);

        // template governance: CMO approval
        call(post("/api/v1/encounter-templates/TRAVEL/approve"), NURSE, null).andExpect(status().isForbidden());
        JsonNode approved = ok(post("/api/v1/encounter-templates/TRAVEL/approve"), CMO, "{\"version\":1}");
        assertThat(approved.path("status").asString()).isEqualTo("APPROVED");
        assertThat(approved.path("approvedBy").asString()).isEqualTo("cmo1");
    }

    @Test
    void f02MentalHealthEncountersAreRestrictedAndBreakGlassOpensThem() throws Exception {
        String emp = newEmployee("OFFICE_ADMIN");
        call(post("/api/v1/employees/" + emp + "/encounters"), NURSE, "{\"type\":\"MENTAL_HEALTH\"}").andExpect(status().isForbidden());
        JsonNode mh = ok(post("/api/v1/employees/" + emp + "/encounters"), PSYCH, "{\"type\":\"MENTAL_HEALTH\"}");
        String id = mh.get("id").asString();
        assertThat(mh.path("classification").asString()).isEqualTo("RESTRICTED_CLINICAL");
        ok(put("/api/v1/encounters/" + id), PSYCH, "{\"diagnoses\":[{\"system\":\"ICD_10\",\"code\":\"F43.2\",\"primary\":true}]}");

        call(get("/api/v1/encounters/" + id), NURSE, null).andExpect(status().isNotFound());
        call(get("/api/v1/employees/" + emp + "/encounters"), NURSE, null)
                .andExpect(status().isOk()).andExpect(header().string("X-Restricted-Items-Hidden", "1"))
                .andExpect(jsonPath("$.length()").value(0));
        JsonNode chart = ok(get("/api/v1/employees/" + emp + "/chart"), NURSE, null);
        assertThat(chart.path("restrictedItemsHidden").asInt()).isGreaterThanOrEqualTo(1);
        assertThat(chart.path("counts").path("unsignedEncounters").asInt()).isZero();
        ok(get("/api/v1/encounters/" + id), PSYCH, null);
        assertThat(jdbc.queryForObject("""
                select count(*) from platform.audit_event where actor_roles like '%OH_CLINICIAN%' and action = 'ENCOUNTER_READ' and resource_id = ?
                   and outcome = 'DENIED'""", Integer.class, id)).isGreaterThanOrEqualTo(1);

        // break-glass: justified emergency access opens the restricted record (and alerts the DPO)
        ok(post("/api/v1/break-glass"), NURSE, "{\"employeeId\":\"" + emp + "\",\"reason\":\"Unconscious at work, need medication history now\"}");
        JsonNode opened = ok(get("/api/v1/encounters/" + id), NURSE, null);
        assertThat(opened.path("primaryDiagnosis").path("code").asString()).isEqualTo("F43.2");
        call(get("/api/v1/employees/" + emp + "/encounters"), NURSE, null).andExpect(header().string("X-Restricted-Items-Hidden", "0"));
    }

    // ================================================================== F03 – terminology

    @Test
    void f03TerminologySearchFavouritesRecentMappingsAndReleases() throws Exception {
        long start = System.nanoTime();
        JsonNode hits = ok(get("/api/v1/terminology/search?system=ICD_10&q=ankl&limit=10"), NURSE, null);
        assertThat((System.nanoTime() - start) / 1_000_000).as("search latency").isLessThan(1000);
        assertThat(texts(hits, "code")).contains("S93.4");
        JsonNode byCode = ok(get("/api/v1/terminology/search?q=S93"), NURSE, null);
        assertThat(byCode.get(0).path("code").asString()).isEqualTo("S93.4");
        assertThat(texts(ok(get("/api/v1/terminology/search?system=LOINC&q=hemoglobin a1c"), NURSE, null), "code")).contains("4548-4");
        assertThat(texts(ok(get("/api/v1/terminology/search?system=SNOMED_CT&q=penicillin"), NURSE, null), "code")).contains("91936005");
        assertThat(ok(get("/api/v1/terminology/search?q=%27%3B%20drop%20table"), NURSE, null).size()).isZero();
        call(get("/api/v1/terminology/search?q=ankle"), HR, null).andExpect(status().isForbidden());

        // favourites: role starter set + personal favourites, reachable in ≤ 3 keystrokes via the list
        JsonNode favs = ok(get("/api/v1/terminology/favourites"), NURSE, null);
        assertThat(texts(favs, "code")).contains("S93.4");
        assertThat(texts(favs, "scope")).contains("ROLE");
        ok(post("/api/v1/terminology/favourites"), NURSE2, "{\"system\":\"ICD_10\",\"code\":\"L24.9\"}");
        JsonNode mine = ok(get("/api/v1/terminology/favourites"), NURSE2, null);
        assertThat(mine.get(0).path("scope").asString()).isEqualTo("USER");
        assertThat(mine.get(0).path("display").asString()).isEqualTo("Irritant contact dermatitis, unspecified cause");
        ok(delete("/api/v1/terminology/favourites?system=ICD_10&code=L24.9"), NURSE2, null);
        assertThat(texts(ok(get("/api/v1/terminology/favourites"), NURSE2, null), "scope")).doesNotContain("USER");
        call(post("/api/v1/terminology/favourites"), NURSE, "{\"system\":\"ICD_10\",\"code\":\"S61.4\",\"role\":\"OH_CLINICIAN\"}")
                .andExpect(status().isForbidden());

        // recent codes come from coding activity
        String emp = newEmployee("RAMP_AGENT");
        ok(post("/api/v1/employees/" + emp + "/problems"), PHYSICIAN, "{\"code\":{\"system\":\"ICD_10\",\"code\":\"H83.3\"}}");
        assertThat(ok(get("/api/v1/terminology/recent"), PHYSICIAN, null).get(0).path("code").asString()).isEqualTo("H83.3");

        assertThat(ok(get("/api/v1/terminology/mappings?system=ICD_10&code=A02.0"), NURSE, null).path("category").asString())
                .isEqualTo("FOOD_HANDLER_NOTIFIABLE");
        assertThat(ok(get("/api/v1/terminology/mappings?system=ICD_10&code=H83.3"), NURSE, null).path("category").asString())
                .isEqualTo("NOISE_INDUCED_HEARING_LOSS");

        // annual release import (OH_ADMIN) and release register
        call(post("/api/v1/terminology/concepts/import"), NURSE, "[{\"system\":\"ICD_10\",\"code\":\"ZZ9.1\",\"display\":\"x\"}]")
                .andExpect(status().isForbidden());
        JsonNode imported = ok(post("/api/v1/terminology/concepts/import?version=2027-test&licence=Test%20licence"), ADMIN, """
                [{"system":"ICD_10","code":"ZZ9.1","display":"Test concept one","active":true},
                 {"system":"ICD_10","code":"ZZ9.2","display":"Test concept retired","active":false}]""");
        assertThat(imported.path("upserted").asInt()).isEqualTo(2);
        assertThat(imported.path("deactivated").asInt()).isEqualTo(1);
        assertThat(texts(ok(get("/api/v1/terminology/search?q=test concept"), NURSE, null), "code")).contains("ZZ9.1").doesNotContain("ZZ9.2");
        assertThat(texts(ok(get("/api/v1/terminology/releases"), ADMIN, null), "version")).contains("2027-test", "starter-2026.1");
    }

    // ================================================================== F04 – orders, results, devices

    @Test
    void f04LabOrderPullQueueResultsWorklistAcknowledgementAndLetter() throws Exception {
        String emp = newEmployee("CATERING_ASSOC");
        call(post("/api/v1/employees/" + emp + "/orders"), NURSE2, "{\"orderSetCode\":\"NOT_A_SET\"}").andExpect(status().isBadRequest());
        assertThat(texts(ok(get("/api/v1/order-sets"), NURSE, null), "code"))
                .contains("NOISE_SURVEILLANCE", "FOOD_HANDLER_SCREEN", "PRE_PLACEMENT_BLOODS", "SPIROMETRY_BASELINE");
        JsonNode order = ok(post("/api/v1/employees/" + emp + "/orders"), PHYSICIAN,
                "{\"orderSetCode\":\"PRE_PLACEMENT_BLOODS\",\"priority\":\"ROUTINE\",\"clinicalNote\":\"Pre-placement\"}");
        String orderId = order.get("id").asString();
        String placer = order.get("placerOrderNumber").asString();
        assertThat(placer).matches("ORD-\\d{4}-\\d{6}");
        assertThat(order.path("status").asString()).isEqualTo("QUEUED");
        assertThat(order.path("items").size()).isEqualTo(6);

        // lab gateway pulls the ORM^O01 and acknowledges it
        call(get("/api/v1/integration/lis/orders"), NURSE, null).andExpect(status().isForbidden());
        JsonNode queue = ok(get("/api/v1/integration/lis/orders?status=QUEUED"), INTEGRATION, null);
        String er7 = null;
        for (JsonNode q : queue) {
            if (placer.equals(q.path("placerOrderNumber").asString())) {
                er7 = q.path("hl7").asString();
            }
        }
        assertThat(er7).startsWith("MSH|").contains("ORM^O01", placer, emp, "2345-7^");
        assertThat(hl7.parse(er7).messageType()).isEqualTo("ORM^O01");
        ok(post("/api/v1/integration/lis/orders/" + orderId + "/ack"), INTEGRATION, null);
        assertThat(ok(get("/api/v1/employees/" + emp + "/orders"), NURSE, null).get(0).path("status").asString()).isEqualTo("SENT");

        // duplicate test within 30 days needs an override reason
        call(post("/api/v1/employees/" + emp + "/orders"), PHYSICIAN, "{\"category\":\"LAB\",\"items\":[{\"system\":\"LOINC\",\"code\":\"2345-7\"}]}")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.alerts[0].code").value("DUPLICATE_TEST"));
        JsonNode repeat = ok(post("/api/v1/employees/" + emp + "/orders"), PHYSICIAN, """
                {"category":"LAB","items":[{"system":"LOINC","code":"2345-7"}],"priority":"URGENT",
                 "overrides":[{"alertCode":"DUPLICATE_TEST","reason":"Fasting sample required"}]}""");

        // JSON result feed: abnormal flag from the reference range, ordering physician notified (content-free)
        JsonNode ingest = ok(post("/api/v1/integration/lis/results"), INTEGRATION, """
                {"placerOrderNumber":"%s","employeeId":"%s","observations":[
                  {"code":{"system":"LOINC","code":"2345-7"},"value":"11.2","unit":"mmol/L","referenceRange":"3.9-7.8","status":"FINAL"},
                  {"code":{"system":"LOINC","code":"4548-4"},"value":"38","unit":"mmol/mol","referenceRange":"20-42","interpretation":"N"}]}"""
                .formatted(placer, emp));
        assertThat(ingest.path("received").asInt()).isEqualTo(2);
        assertThat(ingest.path("abnormal").asInt()).isEqualTo(1);
        call(post("/api/v1/integration/lis/results"), INTEGRATION, """
                {"placerOrderNumber":"%s","employeeId":"E1004","observations":[{"code":{"system":"LOINC","code":"2345-7"},"value":"5"}]}"""
                .formatted(placer)).andExpect(status().isUnprocessableContent());
        JsonNode orders = ok(get("/api/v1/employees/" + emp + "/orders"), NURSE, null);
        JsonNode first = null;
        for (JsonNode o : orders) {
            if (orderId.equals(o.path("id").asString())) {
                first = o;
            }
        }
        assertThat(first.path("status").asString()).isEqualTo("PARTIAL");
        assertThat(first.path("resultCount").asInt()).isEqualTo(2);
        assertThat(jdbc.queryForObject("""
                select count(*) from platform.notification where recipient_id = 'physician1' and template_key = 'escalation.raised'
                   and body like ?""", Integer.class, "%" + placer + "%")).isGreaterThanOrEqualTo(1);

        JsonNode worklist = ok(get("/api/v1/results/worklist"), PHYSICIAN, null);
        String glucoseId = null;
        for (JsonNode r : worklist) {
            if (emp.equals(r.path("employeeId").asString())) {
                glucoseId = r.path("id").asString();
                assertThat(r.path("flag").asString()).isEqualTo("HIGH");
                assertThat(r.path("overdue").asBoolean()).isFalse();
            }
        }
        assertThat(glucoseId).isNotNull();
        JsonNode acked = ok(post("/api/v1/results/" + glucoseId + "/acknowledge"), PHYSICIAN, "{\"action\":\"REPEAT_TEST\",\"note\":\"Repeat fasting\"}");
        assertThat(acked.path("ackAction").asString()).isEqualTo("REPEAT_TEST");
        call(post("/api/v1/results/" + glucoseId + "/acknowledge"), PHYSICIAN, "{\"action\":\"NO_ACTION\"}").andExpect(status().isConflict());
        assertThat(texts(ok(get("/api/v1/results/worklist"), PHYSICIAN, null), "id")).doesNotContain(glucoseId);
        JsonNode letter = ok(post("/api/v1/results/" + glucoseId + "/letter"), NURSE, "{}");
        assertThat(letter.path("type").asString()).isEqualTo("RESULT_LETTER");
        assertThat(letter.path("status").asString()).isEqualTo("DRAFT");

        // HL7 v2 ORU^R01 through the platform endpoint is processed the same way
        String placer2 = repeat.get("placerOrderNumber").asString();
        String oru = "MSH|^~\\&|LABSYS|LAB|WELLNESSZ|OH|20261029101500||ORU^R01|MSG-" + placer2 + "|P|2.5\r"
                + "PID|1||" + emp + "^^^HR||Test^Employee\r"
                + "OBR|1|" + placer2 + "||2345-7^Glucose^LN\r"
                + "OBX|1|NM|2345-7^Glucose^LN||2.1|mmol/L|3.9-7.8|LL|||F\r";
        mvc.perform(post("/api/v1/integration/hl7v2").with(INTEGRATION).contentType("text/plain").content(oru)).andExpect(status().isOk());
        relay.relay();
        JsonNode results = ok(get("/api/v1/employees/" + emp + "/results?category=LAB"), PHYSICIAN, null);
        JsonNode critical = null;
        for (JsonNode r : results) {
            if ("CRITICAL".equals(r.path("flag").asString())) {
                critical = r;
            }
        }
        assertThat(critical).as("ORU result").isNotNull();
        assertThat(critical.path("source").asString()).isEqualTo("LIS");
        assertThat(critical.path("orderId").asString()).isEqualTo(repeat.get("id").asString());

        // 72-hour escalation of unacknowledged abnormal results to the CMO
        jdbc.update("update ohrecord.result set received_at = now() - interval '80 hours' where id = ?::uuid", critical.get("id").asString());
        assertThat(resultService.escalateOverdue()).isGreaterThanOrEqualTo(1);
        assertThat(jdbc.queryForObject("""
                select count(*) from platform.notification where recipient_id = 'cmo1' and template_key = 'escalation.raised' and body like ?""",
                Integer.class, "%" + placer2 + "%")).isEqualTo(1);
        assertThat(texts(ok(get("/api/v1/results/worklist?overdue=true"), CMO, null), "id")).contains(critical.get("id").asString());
        assertThat(resultService.escalateOverdue()).isZero(); // escalated once only

        // cancel: ordering clinician only
        JsonNode device = ok(post("/api/v1/employees/" + emp + "/orders"), PHYSICIAN, "{\"orderSetCode\":\"VISION_SCREEN\"}");
        assertThat(device.path("status").asString()).isEqualTo("SENT");
        call(post("/api/v1/orders/" + device.get("id").asString() + "/cancel"), NURSE, null).andExpect(status().isForbidden());
        assertThat(ok(post("/api/v1/orders/" + device.get("id").asString() + "/cancel"), PHYSICIAN, null).path("status").asString())
                .isEqualTo("CANCELLED");
    }

    @Test
    void f04DeviceCaptureAudiometryStsSpirometryVisionEcg() throws Exception {
        // E1004 has a demo baseline audiogram (left 2/3/4 kHz average 20 dB, right 15 dB)
        String audiogram = """
                {"category":"AUDIOMETRY","deviceId":"AUD-LHR-01","deviceModel":"Demo audiometer 300","calibrationId":%s,
                 "calibrationDate":"2026-09-01","measurements":{"left":{"500":10,"1000":15,"2000":30,"3000":35,"4000":40,"6000":45,"8000":35},
                 "right":{"500":10,"1000":10,"2000":15,"3000":15,"4000":20,"6000":25,"8000":20}}}""";
        call(post("/api/v1/employees/E1004/results/device"), NURSE, audiogram.formatted("null"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("calibration_required"));
        JsonNode sts = ok(post("/api/v1/employees/E1004/results/device"), NURSE, audiogram.formatted("\"CAL-2026-0901\""));
        assertThat(sts.path("flag").asString()).isEqualTo("ABNORMAL");
        assertThat(sts.path("calibrationId").asString()).isEqualTo("CAL-2026-0901");
        assertThat(sts.path("detail").path("sts").path("left").asBoolean()).isTrue();
        assertThat(sts.path("detail").path("sts").path("right").asBoolean()).isFalse();
        assertThat(sts.path("detail").path("baselineId").asString()).isEqualTo("7e03de00-0000-4000-8000-000000000004");
        assertThat(texts(ok(get("/api/v1/results/worklist"), PHYSICIAN, null), "id")).contains(sts.get("id").asString());
        JsonNode acked = ok(post("/api/v1/results/" + sts.get("id").asString() + "/acknowledge"), PHYSICIAN,
                "{\"action\":\"FITNESS_REVIEW\",\"note\":\"Hearing protection review\"}");
        assertThat(acked.path("acknowledgedBy").asString()).isEqualTo("physician1");
        // audiometry today: the NOISE surveillance is no longer overdue on the chart
        JsonNode chart = ok(get("/api/v1/employees/E1004/chart"), NURSE, null);
        for (JsonNode d : chart.path("surveillanceDue")) {
            if ("NOISE".equals(d.path("hazardCode").asString())) {
                assertThat(d.path("overdue").asBoolean()).isFalse();
            }
        }

        String emp = newEmployee("ENGINEER");
        JsonNode baseline = ok(post("/api/v1/employees/" + emp + "/results/device"), INTEGRATION, """
                {"category":"AUDIOMETRY","deviceId":"AUD-02","calibrationId":"CAL-1","measurements":
                 {"left":{"2000":10,"3000":10,"4000":15},"right":{"2000":10,"3000":10,"4000":10}}}""");
        assertThat(baseline.path("baseline").asBoolean()).isTrue();
        assertThat(baseline.path("flag").asString()).isEqualTo("NORMAL");
        call(post("/api/v1/employees/" + emp + "/results/device"), NURSE, """
                {"category":"AUDIOMETRY","measurements":{"left":{"2000":10},"right":{"2000":10}}}""").andExpect(status().isBadRequest());

        JsonNode spiro = ok(post("/api/v1/employees/" + emp + "/results/device"), NURSE, """
                {"category":"SPIROMETRY","deviceId":"SPI-01","calibrationId":"CAL-SPI-7",
                 "measurements":{"fev1L":2.5,"fvcL":4.0,"fev1PredictedL":3.6}}""");
        assertThat(spiro.path("flag").asString()).isEqualTo("ABNORMAL");
        assertThat(spiro.path("code").path("code").asString()).isEqualTo("19926-5");
        JsonNode spiroOk = ok(post("/api/v1/employees/" + emp + "/results/device"), NURSE, """
                {"category":"SPIROMETRY","measurements":{"fev1L":3.4,"fvcL":4.2,"fev1PredictedL":3.6}}""");
        assertThat(spiroOk.path("flag").asString()).isEqualTo("NORMAL");
        assertThat(spiroOk.path("source").asString()).isEqualTo("MANUAL");

        JsonNode vision = ok(post("/api/v1/employees/" + emp + "/results/device"), NURSE, """
                {"category":"VISION","deviceId":"VIS-01","calibrationId":"CAL-V-1","measurements":
                 {"acuityLeft":"6/6","acuityRight":"6/9","binocular":"6/18","colourPlatesCorrect":14,"colourPlatesTotal":15,"corrected":false}}""");
        assertThat(vision.path("flag").asString()).isEqualTo("ABNORMAL");
        JsonNode colour = ok(post("/api/v1/employees/" + emp + "/results/device"), NURSE, """
                {"category":"VISION","measurements":{"binocular":"6/6","colourPlatesCorrect":11,"colourPlatesTotal":15}}""");
        assertThat(colour.path("flag").asString()).isEqualTo("ABNORMAL");
        JsonNode ecg = ok(post("/api/v1/employees/" + emp + "/results/device"), NURSE, """
                {"category":"ECG","deviceId":"ECG-01","calibrationId":"CAL-E-3","measurements":
                 {"rhythm":"SINUS","heartRate":72,"interpretation":"NORMAL","qtcMs":410}}""");
        assertThat(ecg.path("flag").asString()).isEqualTo("NORMAL");
        assertThat(ok(get("/api/v1/employees/" + emp + "/results?category=VISION"), NURSE, null).size()).isEqualTo(2);
    }

    // ================================================================== F05 – encounter CDS: fitness standard, surveillance, duration

    @Test
    void f05EncounterDecisionSupportFitnessStandardSurveillanceAndDuration() throws Exception {
        JsonNode fit = ok(post("/api/v1/employees/E1004/encounters"), PHYSICIAN, "{\"type\":\"FITNESS_ASSESSMENT\"}");
        assertThat(fit.path("fitnessStandard").path("code").asString()).isEqualTo("AIRSIDE_DRIVING");
        assertThat(texts(fit.path("cdsAlerts"), "code")).contains("FITNESS_STANDARD", "SURVEILLANCE_DUE", "PROTOCOL_PROMPT");
        JsonNode chosen = ok(put("/api/v1/encounters/" + fit.get("id").asString()), PHYSICIAN, "{\"fitnessStandardCode\":\"FORKLIFT\"}");
        assertThat(chosen.path("fitnessStandard").path("code").asString()).isEqualTo("FORKLIFT");
        call(put("/api/v1/encounters/" + fit.get("id").asString()), PHYSICIAN, "{\"fitnessStandardCode\":\"NOPE\"}")
                .andExpect(status().isBadRequest());

        JsonNode food = ok(post("/api/v1/employees/E1001/encounters"), NURSE, "{\"type\":\"FOOD_HANDLER\"}");
        assertThat(food.path("fitnessStandard").path("code").asString()).isEqualTo("FOOD_HANDLER");
        JsonNode crew = ok(post("/api/v1/employees/E1002/encounters"), user("ame1", "A1", null, "AME"), "{\"type\":\"CREW_AEROMEDICAL\"}");
        assertThat(crew.path("fitnessStandard").path("code").asString()).isEqualTo("CAA_CLASS1");
        assertThat(crew.path("fitnessStandard").path("requiresSignoffRole").asString()).isEqualTo("AME");

        // DURATION_EXCEEDED: ankle sprain guideline 14 days, case running for 20 days
        String emp = newEmployee("RAMP_AGENT");
        String caseId = selfReferralCase(emp, "INJURY");
        jdbc.update("update casemgmt.oh_case set created_at = now() - interval '20 days', occurred_on = current_date - 20 where id = ?::uuid", caseId);
        String enc = ok(post("/api/v1/employees/" + emp + "/encounters"), NURSE, "{\"type\":\"INJURY\",\"caseId\":\"" + caseId + "\"}")
                .get("id").asString();
        JsonNode coded = ok(put("/api/v1/encounters/" + enc), NURSE, "{\"diagnoses\":[{\"system\":\"ICD_10\",\"code\":\"S93.4\"}]}");
        assertThat(coded.path("diagnoses").get(0).path("primary").asBoolean()).isTrue();
        JsonNode alerts = ok(get("/api/v1/encounters/" + enc), NURSE, null).path("cdsAlerts");
        assertThat(texts(alerts, "code")).contains("DURATION_EXCEEDED");
    }

    // ================================================================== F06 – documents (result letter, photos covered above)

    @Test
    void f06ResultLetterIsAClinicalDocumentNotShareableWithManagers() throws Exception {
        String emp = newEmployee("RAMP_AGENT");
        JsonNode r = ok(post("/api/v1/employees/" + emp + "/results/device"), NURSE,
                "{\"category\":\"ECG\",\"measurements\":{\"interpretation\":\"BORDERLINE\",\"rhythm\":\"SINUS\"}}");
        assertThat(r.path("flag").asString()).isEqualTo("ABNORMAL");
        JsonNode letter = ok(post("/api/v1/results/" + r.get("id").asString() + "/letter"), PHYSICIAN,
                "{\"interpretation\":\"Minor ECG change\",\"action\":\"Please attend a review\"}");
        String docId = letter.get("id").asString();
        call(post("/api/v1/documents/" + docId + "/share"), PHYSICIAN, "{\"target\":\"MANAGER\"}").andExpect(status().is4xxClientError());
        call(get("/api/v1/documents/" + docId), MANAGER, null).andExpect(status().isNotFound());
        assertThat(ok(get("/api/v1/employees/" + emp + "/chart"), NURSE, null).path("counts").path("documents").asInt()).isEqualTo(1);
    }

    // ================================================================== F07 – AI documentation assistant

    @Test
    void f07AiAssistantRequiresConsentAndAConfiguredProviderAndIsAudited() throws Exception {
        String emp = newEmployee("RAMP_AGENT");
        String enc = ok(post("/api/v1/employees/" + emp + "/encounters"), NURSE, "{\"type\":\"ILLNESS\"}").get("id").asString();
        call(post("/api/v1/encounters/" + enc + "/ai-draft"), NURSE, "{\"transcript\":\"Patient reports cough for three days\"}")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("consent_required"));
        call(post("/api/v1/encounters/" + enc + "/ai-consent"), NURSE, "{\"verbal\":false,\"statement\":\"x\"}").andExpect(status().isBadRequest());
        JsonNode consent = ok(post("/api/v1/encounters/" + enc + "/ai-consent"), NURSE,
                "{\"verbal\":true,\"statement\":\"Employee agreed verbally to AI-assisted note taking\"}");
        assertThat(consent.path("purpose").asString()).isEqualTo("AI_DOCUMENTATION");
        call(post("/api/v1/encounters/" + enc + "/ai-draft"), NURSE, "{\"transcript\":\"Patient reports cough for three days\"}")
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("ai_not_configured"));
        JsonNode saved = ok(put("/api/v1/encounters/" + enc), NURSE, "{\"soap\":{\"subjective\":\"Cough for three days\"},\"aiAssisted\":true}");
        assertThat(saved.path("aiAssisted").asBoolean()).isTrue();
        assertThat(texts(ok(get("/api/v1/employees/" + emp + "/encounters"), NURSE, null), "aiAssisted")).contains("true");
        assertThat(jdbc.queryForObject("""
                select count(*) from platform.audit_event where action in ('AI_CONSENT_CAPTURED', 'AI_DRAFT', 'ENCOUNTER_AI_ASSISTED')
                   and resource_id = ?""", Integer.class, enc)).isGreaterThanOrEqualTo(3);
    }

    // ================================================================== F08 – segregation & disclosure control

    @Test
    void f08HrManagersEmployeesAndReceptionNeverOpenClinicalRecords() throws Exception {
        String[] reads = {"/chart", "/problems", "/allergies", "/medications", "/immunisations", "/jobs", "/encounters", "/results",
                "/orders", "/fhir", "/odh", "/record-timeline"};
        for (JwtRequestPostProcessor who : List.of(HR, MANAGER, RECEPTION, user("ramp", "E1004", "/AIRLINE/OPS/GROUND", "EMPLOYEE"),
                user("dpo1", null, null, "DPO"))) {
            for (String path : reads) {
                MvcResult r = call(get("/api/v1/employees/E1004" + path), who, null).andReturn();
                assertThat(r.getResponse().getStatus()).as(path).isIn(403, 404);
                assertThat(r.getResponse().getContentAsString()).doesNotContain("penicillin").doesNotContain("Low back");
            }
        }
        // HR reads are denied and audited
        assertThat(jdbc.queryForObject("""
                select count(*) from platform.audit_event where actor_roles like '%ROLE_HR%' and action = 'CHART_READ' and outcome = 'DENIED'
                   and resource_id = 'E1004'""", Integer.class)).isGreaterThanOrEqualTo(1);
        String demoResult = "7e03de00-0000-4000-8000-000000000004";
        call(post("/api/v1/results/" + demoResult + "/acknowledge"), HR, "{\"action\":\"NO_ACTION\"}").andExpect(status().isForbidden());
        call(get("/api/v1/results/worklist"), MANAGER, null).andExpect(status().isForbidden());
        call(post("/api/v1/employees/E1004/encounters"), RECEPTION, "{\"type\":\"INJURY\"}").andExpect(status().isForbidden());
        call(post("/api/v1/employees/E1004/problems"), HR, "{\"code\":{\"system\":\"ICD_10\",\"code\":\"S93.4\"}}")
                .andExpect(status().isForbidden());
        call(get("/api/v1/encounter-templates"), HR, null).andExpect(status().isForbidden());
        call(get("/api/v1/cases/" + UUID.randomUUID() + "/summary"), MANAGER, null).andExpect(status().isForbidden());
        // case managers are OH team (read) but not clinicians (write)
        JwtRequestPostProcessor caseManager = user("cm1", "CMG1", null, "CASE_MANAGER");
        ok(get("/api/v1/employees/E1004/chart"), caseManager, null);
        call(post("/api/v1/employees/E1004/allergies"), caseManager, """
                {"substance":{"display":"Latex"},"category":"ENVIRONMENT","criticality":"LOW"}""").andExpect(status().isForbidden());
        // unknown employees are not revealed
        call(get("/api/v1/employees/NOPE404/chart"), NURSE, null).andExpect(status().isNotFound());
        call(get("/api/v1/employees/bad%20id/chart"), NURSE, null).andExpect(status().isBadRequest());
        assertThat(call(get("/api/v1/encounters/" + UUID.randomUUID()), NURSE, null).andReturn().getResponse().getStatus()).isEqualTo(404);
        assertThat(ok(get("/api/v1/employees/E1004/allergies"), NURSE, null).get(0).path("substance").path("display").asString())
                .startsWith("Allergy to penicillin");
        assertThat(call(get("/api/v1/employees/E1004/chart"), NURSE, null).andReturn().getResponse().getContentAsString())
                .contains("Allergy to penicillin");
        call(get("/api/v1/employees/E1004/fhir"), NURSE, null).andExpect(header().string("Content-Type", startsWith("application/fhir+json")));
    }
}
