package com.wellnessz.e05.decision;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.wellnessz.casekernel.FitnessFeed;
import com.wellnessz.casekernel.model.FitnessStatus;

class DecisionRulesTest {

    static final LocalDate FROM = LocalDate.of(2026, 11, 1);

    @Test
    void validityDefaultsFromStandardAndOver40Rule() {
        // CAA class 1: 12 months, 6 months over 40
        assertThat(DecisionRules.defaultValidUntil("FIT", FROM, 12, 6, null)).isEqualTo(LocalDate.of(2027, 10, 31));
        assertThat(DecisionRules.defaultValidUntil("FIT", FROM, 12, 6, 39)).isEqualTo(LocalDate.of(2027, 10, 31));
        assertThat(DecisionRules.defaultValidUntil("FIT_WITH_LIMITATIONS", FROM, 12, 6, 41)).isEqualTo(LocalDate.of(2027, 4, 30));
        // no over-40 rule on the standard → normal validity
        assertThat(DecisionRules.defaultValidUntil("FIT", FROM, 60, null, 55)).isEqualTo(LocalDate.of(2031, 10, 31));
        // unfit / pending outcomes carry no default validity
        assertThat(DecisionRules.defaultValidUntil("TEMPORARILY_UNFIT", FROM, 12, 6, null)).isNull();
        assertThat(DecisionRules.defaultValidUntil("PERMANENTLY_UNFIT", FROM, 12, 6, null)).isNull();
        assertThat(DecisionRules.defaultValidUntil("PENDING", FROM, 12, 6, null)).isNull();
    }

    @Test
    void ageIsUnknownWithoutDateOfBirth() {
        assertThat(DecisionRules.age(null, FROM)).isNull();
        assertThat(DecisionRules.age(LocalDate.of(1980, 11, 2), FROM)).isEqualTo(45);
    }

    @Test
    void outcomeToKernelStatusMapping() {
        assertThat(DecisionRules.kernelStatus("FIT", true)).isEqualTo(FitnessStatus.FIT);
        assertThat(DecisionRules.kernelStatus("FIT_WITH_LIMITATIONS", true)).isEqualTo(FitnessStatus.FIT_WITH_RESTRICTIONS);
        assertThat(DecisionRules.kernelStatus("TEMPORARILY_UNFIT", true)).isEqualTo(FitnessStatus.TEMPORARILY_UNFIT);
        assertThat(DecisionRules.kernelStatus("PERMANENTLY_UNFIT", false)).isEqualTo(FitnessStatus.UNDER_REVIEW);
        assertThat(DecisionRules.kernelStatus("PERMANENTLY_UNFIT", true)).isEqualTo(FitnessStatus.PERMANENTLY_UNFIT);
        assertThat(DecisionRules.kernelStatus("PENDING", true)).isEqualTo(FitnessStatus.UNDER_REVIEW);
    }

    @Test
    void rosterStatusMappingAndOperationalCodesOnly() {
        assertThat(FitnessFeed.rosterStatus(FitnessStatus.FIT)).isEqualTo("FIT");
        assertThat(FitnessFeed.rosterStatus(FitnessStatus.FIT_WITH_RESTRICTIONS)).isEqualTo("LIMITED");
        assertThat(FitnessFeed.rosterStatus(FitnessStatus.TEMPORARILY_UNFIT)).isEqualTo("UNFIT");
        assertThat(FitnessFeed.rosterStatus(FitnessStatus.PERMANENTLY_UNFIT)).isEqualTo("UNFIT");
        assertThat(FitnessFeed.rosterStatus(FitnessStatus.UNFIT_PENDING)).isEqualTo("PENDING");
        assertThat(FitnessFeed.rosterStatus(FitnessStatus.UNDER_REVIEW)).isEqualTo("PENDING");
        assertThat(FitnessFeed.rosterStatus(FitnessStatus.NOT_ASSESSED)).isEqualTo("PENDING");
        assertThat(DecisionRules.operational(List.of("VDL", "OML", "HAL", "TML", "OSL"))).containsExactly("OML", "TML", "OSL");
        assertThat(LimitationCodes.labels()).hasSize(15).containsKeys("TML", "VDL", "VML", "VNL", "CCL", "VCL", "OML", "OCL", "OSL", "OPL",
                "ORL", "SSL", "HAL", "SIC", "RXO");
        assertThat(LimitationCodes.OPERATIONAL).containsExactlyInAnyOrder("OML", "OCL", "OSL", "OPL", "ORL", "TML");
    }

    @Test
    void kernelUntilCarriesTheLimitationOrUnfitPeriod() {
        LocalDate until = FROM.plusDays(10);
        assertThat(DecisionRules.kernelUntil("FIT_WITH_LIMITATIONS", until, null)).isEqualTo(until);
        assertThat(DecisionRules.kernelUntil("TEMPORARILY_UNFIT", null, until)).isEqualTo(until);
        assertThat(DecisionRules.kernelUntil("FIT", until, null)).isNull();
    }

    @Test
    void restrictionExpiryStatus() {
        LocalDate today = LocalDate.of(2026, 11, 10);
        assertThat(DecisionRules.restrictionStatus("ACTIVE", null, today)).isEqualTo("ACTIVE");
        assertThat(DecisionRules.restrictionStatus("ACTIVE", today.plusDays(6), today)).isEqualTo("ACTIVE");
        assertThat(DecisionRules.restrictionStatus("ACTIVE", today.plusDays(5), today)).isEqualTo("EXPIRING");
        assertThat(DecisionRules.restrictionStatus("ACTIVE", today, today)).isEqualTo("EXPIRING");
        assertThat(DecisionRules.restrictionStatus("ACTIVE", today.minusDays(1), today)).isEqualTo("LAPSED");
        assertThat(DecisionRules.restrictionStatus("LAPSED", today.minusDays(3), today)).isEqualTo("LAPSED");
        assertThat(DecisionRules.restrictionStatus("CLOSED", today.plusDays(1), today)).isEqualTo("CLOSED");
    }
}
