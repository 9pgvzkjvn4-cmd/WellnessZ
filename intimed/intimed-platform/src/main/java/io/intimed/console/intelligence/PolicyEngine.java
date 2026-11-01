package io.intimed.console.intelligence;

import io.intimed.catalogue.CatalogueService;
import io.intimed.common.Json;
import io.intimed.console.OptionPacks;
import io.intimed.engine.driver.DriverPackage;
import io.intimed.engine.terminology.Terminology;
import io.intimed.security.secrets.SecretScanner;
import io.intimed.templates.TemplateRegistry;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.Map;

/**
 * Conflict and risk detection with tenant policy (IM-E03-F03 / IM-E03-BL-005, SC-19 guardrails): outcomes BLOCK, JUSTIFY
 * (allowed with a recorded justification), WARN. Tenant policies block insecure values regardless of any
 * recommendation: plaintext MLLP, TLS &lt; 1.2, Just-Works pairing, broad cloud scopes, inline secrets, unconverted units,
 * restricted-category matching, home devices without consent, unacceptable vendor posture.
 */
@Component
public class PolicyEngine {
    private final OptionPacks packs;
    private final TemplateRegistry templates;
    private final Terminology terms;
    private final CatalogueService catalogue;

    public PolicyEngine(OptionPacks packs, TemplateRegistry templates, Terminology terms, CatalogueService catalogue) {
        this.packs = packs;
        this.templates = templates;
        this.terms = terms;
        this.catalogue = catalogue;
    }

