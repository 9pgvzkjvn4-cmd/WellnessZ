package com.wellnessz.e03.device;

import java.util.UUID;

import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import com.wellnessz.common.events.IntegrationEnvelope;
import com.wellnessz.e03.results.ResultService;
import com.wellnessz.integration.idempotency.IdempotentConsumer;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Acknowledges the E03 result of an ECG / ABPM / Holter device capture when the physician signs off the device
 * interpretation in the capture dialogue ({@code device.capture.interpretation-signed.v1}, E33-F06). The payload is read
 * into a local tree – E03 never depends on E33 classes.
 */
@Component
public class InterpretationSignOffListener {

    private final IdempotentConsumer consumer;
    private final ResultService results;
    private final JsonMapper json;

    public InterpretationSignOffListener(IdempotentConsumer consumer, ResultService results, JsonMapper json) {
        this.consumer = consumer;
        this.results = results;
        this.json = json;
    }

    @EventListener(condition = "#envelope.eventType() == 'device.capture.interpretation-signed.v1'")
    public void onSigned(IntegrationEnvelope envelope) {
        consumer.handleOnce("e03.device-interpretation-sign-off", envelope, () -> {
            JsonNode e = json.readTree(envelope.payload());
            if (!"RESULT".equals(e.path("routedRecordType").asString("")) || !e.hasNonNull("routedRecordId") || !e.hasNonNull("signedBy")) {
                return;
            }
            try {
                results.acknowledgeSignOff(UUID.fromString(e.get("routedRecordId").asString()), e.get("signedBy").asString());
            } catch (IllegalArgumentException notAResultId) {
                // not an E03 result id: nothing to acknowledge
            }
        });
    }
}
