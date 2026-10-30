package com.wellnessz.casekernel.support;

import java.util.Optional;
import java.util.UUID;

/**
 * SPI implemented by the mental-health & peer-support module (E09) so that other modules can refer an employee into the
 * confidential support pathway without depending on it – e.g. a verified positive or refusal in the substance-testing
 * programme (E10-HLR-04) or a critical incident (E11 exposure). The referral reason is a code, never clinical detail.
 */
public interface SupportProgrammeReferral {

    /**
     * @param reason     SUBSTANCE_POSITIVE | SUBSTANCE_REFUSAL | CRITICAL_INCIDENT | EXPOSURE | OTHER
     * @param sourceType originating object, e.g. {@code DA_TEST}
     * @return the confidential support case id, empty when the programme module is not deployed
     */
    Optional<UUID> refer(String actor, String employeeId, UUID relatedCaseId, String reason, String sourceType, String sourceRef);
}
