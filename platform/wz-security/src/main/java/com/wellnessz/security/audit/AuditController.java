package com.wellnessz.security.audit;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import org.springframework.data.domain.PageRequest;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Read-only access to the audit trail for auditors (SOC 2 evidence collection). */
@RestController
@RequestMapping("/api/v1/audit")
@PreAuthorize("hasAnyRole('AUDITOR','PLATFORM_ADMIN')")
public class AuditController {

    private final AuditEventRepository repository;
    private final AuditService auditService;

    public AuditController(AuditEventRepository repository, AuditService auditService) {
        this.repository = repository;
        this.auditService = auditService;
    }

    /** API view of an audit record (entities are never serialised directly). */
    public record AuditRecordView(long id, Instant occurredAt, String actor, String actorRoles, String action,
                                  String resourceType, String resourceId, String outcome, String reason,
                                  String correlationId, String clientIp, String hash) {
        static AuditRecordView of(AuditEvent e) {
            return new AuditRecordView(e.getId(), e.getOccurredAt(), e.getActor(), e.getActorRoles(), e.getAction(),
                    e.getResourceType(), e.getResourceId(), e.getOutcome(), e.getReason(), e.getCorrelationId(),
                    e.getClientIp(), e.getHash());
        }
    }

    @GetMapping
    public List<AuditRecordView> list(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
                                 @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
                                 @RequestParam(defaultValue = "500") int limit) {
        Instant end = to != null ? to : Instant.now();
        Instant start = from != null ? from : end.minus(1, ChronoUnit.DAYS);
        auditService.record("AUDIT_READ", "AUDIT", null, AuditService.Outcome.SUCCESS, null);
        return repository.findByOccurredAtBetweenOrderByIdAsc(start, end, PageRequest.of(0, Math.min(Math.max(limit, 1), 5000)))
                .stream().map(AuditRecordView::of).toList();
    }

    @GetMapping("/verify")
    public AuditService.ChainVerification verify() {
        return auditService.verify();
    }
}
