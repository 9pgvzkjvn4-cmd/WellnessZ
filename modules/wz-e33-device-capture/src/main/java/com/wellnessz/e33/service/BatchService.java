package com.wellnessz.e33.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.wellnessz.casekernel.support.EmployeeRecordAccess;
import com.wellnessz.common.error.ApiException;
import com.wellnessz.common.model.DataClassification;
import com.wellnessz.e33.domain.CaptureStateMachine.State;
import com.wellnessz.e33.domain.CaptureStateMachine.Trigger;
import com.wellnessz.e33.domain.VerificationRule;
import com.wellnessz.e33.model.CaptureBatch;
import com.wellnessz.e33.model.CaptureObservation;
import com.wellnessz.e33.model.CaptureSession;
import com.wellnessz.e33.model.E33CaptureBatchRepository;
import com.wellnessz.e33.model.E33CaptureSessionRepository;
import com.wellnessz.masterdata.Employee;
import com.wellnessz.security.abac.AccessPolicy;
import com.wellnessz.security.audit.AuditService;
import com.wellnessz.security.identity.CurrentUser;

/**
 * Mobile batch mode for screening days (E33-HLR-07, E33-F10, contract 14.5): a queue of employees on the nurse tablet with the
 * sessions pre-created so the tablet can work offline. Online the nurse advances with one tap; offline the tablet submits BLE
 * readings and fall-backs through the engine's ingest API (with {@code sessionId} / {@code queueEntryId}) and the engine binds
 * them through {@code DeviceCaptureSessions} to the current queue entry of each device. Queued previews without warnings are
 * accepted together when the nurse is back online.
 */
@Service
public class BatchService {

    static final int MAX_ENTRIES = 300;
    static final long BATCH_SESSION_HOURS = 14;

    public record CreateCommand(String siteCode, String screeningEventId, String formCode, List<String> deviceInstanceIds,
                                List<String> employeeIds, List<String> badgeIds) {
    }

    public record QueueEntry(String queueEntryId, UUID sessionId, int position, String employeeId, String displayName, String state,
                             int readings, boolean current) {
    }

    public record BatchView(UUID batchId, String siteCode, String screeningEventId, String formCode, List<String> deviceInstanceIds,
                            String status, String operator, Instant createdAt, List<QueueEntry> sessions) {
    }

    public record Skipped(UUID sessionId, String reason) {
    }

    public record AcceptAllResult(UUID batchId, List<UUID> accepted, List<Skipped> skipped, String status) {
    }

    private final E33CaptureBatchRepository batches;
    private final E33CaptureSessionRepository sessions;
    private final CaptureSessionService sessionService;
    private final CaptureTransitions transitions;
    private final FormRegistry forms;
    private final DeviceListService devices;
    private final EmployeeRecordAccess records;
    private final AuditService audit;
    private final CaptureSupport support;

    public BatchService(E33CaptureBatchRepository batches, E33CaptureSessionRepository sessions, CaptureSessionService sessionService,
                        CaptureTransitions transitions, FormRegistry forms, DeviceListService devices, EmployeeRecordAccess records,
                        AuditService audit, CaptureSupport support) {
        this.batches = batches;
        this.sessions = sessions;
        this.sessionService = sessionService;
        this.transitions = transitions;
        this.forms = forms;
        this.devices = devices;
        this.records = records;
        this.audit = audit;
        this.support = support;
    }

