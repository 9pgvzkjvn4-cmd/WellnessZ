package com.wellnessz.e31.association;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.Test;

/** Association priority, thresholds (no auto-file below 0.95), ambiguity, conflicts, restricted categories. */
class AssociationEngineTest {

    static final BigDecimal AUTO = new BigDecimal("0.95");
    static final BigDecimal SUGGEST = new BigDecimal("0.60");

    static AssociationEngine.Candidate c(String emp, String method, String conf) {
        return new AssociationEngine.Candidate(emp, method, new BigDecimal(conf), emp + " name");
    }

    static AssociationEngine.Decision decide(boolean conflict, boolean restricted, AssociationEngine.Candidate... cs) {
        return AssociationEngine.decide(List.of(cs), conflict, restricted, AUTO, SUGGEST);
    }

    @Test
    void firstMethodAtThresholdWins() {
        AssociationEngine.Decision d = decide(false, false, c("E1", "VENDOR_ID_MATCH", "0.97"), c("E1", "SESSION", "1.00"));
        assertThat(d.associated()).isTrue();
        assertThat(d.method()).isEqualTo("SESSION");
        assertThat(d.employeeId()).isEqualTo("E1");
        assertThat(decide(false, false, c("E1", "WORKLIST", "1.00"), c("E1", "BADGE", "0.98")).method()).isEqualTo("WORKLIST");
        assertThat(decide(false, false, c("E2", "BADGE", "0.98")).method()).isEqualTo("BADGE");
        assertThat(decide(false, false, c("E2", "VENDOR_ID_MATCH", "0.95")).associated()).isTrue();
    }

    @Test
    void nothingBelowTheThresholdIsEverAutoFiled() {
        for (String conf : new String[] {"0.94", "0.92", "0.90", "0.85", "0.70", "0.10"}) {
            AssociationEngine.Decision d = decide(false, false, c("E1", "VENDOR_ID_MATCH", conf));
            assertThat(d.associated()).as(conf).isFalse();
            assertThat(d.status()).isEqualTo("UNMATCHED");
        }
        AssociationEngine.Decision d = decide(false, false, c("E1", "VENDOR_ID_MATCH", "0.92"), c("E3", "VENDOR_ID_MATCH", "0.50"));
        assertThat(d.reason()).isEqualTo("below_threshold");
        assertThat(d.suggestions()).extracting(AssociationEngine.Candidate::employeeId).containsExactly("E1");
        assertThat(decide(false, false).reason()).isEqualTo("no_identity");
    }

    @Test
    void ambiguousCandidatesAndSessionConflictsGoToTheQueue() {
        AssociationEngine.Decision amb = decide(false, false, c("E1", "BADGE", "0.98"), c("E2", "VENDOR_ID_MATCH", "0.97"));
        assertThat(amb.associated()).isFalse();
        assertThat(amb.reason()).isEqualTo("ambiguous");
        assertThat(amb.suggestions()).hasSize(2);
        assertThat(decide(false, false, c("E1", "SESSION", "1.00"), c("E2", "VENDOR_ID_MATCH", "0.92")).associated()).isTrue();
        AssociationEngine.Decision conflict = decide(true, false, c("E1", "SESSION", "0.80"), c("E2", "VENDOR_ID_MATCH", "0.97"));
        assertThat(conflict.associated()).isFalse();
        assertThat(conflict.reason()).isEqualTo("association_conflict");
    }

    @Test
    void restrictedCategoriesAcceptOnlyWorklistOrSession() {
        assertThat(decide(false, true, c("E1", "BADGE", "0.98")).associated()).isFalse();
        assertThat(decide(false, true, c("E1", "BADGE", "0.98")).reason()).isEqualTo("restricted_method");
        assertThat(decide(false, true, c("E1", "VENDOR_ID_MATCH", "0.97")).suggestions()).isEmpty();
        assertThat(decide(false, true, c("E1", "SESSION", "1.00")).associated()).isTrue();
        assertThat(decide(false, true, c("E1", "MANUAL_RESOLUTION", "1.00")).associated()).isTrue();
    }

    @Test
    void areaMeasurementsHaveNoPerson() {
        AssociationEngine.Decision d = decide(false, false, new AssociationEngine.Candidate(null, "AREA_MEASUREMENT", new BigDecimal("1.00"), null));
        assertThat(d.associated()).isTrue();
        assertThat(d.employeeId()).isNull();
    }

    @Test
    void vendorIdNormalisationAndJaroWinkler() {
        List<String> prefixes = List.of("EMP", "WZ");
        assertThat(AssociationEngine.normaliseId("e01001", prefixes)).isEqualTo("E1001");
        assertThat(AssociationEngine.normaliseId(" EMP-0001001 ", prefixes)).isEqualTo("1001");
        assertThat(AssociationEngine.normaliseId("wz_E1001", prefixes)).isEqualTo("E1001");
        assertThat(AssociationEngine.jaroWinkler("MARTHA", "MARHTA")).isCloseTo(0.961, within(0.001));
        assertThat(AssociationEngine.jaroWinkler("DIXON", "DICKSONX")).isCloseTo(0.813, within(0.001));
        assertThat(AssociationEngine.jaroWinkler("Erin Employe", "Erin Employee")).isGreaterThan(0.92);
        assertThat(AssociationEngine.jaroWinkler("Paul Pilot", "Erin Employee")).isLessThan(0.7);
        assertThat(AssociationEngine.jaroWinkler("", "x")).isZero();
    }
}
