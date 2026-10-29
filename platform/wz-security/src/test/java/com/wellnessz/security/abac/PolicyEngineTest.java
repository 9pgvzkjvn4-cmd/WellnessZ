package com.wellnessz.security.abac;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.wellnessz.common.model.DataClassification;
import com.wellnessz.common.model.DisclosureLevel;
import com.wellnessz.security.identity.CurrentUser;

class PolicyEngineTest {

    private final DefaultPolicies p = new DefaultPolicies();
    private final PolicyEngine engine = new PolicyEngine(List.of(p.restrictedClassPolicy(), p.clinicianPolicy(),
            p.selfAccessPolicy(), p.statusOnlyPolicy()));

    static CurrentUser user(String emp, String scope, String... roles) {
        return new CurrentUser("sub", "u", emp, "U", scope, Set.of(roles));
    }

    static ResourceAttributes caseOf(String owner, String path, DataClassification c) {
        return new ResourceAttributes("CASE", owner, path, c);
    }

    @Test
    void managerGetsStatusOnlyWithinScopeAndNothingOutside() {
        var mgr = user("M1", "/AIRLINE/OPS", "MANAGER");
        assertThat(engine.decide(mgr, AccessPolicy.Action.READ, caseOf("E1", "/AIRLINE/OPS/CABIN", DataClassification.CLINICAL)).disclosure())
                .isEqualTo(DisclosureLevel.STATUS_ONLY);
        assertThat(engine.decide(mgr, AccessPolicy.Action.READ, caseOf("E1", "/AIRLINE/OPSX", DataClassification.CLINICAL)).permitted())
                .isFalse();
        assertThat(engine.decide(mgr, AccessPolicy.Action.UPDATE, caseOf("E1", "/AIRLINE/OPS/CABIN", DataClassification.CLINICAL)).permitted())
                .isFalse();
    }

    @Test
    void restrictedClassOnlyForPsychClinicianOrSubject() {
        var restricted = caseOf("E1", "/AIRLINE/OPS", DataClassification.RESTRICTED_CLINICAL);
        assertThat(engine.decide(user("C1", null, "OH_CLINICIAN"), AccessPolicy.Action.READ, restricted).permitted()).isFalse();
        assertThat(engine.decide(user("P1", null, "PSYCH_CLINICIAN"), AccessPolicy.Action.READ, restricted).disclosure())
                .isEqualTo(DisclosureLevel.RESTRICTED_CLINICAL);
        assertThat(engine.decide(user("E1", null, "EMPLOYEE"), AccessPolicy.Action.READ, restricted).disclosure())
                .isEqualTo(DisclosureLevel.SELF);
        assertThat(engine.decide(user("M1", "/AIRLINE", "MANAGER"), AccessPolicy.Action.READ, restricted).disclosure())
                .isEqualTo(DisclosureLevel.STATUS_ONLY);
    }

    @Test
    void defaultDeny() {
        assertThat(engine.decide(user("X", null, "EMPLOYEE"), AccessPolicy.Action.READ,
                caseOf("E1", "/AIRLINE", DataClassification.CLINICAL)).permitted()).isFalse();
    }
}
