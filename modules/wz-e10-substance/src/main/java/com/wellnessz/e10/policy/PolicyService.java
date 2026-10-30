package com.wellnessz.e10.policy;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.wellnessz.common.error.ApiException;
import com.wellnessz.e10.model.E10PolicyRepository;
import com.wellnessz.e10.model.SubstanceEnums.ApprovalStatus;
import com.wellnessz.e10.model.SubstanceEnums.Panel;
import com.wellnessz.e10.model.SubstanceEnums.RoleGroup;
import com.wellnessz.e10.model.TestingPolicy;
import com.wellnessz.e10.support.SubstanceSupport;
import com.wellnessz.e10.support.TestingViews.PolicyView;
import com.wellnessz.e10.support.TestingViews.PoolExclusionView;
import com.wellnessz.e10.support.TestingViews.PoolMember;
import com.wellnessz.e10.support.TestingViews.PoolView;
import com.wellnessz.masterdata.Employee;
import com.wellnessz.security.audit.AuditService;
import com.wellnessz.security.identity.CurrentUser;

/**
 * Testing policies by role group and the pools derived from HRIS job codes (E10-F01). Policies are seeded pending Legal /
 * Safety / HR approval (E10-BL-010); any change of a parameter returns the policy to PENDING unless the same update records
 * the approval.
 */
@Service("e10PolicyService")
public class PolicyService {

    private final E10PolicyRepository policies;
    private final SubstanceSupport support;
    private final JdbcTemplate jdbc;
    private final AuditService audit;

    public PolicyService(E10PolicyRepository policies, SubstanceSupport support, JdbcTemplate jdbc, AuditService audit) {
        this.policies = policies;
        this.support = support;
        this.jdbc = jdbc;
        this.audit = audit;
    }

    public record PolicyUpdate(List<String> jobCodes, List<String> panels, BigDecimal randomDrugRatePct, BigDecimal randomAlcoholRatePct,
                               Integer alcoholWindowHours, Integer drugWindowHours, Integer leadTimeMinutes, String jurisdiction,
                               Boolean consentRequired, String approvalStatus, String approvedBy) {
    }

    /** An active pool exclusion. */
    public record Exclusion(String employeeId, String reason, LocalDate until) {
    }

    // ------------------------------------------------------------------ policies

    @Transactional(readOnly = true)
    public List<PolicyView> list() {
        return policies.findAllByOrderByRoleGroupAsc().stream().map(PolicyService::view).toList();
    }

    @Transactional(readOnly = true)
    public PolicyView get(String roleGroup) {
        return view(policy(roleGroup));
    }

    public TestingPolicy policy(String roleGroup) {
        return policies.findById(roleGroup).orElseThrow(() -> ApiException.notFound("Policy"));
    }

