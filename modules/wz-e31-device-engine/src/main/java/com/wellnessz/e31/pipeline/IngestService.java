package com.wellnessz.e31.pipeline;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.wellnessz.casekernel.device.DeviceCatalogueLookup;
import com.wellnessz.casekernel.device.DeviceCodes;
import com.wellnessz.casekernel.device.DeviceSecurityEventPublisher;
import com.wellnessz.casekernel.device.DeviceSpis;
import com.wellnessz.common.error.ApiException;
import com.wellnessz.e31.driver.DriverRuntime;
import com.wellnessz.e31.store.MessageStore;
import com.wellnessz.e31.store.ObservationStore;
import com.wellnessz.e31.store.RawPayloadStore;
import com.wellnessz.e31.support.EngineSettings;
import com.wellnessz.e31.support.RateLimitedException;

/**
 * ACQUIRE stage (contract sections 7 and 8): endpoint → device-instance authorisation, idempotency per endpoint, tenant quota,
 * raw archive and the work-table row – committed before the receipt (202 means durably stored). Processing then continues in
 * the request thread when {@code device.pipeline.inline} is on (workers retry) or by the workers alone.
 */
@Service("e31IngestService")
public class IngestService {

    private final MessageStore messages;
    private final ObservationStore observations;
    private final RawPayloadStore raw;
    private final PipelineProcessor processor;
    private final DeviceSpis spis;
    private final EngineSettings settings;
    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final TransactionTemplate tx;

    public IngestService(MessageStore messages, ObservationStore observations, RawPayloadStore raw, PipelineProcessor processor, DeviceSpis spis,
                         EngineSettings settings, JdbcTemplate jdbc, Clock clock, PlatformTransactionManager transactions) {
        this.messages = messages;
        this.observations = observations;
        this.raw = raw;
        this.processor = processor;
        this.spis = spis;
        this.settings = settings;
        this.jdbc = jdbc;
        this.clock = clock;
        this.tx = new TransactionTemplate(transactions);
    }

    /**
     * @param deviceInstanceIds instances the endpoint may submit for (empty = resolved from the catalogue)
     * @param virtual           simulator / fall-back source (no catalogue check)
     */
    public record Source(String endpointId, String tenantCode, String siteCode, Set<String> deviceInstanceIds, boolean virtual) {
    }

    /**
     * @param instanceHint   {@code X-Device-Instance} or the record-level instance id
     * @param serialHint     device serial when the caller knows it
     * @param packageId      for virtual sources: the sample package to use
     */
    public record Acquire(Source source, String kind, String contentType, String idempotencyKey, Long sequence, Instant capturedAt, Instant edgeClock,
                          String instanceHint, String serialHint, boolean replayed, byte[] body, Map<String, String> headers, boolean testTraffic,
                          boolean routeTestForm, String packageId, String packageVersion, boolean forceInline) {
    }

    /** @param status ACCEPTED | DUPLICATE */
    public record Receipt(UUID messageId, String status, Instant receivedAt, String deviceInstanceId, List<UUID> observationIds,
                          PipelineProcessor.Outcome outcome) {
    }

