package com.wellnessz.e34.events;

/**
 * Called synchronously after a security event has been committed to the chain (anomaly rules). Implementations run their own
 * transactions and must never throw back into the publisher.
 */
public interface SecurityEventListener {

    void onSecurityEvent(StoredSecurityEvent event);
}
