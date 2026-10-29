package com.wellnessz.e02.queue;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.wellnessz.casekernel.model.CaseStatus;
import com.wellnessz.casekernel.model.CaseTask;
import com.wellnessz.casekernel.model.OhCase;
import com.wellnessz.casekernel.model.OhCaseRepository;
import com.wellnessz.casekernel.CaseService;
import com.wellnessz.casekernel.support.CaseAccess;
import com.wellnessz.casekernel.support.CaseTimeline;
import com.wellnessz.casekernel.support.CaseWorkService;
import com.wellnessz.common.error.ApiException;
import com.wellnessz.common.model.Roles;
import com.wellnessz.casekernel.model.CaseEnums.WaitingState;
import com.wellnessz.e02.routing.AssignmentService;
import com.wellnessz.e02.sla.SlaService;
import com.wellnessz.security.abac.AccessPolicy;
import com.wellnessz.security.audit.AuditService;
import com.wellnessz.security.identity.CurrentUser;

/**
 * Work queues and SLA board (E02-F04). A single indexed SQL query per page joins cases with their current SLA timer,
 * open-task count and owner name; restricted-class cases are visible to psychological-health clinicians only.
 */
@Service
public class QueueService {

    public static final List<String> QUEUES = List.of("MY", "TEAM", "UNASSIGNED", "OVERDUE", "AWAITING_EXTERNAL", "AWAITING_EMPLOYEE",
            "ESCALATED");

    private final JdbcTemplate jdbc;
    private final OhCaseRepository cases;
    private final CaseAccess access;
    private final CaseService caseService;
    private final CaseTimeline timeline;
    private final CaseWorkService work;
    private final AssignmentService assignments;
    private final SlaService sla;
    private final AuditService audit;
    private final Clock clock;

    public QueueService(JdbcTemplate jdbc, OhCaseRepository cases, CaseAccess access, CaseService caseService, CaseTimeline timeline,
                        CaseWorkService work, AssignmentService assignments, SlaService sla, AuditService audit, Clock clock) {
        this.jdbc = jdbc;
        this.cases = cases;
        this.access = access;
        this.caseService = caseService;
        this.timeline = timeline;
        this.work = work;
        this.assignments = assignments;
        this.sla = sla;
        this.audit = audit;
        this.clock = clock;
    }

    public record Filters(String urgency, String subsidiary, String caseType, Boolean safetyCritical, String q) {
    }

    public record CaseSummary(UUID id, String caseRef, String employeeId, String employeeName, String subsidiaryCode,
                              String departmentCode, String jobCode, String locationCode, String category, String caseType,
                              String source, String status, String urgency, boolean safetyCritical, String safetyCategory,
                              String ownerUsername, String ownerName, String queueCode, String waitingState,
                              SlaService.SlaView sla, String intensity, Integer complexityScore, boolean duplicateSuspect,
                              boolean earlyIntervention, boolean escalated, String recordability, int openTasks,
                              Instant createdAt, Instant updatedAt) {
    }

    public record Page(List<CaseSummary> items, long total, int page, int size) {
    }

    private static final String BASE = """
            from casemgmt.oh_case c
            left join masterdata.staff_member s on s.username = c.owner_username
            left join lateral (select t.* from triage.sla_timer t where t.case_id = c.id and t.stopped_at is null
                               order by t.due_at limit 1) t on true
            where c.status not in ('CLOSED','MERGED')
            """;

    @Transactional(readOnly = true)
    public Page queue(CurrentUser user, String queue, Filters f, int page, int size) {
        requireOh(user);
        List<Object> args = new ArrayList<>();
        String where = where(user, queue, f, args);
        Long total = jdbc.queryForObject("select count(*) " + BASE + where, Long.class, args.toArray());
        List<Object> pageArgs = new ArrayList<>(args);
        int s = Math.min(Math.max(size, 1), 200);
        int p = Math.max(page, 0);
        pageArgs.add(s);
        pageArgs.add((long) p * s);
        Instant now = clock.instant();
        List<CaseSummary> items = jdbc.query("""
                select c.*, s.display_name owner_name, t.milestone t_milestone, t.status t_status, t.due_at t_due, t.amber_at t_amber,
                       t.started_at t_started, t.paused_remaining_seconds t_paused,
                       (select count(*) from casemgmt.case_task k where k.case_id = c.id and k.status = 'OPEN') open_tasks
                """ + BASE + where + """
                 \n order by t.due_at asc nulls last,
                          case c.urgency when 'EMERGENCY' then 0 when 'URGENT' then 1 when 'SEMI_URGENT' then 2 when 'ROUTINE' then 3 else 4 end,
                          c.created_at
                 limit ? offset ?""", (rs, i) -> summary(rs, now), pageArgs.toArray());
        return new Page(items, total == null ? 0 : total, p, s);
    }

