package com.wellnessz.security.consent;

/**
 * Called inside the withdrawal transaction so modules can stop processing that relied on the consent
 * (e.g. the document service revokes partner shares). Implementations must be idempotent.
 */
public interface ConsentWithdrawalListener {

    void onWithdrawn(Consent consent);
}