    @Transactional
    public PolicyView update(CurrentUser user, String roleGroup, PolicyUpdate u) {
        TestingPolicy p = policy(roleGroup);
        boolean changed = false;
        if (u.jobCodes() != null) {
            changed |= set(p.getJobCodes(), String.join(",", new LinkedHashSet<>(u.jobCodes())), p::setJobCodes);
        }
        if (u.panels() != null) {
            List<String> panels = new ArrayList<>(new LinkedHashSet<>(u.panels()));
            if (panels.isEmpty()) {
                throw SubstanceSupport.unprocessable("panels_required", "At least one panel is required");
            }
            changed |= set(p.getPanels(), String.join(",", panels), p::setPanels);
        }
        if (u.randomDrugRatePct() != null && u.randomDrugRatePct().compareTo(p.getRandomDrugRatePct()) != 0) {
            p.setRandomDrugRatePct(u.randomDrugRatePct());
            changed = true;
        }
        if (u.randomAlcoholRatePct() != null && u.randomAlcoholRatePct().compareTo(p.getRandomAlcoholRatePct()) != 0) {
            p.setRandomAlcoholRatePct(u.randomAlcoholRatePct());
            changed = true;
        }
        if (u.alcoholWindowHours() != null && u.alcoholWindowHours() != p.getAlcoholWindowHours()) {
            p.setAlcoholWindowHours(u.alcoholWindowHours());
            changed = true;
        }
        if (u.drugWindowHours() != null && u.drugWindowHours() != p.getDrugWindowHours()) {
            p.setDrugWindowHours(u.drugWindowHours());
            changed = true;
        }
        if (p.getAlcoholWindowHours() > p.getDrugWindowHours()) {
            throw SubstanceSupport.unprocessable("invalid_windows", "The alcohol window cannot be longer than the drug window");
        }
        if (u.leadTimeMinutes() != null && u.leadTimeMinutes() != p.getLeadTimeMinutes()) {
            p.setLeadTimeMinutes(u.leadTimeMinutes());
            changed = true;
        }
        if (u.jurisdiction() != null) {
            changed |= set(p.getJurisdiction(), u.jurisdiction().strip(), p::setJurisdiction);
        }
        if (u.consentRequired() != null && u.consentRequired() != p.isConsentRequired()) {
            p.setConsentRequired(u.consentRequired());
            changed = true;
        }
        if (ApprovalStatus.APPROVED.name().equals(u.approvalStatus())) {
            if (u.approvedBy() == null || u.approvedBy().isBlank()) {
                throw SubstanceSupport.unprocessable("approver_required", "approvedBy is required to approve a policy");
            }
            p.setApprovalStatus(ApprovalStatus.APPROVED.name());
            p.setApprovedBy(u.approvedBy().strip());
            p.setApprovedAt(support.now());
        } else if (changed || ApprovalStatus.PENDING.name().equals(u.approvalStatus())) {
            p.setApprovalStatus(ApprovalStatus.PENDING.name());
            p.setApprovedBy(null);
            p.setApprovedAt(null);
        }
        p.setUpdatedBy(user.username());
        p.setUpdatedAt(support.now());
        TestingPolicy saved = policies.saveAndFlush(p);
        audit.record("DA_POLICY_UPDATED", "DA_POLICY", roleGroup, AuditService.Outcome.SUCCESS, saved.getApprovalStatus());
        return view(saved);
    }

    private static boolean set(String current, String next, java.util.function.Consumer<String> setter) {
        if (next.equals(current)) {
            return false;
        }
        setter.accept(next);
        return true;
    }

    public static PolicyView view(TestingPolicy p) {
        return new PolicyView(p.getRoleGroup(), SubstanceSupport.split(p.getJobCodes()), SubstanceSupport.split(p.getPanels()),
                p.getRandomDrugRatePct(), p.getRandomAlcoholRatePct(), p.getAlcoholWindowHours(), p.getDrugWindowHours(), p.getLeadTimeMinutes(),
                p.getJurisdiction(), p.isConsentRequired(), p.getApprovalStatus(), p.getApprovedBy(), p.getApprovedAt(),
                p.getVersion() == null ? 0 : p.getVersion(), p.getUpdatedAt());
    }

    public static List<String> drugPanels(TestingPolicy p) {
        return SubstanceSupport.split(p.getPanels()).stream().filter(c -> !Panel.valueOf(c).alcohol()).toList();
    }

    public static List<String> alcoholPanels(TestingPolicy p) {
        return SubstanceSupport.split(p.getPanels()).stream().filter(c -> Panel.valueOf(c).alcohol()).toList();
    }

    // ------------------------------------------------------------------ pools

    /** Role group of an employee from the HRIS job code (first policy listing it), if any. */
    public Optional<TestingPolicy> policyFor(Employee e) {
        if (e == null || e.getJobCode() == null) {
            return Optional.empty();
        }
        return policies.findAllByOrderByRoleGroupAsc().stream().filter(p -> SubstanceSupport.split(p.getJobCodes()).contains(e.getJobCode()))
                .findFirst();
    }

