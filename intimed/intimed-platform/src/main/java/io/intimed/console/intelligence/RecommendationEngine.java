package io.intimed.console.intelligence;

import io.intimed.catalogue.CatalogueService;
import io.intimed.clinical.PlatformCategories;
import io.intimed.intelligence.AutoMapper;
import io.intimed.common.Hashing;
import io.intimed.common.Json;
import io.intimed.console.OptionPacks;
import io.intimed.engine.driver.DriverPackage;
import io.intimed.engine.driver.DriverRegistry;
import io.intimed.security.endpoint.EndpointService;
import io.intimed.store.DocumentStore;
import io.intimed.templates.TemplateRegistry;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Configuration intelligence (IM-E03-F02 / IM-E03-BL-002, architecture §6): for every wizard step it proposes values
 * from three ordered knowledge sources – driver package defaults → tenant context rules → de-identified knowledge base –
 * with the rule id (RR-01…RR-18), the 'why', a confidence and the tenant-policy outcome. RR-17 infers the healthcare-platform
 * category from the bound templates' settings (recorded on the configuration, used by XT-17 and the validation studio);
 * RR-18 proposes per-field bindings from the auto-mapper for templates without a driver reference binding (imported /
 * host templates) as REVIEW-level recommendations with their confidence – never accepted in bulk below 0.8 (IM-E12-BL-009). Recommendations are records with
 * status PROPOSED; nothing is applied until an administrator accepts, adjusts or overrides (and never automatically
 * in UAT/PROD – SC-19). No language model decides any value (ADR-03).
 */
@Service
public class RecommendationEngine {
    private static final Map<String, Integer> LEVEL = Map.of("HIGH", 3, "MEDIUM", 2, "LOW", 1);
    private final DriverRegistry drivers;
    private final CatalogueService catalogue;
    private final OptionPacks packs;
    private final KnowledgeBase kb;
    private final TemplateRegistry templates;
    private final EndpointService endpoints;
    private final DocumentStore store;
    private final PlatformCategories categories;
    private final AutoMapper mapper;
    /** Template setting code → healthcare-platform category (RR-17). */
    static final Map<String, String> SETTING_CATEGORY = Map.of("ACUTE", "ACUTE_EHR", "OPD", "PRIMARY_CARE", "LAB", "LIS", "IMG", "IMAGING_CARDIO",
            "RPM", "RPM_HOME", "SPEC", "SPECIALIST", "OH", "OCC_HEALTH", "WELLNESS", "WELLNESS");
    public static final double RR18_AUTO_ACCEPT = 0.8;

    public RecommendationEngine(DriverRegistry drivers, CatalogueService catalogue, OptionPacks packs, KnowledgeBase kb, TemplateRegistry templates,
                                EndpointService endpoints, DocumentStore store, PlatformCategories categories, AutoMapper mapper) {
        this.categories = categories;
        this.mapper = mapper;
        this.drivers = drivers;
        this.catalogue = catalogue;
        this.packs = packs;
        this.kb = kb;
        this.templates = templates;
        this.endpoints = endpoints;
        this.store = store;
    }

    public ArrayNode recommend(String tenant, JsonNode cfg, JsonNode tenantSettings, int step) {
        ArrayNode out = Json.arr();
        String model = cfg.path("modelId").asString(null);
        DriverPackage d = cfg.hasNonNull("driver") ? drivers.find(tenant, cfg.get("driver").asString()).orElse(null) : null;
        switch (step) {
            case 1 -> step1(tenant, model, out);
            case 2 -> { if (d != null) step2(tenant, model, d, tenantSettings, out); }
            case 3 -> { if (d != null) step3(tenant, cfg, d, tenantSettings, out); }
            case 4 -> { if (d != null) step4(cfg, d, tenantSettings, out); }
            case 5 -> { if (d != null) step5(tenant, cfg, d, out); }
            case 6 -> { if (d != null) step6(d, out); }
            case 7 -> { if (d != null) step7(tenant, cfg, d, out); }
            case 8 -> { if (d != null) step8(tenant, cfg, d, out); }
            default -> throw new IllegalArgumentException("step must be 1–8");
        }
        for (JsonNode r : out) ((ObjectNode) r).put("step", step);
        return out;
    }

