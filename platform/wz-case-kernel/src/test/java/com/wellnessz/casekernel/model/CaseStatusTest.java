package com.wellnessz.casekernel.model;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class CaseStatusTest {

    @Test
    void followsCasePathway() {
        assertThat(CaseStatus.INTAKE.canTransitionTo(CaseStatus.TRIAGE)).isTrue();
        assertThat(CaseStatus.INTAKE.canTransitionTo(CaseStatus.CLAIMS)).isFalse();
        assertThat(CaseStatus.CLOSED.canTransitionTo(CaseStatus.TRIAGE)).isTrue(); // re-open
    }

    @Test
    void restrictedCategoriesAreClassifiedRestricted() {
        assertThat(CaseCategory.PSYCHOLOGICAL.classification().name()).isEqualTo("RESTRICTED_CLINICAL");
        assertThat(CaseCategory.SUBSTANCE.classification().name()).isEqualTo("RESTRICTED_CLINICAL");
    }
}
