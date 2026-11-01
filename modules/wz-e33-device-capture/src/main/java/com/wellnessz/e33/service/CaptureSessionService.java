package com.wellnessz.e33.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.wellnessz.casekernel.device.DeviceCatalogueLookup;
import com.wellnessz.casekernel.device.DeviceCodes;
import com.wellnessz.casekernel.device.DeviceObservation;
import com.wellnessz.casekernel.device.DeviceObservationGateway;
import com.wellnessz.casekernel.device.DeviceObservationSink;
import com.wellnessz.casekernel.device.DeviceSpis;
import com.wellnessz.casekernel.support.EmployeeRecordAccess;
import com.wellnessz.common.error.ApiException;
import com.wellnessz.common.model.DataClassification;
import com.wellnessz.common.model.Roles;
import com.wellnessz.common.text.ClinicalContentException;
import com.wellnessz.documents.OcrProvider;
import com.wellnessz.e33.E33Events;
import com.wellnessz.e33.domain.CaptureStateMachine;
import com.wellnessz.e33.domain.CaptureStateMachine.State;
import com.wellnessz.e33.domain.CaptureStateMachine.Trigger;
import com.wellnessz.e33.domain.Comparison;
import com.wellnessz.e33.domain.FallbackChain;
import com.wellnessz.e33.domain.OcrFields;
import com.wellnessz.e33.domain.VerificationRule;
import com.wellnessz.e33.model.CaptureAnnotation;
import com.wellnessz.e33.model.CaptureObservation;
import com.wellnessz.e33.model.CaptureSession;
import com.wellnessz.e33.model.E33CaptureAnnotationRepository;
import com.wellnessz.e33.model.E33CaptureObservationRepository;
import com.wellnessz.e33.model.E33CaptureSessionRepository;
import com.wellnessz.e33.model.E33OcrImageRepository;
import com.wellnessz.e33.model.FormRule;
import com.wellnessz.e33.model.OcrImage;
import com.wellnessz.integration.outbox.EventBus;
import com.wellnessz.masterdata.Employee;
import com.wellnessz.security.abac.AccessPolicy;
import com.wellnessz.security.audit.AuditService;
import com.wellnessz.security.identity.CurrentUser;

/**
 * Capture sessions of the OH forms (E33-F01 / F02 / F06 / F11, contract 14.1–14.4 and 14.7): open on a device (station-aware,
 * context push), live stream, preview with quality flags and provenance, accept / reject / re-test with the form's verification
 * rule, the fall-back chain (alternate device → photo / OCR → manual with attestation), physician sign-off of device
 * interpretations, annotations, OCR upload and the comparison summary.
 */
@Service
public class CaptureSessionService {

    static final Set<String> REJECT_REASONS = Set.of("RETEST", "WRONG_EMPLOYEE", "ARTEFACT", "DEVICE_FAULT", "OTHER");
    static final long MAX_IMAGE_BYTES = 10L * 1024 * 1024;

    // ------------------------------------------------------------------ commands

    public record OpenCommand(String formCode, String formVersion, String fieldGroup, String employeeId, UUID caseId, String orderRef,
                              String deviceInstanceId, String siteCode, String stationCode) {
    }

    public record AcceptCommand(List<String> acknowledgedFlags, String note) {
    }

    public record RejectCommand(String reasonCode, String note) {
    }

    public record FallbackCommand(String path, String reasonCode, String deviceInstanceId, UUID imageArchiveId, Map<String, Object> values,
                                  BigDecimal ocrConfidence, FallbackChain.Attestation attested) {
    }

    public record Routed(UUID observationId, String recordType, String recordId) {
    }

    public record AcceptResult(UUID sessionId, String state, String routedRecordType, String routedRecordId, List<Routed> routed,
                               String interpretationStatus) {
    }

    public record OcrResult(UUID imageArchiveId, String sha256, boolean ocrEngine, List<OcrFields.Field> fields) {
    }

    private final E33CaptureSessionRepository sessions;
    private final E33CaptureObservationRepository observations;
    private final E33CaptureAnnotationRepository annotations;
    private final E33OcrImageRepository images;
    private final FormRegistry forms;
    private final DeviceListService devices;
    private final CaptureTransitions transitions;
    private final CaptureEventHub hub;
    private final SummaryReleaser releaser;
    private final DeviceSpis spis;
    private final EmployeeRecordAccess records;
    private final ObjectProvider<OcrProvider> ocr;
    private final EventBus bus;
    private final AuditService audit;
    private final CaptureSupport support;

    public CaptureSessionService(E33CaptureSessionRepository sessions, E33CaptureObservationRepository observations,
                                 E33CaptureAnnotationRepository annotations, E33OcrImageRepository images, FormRegistry forms,
                                 DeviceListService devices, CaptureTransitions transitions, CaptureEventHub hub, SummaryReleaser releaser,
                                 DeviceSpis spis, EmployeeRecordAccess records, ObjectProvider<OcrProvider> ocr, EventBus bus,
                                 AuditService audit, CaptureSupport support) {
        this.sessions = sessions;
        this.observations = observations;
        this.annotations = annotations;
        this.images = images;
        this.forms = forms;
        this.devices = devices;
        this.transitions = transitions;
        this.hub = hub;
        this.releaser = releaser;
        this.spis = spis;
        this.records = records;
        this.ocr = ocr;
        this.bus = bus;
        this.audit = audit;
        this.support = support;
    }

    // ------------------------------------------------------------------ open

