package com.wellnessz.integration;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import jakarta.validation.constraints.Pattern;

import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.wellnessz.common.error.ApiException;
import com.wellnessz.integration.connector.ConnectorDescriptor;
import com.wellnessz.integration.connector.ConnectorRegistry;
import com.wellnessz.integration.idempotency.IdempotencyService;
import com.wellnessz.integration.inbound.InboundMessage;
import com.wellnessz.integration.inbound.InboundService;
import com.wellnessz.integration.outbox.OutboxEvent;
import com.wellnessz.integration.outbox.OutboxRepository;
import com.wellnessz.security.audit.AuditService;

import tools.jackson.databind.json.JsonMapper;

/** Generic inbound endpoint, event monitoring and replay (operations console for the integration gateway). */
@RestController
@RequestMapping("/api/v1/integration")
public class IntegrationController {

    private final IdempotencyService idempotency;
    private final InboundService inbound;
    private final OutboxRepository outbox;
    private final ConnectorRegistry connectors;
    private final AuditService audit;
    private final JsonMapper json;
    private final Clock clock;

    public IntegrationController(IdempotencyService idempotency, InboundService inbound, OutboxRepository outbox,
                                 ConnectorRegistry connectors, AuditService audit, JsonMapper json, Clock clock) {
        this.idempotency = idempotency;
        this.inbound = inbound;
        this.outbox = outbox;
        this.connectors = connectors;
        this.audit = audit;
        this.json = json;
        this.clock = clock;
    }

    @PostMapping(value = "/inbound/{source}", consumes = "application/json", produces = "application/json")
    public ResponseEntity<String> inbound(@PathVariable @Pattern(regexp = "[a-z0-9\\-]{2,50}") String source,
                                          @RequestHeader(value = "Idempotency-Key", required = false) String key,
                                          @RequestBody String body) {
        try {
            json.readTree(body);
        } catch (RuntimeException e) {
            throw ApiException.badRequest("invalid_json", "Body is not valid JSON");
        }
        return idempotency.execute("inbound:" + source, key, body, () -> {
            InboundMessage msg = inbound.accept(source, "JSON", null, key, body);
            return ResponseEntity.status(HttpStatus.ACCEPTED)
                    .body(json.writeValueAsString(Map.of("id", msg.getId().toString(), "status", "ACCEPTED")));
        });
    }

    @GetMapping("/connectors")
    public List<ConnectorDescriptor> connectors() {
        return connectors.all();
    }

    public record EventView(UUID id, String eventType, String destination, String aggregateType, String aggregateId,
                            OutboxEvent.Status status, int attempts, String lastError, java.time.Instant createdAt) {
        static EventView of(OutboxEvent e) {
            return new EventView(e.getId(), e.getEventType(), e.getDestination(), e.getAggregateType(), e.getAggregateId(),
                    e.getStatus(), e.getAttempts(), e.getLastError(), e.getCreatedAt());
        }
    }

    /** Metadata only – payloads are never exposed through the operations console. */
    @GetMapping("/events")
    public List<EventView> events(@RequestParam(required = false) OutboxEvent.Status status,
                                  @RequestParam(defaultValue = "100") int limit) {
        PageRequest page = PageRequest.of(0, Math.min(Math.max(limit, 1), 1000));
        List<OutboxEvent> rows = status == null ? outbox.findAllByOrderByCreatedAtDesc(page)
                : outbox.findByStatusOrderByCreatedAtDesc(status, page);
        return rows.stream().map(EventView::of).toList();
    }

    @PostMapping("/events/{id}/replay")
    @PreAuthorize("hasAnyRole('PLATFORM_ADMIN','OH_ADMIN')")
    @Transactional
    public EventView replay(@PathVariable UUID id) {
        OutboxEvent e = outbox.findById(id).orElseThrow(() -> ApiException.notFound("Event"));
        e.resetForReplay(clock.instant());
        audit.record("EVENT_REPLAY", "OUTBOX_EVENT", id.toString(), AuditService.Outcome.SUCCESS, e.getEventType());
        return EventView.of(e);
    }
}
