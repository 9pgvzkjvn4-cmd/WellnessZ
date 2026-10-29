package com.wellnessz.e08.collab;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.wellnessz.casekernel.model.OhCase;
import com.wellnessz.casekernel.model.OhCaseRepository;
import com.wellnessz.common.error.ApiException;
import com.wellnessz.common.model.Roles;
import com.wellnessz.common.text.ClinicalTextDetector;
import com.wellnessz.e05.model.RtwPlan;
import com.wellnessz.e05.model.RtwPlanRepository;
import com.wellnessz.e05.model.RtwReview;
import com.wellnessz.e05.model.RtwReviewRepository;
import com.wellnessz.e08.disclosure.DisclosureSetService;
import com.wellnessz.e08.model.Claim;
import com.wellnessz.e08.model.ClaimEnums.AssigneeRole;
import com.wellnessz.e08.model.ClaimEnums.TaskStatus;
import com.wellnessz.e08.model.ClaimMessage;
import com.wellnessz.e08.model.ClaimTask;
import com.wellnessz.e08.model.E08ClaimMessageRepository;
import com.wellnessz.e08.model.E08ClaimRepository;
import com.wellnessz.e08.model.E08ClaimTaskRepository;
import com.wellnessz.e08.support.ClaimAccess;
import com.wellnessz.e08.support.ClaimsSupport;
import com.wellnessz.notification.Priority;
import com.wellnessz.security.audit.AuditService;
import com.wellnessz.security.disclosure.DisclosureLogService;
import com.wellnessz.security.identity.CurrentUser;

import tools.jackson.core.type.TypeReference;

/**
 * Nurse case manager ↔ adjuster collaboration (E08-F04): shared tasks and secure messages (screened, encrypted, content-free
 * notifications), the status-only RTW view from E05 (fitness status, restriction end, plan phases with functional duties –
 * never clinical notes) and the partner-portal claim list / detail for TPA adjusters of the payer organisation.
 */
@Service
public class CollaborationService {

    private static final TypeReference<List<Map<String, Object>>> PHASES = new TypeReference<>() {
    };

    public record TaskView(UUID id, String title, LocalDate dueDate, String assigneeRole, String status, String createdBy, Instant createdAt,
                           Instant completedAt) {
    }

    public record MessageView(UUID id, String sender, String senderRole, String text, Instant sentAt) {
    }

    public record Phase(LocalDate from, LocalDate to, BigDecimal hoursPerDay, String dutiesSummary) {
    }

    public record RtwPlanView(String status, LocalDate plannedStart, List<Phase> phases, LocalDate nextReview) {
    }

    public record RtwView(String fitnessStatus, LocalDate restrictionUntil, RtwPlanView rtwPlan) {
    }

    public record PartnerClaim(UUID id, String claimRef, String claimType, String status, String displayName, LocalDate incidentDate,
                               boolean lostTime, long openTasks, long unreadMessages) {
    }

    public record PartnerClaimDetail(UUID id, String claimRef, String claimType, String status, String displayName, LocalDate incidentDate,
                                     boolean lostTime, String policyNumber, String nurseCaseManager, long openTasks, long unreadMessages, RtwView rtw,
                                     List<TaskView> tasks, List<Map<String, Object>> documents) {
    }

    private final E08ClaimRepository claims;
    private final E08ClaimTaskRepository tasks;
    private final E08ClaimMessageRepository messages;
    private final OhCaseRepository cases;
    private final RtwPlanRepository plans;
    private final RtwReviewRepository reviews;
    private final DisclosureSetService disclosureSets;
    private final ClaimAccess access;
    private final ClaimsSupport support;
    private final DisclosureLogService disclosures;
    private final JdbcTemplate jdbc;
    private final AuditService audit;

    public CollaborationService(E08ClaimRepository claims, E08ClaimTaskRepository tasks, E08ClaimMessageRepository messages, OhCaseRepository cases,
                                RtwPlanRepository plans, RtwReviewRepository reviews, DisclosureSetService disclosureSets, ClaimAccess access,
                                ClaimsSupport support, DisclosureLogService disclosures, JdbcTemplate jdbc, AuditService audit) {
        this.claims = claims;
        this.tasks = tasks;
        this.messages = messages;
        this.cases = cases;
        this.plans = plans;
        this.reviews = reviews;
        this.disclosureSets = disclosureSets;
        this.access = access;
        this.support = support;
        this.disclosures = disclosures;
        this.jdbc = jdbc;
        this.audit = audit;
    }

    // ------------------------------------------------------------------ tasks

