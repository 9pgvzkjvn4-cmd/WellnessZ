package com.wellnessz.e04;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.wellnessz.casekernel.model.CaseStatus;
import com.wellnessz.casekernel.model.OhCase;
import com.wellnessz.common.error.ApiException;
import com.wellnessz.e04.medication.MedicationChecks;
import com.wellnessz.e04.model.StockBatch;
import com.wellnessz.e04.model.TreatmentOutcomeRepository;
import com.wellnessz.e04.model.TreatmentPlanRepository;
import com.wellnessz.e04.model.TreatmentReferralRepository;
import com.wellnessz.e04.outcome.OutcomeClosureGuard;
import com.wellnessz.e04.plan.PlanMaths;
import com.wellnessz.e04.preauth.InvoiceMatcher;
import com.wellnessz.e04.provider.ContractStatus;
import com.wellnessz.e04.provider.ScorecardMaths;
import com.wellnessz.e04.referral.ChaseRules;
import com.wellnessz.e04.referral.DataSetValidator;
import com.wellnessz.e04.referral.FhirMapper;
import com.wellnessz.e04.telehealth.TokenWindow;
import com.wellnessz.e04.model.ProviderContract;

/** Unit tests of the E04 rules (contract §7). */
class TreatmentRulesTest {

    static final LocalDate D = LocalDate.of(2026, 3, 2);

    // ------------------------------------------------------------------ plan templates & variance

    @Test
    void templateExpansionDatesInterventionsAndFollowUps() {
        List<Map<String, Object>> template = List.of(
                Map.of("type", "PHYSIOTHERAPY", "description", "Physio", "frequency", "1x weekly", "weeks", 4),
                Map.of("type", "SELF_CARE", "description", "Home exercises", "weeks", 0));
        List<PlanMaths.Intervention> out = PlanMaths.expandInterventions(template, D);
        assertThat(out).hasSize(2);
        assertThat(out.get(0).startDate()).isEqualTo(D);
        assertThat(out.get(0).endDate()).isEqualTo(D.plusDays(27));
        assertThat(out.get(0).frequency()).isEqualTo("1x weekly");
        assertThat(out.get(1).endDate()).isEqualTo(D);
        assertThat(out.get(1).frequency()).isNull();
        assertThat(PlanMaths.followUpDates("14, 7,7,x,21", D)).containsExactly(D.plusDays(7), D.plusDays(14), D.plusDays(21));
        assertThat(PlanMaths.followUpDates(null, D)).isEmpty();
        assertThat(PlanMaths.expectedRecoveryDate(D, 21)).isEqualTo(D.plusDays(21));
    }

    @Test
    void varianceMaths() {
        PlanMaths.Variance v = PlanMaths.variance(D, D.plusDays(30), 21, 20);
        assertThat(v.actualDays()).isEqualTo(30);
        assertThat(v.pctOver()).isEqualByComparingTo("42.9");
        assertThat(v.alert()).isTrue();
        PlanMaths.Variance within = PlanMaths.variance(D, D.plusDays(25), 21, 20);
        assertThat(within.pctOver()).isEqualByComparingTo("19.0");
        assertThat(within.alert()).isFalse();
        assertThat(PlanMaths.variance(D, D.plusDays(10), 21, 20).pctOver()).isEqualByComparingTo("0");
        assertThat(PlanMaths.variance(D, D.plusDays(10), null, 20)).isNull();
        // exactly at the threshold: no alert (strictly above)
        assertThat(PlanMaths.variance(D, D.plusDays(12), 10, 20).alert()).isFalse();
        assertThat(PlanMaths.variance(D, D.plusDays(13), 10, 20).alert()).isTrue();
    }

    // ------------------------------------------------------------------ data set

    static final List<DataSetValidator.Field> FIELDS = List.of(new DataSetValidator.Field("bodyPart", "Body part", "CODE", true),
            new DataSetValidator.Field("injuryDate", "Date of injury", "DATE", true),
            new DataSetValidator.Field("imaging", "Imaging", "LIST", false), new DataSetValidator.Field("notes", "Notes", "TEXT", false));

