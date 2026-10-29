package com.wellnessz.masterdata;

import java.time.Clock;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.wellnessz.security.audit.AuditService;

/** HRIS synchronisation endpoint for the employee master (batch upsert, idempotent by employee id). */
@RestController
@RequestMapping("/api/v1/masterdata/employees")
public class EmployeeController {

    private final EmployeeRepository employees;
    private final AuditService audit;
    private final Clock clock;

    public EmployeeController(EmployeeRepository employees, AuditService audit, Clock clock) {
        this.employees = employees;
        this.audit = audit;
        this.clock = clock;
    }

    public record EmployeeRecord(@NotBlank @Pattern(regexp = "[A-Za-z0-9\\-]{1,64}") String employeeId,
                                 @NotBlank @Size(max = 200) String displayName, @Size(max = 320) String email,
                                 @Size(max = 40) String phone, @NotBlank @Size(max = 20) String subsidiaryCode,
                                 @NotBlank @Size(max = 64) String departmentCode, @Size(max = 40) String costCentre,
                                 @Size(max = 64) String jobCode, @Size(max = 40) String locationCode,
                                 @Size(max = 40) String shiftPattern, @Size(max = 8) String preferredLanguage,
                                 @Size(max = 64) String badgeId, @Size(max = 64) String managerEmployeeId, Boolean active) {
    }

    public record SyncResult(int upserted) {
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('INTEGRATION','OH_ADMIN','PLATFORM_ADMIN')")
    @Transactional
    public SyncResult upsert(@Valid @RequestBody List<@Valid EmployeeRecord> records) {
        if (records.size() > 5000) {
            throw new IllegalArgumentException("Batch too large");
        }
        for (EmployeeRecord r : records) {
            Employee e = employees.findById(r.employeeId())
                    .orElseGet(() -> new Employee(r.employeeId(), r.displayName(), r.subsidiaryCode(), r.departmentCode(), clock.instant()));
            e.apply(r.displayName(), r.email(), r.phone(), r.subsidiaryCode(), r.departmentCode(), r.costCentre(), r.jobCode(),
                    r.locationCode(), r.shiftPattern(), r.preferredLanguage(), r.badgeId(), r.managerEmployeeId(),
                    r.active() == null || r.active(), clock.instant());
            employees.save(e);
        }
        audit.record("HRIS_SYNC", "EMPLOYEE", null, AuditService.Outcome.SUCCESS, "upserted=" + records.size());
        return new SyncResult(records.size());
    }
}
