package com.wellnessz.e31.service;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Service;

import com.wellnessz.casekernel.device.DeviceObservation;
import com.wellnessz.casekernel.support.EmployeeRecordAccess;
import com.wellnessz.common.error.ApiException;
import com.wellnessz.common.model.DataClassification;
import com.wellnessz.common.model.Roles;
import com.wellnessz.e31.pipeline.PipelineProcessor;
import com.wellnessz.e31.store.FhirStore;
import com.wellnessz.e31.store.MessageStore;
import com.wellnessz.e31.store.ObservationStore;
import com.wellnessz.e31.store.RawPayloadStore;
import com.wellnessz.e31.support.EngineSettings;
import com.wellnessz.masterdata.Employee;
import com.wellnessz.security.abac.AccessPolicy;
import com.wellnessz.security.audit.AuditService;
import com.wellnessz.security.identity.CurrentUser;

/**
 * Person-level reads of device observations (contract section 12, confidentiality rules 2–5): ABAC on the employee through
 * {@link EmployeeRecordAccess}; restricted categories (breath alcohol, drug screen) only for MRO / OH physicians – everyone
 * else sees "restricted reading" without values; every read is audited ({@code DEVICE_OBSERVATION_READ}). Also the
 * unmatched queue with suggestions and its resolution (manual association → re-validation).
 */
@Service("e31ClinicalReadService")
public class ClinicalReadService {

    static final Set<String> RESOLUTION_CODES = Set.of("CONFIRMED_IDENTITY", "WORKLIST_ERROR", "BADGE_ERROR", "TEST_TRAFFIC", "DUPLICATE", "OTHER");

    private final ObservationStore observations;
    private final MessageStore messages;
    private final FhirStore fhir;
    private final RawPayloadStore raw;
    private final PipelineProcessor processor;
    private final EmployeeRecordAccess records;
    private final AuditService audit;
    private final Clock clock;

    public ClinicalReadService(ObservationStore observations, MessageStore messages, FhirStore fhir, RawPayloadStore raw, PipelineProcessor processor,
                               EmployeeRecordAccess records, AuditService audit, Clock clock) {
        this.observations = observations;
        this.messages = messages;
        this.fhir = fhir;
        this.raw = raw;
        this.processor = processor;
        this.records = records;
        this.audit = audit;
        this.clock = clock;
    }

    public record ObservationSummary(UUID observationId, String category, String status, Instant effectiveAt, String deviceInstanceId, String model,
                                     String captureMethod, List<String> flags, String routedRecordId, boolean restricted, String restrictedNote) {
    }

    /**
     * @param components {@code null} for a restricted reading the viewer may not open
     */
    public record ObservationView(UUID observationId, UUID reportId, String category, String status, String employeeId, Instant effectiveAt,
                                  String deviceInstanceId, String captureMethod, List<DeviceObservation.Component> components,
                                  List<DeviceObservation.QualityFlag> flags, DeviceObservation.Provenance provenance,
                                  List<DeviceObservation.Attachment> attachments, String formCode, Map<String, Object> boundFields,
                                  String routedRecordType, String routedRecordId, boolean restricted, String restrictedNote,
                                  List<FhirStore.Stored> fhir) {
    }

    static boolean restrictedViewer(CurrentUser user) {
        return user.hasAnyRole(Roles.MRO, Roles.OH_PHYSICIAN);
    }

    public List<ObservationSummary> list(CurrentUser user, String employeeId, String category, Instant from, Instant to) {
        records.requireClinical(user, employeeId, DataClassification.CLINICAL, AccessPolicy.Action.READ, "DEVICE_OBSERVATION_READ");
        List<ObservationSummary> out = new ArrayList<>();
        for (ObservationStore.ObservationRow o : observations.forEmployee(employeeId, category, from, to, 500)) {
            boolean hide = o.restricted() && !restrictedViewer(user);
            out.add(new ObservationSummary(o.id(), o.category().name(), ObservationService.externalStatus(o.status()), o.effectiveAt(),
                    o.deviceInstanceId(), o.canonical().provenance() == null ? null : o.canonical().provenance().model(), o.captureMethod().name(),
                    hide ? List.of() : o.canonical().flags().stream().map(f -> f.gate() + ":" + f.outcome() + ":" + f.code()).toList(),
                    hide ? null : o.routedRecordId(), o.restricted(), hide ? "Restricted reading" : null));
        }
        audit.record("DEVICE_OBSERVATION_READ", "EMPLOYEE", employeeId, AuditService.Outcome.SUCCESS, "rows=" + out.size());
        return out;
    }

