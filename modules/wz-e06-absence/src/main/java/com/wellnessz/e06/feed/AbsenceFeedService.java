package com.wellnessz.e06.feed;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.wellnessz.common.error.ApiException;
import com.wellnessz.common.model.Roles;
import com.wellnessz.e06.lta.LtaService;
import com.wellnessz.e06.model.Episode;
import com.wellnessz.e06.model.E06EpisodeRepository;
import com.wellnessz.e06.model.FeedMismatch;
import com.wellnessz.e06.model.AbsenceFeedMismatchRepository;
import com.wellnessz.e06.model.FeedRun;
import com.wellnessz.e06.model.AbsenceFeedRunRepository;
import com.wellnessz.e06.rules.AbsenceTriggerEngine;
import com.wellnessz.e06.support.AbsenceSupport;
import com.wellnessz.masterdata.Employee;
import com.wellnessz.security.audit.AuditService;
import com.wellnessz.security.identity.CurrentUser;

/**
 * Absence feed (E06-F01): push ingestion and the daily pull, reconciliation against the employee master (employee id, then
 * badge id), idempotent upsert on (sourceSystem, sourceRef), mismatch list and data-quality runs. Every ingested or updated
 * episode is evaluated by the trigger rules.
 */
@Service
public class AbsenceFeedService {

    public static final String OPEN = "OPEN";

    private final AbsenceFeedRunRepository runs;
    private final AbsenceFeedMismatchRepository mismatches;
    private final E06EpisodeRepository episodes;
    private final AbsenceTriggerEngine triggers;
    private final LtaService lta;
    private final List<AbsenceFeedConnector> connectors;
    private final AbsenceSupport support;
    private final AuditService audit;

    public AbsenceFeedService(AbsenceFeedRunRepository runs, AbsenceFeedMismatchRepository mismatches, E06EpisodeRepository episodes, AbsenceTriggerEngine triggers,
                              LtaService lta, List<AbsenceFeedConnector> connectors, AbsenceSupport support, AuditService audit) {
        this.runs = runs;
        this.mismatches = mismatches;
        this.episodes = episodes;
        this.triggers = triggers;
        this.lta = lta;
        this.connectors = connectors;
        this.support = support;
        this.audit = audit;
    }

    public record IngestResult(UUID runId, int received, int matched, int unmatched, int updated, int triggered) {
    }

    public record FeedRunView(UUID id, String sourceSystem, String runType, Instant startedAt, Instant finishedAt, int received, int matched,
                              int unmatched, int updated, int triggered, BigDecimal matchRatePct, int errors) {
    }

    public record MismatchView(UUID id, String sourceSystem, String sourceRef, String employeeRef, LocalDate startDate, LocalDate endDate,
                               String reason, String status, String resolvedEmployeeId, String resolvedBy, Instant resolvedAt, Instant createdAt) {
    }

    // ------------------------------------------------------------------ ingestion

    @Transactional
    public IngestResult ingest(String actor, String sourceSystem, String runType, List<AbsenceFeedConnector.FeedRow> rows) {
        FeedRun fresh = FeedRun.create();
        fresh.setSourceSystem(sourceSystem);
        fresh.setRunType(runType);
        fresh.setStartedAt(support.now());
        fresh.setStartedBy(actor);
        FeedRun run = runs.save(fresh); // assigned ids: save() merges, keep working on the managed instance
        Set<String> affected = new LinkedHashSet<>();
        List<String> errors = new ArrayList<>();
        int matched = 0;
        int unmatched = 0;
        int updated = 0;
        for (AbsenceFeedConnector.FeedRow row : rows) {
            if (row.endDate() != null && row.endDate().isBefore(row.startDate())) {
                errors.add(row.sourceRef() + ": endDate before startDate");
                continue;
            }
            Optional<Episode> existing = episodes.findBySourceSystemAndSourceRef(sourceSystem, row.sourceRef());
            Optional<Employee> emp = existing.isPresent() ? support.employee(existing.get().getEmployeeId()) : support.reconcile(row.employeeRef());
            if (emp.isEmpty()) {
                unmatched++;
                mismatch(run, sourceSystem, row);
                continue;
            }
            matched++;
            if (existing.isPresent()) {
                updated++;
            }
            upsert(existing.orElse(null), sourceSystem, row, emp.get().getEmployeeId(), run.getId());
            affected.add(emp.get().getEmployeeId());
        }
        int triggered = 0;
        LocalDate today = support.today();
        for (String employeeId : affected) {
            triggered += triggers.evaluate(employeeId, today);
        }
        run.setReceived(rows.size());
        run.setMatched(matched);
        run.setUnmatched(unmatched);
        run.setUpdated(updated);
        run.setTriggered(triggered);
        run.setErrors(errors.size());
        run.setErrorDetail(errors.isEmpty() ? null : truncate(String.join("; ", errors), 2000));
        run.setFinishedAt(support.now());
        audit.record("ABSENCE_FEED_INGEST", "ABSENCE_FEED_RUN", run.getId().toString(), AuditService.Outcome.SUCCESS,
                sourceSystem + " received=" + rows.size() + " matched=" + matched + " unmatched=" + unmatched);
        return new IngestResult(run.getId(), rows.size(), matched, unmatched, updated, triggered);
    }

