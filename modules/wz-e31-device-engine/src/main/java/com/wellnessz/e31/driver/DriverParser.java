package com.wellnessz.e31.driver;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import com.wellnessz.casekernel.device.DeviceCodes;
import com.wellnessz.e31.support.Ids;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Reads a driver package and checks its schema ({@code wellnessz.device-driver/1}, contract section 6.1). Every schema
 * finding is collected; {@link #parse(String)} throws {@link DriverException} with all findings when any is fatal.
 */
public final class DriverParser {

    public static final Set<String> ADAPTERS = Set.of("HL7V2", "FHIR", "POCT1A", "FILE_CSV", "FILE_XML", "FILE_GDT", "FILE_PDF", "BLE_GATT",
            "SERIAL", "WEBHOOK_JSON", "CLOUD_POLL_JSON", "DICOMWEB", "OCR", "MANUAL");
    public static final Set<String> PARSE_TYPES = Set.of("HL7_SEGMENTS", "FHIR_PATH", "POCT1A_XPATH", "CSV_COLUMNS", "XPATH", "GDT_FIELDS",
            "JSON_POINTER", "GATT_STRUCT", "REGEX_LINES", "DICOM_JSON", "FORM");
    public static final Set<String> FIELD_TYPES = Set.of("QUANTITY", "STRING", "CODE", "BOOLEAN");
    public static final Set<String> WORKLIST_PUSH = Set.of("NONE", "HL7_ORM", "HL7_ADT", "GDT_6302", "DICOM_MWL", "VENDOR_IMPORT_FILE");
    static final Pattern PACKAGE_ID = Pattern.compile("[a-z0-9]+(\\.[a-z0-9\\-]+){1,4}");
    static final Pattern OPTION = Pattern.compile("P([1-9]|10)");

    private final JsonMapper json;

    public DriverParser(JsonMapper json) {
        this.json = json;
    }

    public JsonNode tree(String packageJson) {
        try {
            JsonNode root = json.readTree(packageJson);
            if (root == null || !root.isObject()) {
                throw new DriverException(List.of("schema: the package must be a JSON object"));
            }
            return root;
        } catch (JacksonException e) {
            throw new DriverException(List.of("schema: the package is not valid JSON"));
        }
    }

    public DriverPackage parse(String packageJson) {
        return parse(tree(packageJson));
    }

    public DriverPackage parse(JsonNode root) {
        List<String> errors = new ArrayList<>();
        String schema = text(root, "schema");
        if (schema != null && !DriverPackage.SCHEMA.equals(schema)) {
            errors.add("schema: unsupported schema " + schema);
        }
        String packageId = required(root, "packageId", errors);
        if (packageId != null && !PACKAGE_ID.matcher(packageId).matches()) {
            errors.add("packageId: must match " + PACKAGE_ID.pattern());
        }
        String version = required(root, "version", errors);
        if (version != null && !SemVer.isStrict(version)) {
            errors.add("version: must be MAJOR.MINOR.PATCH");
        }
        DeviceCodes.Family family = enumValue(DeviceCodes.Family.class, required(root, "family", errors), "family", errors);
        List<String> models = strings(root.get("models"));
        if (models.isEmpty()) {
            errors.add("models: at least one catalogue model is required");
        }
        String adapter = required(root, "adapter", errors);
        if (adapter != null && !ADAPTERS.contains(adapter)) {
            errors.add("adapter: unknown adapter " + adapter);
        }
        List<String> options = strings(root.get("options"));
        for (String o : options) {
            if (!OPTION.matcher(o).matches()) {
                errors.add("options: unknown option " + o);
            }
        }
        String compat = text(root, "engineCompatibility");
        if (compat != null) {
            try {
                SemVer.satisfies("1.0.0", compat);
            } catch (IllegalArgumentException e) {
                errors.add("engineCompatibility: invalid range");
            }
        }
        DriverPackage.Capabilities caps = DriverPackage.Capabilities.NONE;
        JsonNode c = root.get("capabilities");
        if (c != null && c.isObject()) {
            String push = text(c, "worklistPush");
            if (push != null && !WORKLIST_PUSH.contains(push)) {
                errors.add("capabilities.worklistPush: unknown value " + push);
            }
            caps = new DriverPackage.Capabilities(push == null ? "NONE" : push, c.path("startFromForm").asBoolean(false),
                    c.path("historicRetrieval").asBoolean(false));
        }
        JsonNode protocol = root.get("protocol");
        if (protocol == null || !protocol.isObject()) {
            errors.add("protocol: required object");
        }
        DriverPackage.ParseSpec parse = parseSpec(root.get("parse"), errors);
        List<DriverPackage.Mapping> mappings = mappings(root.get("mappings"), errors);
        List<DriverPackage.RefRange> ranges = ranges(root.get("referenceRanges"), errors);
        DriverPackage.Predicted predicted = null;
        JsonNode p = root.get("predicted");
        if (p != null && p.isObject()) {
            Map<String, String> keys = new LinkedHashMap<>();
            JsonNode k = p.get("keys");
            if (k != null && k.isObject()) {
                for (Map.Entry<String, JsonNode> e : k.properties()) {
                    keys.put(e.getKey(), e.getValue().asString());
                }
            }
            String eq = text(p, "equation");
            if (eq != null && !Set.of("GLI_2012", "NHANES_III", "NONE").contains(eq)) {
                errors.add("predicted.equation: unknown equation " + eq);
            }
            predicted = new DriverPackage.Predicted(eq == null ? "NONE" : eq, keys);
        }
        List<String> gates = strings(root.get("qualityGates"));
        List<DriverPackage.Binding> bindings = bindings(root.get("bindings"), errors);
        List<DriverPackage.TestVector> vectors = new ArrayList<>();
        JsonNode tv = root.get("testVectors");
        if (tv != null && tv.isArray()) {
            for (JsonNode v : tv) {
                vectors.add(new DriverPackage.TestVector(text(v, "name"), text(v, "input"), v.get("expect")));
            }
        }
        DriverPackage.Signature sig = null;
        JsonNode s = root.get("signature");
        if (s != null && s.isObject()) {
            sig = new DriverPackage.Signature(text(s, "keyId"), text(s, "algorithm"), text(s, "value"));
        }
        if (!errors.isEmpty()) {
            throw new DriverException(errors);
        }
        String canonical = CanonicalJson.of(root);
        return new DriverPackage(packageId, version, family, models, adapter, options, compat, caps, protocol, parse, mappings, ranges,
                predicted, gates, bindings, vectors, sig, canonical, Ids.sha256Hex(canonical));
    }

    private DriverPackage.ParseSpec parseSpec(JsonNode p, List<String> errors) {
        if (p == null || !p.isObject()) {
            errors.add("parse: required object");
            return new DriverPackage.ParseSpec("FORM", DriverPackage.IdentitySpec.EMPTY, null, Map.of(), List.of());
        }
        String type = text(p, "type");
        if (type == null || !PARSE_TYPES.contains(type)) {
            errors.add("parse.type: unknown parse type " + type);
        }
        DriverPackage.IdentitySpec identity = DriverPackage.IdentitySpec.EMPTY;
        JsonNode id = p.get("identity");
        if (id != null && id.isObject()) {
            JsonNode eff = id.get("effectiveTime");
            identity = new DriverPackage.IdentitySpec(text(id, "employee"), text(id, "badge"), text(id, "deviceSerial"), text(id, "orderRef"),
                    eff == null ? null : text(eff, "selector"), eff == null ? null : text(eff, "format"), eff == null ? null : text(eff, "timeZone"),
                    text(id, "name"), text(id, "dateOfBirth"), text(id, "sex"), text(id, "ageYears"), text(id, "heightCm"), text(id, "operator"));
        }
        Map<String, String> ns = new LinkedHashMap<>();
        JsonNode n = p.get("namespaces");
        if (n != null && n.isObject()) {
            for (Map.Entry<String, JsonNode> e : n.properties()) {
                ns.put(e.getKey(), e.getValue().asString());
            }
        }
        List<DriverPackage.FieldSpec> fields = new ArrayList<>();
        JsonNode f = p.get("fields");
        if (f == null || !f.isArray() || f.isEmpty()) {
            errors.add("parse.fields: at least one field is required");
        } else {
            Set<String> keys = new java.util.HashSet<>();
            for (JsonNode x : f) {
                String key = text(x, "key");
                if (key == null || !key.matches("[A-Za-z][A-Za-z0-9_.]{0,59}")) {
                    errors.add("parse.fields: invalid key " + key);
                    continue;
                }
                if (!keys.add(key)) {
                    errors.add("parse.fields: duplicate key " + key);
                }
                String ft = text(x, "type");
                if (ft != null && !FIELD_TYPES.contains(ft)) {
                    errors.add("parse.fields." + key + ": unknown type " + ft);
                }
                fields.add(new DriverPackage.FieldSpec(key, text(x, "selector"), ft == null ? "STRING" : ft, text(x, "unit"),
                        x.path("required").asBoolean(false), ft == null));
            }
        }
        return new DriverPackage.ParseSpec(type, identity, text(p, "records"), ns, fields);
    }

    private List<DriverPackage.Mapping> mappings(JsonNode m, List<String> errors) {
        List<DriverPackage.Mapping> out = new ArrayList<>();
        if (m == null || !m.isArray()) {
            errors.add("mappings: required array");
            return out;
        }
        for (JsonNode x : m) {
            String key = text(x, "key");
            String loinc = text(x, "loinc");
            if (key == null) {
                errors.add("mappings: key required");
                continue;
            }
            if (loinc == null) {
                errors.add("mappings." + key + ": LOINC code is mandatory");
            }
            DriverPackage.Convert conv = null;
            JsonNode cv = x.get("convert");
            if (cv != null && cv.isObject()) {
                conv = new DriverPackage.Convert(text(cv, "fromUnit"), decimal(cv.get("factor")), decimal(cv.get("offset")));
                if (conv.fromUnit() == null || conv.factor() == null) {
                    errors.add("mappings." + key + ".convert: fromUnit and factor are required");
                }
            }
            JsonNode pl = x.get("plausible");
            Integer decimals = x.has("decimals") && x.get("decimals").isNumber() ? x.get("decimals").asInt() : null;
            out.add(new DriverPackage.Mapping(key, text(x, "mdc"), loinc, text(x, "display") == null ? key : text(x, "display"), text(x, "ucum"),
                    conv, text(x, "bodySite"), text(x, "method"), decimals, pl == null ? null : decimal(pl.get("min")),
                    pl == null ? null : decimal(pl.get("max"))));
        }
        return out;
    }

    private List<DriverPackage.RefRange> ranges(JsonNode r, List<String> errors) {
        List<DriverPackage.RefRange> out = new ArrayList<>();
        if (r == null || !r.isArray()) {
            return out;
        }
        for (JsonNode x : r) {
            String sex = text(x, "sex") == null ? "*" : text(x, "sex");
            if (!Set.of("M", "F", "*").contains(sex)) {
                errors.add("referenceRanges: sex must be M, F or *");
            }
            out.add(new DriverPackage.RefRange(text(x, "key"), sex, x.path("ageMin").asInt(0), x.path("ageMax").asInt(150),
                    decimal(x.get("low")), decimal(x.get("high")), text(x, "text")));
        }
        return out;
    }

    private List<DriverPackage.Binding> bindings(JsonNode b, List<String> errors) {
        List<DriverPackage.Binding> out = new ArrayList<>();
        if (b == null || !b.isArray()) {
            return out;
        }
        for (JsonNode x : b) {
            String form = text(x, "formCode");
            DeviceCodes.Category cat = enumValue(DeviceCodes.Category.class, text(x, "category"), "bindings.category", errors);
            String target = text(x, "target");
            if (form == null || target == null) {
                errors.add("bindings: formCode and target are required");
                continue;
            }
            Map<String, String> fields = new LinkedHashMap<>();
            JsonNode f = x.get("fields");
            if (f != null && f.isObject()) {
                for (Map.Entry<String, JsonNode> e : f.properties()) {
                    fields.put(e.getKey(), e.getValue().asString());
                }
            }
            out.add(new DriverPackage.Binding(form, text(x, "formVersion") == null ? "1" : text(x, "formVersion"), cat, target, fields,
                    x.path("verifyBeforePost").asBoolean(true), strings(x.get("qualityGates"))));
        }
        return out;
    }

    // ------------------------------------------------------------------ helpers

    static String text(JsonNode n, String member) {
        JsonNode v = n == null ? null : n.get(member);
        return v == null || v.isNull() || !v.isValueNode() ? null : v.asString();
    }

    private static String required(JsonNode n, String member, List<String> errors) {
        String v = text(n, member);
        if (v == null || v.isBlank()) {
            errors.add(member + ": required");
            return null;
        }
        return v;
    }

    static List<String> strings(JsonNode n) {
        List<String> out = new ArrayList<>();
        if (n != null && n.isArray()) {
            for (JsonNode x : n) {
                if (x.isValueNode()) {
                    out.add(x.asString());
                }
            }
        }
        return out;
    }

    static BigDecimal decimal(JsonNode n) {
        if (n == null || n.isNull()) {
            return null;
        }
        if (n.isNumber()) {
            return n.decimalValue();
        }
        try {
            return new BigDecimal(n.asString().strip());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static <E extends Enum<E>> E enumValue(Class<E> type, String v, String member, List<String> errors) {
        if (v == null) {
            return null;
        }
        try {
            return Enum.valueOf(type, v.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            errors.add(member + ": unknown value " + v);
            return null;
        }
    }
}
