package com.wellnessz.e32.option;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

import com.wellnessz.e32.support.Docs;
import com.wellnessz.e32.support.FieldIssue;

/**
 * Option-specific rules beyond the JSON schema (internationally accepted practice per option, E32-HLR-03) and the mapping
 * of driver-package {@code protocol} defaults onto the option parameters ("defaults come from the certified driver package,
 * so the administrator validates rather than invents"). Pure functions.
 */
public final class OptionRules {

    /** Read-only SELECT only: no statement separators, no DML / DDL / procedure calls (E34: read-only vendor account). */
    private static final Pattern WRITE_SQL = Pattern.compile(
            "(?i)\\b(insert|update|delete|merge|drop|alter|create|truncate|grant|revoke|exec|execute|call|into|xp_[a-z_]+|sp_[a-z_]+)\\b");

    private OptionRules() {
    }

    /**
     * @param allowedHosts {@code device.cloud.allowed-hosts} (P6 egress allow-list)
     * @param residency    tenant data-residency region
     */
    public record Context(List<String> allowedHosts, String residency, String zone) {

        public Context {
            allowedHosts = allowedHosts == null ? List.of() : List.copyOf(allowedHosts);
        }
    }

    public static List<FieldIssue> semantic(String option, Map<String, Object> p, Context ctx) {
        List<FieldIssue> out = new ArrayList<>();
        switch (option) {
            case "P1" -> p1(p, out);
            case "P2" -> p2(p, out);
            case "P3" -> p3(p, out);
            case "P4" -> p4(p, ctx, out);
            case "P5" -> p5(p, out);
            case "P6" -> p6(p, ctx, out);
            case "P7" -> p7(p, out);
            case "P8" -> p8(p, out);
            case "P9" -> p9(p, out);
            case "P10" -> p10(p, out);
            default -> out.add(new FieldIssue("option", "enum", "unknown connectivity option"));
        }
        return out;
    }

    private static void p1(Map<String, Object> p, List<FieldIssue> out) {
        String connection = Docs.str(p.get("connection"));
        if (("USB_CDC".equals(connection) || "USB_HID_WEDGE".equals(connection)) && Docs.list(p.get("usbAllowList")).isEmpty()) {
            out.add(new FieldIssue("usbAllowList", "required", "USB devices need a vendor / product id allow-list"));
        }
        if ("POLL".equals(Docs.str(p.get("mode")))) {
            if (Docs.str(p.get("pollCommand")) == null) {
                out.add(new FieldIssue("pollCommand", "required", "poll mode needs the poll command"));
            }
            if (p.get("pollSeconds") == null) {
                out.add(new FieldIssue("pollSeconds", "required", "poll mode needs the poll interval"));
            }
        }
        Map<String, Object> framing = Docs.map(p.get("framing"));
        if ("LINE".equals(Docs.str(framing.get("type"))) && Docs.str(framing.get("terminator")) == null) {
            out.add(new FieldIssue("framing.terminator", "required", "line framing needs a terminator"));
        }
    }

    private static void p2(Map<String, Object> p, List<FieldIssue> out) {
        String source = Docs.str(p.get("source"));
        if ("DB_QUERY".equals(source)) {
            for (String k : List.of("dialect", "dbCredentialRef", "query", "watermarkColumn")) {
                if (p.get(k) == null) {
                    out.add(new FieldIssue(k, "required", "is required for a database source"));
                }
            }
            if (p.get("connection") == null) {
                out.add(new FieldIssue("connection", "required", "is required for a database source"));
            }
            String q = Docs.str(p.get("query"));
            if (q != null && !readOnlySelect(q)) {
                out.add(new FieldIssue("query", "query_not_read_only", "only a single read-only SELECT statement is allowed"));
            }
        } else if ("FOLDER_WATCH".equals(source)) {
            for (String k : List.of("path", "pattern")) {
                if (Docs.str(p.get(k)) == null) {
                    out.add(new FieldIssue(k, "required", "is required for a watched folder"));
                }
            }
        }
    }

