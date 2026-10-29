package com.wellnessz.casekernel.support;

import java.util.UUID;

/** SPI notified by E04 (inside its transaction) when a referral raised through {@link ExternalReferralGateway} changes status. */
public interface ReferralStatusListener {

    enum Status { DRAFT, SENT, ACCEPTED, DECLINED, SCHEDULED, SEEN, REPORT_RECEIVED, CLOSED, CANCELLED }

    boolean supports(String sourceType);

    /** @param reportDocumentId the provider's report (wz-documents) once REPORT_RECEIVED, else {@code null} */
    void referralStatusChanged(UUID referralId, String sourceType, String sourceRef, Status status, UUID reportDocumentId);
}
