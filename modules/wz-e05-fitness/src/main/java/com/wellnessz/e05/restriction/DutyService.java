package com.wellnessz.e05.restriction;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.wellnessz.casekernel.model.CaseTask;
import com.wellnessz.casekernel.model.OhCase;
import com.wellnessz.casekernel.support.CaseAccess;
import com.wellnessz.casekernel.support.CaseTimeline;
import com.wellnessz.casekernel.support.CaseWorkService;
import com.wellnessz.casekernel.support.EmployeeRecordAccess;
import com.wellnessz.common.error.ApiException;
import com.wellnessz.common.model.DataClassification;
import com.wellnessz.common.model.Roles;
import com.wellnessz.e05.model.DutyProposal;
import com.wellnessz.e05.model.DutyProposalRepository;
import com.wellnessz.e05.support.FitnessSupport;
import com.wellnessz.e05.support.RestrictionRef;
import com.wellnessz.masterdata.Employee;
import com.wellnessz.masterdata.fitness.JobDemandProfile;
import com.wellnessz.masterdata.fitness.JobDemandProfileRepository;
import com.wellnessz.masterdata.fitness.Restriction;
import com.wellnessz.security.abac.AccessPolicy;
import com.wellnessz.security.audit.AuditService;
import com.wellnessz.security.identity.CurrentUser;

import tools.jackson.core.type.TypeReference;

/**
 * Job-demand matching and modified-duty proposals (E05-BL-005/006): OH matches a restriction set against the job-demand
 * library, proposes a modified duty to the line manager, who accepts or declines with a reason code; when every proposal
 * of a case has been declined the case is escalated to HR with a task.
 */
@Service
public class DutyService {

    static final Set<String> REASON_CODES = Set.of("NO_SUITABLE_WORK", "OPERATIONAL", "SAFETY", "COST", "OTHER");
    private static final TypeReference<Map<String, Object>> DEMANDS = new TypeReference<>() {
    };

    private final DutyProposalRepository proposals;
    private final JobDemandProfileRepository jobDemands;
    private final RestrictionService restrictions;
    private final CaseAccess access;
    private final EmployeeRecordAccess records;
    private final CaseTimeline timeline;
    private final CaseWorkService work;
    private final FitnessSupport support;
    private final AuditService audit;

    public DutyService(DutyProposalRepository proposals, JobDemandProfileRepository jobDemands, RestrictionService restrictions,
                       CaseAccess access, EmployeeRecordAccess records, CaseTimeline timeline, CaseWorkService work, FitnessSupport support,
                       AuditService audit) {
        this.proposals = proposals;
        this.jobDemands = jobDemands;
        this.restrictions = restrictions;
        this.access = access;
        this.records = records;
        this.timeline = timeline;
        this.work = work;
        this.support = support;
        this.audit = audit;
    }

    // ------------------------------------------------------------------ matching

    @Transactional(readOnly = true)
    public List<MatchingEngine.Match> match(CurrentUser user, String employeeId, List<RestrictionRef.Input> inputs, String unit) {
        Employee e = records.requireClinical(user, employeeId, DataClassification.CLINICAL, AccessPolicy.Action.READ, "FITNESS_MATCH");
        List<RestrictionRef.View> views = support.resolve(inputs);
        if (views.isEmpty()) {
            throw ApiException.badRequest("restrictions_required", "Select at least one restriction to match");
        }
        List<MatchingEngine.Rule> rules = views.stream().map(v -> new MatchingEngine.Rule(v.code(),
                support.catalogueEntry(v.code()).map(Restriction::getDimension).orElse(v.code()), v.value(), v.label())).toList();
        List<JobDemandProfile> library = unit == null ? jobDemands.findAllByOrderByUnitAscJobCodeAsc() : jobDemands.findByUnitOrderByJobCodeAsc(unit);
        List<MatchingEngine.Profile> profiles = library.stream().map(p -> new MatchingEngine.Profile(p.getJobCode(), p.getTitle(), p.getUnit(),
                support.readJson(p.getDemands(), DEMANDS))).toList();
        String employeeUnit = e.getJobCode() == null ? e.getSubsidiaryCode()
                : jobDemands.findById(e.getJobCode()).map(JobDemandProfile::getUnit).orElse(e.getSubsidiaryCode());
        List<MatchingEngine.Match> ranked = MatchingEngine.rank(rules, profiles, employeeUnit);
        audit.record("FITNESS_MATCH", "EMPLOYEE", employeeId, AuditService.Outcome.SUCCESS, "restrictions=" + views.size());
        return ranked;
    }