    public static boolean readOnlySelect(String q) {
        String s = q.strip();
        return s.toLowerCase(Locale.ROOT).startsWith("select ") && !s.contains(";") && !s.contains("--") && !s.contains("/*")
                && !WRITE_SQL.matcher(s).find();
    }

    private static void p3(Map<String, Object> p, List<FieldIssue> out) {
        if ("JUST_WORKS_EXCEPTION".equals(Docs.str(p.get("pairing"))) && Docs.str(p.get("securityExceptionRef")) == null) {
            out.add(new FieldIssue("securityExceptionRef", "pairing_exception_required",
                    "'Just Works' pairing needs a security exception approved by Security Operations"));
        }
        if ("REQUIRED".equals(Docs.str(p.get("bonding"))) && "JUST_WORKS_EXCEPTION".equals(Docs.str(p.get("pairing")))) {
            out.add(new FieldIssue("pairing", "pairing_weak", "bonded devices must pair with LE Secure Connections"));
        }
    }

    private static void p4(Map<String, Object> p, Context ctx, List<FieldIssue> out) {
        Map<String, Object> addr = Docs.map(p.get("deviceAddress"));
        if (addr.get("ip") == null && addr.get("dns") == null) {
            out.add(new FieldIssue("deviceAddress", "required", "a static IP address or DNS name is required"));
        }
        Map<String, Object> mllp = Docs.map(p.get("mllp"));
        if ("EXCEPTION_SEGMENTED_VLAN".equals(Docs.str(mllp.get("tls")))) {
            if (Docs.str(mllp.get("plaintextExceptionRef")) == null) {
                out.add(new FieldIssue("mllp.plaintextExceptionRef", "plaintext_exception_required",
                        "plaintext MLLP needs a documented security exception"));
            }
            if (ctx != null && ctx.zone() != null && !"DEVICE_VLAN".equals(ctx.zone())) {
                out.add(new FieldIssue("mllp.tls", "zone_violation", "plaintext MLLP is allowed only inside the segmented DEVICE_VLAN"));
            }
        }
        Map<String, Object> wl = Docs.map(p.get("worklist"));
        if (Docs.bool(wl.get("enabled"), false)) {
            if (wl.get("messageType") == null) {
                out.add(new FieldIssue("worklist.messageType", "required", "worklist push needs the message type"));
            }
            if ("MLLP_OUT".equals(Docs.str(wl.get("push"))) && wl.get("outboundPort") == null) {
                out.add(new FieldIssue("worklist.outboundPort", "required", "outbound MLLP needs the device's listening port"));
            }
        }
    }

    private static void p5(Map<String, Object> p, List<FieldIssue> out) {
        Map<String, Object> qc = Docs.map(p.get("qcLotPolicy"));
        if (!qc.isEmpty() && (!Docs.bool(qc.get("requirePassingQc"), true) || !Docs.bool(qc.get("newLotRequiresQc"), true))) {
            out.add(new FieldIssue("qcLotPolicy", "cannot_relax", "a passing QC per lot and per QC window is mandatory for point-of-care results"));
        }
    }

    private static void p6(Map<String, Object> p, Context ctx, List<FieldIssue> out) {
        Map<String, Object> poll = Docs.map(p.get("poll"));
        Map<String, Object> webhook = Docs.map(p.get("webhook"));
        boolean polling = Docs.bool(poll.get("enabled"), false);
        boolean hooks = Docs.bool(webhook.get("enabled"), !webhook.isEmpty());
        if (!polling && !hooks) {
            out.add(new FieldIssue("poll", "required", "enable polling, the webhook, or both"));
        }
        if (polling) {
            String base = Docs.str(p.get("apiBaseUrl"));
            if (base == null) {
                out.add(new FieldIssue("apiBaseUrl", "required", "polling needs the vendor API base URL"));
            } else if (!hostAllowed(base, ctx == null ? List.of() : ctx.allowedHosts())) {
                out.add(new FieldIssue("apiBaseUrl", "host_not_allowed", "the host is not on the approved egress list (device.cloud.allowed-hosts)"));
            }
            if (p.get("oauth2") == null) {
                out.add(new FieldIssue("oauth2", "required", "polling needs an OAuth2 client"));
            }
        }
        if (hooks && Docs.str(webhook.get("webhookSecretRef")) == null) {
            out.add(new FieldIssue("webhook.webhookSecretRef", "required", "the webhook needs an HMAC secret"));
        }
        String residency = Docs.str(p.get("residencyRegion"));
        if (ctx != null && residency != null && ctx.residency() != null && !ctx.residency().equalsIgnoreCase(residency)) {
            out.add(new FieldIssue("residencyRegion", "residency_check", "the vendor region does not match the tenant's data residency"));
        }
    }