    @Transactional(readOnly = true)
    public Map<String, Long> counts(CurrentUser user) {
        requireOh(user);
        Map<String, Long> out = new LinkedHashMap<>();
        for (String q : QUEUES) {
            List<Object> args = new ArrayList<>();
            String where = where(user, q, new Filters(null, null, null, null, null), args);
            out.put(q, jdbc.queryForObject("select count(*) " + BASE + where, Long.class, args.toArray()));
        }
        return out;
    }

    private String where(CurrentUser user, String queue, Filters f, List<Object> args) {
        StringBuilder w = new StringBuilder();
        if (!user.hasRole(Roles.PSYCH_CLINICIAN)) {
            w.append(" and c.classification <> 'RESTRICTED_CLINICAL'");
        }
        switch (queue) {
            case "MY" -> {
                w.append(" and c.owner_username = ?");
                args.add(user.username());
            }
            case "TEAM" -> {
                if (!user.hasAnyRole(Roles.NURSING_HEAD, Roles.CMO)) {
                    w.append(" and c.queue_code in (select q.code from triage.work_queue q where q.required_role in ("
                            + String.join(",", user.roles().stream().map(r -> "?").toList()) + "))");
                    args.addAll(user.roles());
                }
            }
            case "UNASSIGNED" -> w.append(" and c.owner_username is null");
            case "OVERDUE" -> w.append(" and (t.status = 'BREACHED' or (t.status = 'RUNNING' and t.due_at < now()))");
            case "AWAITING_EXTERNAL" -> w.append(" and c.waiting_state = 'AWAITING_EXTERNAL'");
            case "AWAITING_EMPLOYEE" -> w.append(" and c.waiting_state = 'AWAITING_EMPLOYEE'");
            case "ESCALATED" -> w.append(" and c.escalated");
            default -> throw ApiException.badRequest("unknown_queue", "Unknown queue");
        }
        if (f.urgency() != null) {
            w.append(" and c.urgency = ?");
            args.add(f.urgency());
        }
        if (f.subsidiary() != null) {
            w.append(" and c.subsidiary_code = ?");
            args.add(f.subsidiary());
        }
        if (f.caseType() != null) {
            w.append(" and c.case_type = ?");
            args.add(f.caseType());
        }
        if (f.safetyCritical() != null) {
            w.append(f.safetyCritical() ? " and c.safety_category is not null" : " and c.safety_category is null");
        }
        if (f.q() != null && !f.q().isBlank()) {
            w.append(" and (c.case_ref ilike ? or c.employee_display_name ilike ? or c.employee_id = ?)");
            String like = "%" + f.q().strip().replace("%", "").replace("_", "") + "%";
            args.add(like);
            args.add(like);
            args.add(f.q().strip());
        }
        return w.toString();
    }