    @Transactional
    public CaptureViews.SessionView open(CurrentUser user, OpenCommand cmd) {
        support.requireEnabled();
        FormRegistry.Form form = forms.require(cmd.formCode());
        if (cmd.formVersion() != null && !cmd.formVersion().equals(form.formVersion())) {
            throw CaptureSupport.unprocessable("form_version_mismatch", "The form version is not the certified version of the binding");
        }
        if (!forms.hasGroup(form.formCode(), cmd.fieldGroup())) {
            throw CaptureSupport.unprocessable("binding_field_unknown", "Unknown field group for the form");
        }
        boolean restricted = form.verification().restricted();
        requireOpenRole(user, restricted);
        if (restricted && (cmd.orderRef() == null || cmd.orderRef().isBlank())) {
            throw CaptureSupport.unprocessable("order_required", "Restricted captures need the programme test reference (order)");
        }
        if (restricted) {
            support.employee(cmd.employeeId()).orElseThrow(() -> {
                audit.record("DEVICE_CAPTURE_OPEN", "EMPLOYEE", cmd.employeeId(), AuditService.Outcome.DENIED, "Unknown employee");
                return ApiException.notFound("Employee");
            });
        } else {
            records.requireClinical(user, cmd.employeeId(), DataClassification.CLINICAL, AccessPolicy.Action.CREATE, "DEVICE_CAPTURE_OPEN");
        }
        boolean deviceless = cmd.deviceInstanceId() == null || cmd.deviceInstanceId().isBlank();
        DeviceListService.DeviceChoice choice = null;
        if (deviceless) {
            // fall-back paths without a device (photo / OCR, manual with attestation – contract 14.3) only when the station
            // has no available device for the form; restricted categories keep their rules (collector only, no photo path)
            if (devices.devices(form.formCode(), cmd.siteCode(), cmd.stationCode()).stream().anyMatch(DeviceListService.DeviceChoice::available)) {
                throw CaptureSupport.unprocessable("device_available", "A device is available at the station – capture from the device");
            }
        } else {
            choice = devices.choice(cmd.deviceInstanceId(), form.formCode(), cmd.siteCode());
            if ("BUSY".equals(choice.status())) {
                throw busy(choice.heldBy());
            }
        }
        Instant now = support.now();
        CaptureSession s = new CaptureSession();
        s.setId(UUID.randomUUID());
        s.setMode("FORM");
        s.setEmployeeId(cmd.employeeId());
        s.setCaseId(cmd.caseId());
        s.setOrderRef(blankToNull(cmd.orderRef()));
        s.setFormCode(form.formCode());
        s.setFormVersion(form.formVersion());
        s.setFieldGroup(cmd.fieldGroup());
        s.setCategory(form.category().name());
        s.setRestricted(restricted);
        s.setDeviceInstanceId(deviceless ? null : cmd.deviceInstanceId());
        s.setOriginalDeviceInstanceId(deviceless ? null : cmd.deviceInstanceId());
        s.setOperator(user.username());
        s.setSiteCode(cmd.siteCode());
        s.setStationCode(cmd.stationCode());
        s.setOpenedAt(now);
        s.setLastActivityAt(now);
        s.setExpiresAt(now.plusSeconds(60L * support.sessionTimeoutMinutes()));
        String detail;
        if (deviceless) {
            s.setState(State.FALLBACK_OFFERED.name());
            s.setFallbackReason("DEVICE_UNAVAILABLE");
            s.setContextPush("NONE");
            detail = "no_device_at_station";
        } else if (choice.available()) {
            s.setState(State.LISTENING.name());
            s.setContextPush(contextPush(devices.instance(cmd.deviceInstanceId()).orElse(null)));
            detail = "push=" + s.getContextPush();
        } else {
            s.setState(State.FALLBACK_OFFERED.name());
            s.setFallbackReason("OFFLINE".equals(choice.status()) ? "CONNECTION_FAILED" : "DEVICE_LOCKED");
            s.setContextPush("NONE");
            detail = choice.lockReason();
        }
        try {
            sessions.saveAndFlush(s);
        } catch (DataIntegrityViolationException e) {
            throw busy(null);
        }
        transitions.opened(s, user.username(), detail);
        if (State.LISTENING.name().equals(s.getState())) {
            bus.publish(new E33Events.CaptureSessionOpened(s.getId().toString(), s.getDeviceInstanceId(), s.getFormCode(), s.getMode(),
                    s.getContextPush(), s.getSiteCode()));
        }
        return view(user, s);
    }

    private static void requireOpenRole(CurrentUser user, boolean restricted) {
        if (restricted && !user.hasRole(Roles.COLLECTOR)) {
            throw ApiException.forbidden("Restricted programme captures are opened by the collector");
        }
        if (!restricted && !user.hasAnyRole(Roles.CLINICIANS)) {
            throw ApiException.forbidden("Only OH clinicians open captures for this form");
        }
    }

    static ApiException busy(String heldBy) {
        return new ApiException(HttpStatus.CONFLICT, "device_busy", "The device is in use by another capture session") {
            private static final long serialVersionUID = 1L;

            @Override
            public Map<String, Object> properties() {
                return heldBy == null ? Map.of() : Map.of("heldBy", heldBy);
            }
        };
    }

    /**
     * Worklist / demographics push when the driver supports it (E31-HLR-08): {@code START_ACQUISITION} for drivers that start
     * from the form, {@code PUSH_WORKLIST} for any worklist capability, otherwise {@code NONE}.
     */
    String contextPush(DeviceCatalogueLookup.DeviceInstance i) {
        if (i == null) {
            return "NONE";
        }
        Object start = i.parameters().get("startFromForm");
        Object push = i.parameters().get("worklistPush");
        if (i.driverPackageId() != null) {
            Optional<DeviceCatalogueLookup.DriverPackage> pkg = spis.catalogue().driverPackage(i.driverPackageId(), i.driverVersion());
            if (pkg.isPresent() && pkg.get().manifestJson() != null) {
                Object caps = support.readMap(pkg.get().manifestJson()).get("capabilities");
                if (caps instanceof Map<?, ?> m) {
                    start = start == null ? m.get("startFromForm") : start;
                    push = push == null ? m.get("worklistPush") : push;
                }
            }
        }
        if (Boolean.TRUE.equals(start) || "true".equals(String.valueOf(start))) {
            return "START_ACQUISITION";
        }
        return push != null && !"NONE".equals(String.valueOf(push)) ? "PUSH_WORKLIST" : "NONE";
    }