    // ------------------------------------------------------------------ proposals

    public record ProposalCommand(String jobCode, String duties, BigDecimal hoursPerDay, LocalDate startDate, LocalDate endDate) {
    }

    public record ProposalView(UUID id, UUID caseId, String caseRef, String employeeId, String employeeName, String jobCode, String jobTitle,
                               String duties, List<RestrictionRef.View> restrictions, BigDecimal hoursPerDay, LocalDate startDate,
                               LocalDate endDate, String status, String proposedBy, Instant proposedAt, String decidedBy, Instant decidedAt,
                               String reasonCode, String note) {
    }

    @Transactional
    public ProposalView propose(CurrentUser user, UUID caseId, ProposalCommand cmd) {
        OhCase c = access.clinical(user, caseId, AccessPolicy.Action.UPDATE, "DUTY_PROPOSAL");
        JobDemandProfile job = jobDemands.findById(cmd.jobCode())
                .orElseThrow(() -> ApiException.badRequest("unknown_job_code", "Unknown job code"));
        String duties = FitnessSupport.functional("duties", cmd.duties(), 1000);
        if (duties == null) {
            throw ApiException.badRequest("duties_required", "Describe the modified duties");
        }
        if (cmd.startDate() == null || (cmd.endDate() != null && cmd.endDate().isBefore(cmd.startDate()))) {
            throw ApiException.badRequest("invalid_dates", "startDate is required and endDate must not be before it");
        }
        if (cmd.hoursPerDay() != null && (cmd.hoursPerDay().signum() <= 0 || cmd.hoursPerDay().compareTo(BigDecimal.valueOf(24)) > 0)) {
            throw ApiException.badRequest("invalid_hours", "hoursPerDay must be between 0 and 24");
        }
        DutyProposal p = DutyProposal.create();
        p.setCaseId(c.getId());
        p.setCaseRef(c.getCaseRef());
        p.setEmployeeId(c.getEmployeeId());
        p.setJobCode(job.getJobCode());
        p.setJobTitle(job.getTitle());
        p.setDuties(duties);
        p.setRestrictions(support.write(restrictions.activeForCase(c.getId())));
        p.setHoursPerDay(cmd.hoursPerDay());
        p.setStartDate(cmd.startDate());
        p.setEndDate(cmd.endDate());
        p.setStatus("PROPOSED");
        p.setProposedBy(user.username());
        p.setProposedAt(support.clock().instant());
        proposals.save(p);
        support.notifyManagerOf(c.getEmployeeId(), "duty.proposal",
                Map.of("employeeName", support.employeeName(c.getEmployeeId()), "caseRef", c.getCaseRef()));
        timeline.record(c.getId(), "DUTY_PROPOSED", "Modified duty proposed to the line manager: " + job.getTitle());
        audit.record("DUTY_PROPOSAL", "CASE", caseId.toString(), AuditService.Outcome.SUCCESS, job.getJobCode());
        return view(p);
    }

    @Transactional(readOnly = true)
    public List<ProposalView> forCase(CurrentUser user, UUID caseId) {
        access.clinical(user, caseId, AccessPolicy.Action.READ, "DUTY_PROPOSALS_READ");
        return proposals.findByCaseIdOrderByProposedAtAsc(caseId).stream().map(this::view).toList();
    }

