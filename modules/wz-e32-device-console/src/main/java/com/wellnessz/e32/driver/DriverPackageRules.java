package com.wellnessz.e32.driver;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import com.wellnessz.casekernel.device.DeviceCodes;
import com.wellnessz.e32.support.CanonicalJson;
import com.wellnessz.e32.support.Docs;

/**
 * Validation of declarative driver packages ({@code wellnessz.device-driver/1}, contract §6.1): schema members, model and
 * option coverage, mapping coverage (LOINC for every mapping, UCUM for quantities), quality-gate codes, binding targets,
 * test-vector kinds and the secret scanner (E34-HLR-05: no secret values, only {@code devsec:} / {@code vault:} /
 * {@code PARAMETER:} references). Pure functions.
 */
public final class DriverPackageRules {

    public static final String SCHEMA = "wellnessz.device-driver/1";
    public static final Pattern PACKAGE_ID = Pattern.compile("^[a-z0-9]+(\\.[a-z0-9\\-]+){1,4}$");
    public static final Pattern SEMVER = Pattern.compile("^(0|[1-9]\\d{0,3})\\.(0|[1-9]\\d{0,3})\\.(0|[1-9]\\d{0,4})$");
    public static final Set<String> ADAPTERS = Set.of("HL7V2", "FHIR", "POCT1A", "FILE_CSV", "FILE_XML", "FILE_GDT", "FILE_PDF", "BLE_GATT", "SERIAL",
            "WEBHOOK_JSON", "CLOUD_POLL_JSON", "DICOMWEB", "OCR", "MANUAL");
    public static final Set<String> PARSE_TYPES = Set.of("HL7_SEGMENTS", "FHIR_PATH", "POCT1A_XPATH", "CSV_COLUMNS", "XPATH", "GDT_FIELDS",
            "JSON_POINTER", "GATT_STRUCT", "REGEX_LINES", "DICOM_JSON", "FORM");
    public static final Set<String> WORKLIST_PUSH = Set.of("NONE", "HL7_ORM", "HL7_ADT", "GDT_6302", "DICOM_MWL", "VENDOR_IMPORT_FILE");
    public static final Set<String> GATES = Set.of("CALIBRATION", "QC_DAILY", "QC_LOT", "ATS_ERS_GRADE", "PREDICTED_VALUES", "AUDIO_TEST_RETEST",
            "AUDIO_AMBIENT_NOISE", "BP_CUFF_VALIDATION", "FIT_EXERCISES_COMPLETE", "PLAUSIBILITY", "CLOCK_SKEW", "CONSENT", "ATTESTATION",
            "OCR_CONFIDENCE", "VERIFY_BEFORE_POST");
    public static final Set<String> TARGETS = Set.of("E03.RESULT", "E07.IH_MEASUREMENT", "E10.BREATH_ALCOHOL", "E12.SCREENING", "E12.WEARABLE");
    public static final Pattern FORM_CODE = Pattern.compile("^E[0-9]{2}\\.[A-Z_]{2,40}(\\.[A-Z_]{2,40})?$");
    /** Keys the engine derives (GLI-2012 predicted values, % predicted, LLN) – allowed in bindings without a mapping. */
    public static final Set<String> DERIVED_KEYS = Set.of("fev1PredictedL", "fvcPredictedL", "fev1PctPredicted", "fvcPctPredicted", "fev1Lln",
            "fvcLln", "ratioLln");
    private static final Pattern SECRET_KEY = Pattern.compile("(?i).*(password|secret|token|apikey|privatekey).*");
    private static final Pattern NON_SECRET_SUFFIX = Pattern.compile("(?i).*(url|header|headers|seconds|ttl|algorithm)$");
    private static final Pattern RANGE_BOUND = Pattern.compile("[<>]=?[0-9]{1,4}\\.[0-9]{1,4}(\\.[0-9]{1,5})?");

    private DriverPackageRules() {
    }

    public record Issue(String field, String code, String message) {
    }

    public record Report(List<Issue> errors, List<Issue> warnings, String canonical, String sha256) {

        public Report {
            errors = List.copyOf(errors);
            warnings = List.copyOf(warnings);
        }

        public boolean valid() {
            return errors.isEmpty();
        }
    }

    /** The package without its {@code signature} member, in canonical form (what the signature covers). */
    public static String canonical(Map<String, Object> pkg) {
        Map<String, Object> copy = new LinkedHashMap<>(pkg);
        copy.remove("signature");
        return CanonicalJson.write(copy);
    }

    /**
     * @param modelOptions catalogue model code → supported options (models missing from the map do not exist)
     */
    /** P3 pairing values (option pack schema, contract §13.3). */
    static final java.util.Set<String> P3_PAIRING = java.util.Set.of("LE_SECURE_CONNECTIONS_PASSKEY", "LE_SECURE_CONNECTIONS_NUMERIC", "JUST_WORKS_EXCEPTION");