    public Receipt acquire(Acquire a) {
        if (a.idempotencyKey() == null || a.idempotencyKey().isBlank() || a.idempotencyKey().length() > 200) {
            throw ApiException.badRequest("validation_failed", "Idempotency-Key is required (at most 200 characters)");
        }
        if (a.body() == null || a.body().length == 0) {
            throw ApiException.badRequest("validation_failed", "Empty body");
        }
        if (a.body().length > settings.maxPayloadBytes()) {
            throw new ApiException(HttpStatus.CONTENT_TOO_LARGE, "payload_too_large", "The payload exceeds the maximum size");
        }
        Optional<MessageStore.MessageRow> dup = messages.findByKey(a.source().endpointId(), a.idempotencyKey());
        if (dup.isPresent()) {
            return duplicate(dup.get());
        }
        String tenant = a.source().tenantCode() == null ? EngineSettings.DEFAULT_TENANT : a.source().tenantCode();
        quota(tenant);
        DeviceCatalogueLookup.DeviceInstance instance = a.source().virtual() ? null : resolve(a, tenant);
        String instanceId = instance != null ? instance.deviceInstanceId() : a.instanceHint();
        String site = instance != null ? instance.siteCode() : a.source().siteCode();
        Instant now = clock.instant();
        UUID id = UUID.randomUUID();
        try {
            tx.executeWithoutResult(s -> {
                Map<String, String> headers = new java.util.LinkedHashMap<>(a.headers());
                MessageStore.MessageRow pdf = null;
                if ("FILE".equals(a.kind()) && headers.containsKey("fileStemHash")) {
                    pdf = messages.pendingAttachment(a.source().endpointId(), headers.get("fileStemHash")).orElse(null);
                    if (pdf != null) {
                        headers.put("pairedArchiveId", pdf.rawObjectId().toString());
                    }
                }
                RawPayloadStore.ArchiveRef ref = raw.put(a.body(), "MESSAGE", a.contentType() == null ? "application/octet-stream" : a.contentType(),
                        tenant, a.source().endpointId());
                messages.insert(new MessageStore.MessageRow(id, tenant, a.source().endpointId(), site, instanceId, a.kind(), a.contentType(),
                        a.idempotencyKey(), a.sequence(), a.capturedAt(), a.edgeClock(), now, ref.archiveId(), ref.sha256(), headers, "PARSE",
                        "RECEIVED", 0, null, null, instance != null ? instance.driverPackageId() : a.packageId(),
                        instance != null ? instance.driverVersion() : a.packageVersion(), a.replayed(), a.testTraffic(), a.routeTestForm()));
                messages.stage(id, null, "ACQUIRE", "OK", null, 0);
                if (pdf != null) {
                    messages.update(pdf.id(), "ACK", "PROCESSED", 1, null, null, null, null);
                }
                if (instanceId != null) {
                    touchConnector(instanceId, tenant, a.source().endpointId(), site, instance == null || instance.option() == null ? null
                            : instance.option().code(), now);
                }
            });
        } catch (DuplicateKeyException race) {
            return messages.findByKey(a.source().endpointId(), a.idempotencyKey()).map(this::duplicate).orElseThrow(() -> race);
        }
        PipelineProcessor.Outcome outcome = null;
        if (a.forceInline() || settings.inline()) {
            outcome = processor.process(id, PipelineProcessor.Options.DEFAULT);
        }
        return new Receipt(id, "ACCEPTED", now, instanceId, outcome == null ? List.of() : outcome.observationIds(), outcome);
    }

    /**
     * PDF export of a P2 vendor package: archived (kind PDF) and paired with its data file by the driver's attachment rule
     * (same endpoint and file stem). A PDF arriving after its data file is attached to that file's observations at once;
     * otherwise it waits (status PARKED, {@code awaiting_pair}) and is dead-lettered {@code attachment_unpaired} after 10 minutes.
     */
    public Receipt acquireAttachmentFile(Acquire a) {
        Optional<MessageStore.MessageRow> dup = messages.findByKey(a.source().endpointId(), a.idempotencyKey());
        if (dup.isPresent()) {
            return duplicate(dup.get());
        }
        if (a.body().length > settings.maxPayloadBytes()) {
            throw new ApiException(HttpStatus.CONTENT_TOO_LARGE, "payload_too_large", "The payload exceeds the maximum size");
        }
        String tenant = a.source().tenantCode() == null ? EngineSettings.DEFAULT_TENANT : a.source().tenantCode();
        quota(tenant);
        DeviceCatalogueLookup.DeviceInstance instance = a.source().virtual() ? null : resolve(a, tenant);
        String instanceId = instance != null ? instance.deviceInstanceId() : a.instanceHint();
        Instant now = clock.instant();
        UUID id = UUID.randomUUID();
        UUID[] data = new UUID[1];
        UUID[] archive = new UUID[1];
        tx.executeWithoutResult(s -> {
            RawPayloadStore.ArchiveRef ref = raw.put(a.body(), "PDF", a.contentType() == null ? "application/pdf" : a.contentType(), tenant,
                    a.source().endpointId());
            archive[0] = ref.archiveId();
            Optional<MessageStore.MessageRow> dataFile = a.headers().containsKey("fileStemHash")
                    ? messages.dataFileByStem(a.source().endpointId(), a.headers().get("fileStemHash")) : Optional.empty();
            messages.insert(new MessageStore.MessageRow(id, tenant, a.source().endpointId(), instance == null ? a.source().siteCode() : instance.siteCode(),
                    instanceId, "FILE", a.contentType(), a.idempotencyKey(), a.sequence(), a.capturedAt(), a.edgeClock(), now, ref.archiveId(), ref.sha256(),
                    a.headers(), dataFile.isPresent() ? "ACK" : "PARSE", dataFile.isPresent() ? "PROCESSED" : "PARKED", 0, null,
                    dataFile.isPresent() ? null : "awaiting_pair", null, null, a.replayed(), a.testTraffic(), false));
            messages.stage(id, null, "ACQUIRE", "OK", dataFile.isPresent() ? "paired" : "awaiting_pair", 0);
            dataFile.ifPresent(d -> data[0] = d.id());
        });
        if (data[0] != null) {
            processor.attach(data[0], archive[0]);
        }
        return new Receipt(id, "ACCEPTED", now, instanceId, List.of(), null);
    }