    @Transactional(readOnly = true)
    public List<TaskView> tasks(CurrentUser user, UUID claimId) {
        access.require(user, claimId, ClaimAccess.participant(user), "CLAIM_TASKS_READ");
        return tasks.findByClaimIdOrderByDueDateAscCreatedAtAsc(claimId).stream().map(CollaborationService::view).toList();
    }

    @Transactional
    public TaskView createTask(CurrentUser user, UUID claimId, String title, LocalDate dueDate, String assigneeRole) {
        Claim c = access.require(user, claimId, ClaimAccess.participant(user), "CLAIM_TASK_CREATE");
        String t = ClaimsSupport.screened("title", title, 200);
        if (t == null) {
            throw ApiException.badRequest("title_required", "A title is required");
        }
        AssigneeRole role = role(assigneeRole);
        if (dueDate == null || dueDate.isBefore(support.today().minusDays(1)) || dueDate.isAfter(support.today().plusYears(2))) {
            throw ApiException.badRequest("invalid_due_date", "dueDate must be between today and two years ahead");
        }
        ClaimTask task = ClaimTask.create();
        task.setClaimId(claimId);
        task.setTitle(t);
        task.setDueDate(dueDate);
        task.setAssigneeRole(role.name());
        task.setStatus(TaskStatus.OPEN.name());
        task.setCreatedBy(user.username());
        task.setCreatedAt(support.now());
        tasks.save(task);
        Map<String, String> params = Map.of("reference", c.getClaimRef(), "dueDate", dueDate.toString());
        switch (role) {
            case CLAIMS -> support.notifyClaimsTeam("claim.task", params, "#/claims/" + c.getId(), Priority.NORMAL);
            case NURSE_CM -> {
                if (c.getNurseUsername() != null) {
                    support.notifyStaff(c.getNurseUsername(), "claim.task", params, "#/claims/" + c.getId());
                }
            }
            case ADJUSTER -> support.notifyAdjuster(c.getAdjusterEmail(), c.getPayerCode(), "claim.task", params, "#/claims/" + c.getId());
        }
        audit.record("CLAIM_TASK_CREATE", "CLAIM", claimId.toString(), AuditService.Outcome.SUCCESS, role.name());
        return view(task);
    }

    @Transactional
    public TaskView completeTask(CurrentUser user, UUID taskId) {
        ClaimTask task = tasks.findById(taskId).orElseThrow(() -> ApiException.notFound("Task"));
        Claim c = access.require(user, task.getClaimId(), ClaimAccess.participant(user), "CLAIM_TASK_COMPLETE");
        AssigneeRole role = AssigneeRole.valueOf(task.getAssigneeRole());
        boolean assigneeSide = switch (role) {
            case CLAIMS -> user.hasRole(Roles.CLAIMS_COORDINATOR);
            case NURSE_CM -> user.hasRole(Roles.CASE_MANAGER) || ClaimAccess.isAssignedNurse(user, c);
            case ADJUSTER -> ClaimAccess.isPayerAdjuster(user, c);
        };
        if (!assigneeSide) {
            throw ApiException.forbidden("Only the assignee side can complete this task");
        }
        if (TaskStatus.DONE.name().equals(task.getStatus())) {
            return view(task);
        }
        task.setStatus(TaskStatus.DONE.name());
        task.setCompletedAt(support.now());
        task.setCompletedBy(user.username());
        audit.record("CLAIM_TASK_COMPLETE", "CLAIM", c.getId().toString(), AuditService.Outcome.SUCCESS, role.name());
        return view(task);
    }

    // ------------------------------------------------------------------ messages

    @Transactional
    public List<MessageView> messages(CurrentUser user, UUID claimId) {
        access.require(user, claimId, ClaimAccess.participant(user), "CLAIM_MESSAGES_READ");
        markRead(claimId, user.username());
        audit.record("CLAIM_MESSAGES_READ", "CLAIM", claimId.toString(), AuditService.Outcome.SUCCESS, null);
        return messages.findByClaimIdOrderBySentAtAsc(claimId).stream()
                .map(m -> new MessageView(m.getId(), m.getSender(), m.getSenderRole(), m.getBody(), m.getSentAt())).toList();
    }