    private static ObjectNode rec(ArrayNode out, String param, JsonNode value, String source, String rule, String why, String conf) {
        return out.addObject().put("id", Hashing.id("rec")).put("parameter", param).put("source", source).put("rule", rule).put("why", why)
                .put("confidence", conf).put("confidenceScore", LEVEL.get(conf) / 3.0).put("policy", "ALLOWED").put("status", "PROPOSED")
                .put("createdAt", Instant.now().toString()).set("value", value);
    }

    void step1(String tenant, String model, ArrayNode out) {
        if (model == null) return;
        ObjectNode m = catalogue.model(tenant, model);
        List<DriverPackage> ds = new ArrayList<>(drivers.forModel(tenant, model));
        if (ds.isEmpty()) {
            rec(out, "driver", Json.tree(null), "CATALOGUE", "RR-12", "No certified driver for this model yet – build one with the driver SDK or request the model", "LOW").put("policy", "WARN");
        } else {
            ds.sort((a, b) -> Long.compare(kb.runs(b.ref(), b.options().get(0)), kb.runs(a.ref(), a.options().get(0))));
            DriverPackage best = ds.stream().filter(x -> "CERTIFIED".equals(x.channel())).findFirst().orElse(ds.get(0));
            long runs = kb.runs(best.ref(), best.options().get(0));
            rec(out, "driver", Json.tree(best.ref()), runs > 0 ? "KNOWLEDGE_BASE" : "DRIVER_DEFAULT", "RR-12",
                    best.channel() + " driver " + best.ref() + " (signature " + best.signatureStatus() + ", publisher " + best.publisher() + ")"
                            + (runs > 0 ? " – " + runs + " certified configurations in the knowledge base" : " – certified in the IntiMed device lab"),
                    ds.size() == 1 || runs > 0 ? "HIGH" : "MEDIUM");
            rec(out, "templates", Json.tree(new ArrayList<>(best.bindings().propertyNames())), "DRIVER_DEFAULT", "RR-10",
                    "Templates usually fed by this model (reference bindings in the driver package)", "HIGH");
        }
        JsonNode post = m.get("posture");
        if (!"ACCEPTABLE".equals(post.path("status").asString())) {
            rec(out, "posture", post.path("status"), "TENANT_POLICY", "SC-07", "Vendor posture " + post.path("status").asString()
                    + ": MDS2 " + post.path("mds2").asString() + ", SBOM " + post.path("sbom").asString() + " – record posture or request a security exception before certification", "HIGH").put("policy", "JUSTIFY");
        }
        if (!m.get("recalls").isEmpty()) rec(out, "recalls", Json.tree(m.get("recalls").size()), "CATALOGUE", "IM-E02-F02", "Open recalls/safety communications for this model – review before go-live", "HIGH").put("policy", "WARN");
    }

    void step2(String tenant, String model, DriverPackage d, JsonNode ts, ArrayNode out) {
        List<String> cand = new ArrayList<>(d.options());
        cand.removeIf(o -> !catalogue.optionAccepted(tenant, o));
        if (cand.isEmpty()) { rec(out, "option", Json.tree(null), "TENANT_POLICY", "ACCEPTANCE", "All options of this driver are blocked by tenant policy", "HIGH").put("policy", "BLOCKED"); return; }
        boolean gateway = ts.at("/networkProfile/gatewayInDeviceZone").asBoolean(false);
        String pick;
        String rule, why;
        if (cand.contains("P4") && gateway) {
            pick = "P4"; rule = "RR-01";
            why = "Site has a gateway in the medical-device zone: HL7 MLLP over mutual TLS end-to-end, lowest latency, no dependence on a vendor Windows PC";
        } else if (cand.contains("P3")) {
            pick = "P3"; rule = "RR-02";
            why = "BLE device at a fixed station: bond to the clinic gateway (not a tablet) with LE Secure Connections; approved-device list";
            rec(out, "params.bondingOwner", Json.tree("GATEWAY"), "SETTING_RULE", "RR-02", "Fixed station → gateway owns the bond", "HIGH");
        } else {
            pick = cand.stream().max((a, b) -> Integer.compare(score(a), score(b))).orElse(cand.get(0));
            rule = "RR-01";
            ObjectNode p = packs.get(pick);
            why = "Best security/reliability among supported options: " + p.get("name").asString() + " (security " + p.get("security").asString() + ", reliability "
                    + p.get("reliability").asString() + ", latency " + p.get("latency").asString() + ")";
        }
        rec(out, "option", Json.tree(pick), cand.size() == 1 ? "DRIVER_DEFAULT" : "TENANT_POLICY", rule, why, "HIGH");
    }