    private Episode upsert(Episode existing, String sourceSystem, AbsenceFeedConnector.FeedRow row, String employeeId, UUID runId) {
        Episode e = existing;
        boolean closedNow = false;
        if (e == null) {
            e = Episode.create();
            e.setSourceSystem(sourceSystem);
            e.setSourceRef(row.sourceRef());
            e.setEmployeeId(employeeId);
            e.setCreatedAt(support.now());
            closedNow = row.endDate() != null;
        } else {
            closedNow = e.getEndDate() == null && row.endDate() != null;
        }
        e.setStartDate(row.startDate());
        e.setEndDate(row.endDate());
        e.setAbsenceType(row.absenceType());
        if (row.reasonCategory() != null) {
            e.setReasonCategory(row.reasonCategory());
        }
        e.setCertified(row.certified());
        e.setFeedRunId(runId);
        e.setUpdatedAt(support.now());
        e = episodes.save(e);
        if (closedNow) {
            lta.episodeClosed(e);
        }
        return e;
    }

    private void mismatch(FeedRun run, String sourceSystem, AbsenceFeedConnector.FeedRow row) {
        FeedMismatch m = mismatches.findFirstBySourceSystemAndSourceRefAndStatus(sourceSystem, row.sourceRef(), OPEN).orElseGet(() -> {
            FeedMismatch x = FeedMismatch.create();
            x.setSourceSystem(sourceSystem);
            x.setSourceRef(row.sourceRef());
            x.setStatus(OPEN);
            x.setReason("UNKNOWN_EMPLOYEE");
            x.setCreatedAt(support.now());
            return x;
        });
        m.setRunId(run.getId());
        m.setEmployeeRef(row.employeeRef());
        m.setStartDate(row.startDate());
        m.setEndDate(row.endDate());
        m.setAbsenceType(row.absenceType());
        m.setReasonCategory(row.reasonCategory());
        m.setCertified(row.certified());
        mismatches.save(m);
    }

    /** Daily pull through the {@link AbsenceFeedConnector}s (also on demand by OH_ADMIN). */
    @Transactional
    public FeedRunView pull(String actor) {
        LocalDate since = support.today().minusDays(2);
        FeedRunView last = null;
        for (AbsenceFeedConnector c : connectors) {
            IngestResult r = ingest(actor, c.sourceSystem(), "PULL", c.pull(since));
            last = runs.findById(r.runId()).map(AbsenceFeedService::view).orElse(null);
        }
        if (last == null) {
            IngestResult r = ingest(actor, "HRIS", "PULL", List.of());
            last = runs.findById(r.runId()).map(AbsenceFeedService::view).orElseThrow();
        }
        return last;
    }

    // ------------------------------------------------------------------ data quality

    @Transactional(readOnly = true)
    public List<FeedRunView> runs(int limit) {
        return runs.findAllByOrderByStartedAtDesc(PageRequest.of(0, Math.min(Math.max(limit, 1), 200))).stream()
                .map(AbsenceFeedService::view).toList();
    }

