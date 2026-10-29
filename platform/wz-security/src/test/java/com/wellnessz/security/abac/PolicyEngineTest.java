package com.wellnessz.security.abac;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

import com.wellnessz.common.model.DataClassification;
import com.wellnessz.common.model.DisclosureLevel;
import com.wellnessz.security.breakglass.BreakGlassService;
import com.wellnessz.security.identity.CurrentUser;

class PolicyEngineTest {

    private final DefaultPolicies p = new DefaultPolicies();
    private final ObjectProvider<BreakGlassService> noBreakGlass = new StaticListableBeanFactory().getBeanProvider(BreakGlassService.class);
    private final PolicyEngine engine = new PolicyEngine(List.of(p.restrictedClassPolicy(noBreakGlass), p.clinicianPolicy(),
            p.selfAccessPolicy(), p.statusOnlyPolicy(), p.breakGlassPolicy(noBreakGlass)));

    /** Break-glass grant held by user "u" for employee E1 only. */
    static final class FixedGrant extends BreakGlassService {
        FixedGrant() {
            super(null, null, null, null);
        }

        @Override
        public boolean hasActiveGrant(String username, String employeeId) {
            return "u".equals(username) && "E1".equals(employeeId);
        }
    }

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
    void breakGlassOpensRestrictedClassForTheGrantedEmployeeOnly() {
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        beans.addBean("breakGlass", new FixedGrant());
        ObjectProvider<BreakGlassService> grant = beans.getBeanProvider(BreakGlassService.class);
        PolicyEngine withGrant = new PolicyEngine(List.of(p.restrictedClassPolicy(grant), p.clinicianPolicy(), p.selfAccessPolicy(),
                p.statusOnlyPolicy(), p.breakGlassPolicy(grant)));
        var clinician = user("C1", null, "OH_CLINICIAN");
        assertThat(withGrant.decide(clinician, AccessPolicy.Action.READ, caseOf("E1", "/AIRLINE", DataClassification.RESTRICTED_CLINICAL))
                .disclosure()).isEqualTo(DisclosureLevel.RESTRICTED_CLINICAL);
        assertThat(withGrant.decide(clinician, AccessPolicy.Action.READ, caseOf("E2", "/AIRLINE", DataClassification.RESTRICTED_CLINICAL))
                .permitted()).isFalse();
        // HR never gains clinical access through break-glass
        assertThat(withGrant.decide(user("H1", "/AIRLINE", "HR"), AccessPolicy.Action.READ,
                caseOf("E1", "/AIRLINE", DataClassification.RESTRICTED_CLINICAL)).disclosure()).isEqualTo(DisclosureLevel.STATUS_ONLY);
    }

    @Test
    void defaultDeny() {
        assertThat(engine.decide(user("X", null, "EMPLOYEE"), AccessPolicy.Action.READ,
                caseOf("E1", "/AIRLINE", DataClassification.CLINICAL)).permitted()).isFalse();
    }
}
