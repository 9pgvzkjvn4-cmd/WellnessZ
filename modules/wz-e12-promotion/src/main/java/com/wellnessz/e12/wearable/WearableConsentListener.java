package com.wellnessz.e12.wearable;

import org.springframework.stereotype.Component;

import com.wellnessz.security.consent.Consent;
import com.wellnessz.security.consent.ConsentPurpose;
import com.wellnessz.security.consent.ConsentWithdrawalListener;

/** Withdrawing a WEARABLE_DATA consent withdraws every enrolment that relied on it and purges the stored values. */
@Component
public class WearableConsentListener implements ConsentWithdrawalListener {

    private final WearableService wearables;

    public WearableConsentListener(WearableService wearables) {
        this.wearables = wearables;
    }

    @Override
    public void onWithdrawn(Consent consent) {
        if (consent.getPurpose() == ConsentPurpose.WEARABLE_DATA) {
            wearables.consentWithdrawn(consent.getId());
        }
    }
}