    public ObservationView get(CurrentUser user, UUID id, boolean withFhir) {
        ObservationStore.ObservationRow o = readable(user, id);
        boolean hide = o.restricted() && !restrictedViewer(user);
        audit.record("DEVICE_OBSERVATION_READ", "DEVICE_OBSERVATION", id.toString(), AuditService.Outcome.SUCCESS, hide ? "restricted-placeholder" : "full");
        return new ObservationView(o.id(), o.reportId(), o.category().name(), ObservationService.externalStatus(o.status()), o.employeeId(),
                o.effectiveAt(), o.deviceInstanceId(), o.captureMethod().name(), hide ? null : o.canonical().components(),
                hide ? List.of() : o.canonical().flags(), hide ? null : o.canonical().provenance(), hide ? List.of() : o.canonical().attachments(),
                o.formCode(), hide ? Map.of() : o.boundFields(), o.routedRecordType(), hide ? null : o.routedRecordId(), o.restricted(),
                hide ? "Restricted reading" : null, withFhir && !hide ? fhir.bundle(o.id(), o.deviceInstanceId()) : List.of());
    }

    ObservationStore.ObservationRow readable(CurrentUser user, UUID id) {
        ObservationStore.ObservationRow o = observations.find(id).orElseThrow(() -> ApiException.notFound("Observation"));
        if (o.employeeId() == null) {
            audit.record("DEVICE_OBSERVATION_READ", "DEVICE_OBSERVATION", id.toString(), AuditService.Outcome.DENIED, "unassociated");
            throw ApiException.notFound("Observation");
        }
        records.requireClinical(user, o.employeeId(), DataClassification.CLINICAL, AccessPolicy.Action.READ, "DEVICE_OBSERVATION_READ");
        return o;
    }

    public record AttachmentContent(String mediaType, byte[] bytes) {
    }

    public AttachmentContent attachment(CurrentUser user, UUID observationId, UUID archiveId) {
        ObservationStore.ObservationRow o = readable(user, observationId);
        if (o.restricted() && !restrictedViewer(user)) {
            throw ApiException.notFound("Attachment");
        }
        DeviceObservation.Attachment a = o.canonical().attachments().stream().filter(x -> x.archiveId().equals(archiveId)).findFirst()
                .orElseThrow(() -> ApiException.notFound("Attachment"));
        audit.record("DEVICE_OBSERVATION_READ", "DEVICE_ATTACHMENT", archiveId.toString(), AuditService.Outcome.SUCCESS, a.kind());
        return new AttachmentContent(a.mediaType(), raw.get(archiveId));
    }

    // ------------------------------------------------------------------ unmatched queue (E31-F05, E31-BL-015)

    public record UnmatchedView(UUID id, UUID observationId, String category, Instant receivedAt, long ageHours, Instant slaDueAt,
                                String deviceInstanceId, String reasonCode, List<ObservationStore.Suggestion> suggestions, boolean restricted) {
    }

    public List<UnmatchedView> unmatched(CurrentUser user, String siteCode, String status) {
        Instant now = clock.instant();
        List<UnmatchedView> out = new ArrayList<>();
        for (ObservationStore.UnmatchedRow u : observations.unmatched(siteCode, status == null ? "OPEN" : status, EngineSettings.DEFAULT_TENANT, 500)) {
            boolean hide = u.restricted() && !restrictedViewer(user);
            out.add(new UnmatchedView(u.id(), u.observationId(), u.category().name(), u.receivedAt(), Duration.between(u.receivedAt(), now).toHours(),
                    u.slaDueAt(), u.deviceInstanceId(), u.reasonCode(), hide ? List.of() : u.suggestions(), u.restricted()));
        }
        audit.record("DEVICE_UNMATCHED_LIST", "DEVICE_UNMATCHED", siteCode == null ? "*" : siteCode, AuditService.Outcome.SUCCESS, "rows=" + out.size());
        return out;
    }

    public Map<String, Long> unmatchedCount(String siteCode) {
        long[] c = observations.unmatchedCount(siteCode);
        return Map.of("open", c[0], "overdue", c[1]);
    }

