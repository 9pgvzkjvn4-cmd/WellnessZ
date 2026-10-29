package com.wellnessz.casekernel.support;

import java.util.Optional;
import java.util.UUID;

/**
 * SPI implemented by claims (E08) so that treatment coordination (E04 pre-authorisation) and the case header can show the
 * claim context of a case without depending on the claims module. Status and payer only – no reserves or costs.
 */
public interface ClaimContextProvider {

    /**
     * @param payerCode partner organisation code of the insurer or TPA handling the claim
     * @param lostTime  the claim is a lost-time claim
     */
    record ClaimContext(UUID claimId, String claimRef, String claimType, String status, String payerCode, boolean lostTime,
                        boolean litigationHold) {
    }

    Optional<ClaimContext> forCase(UUID caseId);
}
