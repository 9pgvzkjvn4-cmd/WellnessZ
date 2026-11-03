package io.intimed.api;

import io.intimed.common.ApiException;
import io.intimed.common.Json;
import io.intimed.engine.pipeline.Envelope;
import io.intimed.engine.pipeline.Pipeline;
import io.intimed.engine.pipeline.PipelineResult;
import io.intimed.security.auth.Current;
import io.intimed.store.DocumentStore;
import io.intimed.templates.TemplateAdapters;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.util.List;

/**
 * FHIR R4 API (IM-E07-F01 / IM-E07-BL-001, EP-2, P8 inbound): CapabilityStatement, Observation search/read (filed
 * observations, tenant from the token, scope system/Observation.read), Device, Observation create for P8 devices
 * (scope system/Observation.write) and rest-hook Subscriptions mapped onto signed webhooks. Object-level checks:
 * every read is scoped to the token's tenant; observations still held, unmatched or rejected are never exposed.
 */
@RestController
@RequestMapping(value = "/fhir/r4", produces = "application/fhir+json")
public class FhirController {
    private final DocumentStore store;
    private final Pipeline pipeline;
    private final WebhookService webhooks;

    public FhirController(DocumentStore store, Pipeline pipeline, WebhookService webhooks) {
        this.store = store;
        this.pipeline = pipeline;
        this.webhooks = webhooks;
    }

    @GetMapping("/metadata")
    public ObjectNode metadata() {
        ObjectNode c = Json.obj().put("resourceType", "CapabilityStatement").put("status", "active").put("date", "2026-11-01").put("kind", "instance")
                .put("fhirVersion", "4.0.1").put("software", "IntiMed 2.0.0-local");
        c.putArray("format").add("application/fhir+json");
        ObjectNode rest = c.putArray("rest").addObject().put("mode", "server");
        rest.putObject("security").put("description", "OAuth2 client credentials (ES256 JWT), SMART backend-services scopes system/<Resource>.<read|write>; tenant from token")
                .putArray("service").addObject().putArray("coding").addObject().put("system", "http://terminology.hl7.org/CodeSystem/restful-security-service").put("code", "SMART-on-FHIR");
        ArrayNode res = rest.putArray("resource");
        res.addObject().put("type", "Observation").putArray("interaction").add(Json.obj().put("code", "read")).add(Json.obj().put("code", "search-type")).add(Json.obj().put("code", "create"));
        ((ObjectNode) res.get(0)).putArray("searchParam").add(Json.obj().put("name", "subject").put("type", "reference")).add(Json.obj().put("name", "code").put("type", "token"))
                .add(Json.obj().put("name", "_lastUpdated").put("type", "date"));
        res.addObject().put("type", "Device").putArray("interaction").add(Json.obj().put("code", "search-type"));
        res.addObject().put("type", "Subscription").putArray("interaction").add(Json.obj().put("code", "create"));
        return c;
    }

    @GetMapping("/Observation")
    public ObjectNode search(@RequestParam(value = "subject", required = false) String subject, @RequestParam(value = "code", required = false) String code,
                             @RequestParam(value = "_count", defaultValue = "50") int count) {
        Current.requireScope("system/Observation.read");
        String tenant = Current.tenant();
        ObjectNode b = Json.obj().put("resourceType", "Bundle").put("type", "searchset");
        ArrayNode entries = b.putArray("entry");
        List<ObjectNode> filed = store.listByStatus(tenant, "observation", "FILED");
        int n = 0;
        for (int i = filed.size() - 1; i >= 0 && n < Math.min(count, 200); i--) {
            ObjectNode o = filed.get(i);
            if ("TEST".equals(o.path("mode").asString())) continue;
            if (subject != null && !subject.replace("Patient/", "").equals(o.at("/subject/ref").asString())) continue;
            for (JsonNode m : o.path("measurements")) {
                if (code != null && !code.endsWith("|" + m.at("/code/loinc").asString("-")) && !code.equals(m.at("/code/loinc").asString("-"))) continue;
                entries.addObject().set("resource", toFhir(o, m));
                n++;
            }
        }
        b.put("total", entries.size());
        return b;
    }

    @GetMapping("/Observation/{id}")
    public ObjectNode read(@PathVariable String id) {
        Current.requireScope("system/Observation.read");
        String[] p = id.split("--", 2);
        ObjectNode o = store.get(Current.tenant(), "observation", p[0]).orElseThrow(() -> ApiException.notFound("Observation"));
        if (!"FILED".equals(o.path("status").asString()) || "TEST".equals(o.path("mode").asString())) throw ApiException.notFound("Observation");
        for (JsonNode m : o.path("measurements")) if (p.length < 2 || m.path("key").asString().equals(p[1])) return toFhir(o, m);
        throw ApiException.notFound("Observation");
    }

