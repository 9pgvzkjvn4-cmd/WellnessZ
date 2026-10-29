package com.wellnessz.e03.results;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.wellnessz.casekernel.model.OhCase;
import com.wellnessz.casekernel.model.OhCaseRepository;
import com.wellnessz.casekernel.support.CaseAccess;
import com.wellnessz.casekernel.support.DeviceCalibrationCheck;
import com.wellnessz.casekernel.support.ResultFollowUpListener;
import com.wellnessz.casekernel.support.CaseTimeline;
import com.wellnessz.common.error.ApiException;
import com.wellnessz.common.model.DataClassification;
import com.wellnessz.common.model.Roles;
import com.wellnessz.documents.DocumentService;
import com.wellnessz.e03.E03Events;
import com.wellnessz.e03.model.LabOrder;
import com.wellnessz.e03.model.LabOrderRepository;
import com.wellnessz.e03.model.Result;
import com.wellnessz.e03.model.ResultRepository;
import com.wellnessz.e03.orders.OrderService;
import com.wellnessz.e03.support.CodeRef;
import com.wellnessz.e03.support.JsonSupport;
import com.wellnessz.e03.support.RecordGuard;
import com.wellnessz.e03.terminology.TerminologyCatalog;
import com.wellnessz.integration.outbox.EventBus;
import com.wellnessz.masterdata.Employee;
import com.wellnessz.masterdata.StaffMember;
import com.wellnessz.masterdata.StaffMemberRepository;
import com.wellnessz.notification.Channel;
import com.wellnessz.notification.NotificationService;
import com.wellnessz.notification.Priority;
import com.wellnessz.security.abac.AccessPolicy;
import com.wellnessz.security.audit.AuditService;
import com.wellnessz.security.identity.CurrentUser;

/**
 * Results management (E03-F04): LIS results (JSON or HL7 ORU^R01), clinic-device capture with calibration ID and the
 * abnormal rules of {@link DeviceRules}, the unacknowledged-abnormal worklist with 72-hour CMO escalation, acknowledgement
 * with an action, and result letters. Substance-testing codes are stored in the restricted class.
 */
@Service
public class ResultService {

    /** Content-free staff templates (the wording never names the test or its outcome). */
    static final String ABNORMAL_TEMPLATE = "result.abnormal";
    static final String OVERDUE_TEMPLATE = "result.overdue";

    private final ResultRepository results;
    private final LabOrderRepository orders;
    private final OrderService orderService;
    private final RecordGuard guard;
    private final CaseAccess caseAccess;
    private final OhCaseRepository cases;
    private final CaseTimeline timeline;
    private final TerminologyCatalog terminology;
    private final DocumentService documents;
    private final NotificationService notifications;
    private final StaffMemberRepository staff;
    private final EventBus bus;
    private final JsonSupport json;
    private final AuditService audit;
    private final Clock clock;
    private final Set<String> restrictedCodes;
    private final String portalLink;
    private final Duration overdueAfter;
    private final List<DeviceCalibrationCheck> calibration;
    private final List<ResultFollowUpListener> followUp;
    private final boolean requireRegisteredDevices;

    public ResultService(ResultRepository results, LabOrderRepository orders, OrderService orderService, RecordGuard guard,
                         CaseAccess caseAccess, OhCaseRepository cases, CaseTimeline timeline, TerminologyCatalog terminology,
                         DocumentService documents, NotificationService notifications, StaffMemberRepository staff, EventBus bus,
                         JsonSupport json, AuditService audit, Clock clock,
                         @Value("${wellnessz.record.restricted-result-codes:5643-2,19659-3}") String restrictedCodes,
                         @Value("${wellnessz.notification.oh-workspace-url:https://oh.wellnessz.local}") String workspaceUrl,
                         @Value("${wellnessz.record.result-overdue-after:PT72H}") Duration overdueAfter,
                         List<DeviceCalibrationCheck> calibration, List<ResultFollowUpListener> followUp,
                         @Value("${wellnessz.record.require-registered-devices:false}") boolean requireRegisteredDevices) {
        this.results = results;
        this.orders = orders;
        this.orderService = orderService;
        this.guard = guard;
        this.caseAccess = caseAccess;
        this.cases = cases;
        this.timeline = timeline;
        this.terminology = terminology;
        this.documents = documents;
        this.notifications = notifications;
        this.staff = staff;
        this.bus = bus;
        this.json = json;
        this.audit = audit;
        this.clock = clock;
        this.restrictedCodes = Arrays.stream(restrictedCodes.split(",")).map(String::trim).filter(s -> !s.isEmpty())
                .collect(Collectors.toUnmodifiableSet());
        this.portalLink = workspaceUrl + "/worklist";
        this.overdueAfter = overdueAfter;
        this.calibration = List.copyOf(calibration);
        this.followUp = List.copyOf(followUp);
        this.requireRegisteredDevices = requireRegisteredDevices;
    }

