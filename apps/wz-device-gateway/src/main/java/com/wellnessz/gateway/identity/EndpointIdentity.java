package com.wellnessz.gateway.identity;

import java.time.Instant;

/** The identity issued at enrolment; persisted only encrypted ({@link IdentityStore}). */
public record EndpointIdentity(String endpointId, String endpointToken, Instant tokenExpiresAt, Instant enrolledAt,
                               String certificateSha256) {

    public EndpointIdentity withToken(String token, Instant expiresAt) {
        return new EndpointIdentity(endpointId, token, expiresAt, enrolledAt, certificateSha256);
    }

    @Override
    public String toString() {
        return "EndpointIdentity[endpointId=" + endpointId + ", token=«redacted», tokenExpiresAt=" + tokenExpiresAt + "]";
    }
}