    static ObjectNode toFhir(JsonNode o, JsonNode m) {
        ObjectNode r = Json.obj().put("resourceType", "Observation").put("id", o.get("id").asString() + "--" + m.path("key").asString()).put("status", "final");
        ArrayNode coding = r.putObject("code").putArray("coding");
        if (m.at("/code/loinc").isString()) coding.addObject().put("system", "http://loinc.org").put("code", m.at("/code/loinc").asString()).put("display", m.path("display").asString());
        if (m.at("/code/mdc").isString()) coding.addObject().put("system", TemplateAdapters.MDC).put("code", m.at("/code/mdc").asString());
        if (m.at("/code/local").isString()) coding.addObject().put("system", "urn:intimed:local").put("code", m.at("/code/local").asString());
        r.putObject("subject").putObject("identifier").put("value", o.at("/subject/ref").asString());
        r.put("effectiveDateTime", o.path("effectiveTime").asString());
        r.putObject("device").putObject("identifier").put("value", o.path("deviceSerial").asString());
        if (m.path("value").isNumber()) r.putObject("valueQuantity").put("value", m.get("value").decimalValue()).put("unit", m.path("unit").asString()).put("system", "http://unitsofmeasure.org").put("code", m.path("unit").asString());
        else r.put("valueString", m.path("value").asString());
        r.putObject("meta").putArray("tag").addObject().put("system", "urn:intimed:provenance").put("code", o.at("/driver/ref").asString());
        return r;
    }

    @GetMapping("/Device")
    public ObjectNode devices() {
        Current.requireScope("system/Device.read");
        ObjectNode b = Json.obj().put("resourceType", "Bundle").put("type", "searchset");
        ArrayNode e = b.putArray("entry");
        for (ObjectNode c : store.listByStatus(Current.tenant(), "config", "PUBLISHED")) {
            ObjectNode d = Json.obj().put("resourceType", "Device").put("id", c.get("id").asString()).put("status", "active").put("manufacturer", c.path("manufacturer").asString());
            d.putArray("deviceName").addObject().put("name", c.path("model").asString()).put("type", "model-name");
            d.putObject("location").put("display", c.at("/site/site").asString("") + " / " + c.at("/site/station").asString(""));
            e.addObject().set("resource", d);
        }
        b.put("total", e.size());
        return b;
    }

    /** P8 inbound: device / aggregator posts Observation or Bundle; configuration resolved from the device identifier. */
    @PostMapping(value = "/Observation", consumes = {"application/fhir+json", "application/json"})
    public ResponseEntity<Object> create(@RequestBody String body) {
        Current.requireScope("system/Observation.write");
        String tenant = Current.tenant();
        String serial = body.replaceAll("(?s).*\"device\"\\s*:\\s*\\{\\s*\"identifier\"\\s*:\\s*\\{\\s*\"value\"\\s*:\\s*\"([^\"]+)\".*", "$1");
        ObjectNode cfg = store.listByStatus(tenant, "config", "PUBLISHED").stream().filter(c -> "P8".equals(c.path("option").asString()))
                .filter(c -> !c.path("expectedSerials").isArray() || c.get("expectedSerials").isEmpty() || c.get("expectedSerials").toString().contains("\"" + serial + "\""))
                .findFirst().orElseThrow(() -> ApiException.unprocessable("no-configuration", "no published P8 configuration for this device"));
        PipelineResult r = pipeline.process(new Envelope(tenant, null, cfg.get("id").asString(), "P8", body, "LIVE", null, null, "fhir-api", Instant.now()));
        ObjectNode oo = Json.obj().put("resourceType", "OperationOutcome");
        oo.putArray("issue").addObject().put("severity", "DLQ".equals(r.status) ? "error" : "information").put("code", "informational").put("diagnostics", r.status);
        return ResponseEntity.status("DLQ".equals(r.status) || "REJECTED".equals(r.status) ? 422 : 201).body(oo);
    }

    /** rest-hook Subscription → signed webhook subscription (EP-2 → EP-4). */
    @PostMapping(value = "/Subscription", consumes = {"application/fhir+json", "application/json"})
    public ResponseEntity<Object> subscribe(@RequestBody JsonNode s) {
        Current.requireScope("system/Subscription.write");
        if (!"rest-hook".equals(s.at("/channel/type").asString(""))) throw ApiException.bad("unsupported-channel", "only rest-hook channels are supported");
        ObjectNode sub = webhooks.create(Current.tenant(), s.at("/channel/endpoint").asString(), List.of("observation.posted"), Current.actor());
        ObjectNode out = (ObjectNode) s.deepCopy();
        out.put("id", sub.get("id").asString()).put("status", "active");
        return ResponseEntity.status(201).body(out);
    }
}
