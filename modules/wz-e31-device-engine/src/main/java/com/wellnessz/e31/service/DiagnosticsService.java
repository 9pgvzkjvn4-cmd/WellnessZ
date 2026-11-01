package com.wellnessz.e31.service;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;

import com.wellnessz.casekernel.device.DeviceDiagnostics;
import com.wellnessz.common.error.ApiException;
import com.wellnessz.e31.driver.DriverPackage;
import com.wellnessz.e31.driver.DriverRuntime;
import com.wellnessz.e31.gate.GateEnvironment;
import com.wellnessz.e31.pipeline.PipelineProcessor;
import com.wellnessz.e31.simulator.DryRun;
import com.wellnessz.e31.simulator.GoldenDatasets;
import com.wellnessz.e31.store.MessageStore;
import com.wellnessz.e31.store.RawPayloadStore;
import com.wellnessz.e31.support.EngineException;
import com.wellnessz.e31.support.Ids;
import com.wellnessz.e31.support.Masking;
import com.wellnessz.e31.terminology.TerminologyService;
import com.wellnessz.security.audit.AuditService;

/**
 * {@link DeviceDiagnostics} for the console (E32-HLR-09, E32-F05, E32-BL-005): PHI-masked trace, connector health, wizard
 * step-6 tests (simulator mode: a golden dataset through the dry-run pipeline with the configuration's driver; live mode:
 * the connector must have been seen within five minutes) and re-send of the last message.
 */
@Service("e31DiagnosticsService")
public class DiagnosticsService implements DeviceDiagnostics {

    private final MessageStore messages;
    private final RawPayloadStore raw;
    private final OperationsService ops;
    private final PipelineProcessor processor;
    private final DriverRuntime drivers;
    private final GoldenDatasets golden;
    private final TerminologyService terminology;
    private final AuditService audit;
    private final Clock clock;

    public DiagnosticsService(MessageStore messages, RawPayloadStore raw, OperationsService ops, PipelineProcessor processor, DriverRuntime drivers,
                              GoldenDatasets golden, TerminologyService terminology, AuditService audit, Clock clock) {
        this.messages = messages;
        this.raw = raw;
        this.ops = ops;
        this.processor = processor;
        this.drivers = drivers;
        this.golden = golden;
        this.terminology = terminology;
        this.audit = audit;
        this.clock = clock;
    }

    @Override
    public List<TraceEntry> trace(String deviceInstanceId, int limit) {
        List<TraceEntry> out = new ArrayList<>();
        for (MessageStore.MessageRow m : messages.recentForInstance(deviceInstanceId, Math.max(1, Math.min(limit, 100)))) {
            String excerpt;
            try {
                excerpt = Masking.excerpt(raw.get(m.rawObjectId()), m.kind());
            } catch (ApiException e) {
                excerpt = Masking.MASK;
            }
            List<MessageStore.StageEntry> stages = messages.stages(m.id());
            MessageStore.StageEntry last = stages.isEmpty() ? null : stages.get(stages.size() - 1);
            String mapping = stages.stream().filter(s -> "NORMALISE".equals(s.stage())).findFirst().map(s -> s.outcome()).orElse(
                    "DEAD_LETTER".equals(m.status()) ? "FAILED" : "PENDING");
            Long offset = m.edgeClock() == null ? null : Duration.between(m.receivedAt(), m.edgeClock()).toMillis();
            String outcome = switch (m.status()) {
                case "DEAD_LETTER" -> "DEAD_LETTER";
                case "RETRY" -> "ERROR";
                default -> last != null && "WARN".equals(last.outcome()) ? "WARN" : "OK";
            };
            out.add(new TraceEntry(m.id(), m.receivedAt(), last == null ? m.stage() : last.stage(), outcome, m.errorCode(), excerpt, mapping, offset));
        }
        return out;
    }

    @Override
    public ConnectorHealth health(String deviceInstanceId) {
        OperationsService.ConnectorHealth h = ops.health(deviceInstanceId);
        return new ConnectorHealth(deviceInstanceId, h.state(), h.lastSeenAt(), h.messages24h(), h.errors24h(), h.p95LatencyMillis(), h.unmatchedOpen(),
                h.deadLetters());
    }

