package io.intimed.quality;

import io.intimed.common.Json;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Product quality views (IM-E10 local subset): epics, HLRs, features, the 117 backlog items with delivery status in this
 * local-scope release (traceability from epic/HLR to code and tests – IEC 62304 §5.1.1 style), the sprint plan with
 * capacity (story points planned vs delivered locally), and the regulatory classification record with the excluded
 * functions that stay behind feature flags (MDDS boundary, ADR-11).
 */
@RestController
@RequestMapping("/api/quality")
public class QualityController {
    private final JsonNode backlog = Json.resource("seed/backlog.json");
    private final JsonNode status = Json.resource("seed/delivery-status.json");
    private final JsonNode epics = Json.resource("seed/epics.json");
    private final JsonNode sprints = Json.resource("seed/sprints.json");
    private final JsonNode hlrs = Json.resource("seed/hlrs.json");
    private final JsonNode features = Json.resource("seed/features.json");

    @GetMapping("/backlog")
    public ArrayNode backlog() {
        ArrayNode out = Json.arr();
        for (JsonNode b : backlog) {
            ObjectNode r = (ObjectNode) b.deepCopy();
            JsonNode s = status.path("items").path(b.get("id").asString());
            r.setAll((ObjectNode) (s.isObject() ? s : Json.obj().put("status", "DEFERRED")));
            out.add(r);
        }
        return out;
    }

    @GetMapping("/summary")
    public Map<String, Object> summary() {
        Map<String, int[]> byEpic = new LinkedHashMap<>();
        Map<String, int[]> bySprint = new LinkedHashMap<>();
        for (JsonNode b : backlog()) {
            String st = b.path("status").asString();
            int sp = b.path("sp").asInt();
            int idx = switch (st) { case "DELIVERED" -> 0; case "PARTIAL" -> 1; default -> 2; };
            int[] e = byEpic.computeIfAbsent(b.get("id").asString().substring(0, 6), k -> new int[6]);
            e[idx]++; e[3 + idx] += sp;
            int[] s = bySprint.computeIfAbsent(b.get("sprint").asString(), k -> new int[6]);
            s[idx]++; s[3 + idx] += sp;
        }
        return Map.of("byEpic", byEpic, "bySprint", bySprint, "legend", "[delivered, partial, deferred items, delivered SP, partial SP, deferred SP]",
                "increments", status.path("increments"), "capacity", status.path("capacity"));
    }

    @GetMapping("/epics") public JsonNode epics() { return epics; }
    @GetMapping("/sprints") public JsonNode sprints() { return sprints; }
    @GetMapping("/hlrs") public JsonNode hlrs() { return hlrs; }
    @GetMapping("/features") public JsonNode features() { return features; }

    @GetMapping("/classification")
    public Map<String, Object> classification() {
        return Map.of("product", "IntiMed 2.1.0", "intendedUse", "Transfer, storage, format conversion and display of medical device data; configuration management and verification of device integrations",
                "fdaPosition", "Medical Device Data System functions (21 CFR 880.6310) – enforcement discretion; not active patient monitoring",
                "iec62304Class", "Class A software system with Class B items for association and routing (wrong-patient / wrong-value hazards mitigated by verification, thresholds and the publish gate)",
                "excludedFunctionsFeatureFlags", Map.of("alarmManagement", false, "clinicalInterpretation", false, "closedLoopControl", false, "activePatientMonitoringNotification", false),
                "labelling", "Alarms are flagged as ALARM_STATE_FLAGGED and never interpreted; device interpretations (e.g. ECG statements) are marked unverified and require physician sign-off",
                "referenceRangeFlagging", "IntiMed 2.0: numeric results are annotated with a cited, age/sex/context-partitioned reference interval and an HL7 interpretation flag (N/L/H/LL/HH) – informational display analogous to laboratory report flags; no diagnosis, no alarm, no treatment recommendation. Switchable per configuration (params.referenceRangeFlagging=false). Default intervals must be replaced by locally verified intervals (CLSI EP28-A3c) before clinical use",
                "regulatoryImpact", "Flagging may move the product beyond MDDS enforcement discretion in some jurisdictions (FDA CDS guidance 2022 criteria; EU MDR Rule 11). A documented regulatory assessment is required before enabling flags in production; the validation studio and cohort simulator are non-clinical engineering tools",
                "standards", new String[]{"IEC 62304", "ISO 14971", "IEC 62366-1", "IEC 82304-1", "IEC 81001-5-1", "IEC 80001-1", "ISO 13485-aligned QMS"});
    }
}
