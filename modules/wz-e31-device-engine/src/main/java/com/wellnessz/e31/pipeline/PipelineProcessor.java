package com.wellnessz.e31.pipeline;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import ca.uhn.fhir.context.FhirContext;

import com.wellnessz.casekernel.device.DeviceCaptureSessions;
import com.wellnessz.casekernel.device.DeviceCatalogueLookup;
import com.wellnessz.casekernel.device.DeviceCodes;
import com.wellnessz.casekernel.device.DeviceObservation;
import com.wellnessz.casekernel.device.DeviceObservationListener;
import com.wellnessz.casekernel.device.DeviceObservationSink;
import com.wellnessz.casekernel.device.DeviceSpis;
import com.wellnessz.casekernel.support.DeviceCalibrationCheck;
import com.wellnessz.e31.E31Events;
import com.wellnessz.e31.adapter.Adapters;
import com.wellnessz.e31.adapter.ParseContext;
import com.wellnessz.e31.adapter.ParsedRecord;
import com.wellnessz.e31.adapter.xml.XPathParser;
import com.wellnessz.e31.association.AssociationEngine;
import com.wellnessz.e31.association.AssociationService;
import com.wellnessz.e31.driver.DriverPackage;
import com.wellnessz.e31.driver.DriverRuntime;
import com.wellnessz.e31.driver.FallbackDrivers;
import com.wellnessz.e31.gate.EngineGateEnvironment;
import com.wellnessz.e31.gate.GateContext;
import com.wellnessz.e31.gate.GateEnvironment;
import com.wellnessz.e31.gate.Gates;
import com.wellnessz.e31.ops.ConnectorMetrics;
import com.wellnessz.e31.routing.Router;
import com.wellnessz.e31.store.CanonicalDoc;
import com.wellnessz.e31.store.FhirStore;
import com.wellnessz.e31.store.FhirWriter;
import com.wellnessz.e31.store.MessageStore;
import com.wellnessz.e31.store.ObservationStore;
import com.wellnessz.e31.store.RawPayloadStore;
import com.wellnessz.e31.support.EngineException;
import com.wellnessz.e31.support.EngineSettings;
import com.wellnessz.e31.support.Ids;
import com.wellnessz.e31.support.Masking;
import com.wellnessz.e31.support.TimeParsing;
import com.wellnessz.e31.terminology.TerminologyService;
import com.wellnessz.integration.outbox.EventBus;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The canonical pipeline (E31-HLR-01, contract section 8): PARSE → NORMALISE → ASSOCIATE → VALIDATE → ROUTE → PERSIST →
 * ACKNOWLEDGE for one stored message (ACQUIRE is done by {@link IngestService}). Each attempt runs in one transaction that
 * commits the stage results together with the next state (at-least-once, idempotent per stage); sinks run in their own
 * transaction and are idempotent on the deterministic observation id, so retries and replays never duplicate. Deterministic
 * failures (parse, mapping, unit, signature) are dead-lettered at once; transient ones retry with back-off
 * (5 s, 30 s, 2 min, 10 min, 1 h) up to {@code device.dlq.max-attempts}.
 */
@Service("e31PipelineProcessor")
public class PipelineProcessor {

    /** Check type of a point-of-care QC message in the E32 QC schedules (contract section 13.6). */
    static final String QC_CHECK_TYPE = "POC_QC_LOT";

    static String qcTime(String v) {
        if (v == null) {
            return null;
        }
        try {
            return TimeParsing.iso(v, ZoneOffset.UTC).toString();
        } catch (RuntimeException e) {
            return null;
        }
    }


    public static final String ENGINE_ACTOR = "device-engine";
    static final Duration[] BACKOFF = {Duration.ofSeconds(5), Duration.ofSeconds(30), Duration.ofMinutes(2), Duration.ofMinutes(10), Duration.ofHours(1)};
    static final Set<DeviceCodes.Category> RESTRICTED = Set.of(DeviceCodes.Category.BREATH_ALCOHOL, DeviceCodes.Category.DRUG_SCREEN);
    private static final Logger log = LoggerFactory.getLogger(PipelineProcessor.class);

    private final MessageStore messages;
    private final ObservationStore observations;
    private final RawPayloadStore raw;
    private final FhirStore fhirStore;
    private final FhirWriter fhirWriter;
    private final Adapters adapters;
    private final DriverRuntime drivers;
    private final InstanceResolver instances;
    private final TerminologyService terminology;
    private final AssociationService association;
    private final Router router;
    private final EngineGateEnvironment env;
    private final EngineSettings settings;
    private final DeviceSpis spis;
    private final EventBus bus;
    private final ConnectorMetrics metrics;
    private final JsonMapper json;
    private final Clock clock;
    private final TransactionTemplate tx;
    private final TransactionTemplate newTx;

