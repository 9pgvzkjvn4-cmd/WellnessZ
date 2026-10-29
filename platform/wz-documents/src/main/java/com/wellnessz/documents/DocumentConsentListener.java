package com.wellnessz.documents;

import org.springframework.stereotype.Component;

import com.wellnessz.security.consent.Consent;
import com.wellnessz.security.consent.ConsentWithdrawalListener;

/** Consent withdrawal immediately revokes partner shares made under that consent (E03-BL-022). */
@Component
public class DocumentConsentListener implements ConsentWithdrawalListener {

    private final DocumentService documents;

    public DocumentConsentListener(DocumentService documents) {
        this.documents = documents;
    }

    @Override
    public void onWithdrawn(Consent consent) {
        documents.revokeByConsent(consent.getId());
    }
}