    // ------------------------------------------------------------------ read and stream

    @Transactional
    public CaptureViews.SessionView get(CurrentUser user, UUID id) {
        support.requireEnabled();
        CaptureSession s = require(id);
        requireView(user, s);
        expireIfDue(s);
        CaptureViews.SessionView v = view(user, s);
        if (v.preview() != null && !v.restrictedReading()) {
            audit.record("DEVICE_OBSERVATION_READ", "DEVICE_CAPTURE_SESSION", s.getId().toString(), AuditService.Outcome.SUCCESS,
                    s.getCategory() + " values=" + v.preview().values().size());
        }
        return v;
    }

    @Transactional
    public SseEmitter events(CurrentUser user, UUID id) {
        support.requireEnabled();
        CaptureSession s = require(id);
        requireView(user, s);
        expireIfDue(s);
        List<CaptureEventHub.Event> initial = new ArrayList<>();
        initial.add(transitions.stateEvent(s));
        if (State.PREVIEW.name().equals(s.getState()) && s.getObservationId() != null) {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("sessionId", s.getId().toString());
            data.put("observationId", s.getObservationId().toString());
            data.put("status", s.getObservationStatus());
            data.put("flags", support.readFlags(s.getFlags()).stream().map(f -> f.outcome() + ":" + f.code()).toList());
            initial.add(new CaptureEventHub.Event("preview", data));
        }
        long timeout = 60_000L * (support.sessionTimeoutMinutes() + 1);
        SseEmitter emitter = hub.subscribe(s.getId(), timeout, initial);
        if (State.valueOf(s.getState()).isFinal()) {
            emitter.complete();
        }
        return emitter;
    }

    // ------------------------------------------------------------------ accept / reject / cancel

    @Transactional
    public AcceptResult accept(CurrentUser user, UUID id, AcceptCommand cmd) {
        support.requireEnabled();
        return acceptSession(user, require(id), cmd);
    }

    /**
     * Accept without its own transaction boundary (the batch accept-all calls it per session inside one transaction, so a
     * refused session does not mark the whole batch for rollback).
     */
    public AcceptResult acceptSession(CurrentUser user, CaptureSession s, AcceptCommand cmd) {
        requireView(user, s);
        FormRule rule = forms.rule(s.getFormCode());
        requireVerifier(user, s, rule, "DEVICE_CAPTURE_ACCEPT");
        if (!CaptureStateMachine.allowed(State.valueOf(s.getState()), Trigger.ACCEPT)) {
            CaptureStateMachine.next(State.valueOf(s.getState()), Trigger.ACCEPT);
        }
        List<CaptureObservation> pending = undecided(s);
        if (pending.isEmpty()) {
            throw new ApiException(HttpStatus.CONFLICT, "observation_not_pending", "There is no reading waiting for verification");
        }
        DeviceObservationGateway gateway = spis.gateway();
        Map<CaptureObservation, DeviceObservationGateway.ObservationPreview> previews = new LinkedHashMap<>();
        for (CaptureObservation o : pending) {
            DeviceObservationGateway.ObservationPreview p = gateway.preview(o.getObservationId())
                    .orElseThrow(() -> new ApiException(HttpStatus.CONFLICT, "observation_not_pending", "The reading is no longer available"));
            VerificationRule.requirePostable(p.observation().qualityFlags(), cmd.acknowledgedFlags());
            previews.put(o, p);
        }
        String note = clinicalNote(cmd.note());
        List<Routed> routed = new ArrayList<>();
        Instant now = support.now();
        for (Map.Entry<CaptureObservation, DeviceObservationGateway.ObservationPreview> e : previews.entrySet()) {
            CaptureObservation o = e.getKey();
            DeviceObservation obs = e.getValue().observation();
            DeviceObservationSink.Delivery d = gateway.accept(user.username(), o.getObservationId(), s.getId(), note);
            if (d.status() == DeviceObservationSink.Delivery.Status.REJECTED) {
                throw CaptureSupport.unprocessable("sink_rejected", "The form rejected the reading (" + d.code() + ")");
            }
            o.setDecision("ACCEPTED");
            o.setDecidedBy(user.username());
            o.setDecidedAt(now);
            o.setStatus("ROUTED");
            o.setRoutedRecordType(d.recordType());
            o.setRoutedRecordId(d.recordId());
            fill(o, obs);
            routed.add(new Routed(o.getObservationId(), d.recordType(), d.recordId()));
        }
        Routed last = routed.get(routed.size() - 1);
        s.setVerifiedBy(user.username());
        s.setVerifiedAt(now);
        s.setNote(note);
        s.setRoutedRecordType(last.recordType());
        s.setRoutedRecordId(last.recordId());
        s.setObservationStatus("ROUTED");
        if (s.getPath() == null) {
            s.setPath(FallbackChain.DEVICE);
        }
        if (!VerificationRule.roles(rule.getSignOffRoles()).isEmpty()) {
            s.setInterpretationStatus("UNVERIFIED");
        }
        transitions.apply(s, Trigger.ACCEPT, user.username(), "ACCEPT",
                "observations=" + routed.size() + " acknowledged=" + (cmd.acknowledgedFlags() == null ? 0 : cmd.acknowledgedFlags().size()), false);
        releaser.release(rule, s, new ArrayList<>(previews.keySet()), user.username());
        return new AcceptResult(s.getId(), s.getState(), last.recordType(), last.recordId(), routed, s.getInterpretationStatus());
    }