    public PipelineProcessor(MessageStore messages, ObservationStore observations, RawPayloadStore raw, FhirStore fhirStore, FhirContext fhir,
                             DriverRuntime drivers, InstanceResolver instances, TerminologyService terminology, AssociationService association,
                             Router router, EngineGateEnvironment env, EngineSettings settings, DeviceSpis spis, EventBus bus,
                             ConnectorMetrics metrics, JsonMapper json, Clock clock, PlatformTransactionManager transactions) {
        this.messages = messages;
        this.observations = observations;
        this.raw = raw;
        this.fhirStore = fhirStore;
        this.fhirWriter = new FhirWriter(fhir);
        this.adapters = new Adapters(fhir, json);
        this.drivers = drivers;
        this.instances = instances;
        this.terminology = terminology;
        this.association = association;
        this.router = router;
        this.env = env;
        this.settings = settings;
        this.spis = spis;
        this.bus = bus;
        this.metrics = metrics;
        this.json = json;
        this.clock = clock;
        this.tx = new TransactionTemplate(transactions);
        this.newTx = new TransactionTemplate(transactions);
        this.newTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public Adapters adapters() {
        return adapters;
    }

    /**
     * @param pinnedVersion driver version pinned by a replay request; otherwise the currently published version
     */
    public record Options(String pinnedVersion) {
        public static final Options DEFAULT = new Options(null);
    }

    /** @param status PROCESSED | DEAD_LETTER | RETRY | SKIPPED | PARKED */
    public record Outcome(UUID messageId, String status, String errorCode, boolean parseFailed, List<UUID> observationIds,
                          List<String> observationStatuses, List<DeviceObservationListener.ObservationNotice> notices) {
        static Outcome skipped(UUID id, String status) {
            return new Outcome(id, status, null, false, List.of(), List.of(), List.of());
        }
    }

    // ------------------------------------------------------------------ processing attempt

    public Outcome process(UUID messageId, Options options) {
        Outcome out;
        try {
            out = tx.execute(s -> run(messageId, options));
        } catch (RuntimeException e) {
            out = tx.execute(s -> fail(messageId, e));
        }
        notify(out == null ? List.of() : out.notices());
        return out;
    }

    Outcome run(UUID messageId, Options options) {
        Optional<MessageStore.MessageRow> locked = messages.lock(messageId);
        if (locked.isEmpty()) {
            return Outcome.skipped(messageId, "SKIPPED");
        }
        MessageStore.MessageRow m = locked.get();
        if (Set.of("PROCESSED", "DEAD_LETTER", "DISCARDED", "PARKED").contains(m.status())) {
            List<ObservationStore.ObservationRow> obs = observations.forMessage(m.id());
            return new Outcome(m.id(), m.status(), m.errorCode(), false, obs.stream().map(ObservationStore.ObservationRow::id).toList(),
                    obs.stream().map(ObservationStore.ObservationRow::status).toList(), List.of());
        }
        Instant started = clock.instant();
        DeviceCatalogueLookup.DeviceInstance instance = instances.find(m.deviceInstanceId(), m.driverPackageId(), m.driverVersion(), m.tenantCode())
                .orElse(null);
        try {
            if (instance == null) {
                throw new EngineException("device_not_published", "The device instance is not published", true);
            }
            byte[] body = raw.get(m.rawObjectId());
            JsonNode fallback = "FALLBACK".equals(m.kind()) ? json.readTree(body) : null;
            DriverPackage pkg = driver(m, instance, options, fallback);
            if ("POCT1A".equals(m.kind())) {
                String root = XPathParser.rootName(body);
                if ("QCN.R01".equals(root)) {
                    Map<String, String> qc = XPathParser.qcSummary(body);
                    String expiry = qc.get("lotExpiry") == null ? null : qc.get("lotExpiry").substring(0, Math.min(10, qc.get("lotExpiry").length()));
                    String performed = qcTime(qc.get("performedAt"));
                    bus.publish(new E31Events.QcReceived(m.id().toString(), instance.deviceInstanceId(), root, QC_CHECK_TYPE, qc.get("lot"), expiry,
                            qc.get("outcome") == null ? "PASS" : qc.get("outcome"), performed));
                }
            }
            ParseContext ctx = new ParseContext(pkg, body, m.contentType(), m.headers(), m.capturedAt() == null ? m.receivedAt() : m.capturedAt(),
                    TimeParsing.zone(instance.timeZone()));
            List<ParsedRecord> records = adapters.parse(ctx);
            messages.stage(m.id(), null, "PARSE", "OK", "records=" + records.size(), Duration.between(started, clock.instant()).toMillis());
            List<Prepared> prepared = new ArrayList<>();
            for (ParsedRecord rec : records) {
                prepared.add(prepare(m, instance, pkg, rec, fallback));
            }
            List<UUID> ids = new ArrayList<>();
            List<String> statuses = new ArrayList<>();
            List<DeviceObservationListener.ObservationNotice> notices = new ArrayList<>();
            for (Prepared p : prepared) {
                ObservationStore.ObservationRow row = persistAndRoute(m, instance, pkg, p, notices);
                ids.add(row.id());
                statuses.add(row.status());
            }
            messages.update(m.id(), "ACK", "PROCESSED", m.attempts() + 1, null, null, pkg.packageId(), pkg.version());
            messages.resolveOpenDeadLetters(m.id(), "REPLAYED", ENGINE_ACTOR, "processed on replay");
            messages.stage(m.id(), null, "ACK", "OK", null, Duration.between(started, clock.instant()).toMillis());
            metrics.message(instance.deviceInstanceId(), optionCode(instance), instance.siteCode(), "ACK", "OK");
            metrics.latency(instance.deviceInstanceId(), optionCode(instance), instance.siteCode(), "ACK", Duration.between(m.receivedAt(), clock.instant()));
            return new Outcome(m.id(), "PROCESSED", null, false, ids, statuses, notices);
        } catch (EngineException e) {
            if (!e.deterministic()) {
                throw e;
            }
            return deadLetter(m, instance, e.code(), e.getMessage(), m.attempts() + 1);
        }
    }

    Outcome fail(UUID messageId, RuntimeException e) {
        MessageStore.MessageRow m = messages.find(messageId).orElse(null);
        if (m == null) {
            return Outcome.skipped(messageId, "SKIPPED");
        }
        String code = e instanceof EngineException ee ? ee.code() : e instanceof com.wellnessz.common.error.ApiException ae ? ae.code() : "stage_error";
        int attempts = m.attempts() + 1;
        boolean deterministic = e instanceof EngineException ee && ee.deterministic();
        if (!deterministic) {
            log.warn("Device message {} attempt {} failed: {}", messageId, attempts, e.getClass().getSimpleName());
        }
        if (deterministic || attempts >= settings.maxAttempts()) {
            return deadLetter(m, null, code, e.getMessage(), attempts);
        }
        Instant next = clock.instant().plus(BACKOFF[Math.min(attempts - 1, BACKOFF.length - 1)]);
        messages.update(m.id(), m.stage(), "RETRY", attempts, next, code, null, null);
        messages.stage(m.id(), null, m.stage(), "ERROR", code, 0);
        metrics.message(m.deviceInstanceId(), null, m.siteCode(), m.stage(), "RETRY");
        return new Outcome(m.id(), "RETRY", code, false, List.of(), List.of(), List.of());
    }

    Outcome deadLetter(MessageStore.MessageRow m, DeviceCatalogueLookup.DeviceInstance instance, String code, String detail, int attempts) {
        String stage = switch (code) {
            case "parse_failed", "profile_violation", "attachment_unpaired" -> "PARSE";
            case "mapping_missing", "unit_unconvertible" -> "NORMALISE";
            case "binding_field_unknown", "route_target_unavailable" -> "ROUTE";
            default -> m.stage();
        };
        messages.update(m.id(), stage, "DEAD_LETTER", attempts, null, code, null, null);
        messages.deadLetter(m, null, stage, code, Masking.detail(detail), attempts);
        messages.stage(m.id(), null, stage, "DEAD_LETTER", code, 0);
        raw.retainAsUnfiled(m.rawObjectId());
        metrics.deadLetter(m.deviceInstanceId(), stage, code);
        boolean parseFailed = "PARSE".equals(stage);
        return new Outcome(m.id(), "DEAD_LETTER", code, parseFailed, List.of(), List.of(), List.of());
    }

    DriverPackage driver(MessageStore.MessageRow m, DeviceCatalogueLookup.DeviceInstance instance, Options options, JsonNode fallback) {
        if (fallback != null) {
            String method = fallback.path("method").asString("MANUAL_ATTESTED");
            String adapter = "OCR_VERIFIED".equals(method) ? "OCR" : "MANUAL";
            if (instance.driverPackageId() != null) {
                try {
                    DriverPackage own = drivers.forInstance(instance, options.pinnedVersion());
                    if (adapter.equals(own.adapter())) {
                        return own;
                    }
                } catch (EngineException e) {
                    if (!e.deterministic()) {
                        throw e;
                    }
                }
            }
            DeviceCodes.Category category = DeviceCodes.Category.valueOf(fallback.path("category").asString("VITALS"));
            Map<String, Object> values = new LinkedHashMap<>();
            for (Map.Entry<String, JsonNode> e : fallback.path("values").properties()) {
                JsonNode v = e.getValue();
                values.put(e.getKey(), v.isNumber() ? v.decimalValue() : v.isBoolean() ? Boolean.valueOf(v.asBoolean()) : v.asString());
            }
            return FallbackDrivers.forCategory(category, adapter, values);
        }
        String pinned = options.pinnedVersion();
        if (pinned == null && DriverRuntime.virtual(instance.deviceInstanceId())) {
            pinned = m.driverVersion();
        }
        return drivers.forInstance(instance, pinned);
    }

    static String optionCode(DeviceCatalogueLookup.DeviceInstance i) {
        return i == null || i.option() == null ? null : i.option().code();
    }

    // ------------------------------------------------------------------ per record

    /** Pure part of a record: normalisation, session, binding and the deterministic id (no writes). */
    record Prepared(ParsedRecord record, Normaliser.Result norm, DeviceCaptureSessions.SessionBinding session, Router.Selection selection,
                    UUID observationId, DeviceCodes.CaptureMethod captureMethod, CanonicalDoc.Fallback fallback, String enteredEmployee) {
    }

    Prepared prepare(MessageStore.MessageRow m, DeviceCatalogueLookup.DeviceInstance instance, DriverPackage pkg, ParsedRecord rec, JsonNode fb) {
        Normaliser.Result norm = Normaliser.normalise(pkg, rec, terminology,
                new Normaliser.Context(m.receivedAt(), m.capturedAt(), m.edgeClock(), settings.maxSkewSeconds()));
        if (simulatedOnDevice(m, instance)) {
            // a simulator run on a published device arrives "now": the golden dataset's own timestamp would fall outside the
            // capture session's window and would mis-date the reading if a session accepts it
            norm = new Normaliser.Result(norm.components(), m.receivedAt(), norm.deviceTime(), norm.clockSkewMillis(), norm.demographics(),
                    norm.derived());
        }
        DeviceCaptureSessions.SessionBinding session = association.session(instance, norm.effectiveAt()).orElse(null);
        String preferred = fb == null ? null : text(fb, "formCode");
        // fall-back values use the binding and gates of their FORM driver (the device's own binding may assume a connected device)
        Router.Selection sel = router.select(pkg, fb != null ? null : instance, session, preferred);
        UUID id = Ids.observationId(m.id(), rec.index(), Router.bindingKeys(sel));
        DeviceCodes.CaptureMethod method = DeviceCodes.CaptureMethod.DEVICE;
        CanonicalDoc.Fallback fallback = null;
        String entered = null;
        if (fb != null) {
            method = DeviceCodes.CaptureMethod.valueOf(fb.path("method").asString("MANUAL_ATTESTED"));
            fallback = new CanonicalDoc.Fallback(text(fb, "attestedModel"), text(fb, "attestedSerial"), text(fb, "attestedCalibrationId"),
                    TimeParsing.date(text(fb, "attestedCalibrationDate")), fb.hasNonNull("ocrConfidence") ? fb.get("ocrConfidence").decimalValue() : null,
                    fb.hasNonNull("imageArchiveId") ? UUID.fromString(fb.get("imageArchiveId").asString()) : null, text(fb, "attestation"));
            entered = text(fb, "employeeId");
        }
        return new Prepared(rec, norm, session, sel, id, method, fallback, entered);
    }

    /** Simulator test traffic fed as a published device instance ({@code deviceInstanceId} on the simulator run). */
    static boolean simulatedOnDevice(MessageStore.MessageRow m, DeviceCatalogueLookup.DeviceInstance instance) {
        return m.testTraffic() && instance != null && !DriverRuntime.virtual(instance.deviceInstanceId());
    }

    /** Quality flag shown with every simulated reading (preview, observation view). */
    static DeviceObservation.QualityFlag simulatedFlag() {
        return new DeviceObservation.QualityFlag("SIMULATED", DeviceCodes.GateOutcome.INFO, "simulated_test_traffic",
                "Simulated test reading from a simulator run – not measured on this person.");
    }

    static String text(JsonNode n, String member) {
        JsonNode v = n.get(member);
        return v == null || v.isNull() ? null : v.asString();
    }

    ObservationStore.ObservationRow persistAndRoute(MessageStore.MessageRow m, DeviceCatalogueLookup.DeviceInstance instance, DriverPackage pkg,
                                                     Prepared p, List<DeviceObservationListener.ObservationNotice> notices) {
        ObservationStore.ObservationRow existing = observations.lock(p.observationId()).orElse(null);
        if (existing != null && Set.of("ROUTED", "REJECTED").contains(existing.status())) {
            messages.stage(m.id(), existing.id(), "ROUTE", "OK", "already_" + existing.status().toLowerCase(java.util.Locale.ROOT), 0);
            return existing;
        }
        DeviceCodes.Category category = p.selection().category();
        boolean restricted = RESTRICTED.contains(category);
        boolean virtual = DriverRuntime.virtual(instance.deviceInstanceId());
        AssociationService.Request request = new AssociationService.Request(p.observationId(), instance, p.record().identity(),
                category, p.norm().effectiveAt(), restricted, existing, p.enteredEmployee(), p.session(), true);
        // test traffic associates only with a capture session that listens on the device – never by the synthetic identity in the
        // dataset (vendor id, badge, worklist), so it cannot land in a real employee's record or in the clinicians' queue
        AssociationService.Result assoc = m.testTraffic() ? association.associateTestTraffic(request) : association.associate(request);
        AssociationEngine.Decision d = assoc.decision();
        boolean verify = p.selection().verifyBeforePost() || p.captureMethod() != DeviceCodes.CaptureMethod.DEVICE || restricted || m.testTraffic();
        String employee = d.associated() ? d.employeeId() : null;
        GateEnvironment gateEnv = virtual ? GateEnvironment.SIMULATOR : env;
        GateContext gctx = new GateContext(pkg, category, p.norm().components(), instance.deviceInstanceId(), instance.equipmentDeviceId(),
                instance.modelCode(), instance.parameters(), employee, p.norm().effectiveAt(), p.norm().clockSkewMillis(), settings.maxSkewSeconds(),
                p.captureMethod(), p.session() == null ? null : p.session().mode(), att(p, a -> a.attestedModel()), att(p, a -> a.attestedSerial()),
                att(p, a -> a.attestedCalibrationId()), p.fallback() == null ? null : p.fallback().attestedCalibrationDate(),
                p.fallback() == null ? null : p.fallback().ocrConfidence(), settings.ocrMinConfidence(), verify, gateEnv);
        List<DeviceObservation.QualityFlag> flags = new ArrayList<>(Gates.run(p.selection().qualityGates(), gctx));
        if (m.testTraffic()) {
            flags.add(simulatedFlag());
        }
        if (employee != null) {
            observations.latestRouted(employee, category).filter(l -> l.effectiveAt().isAfter(p.norm().effectiveAt()) && !l.id().equals(p.observationId()))
                    .ifPresent(l -> flags.add(new DeviceObservation.QualityFlag("OUT_OF_SEQUENCE", DeviceCodes.GateOutcome.INFO, "out_of_sequence",
                            "A later result of the same kind is already filed; this one was measured earlier.")));
        }
        boolean blocked = flags.stream().anyMatch(f -> f.outcome() == DeviceCodes.GateOutcome.BLOCK);
        String status = !d.associated() ? "UNMATCHED" : blocked ? "BLOCKED" : verify ? "PENDING_VERIFICATION" : "VALIDATED";
        DeviceCalibrationCheck.Status cal = calibration(instance, p.norm().effectiveAt(), gateEnv);
        DeviceObservation.Provenance prov = new DeviceObservation.Provenance(instance.modelCode(), instance.manufacturer(), instance.model(),
                instance.udi(), p.record().identity().deviceSerial() != null ? p.record().identity().deviceSerial() : instance.serial(),
                instance.firmware(), pkg.protocolText("software"), pkg.packageId(), pkg.version(),
                cal == null ? null : cal.calibrationId(), cal == null || cal.calibrationDueOn() == null ? null : cal.calibrationDueOn(),
                cal == null ? null : cal.verdict().name(),
                p.record().identity().operator() != null ? p.record().identity().operator() : p.session() == null ? null : p.session().operator(),
                null, null, d.associated() ? d.method() : null, d.associated() ? d.confidence() : null, p.norm().clockSkewMillis(), m.sha256(),
                m.testTraffic());
        List<DeviceObservation.Attachment> attachments = attachments(m, p);
        CanonicalDoc.Demographics demo = new CanonicalDoc.Demographics(p.norm().demographics().sex(), p.norm().demographics().ageYears(),
                p.norm().demographics().heightCm(), p.norm().demographics().dateOfBirth(), null);
        CanonicalDoc doc = new CanonicalDoc(p.norm().components(), flags, prov, attachments, demo, p.norm().derived(), p.fallback());
        Map<String, Object> bound = Router.boundFields(p.selection(), p.norm().components(), p.norm().derived(), instance.parameters());
        UUID reportId = Set.of(DeviceCodes.Category.AUDIOMETRY, DeviceCodes.Category.SPIROMETRY, DeviceCodes.Category.ECG, DeviceCodes.Category.POC,
                DeviceCodes.Category.FIT_TEST, DeviceCodes.Category.ABPM_HOLTER, DeviceCodes.Category.IMAGING).contains(category)
                ? Ids.reportId(p.observationId()) : null;
        UUID sessionId = p.session() == null ? null : p.session().sessionId();
        UUID caseId = assoc.caseId();
        Instant now = clock.instant();
        ObservationStore.ObservationRow row = new ObservationStore.ObservationRow(p.observationId(), m.id(), p.record().index(), reportId,
                m.tenantCode(), instance.siteCode(), employee, caseId, assoc.orderRef(), instance.deviceInstanceId(), instance.equipmentDeviceId(),
                category, status, p.captureMethod(), restricted, d.associated() ? d.method() : null, d.associated() ? d.confidence() : null, sessionId,
                p.selection().formCode(), p.selection().formVersion(), p.selection().target(), verify, null, null, p.norm().effectiveAt(),
                p.norm().clockSkewMillis(), m.gatewaySequence(), pkg.packageId(), pkg.version(), doc, bound, m.testTraffic(), null, null, null,
                existing == null ? 0 : existing.routeAttempts(), existing == null ? now : existing.createdAt(), now);
        messages.stage(m.id(), row.id(), "NORMALISE", "OK", null, 0);
        messages.stage(m.id(), row.id(), "ASSOCIATE", d.associated() ? "OK" : "WARN", d.associated() ? d.method() : d.reason(), 0);
        messages.stage(m.id(), row.id(), "VALIDATE", blocked ? "WARN" : "OK", blocked ? "observation_blocked" : null, 0);
        observations.upsert(row);
        attachments.forEach(a -> raw.reference(a.archiveId()));
        if ("UNMATCHED".equals(status) && m.testTraffic()) {
            // simulator traffic without a listening capture session: kept for the run's evidence, never queued for clinicians
            messages.stage(m.id(), row.id(), "ASSOCIATE", "WARN", "test_traffic_unassociated", 0);
        } else if ("UNMATCHED".equals(status)) {
            List<ObservationStore.Suggestion> sug = d.suggestions().stream()
                    .map(c -> new ObservationStore.Suggestion(c.employeeId(), c.displayName(), c.method(), c.confidence())).toList();
            observations.openUnmatched(row, d.reason(), sug, now.plus(Duration.ofHours(settings.unmatchedSlaHours())));
            metrics.unmatched(instance.siteCode());
        } else if (existing != null && "UNMATCHED".equals(existing.status())) {
            observations.closeUnmatchedForObservation(row.id(), ENGINE_ACTOR);
        }
        if ("VALIDATED".equals(status)) {
            row = route(row, m, instance, ENGINE_ACTOR, false);
        } else {
            writeFhir(row, instance, m, "preliminary");
        }
        notices.add(notice(row));
        metrics.message(instance.deviceInstanceId(), optionCode(instance), instance.siteCode(), "VALIDATE", row.status());
        return row;
    }

    interface FallbackField {
        String get(CanonicalDoc.Fallback f);
    }

    static String att(Prepared p, FallbackField f) {
        return p.fallback() == null ? null : f.get(p.fallback());
    }

    static DeviceCalibrationCheck.Status calibration(DeviceCatalogueLookup.DeviceInstance instance, Instant at, GateEnvironment env) {
        if (instance.equipmentDeviceId() == null && !DriverRuntime.virtual(instance.deviceInstanceId())) {
            return null;
        }
        List<DeviceCalibrationCheck.Status> s = env.calibration(instance.equipmentDeviceId(), LocalDate.ofInstant(at, ZoneOffset.UTC));
        return s.isEmpty() ? null : s.get(0);
    }

    List<DeviceObservation.Attachment> attachments(MessageStore.MessageRow m, Prepared p) {
        List<DeviceObservation.Attachment> out = new ArrayList<>();
        for (String key : List.of("attachmentArchiveId", "pairedArchiveId", "imageArchiveId")) {
            String id = m.headers().get(key);
            if (id != null) {
                addAttachment(out, UUID.fromString(id), m.tenantCode());
            }
        }
        if (p.fallback() != null && p.fallback().imageArchiveId() != null) {
            addAttachment(out, p.fallback().imageArchiveId(), m.tenantCode());
        }
        return out;
    }

    void addAttachment(List<DeviceObservation.Attachment> out, UUID id, String tenant) {
        if (out.stream().anyMatch(a -> a.archiveId().equals(id))) {
            return;
        }
        raw.info(id).filter(i -> i.tenantCode().equals(tenant)).ifPresent(i -> out.add(new DeviceObservation.Attachment(i.id(),
                "MESSAGE".equals(i.kind()) ? "RAW" : i.kind(), i.mediaType(), i.sha256(), i.sizeBytes())));
    }

    // ------------------------------------------------------------------ routing (auto-post and clinician accept)

    /**
     * ROUTE → PERSIST → ACK of one observation. Simulator traffic is never routed to production sinks (it ends VALIDATED
     * unless the run targets the wizard test form).
     */
    public ObservationStore.ObservationRow route(ObservationStore.ObservationRow row, MessageStore.MessageRow m,
                                                 DeviceCatalogueLookup.DeviceInstance instance, String actor, boolean verified) {
        if (row.testTraffic() && !sessionAccepted(row, verified)) {
            if (m != null && m.routeTestForm()) {
                // wizard test form: the run's evidence – never a production sink
                ObservationStore.ObservationRow test = copy(row, "ROUTED", "TEST_FORM", row.id().toString(), null, row.routeAttempts() + 1);
                observations.upsert(test);
                messages.stage(m.id(), row.id(), "ROUTE", "OK", "test_form", 0);
                writeFhir(test, instance, m, "preliminary");
                return test;
            }
            writeFhir(row, instance, m, "preliminary");
            return row;
        }
        Optional<DeviceObservationSink.Delivery> delivery;
        try {
            ObservationStore.ObservationRow r = row;
            delivery = newTx.execute(s -> router.deliver(actor, r, verified));
        } catch (RuntimeException e) {
            log.warn("Routing of observation {} to {} failed: {}", row.id(), row.routeTarget(), e.getClass().getSimpleName());
            delivery = null;
        }
        Instant now = clock.instant();
        ObservationStore.ObservationRow out;
        if (delivery == null || delivery.isEmpty()) {
            int attempts = row.routeAttempts() + 1;
            boolean dead = attempts >= settings.maxAttempts();
            out = copy(row, dead ? "DEAD_LETTER" : "ROUTE_PENDING", null, null, null, attempts);
            observations.upsert(out);
            String code = delivery == null ? "sink_error" : "route_target_unavailable";
            if (m != null) {
                messages.stage(m.id(), row.id(), "ROUTE", dead ? "DEAD_LETTER" : "ERROR", code, 0);
                if (dead) {
                    messages.deadLetter(m, row.id(), "ROUTE", "route_target_unavailable", "Target " + row.routeTarget() + " unavailable", attempts);
                }
            }
            writeFhir(out, instance, m, "preliminary");
            return out;
        }
        DeviceObservationSink.Delivery d = delivery.get();
        if (d.status() == DeviceObservationSink.Delivery.Status.REJECTED) {
            List<DeviceObservation.QualityFlag> flags = new ArrayList<>(row.canonical().flags());
            flags.add(new DeviceObservation.QualityFlag("ROUTING", DeviceCodes.GateOutcome.WARN, "sink_rejected:" + d.code(),
                    d.message() == null ? "The target form rejected the result" : Masking.detail(d.message())));
            out = new ObservationStore.ObservationRow(row.id(), row.messageId(), row.recordIndex(), row.reportId(), row.tenantCode(), row.siteCode(),
                    row.employeeId(), row.caseId(), row.orderRef(), row.deviceInstanceId(), row.equipmentDeviceId(), row.category(), "REJECTED",
                    row.captureMethod(), row.restricted(), row.associationMethod(), row.confidence(), row.sessionId(), row.formCode(), row.formVersion(),
                    row.routeTarget(), row.verifyBeforePost(), null, null, row.effectiveAt(), row.clockSkewMs(), row.gatewaySequence(),
                    row.driverPackageId(), row.driverVersion(), row.canonical().withFlags(flags), row.boundFields(), row.testTraffic(), row.verifiedBy(),
                    row.verifiedAt(), truncate(d.code(), 30), row.routeAttempts() + 1, row.createdAt(), now);
            observations.upsert(out);
            if (m != null) {
                messages.stage(m.id(), row.id(), "ROUTE", "REJECTED", truncate("sink_rejected:" + d.code(), 80), 0);
                raw.retainAsUnfiled(m.rawObjectId());
            }
            writeFhir(out, instance, m, "preliminary");
            return out;
        }
        out = copy(row, "ROUTED", d.recordType(), d.recordId(), null, row.routeAttempts() + 1);
        observations.upsert(out);
        if (m != null) {
            raw.retainAsPayload(m.rawObjectId());
            messages.stage(m.id(), row.id(), "ROUTE", "OK", d.status().name(), 0);
            messages.stage(m.id(), row.id(), "PERSIST", "OK", null, 0);
        }
        out.canonical().attachments().forEach(a -> raw.retainAsPayload(a.archiveId()));
        writeFhir(out, instance, m, "final");
        bus.publish(new E31Events.ObservationRouted(out.id().toString(), out.category().name(), out.routeTarget(), d.recordId(),
                out.deviceInstanceId()));
        return out;
    }

    /**
     * Test traffic reaches a production sink only when a clinician accepted it in the capture session it was associated with
     * (session association, verified accept) – the explicit opt-in of contract section 12.
     */
    static boolean sessionAccepted(ObservationStore.ObservationRow row, boolean verified) {
        return verified && row.sessionId() != null && row.employeeId() != null && "SESSION".equals(row.associationMethod());
    }

    static ObservationStore.ObservationRow copy(ObservationStore.ObservationRow r, String status, String recordType, String recordId, String reject,
                                                int attempts) {
        return new ObservationStore.ObservationRow(r.id(), r.messageId(), r.recordIndex(), r.reportId(), r.tenantCode(), r.siteCode(), r.employeeId(),
                r.caseId(), r.orderRef(), r.deviceInstanceId(), r.equipmentDeviceId(), r.category(), status, r.captureMethod(), r.restricted(),
                r.associationMethod(), r.confidence(), r.sessionId(), r.formCode(), r.formVersion(), r.routeTarget(), r.verifyBeforePost(),
                recordType == null ? r.routedRecordType() : recordType, recordId == null ? r.routedRecordId() : recordId, r.effectiveAt(), r.clockSkewMs(),
                r.gatewaySequence(), r.driverPackageId(), r.driverVersion(), r.canonical(), r.boundFields(), r.testTraffic(), r.verifiedBy(), r.verifiedAt(),
                reject == null ? r.rejectReason() : reject, attempts, r.createdAt(), r.updatedAt());
    }

    static String truncate(String s, int n) {
        return s == null || s.length() <= n ? s : s.substring(0, n);
    }

    void writeFhir(ObservationStore.ObservationRow row, DeviceCatalogueLookup.DeviceInstance instance, MessageStore.MessageRow m, String status) {
        String endpoint = m == null ? null : m.endpointId();
        String rawId = m == null ? null : m.rawObjectId().toString();
        fhirStore.save(fhirWriter.write(row, instance, row.tenantCode(), endpoint, rawId, status), row, status);
    }

    /** Retries routing of an observation whose target was unavailable (ROUTE_PENDING), keeping the original verifier. */
    public void retryRoute(UUID observationId) {
        List<DeviceObservationListener.ObservationNotice> notices = tx.execute(s -> {
            ObservationStore.ObservationRow o = observations.lock(observationId).orElse(null);
            if (o == null || !"ROUTE_PENDING".equals(o.status())) {
                return List.<DeviceObservationListener.ObservationNotice>of();
            }
            MessageStore.MessageRow m = messages.find(o.messageId()).orElse(null);
            DeviceCatalogueLookup.DeviceInstance instance = instances.find(o.deviceInstanceId(), o.driverPackageId(), o.driverVersion(), o.tenantCode())
                    .orElse(null);
            ObservationStore.ObservationRow r = route(o, m, instance, o.verifiedBy() == null ? ENGINE_ACTOR : o.verifiedBy(), o.verifiedBy() != null);
            return List.of(notice(r));
        });
        notify(notices == null ? List.of() : notices);
    }

    /** Attaches a late attachment (paired PDF) to the observations of a data message; FHIR resources are rewritten. */
    public void attach(UUID dataMessageId, UUID archiveId) {
        tx.executeWithoutResult(s -> {
            MessageStore.MessageRow m = messages.find(dataMessageId).orElse(null);
            if (m == null) {
                return;
            }
            DeviceCatalogueLookup.DeviceInstance instance = instances.find(m.deviceInstanceId(), m.driverPackageId(), m.driverVersion(), m.tenantCode())
                    .orElse(null);
            for (ObservationStore.ObservationRow o : observations.forMessage(dataMessageId)) {
                List<DeviceObservation.Attachment> list = new ArrayList<>(o.canonical().attachments());
                addAttachment(list, archiveId, m.tenantCode());
                ObservationStore.ObservationRow updated = new ObservationStore.ObservationRow(o.id(), o.messageId(), o.recordIndex(), o.reportId(),
                        o.tenantCode(), o.siteCode(), o.employeeId(), o.caseId(), o.orderRef(), o.deviceInstanceId(), o.equipmentDeviceId(), o.category(),
                        o.status(), o.captureMethod(), o.restricted(), o.associationMethod(), o.confidence(), o.sessionId(), o.formCode(), o.formVersion(),
                        o.routeTarget(), o.verifyBeforePost(), o.routedRecordType(), o.routedRecordId(), o.effectiveAt(), o.clockSkewMs(),
                        o.gatewaySequence(), o.driverPackageId(), o.driverVersion(), o.canonical().withAttachments(list), o.boundFields(), o.testTraffic(),
                        o.verifiedBy(), o.verifiedAt(), o.rejectReason(), o.routeAttempts(), o.createdAt(), o.updatedAt());
                observations.upsert(updated);
                if ("ROUTED".equals(o.status())) {
                    raw.retainAsPayload(archiveId);
                } else {
                    raw.reference(archiveId);
                }
                if (instance != null) {
                    writeFhir(updated, instance, m, "ROUTED".equals(o.status()) ? "final" : "preliminary");
                }
            }
        });
    }

    public FhirWriter fhirWriter() {
        return fhirWriter;
    }

    // ------------------------------------------------------------------ listeners (after commit)

    public static DeviceObservationListener.ObservationNotice notice(ObservationStore.ObservationRow row) {
        return new DeviceObservationListener.ObservationNotice(row.id(), row.sessionId(), row.deviceInstanceId(), row.employeeId(), row.category(),
                row.status(), row.canonical().flags(), Instant.now());
    }

    /** Must run after commit; listener failures are logged and ignored (contract: must be fast and must not throw). */
    /**
     * Notifies the observation listeners (E33) – after the commit of the surrounding transaction when there is one (contract
     * section 14: "called after commit"). A command such as {@code accept} or {@code submitFallback} runs inside the caller's
     * transaction (E33 holds its capture session there); notifying before that commit let the listener update the same session
     * in its own transaction, and the caller's commit then failed with an optimistic-locking conflict (500 on accept).
     */
    public void notify(List<DeviceObservationListener.ObservationNotice> notices) {
        if (notices == null || notices.isEmpty()) {
            return;
        }
        List<DeviceObservationListener.ObservationNotice> copy = List.copyOf(notices);
        if (org.springframework.transaction.support.TransactionSynchronizationManager.isSynchronizationActive()
                && org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()) {
            org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                    new org.springframework.transaction.support.TransactionSynchronization() {
                        @Override
                        public void afterCommit() {
                            dispatch(copy);
                        }
                    });
            return;
        }
        dispatch(copy);
    }

    private void dispatch(List<DeviceObservationListener.ObservationNotice> notices) {
        for (DeviceObservationListener.ObservationNotice n : notices) {
            for (DeviceObservationListener l : spis.observationListeners()) {
                try {
                    l.observationUpdated(n);
                } catch (RuntimeException e) {
                    log.warn("Device observation listener failed: {}", e.getClass().getSimpleName());
                }
            }
        }
    }

    public MessageStore messages() {
        return messages;
    }

    public TransactionTemplate tx() {
        return tx;
    }

    public InstanceResolver instances() {
        return instances;
    }
}
