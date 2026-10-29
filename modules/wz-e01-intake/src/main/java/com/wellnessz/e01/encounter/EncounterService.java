package com.wellnessz.e01.encounter;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.wellnessz.casekernel.CaseService;
import com.wellnessz.casekernel.model.CaseCategory;
import com.wellnessz.casekernel.model.CaseLink;
import com.wellnessz.casekernel.model.CaseLinkRepository;
import com.wellnessz.casekernel.model.CaseSource;
import com.wellnessz.casekernel.model.CaseType;
import com.wellnessz.casekernel.model.OhCase;
import com.wellnessz.casekernel.model.OpenCaseCommand;
import com.wellnessz.casekernel.model.Urgency;
import com.wellnessz.common.error.ApiException;
import com.wellnessz.common.model.Roles;
import com.wellnessz.masterdata.Employee;
import com.wellnessz.masterdata.EmployeeRepository;
import com.wellnessz.security.audit.AuditService;
import com.wellnessz.security.identity.CurrentUser;

/**
 * Walk-in / emergency registration (E01-F03). Creation is idempotent on the client-generated {@code clientRef} so
 * an offline-tolerant client can safely retry. Reception and first aiders never read clinical fields back.
 */
@Service
public class EncounterService {

    private final EncounterRepository encounters;
    private final EmployeeRepository employees;
    private final CaseService cases;
    private final CaseLinkRepository links;
    private final AuditService audit;
    private final Clock clock;

    public EncounterService(EncounterRepository encounters, EmployeeRepository employees, CaseService cases,
                            CaseLinkRepository links, AuditService audit, Clock clock) {
        this.encounters = encounters;
        this.employees = employees;
        this.cases = cases;
        this.links = links;
        this.audit = audit;
        this.clock = clock;
    }

    public record CreateRequest(UUID clientRef, Encounter.Mode mode, String employeeId, Instant occurredAt, String siteCode,
                                String location, String chiefComplaint, String actionTaken) {
    }

    public record EncounterView(UUID id, UUID clientRef, Encounter.Mode mode, String employeeId, String employeeName,
                                Instant occurredAt, String siteCode, String location, String chiefComplaint, String actionTaken,
                                Encounter.Status status, boolean enrichmentDue, UUID caseId, String caseRef, String createdBy) {
        static EncounterView of(Encounter e, boolean clinical) {
            return new EncounterView(e.getId(), e.getClientRef(), e.getMode(), e.getEmployeeId(), e.getEmployeeName(),
                    e.getOccurredAt(), e.getSiteCode(), e.getLocation(), clinical ? e.getChiefComplaint() : null,
                    clinical ? e.getActionTaken() : null, e.getStatus(), e.isEnrichmentDue(), e.getCaseId(), e.getCaseRef(),
                    e.getCreatedBy());
        }
    }

    @Transactional
    public EncounterView create(CurrentUser user, CreateRequest r) {
        var existing = encounters.findByClientRef(r.clientRef());
        if (existing.isPresent()) {
            return EncounterView.of(existing.get(), isClinical(user)); // idempotent retry from an offline queue
        }
        Employee emp = employees.findById(r.employeeId()).filter(Employee::isActive)
                .orElseThrow(() -> new ApiException(org.springframework.http.HttpStatus.UNPROCESSABLE_CONTENT, "unknown_employee",
                        "Employee not found in HRIS – check the badge or employee number"));
        Encounter e = encounters.save(new Encounter(r.clientRef(), r.mode(), r.employeeId(),
                emp.getDisplayName(), r.occurredAt() == null ? clock.instant() : r.occurredAt(),
                r.siteCode(), r.location(), r.chiefComplaint(), r.actionTaken(), user.username(), clock.instant()));
        audit.record("ENCOUNTER_REGISTERED", "ENCOUNTER", e.getId().toString(), AuditService.Outcome.SUCCESS, r.mode().name());
        return EncounterView.of(e, isClinical(user));
    }

    @Transactional
    public EncounterView enrich(CurrentUser user, UUID id, String chiefComplaint, String actionTaken, String notes) {
        Encounter e = encounters.findById(id).orElseThrow(() -> ApiException.notFound("Encounter"));
        if (!isClinical(user) && !user.username().equals(e.getCreatedBy())) {
            throw ApiException.forbidden("Only the registering user or OH clinicians can enrich this encounter");
        }
        e.enrich(chiefComplaint, actionTaken, notes, clock.instant());
        audit.record("ENCOUNTER_ENRICHED", "ENCOUNTER", id.toString(), AuditService.Outcome.SUCCESS, null);
        return EncounterView.of(e, isClinical(user));
    }

