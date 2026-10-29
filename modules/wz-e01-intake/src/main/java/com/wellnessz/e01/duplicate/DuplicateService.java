package com.wellnessz.e01.duplicate;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.wellnessz.casekernel.model.CaseAttachmentRepository;
import com.wellnessz.casekernel.model.CaseLink;
import com.wellnessz.casekernel.model.CaseLinkRepository;
import com.wellnessz.casekernel.model.CaseStatus;
import com.wellnessz.casekernel.model.OhCase;
import com.wellnessz.casekernel.model.OhCaseRepository;
import com.wellnessz.casekernel.support.CaseAccess;
import com.wellnessz.casekernel.support.CaseTimeline;
import com.wellnessz.common.error.ApiException;
import com.wellnessz.common.events.IntegrationEnvelope;
import com.wellnessz.integration.idempotency.IdempotentConsumer;
import com.wellnessz.security.abac.AccessPolicy;
import com.wellnessz.security.audit.AuditService;
import com.wellnessz.security.identity.CurrentUser;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Probable-duplicate detection (same employee, event date ±2 days, same body part or same incident) at case creation,
 * and merge / link with full audit (E01-F06). Merging keeps both source records: links and attachments move to the
 * surviving case and a {@code MERGED_FROM} link records the origin.
 */
@Service
public class DuplicateService {

    private final OhCaseRepository cases;
    private final CaseLinkRepository links;
    private final CaseAttachmentRepository attachments;
    private final CaseAccess access;
    private final CaseTimeline timeline;
    private final IdempotentConsumer consumer;
    private final AuditService audit;
    private final JsonMapper json;
    private final Clock clock;

    public DuplicateService(OhCaseRepository cases, CaseLinkRepository links, CaseAttachmentRepository attachments,
                            CaseAccess access, CaseTimeline timeline, IdempotentConsumer consumer, AuditService audit,
                            JsonMapper json, Clock clock) {
        this.cases = cases;
        this.links = links;
        this.attachments = attachments;
        this.access = access;
        this.timeline = timeline;
        this.consumer = consumer;
        this.audit = audit;
        this.json = json;
        this.clock = clock;
    }

    public record Candidate(UUID caseId, String caseRef, CaseStatus status, java.time.Instant createdAt, int score,
                            List<String> reasons) {
    }

    @EventListener(condition = "#envelope.eventType() == 'case.opened.v1'")
    public void onCaseOpened(IntegrationEnvelope envelope) {
        consumer.handleOnce("e01.duplicate-detection", envelope, () -> {
            JsonNode n = json.readTree(envelope.payload());
            OhCase c = cases.findById(UUID.fromString(n.get("caseId").asString())).orElse(null);
            if (c == null) {
                return;
            }
            List<Candidate> found = candidatesFor(c);
            if (!found.isEmpty()) {
                c.flagDuplicateSuspect(true, clock.instant());
                timeline.record(c.getId(), "DUPLICATE_SUSPECT", "Possible duplicate of " + found.getFirst().caseRef());
            }
            // repeat visits for the same body part (E01-HLR-04) – visible as a badge and complexity driver
            if (c.getBodyPart() != null && cases.countByEmployeeAndBodyPartSince(c.getEmployeeId(), c.getBodyPart(),
                    clock.instant().minus(Duration.ofDays(365))) >= 3) {
                timeline.record(c.getId(), "REPEAT_BODY_PART", "Third or later case for the same body part within 12 months");
            }
        });
    }

    @Transactional(readOnly = true)
    public List<Candidate> candidates(CurrentUser user, UUID caseId) {
        OhCase c = access.clinical(user, caseId, AccessPolicy.Action.READ, "CASE_DUPLICATES_READ");
        return candidatesFor(c);
    }

    List<Candidate> candidatesFor(OhCase c) {
        LocalDate day = eventDate(c);
        List<Candidate> out = new ArrayList<>();
        for (OhCase o : cases.findOpenForEmployeeSince(c.getEmployeeId(), c.getId(), c.getCreatedAt().minus(Duration.ofDays(30)))) {
            List<String> reasons = new ArrayList<>(List.of("SAME_EMPLOYEE"));
            int score = 30;
            LocalDate other = eventDate(o);
            boolean near = Math.abs(java.time.temporal.ChronoUnit.DAYS.between(day, other)) <= 2;
            if (near) {
                reasons.add("DATE_WITHIN_2_DAYS");
                score += 30;
            }
            if (c.getBodyPart() != null && c.getBodyPart().equals(o.getBodyPart())) {
                reasons.add("SAME_BODY_PART");
                score += 25;
            }
            if (c.getIncidentRef() != null && c.getIncidentRef().equals(o.getIncidentRef())) {
                reasons.add("SAME_INCIDENT");
                score += 40;
            }
            if (near && score >= 55) {
                out.add(new Candidate(o.getId(), o.getCaseRef(), o.getStatus(), o.getCreatedAt(), Math.min(score, 100), reasons));
            }
        }
        out.sort(Comparator.comparingInt(Candidate::score).reversed());
        return out;
    }

    private static LocalDate eventDate(OhCase c) {
        return c.getOccurredOn() != null ? c.getOccurredOn() : c.getCreatedAt().atZone(ZoneOffset.UTC).toLocalDate();
    }

    @Transactional
    public void merge(CurrentUser user, UUID sourceId, UUID targetId, String reason) {
        if (Objects.equals(sourceId, targetId)) {
            throw ApiException.badRequest("same_case", "Cannot merge a case into itself");
        }
        OhCase source = access.clinical(user, sourceId, AccessPolicy.Action.UPDATE, "CASE_MERGE");
        OhCase target = access.clinical(user, targetId, AccessPolicy.Action.UPDATE, "CASE_MERGE");
        if (!source.getEmployeeId().equals(target.getEmployeeId())) {
            throw ApiException.conflict("different_employee", "Only cases of the same employee can be merged; use a link instead");
        }
        if (!target.isOpen()) {
            throw ApiException.conflict("target_closed", "Target case is not open");
        }
        try {
            source.mergeInto(target.getId(), clock.instant());
        } catch (IllegalStateException e) {
            throw ApiException.conflict("invalid_transition", e.getMessage());
        }
        links.findByCaseIdOrderByCreatedAt(source.getId()).forEach(l -> l.moveTo(target.getId()));
        attachments.findByCaseId(source.getId()).forEach(a -> a.moveTo(target.getId()));
        links.save(new CaseLink(target.getId(), CaseLink.Type.MERGED_FROM, source.getCaseRef(), user.username(), clock.instant()));
        if (source.getClinicalSummary() != null) {
            target.appendClinicalSummary("[Merged from " + source.getCaseRef() + "] " + source.getClinicalSummary(), clock.instant());
        }
        target.flagDuplicateSuspect(false, clock.instant());
        timeline.record(source.getId(), "MERGED", "Merged into " + target.getCaseRef());
        timeline.record(target.getId(), "MERGED_FROM", "Merged from " + source.getCaseRef());
        audit.record("CASE_MERGE", "CASE", source.getId().toString(), AuditService.Outcome.SUCCESS,
                "into=" + target.getCaseRef() + " reason=" + reason);
    }
}
