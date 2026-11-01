package com.wellnessz.e32.spi;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import com.wellnessz.common.events.IntegrationEnvelope;
import com.wellnessz.e32.driver.DriverRegistryService;
import com.wellnessz.e32.support.Docs;
import com.wellnessz.e32.support.E32Support;
import com.wellnessz.integration.idempotency.IdempotentConsumer;

/**
 * Consumes {@code device.signing-key.revoked.v1} (published in-process by E34 when SECURITY_OPS revokes a package-signing key,
 * contract §15.5): every driver package version signed with the key becomes REVOKED in the registry and the live instances on it
 * SUSPENDED. The event is matched by type only – no class dependency on E34. Handled once per message; never throws.
 */
@Component("e32SigningKeyRevocationListener")
public class SigningKeyRevocationListener {

    public static final String EVENT = "device.signing-key.revoked.v1";
    private static final Logger log = LoggerFactory.getLogger(SigningKeyRevocationListener.class);

    private final DriverRegistryService drivers;
    private final IdempotentConsumer consumer;
    private final E32Support support;

    public SigningKeyRevocationListener(DriverRegistryService drivers, IdempotentConsumer consumer, E32Support support) {
        this.drivers = drivers;
        this.consumer = consumer;
        this.support = support;
    }

    @EventListener(condition = "#envelope.eventType() == 'device.signing-key.revoked.v1'")
    public void on(IntegrationEnvelope envelope) {
        if (!support.enabled()) {
            log.info("E32 disabled – signing-key revocation {} left for later processing", envelope.messageId());
            return;
        }
        try {
            consumer.handleOnce("e32.signing-key-revoked", envelope, () -> {
                var payload = support.readMap(envelope.payload());
                String keyId = Docs.str(payload.get("keyId"));
                String by = Docs.str(payload.get("revokedBy"));
                List<String> revoked = drivers.revokeSignedWith(keyId, by == null ? "security" : by);
                if (!revoked.isEmpty()) {
                    log.info("Signing key revoked: {} driver package version(s) revoked", revoked.size());
                }
            });
        } catch (RuntimeException e) {
            log.warn("Signing-key revocation {} not applied: {}", envelope.messageId(), e.getClass().getSimpleName());
        }
    }
}