    public static Report validate(Map<String, Object> pkg, Map<String, List<String>> modelOptions) {
        List<Issue> e = new ArrayList<>();
        List<Issue> w = new ArrayList<>();
        if (!SCHEMA.equals(Docs.str(pkg.get("schema")))) {
            e.add(new Issue("schema", "schema_invalid", "schema must be " + SCHEMA));
        }
        String packageId = Docs.str(pkg.get("packageId"));
        if (packageId == null || !PACKAGE_ID.matcher(packageId).matches()) {
            e.add(new Issue("packageId", "pattern", "packageId must look like family.vendor.product"));
        }
        String version = Docs.str(pkg.get("version"));
        if (version == null || !SEMVER.matcher(version).matches()) {
            e.add(new Issue("version", "semver", "version must be MAJOR.MINOR.PATCH"));
        }
        String family = Docs.str(pkg.get("family"));
        if (family == null || !enumContains(DeviceCodes.Family.class, family)) {
            e.add(new Issue("family", "enum", "unknown device family"));
        }
        List<String> models = Docs.strings(pkg.get("models"));
        if (models.isEmpty()) {
            e.add(new Issue("models", "required", "at least one catalogue model is required"));
        }
        Set<String> modelOpts = new LinkedHashSet<>();
        for (String m : models) {
            List<String> opts = modelOptions.get(m);
            if (opts == null) {
                e.add(new Issue("models", "unknown_model", "model " + m + " is not in the catalogue"));
            } else {
                modelOpts.addAll(opts);
            }
        }
        String adapter = Docs.str(pkg.get("adapter"));
        if (adapter == null || !ADAPTERS.contains(adapter)) {
            e.add(new Issue("adapter", "enum", "unknown adapter type"));
        }
        List<String> options = Docs.strings(pkg.get("options"));
        if (options.isEmpty()) {
            e.add(new Issue("options", "required", "the package must name the connectivity options it serves"));
        }
        for (String o : options) {
            if (!o.matches("P([1-9]|10)")) {
                e.add(new Issue("options", "enum", "unknown connectivity option " + o));
            } else if (!modelOpts.isEmpty() && !modelOpts.contains(o)) {
                e.add(new Issue("options", "option_not_supported_by_model", "option " + o + " is not offered by the listed models"));
            }
        }
        String range = Docs.str(pkg.get("engineCompatibility"));
        if (range != null && !engineRange(range)) {
            e.add(new Issue("engineCompatibility", "pattern", "engine range must look like \">=1.0 <2.0\""));
        }
        Object push = Docs.at(pkg, "capabilities.worklistPush");
        if (push != null && !WORKLIST_PUSH.contains(Docs.str(push))) {
            e.add(new Issue("capabilities.worklistPush", "enum", "unknown worklist push capability"));
        }
        if (!(pkg.get("protocol") instanceof Map<?, ?>)) {
            e.add(new Issue("protocol", "required", "adapter protocol defaults are required"));
        }
        // the package's BLE pairing becomes the wizard default of the P3 schema: it must be one of its values (Just Works only by exception)
        String pairing = Docs.str(Docs.at(pkg, "protocol.pairing"));
        if (pairing != null && !P3_PAIRING.contains(pairing)) {
            e.add(new Issue("protocol.pairing", "enum", "pairing must be LE_SECURE_CONNECTIONS_PASSKEY, LE_SECURE_CONNECTIONS_NUMERIC or JUST_WORKS_EXCEPTION"));
        }
        Map<String, Object> parse = Docs.map(pkg.get("parse"));
        if (!PARSE_TYPES.contains(Docs.str(parse.get("type")))) {
            e.add(new Issue("parse.type", "enum", "unknown parse type"));
        }
        Map<String, String> fieldTypes = new LinkedHashMap<>();
        for (Object o : Docs.list(parse.get("fields"))) {
            Map<String, Object> f = Docs.map(o);
            String key = Docs.str(f.get("key"));
            if (key == null) {
                e.add(new Issue("parse.fields", "required", "every field needs a key"));
            } else if (fieldTypes.containsKey(key)) {
                e.add(new Issue("parse.fields", "duplicate_key", "duplicate field key " + key));
            } else {
                fieldTypes.put(key, Docs.str(f.get("type")));
            }
        }
        if (fieldTypes.isEmpty()) {
            e.add(new Issue("parse.fields", "required", "at least one parsed field is required"));
        }
        Map<String, Map<String, Object>> mappings = new LinkedHashMap<>();
        for (Object o : Docs.list(pkg.get("mappings"))) {
            Map<String, Object> m = Docs.map(o);
            String key = Docs.str(m.get("key"));
            if (key == null) {
                e.add(new Issue("mappings", "required", "every mapping needs a key"));
                continue;
            }
            mappings.put(key, m);
            if (Docs.str(m.get("loinc")) == null) {
                e.add(new Issue("mappings." + key, "loinc_missing", "LOINC (or registered LOCAL:) code is mandatory"));
            }
            if (!fieldTypes.containsKey(key)) {
                w.add(new Issue("mappings." + key, "mapping_without_field", "mapping for a key that is not parsed"));
            }
        }
        fieldTypes.forEach((key, type) -> {
            Map<String, Object> m = mappings.get(key);
            if ("QUANTITY".equals(type)) {
                if (m == null) {
                    e.add(new Issue("mappings." + key, "mapping_missing", "quantity field " + key + " has no mapping"));
                } else if (Docs.str(m.get("ucum")) == null) {
                    e.add(new Issue("mappings." + key, "ucum_missing", "UCUM unit is mandatory for quantities"));
                }
            } else if (type != null && m == null) {
                w.add(new Issue("mappings." + key, "mapping_missing", "field " + key + " is parsed but not mapped"));
            }
        });
        for (String gate : Docs.strings(pkg.get("qualityGates"))) {
            if (!GATES.contains(gate.split(":")[0])) {
                e.add(new Issue("qualityGates", "unknown_gate", "unknown quality gate " + gate));
            }
        }
        Set<String> bindable = new LinkedHashSet<>(mappings.keySet());
        bindable.addAll(DERIVED_KEYS);
        for (Object o : Docs.list(pkg.get("bindings"))) {
            Map<String, Object> b = Docs.map(o);
            String formCode = Docs.str(b.get("formCode"));
            if (formCode == null || !FORM_CODE.matcher(formCode).matches()) {
                e.add(new Issue("bindings.formCode", "pattern", "form code must look like E03.DEVICE_RESULT.VITALS"));
            }
            if (!TARGETS.contains(Docs.str(b.get("target")))) {
                e.add(new Issue("bindings.target", "unknown_target", "unknown routing target"));
            }
            String cat = Docs.str(b.get("category"));
            if (cat == null || !enumContains(DeviceCodes.Category.class, cat)) {
                e.add(new Issue("bindings.category", "enum", "unknown measurement category"));
            }
            for (String key : Docs.map(b.get("fields")).keySet()) {
                if (!bindable.contains(key)) {
                    e.add(new Issue("bindings.fields." + key, "binding_field_unknown", "binding key " + key + " is not a mapping key"));
                }
            }
        }
        if (Docs.list(pkg.get("bindings")).isEmpty()) {
            e.add(new Issue("bindings", "required", "at least one default binding is required"));
        }
        List<Object> vectors = Docs.list(pkg.get("testVectors"));
        boolean normal = false;
        boolean malformed = false;
        int edge = 0;
        for (Object o : vectors) {
            String name = String.valueOf(Docs.map(o).get("name")).toLowerCase(java.util.Locale.ROOT);
            if (name.startsWith("normal")) {
                normal = true;
            } else if (name.startsWith("malformed")) {
                malformed = true;
            } else {
                edge++;
            }
        }
        if (!normal || !malformed || edge == 0) {
            e.add(new Issue("testVectors", "test_vectors_incomplete", "at least one normal, one malformed and one edge-case test vector are required"));
        }
        scanSecrets("", pkg, e);
        String canonical = canonical(pkg);
        return new Report(e, w, canonical, Docs.sha256(canonical));
    }