    @Transactional(readOnly = true)
    public List<EncounterView> list(CurrentUser user, Encounter.Status status, String site) {
        List<Encounter.Status> statuses = status == null ? List.of(Encounter.Status.OPEN, Encounter.Status.ENRICHED) : List.of(status);
        boolean clinical = isClinical(user);
        return encounters.findTop200ByStatusInOrderByOccurredAtDesc(statuses).stream()
                .filter(e -> site == null || site.equals(e.getSiteCode()))
                .map(e -> EncounterView.of(e, clinical)).toList();
    }

    public record Converted(String caseId, String caseRef) {
    }

    /** One-click conversion to a case; the case type defaults from the chief complaint (E01-BL-013). */
    @Transactional
    public Converted convert(CurrentUser user, UUID id) {
        if (!isClinical(user)) {
            throw ApiException.forbidden("Only OH clinicians can convert encounters");
        }
        Encounter e = encounters.findById(id).orElseThrow(() -> ApiException.notFound("Encounter"));
        if (e.getStatus() == Encounter.Status.CONVERTED) {
            return new Converted(e.getCaseId().toString(), e.getCaseRef());
        }
        CaseType type = typeFor(e.getChiefComplaint());
        OhCase c = cases.open(OpenCaseCommand.builder(e.getEmployeeId(), categoryFor(type),
                        e.getMode() == Encounter.Mode.EMERGENCY ? CaseSource.EMERGENCY : CaseSource.WALK_IN)
                .employeeName(e.getEmployeeName())
                .caseType(type)
                .sourceRef(e.getId().toString())
                .locationCode(e.getSiteCode())
                .occurredOn(e.getOccurredAt().atZone(ZoneOffset.UTC).toLocalDate())
                .urgency(e.getMode() == Encounter.Mode.EMERGENCY ? Urgency.URGENT : null)
                .clinicalSummary(summary(e))
                .build());
        links.save(new CaseLink(c.getId(), CaseLink.Type.ENCOUNTER, e.getId().toString(), user.username(), clock.instant()));
        e.converted(c.getId(), c.getCaseRef());
        audit.record("ENCOUNTER_CONVERTED", "ENCOUNTER", id.toString(), AuditService.Outcome.SUCCESS, c.getCaseRef());
        return new Converted(c.getId().toString(), c.getCaseRef());
    }

    static CaseType typeFor(String complaint) {
        String c = complaint == null ? "" : complaint.toLowerCase(Locale.ROOT);
        if (c.matches(".*(fume|chemical|smoke|splash|exposure|needle|blood|fluid|inhal).*")) {
            return CaseType.EXPOSURE;
        }
        if (c.matches(".*(cut|burn|fall|slip|trip|sprain|strain|lift|injur|wound|bruise|fracture|twist|crush|laceration).*")) {
            return CaseType.INJURY;
        }
        if (c.matches(".*(stress|anxi|panic|low mood|distress).*")) {
            return CaseType.MENTAL_HEALTH;
        }
        if (c.isBlank()) {
            return CaseType.OTHER;
        }
        return CaseType.ILLNESS;
    }

    private static CaseCategory categoryFor(CaseType t) {
        return switch (t) {
            case EXPOSURE -> CaseCategory.EXPOSURE;
            case INJURY -> CaseCategory.INJURY;
            case MENTAL_HEALTH -> CaseCategory.PSYCHOLOGICAL;
            case ILLNESS -> CaseCategory.ILLNESS;
            default -> CaseCategory.OTHER;
        };
    }

    private static String summary(Encounter e) {
        StringBuilder sb = new StringBuilder();
        if (e.getChiefComplaint() != null) {
            sb.append("Chief complaint: ").append(e.getChiefComplaint());
        }
        if (e.getActionTaken() != null) {
            sb.append("\nAction taken: ").append(e.getActionTaken());
        }
        if (e.getNotes() != null) {
            sb.append("\nNotes: ").append(e.getNotes());
        }
        return sb.isEmpty() ? null : sb.toString();
    }

    static boolean isClinical(CurrentUser user) {
        return user.hasAnyRole(Roles.OH_TEAM);
    }
}
