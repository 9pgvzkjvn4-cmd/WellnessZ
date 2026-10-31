package com.wellnessz.e32.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.wellnessz.casekernel.device.DeviceCodes.Category;
import com.wellnessz.casekernel.device.DeviceCodes.Family;
import com.wellnessz.e32.support.Docs;
import com.wellnessz.e32.support.FieldIssue;

class WizardRulesTest {

    static final Instant NOW = Instant.parse("2026-10-31T09:00:00Z");

    static Configuration configured() {
        Configuration c = ConfigurationStore.draft("CFG-000101", 1, "DEV-000101", "MD-027", "deviceadmin1");
        for (int n = 1; n <= 6; n++) {
            c.setStep(n, "VALID", Docs.of("x", n), null, "deviceadmin1", NOW);
        }
        c.setCurrentStep(7);
        c.tests().add(Docs.of("mode", "SIMULATOR", "status", "PASSED"));
        c.data().put("nurseReview", Docs.of("outcome", "ACCEPTED"));
        return c;
    }

    static List<String> codes(List<FieldIssue> issues) {
        return issues.stream().map(FieldIssue::code).toList();
    }

    @Test
    void fourEyesRefusesTheSubmitterAndAnyoneWhoChangedSteps4To6() {
        Configuration c = configured();
        c.setSubmitted("deviceadmin1", NOW);
        c.addStepEditor("inttester1");
        assertThat(WizardRules.fourEyesViolation(c, "deviceadmin1")).isTrue();
        assertThat(WizardRules.fourEyesViolation(c, "inttester1")).isTrue();
        assertThat(WizardRules.fourEyesViolation(c, "deviceapprover1")).isFalse();
    }

    @Test
    void changingAnEarlierStepInvalidatesTheLaterSteps() {
        Configuration c = configured();
        WizardRules.invalidateFrom(c, 5, false, "deviceadmin1", NOW);
        assertThat(c.stepStatus(4)).isEqualTo("VALID");
        assertThat(c.stepStatus(5)).isEqualTo("INVALID");
        assertThat(c.stepStatus(6)).isEqualTo("INVALID");
        assertThat(c.stepData(5)).containsEntry("x", 5);
        assertThat(c.tests()).isEmpty();
        assertThat(c.data()).doesNotContainKey("nurseReview");
        assertThat(c.currentStep()).isEqualTo(5);

        Configuration d = configured();
        WizardRules.invalidateFrom(d, 3, true, "deviceadmin1", NOW);
        assertThat(d.stepStatus(3)).isEqualTo("NOT_STARTED");
        assertThat(d.stepData(4)).isEmpty();
        assertThat(d.stepStatus(2)).isEqualTo("VALID");
    }

    @Test
    void prerequisitesDependOnOptionFamilyAndConfidence() {
        List<Map<String, Object>> poc = WizardRules.prerequisites(List.of("NETWORK_ZONE", "ENDPOINT_IDENTITY"), Family.POC, false, "P5");
        assertThat(poc).extracting(m -> m.get("code")).contains("NETWORK_ZONE", "ENDPOINT_IDENTITY", "CONSUMABLES", "INTERFACE_SPEC_CONFIRMED",
                "VENDOR_POSTURE", "CALIBRATION_EVIDENCE");
        assertThat(poc).filteredOn(m -> "ENDPOINT_IDENTITY".equals(m.get("code"))).extracting(m -> m.get("kind")).containsExactly("AUTO");
        List<Map<String, Object>> wear = WizardRules.prerequisites(List.of(), Family.WEARABLE, true, "P8");
        assertThat(wear).extracting(m -> m.get("code")).doesNotContain("INTERFACE_SPEC_CONFIRMED", "CONSUMABLES");
        assertThat(wear).filteredOn(m -> "CALIBRATION_EVIDENCE".equals(m.get("code"))).extracting(m -> m.get("status")).containsExactly("NOT_APPLICABLE");
        assertThat(codes(WizardRules.prerequisiteIssues(wear))).containsExactly("prerequisite_open");
    }

    @Test
    void associationMethodsMustMatchTheDriverCapabilities() {
        Map<String, Object> noWorklist = Docs.of("capabilities", Docs.of("worklistPush", "NONE"), "parse", Docs.of("identity", Docs.of()));
        assertThat(codes(WizardRules.associationIssues(List.of("WORKLIST", "VENDOR_ID_MATCH"), noWorklist, Set.of(Category.VITALS))))
                .contains("worklist_not_supported", "vendor_id_not_supported");
        assertThat(codes(WizardRules.associationIssues(List.of("BADGE"), noWorklist, Set.of(Category.BREATH_ALCOHOL)))).contains("restricted_association");
        assertThat(WizardRules.associationIssues(List.of("WORKLIST", "SESSION"), noWorklist, Set.of(Category.BREATH_ALCOHOL))).isEmpty();
        assertThat(codes(WizardRules.associationIssues(List.of(), noWorklist, Set.of()))).contains("required");
    }

    @Test
    void bindingsUsePackageKeysAndVerificationIsOnlyTightened() {
        Map<String, Object> manifest = Docs.of("mappings", List.of(Docs.of("key", "systolic"), Docs.of("key", "diastolic")),
                "bindings", List.of(Docs.of("formCode", "E03.DEVICE_RESULT.VITALS", "category", "VITALS", "target", "E03.RESULT",
                        "fields", Docs.of("systolic", "measurements.systolic"), "verifyBeforePost", true)));
        List<Object> ok = WizardRules.completeBindings(List.of(Docs.of("formCode", "E03.DEVICE_RESULT.VITALS",
                "fields", Docs.of("systolic", "measurements.systolic", "diastolic", "measurements.diastolic"))), manifest);
        assertThat(WizardRules.bindingIssues(ok, manifest, "P4")).isEmpty();
        assertThat(Docs.map(ok.get(0))).containsEntry("target", "E03.RESULT").containsEntry("formVersion", "1");
        List<Object> bad = List.of(Docs.of("formCode", "E03.DEVICE_RESULT.VITALS", "target", "E12.SCREENING", "category", "VITALS",
                "fields", Docs.of("weightKg", "measurements.weight"), "verifyBeforePost", false));
        assertThat(codes(WizardRules.bindingIssues(bad, manifest, "P4"))).contains("binding_field_unknown", "cannot_relax", "target_mismatch");
    }

    @Test
    void commonItemsCanOnlyBeTightenedAndGatesKeepVerificationLast() {
        Map<String, Object> manifest = Docs.of("qualityGates", List.of("CALIBRATION", "PLAUSIBILITY", "VERIFY_BEFORE_POST"),
                "bindings", List.of(Docs.of("verifyBeforePost", true)));
        Map<String, Object> common = Docs.of("verifyBeforePost", false, "qualityGateAdditions", List.of("CLOCK_SKEW", "WISHFUL"),
                "calibrationSchedule", List.of(Docs.of("checkType", "SPIRO_SYRINGE_3L", "intervalHours", 48, "lockout", false)));
        assertThat(codes(WizardRules.commonIssues(common, manifest, "P2", Map.of("SPIRO_SYRINGE_3L", 24))))
                .containsExactlyInAnyOrder("cannot_relax", "unknown_gate", "cannot_relax", "cannot_relax");
        assertThat(WizardRules.gates(manifest, List.of("CLOCK_SKEW"))).containsExactly("CALIBRATION", "PLAUSIBILITY", "CLOCK_SKEW", "VERIFY_BEFORE_POST");
    }
}