    private int score(String o) {
        ObjectNode p = packs.get(o);
        return LEVEL.get(p.get("security").asString()) * 2 + LEVEL.get(p.get("reliability").asString());
    }

    void step3(String tenant, JsonNode cfg, DriverPackage d, JsonNode ts, ArrayNode out) {
        String opt = cfg.path("option").asString(d.options().get(0));
        ObjectNode pack = packs.get(opt);
        rec(out, "prerequisites", pack.get("prerequisites"), "DRIVER_DEFAULT", "IM-E03-F01", "Option " + opt + " prerequisites (" + pack.get("via").asString() + ")", "HIGH");
        String type = switch (opt) { case "P2" -> "DEVICE_HOST"; case "P6" -> "VENDOR_CLOUD"; case "P8" -> "VENDOR_CLOUD"; case "P9", "P10" -> "MOBILE"; default -> "GATEWAY"; };
        ObjectNode best = null;
        for (ObjectNode e : endpoints.list(tenant)) {
            if (!"ACTIVE".equals(e.path("status").asString())) continue;
            if (e.path("type").asString().equals(type) || best == null && !"HARNESS".equals(e.path("type").asString())) best = e;
        }
        if (best != null) {
            ObjectNode r = rec(out, "endpointId", Json.tree(best.get("id").asString()), "TENANT_POLICY", "SC-01",
                    "Enrolled " + best.path("type").asString() + " '" + best.path("name").asString() + "' in zone " + best.path("zone").asString() + " carries this device's mTLS identity", "HIGH");
            if (best.hasNonNull("notAfter") && Instant.parse(best.get("notAfter").asString()).isBefore(Instant.now().plus(Duration.ofDays(30)))) {
                r.put("policy", "WARN").put("why", r.get("why").asString() + " – WARNING: certificate expires in < 30 days, rotate first (SC-14)");
            }
        } else {
            rec(out, "endpointId", Json.tree(null), "TENANT_POLICY", "SC-01", "No ACTIVE " + type + " endpoint – register one in the security console and enrol it with a one-time token", "HIGH").put("policy", "WARN");
        }
        rec(out, "timeSource", Json.tree(ts.at("/networkProfile/ntp").asString("pool.ntp.org")), "TENANT_POLICY", "SC-14", "Tenant time source; device clock offset > 5 min is corrected and flagged", "HIGH");
        rec(out, "firewall", Json.tree(ts.at("/networkProfile/deviceZone").asString("MDZ-1") + " → engine " + pack.get("via").asString()), "TENANT_POLICY", "SC-02",
                "Pre-filled conduit from the zoning reference design (deny-by-default)", "HIGH");
    }

