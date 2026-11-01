package com.wellnessz.e31.service;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import com.wellnessz.casekernel.device.DeviceCatalogueLookup;
import com.wellnessz.casekernel.device.DeviceCodes;
import com.wellnessz.casekernel.device.DeviceObservation;
import com.wellnessz.casekernel.device.DeviceObservationGateway;
import com.wellnessz.casekernel.device.DeviceObservationListener;
import com.wellnessz.casekernel.device.DeviceObservationSink;
import com.wellnessz.common.error.ApiException;
import com.wellnessz.e31.pipeline.IngestService;
import com.wellnessz.e31.pipeline.InstanceResolver;
import com.wellnessz.e31.pipeline.PipelineProcessor;
import com.wellnessz.e31.store.MessageStore;
import com.wellnessz.e31.store.ObservationStore;
import com.wellnessz.e31.store.RawPayloadStore;
import com.wellnessz.e31.support.EngineSettings;
import com.wellnessz.security.audit.AuditService;

import tools.jackson.databind.json.JsonMapper;

/**
 * {@link DeviceObservationGateway} (contract sections 12 and 14): preview, clinician accept (routes the observation), reject
 * (re-measure – values are never edited) and fall-back submissions (OCR-verified / manual-attested) that enter the same
 * pipeline with the same normalisation, gates and provenance.
 */
@Service("e31ObservationService")
public class ObservationService implements DeviceObservationGateway {

    static final Set<String> REJECT_REASONS = Set.of("RETEST", "WRONG_EMPLOYEE", "ARTEFACT", "DEVICE_FAULT", "OTHER");

    private final ObservationStore observations;
    private final MessageStore messages;
    private final RawPayloadStore raw;
    private final PipelineProcessor processor;
    private final IngestService ingest;
    private final AuditService audit;
    private final JsonMapper json;
    private final Clock clock;

    public ObservationService(ObservationStore observations, MessageStore messages, RawPayloadStore raw, PipelineProcessor processor,
                              IngestService ingest, AuditService audit, JsonMapper json, Clock clock) {
        this.observations = observations;
        this.messages = messages;
        this.raw = raw;
        this.processor = processor;
        this.ingest = ingest;
        this.audit = audit;
        this.json = json;
        this.clock = clock;
    }

    @Override
    public Optional<ObservationPreview> preview(UUID observationId) {
        return observations.find(observationId).map(o -> new ObservationPreview(o.toKernel(), externalStatus(o.status()), o.formCode(),
                o.formVersion(), o.boundFields(), o.verifyBeforePost()));
    }

    /** VALIDATED / ROUTE_PENDING are internal: capture sees PENDING_VERIFICATION until routed. */
    static String externalStatus(String s) {
        return switch (s) {
            case "VALIDATED", "ROUTE_PENDING" -> "PENDING_VERIFICATION";
            default -> s;
        };
    }