    @Test
    void dataSetValidation() {
        Map<String, Object> ok = new LinkedHashMap<>();
        ok.put("bodyPart", " LOWER_BACK ");
        ok.put("injuryDate", "2026-02-01");
        ok.put("imaging", List.of("X-ray"));
        assertThat(DataSetValidator.validate(FIELDS, ok, true)).containsEntry("bodyPart", "LOWER_BACK").containsEntry("imaging", List.of("X-ray"));
        // drafts may be incomplete, sending may not
        assertThat(DataSetValidator.validate(FIELDS, Map.of("bodyPart", "KNEE"), false)).hasSize(1);
        assertThatThrownBy(() -> DataSetValidator.validate(FIELDS, Map.of("bodyPart", "KNEE"), true))
                .isInstanceOfSatisfying(DataSetValidator.DataSetException.class, e -> {
                    assertThat(e.code()).isEqualTo("data_set_incomplete");
                    assertThat(e.properties()).containsEntry("field", "injuryDate");
                });
        assertThatThrownBy(() -> DataSetValidator.validate(FIELDS, Map.of("injuryDate", "01/02/2026"), false))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo("invalid_field"));
        assertThatThrownBy(() -> DataSetValidator.validate(FIELDS, Map.of("bodyPart", "lower back!"), false))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo("invalid_field"));
        assertThatThrownBy(() -> DataSetValidator.validate(FIELDS, Map.of("unknown", "x"), false))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo("unknown_field"));
        assertThatThrownBy(() -> DataSetValidator.validate(FIELDS, Map.of("notes", "x".repeat(2001)), false)).isInstanceOf(ApiException.class);
        assertThat(DataSetValidator.fields(List.of(Map.of("code", "a", "label", "A", "type", "TEXT", "required", false))).get(0).required()).isFalse();
    }

    // ------------------------------------------------------------------ chase rules

    @Test
    void chaseRules() {
        Instant due = Instant.parse("2026-03-10T00:00:00Z");
        Instant now = due.plus(Duration.ofDays(1));
        assertThat(ChaseRules.decide("SEEN", due, null, 0, null, false, now, 2, 2)).isEqualTo(ChaseRules.Action.CHASE);
        assertThat(ChaseRules.decide("SEEN", due, null, 0, null, false, due.minusSeconds(1), 2, 2)).isEqualTo(ChaseRules.Action.NONE);
        assertThat(ChaseRules.decide("REPORT_RECEIVED", due, now, 0, null, false, now, 2, 2)).isEqualTo(ChaseRules.Action.NONE);
        assertThat(ChaseRules.decide("CANCELLED", due, null, 0, null, false, now, 2, 2)).isEqualTo(ChaseRules.Action.NONE);
        // interval between chases
        assertThat(ChaseRules.decide("SEEN", due, null, 1, now.minus(Duration.ofDays(1)), false, now, 2, 2)).isEqualTo(ChaseRules.Action.NONE);
        assertThat(ChaseRules.decide("SEEN", due, null, 1, now.minus(Duration.ofDays(2)), false, now, 2, 2)).isEqualTo(ChaseRules.Action.CHASE);
        // after max chases → escalate once
        assertThat(ChaseRules.decide("SCHEDULED", due, null, 2, now.minus(Duration.ofDays(3)), false, now, 2, 2)).isEqualTo(ChaseRules.Action.ESCALATE);
        assertThat(ChaseRules.decide("SCHEDULED", due, null, 2, now.minus(Duration.ofDays(3)), true, now, 2, 2)).isEqualTo(ChaseRules.Action.NONE);
        assertThat(ChaseRules.overdue("SENT", due, null, now)).isTrue();
        assertThat(ChaseRules.overdue("SENT", due, null, due.minusSeconds(5))).isFalse();
    }

    // ------------------------------------------------------------------ FHIR

    @Test
    void fhirServiceRequestMapping() {
        UUID id = UUID.randomUUID();
        UUID doc = UUID.randomUUID();
        Map<String, Object> sr = FhirMapper.serviceRequest(new FhirMapper.Source(id, "RFL-2026-000001", "SENT", "URGENT", "ORTHOPAEDICS",
                "Orthopaedic consultation", "CITYHOSP", "City General Hospital", "E1004", "Rami Ramp", "Clara Clinician", "Back pain",
                "Surgery?", Map.of("bodyPart", "LOWER_BACK"), Instant.parse("2026-03-01T10:00:00Z"), null, List.of(doc)));
        assertThat(sr).containsEntry("resourceType", "ServiceRequest").containsEntry("id", id.toString()).containsEntry("status", "active")
                .containsEntry("intent", "order").containsEntry("priority", "urgent").containsEntry("authoredOn", "2026-03-01T10:00:00Z");
        assertThat(sr.get("identifier").toString()).contains("RFL-2026-000001");
        assertThat(sr.get("performer").toString()).contains("CITYHOSP");
        assertThat(sr.get("subject").toString()).contains("E1004");
        assertThat(sr.get("supportingInfo").toString()).contains("DocumentReference/" + doc).contains("bodyPart: LOWER_BACK");
        assertThat(sr.get("reasonCode").toString()).contains("Back pain");
        assertThat(FhirMapper.fhirStatus("DRAFT")).isEqualTo("draft");
        assertThat(FhirMapper.fhirStatus("CANCELLED")).isEqualTo("revoked");
        assertThat(FhirMapper.fhirStatus("REPORT_RECEIVED")).isEqualTo("completed");

        Map<String, Object> docRef = Map.of("resourceType", "DocumentReference", "description", "Summary",
                "context", Map.of("related", List.of(Map.of("reference", "ServiceRequest/" + id))),
                "content", List.of(Map.of("attachment", Map.of("contentType", "application/pdf", "data", "JVBERi0=", "title", "Report"))));
        assertThat(FhirMapper.relatedServiceRequest(docRef)).contains(id.toString());
        assertThat(FhirMapper.attachment(docRef)).hasValueSatisfying(a -> {
            assertThat(a.data()).isEqualTo("JVBERi0=");
            assertThat(a.description()).isEqualTo("Summary");
        });
        assertThat(FhirMapper.relatedServiceRequest(Map.of("resourceType", "Patient"))).isEmpty();
    }

    // ------------------------------------------------------------------ scorecards & contracts

    @Test
    void scorecardMaths() {
        ScorecardMaths.Result r = ScorecardMaths.compute(new ScorecardMaths.Input(List.of(3L, 5L, 9L, 4L), 4, 3, List.of(4, 5), 2, 1,
                List.of(new BigDecimal("300"), new BigDecimal("320")), 5, new BigDecimal("280")));
        assertThat(r.medianDaysToAppointment()).isEqualByComparingTo("4.5");
        assertThat(r.reportWithin7DaysPct()).isEqualByComparingTo("75.0");
        assertThat(r.avgReportQuality()).isEqualByComparingTo("4.5");
        assertThat(r.outcomeImprovedPct()).isEqualByComparingTo("50.0");
        assertThat(r.avgInvoice()).isEqualByComparingTo("310.00");
        assertThat(r.timeliness()).isEqualByComparingTo("87.5");   // (100 + 75) / 2
        assertThat(r.reportQuality()).isEqualByComparingTo("90.0");
        assertThat(r.cost()).isEqualByComparingTo("90.3");         // 280 / 310
        // 0.3·87.5 + 0.25·90 + 0.25·50 + 0.2·90.32 = 79.32
        assertThat(r.overall()).isEqualByComparingTo("79.3");
        ScorecardMaths.Result empty = ScorecardMaths.compute(new ScorecardMaths.Input(List.of(), 0, 0, List.of(), 0, 0, List.of(), null, null));
        assertThat(empty.overall()).isNull();
        ScorecardMaths.Result qualityOnly = ScorecardMaths.compute(new ScorecardMaths.Input(List.of(), 0, 0, List.of(3), 0, 0, List.of(), null, null));
        assertThat(qualityOnly.overall()).isEqualByComparingTo("60.0");
        // SLA breach: 2 days late → 80
        assertThat(ScorecardMaths.compute(new ScorecardMaths.Input(List.of(7L), 0, 0, List.of(), 0, 0, List.of(), 5, null)).timeliness())
                .isEqualByComparingTo("80.0");
        // Friday → Monday = 1 working day
        assertThat(ScorecardMaths.workingDays(Instant.parse("2026-03-06T10:00:00Z"), Instant.parse("2026-03-09T10:00:00Z"))).isEqualTo(1);
    }

    @Test
    void contractStatus() {
        ProviderContract c = new ProviderContract();
        c.setStartDate(D.minusDays(100));
        c.setEndDate(D.plusDays(100));
        assertThat(ContractStatus.of(c, D, 60)).isEqualTo("ACTIVE");
        c.setEndDate(D.plusDays(30));
        assertThat(ContractStatus.of(c, D, 60)).isEqualTo("EXPIRING");
        c.setEndDate(D.minusDays(1));
        assertThat(ContractStatus.of(c, D, 60)).isEqualTo("EXPIRED");
        assertThat(ContractStatus.of(null, D, 60)).isEqualTo("NONE");
        assertThat(ContractStatus.contracted("EXPIRING")).isTrue();
        assertThat(ContractStatus.contracted("EXPIRED")).isFalse();
    }

    // ------------------------------------------------------------------ invoice matching

    @Test
    void invoiceMatching() {
        BigDecimal tol = new BigDecimal("5");
        assertThat(InvoiceMatcher.match(new BigDecimal("430"), "GBP", new BigDecimal("420"), "GBP", tol))
                .isEqualTo(new InvoiceMatcher.Match("MATCHED", new BigDecimal("10.00")));
        assertThat(InvoiceMatcher.match(new BigDecimal("441"), "GBP", new BigDecimal("420"), "GBP", tol).status()).isEqualTo("MATCHED");
        assertThat(InvoiceMatcher.match(new BigDecimal("441.01"), "GBP", new BigDecimal("420"), "GBP", tol).status()).isEqualTo("DISPUTED");
        assertThat(InvoiceMatcher.match(new BigDecimal("300"), "GBP", new BigDecimal("420"), "GBP", tol).variance()).isEqualByComparingTo("-120");
        assertThat(InvoiceMatcher.match(new BigDecimal("300"), "EUR", new BigDecimal("420"), "GBP", tol).status()).isEqualTo("DISPUTED");
        assertThat(InvoiceMatcher.match(new BigDecimal("300"), "GBP", null, null, tol)).isEqualTo(new InvoiceMatcher.Match("RECEIVED", null));
    }

    // ------------------------------------------------------------------ medication checks

    @Test
    void allergyAndBatchChecks() {
        List<MedicationChecks.Substance> allergies = List.of(new MedicationChecks.Substance("Ibuprofen", null),
                new MedicationChecks.Substance("Latex", "111088007"));
        assertThat(MedicationChecks.allergyAlerts("Ibuprofen", "329653008", allergies)).hasSize(1).first().asString().contains("Ibuprofen");
        assertThat(MedicationChecks.allergyAlerts("Paracetamol", "322236009", allergies)).isEmpty();
        assertThat(MedicationChecks.allergyAlerts("Gloves", "111088007", allergies)).hasSize(1);
        assertThat(MedicationChecks.allergyAlerts("Chlorhexidine", null, List.of(new MedicationChecks.Substance("Allergy to chlorhexidine", null))))
                .hasSize(1);

        StockBatch b = new StockBatch();
        b.setExpiry(D.plusDays(10));
        b.setQuantity(2);
        assertThat(MedicationChecks.batchProblem(b, D, true)).isNull();
        assertThat(MedicationChecks.batchProblem(null, D, true)).isEqualTo("batch_unknown");
        b.setExpiry(D.minusDays(1));
        assertThat(MedicationChecks.batchProblem(b, D, true)).isEqualTo("batch_expired");
        b.setExpiry(D);
        b.setQuantity(0);
        assertThat(MedicationChecks.batchProblem(b, D, true)).isEqualTo("out_of_stock");
        assertThat(MedicationChecks.batchProblem(b, D, false)).isNull();
        assertThat(new MedicationChecks.AllergyAlertException(List.of("x")).code()).isEqualTo("allergy_alert");
    }

    // ------------------------------------------------------------------ telehealth tokens

    @Test
    void telehealthTokenWindow() {
        Instant start = Instant.parse("2026-03-02T09:00:00Z");
        assertThat(TokenWindow.isOpen(start.minus(Duration.ofMinutes(16)), start, 30)).isFalse();
        assertThat(TokenWindow.isOpen(start.minus(Duration.ofMinutes(15)), start, 30)).isTrue();
        assertThat(TokenWindow.isOpen(start.plus(Duration.ofMinutes(29)), start, 30)).isTrue();
        assertThat(TokenWindow.isOpen(start.plus(Duration.ofMinutes(30)), start, 30)).isFalse();
        String a = TokenWindow.newToken();
        String b = TokenWindow.newToken();
        assertThat(a).isNotEqualTo(b).hasSizeGreaterThanOrEqualTo(43).matches("[A-Za-z0-9_\\-]+");
        assertThat(TokenWindow.hash(a)).hasSize(64).isEqualTo(TokenWindow.hash(a)).isNotEqualTo(TokenWindow.hash(b));
    }

    // ------------------------------------------------------------------ closure guard

    @Test
    void outcomeClosureGuard() {
        TreatmentPlanRepository plans = mock(TreatmentPlanRepository.class);
        TreatmentReferralRepository referrals = mock(TreatmentReferralRepository.class);
        TreatmentOutcomeRepository outcomes = mock(TreatmentOutcomeRepository.class);
        OutcomeClosureGuard guard = new OutcomeClosureGuard(plans, referrals, outcomes);
        OhCase c = mock(OhCase.class);
        UUID id = UUID.randomUUID();
        when(c.getId()).thenReturn(id);
        // no plan, no referral → closing allowed
        guard.check(c, CaseStatus.CLOSED);
        when(plans.existsByCaseId(id)).thenReturn(true);
        assertThatThrownBy(() -> guard.check(c, CaseStatus.CLOSED))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo("outcome_required"));
        // other transitions are not affected
        guard.check(c, CaseStatus.MONITORING);
        when(outcomes.existsByCaseIdAndMilestone(eq(id), any())).thenReturn(true);
        guard.check(c, CaseStatus.CLOSED);
    }
}