    void step4(JsonNode cfg, DriverPackage d, JsonNode ts, ArrayNode out) {
        String opt = cfg.path("option").asString(d.options().get(0));
        JsonNode drvParams = d.transport(opt).path("parameters");
        JsonNode pol = ts.path("securityPolicy");
        for (JsonNode s : packs.get(opt).get("parameters")) {
            String n = s.get("name").asString();
            JsonNode recs = d.recommendations().get(n);
            if ("tls".equals(n)) { rec(out, "params.tls", Json.tree("MTLS"), "TENANT_POLICY", "RR-04", "Plaintext MLLP is blocked; MLLP over TLS with client certificate", "HIGH"); continue; }
            if ("tlsMin".equals(n)) { rec(out, "params.tlsMin", Json.tree(pol.path("tlsMin").asString("1.2")), "TENANT_POLICY", "RR-04", "Tenant minimum TLS version; cipher policy auto-filled", "HIGH"); continue; }
            if ("pairing".equals(n)) {
                rec(out, "params.pairing", Json.tree("LE_SECURE_CONNECTIONS_PASSKEY"), "TENANT_POLICY", "RR-03", "Device supports passkey: LE Secure Connections; Just-Works blocked by tenant policy", "HIGH");
                continue;
            }
            if ("secret".equals(s.get("type").asString())) {
                String ref = drvParams.path(n).asString("");
                if (ref.isEmpty() && !s.path("required").asBoolean()) continue;
                rec(out, "params." + n, Json.tree(ref.isEmpty() ? "secret://" + d.packageId() + "-" + n : ref), "TENANT_POLICY", "SC-05",
                        "Secrets are vault references only – store the value under this name in the secrets console", "HIGH");
                continue;
            }
            if (List.of("pollSeconds", "stabilitySeconds").contains(n)) {
                KnowledgeBase.Stat st = kb.best(d.ref(), opt, n);
                if (st != null && st.runs() >= 2) {
                    rec(out, "params." + n, Json.tree(num(st.value())), "KNOWLEDGE_BASE", "RR-13", String.format("Value used by %d certified configurations of this vendor software (success %.0f %%)", st.runs(), st.successRate() * 100), "MEDIUM");
                    continue;
                }
            }
            JsonNode v = drvParams.get(n);
            if (v == null || v.isNull() || v.asString("").isEmpty()) continue;
            String rule = "P1".equals(opt) && List.of("baud", "parity", "dataBits", "stopBits", "flowControl", "terminator").contains(n) ? "RR-05" : "RR-12";
            String why = recs != null && recs.has("why") ? recs.get("why").asString() : "Certified driver default (" + d.ref() + ")";
            rec(out, "params." + n, v, "DRIVER_DEFAULT", rule, why, "HIGH");
        }
        // driver-specific recommendations (e.g. temperature unit, auto-programming)
        d.recommendations().properties().forEach(e -> {
            if (List.of("association", "captureMode", "qualityGates", "intervalMinutes", "grading", "note", "verification").contains(e.getKey())) return;
            if (drvParams.has(e.getKey())) return;
            rec(out, "notes." + e.getKey(), e.getValue().path("value"), "DRIVER_DEFAULT", "RR-12", e.getValue().path("why").asString(), "MEDIUM");
        });
    }

    private static JsonNode num(String s) {
        try { return Json.tree(Integer.parseInt(s)); } catch (NumberFormatException e) { return Json.tree(s); }
    }

