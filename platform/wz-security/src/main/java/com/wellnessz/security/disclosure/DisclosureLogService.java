package com.wellnessz.security.disclosure;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.wellnessz.security.audit.AuditService;

/**
 * Disclosure log (E03-BL-022): every share of an employee's documents or record outside the OH team is recorded so the
 * employee can see who received what, and consent withdrawal can be traced to its effect.
 */
@Service
public class DisclosureLogService {

    private final DisclosureRepository repository;
    private final AuditService audit;
    private final Clock clock;

    public DisclosureLogService(DisclosureRepository repository, AuditService audit, Clock clock) {
        this.repository = repository;
        this.audit = audit;
        this.clock = clock;
    }

    public record Disclosure(UUID id, Instant at, String recipientType, String recipientName, String resourceType, String resourceId,
                             String documentTitle, String purpose, UUID consentId, String status, Instant revokedAt, String revokedReason,
                             String disclosedBy) {
        static Disclosure of(DisclosureRecord r, boolean withActor) {
            return new Disclosure(r.getId(), r.getDisclosedAt(), r.getRecipientType(), r.getRecipientName(), r.getResourceType(),
                    r.getResourceId(), r.getResourceTitle(), r.getPurpose(), r.getConsentId(), r.getRevokedAt() == null ? "ACTIVE" : "REVOKED",
                    r.getRevokedAt(), r.getRevokedReason(), withActor ? r.getDisclosedBy() : null);
        }
    }

    @Transactional
    public UUID record(String employeeId, String recipientType, String recipientName, String resourceType, String resourceId,
                       String resourceTitle, String purpose, UUID consentId, String disclosedBy) {
        DisclosureRecord r = repository.save(new DisclosureRecord(employeeId, recipientType, recipientName, resourceType, resourceId,
                resourceTitle, purpose, consentId, disclosedBy, clock.instant()));
        return r.getId();
    }

    /** Marks the disclosures of one resource as revoked (e.g. a share was withdrawn). */
    @Transactional
    public void revokeResource(String resourceType, String resourceId, String reason) {
        repository.findByResourceTypeAndResourceIdAndRevokedAtIsNull(resourceType, resourceId).forEach(r -> r.revoke(reason, clock.instant()));
    }

    /** Effect of consent withdrawal on the log. Returns the number of disclosures revoked. */
    @Transactional
    public int revokeByConsent(UUID consentId) {
        List<DisclosureRecord> list = repository.findByConsentIdAndRevokedAtIsNull(consentId);
        list.forEach(r -> r.revoke("Consent withdrawn", clock.instant()));
        if (!list.isEmpty()) {
            audit.record("DISCLOSURE_REVOKED", "CONSENT", consentId.toString(), AuditService.Outcome.SUCCESS, "disclosures=" + list.size());
        }
        return list.size();
    }

    @Transactional(readOnly = true)
    public List<Disclosure> forEmployee(String employeeId, boolean withActor) {
        return repository.findByEmployeeIdOrderByDisclosedAtDesc(employeeId).stream().map(r -> Disclosure.of(r, withActor)).toList();
    }
}