    @Override
    public TestRun runTest(String actor, TestRequest r) {
        List<TestStep> steps = new ArrayList<>();
        StringBuilder evidence = new StringBuilder(String.valueOf(r.configurationId()));
        boolean ok = true;
        if ("LIVE".equals(r.mode())) {
            boolean seen = liveSeen(r);
            steps.add(new TestStep("CONNECTION", seen ? "PASS" : "FAIL", seen ? "Connector seen within 5 minutes" : "No traffic or heartbeat in 5 minutes"));
            ok = seen;
        } else {
            steps.add(new TestStep("CONNECTION", "PASS", "Simulator endpoint SIM-" + (r.option() == null ? "?" : r.option().code())));
            DriverPackage pkg;
            try {
                pkg = drivers.resolve(r.driverPackageId(), r.driverVersion(), null);
                steps.add(new TestStep("DRIVER", "PASS", pkg.ref()));
            } catch (EngineException | ApiException e) {
                steps.add(new TestStep("DRIVER", "FAIL", e instanceof EngineException ee ? ee.code() : ((ApiException) e).code()));
                return finish(actor, r, steps, evidence, false);
            }
            GoldenDatasets.Dataset d = dataset(pkg.packageId(), r.goldenDatasetId());
            if (d == null) {
                steps.add(new TestStep("DATASET", "FAIL", "No golden dataset for the driver"));
                return finish(actor, r, steps, evidence, false);
            }
            DryRun.Result res = DryRun.run(processor.adapters(), terminology, pkg, golden.content(d.resource()), d.contentType(),
                    d.headers() == null ? Map.of() : d.headers(), clock.instant(), null, GateEnvironment.SIMULATOR, 120);
            if (res.error() != null) {
                steps.add(new TestStep("PARSE", "FAIL", res.error()));
                ok = false;
            } else {
                steps.add(new TestStep("PARSE", "PASS", "records=" + res.records().size()));
                DryRun.Record rec = res.records().get(0);
                boolean coded = rec.components().stream().allMatch(c -> c.loincCode() != null && (!"QUANTITY".equals(c.valueType()) || c.ucum() != null));
                steps.add(new TestStep("NORMALISE", coded ? "PASS" : "FAIL", coded ? "LOINC + UCUM on every component" : "uncoded component"));
                boolean blocked = rec.flags().stream().anyMatch(f -> f.outcome() == com.wellnessz.casekernel.device.DeviceCodes.GateOutcome.BLOCK);
                steps.add(new TestStep("GATES", blocked ? "FAIL" : "PASS", blocked ? "a gate blocks the golden result" : "no blocking gate"));
                boolean bound = !rec.boundFields().isEmpty();
                steps.add(new TestStep("BINDING", bound ? "PASS" : "FAIL", bound ? "fields=" + rec.boundFields().size() : "no bound field"));
                ok = coded && !blocked && bound;
            }
            evidence.append('|').append(d.datasetId());
        }
        return finish(actor, r, steps, evidence, ok);
    }

    /**
     * Golden dataset of a test: the full id ({@code vitals/csm-pcd01/normal}), or – as the console sends it – the short kind
     * ({@code normal}, {@code edge} …) of the driver package's datasets; the package's NORMAL dataset when none is named.
     */
    GoldenDatasets.Dataset dataset(String packageId, String datasetId) {
        if (datasetId == null || datasetId.isBlank()) {
            return golden.list(null).stream().filter(x -> x.packageId().equals(packageId) && "NORMAL".equals(x.kind())).findFirst().orElse(null);
        }
        return golden.find(datasetId).or(() -> golden.list(null).stream().filter(x -> x.packageId().equals(packageId))
                .filter(x -> datasetId.equalsIgnoreCase(x.kind()) || x.datasetId().endsWith("/" + datasetId)).findFirst()).orElse(null);
    }

    /**
     * LIVE connection test of a draft (wizard step 6): the device connector was seen within 5 minutes, or – for a device that is
     * not published yet, so the gateway cannot report it – the endpoint fronting it (parameter {@code endpointId}) sent a heartbeat
     * within 5 minutes. The console passes {@code deviceInstanceId} and {@code endpointId} with the draft parameters.
     */
    boolean liveSeen(TestRequest r) {
        Object instance = r.parameters().get("deviceInstanceId");
        if (instance != null) {
            OperationsService.ConnectorHealth h = ops.health(String.valueOf(instance));
            if ("HEALTHY".equals(h.state()) || "DEGRADED".equals(h.state())) {
                return true;
            }
        }
        Object endpoint = r.parameters().get("endpointId");
        if (endpoint == null) {
            return false;
        }
        java.time.Instant heartbeat = ops.lastHeartbeat(String.valueOf(endpoint));
        return heartbeat != null && heartbeat.isAfter(clock.instant().minus(Duration.ofMinutes(5)));
    }

    TestRun finish(String actor, TestRequest r, List<TestStep> steps, StringBuilder evidence, boolean ok) {
        steps.forEach(s -> evidence.append('|').append(s.name()).append(':').append(s.outcome()));
        UUID id = UUID.randomUUID();
        audit.recordAs(actor, "DEVICE", "DEVICE_SIMULATOR_RUN", "DEVICE_CONFIGURATION", r.configurationId() == null ? id.toString() : r.configurationId(),
                AuditService.Outcome.SUCCESS, ok ? "PASSED" : "FAILED");
        return new TestRun(id, ok ? "PASSED" : "FAILED", steps, Ids.sha256Hex(evidence.toString()), clock.instant());
    }

    @Override
    public UUID resendLast(String actor, String deviceInstanceId) {
        MessageStore.MessageRow last = messages.recentForInstance(deviceInstanceId, 20).stream()
                .filter(m -> "PROCESSED".equals(m.status())).findFirst().orElseThrow(() -> ApiException.notFound("Message"));
        processor.tx().executeWithoutResult(s -> messages.resetForReplay(last.id(), null));
        processor.process(last.id(), PipelineProcessor.Options.DEFAULT);
        audit.recordAs(actor, "DEVICE", "DEVICE_RESEND", "DEVICE_MESSAGE", last.id().toString(), AuditService.Outcome.SUCCESS, null);
        return last.id();
    }
}
