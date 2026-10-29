package com.wellnessz.e03.support;

import org.springframework.stereotype.Component;

import com.wellnessz.casekernel.support.EmployeeRecordAccess;
import com.wellnessz.common.model.DataClassification;
import com.wellnessz.masterdata.Employee;
import com.wellnessz.security.abac.AccessPolicy;
import com.wellnessz.security.audit.AuditService;
import com.wellnessz.security.identity.CurrentUser;

/**
 * ABAC entry point for the OH record (E03-F08). Every read and write of chart content goes through here: the platform
 * {@link EmployeeRecordAccess} decides (OH team for the clinical class, PSYCH_CLINICIAN or an active break-glass grant
 * for the restricted class) and denials are audited and reported as 404. HR, managers, employees and other roles never
 * pass these checks.
 */
@Component
public class RecordGuard {

    private final EmployeeRecordAccess records;
    private final AuditService audit;

    public RecordGuard(EmployeeRecordAccess records, AuditService audit) {
        this.records = records;
        this.audit = audit;
    }

    /** Clinical read of an employee's record; audited on success by the caller (with row counts, no PHI). */
    public Access read(CurrentUser user, String employeeId, String auditAction) {
        Employee e = records.requireClinical(user, employeeId, DataClassification.CLINICAL, AccessPolicy.Action.READ, auditAction);
        return new Access(e, records.canSeeClinical(user, e, DataClassification.RESTRICTED_CLINICAL));
    }

    /** Clinical write; {@code classification} RESTRICTED_CLINICAL requires psych / break-glass access. */
    public Access write(CurrentUser user, String employeeId, DataClassification classification, AccessPolicy.Action action,
                        String auditAction) {
        Employee e = records.requireClinical(user, employeeId, classification, action, auditAction);
        return new Access(e, records.canSeeClinical(user, e, DataClassification.RESTRICTED_CLINICAL));
    }

    /** Reads a single item: a restricted item the caller may not see is reported as not found (and audited). */
    public void requireVisible(Access access, String classification, String resourceType, String resourceId, String auditAction) {
        if (!access.canSee(classification)) {
            audit.record(auditAction, resourceType, resourceId, AuditService.Outcome.DENIED, "Restricted clinical class");
            throw com.wellnessz.common.error.ApiException.notFound(resourceType.charAt(0) + resourceType.substring(1).toLowerCase());
        }
    }

    /** Employee master lookup without clinical disclosure (machine-to-machine ingestion). */
    public Employee employee(String employeeId) {
        return records.employee(employeeId);
    }

    public boolean canSeeRestricted(CurrentUser user, Employee e) {
        return records.canSeeClinical(user, e, DataClassification.RESTRICTED_CLINICAL);
    }

    public boolean canSeeClinical(CurrentUser user, Employee e) {
        return records.canSeeClinical(user, e, DataClassification.CLINICAL);
    }

    /** Result of an access check: the employee and whether the restricted class is visible to the caller. */
    public record Access(Employee employee, boolean restricted) {
        public boolean canSee(String classification) {
            return restricted || !DataClassification.RESTRICTED_CLINICAL.name().equals(classification);
        }
    }
}