    @Transactional
    public CaptureViews.SessionView reject(CurrentUser user, UUID id, RejectCommand cmd) {
        support.requireEnabled();
        if (cmd.reasonCode() == null || !REJECT_REASONS.contains(cmd.reasonCode())) {
            throw ApiException.badRequest("validation_failed", "Unknown reason code");
        }
        CaptureSession s = require(id);
        requireView(user, s);
        FormRule rule = forms.rule(s.getFormCode());
        requireVerifier(user, s, rule, "DEVICE_CAPTURE_REJECT");
        boolean retest = "RETEST".equals(cmd.reasonCode());
        Trigger trigger = retest ? Trigger.RETEST : Trigger.REJECT;
        CaptureStateMachine.next(State.valueOf(s.getState()), trigger);
        String note = clinicalNote(cmd.note());
        rejectUndecided(s, user.username(), cmd.reasonCode(), note, true);
        s.setNote(note);
        if (retest) {
            s.setRetestCount(s.getRetestCount() + 1);
            clearPreview(s);
            s.setExpiresAt(support.now().plusSeconds(60L * support.sessionTimeoutMinutes()));
        } else {
            s.setRejectReason(cmd.reasonCode());
        }
        transitions.apply(s, trigger, user.username(), retest ? "RETEST" : "REJECT", cmd.reasonCode(), false);
        return view(user, s);
    }

    @Transactional
    public CaptureViews.SessionView cancel(CurrentUser user, UUID id) {
        support.requireEnabled();
        CaptureSession s = require(id);
        if (!own(user, s) && !user.hasRole(Roles.NURSING_HEAD)) {
            requireView(user, s);
            throw ApiException.forbidden("Only the operator of the session can cancel it");
        }
        CaptureStateMachine.next(State.valueOf(s.getState()), Trigger.CANCEL);
        rejectUndecided(s, user.username(), "OTHER", null, false);
        transitions.apply(s, Trigger.CANCEL, user.username(), "CANCEL", null, false);
        return view(user, s);
    }

    // ------------------------------------------------------------------ fall-back chain

    @Transactional
    public CaptureViews.SessionView fallback(CurrentUser user, UUID id, FallbackCommand cmd) {
        support.requireEnabled();
        CaptureSession s = require(id);
        if (!own(user, s)) {
            requireView(user, s);
            throw ApiException.forbidden("Only the operator of the session can choose a fall-back");
        }
        State state = State.valueOf(s.getState());
        Trigger choose = FallbackChain.ALTERNATE_DEVICE.equals(cmd.path()) ? Trigger.CHOOSE_DEVICE : Trigger.CHOOSE_ENTRY;
        CaptureStateMachine.next(state, choose);
        List<FallbackChain.Option> offered = offer(user, s);
        String path = FallbackChain.validate(cmd.path(), cmd.reasonCode(), cmd.deviceInstanceId(), offered, cmd.values(), cmd.imageArchiveId(),
                cmd.attested(), support.today());
        rejectUndecided(s, user.username(), "DEVICE_FAULT", null, false);
        clearPreview(s);
        s.setFallbackReason(cmd.reasonCode());
        if (FallbackChain.ALTERNATE_DEVICE.equals(path)) {
            DeviceListService.DeviceChoice alt = devices.choice(cmd.deviceInstanceId(), s.getFormCode(), s.getSiteCode());
            if (!alt.available()) {
                throw busy(alt.heldBy());
            }
            s.setDeviceInstanceId(alt.deviceInstanceId());
            s.setPath(FallbackChain.ALTERNATE_DEVICE);
            s.setExpiresAt(support.now().plusSeconds(60L * support.sessionTimeoutMinutes()));
            s.setContextPush(contextPush(devices.instance(alt.deviceInstanceId()).orElse(null)));
            try {
                sessions.saveAndFlush(s);
            } catch (DataIntegrityViolationException e) {
                throw busy(null);
            }
            transitions.apply(s, Trigger.CHOOSE_DEVICE, user.username(), "FALLBACK", path + " " + cmd.reasonCode(), false);
            bus.publish(new E33Events.CaptureSessionOpened(s.getId().toString(), s.getDeviceInstanceId(), s.getFormCode(), s.getMode(),
                    s.getContextPush(), s.getSiteCode()));
            return view(user, s);
        }
        Map<String, Object> values = checkValues(s.getFormCode(), cmd.values());
        if (FallbackChain.OCR_VERIFIED.equals(path) && !images.existsById(cmd.imageArchiveId())) {
            throw CaptureSupport.unprocessable("image_unknown", "Upload the photo through /device-capture/ocr first");
        }
        // photo / manual entry no longer holds the failed device (it stays recorded as the original device)
        String failedDevice = s.getDeviceInstanceId();
        s.setDeviceInstanceId(null);
        transitions.apply(s, Trigger.CHOOSE_ENTRY, user.username(), "FALLBACK", path + " " + cmd.reasonCode(), false);
        DeviceCodes.CaptureMethod method = DeviceCodes.CaptureMethod.valueOf(path);
        FallbackChain.Attestation a = FallbackChain.MANUAL_ATTESTED.equals(path) ? cmd.attested() : null;
        UUID observationId = spis.gateway().submitFallback(user.username(), new DeviceObservationGateway.FallbackSubmission(s.getId(), method,
                failedDevice, a == null ? null : a.model(), a == null ? null : a.serial(), a == null ? null : a.calibrationId(),
                a == null ? null : a.calibrationDate(), DeviceCodes.Category.valueOf(s.getCategory()), s.getEmployeeId(), s.getFormCode(),
                values, FallbackChain.OCR_VERIFIED.equals(path) ? cmd.imageArchiveId() : null,
                FallbackChain.OCR_VERIFIED.equals(path) ? cmd.ocrConfidence() : null, a == null ? null : FallbackChain.ATTESTATION));
        CaptureObservation o = observations.findByObservationId(observationId).orElseGet(() -> newObservation(s, observationId));
        o.setCaptureMethod(method.name());
        o.setDeviceInstanceId(failedDevice);
        o.setStatus("PENDING_VERIFICATION");
        observations.save(o);
        s.setPath(path);
        s.setObservationId(observationId);
        s.setObservationStatus("PENDING_VERIFICATION");
        transitions.apply(s, Trigger.SUBMIT_ENTRY, user.username(), "FALLBACK_SUBMIT", path, false);
        transitions.previewReady(s);
        return view(user, s);
    }