    void step5(String tenant, JsonNode cfg, DriverPackage d, ArrayNode out) {
        String mode = d.manifest().path("captureMode").asString("SPOT");
        String assoc = d.recommendations().path("association").path("value").asString("WORKLIST");
        String rule = switch (assoc) { case "ENCOUNTER_BED" -> "RR-07"; case "CONSENT" -> "RR-09"; default -> "RR-08"; };
        String why = switch (assoc) {
            case "ENCOUNTER_BED" -> "Continuous monitor: encounter–bed–device binding driven by ADT with transfer/discharge handling";
            case "CONSENT" -> "Home / RPM device: consent-bound enrolment with device-serial matching and weekly reconciliation";
            case "SESSION" -> d.restricted() ? "Restricted category: collector session only – no identifier matching" : "Case/session binding started by the clinician (OR, dialysis)";
            case "MATCH" -> "Vendor-typed identifier matching with suggestion queue (exact 0.97 / normalised 0.92 / fuzzy 0.85)";
            default -> "Spot-check instrument: worklist/order push with session fallback and badge/QR confirmation; unmatched queue at the station";
        };
        rec(out, "association.method", Json.tree(assoc), "SETTING_RULE", rule, why, "CONSENT".equals(assoc) ? "MEDIUM" : "HIGH");
        rec(out, "association.threshold", Json.tree(0.95), "SETTING_RULE", rule, "Auto-file at confidence ≥ 0.95; 0.60–0.95 goes to the unmatched queue with a suggestion", "HIGH");
        if (!"CONSENT".equals(assoc) && !"SESSION".equals(assoc)) rec(out, "association.sessionFallback", Json.tree(true), "SETTING_RULE", "RR-08", "Session fallback when no worklist entry is open", "HIGH");
        if ("CONTINUOUS".equals(mode)) {
            int iv = d.recommendations().path("intervalMinutes").path("value").asInt(5);
            rec(out, "intervalMinutes", Json.tree(iv), "SETTING_RULE", "RR-07", d.recommendations().path("intervalMinutes").path("why").asString("Interval posting per template policy (1–15 min)"), "HIGH");
        }
        ArrayNode bindings = Json.arr();
        StringBuilder notes = new StringBuilder();
        d.bindings().properties().forEach(e -> {
            bindings.addObject().put("templateId", e.getKey()).set("fields", e.getValue().get("fields"));
            JsonNode tpl = templates.find(tenant, e.getKey()).orElse(null);
            if (tpl == null) return;
            java.util.Set<String> bound = new java.util.HashSet<>(e.getValue().get("fields").propertyNames());
            for (JsonNode f : TemplateRegistry.fields(tpl)) {
                if (f.path("mandatory").asBoolean() && !bound.contains(f.get("id").asString())) notes.append(e.getKey()).append(": mandatory '").append(f.path("label").asString()).append("' unbound; ");
                if (bound.contains(f.get("id").asString()) && f.path("code").has("local")) notes.append(e.getKey()).append(".").append(f.get("id").asString()).append(" has no LOINC (local ").append(f.at("/code/local").asString()).append("); ");
            }
        });
        ObjectNode b = rec(out, "bindings", bindings, "DRIVER_DEFAULT", "RR-10", "Pre-filled from the reference binding library" + (notes.length() > 0 ? " – review: " + notes : ""), "HIGH");
        if (notes.toString().contains("unbound")) b.put("policy", "WARN");
        for (JsonNode s : d.semantics()) {
            if ("text".equals(s.path("type").asString())) continue;
            for (var e : d.bindings().properties()) {
                JsonNode tpl = templates.find(tenant, e.getKey()).orElse(null);
                if (tpl == null) continue;
                e.getValue().get("fields").properties().forEach(f -> {
                    if (!f.getValue().asString().equals(s.get("key").asString())) return;
                    JsonNode tf = TemplateRegistry.field(tpl, f.getKey());
                    String unitFromDevice = deviceUnit(d, s.get("key").asString());
                    if (tf != null && unitFromDevice != null && !unitFromDevice.equals(tf.path("unit").asString(unitFromDevice))) {
                        rec(out, "conversions." + s.get("key").asString(), Json.tree(unitFromDevice + " → " + tf.path("unit").asString()), "TEMPLATE", "RR-06",
                                "Device sends " + unitFromDevice + ", template " + e.getKey() + " expects " + tf.path("unit").asString() + ": converted in normalisation (UCUM), both values shown in preview, unconverted values blocked", "HIGH");
                    }
                });
            }
        }
        rr17(tenant, cfg, d, out);
        rr18(tenant, cfg, d, out);
        JsonNode gates = d.recommendations().path("qualityGates").path("value");
        rec(out, "qualityGates", gates.isMissingNode() ? Json.obj() : gates, "DRIVER_DEFAULT", "RR-11",
                gates.has("grading") ? "Family guideline grading " + gates.get("grading").asString() + " and lockouts enabled" : "Driver quality-gate defaults (plausibility, artefact, verify rules)", "HIGH");
        if (gates.path("calibrationLockout").asBoolean(false)) rec(out, "calibration.intervalDays", Json.tree(gates.path("grading").asString("").startsWith("ATS") ? 1 : 365), "DRIVER_DEFAULT", "RR-11",
                "Calibration check interval for this family; results are locked out when overdue", "HIGH");
        if (d.options().contains("P6") || d.options().contains("P8")) {
            String need = d.transport(d.options().get(0)).path("parameters").path("scopes").asString("");
            rec(out, "params.scopes", Json.tree(need), "TENANT_POLICY", "RR-15", "Minimum scopes for the bound templates" + ("CONSENT".equals(assoc) ? "; consent is required for home devices" : ""), "HIGH");
        }
    }

