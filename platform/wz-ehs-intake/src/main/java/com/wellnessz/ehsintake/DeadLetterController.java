package com.wellnessz.ehsintake;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.wellnessz.common.error.ApiException;
import com.wellnessz.ehsintake.model.DeadLetter;
import com.wellnessz.ehsintake.model.DeadLetterRepository;
import com.wellnessz.security.audit.AuditService;
import com.wellnessz.security.identity.CurrentUserProvider;

/** Operator console for the incident dead-letter queue (payloads are never returned – metadata only). */
@RestController
@RequestMapping("/api/v1/intake/dlq")
@PreAuthorize("hasAnyRole('OH_ADMIN','PLATFORM_ADMIN')")
public class DeadLetterController {

    private final DeadLetterRepository dlq;
    private final IncidentWebhookService webhook;
    private final CurrentUserProvider users;
    private final AuditService audit;
    private final Clock clock;

    public DeadLetterController(DeadLetterRepository dlq, IncidentWebhookService webhook, CurrentUserProvider users,
                                AuditService audit, Clock clock) {
        this.dlq = dlq;
        this.webhook = webhook;
        this.users = users;
        this.audit = audit;
        this.clock = clock;
    }

    public record DeadLetterView(String id, Instant receivedAt, String source, String reason, DeadLetter.Status status,
                                 String resolvedBy, Instant resolvedAt) {
        static DeadLetterView of(DeadLetter d) {
            return new DeadLetterView(d.getId().toString(), d.getReceivedAt(), d.getSource(), d.getReason(), d.getStatus(),
                    d.getResolvedBy(), d.getResolvedAt());
        }
    }

    @GetMapping
    public List<DeadLetterView> open() {
        return dlq.findByStatusOrderByReceivedAtDesc(DeadLetter.Status.OPEN).stream().map(DeadLetterView::of).toList();
    }

    @PostMapping("/{id}/replay")
    @Transactional
    public IncidentWebhookService.WebhookResult replay(@PathVariable UUID id) {
        DeadLetter d = dlq.findById(id).filter(x -> x.getStatus() == DeadLetter.Status.OPEN)
                .orElseThrow(() -> ApiException.notFound("Dead letter"));
        try {
            IncidentWebhookService.WebhookResult r = webhook.replay(d, users.actorOrSystem());
            audit.record("EHS_DLQ_REPLAY", "INCIDENT_DLQ", id.toString(), AuditService.Outcome.SUCCESS, null);
            return r;
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("still_invalid", e.getMessage());
        }
    }

    @PostMapping("/{id}/discard")
    @Transactional
    public DeadLetterView discard(@PathVariable UUID id) {
        DeadLetter d = dlq.findById(id).orElseThrow(() -> ApiException.notFound("Dead letter"));
        d.resolve(DeadLetter.Status.DISCARDED, users.actorOrSystem(), clock.instant());
        audit.record("EHS_DLQ_DISCARD", "INCIDENT_DLQ", id.toString(), AuditService.Outcome.SUCCESS, null);
        return DeadLetterView.of(d);
    }
}