    Receipt duplicate(MessageStore.MessageRow m) {
        List<UUID> ids = observations.forMessage(m.id()).stream().map(ObservationStore.ObservationRow::id).toList();
        PipelineProcessor.Outcome o = new PipelineProcessor.Outcome(m.id(), m.status(), m.errorCode(), "PARSE".equals(m.stage())
                && "DEAD_LETTER".equals(m.status()), ids, List.of(), List.of());
        return new Receipt(m.id(), "DUPLICATE", m.receivedAt(), m.deviceInstanceId(), ids, o);
    }

    void quota(String tenant) {
        Integer limit = jdbc.query("select messages_per_minute from deviceint.tenant_quota where code = ?", rs -> rs.next() ? rs.getInt(1) : null, tenant);
        if (limit != null && limit > 0 && messages.countForTenantSince(tenant, clock.instant().minusSeconds(60)) >= limit) {
            throw new RateLimitedException("Tenant message quota exceeded", 60);
        }
    }

    /** Device instance of the message, authorised for the endpoint (contract section 7 errors). */
    DeviceCatalogueLookup.DeviceInstance resolve(Acquire a, String tenant) {
        DeviceCatalogueLookup catalogue = spis.catalogue();
        DeviceCatalogueLookup.DeviceInstance instance;
        if (a.instanceHint() != null) {
            instance = catalogue.instance(a.instanceHint()).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "device_not_published",
                    "The device instance is not published"));
        } else {
            List<DeviceCatalogueLookup.DeviceInstance> candidates = catalogue.instancesForEndpoint(a.source().endpointId(), a.serialHint());
            if (candidates.size() > 1) {
                String text = new String(a.body(), StandardCharsets.ISO_8859_1);
                candidates = candidates.stream().filter(i -> i.serial() != null && !i.serial().isBlank() && text.contains(i.serial())).toList();
            }
            if (candidates.size() != 1) {
                event("UNKNOWN_DEVICE_IDENTITY", a.source(), null, "unknown_device");
                throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "unknown_device", "The message matches no published device of this endpoint");
            }
            instance = candidates.get(0);
        }
        if (!tenant.equals(instance.tenantCode() == null ? EngineSettings.DEFAULT_TENANT : instance.tenantCode())) {
            throw new ApiException(HttpStatus.NOT_FOUND, "device_not_published", "The device instance is not published");
        }
        boolean allowed = a.source().endpointId().equals(instance.endpointId()) || a.source().deviceInstanceIds().contains(instance.deviceInstanceId())
                || a.source().endpointId().equals("VENDOR:" + instance.deviceInstanceId());
        if (!allowed) {
            event("UNKNOWN_DEVICE_IDENTITY", a.source(), instance.deviceInstanceId(), "device_not_authorised_for_endpoint");
            throw new ApiException(HttpStatus.FORBIDDEN, "device_not_authorised_for_endpoint", "The endpoint may not submit for this device");
        }
        String state = jdbc.query("select endpoint_status from deviceint.connector_state where device_instance_id = ?",
                rs -> rs.next() ? rs.getString(1) : null, instance.deviceInstanceId());
        if (Set.of("QUARANTINED", "SUSPENDED").contains(instance.status()) || "ISOLATED".equals(state) || "REVOKED".equals(state)) {
            throw ApiException.conflict("device_quarantined", "The device is quarantined");
        }
        if (!instance.published()) {
            throw new ApiException(HttpStatus.NOT_FOUND, "device_not_published", "The device instance is not published");
        }
        return instance;
    }

    void event(String type, Source s, String instanceId, String reason) {
        try {
            spis.securityEvents().publish(new DeviceSecurityEventPublisher.DeviceSecurityEvent(type, DeviceCodes.Severity.MEDIUM, s.endpointId(),
                    instanceId, s.siteCode(), "device:" + s.endpointId(), "E31", reason, Map.of(), clock.instant()));
        } catch (RuntimeException ignored) {
            // the refusal is returned regardless of event delivery
        }
    }

    void touchConnector(String instanceId, String tenant, String endpointId, String site, String option, Instant now) {
        if (DriverRuntime.virtual(instanceId)) {
            return;
        }
        int n = jdbc.update("update deviceint.connector_state set last_seen_at = ?, endpoint_id = coalesce(?, endpoint_id), updated_at = ? "
                + "where device_instance_id = ?", Timestamp.from(now), endpointId, Timestamp.from(now), instanceId);
        if (n == 0) {
            jdbc.update("""
                    insert into deviceint.connector_state (device_instance_id, tenant_code, endpoint_id, site_code, option_code, last_seen_at,
                        endpoint_status, updated_at) values (?, ?, ?, ?, ?, ?, 'ACTIVE', ?) on conflict (device_instance_id) do nothing""",
                    instanceId, tenant, endpointId, site, option, Timestamp.from(now), Timestamp.from(now));
        }
    }
}
