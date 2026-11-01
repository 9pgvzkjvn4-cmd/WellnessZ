package com.wellnessz.e31.service;

import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import com.wellnessz.casekernel.device.DeviceCaptureSessions;
import com.wellnessz.casekernel.device.DeviceCatalogueLookup;
import com.wellnessz.casekernel.device.DeviceSpis;
import com.wellnessz.casekernel.module.ModuleRegistry;
import com.wellnessz.common.events.IntegrationEnvelope;
import com.wellnessz.integration.idempotency.IdempotentConsumer;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Bidirectional workflows (E31-HLR-08): reacts to E33's {@code device.capture.session.opened.v1} by queueing the gateway
 * command named in {@code contextPush} ({@code PUSH_WORKLIST} / {@code START_ACQUISITION}) for the endpoint fronting the
 * device. The person context is read through {@link DeviceCaptureSessions#activeSession}; the command carries only the
 * session and order reference – the gateway pulls the minimised worklist itself ({@code /device-ingest/worklist}).
 */
@Component("e31CaptureSessionListener")
public class CaptureSessionListener {

    static final Set<String> COMMANDS = Set.of("PUSH_WORKLIST", "START_ACQUISITION");

    private final IdempotentConsumer consumer;
    private final DeviceSpis spis;
    private final GatewayService gateways;
    private final ModuleRegistry modules;
    private final JsonMapper json;
    private final Clock clock;

    public CaptureSessionListener(IdempotentConsumer consumer, DeviceSpis spis, GatewayService gateways, ModuleRegistry modules, JsonMapper json,
                                  Clock clock) {
        this.consumer = consumer;
        this.spis = spis;
        this.gateways = gateways;
        this.modules = modules;
        this.json = json;
        this.clock = clock;
    }

    @EventListener(condition = "#envelope.eventType() == 'device.capture.session.opened.v1'")
    public void onSessionOpened(IntegrationEnvelope envelope) {
        if (!modules.isEnabled("E31")) {
            return;
        }
        consumer.handleOnce("e31.capture-session-context", envelope, () -> handle(json.readTree(envelope.payload())));
    }

    /** @return the queued command type, or {@code null} when nothing is pushed */
    public String handle(JsonNode e) {
        String push = e.path("contextPush").asString("");
        String instanceId = e.path("deviceInstanceId").asString(null);
        if (!COMMANDS.contains(push) || instanceId == null) {
            return null;
        }
        DeviceCatalogueLookup.DeviceInstance instance = spis.catalogue().instance(instanceId).orElse(null);
        if (instance == null || instance.endpointId() == null || !instance.published()) {
            return null;
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("sessionId", e.path("sessionId").asString(null));
        spis.sessions().activeSession(instanceId, clock.instant()).ifPresent(s -> {
            if (s.orderRef() != null) {
                payload.put("orderRef", s.orderRef());
            }
            payload.put("formCode", s.formCode());
        });
        gateways.queueCommand(instance.endpointId(), push, instanceId, payload, "device-engine");
        return push;
    }
}
