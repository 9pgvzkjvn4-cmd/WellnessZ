package com.wellnessz.e01.timeline;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import jakarta.validation.constraints.Pattern;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.wellnessz.casekernel.model.OhCase;
import com.wellnessz.casekernel.model.OhCaseRepository;
import com.wellnessz.casekernel.support.CaseAccess;
import com.wellnessz.e01.encounter.EncounterRepository;
import com.wellnessz.e01.trigger.AbsenceEpisodeRepository;
import com.wellnessz.e01.trigger.TriggerEventRepository;
import com.wellnessz.ehsintake.model.EhsIncident;
import com.wellnessz.ehsintake.model.EhsIncidentRepository;
import com.wellnessz.masterdata.EmployeeRepository;
import com.wellnessz.security.audit.AuditService;
import com.wellnessz.security.identity.CurrentUser;
import com.wellnessz.security.identity.CurrentUserProvider;

/**
 * Employee timeline (E01-BL-018): cases, encounters, absences, triggers and incidents of one employee. Cases the
 * caller may not see (e.g. restricted class for a general clinician) are omitted. Every read is audited.
 */
@RestController
@RequestMapping("/api/v1/employees")
public class EmployeeTimelineController {

    private final EmployeeRepository employees;
    private final OhCaseRepository cases;
    private final EncounterRepository encounters;
    private final AbsenceEpisodeRepository absences;
    private final TriggerEventRepository triggers;
    private final EhsIncidentRepository incidents;
    private final CaseAccess access;
    private final CurrentUserProvider users;
    private final AuditService audit;

    public EmployeeTimelineController(EmployeeRepository employees, OhCaseRepository cases, EncounterRepository encounters,
                                      AbsenceEpisodeRepository absences, TriggerEventRepository triggers,
                                      EhsIncidentRepository incidents, CaseAccess access, CurrentUserProvider users,
                                      AuditService audit) {
        this.employees = employees;
        this.cases = cases;
        this.encounters = encounters;
        this.absences = absences;
        this.triggers = triggers;
        this.incidents = incidents;
        this.access = access;
        this.users = users;
        this.audit = audit;
    }

    public record Item(Instant at, String kind, String ref, String title, String status, String caseId) {
        Item(Instant at, String kind, String ref, String title, String status) {
            this(at, kind, ref, title, status, null);
        }
    }

    @GetMapping("/{employeeId}/timeline")
    @PreAuthorize("hasAnyRole('OH_CLINICIAN','OH_PHYSICIAN','AME','CMO','NURSING_HEAD','CASE_MANAGER','PSYCH_CLINICIAN')")
    @Transactional(readOnly = true)
    public Map<String, Object> timeline(@PathVariable @Pattern(regexp = "[A-Za-z0-9\\-]{1,64}") String employeeId) {
        CurrentUser user = users.require();
        List<Item> items = new ArrayList<>();
        for (OhCase c : cases.findByEmployeeIdOrderByCreatedAtDesc(employeeId)) {
            var d = access.decide(user, c);
            if (d.permitted() && d.disclosure().atLeast(com.wellnessz.common.model.DisclosureLevel.CLINICAL)) {
                items.add(new Item(c.getCreatedAt(), "CASE", c.getCaseRef(), c.getCaseType() + " via " + c.getSource(), c.getStatus().name(),
                        c.getId().toString()));
            }
        }
        encounters.findByEmployeeIdOrderByOccurredAtDesc(employeeId).forEach(e -> items.add(new Item(e.getOccurredAt(), "ENCOUNTER",
                e.getId().toString(), e.getMode() + " at " + (e.getSiteCode() == null ? "clinic" : e.getSiteCode()), e.getStatus().name())));
        absences.findByEmployeeIdOrderByStartDateDesc(employeeId).forEach(a -> items.add(new Item(
                a.getStartDate().atStartOfDay().toInstant(java.time.ZoneOffset.UTC), "ABSENCE", a.getSourceRef(),
                "Absence " + a.getStartDate() + " – " + (a.getEndDate() == null ? "open" : a.getEndDate()), a.getReasonCategory())));
        triggers.findByEmployeeIdOrderByReceivedAtDesc(employeeId).forEach(t -> items.add(new Item(t.getReceivedAt(), "TRIGGER",
                t.getSourceRef(), t.getType().name(), t.getOutcome().name())));
        incidents.findTop200ByEmployeeIdOrderByReceivedAtDesc(employeeId)
                .forEach((EhsIncident i) -> items.add(new Item(i.getOccurredAt() == null ? i.getReceivedAt() : i.getOccurredAt(),
                        "INCIDENT", i.getExternalRef(), i.getIncidentClass() == null ? "Incident" : i.getIncidentClass(), i.getStatus().name())));
        items.sort(Comparator.comparing(Item::at, Comparator.nullsLast(Comparator.reverseOrder())));
        var emp = employees.findById(employeeId).map(e -> Map.<String, Object>of("employeeId", e.getEmployeeId(),
                "displayName", e.getDisplayName(), "subsidiaryCode", e.getSubsidiaryCode(), "departmentCode", e.getDepartmentCode(),
                "jobCode", e.getJobCode() == null ? "" : e.getJobCode())).orElse(Map.of("employeeId", employeeId));
        audit.record("EMPLOYEE_TIMELINE_READ", "EMPLOYEE", employeeId, AuditService.Outcome.SUCCESS, "items=" + items.size());
        return Map.of("employee", emp, "items", items);
    }
}