    /** Manager (own scope) / HR task list – functional content only, access audited. */
    @Transactional(readOnly = true)
    public List<ProposalView> workspace(CurrentUser user, String status) {
        if (!user.hasAnyRole(Roles.MANAGER, Roles.HR)) {
            throw ApiException.forbidden("Managers and HR only");
        }
        List<ProposalView> out = new ArrayList<>();
        for (DutyProposal p : proposals.findByStatusOrderByProposedAtAsc(status == null ? "PROPOSED" : status)) {
            Employee e = support.employee(p.getEmployeeId()).orElse(null);
            if (e != null && restrictions.inScope(user, e)) {
                out.add(view(p));
            }
        }
        audit.record("DUTY_PROPOSALS_WORKSPACE_READ", "DUTY_PROPOSAL", null, AuditService.Outcome.SUCCESS, "rows=" + out.size());
        return out;
    }

    @Transactional
    public ProposalView decide(CurrentUser user, UUID id, boolean accept, String reasonCode, String note) {
        if (!user.hasRole(Roles.MANAGER)) {
            throw ApiException.forbidden("Line managers decide modified-duty proposals");
        }
        DutyProposal p = proposals.findById(id).orElseThrow(() -> ApiException.notFound("Duty proposal"));
        Employee e = support.employee(p.getEmployeeId()).orElse(null);
        if (e == null || !restrictions.inScope(user, e)) {
            audit.record("DUTY_PROPOSAL_DECISION", "DUTY_PROPOSAL", id.toString(), AuditService.Outcome.DENIED, "Outside scope");
            throw ApiException.notFound("Duty proposal");
        }
        if (!"PROPOSED".equals(p.getStatus())) {
            throw ApiException.conflict("already_decided", "This proposal has already been decided");
        }
        if (!accept && (reasonCode == null || !REASON_CODES.contains(reasonCode))) {
            throw ApiException.badRequest("reason_required", "A reason code is required when declining");
        }
        if (reasonCode != null && !REASON_CODES.contains(reasonCode)) {
            throw ApiException.badRequest("invalid_reason_code", "Unknown reason code");
        }
        Instant now = support.clock().instant();
        p.setStatus(accept ? "ACCEPTED" : "DECLINED");
        p.setDecidedBy(user.username());
        p.setDecidedAt(now);
        p.setReasonCode(reasonCode);
        p.setNote(FitnessSupport.functional("note", note, 1000));
        timeline.record(p.getCaseId(), "DUTY_DECISION", "Line manager " + (accept ? "accepted" : "declined (" + reasonCode + ")")
                + " the modified duty " + p.getJobTitle());
        if (!accept) {
            List<DutyProposal> all = proposals.findByCaseIdOrderByProposedAtAsc(p.getCaseId());
            boolean allDeclined = all.stream().allMatch(x -> "DECLINED".equals(x.getStatus()) || "ESCALATED_TO_HR".equals(x.getStatus())
                    || "WITHDRAWN".equals(x.getStatus()));
            if (allDeclined) {
                all.stream().filter(x -> "DECLINED".equals(x.getStatus())).forEach(x -> x.setStatus("ESCALATED_TO_HR"));
                CaseTask t = work.systemTask(p.getCaseId(), CaseTask.Type.FOLLOW_UP,
                        "HR: no suitable modified duty available – review alternative duties / job offer", now.plus(Duration.ofDays(2)),
                        Roles.HR, "system");
                timeline.record(p.getCaseId(), "DUTY_ESCALATED_HR", "All modified-duty proposals declined – escalated to HR (task " + t.getId() + ")");
            }
        }
        audit.record("DUTY_PROPOSAL_DECISION", "DUTY_PROPOSAL", id.toString(), AuditService.Outcome.SUCCESS, p.getStatus());
        return view(p);
    }

    private ProposalView view(DutyProposal p) {
        return new ProposalView(p.getId(), p.getCaseId(), p.getCaseRef(), p.getEmployeeId(), support.employeeName(p.getEmployeeId()),
                p.getJobCode(), p.getJobTitle(), p.getDuties(), support.read(p.getRestrictions()), p.getHoursPerDay(), p.getStartDate(),
                p.getEndDate(), p.getStatus(), p.getProposedBy(), p.getProposedAt(), p.getDecidedBy(), p.getDecidedAt(), p.getReasonCode(),
                p.getNote());
    }
}