    @Transactional
    public BatchView create(CurrentUser user, CreateCommand cmd) {
        support.requireEnabled();
        FormRegistry.Form form = forms.require(cmd.formCode());
        if (form.verification().restricted()) {
            throw CaptureSupport.unprocessable("batch_not_allowed", "Restricted programme readings are not captured in batch mode");
        }
        List<String> deviceIds = cmd.deviceInstanceIds() == null ? List.of() : List.copyOf(new LinkedHashSet<>(cmd.deviceInstanceIds()));
        if (deviceIds.isEmpty()) {
            throw ApiException.badRequest("validation_failed", "At least one paired device is required");
        }
        for (String d : deviceIds) {
            DeviceListService.DeviceChoice c = devices.choice(d, form.formCode(), cmd.siteCode());
            if ("BUSY".equals(c.status())) {
                throw CaptureSessionService.busy(c.heldBy());
            }
        }
        List<Employee> queue = resolveQueue(user, cmd);
        if (queue.isEmpty() || queue.size() > MAX_ENTRIES) {
            throw ApiException.badRequest("validation_failed", "A batch holds 1–" + MAX_ENTRIES + " employees");
        }
        Instant now = support.now();
        CaptureBatch b = new CaptureBatch();
        b.setId(UUID.randomUUID());
        b.setSiteCode(cmd.siteCode());
        b.setScreeningEventId(cmd.screeningEventId());
        b.setFormCode(form.formCode());
        b.setDeviceInstanceIds(String.join(",", deviceIds));
        b.setOperator(user.username());
        b.setStatus("OPEN");
        b.setEntries(queue.size());
        b.setCreatedAt(now);
        batches.save(b);
        int pos = 1;
        for (Employee e : queue) {
            CaptureSession s = new CaptureSession();
            s.setId(UUID.randomUUID());
            s.setMode("BATCH");
            s.setState(State.LISTENING.name());
            s.setEmployeeId(e.getEmployeeId());
            s.setOrderRef(cmd.screeningEventId());
            s.setFormCode(form.formCode());
            s.setFormVersion(form.formVersion());
            s.setCategory(form.category().name());
            s.setRestricted(false);
            s.setOperator(user.username());
            s.setSiteCode(cmd.siteCode());
            s.setBatchId(b.getId());
            s.setQueuePosition(pos++);
            s.setContextPush("NONE");
            s.setOpenedAt(now);
            s.setLastActivityAt(now);
            s.setExpiresAt(now.plusSeconds(BATCH_SESSION_HOURS * 3600));
            sessions.save(s);
            transitions.opened(s, user.username(), "batch");
        }
        audit.record("DEVICE_BATCH_CREATE", "DEVICE_CAPTURE_BATCH", b.getId().toString(), AuditService.Outcome.SUCCESS,
                form.formCode() + " entries=" + queue.size() + " devices=" + deviceIds.size());
        return view(b);
    }

    private List<Employee> resolveQueue(CurrentUser user, CreateCommand cmd) {
        Set<String> seen = new LinkedHashSet<>();
        List<Employee> out = new ArrayList<>();
        if (cmd.employeeIds() != null) {
            for (String id : cmd.employeeIds()) {
                if (seen.add(id)) {
                    out.add(records.requireClinical(user, id, DataClassification.CLINICAL, AccessPolicy.Action.CREATE, "DEVICE_BATCH_CREATE"));
                }
            }
        }
        if (cmd.badgeIds() != null) {
            for (String badge : cmd.badgeIds()) {
                Employee e = support.employeeByBadge(badge).orElseThrow(() -> ApiException.notFound("Badge"));
                if (seen.add(e.getEmployeeId())) {
                    out.add(records.requireClinical(user, e.getEmployeeId(), DataClassification.CLINICAL, AccessPolicy.Action.CREATE,
                            "DEVICE_BATCH_CREATE"));
                }
            }
        }
        return out;
    }

    @Transactional(readOnly = true)
    public BatchView get(CurrentUser user, UUID id) {
        support.requireEnabled();
        return view(requireOwn(user, id));
    }

    /** One-tap advance: skips the current entry when it has no reading yet. */
    @Transactional
    public BatchView next(CurrentUser user, UUID id) {
        support.requireEnabled();
        CaptureBatch b = requireOwn(user, id);
        requireOpen(b);
        CaptureSession current = current(b);
        if (current != null && readings(current) == 0) {
            transitions.apply(current, Trigger.CANCEL, user.username(), "SKIPPED", "batch next", false);
        }
        closeIfDone(b);
        return view(b);
    }