    /**
     * Resolution with a reason code (audited {@code DEVICE_UNMATCHED_RESOLVE}); the observation re-enters VALIDATE with the
     * clinician's association (MANUAL_RESOLUTION, 1.00). {@code discard} rejects it (raw → DEVICE_RAW_UNFILED).
     */
    public Map<String, Object> resolve(CurrentUser user, UUID unmatchedId, String employeeId, String reasonCode, String note, boolean discard) {
        if (reasonCode == null || !RESOLUTION_CODES.contains(reasonCode)) {
            throw ApiException.badRequest("validation_failed", "reasonCode must be one of " + RESOLUTION_CODES);
        }
        ObservationStore.UnmatchedRow u = observations.unmatched(unmatchedId).orElseThrow(() -> ApiException.notFound("Unmatched item"));
        if (!"OPEN".equals(u.status())) {
            throw ApiException.conflict("already_resolved", "The item is no longer open");
        }
        if (u.restricted() && !restrictedViewer(user)) {
            throw ApiException.notFound("Unmatched item");
        }
        if (discard) {
            processor.tx().executeWithoutResult(s -> {
                ObservationStore.ObservationRow o = observations.lock(u.observationId()).orElseThrow();
                observations.upsert(new ObservationStore.ObservationRow(o.id(), o.messageId(), o.recordIndex(), o.reportId(), o.tenantCode(),
                        o.siteCode(), null, o.caseId(), o.orderRef(), o.deviceInstanceId(), o.equipmentDeviceId(), o.category(), "REJECTED",
                        o.captureMethod(), o.restricted(), null, null, o.sessionId(), o.formCode(), o.formVersion(), o.routeTarget(), o.verifyBeforePost(),
                        null, null, o.effectiveAt(), o.clockSkewMs(), o.gatewaySequence(), o.driverPackageId(), o.driverVersion(), o.canonical(),
                        o.boundFields(), o.testTraffic(), null, null, "discarded", o.routeAttempts(), o.createdAt(), o.updatedAt()));
                observations.resolveUnmatched(unmatchedId, "DISCARDED", user.username(), reasonCode, note);
                messages.find(o.messageId()).ifPresent(m -> raw.retainAsUnfiled(m.rawObjectId()));
            });
            audit.record("DEVICE_UNMATCHED_DISCARD", "DEVICE_UNMATCHED", unmatchedId.toString(), AuditService.Outcome.SUCCESS, reasonCode);
            return Map.of("id", unmatchedId, "status", "DISCARDED");
        }
        if (employeeId == null || employeeId.isBlank()) {
            throw ApiException.badRequest("validation_failed", "employeeId is required");
        }
        Employee e = records.requireClinical(user, employeeId, DataClassification.CLINICAL, AccessPolicy.Action.UPDATE, "DEVICE_UNMATCHED_RESOLVE");
        UUID messageId = processor.tx().execute(s -> {
            ObservationStore.ObservationRow o = observations.lock(u.observationId()).orElseThrow();
            observations.upsert(new ObservationStore.ObservationRow(o.id(), o.messageId(), o.recordIndex(), o.reportId(), o.tenantCode(), o.siteCode(),
                    e.getEmployeeId(), o.caseId(), o.orderRef(), o.deviceInstanceId(), o.equipmentDeviceId(), o.category(), "ASSOCIATED",
                    o.captureMethod(), o.restricted(), "MANUAL_RESOLUTION", new BigDecimal("1.00"), o.sessionId(), o.formCode(), o.formVersion(),
                    o.routeTarget(), o.verifyBeforePost(), null, null, o.effectiveAt(), o.clockSkewMs(), o.gatewaySequence(), o.driverPackageId(),
                    o.driverVersion(), o.canonical(), o.boundFields(), o.testTraffic(), null, null, null, o.routeAttempts(), o.createdAt(),
                    o.updatedAt()));
            observations.candidate(o.id(), e.getEmployeeId(), "MANUAL_RESOLUTION", new BigDecimal("1.00"), true);
            observations.resolveUnmatched(unmatchedId, "RESOLVED", user.username(), reasonCode, note);
            messages.resetForReplay(o.messageId(), null);
            return o.messageId();
        });
        audit.record("DEVICE_UNMATCHED_RESOLVE", "DEVICE_UNMATCHED", unmatchedId.toString(), AuditService.Outcome.SUCCESS, reasonCode);
        processor.process(messageId, PipelineProcessor.Options.DEFAULT);
        ObservationStore.ObservationRow after = observations.find(u.observationId()).orElseThrow();
        return Map.of("id", unmatchedId, "status", "RESOLVED", "observationId", after.id(), "observationStatus",
                ObservationService.externalStatus(after.status()));
    }
}
