package io.intimed.capture;

import io.intimed.common.ApiException;
import io.intimed.common.Json;
import io.intimed.engine.association.AssociationEngine;
import io.intimed.engine.pipeline.Envelope;
import io.intimed.engine.pipeline.Pipeline;
import io.intimed.engine.pipeline.PipelineResult;
import io.intimed.engine.sim.Simulator;
import io.intimed.engine.driver.DriverPackage;
import io.intimed.engine.driver.DriverRegistry;
import io.intimed.engine.transport.DeviceListeners;
import io.intimed.engine.transport.TransportClient;
import io.intimed.harness.ProbeIdentity;
import io.intimed.security.audit.AuditService;
import io.intimed.store.DocumentStore;
import io.intimed.templates.TemplateRegistry;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

/**
 * Clinical capture experience (IM-E05-F04/F05/F07): lists certified, published, in-calibration devices for a template
 * at a station or bed; starts a capture session (association), triggers acquisition where the device supports start
 * from the form (simulated devices in the local kit send through the demo gateway's mTLS identity), shows held results
 * with quality flags for accept / correct / reject, offers the fallback chain (other certified device → OCR with
 * verification → manual with attestation) and shows continuous-feed interval values without alarms.
 */
@Service
public class CaptureService {
    private final DocumentStore store;
    private final TemplateRegistry templates;
    private final AssociationEngine association;
    private final Pipeline pipeline;
    private final DriverRegistry drivers;
    private final ProbeIdentity probes;
    private final DeviceListeners listeners;
    private final AuditService audit;

    public CaptureService(DocumentStore store, TemplateRegistry templates, AssociationEngine association, Pipeline pipeline, DriverRegistry drivers,
                          ProbeIdentity probes, DeviceListeners listeners, AuditService audit) {
        this.store = store;
        this.templates = templates;
        this.association = association;
        this.pipeline = pipeline;
        this.drivers = drivers;
        this.probes = probes;
        this.listeners = listeners;
        this.audit = audit;
    }

    public ArrayNode devices(String tenant, String templateId, String station) {
        ArrayNode out = Json.arr();
        for (ObjectNode c : store.listByStatus(tenant, "config", "PUBLISHED")) {
            boolean bound = false;
            for (JsonNode b : c.path("bindings")) bound |= templateId.equals(b.path("templateId").asString());
            if (!bound) continue;
            if (station != null && !station.isBlank() && !station.equals(c.at("/site/station").asString()) && !station.equals(c.at("/site/bed").asString())) continue;
            boolean calOk = !"LOCKED".equals(c.at("/calibration/status").asString()) && (!c.at("/calibration/dueDate").isString()
                    || !LocalDate.parse(c.at("/calibration/dueDate").asString()).isBefore(LocalDate.now()));
            String ep = c.path("endpointId").asString(null);
            String epStatus = ep == null ? "n/a" : store.get(tenant, "endpoint", ep).map(e -> e.path("status").asString()).orElse("UNKNOWN");
            boolean available = calOk && !c.path("recertificationRequired").asBoolean(false) && !"ISOLATED".equals(epStatus) && !"REVOKED".equals(epStatus);
            out.addObject().put("configId", c.get("id").asString()).put("name", c.get("name").asString()).put("model", c.path("model").asString())
                    .put("option", c.path("option").asString()).put("station", c.at("/site/station").asString("")).put("bed", c.at("/site/bed").asString(""))
                    .put("captureMode", c.path("captureMode").asString()).put("calibrationOk", calOk).put("endpointStatus", epStatus).put("available", available)
                    .put("reason", available ? "" : !calOk ? "calibration lockout" : "device isolated / re-certification required");
        }
        return out;
    }

    /** Fallback chain when the preferred device is unavailable (IM-E05-BL-006). */
    public ArrayNode fallback(String tenant, String templateId, String station) {
        ArrayNode chain = Json.arr();
        for (JsonNode d : devices(tenant, templateId, null)) if (d.get("available").asBoolean()) chain.addObject().put("step", "OTHER_CERTIFIED_DEVICE").put("configId", d.get("configId").asString()).put("label", d.get("name").asString() + " (" + d.get("option").asString() + ")");
        for (JsonNode d : devices(tenant, templateId, null)) if ("P9".equals(d.get("option").asString())) chain.addObject().put("step", "OCR_WITH_VERIFICATION").put("configId", d.get("configId").asString()).put("label", "Photo + OCR – " + d.get("name").asString());
        for (JsonNode d : devices(tenant, templateId, null)) if ("P10".equals(d.get("option").asString())) chain.addObject().put("step", "MANUAL_WITH_ATTESTATION").put("configId", d.get("configId").asString()).put("label", "Manual entry with attestation – " + d.get("name").asString());
        chain.addObject().put("step", "MANUAL_WITH_ATTESTATION").put("configId", (String) null).put("label", "Manual entry in the host form (no device binding)");
        return chain;
    }