    /** Values of a fall-back keyed by component key (last segment of a form field path) or by the field path itself. */
    private Map<String, Object> checkValues(String formCode, Map<String, Object> values) {
        Set<String> known = new java.util.HashSet<>();
        for (FormRegistry.FieldGroup g : forms.require(formCode).fieldGroups()) {
            for (FormRegistry.Field f : g.fields()) {
                known.add(f.path());
                known.add(f.path().startsWith("measurements.") ? f.path().substring("measurements.".length())
                        : f.path().startsWith("measures.") ? f.path().substring("measures.".length()) : f.path());
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        values.forEach((k, v) -> {
            if (!known.contains(k)) {
                throw CaptureSupport.unprocessable("binding_field_unknown", "Unknown field '" + k + "' for the form");
            }
            if (!(v instanceof Number) && !(v instanceof Boolean) && !(v instanceof String str && str.length() <= 100)) {
                throw ApiException.badRequest("validation_failed", "Field '" + k + "' must be a number, a flag or a short text");
            }
            out.put(k, v);
        });
        return out;
    }

    /** The fall-back chain on offer for a session (empty for viewers other than the operator). */
    List<FallbackChain.Option> offer(CurrentUser user, CaptureSession s) {
        boolean ocrOk = devices.ocrAvailable(DeviceCodes.Category.valueOf(s.getCategory()), s.getSiteCode(), s.getStationCode());
        return FallbackChain.offer(devices.alternates(s.getFormCode(), s.getSiteCode(), s.getStationCode(), s.getDeviceInstanceId()), ocrOk,
                s.isRestricted(), user.hasRole(Roles.COLLECTOR));
    }

    // ------------------------------------------------------------------ sign-off, annotations, OCR, comparison

    @Transactional
    public CaptureViews.SessionView signOff(CurrentUser user, UUID id, String note) {
        support.requireEnabled();
        CaptureSession s = require(id);
        requireView(user, s);
        FormRule rule = forms.rule(s.getFormCode());
        VerificationRule.requireSignOff(user, VerificationRule.roles(rule.getSignOffRoles()));
        records.requireClinical(user, s.getEmployeeId(), DataClassification.CLINICAL, AccessPolicy.Action.UPDATE, "DEVICE_CAPTURE_SIGN_OFF");
        if (!State.ACCEPTED.name().equals(s.getState()) || !"UNVERIFIED".equals(s.getInterpretationStatus())) {
            throw new ApiException(HttpStatus.CONFLICT, "observation_not_pending", "Only an accepted capture with an unverified interpretation can be signed off");
        }
        s.setInterpretationStatus("SIGNED");
        s.setSignedBy(user.username());
        s.setSignedAt(support.now());
        String n = clinicalNote(note);
        if (n != null) {
            annotations.save(new CaptureAnnotation(s.getId(), user.username(), support.now(), n));
        }
        audit.record("DEVICE_CAPTURE_SIGN_OFF", "DEVICE_CAPTURE_SESSION", s.getId().toString(), AuditService.Outcome.SUCCESS,
                s.getFormCode() + " interpretation SIGNED");
        bus.publish(new E33Events.InterpretationSigned(s.getId().toString(), s.getObservationId() == null ? null : s.getObservationId().toString(),
                s.getRoutedRecordType(), s.getRoutedRecordId(), user.username()));
        hub.publish(s.getId(), transitions.stateEvent(s));
        releaser.release(rule, s, observations.findBySessionIdOrderByReceivedAtAsc(s.getId()).stream()
                .filter(o -> "ACCEPTED".equals(o.getDecision())).toList(), user.username());
        return view(user, s);
    }

    /**
     * One line of the physician sign-off queue: who and what awaits sign-off, without values (the detail screen reads the
     * session through {@code GET /device-capture/sessions/{id}}, which is authorised and audited).
     */
    public record SignOffItem(UUID sessionId, String category, String formCode, String employeeId, String employeeName, String siteCode,
                              String stationCode, String deviceName, Instant acceptedAt, String verifiedBy) {
    }

    /**
     * Captures awaiting physician sign-off (ECG / ABPM interpretations, state ACCEPTED + interpretation UNVERIFIED) for the
     * signing roles of each form, filtered by ABAC on the employee (clinical read) and optionally by site; oldest first.
     */
    @Transactional(readOnly = true)
    public List<SignOffItem> pendingSignOffs(CurrentUser user, String siteCode) {
        support.requireEnabled();
        List<SignOffItem> out = new ArrayList<>();
        for (CaptureSession s : sessions.findTop500ByStateAndInterpretationStatusOrderByVerifiedAtAsc(State.ACCEPTED.name(), "UNVERIFIED")) {
            if (siteCode != null && !siteCode.equals(s.getSiteCode()) || s.isRestricted()) {
                continue;
            }
            if (VerificationRule.roles(forms.rule(s.getFormCode()).getSignOffRoles()).stream().noneMatch(user::hasRole)) {
                continue;
            }
            Optional<Employee> e = support.employee(s.getEmployeeId());
            if (e.isEmpty() || !records.canSeeClinical(user, e.get(), DataClassification.CLINICAL)) {
                continue;
            }
            String device = s.getOriginalDeviceInstanceId() == null ? null : DeviceListService.displayName(DeviceCodes.Category.valueOf(s.getCategory()),
                    s.getStationCode());
            out.add(new SignOffItem(s.getId(), s.getCategory(), s.getFormCode(), s.getEmployeeId(), e.get().getDisplayName(), s.getSiteCode(),
                    s.getStationCode(), device, s.getVerifiedAt(), s.getVerifiedBy()));
        }
        audit.record("DEVICE_SIGN_OFF_QUEUE_READ", "DEVICE_CAPTURE_SESSION", "sign-off-queue", AuditService.Outcome.SUCCESS, "rows=" + out.size());
        return out;
    }

    @Transactional
    public CaptureViews.AnnotationView annotate(CurrentUser user, UUID id, String text) {
        support.requireEnabled();
        CaptureSession s = require(id);
        requireView(user, s);
        if (s.isRestricted() && !VerificationRule.maySeeRestricted(user, own(user, s), support.actsAsMro(user))) {
            throw ApiException.forbidden("Restricted captures are annotated by the collector or the MRO");
        }
        if (text == null || text.isBlank() || text.length() > 2000) {
            throw ApiException.badRequest("validation_failed", "Annotation text of 1–2000 characters is required");
        }
        ClinicalContentException.screen("text", text);
        CaptureAnnotation a = annotations.save(new CaptureAnnotation(s.getId(), user.username(), support.now(), text.strip()));
        audit.record("DEVICE_CAPTURE_ANNOTATE", "DEVICE_CAPTURE_SESSION", s.getId().toString(), AuditService.Outcome.SUCCESS, s.getFormCode());
        return new CaptureViews.AnnotationView(a.getId(), a.getAuthor(), a.getCreatedAt(), a.getText());
    }

    @Transactional
    public OcrResult ocr(CurrentUser user, byte[] bytes, String contentType, String deviceInstanceId, String modelCode, String category) {
        support.requireEnabled();
        if (bytes == null || bytes.length == 0) {
            throw ApiException.badRequest("validation_failed", "An image is required");
        }
        if (bytes.length > MAX_IMAGE_BYTES) {
            throw new ApiException(HttpStatus.CONTENT_TOO_LARGE, "payload_too_large", "Images up to 10 MB are accepted");
        }
        String type = contentType == null ? "" : contentType.toLowerCase(java.util.Locale.ROOT);
        if (!type.equals("image/png") && !type.equals("image/jpeg")) {
            throw new ApiException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "unsupported_media_type", "PNG or JPEG images only");
        }
        Optional<DeviceCatalogueLookup.DeviceInstance> instance = devices.instance(deviceInstanceId);
        if (deviceInstanceId != null && instance.isEmpty()) {
            throw ApiException.notFound("Device");
        }
        String cat = category != null ? category
                : instance.flatMap(i -> i.categories().stream().map(Enum::name).sorted().findFirst()).orElse(null);
        if (cat == null) {
            throw ApiException.badRequest("validation_failed", "A device instance or a category is required");
        }
        String sha = sha256(bytes);
        OcrImage img = images.save(new OcrImage(deviceInstanceId, instance.map(DeviceCatalogueLookup.DeviceInstance::modelCode).orElse(modelCode),
                user.username(), support.now(), type, bytes.length, sha, java.util.Base64.getEncoder().encodeToString(bytes)));
        OcrProvider engine = ocr.getIfAvailable();
        String text = engine == null ? "" : engine.extractText(bytes, type).orElse("");
        List<OcrFields.Field> fields = OcrFields.parse(cat, text, support.ocrMinConfidence());
        audit.record("DEVICE_CAPTURE_OCR_UPLOAD", "DEVICE_OCR_IMAGE", img.getId().toString(), AuditService.Outcome.SUCCESS,
                cat + " fields=" + fields.size() + " engine=" + (engine != null));
        return new OcrResult(img.getId(), sha, engine != null, fields);
    }

    @Transactional(readOnly = true)
    public Comparison.Summary summary(CurrentUser user, String employeeId, String category) {
        support.requireEnabled();
        if (category == null || !Comparison.CATEGORIES.contains(category)) {
            throw ApiException.badRequest("validation_failed", "Category must be one of " + Comparison.CATEGORIES);
        }
        records.requireClinical(user, employeeId, DataClassification.CLINICAL, AccessPolicy.Action.READ, "DEVICE_OBSERVATION_READ");
        Comparison.Summary out = comparison(employeeId, category, null);
        audit.record("DEVICE_OBSERVATION_READ", "EMPLOYEE", employeeId, AuditService.Outcome.SUCCESS, category + " points=" + out.trend().size());
        return out;
    }

    Comparison.Summary comparison(String employeeId, String category, UUID excludeSession) {
        List<Comparison.Point> points = observations
                .findByEmployeeIdAndCategoryAndDecisionInOrderByEffectiveAtAsc(employeeId, category, List.of("ACCEPTED", "AUTO_POSTED")).stream()
                .filter(o -> excludeSession == null || !excludeSession.equals(o.getSessionId()))
                .map(o -> new Comparison.Point(o.getEffectiveAt() == null ? o.getReceivedAt() : o.getEffectiveAt(),
                        SummaryReleaser.numeric(support.readMap(o.getValuesJson())),
                        support.readFlags(o.getFlags()).stream().anyMatch(f -> f.outcome() != DeviceCodes.GateOutcome.PASS
                                && f.outcome() != DeviceCodes.GateOutcome.INFO) ? "FLAGGED" : null))
                .toList();
        return Comparison.build(category, points);
    }

    // ------------------------------------------------------------------ expiry (job and lazy)

    /** Expires waiting form / batch sessions past their time-out; returns the number expired. */
    @Transactional
    public int expireDue() {
        int n = 0;
        for (CaptureSession s : sessions.findByStateInAndExpiresAtBefore(List.of("LISTENING", "FALLBACK_OFFERED", "FALLBACK_ENTRY"), support.now())) {
            if (!"KIOSK".equals(s.getMode())) {
                transitions.apply(s, Trigger.TIMEOUT, CaptureTransitions.MACHINE_ACTOR, "TIMEOUT", null, true);
                n++;
            }
        }
        return n;
    }

    private void expireIfDue(CaptureSession s) {
        State st = State.valueOf(s.getState());
        if (st.waiting() && !"KIOSK".equals(s.getMode()) && s.getExpiresAt().isBefore(support.now())) {
            transitions.apply(s, Trigger.TIMEOUT, CaptureTransitions.MACHINE_ACTOR, "TIMEOUT", null, true);
        }
    }

    // ------------------------------------------------------------------ helpers shared with batch / kiosk / notices

    public CaptureSession require(UUID id) {
        return sessions.findById(id).orElseThrow(() -> ApiException.notFound("Capture session"));
    }

    static boolean own(CurrentUser user, CaptureSession s) {
        return s.getOperator().equals(user.username());
    }

    /** Session operator, the MRO (restricted), or an OH clinician with clinical access to the employee; otherwise audited 404. */
    void requireView(CurrentUser user, CaptureSession s) {
        if (own(user, s) || (s.isRestricted() && support.actsAsMro(user))) {
            return;
        }
        if (user.hasAnyRole(Roles.CLINICIANS)) {
            Optional<Employee> e = support.employee(s.getEmployeeId());
            if (e.isPresent() && records.canSeeClinical(user, e.get(), DataClassification.CLINICAL)) {
                return;
            }
        }
        audit.record("DEVICE_OBSERVATION_READ", "DEVICE_CAPTURE_SESSION", s.getId().toString(), AuditService.Outcome.DENIED, "no access");
        throw ApiException.notFound("Capture session");
    }

    void requireVerifier(CurrentUser user, CaptureSession s, FormRule rule, String auditAction) {
        boolean mro = support.actsAsMro(user);
        try {
            VerificationRule.requireVerifier(user, VerificationRule.roles(rule.getVerifierRoles()), s.isRestricted(), own(user, s), mro);
        } catch (ApiException e) {
            audit.record(auditAction, "DEVICE_CAPTURE_SESSION", s.getId().toString(), AuditService.Outcome.DENIED, "verification rule");
            throw e;
        }
        if (!s.isRestricted()) {
            records.requireClinical(user, s.getEmployeeId(), DataClassification.CLINICAL, AccessPolicy.Action.UPDATE, auditAction);
        }
    }

    List<CaptureObservation> observationsOf(CaptureSession s) {
        return observations.findBySessionIdOrderByReceivedAtAsc(s.getId());
    }

    List<CaptureObservation> undecided(CaptureSession s) {
        return observations.findBySessionIdOrderByReceivedAtAsc(s.getId()).stream()
                .filter(o -> o.getDecision() == null)
                .filter(o -> "PENDING_VERIFICATION".equals(o.getStatus()) || "BLOCKED".equals(o.getStatus())).toList();
    }

    /** Rejects the undecided readings of the session in the engine (values never filed). */
    void rejectUndecided(CaptureSession s, String actor, String reasonCode, String note, boolean strict) {
        for (CaptureObservation o : undecided(s)) {
            try {
                spis.gateway().reject(actor, o.getObservationId(), s.getId(), reasonCode, note);
            } catch (ApiException e) {
                if (strict) {
                    throw e;
                }
            }
            o.setDecision("REJECTED");
            o.setDecidedBy(actor);
            o.setDecidedAt(support.now());
            o.setRejectReason(reasonCode);
            o.setStatus("REJECTED");
        }
    }

    static void clearPreview(CaptureSession s) {
        s.setObservationId(null);
        s.setObservationStatus(null);
        s.setFlags(null);
    }

    CaptureObservation newObservation(CaptureSession s, UUID observationId) {
        CaptureObservation o = new CaptureObservation();
        o.setId(UUID.randomUUID());
        o.setSessionId(s.getId());
        o.setObservationId(observationId);
        o.setEmployeeId(s.getEmployeeId());
        o.setCategory(s.getCategory());
        o.setDeviceInstanceId(s.getDeviceInstanceId());
        o.setReceivedAt(support.now());
        return o;
    }

    /** Copies what the preview tells about an accepted / auto-posted observation (values encrypted). */
    void fill(CaptureObservation o, DeviceObservation obs) {
        o.setValuesJson(support.write(SummaryReleaser.snapshot(obs)));
        o.setEffectiveAt(obs.effectiveAt());
        o.setFlags(support.writeFlags(obs.qualityFlags()));
        if (obs.captureMethod() != null) {
            o.setCaptureMethod(obs.captureMethod().name());
        }
        if (obs.provenance() != null && obs.provenance().model() != null) {
            o.setModel(obs.provenance().model());
        }
        if (obs.deviceInstanceId() != null) {
            o.setDeviceInstanceId(obs.deviceInstanceId());
        }
    }

    private static String clinicalNote(String note) {
        if (note == null || note.isBlank()) {
            return null;
        }
        if (note.length() > 2000) {
            throw ApiException.badRequest("validation_failed", "Notes are limited to 2000 characters");
        }
        return note.strip();
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }

    static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    // ------------------------------------------------------------------ view

    CaptureViews.SessionView view(CurrentUser user, CaptureSession s) {
        State st = State.valueOf(s.getState());
        boolean own = own(user, s);
        boolean valuesVisible = !s.isRestricted() || VerificationRule.maySeeRestricted(user, own, support.actsAsMro(user));
        List<DeviceObservation.QualityFlag> flags = support.readFlags(s.getFlags());
        boolean blocked = flags.stream().anyMatch(f -> f.outcome() == DeviceCodes.GateOutcome.BLOCK);
        List<FallbackChain.Option> options = own && (st == State.FALLBACK_OFFERED || st == State.LISTENING || (st == State.PREVIEW && blocked))
                ? offer(user, s) : List.of();
        List<String> actions = CaptureStateMachine.triggers(st).stream().map(Enum::name)
                .filter(a -> !"TIMEOUT".equals(a) && !"RESULT".equals(a) && !"UNAVAILABLE".equals(a) && !"SUBMIT_ENTRY".equals(a))
                .filter(a -> !("ACCEPT".equals(a) && blocked)).toList();
        CaptureViews.Preview preview = valuesVisible ? preview(s) : null;
        String deviceName = devices.displayName(s.getDeviceInstanceId(), s.getFormCode(), s.getSiteCode(), s.getStationCode());
        List<CaptureViews.AnnotationView> notes = valuesVisible
                ? annotations.findBySessionIdOrderByCreatedAtAsc(s.getId()).stream()
                .map(a -> new CaptureViews.AnnotationView(a.getId(), a.getAuthor(), a.getCreatedAt(), a.getText())).toList()
                : List.of();
        return new CaptureViews.SessionView(s.getId(), s.getState(), s.getMode(), s.getEmployeeId(), support.displayName(s.getEmployeeId()),
                s.getCaseId(), s.getOrderRef(), s.getFormCode(), s.getFormVersion(), s.getFieldGroup(), s.getCategory(), s.getDeviceInstanceId(),
                deviceName, s.getPath(), s.getOperator(), s.getSiteCode(), s.getStationCode(), s.getOpenedAt(), s.getExpiresAt(),
                s.getObservationId(), s.getObservationStatus(), s.getRoutedRecordType(), s.getRoutedRecordId(), s.getFallbackReason(),
                s.getRejectReason(), s.getRetestCount(), s.getInterpretationStatus(), s.getSignedBy(), s.getSignedAt(), s.getContextPush(),
                s.isRestricted(), !valuesVisible, s.getBatchId(), s.getQueuePosition(), actions, options,
                flags.stream().map(CaptureViews.FlagView::of).toList(), preview, notes);
    }

    /** Preview of the current round: undecided readings, or the accepted ones once decided. */
    private CaptureViews.Preview preview(CaptureSession s) {
        List<CaptureObservation> all = observations.findBySessionIdOrderByReceivedAtAsc(s.getId());
        List<CaptureObservation> round = all.stream().filter(o -> o.getDecision() == null).toList();
        if (round.isEmpty()) {
            round = all.stream().filter(o -> "ACCEPTED".equals(o.getDecision()) || "AUTO_POSTED".equals(o.getDecision())).toList();
        }
        if (round.isEmpty()) {
            return null;
        }
        DeviceObservationGateway gateway;
        try {
            gateway = spis.gateway();
        } catch (ApiException e) {
            return null;
        }
        List<CaptureViews.ValueView> values = new ArrayList<>();
        List<CaptureViews.FlagView> flags = new ArrayList<>();
        List<CaptureViews.AttachmentView> attachments = new ArrayList<>();
        Map<String, Object> fields = new LinkedHashMap<>();
        List<CaptureViews.SequenceItem> sequence = new ArrayList<>();
        DeviceObservationGateway.ObservationPreview last = null;
        boolean blocked = false;
        boolean verify = false;
        for (CaptureObservation o : round) {
            Optional<DeviceObservationGateway.ObservationPreview> p = gateway.preview(o.getObservationId());
            if (p.isEmpty()) {
                continue;
            }
            last = p.get();
            DeviceObservation obs = last.observation();
            obs.components().forEach(c -> values.add(CaptureViews.ValueView.of(o.getObservationId(), c)));
            obs.qualityFlags().forEach(f -> flags.add(CaptureViews.FlagView.of(f)));
            obs.attachments().forEach(a -> attachments.add(CaptureViews.AttachmentView.of(o.getObservationId(), a)));
            fields.putAll(last.fields());
            blocked |= obs.blocked();
            verify |= last.verifyBeforePost();
            sequence.add(new CaptureViews.SequenceItem(o.getObservationId(), o.getCategory(), last.status(), o.getDecision(), obs.qualityFlags().size()));
        }
        if (last == null) {
            return null;
        }
        DeviceObservation lo = last.observation();
        String method = lo.captureMethod() == null ? null : lo.captureMethod().name();
        String model = lo.provenance() == null ? null : lo.provenance().model();
        Comparison.Summary comparison = Comparison.CATEGORIES.contains(s.getCategory()) ? comparison(s.getEmployeeId(), s.getCategory(), s.getId()) : null;
        boolean decided = round.stream().allMatch(o -> o.getDecision() != null);
        return new CaptureViews.Preview(lo.observationId(), last.status(), method, lo.effectiveAt(), values, flags,
                !blocked && !decided, verify, attachments, fields, CaptureViews.ProvenanceView.of(lo),
                CaptureViews.marking(method, model, lo.effectiveAt(), lo.provenance() != null && lo.provenance().simulated()),
                s.getInterpretationStatus(), sequence, comparison);
    }

    /** Today in UTC (for tests and the batch service). */
    LocalDate today() {
        return support.today();
    }
}
