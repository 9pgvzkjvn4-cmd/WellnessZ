package com.wellnessz.analytics;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Base64;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Keyed pseudonymisation (HMAC-SHA256) of employee identifiers for analytics extracts and predictive-model
 * training sets. The key is held only by the platform, so the warehouse cannot re-identify individuals.
 */
@Component
public class Pseudonymizer {

    private final byte[] key;

    public Pseudonymizer(@Value("${WZ_PSEUDONYM_KEY:}") String base64Key) {
        if (base64Key == null || base64Key.isBlank()) {
            this.key = null;
        } else {
            this.key = Base64.getDecoder().decode(base64Key.trim());
            if (key.length < 32) {
                throw new IllegalArgumentException("WZ_PSEUDONYM_KEY must be at least 256 bits");
            }
        }
    }

    public String pseudonym(String employeeId) {
        if (key == null) {
            throw new IllegalStateException("Pseudonymisation key (WZ_PSEUDONYM_KEY) not configured");
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(employeeId.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