    public ObjectNode startSession(String tenant, String station, String subject, String templateId, String user) {
        templates.require(tenant, templateId);
        if (station == null || subject == null || subject.isBlank()) throw ApiException.bad("invalid-session", "station and subject are required");
        ObjectNode s = association.openSession(tenant, station, subject, templateId, user, 15);
        audit.record(tenant, "user:" + user, "capture.session.start", "station/" + station, Map.of("template", templateId));
        return s;
    }

    /** 'Capture from device': the local kit asks the simulated device behind the demo gateway to measure now. */
    public ObjectNode acquire(String tenant, String configId, String subjectForMessage) {
        ObjectNode c = store.require(tenant, "config", configId);
        if (!"PUBLISHED".equals(c.path("status").asString())) throw ApiException.conflict("not-published", "device is not published");
        DriverPackage d = drivers.require(tenant, c.get("driver").asString());
        String serial = c.path("expectedSerials").isArray() && !c.get("expectedSerials").isEmpty() ? c.get("expectedSerials").get(0).asString() : d.tests().path("serial").asString();
        Map<String, String> v = Simulator.vary(Simulator.values(d), System.nanoTime());
        String payload = Simulator.render(d, v, subjectForMessage, Instant.now().truncatedTo(ChronoUnit.SECONDS), serial);
        return sendLive(tenant, c, payload);
    }

    public ObjectNode sendLive(String tenant, JsonNode c, String payload) {
        ProbeIdentity.Creds gw = probes.demoGateway(tenant);
        try {
            if ("P4".equals(c.path("option").asString())) {
                TransportClient.Sent s = TransportClient.mllp("localhost", listeners.mllpPort(), gw.ssl(), payload);
                return Json.obj().put("transport", "MLLP-TLS " + s.protocol()).put("ack", s.response().replace("\r", "\n")).put("millis", s.millis());
            }
            ObjectNode env = Json.obj().put("configId", c.get("id").asString()).put("option", c.path("option").asString()).put("payload", payload);
            TransportClient.Sent s = TransportClient.ingress("localhost", listeners.ingressPort(), gw.ssl(), env);
            ObjectNode r = (ObjectNode) Json.parse(s.response());
            r.put("transport", "HTTPS ingress " + s.protocol()).put("millis", s.millis());
            return r;
        } catch (Exception e) {
            throw ApiException.conflict("transport", "device transport failed: " + e.getClass().getSimpleName());
        }
    }

    /** P9 / P10 captured in the component itself (authenticated clinician session). */
    public ObjectNode submitForm(String tenant, String configId, JsonNode body, String user) {
        ObjectNode c = store.require(tenant, "config", configId);
        if (!List.of("P9", "P10").contains(c.path("option").asString())) throw ApiException.bad("not-form-device", "only OCR / manual configurations accept form capture");
        ObjectNode env = (ObjectNode) body.deepCopy();
        if (!env.has("time")) env.put("time", Instant.now().toString());
        PipelineResult r = pipeline.process(new Envelope(tenant, null, configId, c.get("option").asString(), Json.write(env), "LIVE", null, null, "capture-component:" + user, Instant.now()));
        return (ObjectNode) Json.tree(r.summary());
    }

    public ArrayNode pending(String tenant, String station) {
        ArrayNode out = Json.arr();
        for (String st : List.of("HELD_VERIFY", "HELD_QUALITY")) {
            for (ObjectNode o : store.listByStatus(tenant, "observation", st)) {
                if ("TEST".equals(o.path("mode").asString())) continue;
                if (station != null && !station.isBlank() && !station.equals(o.at("/context/station").asString()) && !station.equals(o.at("/context/bed").asString())) continue;
                out.add(o);
            }
        }
        return out;
    }

    public ObjectNode accept(String tenant, String observationId, Map<String, BigDecimal> corrections, String user) {
        PipelineResult r = pipeline.releaseHeld(tenant, observationId, corrections, "user:" + user);
        audit.record(tenant, "user:" + user, "capture.accept", "observation/" + observationId, Map.of("corrected", corrections != null && !corrections.isEmpty()));
        return (ObjectNode) Json.tree(r.summary());
    }

    public ObjectNode reject(String tenant, String observationId, String reason, String user) {
        ObjectNode o = store.require(tenant, "observation", observationId);
        o.put("status", "REJECTED_BY_CLINICIAN").put("rejectedBy", user).put("rejectReason", reason == null ? "" : reason);
        store.put(tenant, "observation", observationId, o, "REJECTED", o.path("configId").asString());
        audit.record(tenant, "user:" + user, "capture.reject", "observation/" + observationId, Map.of("reason", reason == null ? "" : reason));
        return o;
    }

    public List<ObjectNode> instances(String tenant, String templateId, String subject) {
        return store.listByRef(tenant, "template-instance", templateId).stream()
                .filter(i -> subject == null || subject.isBlank() || subject.equals(i.path("subject").asString())).toList().reversed();
    }
}