    public ArrayNode validate(String tenant, JsonNode cfg, DriverPackage d, JsonNode tenantSettings) {
        ArrayNode v = Json.arr();
        JsonNode pol = tenantSettings.path("securityPolicy");
        String opt = cfg.path("option").asString("");
        JsonNode p = cfg.path("params");
        if (d != null && !opt.isEmpty()) {
            if (!d.options().contains(opt)) add(v, "BLOCK", "option", "RR-02", "Driver " + d.ref() + " does not support " + opt);
            if (!catalogue.optionAccepted(tenant, opt)) add(v, "BLOCK", "option", "ACCEPTANCE", "Option " + opt + " is blocked by the tenant acceptance policy");
            for (String s : packs.validate(opt, p)) add(v, s.contains("vault") ? "BLOCK" : "WARN", "params", "SCHEMA", s);
        }
        if ("PLAINTEXT".equals(p.path("tls").asString()) && pol.path("blockPlaintextMllp").asBoolean(true)) add(v, "BLOCK", "params.tls", "RR-04", "Plaintext MLLP is blocked – use MLLP over TLS 1.2+ with client certificate");
        if (p.has("tlsMin") && !"1.2".equals(p.get("tlsMin").asString()) && !"1.3".equals(p.get("tlsMin").asString())) add(v, "BLOCK", "params.tlsMin", "RR-04", "TLS below 1.2 is blocked by tenant policy");
        if ("JUST_WORKS".equals(p.path("pairing").asString()) && pol.path("blockJustWorks").asBoolean(true)) {
            boolean passkey = d != null && d.transport("P3").path("parameters").path("pairing").asString("").startsWith("LE_SECURE");
            add(v, passkey ? "BLOCK" : "JUSTIFY", "params.pairing", "RR-03", passkey ? "Just-Works pairing blocked: this device supports LE Secure Connections with passkey"
                    : "Just-Works pairing requires a justification (device lacks passkey/OOB)");
        }
        if (p.has("scopes") && d != null) {
            String need = d.transport(opt).path("parameters").path("scopes").asString("");
            String have = p.get("scopes").asString("");
            if (have.contains("*") || have.split("\\s+").length > Math.max(1, need.split("\\s+").length)) {
                add(v, pol.path("blockBroadCloudScopes").asBoolean(true) ? "JUSTIFY" : "WARN", "params.scopes", "RR-15", "Scopes broader than the bound templates need (" + need + ")");
            }
        }
        if (!SecretScanner.scan(Json.write(cfg)).isEmpty()) add(v, "BLOCK", "params", "SC-05", "Configuration contains an inline secret – store it in the vault and reference secret://…");
        String assoc = cfg.at("/association/method").asString("");
        if (d != null && d.restricted() && ("MATCH".equals(assoc) || "BADGE".equals(assoc))) add(v, "BLOCK", "association.method", "RR-08", "Restricted category (drug & alcohol): identifier matching/badge association not allowed");
        if ("P6".equals(opt) && cfg.at("/site/setting").asString("").equals("HOME") && !"CONSENT".equals(assoc)) add(v, "BLOCK", "association.method", "RR-09", "Home / RPM devices require consent-bound enrolment");
        double thr = cfg.at("/association/threshold").asDouble(0.95);
        if (thr < 0.9) add(v, "JUSTIFY", "association.threshold", "RR-08", "Auto-file threshold below 0.90 raises wrong-patient risk");
        // bindings: units and mandatory fields
        if (d != null) {
            for (JsonNode b : cfg.path("bindings")) {
                JsonNode tpl = templates.find(tenant, b.path("templateId").asString()).orElse(null);
                if (tpl == null) { add(v, "BLOCK", "bindings", "RR-10", "Unknown template " + b.path("templateId").asString()); continue; }
                java.util.Set<String> bound = new java.util.HashSet<>();
                b.path("fields").properties().forEach(e -> {
                    bound.add(e.getKey());
                    JsonNode f = TemplateRegistry.field(tpl, e.getKey());
                    JsonNode s = d.semantic(e.getValue().asString());
                    if (f == null || s == null) { add(v, "BLOCK", "bindings", "RR-10", e.getKey() + " ← " + e.getValue().asString() + " is not a valid binding"); return; }
                    String fu = f.path("unit").asString(null), su = s.path("ucum").asString(null);
                    if (fu != null && su != null && !fu.equals(su)) {
                        Map<String, Double> fac = new java.util.HashMap<>();
                        s.path("factors").properties().forEach(x -> fac.put(x.getKey(), x.getValue().asDouble()));
                        if (terms.convert(java.math.BigDecimal.ONE, su, fu, fac).isEmpty()) add(v, "BLOCK", "bindings." + e.getKey(), "RR-06", "No UCUM conversion " + su + " → " + fu + ": unconverted values are never posted");
                    }
                    if ("text".equals(s.path("type").asString()) != "text".equals(f.path("type").asString())) add(v, "WARN", "bindings." + e.getKey(), "RR-10", "Data type differs between driver and template field");
                });
                for (JsonNode f : TemplateRegistry.fields(tpl)) {
                    if (f.path("mandatory").asBoolean() && !bound.contains(f.get("id").asString())) {
                        add(v, "WARN", "bindings." + tpl.get("id").asString(), "RR-10", "Mandatory field '" + f.path("label").asString() + "' is not bound (XT-07 will fail unless another device fills it)");
                    }
                }
            }
        }
        // posture gate
        String model = cfg.path("modelId").asString(null);
        if (model != null && !"OFF".equals(pol.path("postureGate").asString("BLOCK"))) {
            JsonNode post = catalogue.posture(tenant, model);
            boolean exception = cfg.path("postureException").path("approved").asBoolean(false);
            if (!"ACCEPTABLE".equals(post.path("status").asString()) && !exception) {
                add(v, "BLOCK".equals(pol.path("postureGate").asString("BLOCK")) ? "JUSTIFY" : "WARN", "posture", "SC-07",
                        "Vendor posture " + post.path("status").asString() + " (MDS2 " + post.path("mds2").asString() + ", SBOM " + post.path("sbom").asString() + ") – certification needs a security-approved exception");
            }
        }
        return v;
    }

    public static boolean blocking(ArrayNode v, JsonNode justifications) {
        for (JsonNode x : v) {
            if ("BLOCK".equals(x.get("severity").asString())) return true;
            if ("JUSTIFY".equals(x.get("severity").asString()) && (justifications == null || !justifications.has(x.get("rule").asString() + ":" + x.get("parameter").asString()))) return true;
        }
        return false;
    }

    private static void add(ArrayNode v, String sev, String param, String rule, String msg) {
        v.addObject().put("severity", sev).put("parameter", param).put("rule", rule).put("message", msg);
    }
}
