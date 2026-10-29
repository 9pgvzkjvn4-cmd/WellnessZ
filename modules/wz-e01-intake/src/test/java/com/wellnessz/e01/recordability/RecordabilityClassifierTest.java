package com.wellnessz.e01.recordability;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.Test;

import com.wellnessz.casekernel.model.CaseEnums.Recordability;

class RecordabilityClassifierTest {

    @Test
    void severityOrderFollowsOshaLogic() {
        assertThat(RecordabilityClassifier.classify(Map.of("fatality", true, "daysAwayFromWork", 3))).isEqualTo(Recordability.FATALITY);
        assertThat(RecordabilityClassifier.classify(Map.of("daysAwayFromWork", 1, "restrictedWorkOrTransfer", true))).isEqualTo(Recordability.LOST_TIME);
        assertThat(RecordabilityClassifier.classify(Map.of("restrictedWorkOrTransfer", "true"))).isEqualTo(Recordability.RESTRICTED_DUTY);
        assertThat(RecordabilityClassifier.classify(Map.of("lossOfConsciousness", true))).isEqualTo(Recordability.MEDICAL_TREATMENT);
        assertThat(RecordabilityClassifier.classify(Map.of())).isEqualTo(Recordability.FIRST_AID);
    }
}
