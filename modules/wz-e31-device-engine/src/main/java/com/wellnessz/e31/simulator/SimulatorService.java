package com.wellnessz.e31.simulator;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import com.wellnessz.casekernel.device.DeviceCatalogueLookup;
import com.wellnessz.casekernel.device.DeviceSpis;
import com.wellnessz.common.error.ApiException;
import com.wellnessz.e31.driver.BundledDrivers;
import com.wellnessz.e31.driver.DriverPackage;
import com.wellnessz.e31.driver.DriverRuntime;
import com.wellnessz.e31.pipeline.IngestService;
import com.wellnessz.e31.support.EngineSettings;
import com.wellnessz.e31.support.Ids;
import com.wellnessz.security.audit.AuditService;

/**
 * Simulators (E31-F10): feed golden datasets into the pipeline as the virtual endpoint {@code SIM-<adapter>} – test traffic,
 * retention {@code DEVICE_RAW_UNFILED}, never routed to production sinks unless the run targets the wizard test form.
 * Duplicate datasets are sent twice with the same idempotency key; time-skew datasets carry a shifted edge clock.
 *
 * <p>With a {@code deviceInstanceId} the readings arrive <b>as that published device</b> (its driver, station and calibration
 * gates) through the virtual endpoint {@code SIM-<adapter>}, so a capture session listening on the device receives them
 * (E33 test capture, end-to-end check). They stay test traffic: flagged {@code simulated:true} in the provenance and with the
 * {@code SIMULATED} quality flag, associated only with that capture session (never by the dataset's synthetic identity), never
 * queued as unmatched, always verify-before-post, and filed into the employee's record only when the clinician accepts them in
 * the session.</p>
 */
@Service("e31SimulatorService")
public class SimulatorService {

    public static final Map<String, String> KIND = Map.ofEntries(Map.entry("HL7V2", "HL7V2"), Map.entry("FHIR", "FHIR"), Map.entry("POCT1A", "POCT1A"),
            Map.entry("FILE_CSV", "FILE"), Map.entry("FILE_XML", "FILE"), Map.entry("FILE_GDT", "FILE"), Map.entry("FILE_PDF", "FILE"),
            Map.entry("BLE_GATT", "BLE"), Map.entry("SERIAL", "SERIAL"), Map.entry("WEBHOOK_JSON", "WEBHOOK"), Map.entry("CLOUD_POLL_JSON", "WEBHOOK"),
            Map.entry("DICOMWEB", "DICOMWEB"), Map.entry("OCR", "FALLBACK"), Map.entry("MANUAL", "FALLBACK"));

    private final GoldenDatasets datasets;
    private final BundledDrivers bundled;
    private final IngestService ingest;
    private final JdbcTemplate jdbc;
    private final AuditService audit;
    private final Clock clock;
    private final DeviceSpis spis;
    private final DriverRuntime drivers;

    public SimulatorService(GoldenDatasets datasets, BundledDrivers bundled, IngestService ingest, JdbcTemplate jdbc, AuditService audit, Clock clock,
                            DeviceSpis spis, DriverRuntime drivers) {
        this.spis = spis;
        this.drivers = drivers;
        this.datasets = datasets;
        this.bundled = bundled;
        this.ingest = ingest;
        this.jdbc = jdbc;
        this.audit = audit;
        this.clock = clock;
    }

    public record RunResult(UUID runId, String adapter, String datasetId, int messages, int accepted, int duplicates, int failed,
                            List<UUID> messageIds, List<UUID> observationIds, List<String> statuses, String evidenceSha256, String deviceInstanceId,
                            String endpointId, boolean simulated) {
    }

    /** The published device a run is fed as: its driver must speak the dataset's adapter; quarantined devices are refused. */
    DeviceCatalogueLookup.DeviceInstance device(String deviceInstanceId, String adapter) {
        DeviceCatalogueLookup.DeviceInstance i = spis.catalogue().instance(deviceInstanceId)
                .orElseThrow(() -> new ApiException(org.springframework.http.HttpStatus.NOT_FOUND, "device_not_published",
                        "The device instance is not published"));
        if (Set.of("QUARANTINED", "SUSPENDED").contains(i.status())) {
            throw ApiException.conflict("device_quarantined", "The device is quarantined");
        }
        if (!i.published()) {
            throw new ApiException(org.springframework.http.HttpStatus.NOT_FOUND, "device_not_published", "The device instance is not published");
        }
        DriverPackage pkg = drivers.forInstance(i, null);
        if (!adapter.equals(pkg.adapter())) {
            throw new ApiException(org.springframework.http.HttpStatus.UNPROCESSABLE_CONTENT, "dataset_device_mismatch",
                    "The device's driver uses adapter " + pkg.adapter() + ", the dataset " + adapter);
        }
        return i;
    }

    public RunResult run(String actor, String adapter, String datasetId, Integer count, String packageId, String packageVersion, boolean routeTestForm,
                         String tenant) {
        return run(actor, adapter, datasetId, count, packageId, packageVersion, routeTestForm, tenant, null);
    }