    @Transactional
    public MessageView post(CurrentUser user, UUID claimId, String text) {
        Claim c = access.require(user, claimId, ClaimAccess.participant(user), "CLAIM_MESSAGE_POST");
        String body = ClaimsSupport.screened("text", text, 2000);
        if (body == null) {
            throw ApiException.badRequest("text_required", "A message text is required");
        }
        String side = side(user, c);
        ClaimMessage m = ClaimMessage.create();
        m.setClaimId(claimId);
        m.setSender(user.username());
        m.setSenderRole(side);
        m.setBody(body);
        m.setSentAt(support.now());
        messages.save(m);
        markRead(claimId, user.username());
        Map<String, String> params = Map.of("reference", c.getClaimRef());
        String route = "#/claims/" + c.getId();
        if (!AssigneeRole.ADJUSTER.name().equals(side)) {
            support.notifyAdjuster(c.getAdjusterEmail(), c.getPayerCode(), "claim.message", params, route);
        }
        if (!AssigneeRole.CLAIMS.name().equals(side)) {
            support.notifyClaimsTeam("claim.message", params, route, Priority.NORMAL);
        }
        if (!AssigneeRole.NURSE_CM.name().equals(side) && c.getNurseUsername() != null) {
            support.notifyStaff(c.getNurseUsername(), "claim.message", params, route);
        }
        audit.record("CLAIM_MESSAGE_POST", "CLAIM", claimId.toString(), AuditService.Outcome.SUCCESS, side);
        return new MessageView(m.getId(), m.getSender(), m.getSenderRole(), m.getBody(), m.getSentAt());
    }

    private static String side(CurrentUser user, Claim c) {
        if (ClaimAccess.isPayerAdjuster(user, c)) {
            return AssigneeRole.ADJUSTER.name();
        }
        if (user.hasRole(Roles.CLAIMS_COORDINATOR)) {
            return AssigneeRole.CLAIMS.name();
        }
        return AssigneeRole.NURSE_CM.name();
    }

    private void markRead(UUID claimId, String reader) {
        jdbc.update("""
                insert into claims.message_read (claim_id, reader, read_at) values (?, ?, ?)
                on conflict (claim_id, reader) do update set read_at = excluded.read_at""", claimId, reader, Timestamp.from(support.now()));
    }

    private long unread(UUID claimId, String reader) {
        Long n = jdbc.queryForObject("""
                select count(*) from claims.claim_message m
                 where m.claim_id = ? and m.sender <> ?
                   and m.sent_at > coalesce((select r.read_at from claims.message_read r where r.claim_id = m.claim_id and r.reader = ?),
                                            timestamp with time zone '1970-01-01 00:00:00+00')""", Long.class, claimId, reader, reader);
        return n == null ? 0 : n;
    }

    // ------------------------------------------------------------------ RTW (status only)

    @Transactional(readOnly = true)
    public RtwView rtw(CurrentUser user, UUID claimId) {
        Claim c = access.require(user, claimId, x -> user.hasAnyRole(Roles.CLAIMS_COORDINATOR, Roles.CASE_MANAGER) || ClaimAccess.isAssignedNurse(user, x)
                || ClaimAccess.isPayerOrg(user, x), "CLAIM_RTW_READ");
        RtwView v = rtwView(c);
        if (user.hasRole(Roles.TPA_ADJUSTER)) {
            logAdjusterDisclosure(user, c, v);
        }
        audit.record("CLAIM_RTW_READ", "CLAIM", claimId.toString(), AuditService.Outcome.SUCCESS, null);
        return v;
    }

    RtwView rtwView(Claim c) {
        OhCase oc = cases.findById(c.getCaseId()).orElse(null);
        String fitness = oc == null || oc.getFitnessStatus() == null ? "NOT_ASSESSED" : oc.getFitnessStatus().name();
        LocalDate until = oc == null ? null : oc.getRestrictionUntil();
        Optional<RtwPlan> plan = plans.findByCaseIdOrderByCreatedAtAsc(c.getCaseId()).stream()
                .filter(p -> Set.of("ACTIVE", "COMPLETED").contains(p.getStatus()))
                .max(Comparator.comparing((RtwPlan p) -> "ACTIVE".equals(p.getStatus())).thenComparing(RtwPlan::getCreatedAt));
        return new RtwView(fitness, until, plan.map(this::planView).orElse(null));
    }