    /** All active employees whose job code belongs to the role group (before exclusions). */
    public List<Employee> members(TestingPolicy p) {
        Set<String> codes = Set.copyOf(SubstanceSupport.split(p.getJobCodes()));
        return support.allEmployees().stream().filter(Employee::isActive).filter(e -> e.getJobCode() != null && codes.contains(e.getJobCode()))
                .sorted(Comparator.comparing(Employee::getEmployeeId)).toList();
    }

    public List<Exclusion> activeExclusions(String roleGroup, LocalDate on) {
        return jdbc.query("""
                select employee_id, reason, until from substance.pool_exclusion where role_group = ? and until >= ? order by until desc""",
                (rs, i) -> new Exclusion(rs.getString(1), rs.getString(2), rs.getDate(3).toLocalDate()), roleGroup, Date.valueOf(on));
    }

    /** Eligible pool on a date: members minus active exclusions (employee ids). */
    public List<String> eligible(TestingPolicy p, LocalDate on) {
        Set<String> excluded = activeExclusions(p.getRoleGroup(), on).stream().map(Exclusion::employeeId).collect(Collectors.toSet());
        return members(p).stream().map(Employee::getEmployeeId).filter(id -> !excluded.contains(id)).toList();
    }

    @Transactional(readOnly = true)
    public List<PoolView> pools(CurrentUser user, String roleGroup) {
        List<TestingPolicy> list = roleGroup == null ? policies.findAllByOrderByRoleGroupAsc() : List.of(policy(roleGroup));
        LocalDate today = support.today();
        List<PoolView> out = new ArrayList<>();
        for (TestingPolicy p : list) {
            List<Exclusion> ex = activeExclusions(p.getRoleGroup(), today);
            Set<String> excluded = ex.stream().map(Exclusion::employeeId).collect(Collectors.toSet());
            List<PoolMember> members = members(p).stream().filter(e -> !excluded.contains(e.getEmployeeId()))
                    .map(e -> new PoolMember(e.getEmployeeId(), e.getDisplayName(), e.getJobCode())).toList();
            out.add(new PoolView(p.getRoleGroup(), members.size(), members,
                    ex.stream().map(x -> new PoolExclusionView(x.employeeId(), support.name(x.employeeId()), x.reason(), x.until())).toList()));
        }
        audit.record("DA_POOLS_READ", "DA_POOL", roleGroup, AuditService.Outcome.SUCCESS, "groups=" + out.size());
        return out;
    }

    @Transactional
    public PoolExclusionView exclude(CurrentUser user, String roleGroup, String employeeId, String reason, LocalDate until) {
        TestingPolicy p = policy(RoleGroup.valueOf(roleGroup).name());
        if (until.isBefore(support.today())) {
            throw SubstanceSupport.unprocessable("until_in_past", "The exclusion must end today or later");
        }
        boolean member = members(p).stream().anyMatch(e -> e.getEmployeeId().equals(employeeId));
        if (!member) {
            throw SubstanceSupport.unprocessable("not_in_pool", "The employee is not in this testing pool");
        }
        jdbc.update("""
                insert into substance.pool_exclusion (id, role_group, employee_id, reason, until, created_by, created_at)
                values (?, ?, ?, ?, ?, ?, ?)""", UUID.randomUUID(), roleGroup, employeeId, reason, Date.valueOf(until), user.username(),
                Timestamp.from(support.now()));
        audit.record("DA_POOL_EXCLUSION", "EMPLOYEE", employeeId, AuditService.Outcome.SUCCESS, roleGroup + ":" + reason);
        return new PoolExclusionView(employeeId, support.name(employeeId), reason, until);
    }

    /** Policy summary used by the views of other services. */
    public Map<String, TestingPolicy> byRoleGroup() {
        return policies.findAllByOrderByRoleGroupAsc().stream().collect(Collectors.toMap(TestingPolicy::getRoleGroup, p -> p));
    }
}