    /** Templates the configuration feeds: its bindings, else the driver's reference bindings. */
    private static List<String> boundTemplates(JsonNode cfg, DriverPackage d) {
        List<String> t = new ArrayList<>();
        for (JsonNode b : cfg.path("bindings")) if (b.hasNonNull("templateId") && !t.contains(b.get("templateId").asString())) t.add(b.get("templateId").asString());
        if (t.isEmpty()) t.addAll(d.bindings().propertyNames());
        return t;
    }

    /** RR-17: healthcare-platform category inferred from the settings of the bound templates. */
    void rr17(String tenant, JsonNode cfg, DriverPackage d, ArrayNode out) {
        List<String> tpls = boundTemplates(cfg, d);
        if (tpls.isEmpty()) return;
        String first = tpls.get(0);
        JsonNode tpl = templates.find(tenant, first).orElse(null);
        if (tpl == null) return;
        List<String> ofTemplate = categories.forTemplate(first);
        String pick = null, conf = "LOW", why;
        List<String> settings = new ArrayList<>();
        tpl.path("settings").forEach(s -> settings.add(s.asString()));
        for (String s : settings) {
            String c = SETTING_CATEGORY.get(s);
            if (c != null && ofTemplate.contains(c)) { pick = c; conf = "HIGH"; break; }
        }
        if (pick == null) for (String s : settings) { String c = SETTING_CATEGORY.get(s); if (c != null) { pick = c; conf = "MEDIUM"; break; } }
        if (pick == null && !ofTemplate.isEmpty()) { pick = ofTemplate.get(0); conf = "MEDIUM"; }
        if (pick == null) pick = "ACUTE_EHR";
        why = "Template " + first + " settings " + settings + " → category " + pick + " (" + categories.require(pick).path("name").asString() + "): rule pack "
                + categories.require(pick).path("rules") + ", interpretation " + categories.require(pick).path("interpretation").asString()
                + " – recorded on the configuration and used by XT-17 and the validation studio";
        ObjectNode r = rec(out, "platformCategory", Json.tree(pick), "TEMPLATE", "RR-17", "LOW".equals(conf) ? why + " – no setting maps to a category: confirm with the host owner" : why, conf);
        if ("LOW".equals(conf)) r.put("policy", "WARN");
    }

    /**
     * RR-18: auto-map suggestions (AutoMapper) for templates the configuration feeds or the tenant imported that have no
     * reference binding in the driver package. One recommendation per field, REVIEW level with the numeric confidence;
     * 'accept all' skips suggestions below {@link #RR18_AUTO_ACCEPT} – those need a one-click human decision.
     */
    void rr18(String tenant, JsonNode cfg, DriverPackage d, ArrayNode out) {
        java.util.Set<String> withRef = new java.util.HashSet<>(d.bindings().propertyNames());
        java.util.LinkedHashSet<String> cand = new java.util.LinkedHashSet<>();
        for (JsonNode b : cfg.path("bindings")) if (b.hasNonNull("templateId") && !withRef.contains(b.get("templateId").asString())) cand.add(b.get("templateId").asString());
        for (ObjectNode t : templates.all(tenant)) if (!"REFERENCE".equals(t.path("origin").asString("REFERENCE")) && !withRef.contains(t.get("id").asString())) cand.add(t.get("id").asString());
        for (String tid : cand) {
            JsonNode tpl = templates.find(tenant, tid).orElse(null);
            if (tpl == null) continue;
            ObjectNode s = mapper.suggest(tpl, d);
            for (JsonNode sg : s.path("suggestions")) {
                if (!sg.hasNonNull("key")) continue;
                double c = sg.path("confidence").asDouble();
                boolean auto = c >= RR18_AUTO_ACCEPT;
                ObjectNode r = rec(out, "bindings." + tid + "." + sg.get("field").asString(), Json.tree(sg.get("key").asString()), "AUTO_MAPPER", "RR-18",
                        "Auto-map " + tpl.path("origin").asString("") + " template " + tid + " field '" + sg.path("label").asString() + "' ← driver key " + sg.get("key").asString()
                                + " (confidence " + c + "): " + sg.path("why").asString() + (auto ? "" : " – REVIEW: below 0.8, never auto-accepted"),
                        c >= RR18_AUTO_ACCEPT ? "HIGH" : c >= 0.65 ? "MEDIUM" : "LOW");
                r.put("confidenceScore", c).put("level", "REVIEW").put("autoAcceptEligible", auto).put("templateId", tid).put("field", sg.get("field").asString());
                if (!auto) r.put("policy", "WARN");
            }
        }
    }