    static boolean hostAllowed(String url, List<String> allowed) {
        try {
            String host = URI.create(url).getHost();
            return host != null && allowed.stream().anyMatch(a -> a.equalsIgnoreCase(host) || a.startsWith(".") && host.endsWith(a));
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static void p7(Map<String, Object> p, List<FieldIssue> out) {
        Map<String, Object> mwl = Docs.map(p.get("mwl"));
        if (Docs.bool(mwl.get("enabled"), false) && Docs.map(mwl.get("filters")).isEmpty()) {
            out.add(new FieldIssue("mwl.filters", "required", "the worklist needs a modality or station AE title filter"));
        }
        if (Docs.str(Docs.at(p, "aeTitles.calling")) != null && Docs.str(Docs.at(p, "aeTitles.calling")).equals(Docs.str(Docs.at(p, "aeTitles.called")))) {
            out.add(new FieldIssue("aeTitles", "ae_title_conflict", "calling and called AE titles must differ"));
        }
    }

    private static void p8(Map<String, Object> p, List<FieldIssue> out) {
        if ("SMART_BACKEND".equals(Docs.str(p.get("auth"))) && Docs.list(p.get("smartScopes")).isEmpty()) {
            out.add(new FieldIssue("smartScopes", "required", "SMART backend services need system scopes"));
        }
    }

    private static void p9(Map<String, Object> p, List<FieldIssue> out) {
        List<Object> regions = Docs.list(p.get("regions"));
        for (int i = 0; i < regions.size(); i++) {
            List<Object> box = Docs.list(Docs.map(regions.get(i)).get("box"));
            if (box.size() == 4 && box.stream().allMatch(Number.class::isInstance)) {
                double x1 = ((Number) box.get(0)).doubleValue();
                double y1 = ((Number) box.get(1)).doubleValue();
                double x2 = ((Number) box.get(2)).doubleValue();
                double y2 = ((Number) box.get(3)).doubleValue();
                if (x1 >= x2 || y1 >= y2) {
                    out.add(new FieldIssue("regions[" + i + "].box", "box_invalid", "the region must be [x1, y1, x2, y2] with x1 < x2 and y1 < y2"));
                }
            }
        }
    }

    private static void p10(Map<String, Object> p, List<FieldIssue> out) {
        List<String> fields = Docs.strings(p.get("attestationFields"));
        for (String f : List.of("MODEL", "SERIAL", "CALIBRATION_ID", "CALIBRATION_DATE")) {
            if (!fields.isEmpty() && !fields.contains(f)) {
                out.add(new FieldIssue("attestationFields", "attestation_incomplete", "all four attestation fields are mandatory"));
                return;
            }
        }
    }

    /** Endpoint id the option's traffic arrives through (gateway, agent, tablet, data manager, client), when already enrolled. */
    public static String hostEndpoint(String option, Map<String, Object> p) {
        return switch (option) {
            case "P1", "P2" -> Docs.str(p.get("hostEndpointId"));
            case "P3" -> Docs.str(p.get("bondingOwnerEndpointId"));
            case "P4" -> Docs.str(Docs.at(p, "mllp.listenerEndpointId"));
            case "P5" -> Docs.str(Docs.at(p, "dms.endpoint"));
            case "P7" -> Docs.str(p.get("listenerEndpointId"));
            case "P8" -> Docs.str(p.get("clientEndpointId"));
            default -> null;
        };
    }

    /** Endpoint kind for P3 depends on the bond owner. */
    public static String endpointKind(String option, String packKind, Map<String, Object> p) {
        if ("P3".equals(option)) {
            return switch (Docs.str(p.get("bondingOwner")) == null ? "TABLET" : Docs.str(p.get("bondingOwner"))) {
                case "GATEWAY" -> "GATEWAY";
                case "KIOSK" -> "KIOSK";
                default -> "MOBILE_APP";
            };
        }
        return packKind;
    }

    /** Wizard step-4 defaults derived from the driver package's {@code protocol} member. */
    public static Map<String, Object> fromPackage(String option, Map<String, Object> protocol, Map<String, Object> schema) {
        Map<String, Object> out = new LinkedHashMap<>();
        Map<String, Object> props = Docs.map(schema.get("properties"));
        protocol.forEach((k, v) -> {
            if (props.containsKey(k) && !(v instanceof String s && s.startsWith("PARAMETER:"))) {
                out.put(k, Docs.copy(v));
            }
        });
        switch (option) {
            case "P2" -> {
                if (protocol.containsKey("deleteAfterRead")) {
                    out.put("afterRead", "KEEP");
                }
            }
            case "P3" -> {
                if (protocol.get("characteristic") != null) {
                    out.put("characteristics", List.of(protocol.get("characteristic")));
                }
            }
            case "P4" -> {
                if (protocol.get("mllpPort") != null) {
                    Docs.put(out, "mllp.port", protocol.get("mllpPort"));
                }
                if (protocol.get("tls") != null) {
                    Docs.put(out, "mllp.tls", protocol.get("tls"));
                }
                if (protocol.get("profile") != null) {
                    out.put("messageProfile", protocol.get("profile"));
                }
            }
            case "P5" -> {
                if (protocol.get("dmsVendor") != null) {
                    Docs.put(out, "dms.vendor", protocol.get("dmsVendor"));
                }
            }
            case "P6" -> {
                Map<String, Object> sig = Docs.map(protocol.get("signature"));
                if (!sig.isEmpty()) {
                    Docs.put(out, "webhook.enabled", true);
                    putIfPresent(out, "webhook.signatureHeader", sig.get("header"));
                    putIfPresent(out, "webhook.timestampHeader", sig.get("timestampHeader"));
                    putIfPresent(out, "webhook.replayWindowSeconds", sig.get("replayWindowSeconds"));
                }
                Map<String, Object> poll = Docs.map(protocol.get("poll"));
                if (!poll.isEmpty()) {
                    putIfPresent(out, "poll.enabled", poll.get("enabled"));
                    putIfPresent(out, "poll.intervalSeconds", poll.get("intervalSeconds"));
                    putIfPresent(out, "poll.rateLimitPerMinute", poll.get("rateLimitPerMinute"));
                    putIfPresent(out, "poll.path", poll.get("path"));
                }
                // consent-scoped wearables: the package's consent purpose becomes the connector's consent mapping
                putIfPresent(out, "consentMapping.purpose", protocol.get("consentPurpose"));
            }
            case "P7" -> {
                if (protocol.get("sopClasses") != null) {
                    out.put("storageClasses", Docs.copy(protocol.get("sopClasses")));
                }
                if (protocol.get("mwl") instanceof Boolean b) {
                    Docs.put(out, "mwl.enabled", b);
                }
            }
            case "P9" -> {
                if (protocol.get("template") != null) {
                    out.put("ocrTemplate", protocol.get("template"));
                }
            }
            default -> {
                // P1, P8, P10: protocol members carry the parameter names directly
            }
        }
        return out;
    }

    private static void putIfPresent(Map<String, Object> out, String path, Object v) {
        if (v != null) {
            Docs.put(out, path, Docs.copy(v));
        }
    }
}