    private static CaseSummary summary(ResultSet rs, Instant now) throws SQLException {
        SlaService.SlaView sla = SlaService.SlaView.NONE;
        Timestamp due = rs.getTimestamp("t_due");
        if (due != null || rs.getString("t_status") != null) {
            var t = new com.wellnessz.e02.model.SlaTimer();
            t.setMilestone(rs.getString("t_milestone"));
            t.setStatus(rs.getString("t_status"));
            t.setDueAt(due == null ? now : due.toInstant());
            Timestamp amber = rs.getTimestamp("t_amber");
            t.setAmberAt(amber == null ? t.getDueAt() : amber.toInstant());
            Timestamp started = rs.getTimestamp("t_started");
            t.setStartedAt(started == null ? now : started.toInstant());
            long paused = rs.getLong("t_paused");
            t.setPausedRemainingSeconds(rs.wasNull() ? null : paused);
            sla = SlaService.view(t, now);
        }
        Integer score = rs.getObject("complexity_score") == null ? null : rs.getInt("complexity_score");
        return new CaseSummary(rs.getObject("id", UUID.class), rs.getString("case_ref"), rs.getString("employee_id"),
                rs.getString("employee_display_name"), rs.getString("subsidiary_code"), rs.getString("org_unit_code"),
                rs.getString("job_code"), rs.getString("location_code"), rs.getString("category"), rs.getString("case_type"),
                rs.getString("source"), rs.getString("status"), rs.getString("urgency"), rs.getString("safety_category") != null,
                rs.getString("safety_category"), rs.getString("owner_username"), rs.getString("owner_name"), rs.getString("queue_code"),
                rs.getString("waiting_state"), sla, rs.getString("intensity"), score, rs.getBoolean("duplicate_suspect"),
                rs.getBoolean("early_intervention"), rs.getBoolean("escalated"), rs.getString("recordability"), rs.getInt("open_tasks"),
                rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant());
    }

    public record BulkResult(List<UUID> succeeded, List<Map<String, String>> failed) {
    }

    @Transactional
    public BulkResult bulk(CurrentUser user, String action, List<UUID> caseIds, String toUsername) {
        requireOh(user);
        if (caseIds.size() > 200) {
            throw ApiException.badRequest("too_many", "At most 200 cases per bulk action");
        }
        if ("REASSIGN".equals(action) && !user.hasRole(Roles.NURSING_HEAD)) {
            throw ApiException.forbidden("Bulk re-assignment is reserved to the Nursing Head");
        }
        List<UUID> ok = new ArrayList<>();
        List<Map<String, String>> failed = new ArrayList<>();
        for (UUID id : caseIds) {
            try {
                switch (action) {
                    case "REASSIGN" -> assignments.assign(user, id, toUsername, null, "Bulk re-assignment");
                    case "REMIND" -> {
                        OhCase c = access.clinical(user, id, AccessPolicy.Action.UPDATE, "CASE_REMIND");
                        work.systemTask(id, CaseTask.Type.REMINDER, "Reminder from " + user.username(), clock.instant(),
                                c.getOwnerUsername(), user.username());
                    }
                    case "CLOSE_DUPLICATES" -> {
                        OhCase c = access.clinical(user, id, AccessPolicy.Action.UPDATE, "CASE_CLOSE_DUPLICATE");
                        if (!c.isDuplicateSuspect()) {
                            throw ApiException.conflict("not_duplicate", "Case is not flagged as a probable duplicate");
                        }
                        caseService.transition(user, id, CaseStatus.CLOSED);
                        sla.cancelAll(id);
                    }
                    default -> throw ApiException.badRequest("unknown_action", "Unknown bulk action");
                }
                ok.add(id);
            } catch (ApiException e) {
                failed.add(Map.of("caseId", id.toString(), "reason", e.getMessage()));
            }
        }
        audit.record("QUEUE_BULK_" + action, "CASE", null, AuditService.Outcome.SUCCESS, "ok=" + ok.size() + " failed=" + failed.size());
        return new BulkResult(ok, failed);
    }

    @Transactional
    public void waitingState(CurrentUser user, UUID caseId, WaitingState state, String note) {
        OhCase c = access.clinical(user, caseId, AccessPolicy.Action.UPDATE, "CASE_WAITING_STATE");
        c.setWaitingState(state, clock.instant());
        if (state == WaitingState.NONE) {
            sla.resume(caseId);
        } else {
            sla.pause(caseId);
        }
        timeline.record(caseId, "WAITING_STATE", "Waiting state " + state + (note == null ? "" : " – note recorded"));
    }

    private static void requireOh(CurrentUser user) {
        if (!user.hasAnyRole(Roles.OH_TEAM)) {
            throw ApiException.forbidden("Work queues are available to the OH team only");
        }
    }
}
