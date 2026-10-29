package com.wellnessz.casekernel.support;

import java.util.Optional;
import java.util.UUID;

/**
 * SPI implemented by e-referral management (E04-F02) so that other modules can raise a referral to a contracted partner
 * provider – e.g. an independent medical examination (IME) requested by claims (E08-BL-011). Status changes come back
 * through {@link ReferralStatusListener}.
 */
public interface ExternalReferralGateway {

    /**
     * @param serviceCode  E04 service catalogue code, e.g. {@code IME}, {@code ORTHOPAEDICS}, {@code PHYSIOTHERAPY}
     * @param providerCode partner organisation code, or {@code null} to let OH choose
     * @param urgency      ROUTINE | URGENT
     * @param sourceType   originating object, e.g. {@code CLAIM_IME}
     */
    record ReferralRequest(UUID caseId, String employeeId, String serviceCode, String providerCode, String urgency, String reason,
                           String sourceType, String sourceRef) {
    }

    record ReferralRef(UUID referralId, String referralRef, String status) {
    }

    /** Creates a DRAFT referral for OH to complete (data set, consent) and send; empty when the service is unknown. */
    Optional<ReferralRef> requestReferral(String actor, ReferralRequest request);
}