    /** Accepts the queued previews that carry no WARN / BLOCK flag (contract 14.5); others stay for individual review. */
    @Transactional
    public AcceptAllResult acceptAll(CurrentUser user, UUID id, List<UUID> sessionIds, Map<String, List<String>> acknowledgedFlags) {
        support.requireEnabled();
        CaptureBatch b = requireOwn(user, id);
        List<UUID> accepted = new ArrayList<>();
        List<Skipped> skipped = new ArrayList<>();
        Set<UUID> wanted = sessionIds == null ? Set.of() : Set.copyOf(sessionIds);
        for (CaptureSession s : sessions.findByBatchIdOrderByQueuePositionAsc(b.getId())) {
            if (!wanted.isEmpty() && !wanted.contains(s.getId())) {
                continue;
            }
            if (!State.PREVIEW.name().equals(s.getState())) {
                if (wanted.contains(s.getId())) {
                    skipped.add(new Skipped(s.getId(), "not_in_preview"));
                }
                continue;
            }
            List<CaptureObservation> pending = sessionService.undecided(s);
            boolean clean = !pending.isEmpty() && pending.stream().allMatch(o -> VerificationRule.clean(support.readFlags(o.getFlags())));
            if (!clean) {
                skipped.add(new Skipped(s.getId(), pending.isEmpty() ? "nothing_pending" : "flags_need_review"));
                continue;
            }
            List<String> ack = acknowledgedFlags == null ? List.of() : acknowledgedFlags.getOrDefault(s.getId().toString(), List.of());
            try {
                sessionService.acceptSession(user, s, new CaptureSessionService.AcceptCommand(ack, null));
                accepted.add(s.getId());
            } catch (ApiException e) {
                skipped.add(new Skipped(s.getId(), e.code()));
            }
        }
        closeIfDone(b);
        return new AcceptAllResult(b.getId(), accepted, skipped, b.getStatus());
    }

    private CaptureBatch requireOwn(CurrentUser user, UUID id) {
        CaptureBatch b = batches.findById(id).orElseThrow(() -> ApiException.notFound("Batch"));
        if (!b.getOperator().equals(user.username())) {
            audit.record("DEVICE_BATCH_READ", "DEVICE_CAPTURE_BATCH", id.toString(), AuditService.Outcome.DENIED, "not the batch operator");
            throw ApiException.notFound("Batch");
        }
        return b;
    }

    private static void requireOpen(CaptureBatch b) {
        if (!"OPEN".equals(b.getStatus())) {
            throw ApiException.conflict("batch_closed", "The batch is closed");
        }
    }

    private CaptureSession current(CaptureBatch b) {
        return sessions.findByBatchIdOrderByQueuePositionAsc(b.getId()).stream()
                .filter(s -> State.LISTENING.name().equals(s.getState())).findFirst().orElse(null);
    }

    private int readings(CaptureSession s) {
        return (int) sessionService.observationsOf(s).stream().filter(o -> !"REJECTED".equals(o.getDecision())).count();
    }

    private void closeIfDone(CaptureBatch b) {
        boolean done = sessions.findByBatchIdOrderByQueuePositionAsc(b.getId()).stream().allMatch(s -> State.valueOf(s.getState()).isFinal());
        if (done && "OPEN".equals(b.getStatus())) {
            b.setStatus("CLOSED");
            b.setClosedAt(support.now());
        }
    }

    private BatchView view(CaptureBatch b) {
        List<CaptureSession> queue = sessions.findByBatchIdOrderByQueuePositionAsc(b.getId());
        CaptureSession current = queue.stream().filter(s -> State.LISTENING.name().equals(s.getState()) && readings(s) == 0).findFirst().orElse(null);
        List<QueueEntry> entries = queue.stream().map(s -> new QueueEntry(b.getId() + ":" + s.getQueuePosition(), s.getId(), s.getQueuePosition(),
                s.getEmployeeId(), support.displayName(s.getEmployeeId()), s.getState(), readings(s), s == current)).toList();
        return new BatchView(b.getId(), b.getSiteCode(), b.getScreeningEventId(), b.getFormCode(), CaptureSupport.csv(b.getDeviceInstanceIds()),
                b.getStatus(), b.getOperator(), b.getCreatedAt(), entries);
    }
}