    /** Secret scanner: secret-like members must hold references, never values. */
    static void scanSecrets(String path, Object node, List<Issue> errors) {
        if (node instanceof Map<?, ?> m) {
            m.forEach((k, v) -> {
                String key = String.valueOf(k);
                String p = path.isEmpty() ? key : path + "." + key;
                if ("signature".equals(p)) {
                    return;
                }
                if (v instanceof String s && SECRET_KEY.matcher(key).matches() && !NON_SECRET_SUFFIX.matcher(key).matches()
                        && !isReference(s)) {
                    errors.add(new Issue(p, "secret_in_package", "secret values are not allowed in driver packages – use a devsec: or vault: reference"));
                }
                scanSecrets(p, v, errors);
            });
        } else if (node instanceof List<?> l) {
            for (Object x : l) {
                scanSecrets(path, x, errors);
            }
        }
    }

    /** Engine compatibility range: a lower bound ({@code >=1.0}) optionally followed by an upper bound ({@code <2.0}). */
    static boolean engineRange(String range) {
        String[] parts = range.strip().split("\\s+");
        if (parts.length < 1 || parts.length > 2 || !parts[0].startsWith(">")) {
            return false;
        }
        for (String p : parts) {
            if (!RANGE_BOUND.matcher(p).matches()) {
                return false;
            }
        }
        return parts.length == 1 || parts[1].startsWith("<");
    }

    public static boolean isReference(String s) {
        return s.startsWith("devsec:") || s.startsWith("vault:") || s.startsWith("PARAMETER:");
    }

    /** Semantic-version comparison (MAJOR.MINOR.PATCH). */
    public static int compareVersions(String a, String b) {
        String[] x = a.split("\\.");
        String[] y = b.split("\\.");
        for (int i = 0; i < 3; i++) {
            int c = Integer.compare(part(x, i), part(y, i));
            if (c != 0) {
                return c;
            }
        }
        return 0;
    }

    private static int part(String[] p, int i) {
        try {
            return i < p.length ? Integer.parseInt(p[i]) : 0;
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    public static int major(String version) {
        return part(version.split("\\."), 0);
    }

    private static <E extends Enum<E>> boolean enumContains(Class<E> type, String name) {
        for (E c : type.getEnumConstants()) {
            if (c.name().equals(name)) {
                return true;
            }
        }
        return false;
    }
}
