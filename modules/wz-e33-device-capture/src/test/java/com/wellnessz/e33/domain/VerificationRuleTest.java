package com.wellnessz.e33.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.wellnessz.casekernel.device.DeviceCodes.GateOutcome;
import com.wellnessz.casekernel.device.DeviceObservation.QualityFlag;
import com.wellnessz.common.error.ApiException;
import com.wellnessz.security.identity.CurrentUser;

class VerificationRuleTest {

    static CurrentUser user(String name, String... roles) {
        return new CurrentUser("sub-" + name, name, null, name, "/AIRLINE", Set.of(roles));
    }

    static final Set<String> CLINICAL = VerificationRule.roles("OH_CLINICIAN,OH_PHYSICIAN,AME,CMO,NURSING_HEAD");

    @Test
    void clinicalFormsAreVerifiedByTheFormsRoles() {
        assertThat(VerificationRule.mayVerify(user("nurse1", "OH_CLINICIAN"), CLINICAL, false, false, false)).isTrue();
        assertThat(VerificationRule.mayVerify(user("deviceadmin1", "DEVICE_ADMIN"), CLINICAL, false, false, false)).isFalse();
        assertThatThrownBy(() -> VerificationRule.requireVerifier(user("hr1", "HR"), CLINICAL, false, false, false))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void restrictedReadingsOnlyCollectorInOwnSessionOrMro() {
        Set<String> roles = VerificationRule.roles("COLLECTOR,MRO");
        assertThat(VerificationRule.mayVerify(user("collector1", "COLLECTOR"), roles, true, true, false)).isTrue();
        assertThat(VerificationRule.mayVerify(user("collector2", "COLLECTOR"), roles, true, false, false)).isFalse();
        assertThat(VerificationRule.mayVerify(user("mro1", "MRO"), roles, true, false, true)).isTrue();
        assertThat(VerificationRule.mayVerify(user("nurse1", "OH_CLINICIAN"), roles, true, false, false)).isFalse();
        assertThat(VerificationRule.maySeeRestricted(user("nurse1", "OH_CLINICIAN"), false, false)).isFalse();
        assertThat(VerificationRule.maySeeRestricted(user("collector1", "COLLECTOR"), true, false)).isTrue();
    }

    @Test
    void blockPreventsPostingAndWarningsNeedAcknowledgement() {
        List<QualityFlag> blocked = List.of(new QualityFlag("CALIBRATION", GateOutcome.BLOCK, "device_out_of_calibration", "Expired"));
        assertThatThrownBy(() -> VerificationRule.requirePostable(blocked, List.of("device_out_of_calibration")))
                .extracting(e -> ((ApiException) e).code()).isEqualTo("observation_blocked");
        List<QualityFlag> warn = List.of(new QualityFlag("ATS_ERS_GRADE", GateOutcome.WARN, "grade_below_minimum", "Grade D"),
                new QualityFlag("PLAUSIBILITY", GateOutcome.PASS, "ok", null));
        assertThatThrownBy(() -> VerificationRule.requirePostable(warn, List.of()))
                .extracting(e -> ((ApiException) e).code()).isEqualTo("flags_not_acknowledged");
        assertThatCode(() -> VerificationRule.requirePostable(warn, List.of("grade_below_minimum"))).doesNotThrowAnyException();
        assertThatCode(() -> VerificationRule.requirePostable(warn, List.of("ATS_ERS_GRADE"))).doesNotThrowAnyException();
        assertThat(VerificationRule.clean(warn)).isFalse();
        assertThat(VerificationRule.clean(List.of(new QualityFlag("PLAUSIBILITY", GateOutcome.INFO, "x", null)))).isTrue();
    }

    @Test
    void signOffIsForPhysicians() {
        Set<String> sign = VerificationRule.roles("OH_PHYSICIAN,AME,CMO");
        assertThatCode(() -> VerificationRule.requireSignOff(user("physician1", "OH_PHYSICIAN"), sign)).doesNotThrowAnyException();
        assertThatThrownBy(() -> VerificationRule.requireSignOff(user("nurse1", "OH_CLINICIAN"), sign)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> VerificationRule.requireSignOff(user("physician1", "OH_PHYSICIAN"), Set.of()))
                .extracting(e -> ((ApiException) e).code()).isEqualTo("sign_off_not_applicable");
    }
}