    private RtwPlanView planView(RtwPlan p) {
        List<Phase> phases = new ArrayList<>();
        List<Map<String, Object>> raw = p.getPhases() == null || p.getPhases().isBlank() ? List.of() : support.read(p.getPhases(), PHASES);
        for (Map<String, Object> ph : raw) {
            int fromWeek = number(ph.get("fromWeek"), 1);
            int toWeek = number(ph.get("toWeek"), fromWeek);
            Object hours = ph.get("hoursPerDay");
            String duties = ph.get("duties") == null ? null : String.valueOf(ph.get("duties"));
            if (duties != null && (ClinicalTextDetector.looksClinical(duties) || duties.length() > 500)) {
                duties = null; // functional wording only reaches the adjuster
            }
            phases.add(new Phase(p.getStartDate().plusWeeks(fromWeek - 1L), p.getStartDate().plusWeeks(toWeek).minusDays(1),
                    hours == null ? null : new BigDecimal(String.valueOf(hours)), duties));
        }
        LocalDate next = reviews.findByPlanIdOrderByDueDateAsc(p.getId()).stream().filter(r -> r.getCompletedAt() == null)
                .map(RtwReview::getDueDate).findFirst().orElse(null);
        return new RtwPlanView(p.getStatus(), p.getStartDate(), phases, next);
    }

    private static int number(Object v, int fallback) {
        if (v instanceof Number n) {
            return n.intValue();
        }
        try {
            return v == null ? fallback : Integer.parseInt(String.valueOf(v));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /** RTW status / milestones shared with the payer: disclosure-logged once per adjuster and status. */
    private void logAdjusterDisclosure(CurrentUser user, Claim c, RtwView v) {
        String recipient = "Insurer / TPA " + c.getPayerCode() + " (" + user.username() + ")";
        String title = "RTW status " + c.getClaimRef() + " (" + v.fitnessStatus() + (v.rtwPlan() == null ? "" : ", plan " + v.rtwPlan().status()) + ")";
        if (!support.alreadyDisclosed("CLAIM_RTW_STATUS", c.getId().toString(), recipient, title)) {
            disclosures.record(c.getEmployeeId(), "INSURER", recipient, "CLAIM_RTW_STATUS", c.getId().toString(), title, "CLAIM", null,
                    user.username());
        }
    }

    // ------------------------------------------------------------------ partner portal

    @Transactional(readOnly = true)
    public List<PartnerClaim> partnerClaims(CurrentUser user, String status) {
        String org = ClaimsSupport.partnerOrg(user);
        if (org == null) {
            return List.of();
        }
        List<PartnerClaim> out = new ArrayList<>();
        for (Claim c : claims.findByPayerCodeOrderByNotifiedAtDesc(org)) {
            if (status != null && !status.equals(c.getStatus())) {
                continue;
            }
            out.add(new PartnerClaim(c.getId(), c.getClaimRef(), c.getClaimType(), c.getStatus(), support.name(c.getEmployeeId()), c.getIncidentDate(),
                    c.isLostTime(), tasks.countByClaimIdAndStatus(c.getId(), TaskStatus.OPEN.name()), unread(c.getId(), user.username())));
        }
        audit.record("PARTNER_CLAIMS_READ", "CLAIM", null, AuditService.Outcome.SUCCESS, "partner=" + org + " rows=" + out.size());
        return out;
    }

    @Transactional
    public PartnerClaimDetail partnerClaim(CurrentUser user, UUID claimId) {
        Claim c = access.require(user, claimId, x -> ClaimAccess.isPayerOrg(user, x), "PARTNER_CLAIM_READ");
        RtwView rtw = rtwView(c);
        logAdjusterDisclosure(user, c, rtw);
        List<TaskView> ts = tasks.findByClaimIdOrderByDueDateAscCreatedAtAsc(claimId).stream().map(CollaborationService::view).toList();
        audit.record("PARTNER_CLAIM_READ", "CLAIM", claimId.toString(), AuditService.Outcome.SUCCESS, "partner=" + c.getPayerCode());
        return new PartnerClaimDetail(c.getId(), c.getClaimRef(), c.getClaimType(), c.getStatus(), support.name(c.getEmployeeId()), c.getIncidentDate(),
                c.isLostTime(), c.getPolicyNumber(), c.getNurseUsername() == null ? null : support.staffName(c.getNurseUsername()),
                ts.stream().filter(t -> TaskStatus.OPEN.name().equals(t.status())).count(), unread(c.getId(), user.username()), rtw, ts,
                disclosureSets.sharedWith(c, c.getPayerCode()));
    }

    // ------------------------------------------------------------------ helpers

    private static AssigneeRole role(String v) {
        try {
            return AssigneeRole.valueOf(v == null ? "" : v);
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("invalid_assignee_role", "assigneeRole must be CLAIMS, NURSE_CM or ADJUSTER");
        }
    }

    private static TaskView view(ClaimTask t) {
        return new TaskView(t.getId(), t.getTitle(), t.getDueDate(), t.getAssigneeRole(), t.getStatus(), t.getCreatedBy(), t.getCreatedAt(),
                t.getCompletedAt());
    }
}