    @Override
    public DeviceObservationSink.Delivery accept(String actor, UUID observationId, UUID sessionId, String note) {
        PipelineProcessor.Outcome[] holder = new PipelineProcessor.Outcome[1];
        DeviceObservationSink.Delivery result = processor.tx().execute(s -> {
            ObservationStore.ObservationRow o = observations.lock(observationId).orElseThrow(() -> ApiException.notFound("Observation"));
            if (sessionId != null && o.sessionId() != null && !sessionId.equals(o.sessionId())) {
                throw ApiException.notFound("Observation");
            }
            if (o.blocked()) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "observation_blocked", "A quality gate blocks posting of this result");
            }
            if (!"PENDING_VERIFICATION".equals(o.status())) {
                throw ApiException.conflict("observation_not_pending", "The observation is not waiting for verification");
            }
            Instant now = clock.instant();
            DeviceObservation.Provenance p = o.canonical().provenance();
            DeviceObservation.Provenance verifiedProv = p == null ? null : new DeviceObservation.Provenance(p.modelCode(), p.manufacturer(), p.model(),
                    p.udi(), p.serial(), p.firmware(), p.softwareVersion(), p.driverPackageId(), p.driverVersion(), p.calibrationId(), p.calibrationDate(),
                    p.calibrationVerdict(), p.operator(), actor, now, p.associationMethod(), p.associationConfidence(), p.clockSkewMillis(),
                    p.rawPayloadSha256());
            ObservationStore.ObservationRow verified = new ObservationStore.ObservationRow(o.id(), o.messageId(), o.recordIndex(), o.reportId(),
                    o.tenantCode(), o.siteCode(), o.employeeId(), o.caseId(), o.orderRef(), o.deviceInstanceId(), o.equipmentDeviceId(), o.category(),
                    o.status(), o.captureMethod(), o.restricted(), o.associationMethod(), o.confidence(), o.sessionId(), o.formCode(), o.formVersion(),
                    o.routeTarget(), o.verifyBeforePost(), o.routedRecordType(), o.routedRecordId(), o.effectiveAt(), o.clockSkewMs(),
                    o.gatewaySequence(), o.driverPackageId(), o.driverVersion(), o.canonical().withProvenance(verifiedProv), o.boundFields(),
                    o.testTraffic(), actor, now, null, o.routeAttempts(), o.createdAt(), o.updatedAt());
            MessageStore.MessageRow m = messages.find(o.messageId()).orElse(null);
            DeviceCatalogueLookup.DeviceInstance instance = processor.instances().find(o.deviceInstanceId(), o.driverPackageId(), o.driverVersion(),
                    o.tenantCode()).orElse(null);
            ObservationStore.ObservationRow routed = processor.route(verified, m, instance, actor, true);
            audit.recordAs(actor, "OH", "DEVICE_OBSERVATION_ACCEPT", "DEVICE_OBSERVATION", o.id().toString(), AuditService.Outcome.SUCCESS,
                    o.category() + " " + routed.status());
            holder[0] = new PipelineProcessor.Outcome(o.messageId(), routed.status(), null, false, List.of(o.id()), List.of(routed.status()),
                    List.of(PipelineProcessor.notice(routed)));
            return switch (routed.status()) {
                case "ROUTED" -> DeviceObservationSink.Delivery.delivered(routed.routedRecordType(), routed.routedRecordId());
                case "REJECTED" -> DeviceObservationSink.Delivery.rejected(routed.rejectReason(), "The target form rejected the result");
                default -> o.testTraffic() ? DeviceObservationSink.Delivery.delivered("TEST_FORM", o.id().toString())
                        : DeviceObservationSink.Delivery.rejected("route_target_unavailable", "Filing is queued and will be retried");
            };
        });
        if (holder[0] != null) {
            processor.notify(holder[0].notices());
        }
        return result;
    }

    @Override
    public void reject(String actor, UUID observationId, UUID sessionId, String reasonCode, String note) {
        if (reasonCode == null || !REJECT_REASONS.contains(reasonCode)) {
            throw ApiException.badRequest("validation_failed", "reasonCode must be one of " + REJECT_REASONS);
        }
        List<DeviceObservationListener.ObservationNotice> notices = processor.tx().execute(s -> {
            ObservationStore.ObservationRow o = observations.lock(observationId).orElseThrow(() -> ApiException.notFound("Observation"));
            if (!Set.of("PENDING_VERIFICATION", "BLOCKED", "UNMATCHED", "VALIDATED").contains(o.status())) {
                throw ApiException.conflict("observation_not_pending", "The observation can no longer be rejected");
            }
            ObservationStore.ObservationRow r = new ObservationStore.ObservationRow(o.id(), o.messageId(), o.recordIndex(), o.reportId(),
                    o.tenantCode(), o.siteCode(), o.employeeId(), o.caseId(), o.orderRef(), o.deviceInstanceId(), o.equipmentDeviceId(), o.category(),
                    "REJECTED", o.captureMethod(), o.restricted(), o.associationMethod(), o.confidence(), o.sessionId(), o.formCode(), o.formVersion(),
                    o.routeTarget(), o.verifyBeforePost(), o.routedRecordType(), o.routedRecordId(), o.effectiveAt(), o.clockSkewMs(),
                    o.gatewaySequence(), o.driverPackageId(), o.driverVersion(), o.canonical(), o.boundFields(), o.testTraffic(), o.verifiedBy(),
                    o.verifiedAt(), reasonCode, o.routeAttempts(), o.createdAt(), o.updatedAt());
            observations.upsert(r);
            observations.closeUnmatchedForObservation(o.id(), actor);
            messages.find(o.messageId()).ifPresent(m -> raw.retainAsUnfiled(m.rawObjectId()));
            audit.recordAs(actor, "OH", "DEVICE_OBSERVATION_REJECT", "DEVICE_OBSERVATION", o.id().toString(), AuditService.Outcome.SUCCESS, reasonCode);
            return List.of(PipelineProcessor.notice(r));
        });
        processor.notify(notices);
    }

    @Override
    public UUID submitFallback(String actor, FallbackSubmission f) {
        if (f.method() == null || f.method() == DeviceCodes.CaptureMethod.DEVICE) {
            throw ApiException.badRequest("validation_failed", "method must be OCR_VERIFIED or MANUAL_ATTESTED");
        }
        if (f.category() == null || f.values().isEmpty()) {
            throw ApiException.badRequest("validation_failed", "category and values are required");
        }
        return submitFallback(actor, f, null, actor, UUID.randomUUID().toString(), null).observationIds().stream().findFirst()
                .orElseThrow(() -> new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "parse_failed", "The fall-back values could not be processed"));
    }

    /**
     * Fall-back entry from the capture dialogue (E33) or replayed by the tablet ({@code /device-ingest/fallback}).
     *
     * @param source endpoint of a tablet replaying offline entries, {@code null} for the capture dialogue
     */
    public IngestService.Receipt submitFallback(String actor, FallbackSubmission f, Instant capturedAt, String operator, String idempotencyKey,
                                                IngestService.Source source) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("method", f.method().name());
        body.put("category", f.category().name());
        body.put("deviceInstanceId", f.deviceInstanceId());
        body.put("employeeId", f.employeeId());
        body.put("formCode", f.formCode());
        body.put("sessionId", f.sessionId() == null ? null : f.sessionId().toString());
        body.put("values", f.values());
        body.put("imageArchiveId", f.imageArchiveId() == null ? null : f.imageArchiveId().toString());
        body.put("ocrConfidence", f.ocrConfidence());
        body.put("attestation", f.attestation());
        body.put("attestedModel", f.attestedModel());
        body.put("attestedSerial", f.attestedSerial());
        body.put("attestedCalibrationId", f.attestedCalibrationId());
        body.put("attestedCalibrationDate", f.attestedCalibrationDate() == null ? null : f.attestedCalibrationDate().toString());
        body.put("capturedAt", capturedAt == null ? null : capturedAt.toString());
        body.put("operator", operator);
        byte[] bytes = json.writeValueAsBytes(body);
        String instanceId = f.deviceInstanceId() != null ? f.deviceInstanceId() : InstanceResolver.MANUAL_PREFIX + f.category().name();
        boolean manualInstance = f.deviceInstanceId() == null;
        IngestService.Source src = source != null ? source
                : new IngestService.Source("CAPTURE:" + truncate(actor, 50), EngineSettings.DEFAULT_TENANT, null, Set.of(instanceId), manualInstance);
        if (source != null && manualInstance) {
            src = new IngestService.Source(source.endpointId(), source.tenantCode(), source.siteCode(), source.deviceInstanceIds(), true);
        }
        IngestService.Receipt receipt = ingest.acquire(new IngestService.Acquire(src, "FALLBACK", "application/json", idempotencyKey, null, capturedAt,
                null, instanceId, null, false, bytes, Map.of(), false, false, null, null, true));
        audit.recordAs(actor, "OH", "DEVICE_CAPTURE_FALLBACK", "DEVICE_OBSERVATION",
                receipt.observationIds().isEmpty() ? receipt.messageId().toString() : receipt.observationIds().get(0).toString(),
                AuditService.Outcome.SUCCESS, f.method() + " " + f.category());
        return receipt;
    }

    static String truncate(String s, int n) {
        return s == null || s.length() <= n ? s : s.substring(0, n);
    }

    /** Fall-back submission parsed from a tablet replay ({@code /device-ingest/fallback}). */
    public static FallbackSubmission submission(UUID sessionId, DeviceCodes.CaptureMethod method, String deviceInstanceId, String model, String serial,
                                                String calId, LocalDate calDate, DeviceCodes.Category category, String employeeId, String formCode,
                                                Map<String, Object> values, UUID imageArchiveId, BigDecimal ocrConfidence, String attestation) {
        return new FallbackSubmission(sessionId, method, deviceInstanceId, model, serial, calId, calDate, category, employeeId, formCode, values,
                imageArchiveId, ocrConfidence, attestation);
    }
}