    /** E14-HLR-04: results must not be captured on an out-of-calibration or out-of-service device. */
    void checkCalibration(String deviceId, LocalDate on) {
        for (DeviceCalibrationCheck check : calibration) {
            DeviceCalibrationCheck.Status st = check.check(deviceId, on);
            switch (st.verdict()) {
                case OUT_OF_CALIBRATION -> throw new ApiException(org.springframework.http.HttpStatus.UNPROCESSABLE_CONTENT,
                        "device_out_of_calibration", "Device " + deviceId + " is out of calibration"
                        + (st.calibrationDueOn() == null ? "" : " (due " + st.calibrationDueOn() + ")") + " – results cannot be recorded");
                case OUT_OF_SERVICE -> throw new ApiException(org.springframework.http.HttpStatus.UNPROCESSABLE_CONTENT,
                        "device_out_of_service", "Device " + deviceId + " is out of service – results cannot be recorded");
                case UNKNOWN -> {
                    if (requireRegisteredDevices) {
                        throw new ApiException(org.springframework.http.HttpStatus.UNPROCESSABLE_CONTENT, "device_not_registered",
                                "Device " + deviceId + " is not in the equipment register");
                    }
                }
                default -> {
                }
            }
        }
    }

    private void notifyFollowUp(Result res) {
        ResultFollowUpListener.RecordedResult rr = new ResultFollowUpListener.RecordedResult(res.getId(), res.getEmployeeId(),
                res.getCaseId(), res.getCategory(), res.getCodeSystem(), res.getCode(), res.getFlag(), res.isBaseline(), res.getRecordedBy());
        followUp.forEach(l -> l.resultRecorded(rr));
    }

    // ------------------------------------------------------------------ records

    public record ResultView(UUID id, UUID orderId, String employeeId, UUID caseId, String category, CodeRef code, String value, String unit,
                             String referenceRange, String flag, String status, Instant effectiveAt, Instant receivedAt, String source,
                             String deviceId, String deviceModel, String calibrationId, LocalDate calibrationDate, boolean baseline,
                             String acknowledgedBy, Instant acknowledgedAt, String ackAction, String ackNote, long ageHours,
                             boolean overdue, Map<String, Object> detail, Map<String, Object> measurements, UUID letterDocumentId) {
    }

    public record LisObservation(@NotNull @Valid CodeRef code, @Size(max = 200) String value, @Size(max = 40) String unit,
                                 @Size(max = 60) String referenceRange,
                                 @Pattern(regexp = "N|L|H|LL|HH|A|AA|NORMAL|LOW|HIGH|ABNORMAL|CRITICAL") String interpretation,
                                 @Pattern(regexp = "PRELIMINARY|FINAL|AMENDED") String status, Instant effectiveAt) {
    }

    public record LisResults(@Pattern(regexp = "[A-Z0-9\\-]{1,30}") String placerOrderNumber,
                             @NotBlank @Pattern(regexp = "[A-Za-z0-9_\\-]{1,64}") String employeeId,
                             @NotEmpty @Valid @Size(max = 200) List<LisObservation> observations) {
    }

    public record IngestResult(int received, int abnormal, List<UUID> resultIds) {
    }

    public record DeviceRequest(@NotBlank @Pattern(regexp = "AUDIOMETRY|SPIROMETRY|VISION|ECG") String category,
                                @Size(max = 100) String deviceId, @Size(max = 100) String deviceModel,
                                @Size(max = 100) String calibrationId, LocalDate calibrationDate, Instant performedAt, UUID caseId,
                                UUID orderId, @NotNull @Size(max = 20) Map<String, Object> measurements) {
    }