    /**
     * @param packageVersion optional pinned version (default: the bundled sample)
     * @param routeTestForm  route to the wizard test form (sinks idempotent; for certification evidence)
     */
    /**
     * @param deviceInstanceId optional published device the readings arrive as (feeds a capture session listening on it)
     */
    public RunResult run(String actor, String adapter, String datasetId, Integer count, String packageId, String packageVersion, boolean routeTestForm,
                         String tenant, String deviceInstanceId) {
        GoldenDatasets.Dataset d = datasets.require(datasetId);
        if (!d.adapter().equals(adapter)) {
            throw ApiException.badRequest("validation_failed", "The dataset belongs to adapter " + d.adapter());
        }
        String kind = KIND.get(adapter);
        if (kind == null) {
            throw ApiException.badRequest("validation_failed", "Unknown adapter");
        }
        String pkgId = packageId == null ? d.packageId() : packageId;
        DriverPackage pkg = bundled.get(pkgId, packageVersion).orElse(null);
        String version = pkg == null ? packageVersion : pkg.version();
        byte[] body = datasets.content(d.resource());
        int n = Math.max(1, Math.min(count == null ? 1 : count, 500));
        boolean duplicate = "DUPLICATE".equals(d.kind());
        UUID run = UUID.randomUUID();
        Instant started = clock.instant();
        String endpoint = "SIM-" + adapter;
        DeviceCatalogueLookup.DeviceInstance device = deviceInstanceId == null ? null : device(deviceInstanceId, adapter);
        if (device != null && routeTestForm) {
            throw ApiException.badRequest("validation_failed", "targetTestForm and deviceInstanceId cannot be combined");
        }
        String tenantCode = device != null && device.tenantCode() != null ? device.tenantCode() : tenant == null ? EngineSettings.DEFAULT_TENANT : tenant;
        IngestService.Source source = device == null ? new IngestService.Source(endpoint, tenantCode, "SIM", Set.of(), true)
                : new IngestService.Source(endpoint, tenantCode, device.siteCode(), Set.of(device.deviceInstanceId()), false);
        List<UUID> messages = new ArrayList<>();
        List<UUID> observations = new ArrayList<>();
        List<String> statuses = new ArrayList<>();
        int accepted = 0;
        int duplicates = 0;
        int failed = 0;
        StringBuilder evidence = new StringBuilder(run.toString());
        long skewSeconds = d.headers() != null && d.headers().containsKey("edgeClockOffsetSeconds")
                ? Long.parseLong(d.headers().get("edgeClockOffsetSeconds")) : 0;
        Map<String, String> headers = new LinkedHashMap<>();
        if (d.headers() != null) {
            d.headers().forEach((k, v) -> {
                if (!"edgeClockOffsetSeconds".equals(k)) {
                    headers.put(k, v);
                }
            });
        }
        for (int i = 0; i < n; i++) {
            int sends = duplicate ? 2 : 1;
            for (int s = 0; s < sends; s++) {
                String key = "SIM:" + run + ":" + i;
                Instant now = clock.instant();
                try {
                    IngestService.Receipt r = ingest.acquire(new IngestService.Acquire(source, kind, d.contentType(), key, (long) i, now,
                            skewSeconds == 0 ? null : now.plusSeconds(skewSeconds), device == null ? endpoint : device.deviceInstanceId(), null, false,
                            body, headers, true, routeTestForm, device == null ? pkgId : null, device == null ? version : null, true));
                    if ("DUPLICATE".equals(r.status())) {
                        duplicates++;
                    } else {
                        accepted++;
                        messages.add(r.messageId());
                    }
                    if (s == 0) {
                        observations.addAll(r.observationIds());
                        statuses.add(r.outcome() == null ? "ACCEPTED" : r.outcome().status()
                                + (r.outcome().errorCode() == null ? "" : ":" + r.outcome().errorCode()));
                    }
                    evidence.append('|').append(r.status()).append(':').append(r.outcome() == null ? "" : r.outcome().status());
                } catch (ApiException e) {
                    failed++;
                    statuses.add("REFUSED:" + e.code());
                    evidence.append("|REFUSED:").append(e.code());
                }
            }
        }
        String sha = Ids.sha256Hex(evidence.toString());
        jdbc.update("""
                insert into deviceint.simulator_run (id, tenant_code, adapter, dataset_id, requested_by, messages, accepted, failed, evidence_sha256,
                    started_at, finished_at) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""", run, source.tenantCode(), adapter, datasetId, actor,
                n * (duplicate ? 2 : 1), accepted, failed, sha, Timestamp.from(started), Timestamp.from(clock.instant()));
        audit.recordAs(actor, "DEVICE", "DEVICE_SIMULATOR_RUN", "DEVICE_SIMULATOR", run.toString(), AuditService.Outcome.SUCCESS,
                adapter + " " + d.kind() + " messages=" + n + (device == null ? "" : " device=" + device.deviceInstanceId()));
        return new RunResult(run, adapter, datasetId, n * (duplicate ? 2 : 1), accepted, duplicates, failed, messages, observations, statuses, sha,
                device == null ? endpoint : device.deviceInstanceId(), endpoint, true);
    }
}