    @Transactional(readOnly = true)
    public List<MismatchView> mismatches(CurrentUser user, String status) {
        List<FeedMismatch> list = status == null || status.isBlank() ? mismatches.findAllByOrderByCreatedAtDesc()
                : mismatches.findByStatusOrderByCreatedAtDesc(status);
        audit.record("ABSENCE_MISMATCH_READ", "ABSENCE_FEED_RUN", null, AuditService.Outcome.SUCCESS, "rows=" + list.size());
        return list.stream().map(AbsenceFeedService::view).toList();
    }

    @Transactional
    public MismatchView resolve(CurrentUser user, UUID id, String employeeId, boolean ignore, String reason) {
        FeedMismatch m = mismatches.findById(id).orElseThrow(() -> ApiException.notFound("Mismatch"));
        if (!OPEN.equals(m.getStatus())) {
            throw ApiException.conflict("not_open", "The mismatch is already resolved");
        }
        m.setResolvedBy(user.username());
        m.setResolvedAt(support.now());
        if (ignore) {
            String r = reason == null || reason.isBlank() ? null : reason.strip();
            if (r == null) {
                throw ApiException.badRequest("reason_required", "Give a reason for ignoring the row");
            }
            m.setStatus("IGNORED");
            m.setResolutionNote(r);
            audit.record("ABSENCE_MISMATCH_IGNORE", "ABSENCE_MISMATCH", id.toString(), AuditService.Outcome.SUCCESS, null);
            return view(m);
        }
        if (employeeId == null || employeeId.isBlank()) {
            throw ApiException.badRequest("employee_required", "Give the employee id or ignore the row");
        }
        Employee e = user.hasRole(Roles.OH_ADMIN) ? support.employee(employeeId).orElseThrow(() -> ApiException.notFound("Employee"))
                : support.requireInScope(user, employeeId, "ABSENCE_MISMATCH_RESOLVE");
        AbsenceFeedConnector.FeedRow row = new AbsenceFeedConnector.FeedRow(m.getSourceRef(), e.getEmployeeId(), m.getStartDate(), m.getEndDate(),
                m.getAbsenceType(), m.getReasonCategory(), m.isCertified());
        Episode ep = upsert(episodes.findBySourceSystemAndSourceRef(m.getSourceSystem(), m.getSourceRef()).orElse(null), m.getSourceSystem(),
                row, e.getEmployeeId(), m.getRunId());
        m.setStatus("RESOLVED");
        m.setResolvedEmployeeId(e.getEmployeeId());
        m.setEpisodeId(ep.getId());
        m.setResolutionNote(reason == null ? null : truncate(reason.strip(), 500));
        runs.findById(m.getRunId()).ifPresent(r -> {
            r.setMatched(r.getMatched() + 1);
            r.setUnmatched(Math.max(0, r.getUnmatched() - 1));
        });
        triggers.evaluate(e.getEmployeeId(), support.today());
        audit.record("ABSENCE_MISMATCH_RESOLVE", "ABSENCE_MISMATCH", id.toString(), AuditService.Outcome.SUCCESS, e.getEmployeeId());
        return view(m);
    }

    // ------------------------------------------------------------------ views

    static FeedRunView view(FeedRun r) {
        BigDecimal rate = r.getReceived() == 0 ? null
                : BigDecimal.valueOf(r.getMatched() * 100L).divide(BigDecimal.valueOf(r.getReceived()), 1, RoundingMode.HALF_UP);
        return new FeedRunView(r.getId(), r.getSourceSystem(), r.getRunType(), r.getStartedAt(), r.getFinishedAt(), r.getReceived(), r.getMatched(),
                r.getUnmatched(), r.getUpdated(), r.getTriggered(), rate, r.getErrors());
    }

    static MismatchView view(FeedMismatch m) {
        return new MismatchView(m.getId(), m.getSourceSystem(), m.getSourceRef(), m.getEmployeeRef(), m.getStartDate(), m.getEndDate(), m.getReason(),
                m.getStatus(), m.getResolvedEmployeeId(), m.getResolvedBy(), m.getResolvedAt(), m.getCreatedAt());
    }

    private static String truncate(String s, int max) {
        return s.length() > max ? s.substring(0, max) : s;
    }
}