    public record AckRequest(@NotBlank @Pattern(regexp = "NO_ACTION|REPEAT_TEST|REFER|FOLLOW_UP_APPOINTMENT|FITNESS_REVIEW") String action,
                             @Size(max = 2000) String note) {
    }

    public record LetterRequest(@Size(max = 2000) String interpretation, @Size(max = 2000) String action) {
    }

    // ------------------------------------------------------------------ views

    ResultView view(Result r, Instant now) {
        long age = Duration.between(r.getReceivedAt(), now).toHours();
        boolean overdue = r.getAcknowledgedAt() == null && !"NORMAL".equals(r.getFlag())
                && Duration.between(r.getReceivedAt(), now).compareTo(overdueAfter) > 0;
        return new ResultView(r.getId(), r.getOrderId(), r.getEmployeeId(), r.getCaseId(), r.getCategory(),
                CodeRef.of(r.getCodeSystem(), r.getCode(), r.getCodeDisplay()), r.getValue(), r.getUnit(), r.getReferenceRange(), r.getFlag(),
                r.getStatus(), r.getEffectiveAt(), r.getReceivedAt(), r.getSource(), r.getDeviceId(), r.getDeviceModel(), r.getCalibrationId(),
                r.getCalibrationDate(), r.isBaseline(), r.getAcknowledgedBy(), r.getAcknowledgedAt(), r.getAckAction(), r.getAckNote(), age,
                overdue, r.getDetail() == null ? null : json.map(r.getDetail()),
                r.getMeasurements() == null ? null : json.map(r.getMeasurements()), r.getLetterDocumentId());
    }

    @Transactional(readOnly = true)
    public List<ResultView> list(CurrentUser user, String employeeId, String category) {
        RecordGuard.Access a = guard.read(user, employeeId, "RESULT_LIST");
        Instant now = clock.instant();
        List<ResultView> out = results.findByEmployeeIdOrderByEffectiveAtDesc(employeeId).stream()
                .filter(r -> category == null || category.equals(r.getCategory()))
                .filter(r -> a.canSee(r.getClassification()))
                .map(r -> view(r, now)).toList();
        audit.record("RESULT_LIST", "EMPLOYEE", employeeId, AuditService.Outcome.SUCCESS, "rows=" + out.size());
        return out;
    }

    // ------------------------------------------------------------------ LIS ingestion (JSON and HL7 ORU^R01)

    @Transactional
    public IngestResult ingest(LisResults r, String actor) {
        Employee e = guard.employee(r.employeeId());
        LabOrder order = null;
        if (r.placerOrderNumber() != null) {
            order = orders.findByPlacerOrderNumber(r.placerOrderNumber()).orElseThrow(() -> ApiException.notFound("Order"));
            if (!order.getEmployeeId().equals(e.getEmployeeId())) {
                audit.recordAs(actor, Roles.INTEGRATION, "RESULT_INGEST", "ORDER", order.getId().toString(), AuditService.Outcome.DENIED,
                        "Employee does not match the order");
                throw new ApiException(org.springframework.http.HttpStatus.UNPROCESSABLE_CONTENT, "order_mismatch",
                        "The result's employee does not match the order");
            }
        }
        Instant now = clock.instant();
        List<UUID> ids = new ArrayList<>();
        int abnormal = 0;
        for (LisObservation o : r.observations()) {
            CodeRef code = terminology.lookup(o.code().system(), o.code().code())
                    .map(c -> new CodeRef(c.system(), c.code(), c.display()))
                    .orElse(new CodeRef(o.code().system(), o.code().code(), o.code().display() == null ? o.code().code() : o.code().display()));
            Result res = new Result();
            res.setId(UUID.randomUUID());
            res.setOrderId(order == null ? null : order.getId());
            res.setEmployeeId(e.getEmployeeId());
            res.setCaseId(order == null ? null : order.getCaseId());
            res.setCategory(order == null ? "LAB" : order.getCategory());
            res.setCodeSystem(code.system());
            res.setCode(code.code());
            res.setCodeDisplay(code.display());
            res.setValue(o.value());
            res.setUnit(o.unit());
            res.setReferenceRange(o.referenceRange());
            res.setFlag(ResultFlags.flag(o.interpretation(), o.value(), o.referenceRange()));
            res.setStatus(o.status() == null ? "FINAL" : o.status());
            res.setEffectiveAt(o.effectiveAt() == null ? now : o.effectiveAt());
            res.setReceivedAt(now);
            res.setSource("LIS");
            res.setBaseline(false);
            res.setClassification(classification(code));
            res.setRecordedBy(actor);
            results.save(res);
            ids.add(res.getId());
            if (!"NORMAL".equals(res.getFlag())) {
                abnormal++;
            }
            bus.publish(new E03Events.ResultReceived(res.getId().toString(), e.getEmployeeId(), res.getFlag()));
            notifyFollowUp(res);
        }
        if (order != null) {
            results.flush();
            orderService.onResults(order);
            if (abnormal > 0) {
                notifyAbnormal(order.getOrderedBy(), order.getCaseId(), order.getPlacerOrderNumber(), abnormal);
            }
        }
        audit.recordAs(actor, Roles.INTEGRATION, "RESULT_INGEST", "EMPLOYEE", e.getEmployeeId(), AuditService.Outcome.SUCCESS,
                "results=" + ids.size() + " abnormal=" + abnormal + (order == null ? "" : " order=" + order.getPlacerOrderNumber()));
        return new IngestResult(ids.size(), abnormal, ids);
    }

