package com.wellnessz.e33.spi;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import com.wellnessz.e33.service.EmployeeDeviceService;
import com.wellnessz.security.consent.Consent;
import com.wellnessz.security.consent.ConsentPurpose;
import com.wellnessz.security.consent.ConsentWithdrawalListener;

/** A {@code WEARABLE_DATA} consent withdrawn anywhere (consent centre or /me/device-consents) revokes its device scopes. */
@Component
public class WearableConsentRevocationListener implements ConsentWithdrawalListener {

    private final ObjectProvider<EmployeeDeviceService> service;

    public WearableConsentRevocationListener(ObjectProvider<EmployeeDeviceService> service) {
        this.service = service;
    }

    @Override
    public void onWithdrawn(Consent consent) {
        if (consent.getPurpose() == ConsentPurpose.WEARABLE_DATA) {
            service.getObject().consentWithdrawn(consent.getId());
        }
    }
}
