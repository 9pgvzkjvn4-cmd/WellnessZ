package com.wellnessz.casekernel.support;

import org.springframework.stereotype.Component;

import com.wellnessz.common.error.ApiException;
import com.wellnessz.common.model.DataClassification;
import com.wellnessz.common.model.DisclosureLevel;
import com.wellnessz.masterdata.Employee;
import com.wellnessz.masterdata.EmployeeRepository;
import com.wellnessz.masterdata.OrgUnit;
import com.wellnessz.masterdata.OrgUnitRepository;
import com.wellnessz.security.abac.AccessDecision;
import com.wellnessz.security.abac.AccessPolicy;
import com.wellnessz.security.abac.PolicyEngine;
import com.wellnessz.security.abac.ResourceAttributes;
import com.wellnessz.security.audit.AuditService;
import com.wellnessz.security.identity.CurrentUser;

/**
 * ABAC for employee-level records that are not cases (OH chart, encounters, results, documents, fitness decisions).
 * The employee's org path comes from the HRIS master; denied access is audited and reported as 404.
 */
@Component
public class EmployeeRecordAccess {

    private final EmployeeRepository employees;
    private final OrgUnitRepository orgUnits;
    private final PolicyEngine policy;
    private final AuditService audit;

    public EmployeeRecordAccess(EmployeeRepository employees, OrgUnitRepository orgUnits, PolicyEngine policy, AuditService audit) {
        this.employees = employees;
        this.orgUnits = orgUnits;
        this.policy = policy;
        this.audit = audit;
    }

    public Employee employee(String employeeId) {
        return employees.findById(employeeId).orElseThrow(() -> ApiException.notFound("Employee"));
    }

    public ResourceAttributes attributes(String resourceType, Employee e, DataClassification classification) {
        String path = orgUnits.findById(e.getDepartmentCode()).map(OrgUnit::getPath).orElse(null);
        return new ResourceAttributes(resourceType, e.getEmployeeId(), path, classification);
    }

    public AccessDecision decide(CurrentUser user, AccessPolicy.Action action, String resourceType, Employee e,
                                 DataClassification classification) {
        return policy.decide(user, action, attributes(resourceType, e, classification));
    }

    /** Clinical access (OH team, or break-glass / psych for the restricted class); otherwise audited 404. */
    public Employee requireClinical(CurrentUser user, String employeeId, DataClassification classification, AccessPolicy.Action action,
                                    String auditAction) {
        return require(user, employeeId, classification, action, DisclosureLevel.CLINICAL, auditAction);
    }

    /** At least status-only access (manager / HR within org scope, or OH team). */
    public Employee requireStatus(CurrentUser user, String employeeId, String auditAction) {
        return require(user, employeeId, DataClassification.CONFIDENTIAL, AccessPolicy.Action.READ, DisclosureLevel.STATUS_ONLY, auditAction);
    }

    public boolean canSeeClinical(CurrentUser user, Employee e, DataClassification classification) {
        AccessDecision d = decide(user, AccessPolicy.Action.READ, "EMPLOYEE_RECORD", e, classification);
        return d.permitted() && d.disclosure().atLeast(classification == DataClassification.RESTRICTED_CLINICAL
                ? DisclosureLevel.RESTRICTED_CLINICAL : DisclosureLevel.CLINICAL);
    }

    private Employee require(CurrentUser user, String employeeId, DataClassification classification, AccessPolicy.Action action,
                             DisclosureLevel minimum, String auditAction) {
        Employee e = employees.findById(employeeId).orElse(null);
        if (e == null) {
            audit.record(auditAction, "EMPLOYEE", employeeId, AuditService.Outcome.DENIED, "Unknown employee");
            throw ApiException.notFound("Employee");
        }
        DisclosureLevel needed = classification == DataClassification.RESTRICTED_CLINICAL && minimum == DisclosureLevel.CLINICAL
                ? DisclosureLevel.RESTRICTED_CLINICAL : minimum;
        AccessDecision d = decide(user, action, "EMPLOYEE_RECORD", e, classification);
        if (!d.permitted() || !d.disclosure().atLeast(needed)) {
            audit.record(auditAction, "EMPLOYEE", employeeId, AuditService.Outcome.DENIED, d.reason());
            throw ApiException.notFound("Employee");
        }
        return e;
    }
}