    /** Ingestion in its own transaction, so that one rejected group does not roll back the rest of an HL7 message. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public IngestResult ingestIsolated(LisResults r, String actor) {
        return ingest(r, actor);
    }

    String classification(CodeRef code) {
        return "LOINC".equals(code.system()) && restrictedCodes.contains(code.code())
                ? DataClassification.RESTRICTED_CLINICAL.name() : DataClassification.CLINICAL.name();
    }

    private void notifyAbnormal(String clinician, UUID caseId, String fallbackRef, int count) {
        if (clinician == null) {
            return;
        }
        StaffMember s = staff.findById(clinician).orElse(null);
        if (s == null || s.getEmail() == null || !s.isActive()) {
            return;
        }
        String ref = caseId == null ? fallbackRef : cases.findById(caseId).map(OhCase::getCaseRef).orElse(fallbackRef);
        notifications.enqueue(s.getUsername(), s.getEmail(), Channel.EMAIL, Priority.URGENT, ABNORMAL_TEMPLATE,
                Map.of("caseRef", ref, "level", "ordering clinician review", "portalLink", portalLink), ZoneOffset.UTC);
    }

    // ------------------------------------------------------------------ device capture (E03-BL-011)

    @Transactional
    public ResultView device(CurrentUser user, String employeeId, DeviceRequest r) {
        Employee e;
        if (user.hasAnyRole(Roles.OH_TEAM)) {
            e = guard.write(user, employeeId, DataClassification.CLINICAL, AccessPolicy.Action.CREATE, "RESULT_DEVICE").employee();
        } else {
            e = guard.employee(employeeId); // INTEGRATION (device middleware)
        }
        boolean manual = r.deviceId() == null || r.deviceId().isBlank();
        if (!manual && (r.calibrationId() == null || r.calibrationId().isBlank())) {
            throw ApiException.badRequest("calibration_required", "Device capture requires the device calibration ID");
        }
        if (!manual) {
            checkCalibration(r.deviceId(), LocalDate.now(clock));
        }
        if (r.caseId() != null) {
            OhCase c = user.hasAnyRole(Roles.OH_TEAM)
                    ? caseAccess.clinical(user, r.caseId(), AccessPolicy.Action.UPDATE, "RESULT_DEVICE")
                    : cases.findById(r.caseId()).orElseThrow(() -> ApiException.notFound("Case"));
            if (!c.getEmployeeId().equals(employeeId)) {
                throw ApiException.badRequest("case_employee_mismatch", "The case belongs to another employee");
            }
        }
        LabOrder order = null;
        if (r.orderId() != null) {
            order = orders.findById(r.orderId()).filter(o -> o.getEmployeeId().equals(employeeId))
                    .orElseThrow(() -> ApiException.notFound("Order"));
            if (!order.getCategory().equals(r.category())) {
                throw ApiException.badRequest("category_mismatch", "The order is for " + order.getCategory());
            }
        }
        Map<String, Object> m = new LinkedHashMap<>(r.measurements());
        Result baseline = null;
        DeviceRules.Evaluation ev = switch (r.category()) {
            case "AUDIOMETRY" -> {
                baseline = results.findByEmployeeIdAndCategoryAndBaselineTrue(employeeId, "AUDIOMETRY").stream()
                        .min(Comparator.comparing(Result::getEffectiveAt)).orElse(null);
                yield DeviceRules.audiometry(m, baseline == null ? null : json.map(baseline.getMeasurements()),
                        baseline == null ? null : baseline.getId().toString());
            }
            case "SPIROMETRY" -> DeviceRules.spirometry(m);
            case "VISION" -> DeviceRules.vision(m);
            default -> DeviceRules.ecg(m);
        };
        Instant now = clock.instant();
        Result res = new Result();
        res.setId(UUID.randomUUID());
        res.setOrderId(order == null ? null : order.getId());
        res.setEmployeeId(e.getEmployeeId());
        res.setCaseId(r.caseId() != null ? r.caseId() : order == null ? null : order.getCaseId());
        res.setCategory(r.category());
        res.setCodeSystem(ev.code().system());
        res.setCode(ev.code().code());
        res.setCodeDisplay(ev.code().display());
        res.setValue(ev.value());
        res.setUnit(ev.unit());
        res.setReferenceRange(ev.referenceRange());
        res.setFlag(ev.flag());
        res.setStatus("FINAL");
        res.setEffectiveAt(r.performedAt() == null ? now : r.performedAt());
        res.setReceivedAt(now);
        res.setSource(manual ? "MANUAL" : "DEVICE");
        res.setDeviceId(manual ? null : r.deviceId());
        res.setDeviceModel(r.deviceModel());
        res.setCalibrationId(r.calibrationId());
        res.setCalibrationDate(r.calibrationDate());
        res.setMeasurements(json.write(m));
        res.setDetail(json.write(ev.detail()));
        res.setBaseline(ev.baseline());
        res.setClassification(DataClassification.CLINICAL.name());
        res.setRecordedBy(user.username());
        results.save(res);
        bus.publish(new E03Events.ResultReceived(res.getId().toString(), employeeId, res.getFlag()));
        notifyFollowUp(res);
        if (order != null) {
            results.flush();
            orderService.onResults(order);
            if (!"NORMAL".equals(res.getFlag())) {
                notifyAbnormal(order.getOrderedBy(), order.getCaseId(), order.getPlacerOrderNumber(), 1);
            }
        }
        audit.record("RESULT_DEVICE", "RESULT", res.getId().toString(), AuditService.Outcome.SUCCESS,
                r.category() + " source=" + res.getSource() + " flag=" + res.getFlag());
        return view(res, now);
    }

    // ------------------------------------------------------------------ worklist & acknowledgement (E03-BL-012)

    @Transactional(readOnly = true)
    public List<ResultView> worklist(CurrentUser user, Boolean overdueOnly) {
        Instant now = clock.instant();
        Map<String, RecordGuard.Access> access = new HashMap<>();
        List<ResultView> out = new ArrayList<>();
        for (Result r : results.findUnacknowledgedAbnormal()) {
            RecordGuard.Access a = access.computeIfAbsent(r.getEmployeeId(), id -> {
                Employee e = guard.employee(id);
                return guard.canSeeClinical(user, e) ? new RecordGuard.Access(e, guard.canSeeRestricted(user, e)) : null;
            });
            if (a == null || !a.canSee(r.getClassification())) {
                continue;
            }
            ResultView v = view(r, now);
            if (overdueOnly == null || overdueOnly == v.overdue()) {
                out.add(v);
            }
        }
        audit.record("RESULT_WORKLIST", "RESULT", "worklist", AuditService.Outcome.SUCCESS, "rows=" + out.size());
        return out;
    }

    private Result load(CurrentUser user, UUID id, AccessPolicy.Action action, String auditAction) {
        Result r = results.findById(id).orElseThrow(() -> ApiException.notFound("Result"));
        RecordGuard.Access a = guard.write(user, r.getEmployeeId(), DataClassification.valueOf(r.getClassification()), action, auditAction);
        guard.requireVisible(a, r.getClassification(), "RESULT", id.toString(), auditAction);
        return r;
    }

    @Transactional
    public ResultView acknowledge(CurrentUser user, UUID id, AckRequest req) {
        Result r = load(user, id, AccessPolicy.Action.UPDATE, "RESULT_ACKNOWLEDGE");
        if (r.getAcknowledgedAt() != null) {
            throw ApiException.conflict("already_acknowledged", "Result was already acknowledged");
        }
        Instant now = clock.instant();
        r.setAcknowledgedBy(user.username());
        r.setAcknowledgedAt(now);
        r.setAckAction(req.action());
        r.setAckNote(req.note());
        if (r.getCaseId() != null) {
            timeline.record(r.getCaseId(), "RESULT_ACKNOWLEDGED", "Result acknowledged (" + req.action() + ")");
        }
        audit.record("RESULT_ACKNOWLEDGE", "RESULT", id.toString(), AuditService.Outcome.SUCCESS, req.action());
        return view(r, now);
    }

    /** Escalates unacknowledged abnormal results older than 72 h to the CMO(s): content-free notification + audit. */
    @Transactional
    public int escalateOverdue() {
        Instant now = clock.instant();
        List<Result> overdue = results.findOverdueNotEscalated(now.minus(overdueAfter));
        if (overdue.isEmpty()) {
            return 0;
        }
        List<StaffMember> cmos = staff.findByActiveTrueOrderByDisplayName().stream()
                .filter(s -> StaffMember.list(s.getRoles()).contains(Roles.CMO) && s.getEmail() != null).toList();
        for (Result r : overdue) {
            r.setEscalatedAt(now);
            String ref = r.getCaseId() != null ? cases.findById(r.getCaseId()).map(OhCase::getCaseRef).orElse(null) : null;
            if (ref == null && r.getOrderId() != null) {
                ref = orders.findById(r.getOrderId()).map(LabOrder::getPlacerOrderNumber).orElse(null);
            }
            String caseRef = ref == null ? "RES-" + r.getId().toString().substring(0, 8) : ref;
            for (StaffMember cmo : cmos) {
                notifications.enqueue(cmo.getUsername(), cmo.getEmail(), Channel.EMAIL, Priority.URGENT, OVERDUE_TEMPLATE,
                        Map.of("caseRef", caseRef, "level", "CMO review", "portalLink", portalLink), ZoneOffset.UTC);
            }
            audit.recordAs("system", "", "RESULT_OVERDUE_ESCALATED", "RESULT", r.getId().toString(), AuditService.Outcome.SUCCESS,
                    "notified=" + cmos.size());
        }
        return overdue.size();
    }

