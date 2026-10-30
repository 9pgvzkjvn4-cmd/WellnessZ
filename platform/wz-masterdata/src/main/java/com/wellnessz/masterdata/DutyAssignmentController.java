package com.wellnessz.masterdata;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.wellnessz.common.error.ApiException;
import com.wellnessz.common.model.Roles;
import com.wellnessz.security.audit.AuditService;
import com.wellnessz.security.identity.CurrentUser;
import com.wellnessz.security.identity.CurrentUserProvider;

/**
 * Duty assignments from crew rostering, shift planning and facility access (increment 5): ingestion by internal
 * machine clients and person-level reads for OH contact tracing and critical-incident lists.
 */
@RestController
@RequestMapping("/api/v1")
@Validated
public class DutyAssignmentController {

    static final String CODE = "[A-Z0-9_\\-]{1,60}";
    static final Duration MAX_WINDOW = Duration.ofDays(62);

    private final DutyAssignmentRepository duties;
    private final EmployeeRepository employees;
    private final CurrentUserProvider users;
    private final AuditService audit;
    private final Clock clock;

    public DutyAssignmentController(DutyAssignmentRepository duties, EmployeeRepository employees, CurrentUserProvider users,
                                    AuditService audit, Clock clock) {
        this.duties = duties;
        this.employees = employees;
        this.users = users;
        this.audit = audit;
        this.clock = clock;
    }

    public record DutyInput(@NotBlank @Size(max = 120) @Pattern(regexp = "[A-Za-z0-9_:./\\-]{1,120}") String sourceRef,
                            @NotBlank @Pattern(regexp = CODE) String dutyRef, @NotNull LocalDate dutyDate, @NotNull Instant startsAt,
                            @NotNull Instant endsAt, @NotBlank @Pattern(regexp = "[A-Za-z0-9_\\-]{1,64}") String employeeRef,
                            @Pattern(regexp = "[A-Z_]{1,40}") String dutyRole, @Pattern(regexp = "[A-Z0-9_\\-]{1,40}") String locationCode,
                            @Pattern(regexp = "[A-Z0-9_\\-]{1,40}") String destinationCode, @Pattern(regexp = CODE) String proximityGroup) {
    }

    public record DutyBatch(@NotBlank @Pattern(regexp = "CREW_ROSTER|SHIFT|FACILITY") String source,
                            @NotEmpty @Size(max = 5000) List<@Valid @NotNull DutyInput> duties) {
    }

    public record IngestResult(int received, int upserted, List<String> unmatched) {
    }

    public record DutyView(String source, String dutyRef, LocalDate dutyDate, Instant startsAt, Instant endsAt, String employeeId,
                           String dutyRole, String locationCode, String destinationCode, String proximityGroup) {
        static DutyView of(DutyAssignment d) {
            return new DutyView(d.getSource(), d.getDutyRef(), d.getDutyDate(), d.getStartsAt(), d.getEndsAt(), d.getEmployeeId(),
                    d.getDutyRole(), d.getLocationCode(), d.getDestinationCode(), d.getProximityGroup());
        }
    }

    /** Internal rostering / shift systems only – machine clients bound to an external partner organisation are refused. */
    @PostMapping("/integration/duty-assignments")
    @PreAuthorize("hasRole('INTEGRATION')")
    @Transactional
    public IngestResult ingest(@Valid @RequestBody DutyBatch batch) {
        CurrentUser client = users.require();
        if (client.partnerOrganisation() != null) {
            throw ApiException.forbidden("Duty data is accepted from internal rostering systems only");
        }
        Instant now = clock.instant();
        int upserted = 0;
        List<String> unmatched = new java.util.ArrayList<>();
        for (DutyInput d : batch.duties()) {
            if (!d.endsAt().isAfter(d.startsAt())) {
                throw ApiException.badRequest("invalid_window", "endsAt must be after startsAt for " + d.sourceRef());
            }
            String employeeId = employees.findById(d.employeeRef()).map(Employee::getEmployeeId)
                    .or(() -> employees.findByBadgeIdAndActiveTrue(d.employeeRef()).map(Employee::getEmployeeId)).orElse(null);
            if (employeeId == null) {
                unmatched.add(d.sourceRef());
                continue;
            }
            DutyAssignment existing = duties.findBySourceAndSourceRef(batch.source(), d.sourceRef()).orElse(null);
            if (existing == null) {
                duties.save(new DutyAssignment(batch.source(), d.sourceRef(), d.dutyRef(), d.dutyDate(), d.startsAt(), d.endsAt(), employeeId,
                        d.dutyRole(), d.locationCode(), d.destinationCode(), d.proximityGroup(), now));
            } else {
                existing.update(d.dutyRef(), d.dutyDate(), d.startsAt(), d.endsAt(), employeeId, d.dutyRole(), d.locationCode(),
                        d.destinationCode(), d.proximityGroup(), now);
            }
            upserted++;
        }
        audit.record("DUTY_INGEST", "DUTY_ASSIGNMENT", batch.source(), AuditService.Outcome.SUCCESS,
                "received=" + batch.duties().size() + " upserted=" + upserted + " unmatched=" + unmatched.size());
        return new IngestResult(batch.duties().size(), upserted, unmatched);
    }

    @GetMapping("/masterdata/duty-assignments")
    @PreAuthorize(Roles.OH_TEAM_SPEL + " or hasAnyRole('SAFETY_MANAGER','CREW_SCHEDULING')")
    @Transactional(readOnly = true)
    public List<DutyView> forEmployee(@RequestParam @Pattern(regexp = "[A-Za-z0-9_\\-]{1,64}") String employeeId,
                                      @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
                                      @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to) {
        if (!to.isAfter(from) || Duration.between(from, to).compareTo(MAX_WINDOW) > 0) {
            throw ApiException.badRequest("invalid_window", "The window must be positive and at most 62 days");
        }
        List<DutyView> out = duties.findForEmployee(employeeId, from, to).stream().map(DutyView::of).toList();
        audit.record("DUTY_READ", "EMPLOYEE", employeeId, AuditService.Outcome.SUCCESS, "rows=" + out.size());
        return out;
    }
}