    private static String deviceUnit(DriverPackage d, String key) {
        for (JsonNode f : d.grammar().path("fields")) if (key.equals(f.path("key").asString()) && f.has("unit")) return f.get("unit").asString();
        return null;
    }

    void step6(DriverPackage d, ArrayNode out) {
        rec(out, "harness.negativeCases", d.tests().path("negative"), "DRIVER_DEFAULT", "RR-14", "Family failure modes added to the exchange test plan automatically (duplicates, truncated frames, clock skew, out-of-range, wrong subject)", "HIGH");
        rec(out, "harness.mode", Json.tree("SIMULATOR"), "SETTING_RULE", "IM-E04-F01", "LOCAL environment: family simulator golden session; LIVE mode with a real device in UAT", "HIGH");
    }

    void step7(String tenant, JsonNode cfg, DriverPackage d, ArrayNode out) {
        boolean newFamily = store.listByStatus(tenant, "config", "PUBLISHED").stream().noneMatch(c -> d.family().equals(c.path("family").asString()));
        rec(out, "shadowDays", Json.tree(newFamily ? 14 : 7), "SETTING_RULE", "RR-16", newFamily ? "New device family for this tenant: 14-day shadow run before clinical sign-off" : "Known family: 7-day shadow run", "MEDIUM");
        JsonNode run = cfg.path("lastRun");
        String summary = "Configuration " + cfg.path("name").asString() + " (" + d.ref() + ", " + cfg.path("option").asString() + ") – exchange test "
                + run.path("status").asString("NOT RUN") + (run.has("passed") ? " (" + run.get("passed").asInt() + "/" + run.get("total").asInt() + " checks)" : "")
                + ". Residual risks: association by " + cfg.at("/association/method").asString() + "; device remains the measurement system; alarms flagged, not interpreted.";
        rec(out, "certificationSummary", Json.tree(summary), "DRIVER_DEFAULT", "IM-E03-F08", "Pre-written from the evidence – edit before approval", "MEDIUM");
    }

    void step8(String tenant, JsonNode cfg, DriverPackage d, ArrayNode out) {
        long p95 = cfg.path("lastRun").path("latencyP95Ms").asLong(500);
        ObjectNode mon = Json.obj().put("latencyP95Ms", Math.max(3000, p95 * 3)).put("errorRatePct", 1.0).put("unmatchedRatePct", 2.0).put("silenceMinutes", "CONTINUOUS".equals(d.manifest().path("captureMode").asString()) ? 10 : 1440);
        rec(out, "monitoring", mon, "SETTING_RULE", "RR-16", "Thresholds from the fleet baseline of this family (harness p95 " + p95 + " ms × 3, spot ≤ 3 s)", "MEDIUM");
        List<String> clones = catalogue.search(tenant, null, catalogue.requireModel(cfg.path("modelId").asString()).get("family").asString(), null, null, null, 50).stream()
                .filter(m -> !m.get("id").asString().equals(cfg.path("modelId").asString()) && m.get("certifiedDrivers").asInt() > 0).map(m -> m.get("id").asString()).limit(5).toList();
        rec(out, "cloneTargets", Json.tree(clones), "CATALOGUE", "IM-E03-F01", "Same family with certified drivers – clone this configuration as a site template", "LOW");
    }
}