    // ------------------------------------------------------------------ result letter

    @Transactional
    public DocumentService.DocumentView letter(CurrentUser user, UUID id, LetterRequest req) {
        Result r = load(user, id, AccessPolicy.Action.CREATE, "RESULT_LETTER");
        Employee e = guard.employee(r.getEmployeeId());
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("employeeName", e.getDisplayName());
        fields.put("testName", r.getCodeDisplay());
        fields.put("performedOn", LocalDate.ofInstant(r.getEffectiveAt(), ZoneOffset.UTC).toString());
        fields.put("resultSummary", (r.getValue() == null ? "" : r.getValue()) + (r.getUnit() == null ? "" : " " + r.getUnit()));
        fields.put("interpretation", req != null && req.interpretation() != null ? req.interpretation()
                : "NORMAL".equals(r.getFlag()) ? "Your result is within the expected range."
                : "Your result is outside the expected range. Occupational Health will discuss it with you.");
        String action = req != null && req.action() != null ? req.action() : r.getAckAction() == null ? null : actionText(r.getAckAction());
        if (action != null) {
            fields.put("action", action);
        }
        fields.put("clinician", user.displayName() == null ? user.username() : user.displayName());
        DocumentService.DocumentView doc = documents.generate(user, "RESULT_LETTER", r.getEmployeeId(), r.getCaseId(), fields);
        r.setLetterDocumentId(doc.id());
        audit.record("RESULT_LETTER", "RESULT", id.toString(), AuditService.Outcome.SUCCESS, "document=" + doc.id());
        return doc;
    }

    static String actionText(String ackAction) {
        return switch (ackAction) {
            case "REPEAT_TEST" -> "We will arrange a repeat test.";
            case "REFER" -> "We will refer you for further assessment.";
            case "FOLLOW_UP_APPOINTMENT" -> "Please book a follow-up appointment with Occupational Health.";
            case "FITNESS_REVIEW" -> "Occupational Health will review your fitness for your role with you.";
            default -> "No further action is needed.";
        };
    }
}
