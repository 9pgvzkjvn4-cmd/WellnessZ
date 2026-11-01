package com.wellnessz.e31.driver;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import com.wellnessz.e31.gate.GateCatalogue;
import com.wellnessz.e31.support.EngineSettings;
import com.wellnessz.e31.terminology.Terminology;

import tools.jackson.databind.JsonNode;

/**
 * Validation report of a driver package (E31-HLR-03, contract section 6.1): schema, engine compatibility, secret scan
 * (E34-HLR-05 rule, applied by E31 as well), mapping coverage (every measured field mapped, LOINC everywhere, UCUM for
 * quantities, codes listed in the loaded terminology release), known gates, bindings to deployed targets and the minimum
 * test-vector set (normal, malformed, edge). Nothing is persisted.
 */
public final class DriverValidator {

    static final Pattern SECRET_KEY = Pattern.compile("(?i).*(password|secret|token|api[-_]?key|private[-_]?key|credential).*");

    private DriverValidator() {
    }

    public record Report(boolean valid, String packageId, String version, String sha256, List<String> errors, List<String> warnings,
                         Coverage coverage, List<String> secretFindings) {
    }

    /**
     * @param fields measured (non-technical) fields
     * @param coded  fields whose LOINC (and MDC where given) are listed in the terminology release
     */
    public record Coverage(int fields, int mapped, int coded, int quantities, int quantitiesWithUcum) {
    }

    public static Report validate(DriverParser parser, String packageJson, Terminology terminology, Set<String> deployedTargets) {
        JsonNode root;
        try {
            root = parser.tree(packageJson);
        } catch (DriverException e) {
            return new Report(false, null, null, null, e.errors(), List.of(), new Coverage(0, 0, 0, 0, 0), List.of());
        }
        List<String> secrets = new ArrayList<>();
        scanSecrets(root, "", secrets);
        DriverPackage pkg;
        try {
            pkg = parser.parse(root);
        } catch (DriverException e) {
            List<String> errors = new ArrayList<>(e.errors());
            if (!secrets.isEmpty()) {
                errors.add("secrets: literal secret values found – use devsec: or vault: references");
            }
            return new Report(false, DriverParser.text(root, "packageId"), DriverParser.text(root, "version"), null, errors, List.of(),
                    new Coverage(0, 0, 0, 0, 0), secrets);
        }
        return validate(pkg, terminology, deployedTargets, secrets);
    }

    public static Report validate(DriverPackage pkg, Terminology terminology, Set<String> deployedTargets, List<String> secretFindings) {
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        if (!secretFindings.isEmpty()) {
            errors.add("secrets: literal secret values found – use devsec: or vault: references");
        }
        if (pkg.engineCompatibility() != null && !SemVer.satisfies(EngineSettings.ENGINE_VERSION, pkg.engineCompatibility())) {
            errors.add("engineCompatibility: engine " + EngineSettings.ENGINE_VERSION + " is outside " + pkg.engineCompatibility());
        }
        protocolRules(pkg, errors, warnings);
        int fields = 0;
        int mapped = 0;
        int coded = 0;
        int quantities = 0;
        int withUcum = 0;
        Set<String> parseKeys = new HashSet<>();
        for (DriverPackage.FieldSpec f : pkg.parse().fields()) {
            parseKeys.add(f.key());
            if (f.technical()) {
                continue;
            }
            fields++;
            DriverPackage.Mapping m = pkg.mapping(f.key()).orElse(null);
            if (m == null) {
                errors.add("mappings: field " + f.key() + " is not mapped (mapping_missing)");
                continue;
            }
            mapped++;
            boolean loincKnown = terminology == null || terminology.known(m.loinc());
            boolean mdcKnown = m.mdc() == null || terminology == null || terminology.known(m.mdc());
            if (loincKnown && mdcKnown) {
                coded++;
            } else {
                errors.add("mappings." + f.key() + ": code not in the loaded terminology release");
            }
            if ("QUANTITY".equals(f.type())) {
                quantities++;
                if (m.ucum() != null) {
                    withUcum++;
                } else {
                    errors.add("mappings." + f.key() + ": UCUM unit is mandatory for quantities");
                }
            }
        }
        for (DriverPackage.Mapping m : pkg.mappings()) {
            if (!parseKeys.contains(m.key())) {
                warnings.add("mappings." + m.key() + ": no parse field with this key");
            }
        }
        for (String g : pkg.qualityGates()) {
            if (!GateCatalogue.isKnown(g)) {
                errors.add("qualityGates: unknown gate " + g);
            }
        }
        for (DriverPackage.Binding b : pkg.bindings()) {
            breathAlcoholUnit(pkg, b, errors);
            if (deployedTargets != null && !deployedTargets.contains(b.target())) {
                warnings.add("bindings." + b.formCode() + ": target " + b.target() + " is not deployed (route_target_unavailable)");
            }
            for (Map.Entry<String, String> e : b.fields().entrySet()) {
                String key = e.getKey();
                boolean derived = key.startsWith("param:") || key.startsWith("const:") || key.endsWith("PredictedL") || key.startsWith("ageCorrection")
                        || "ageYears".equals(key) || "sex".equals(key);
                if (!derived && !parseKeys.contains(key)) {
                    errors.add("bindings." + b.formCode() + ": field key " + key + " is unknown (binding_field_unknown)");
                }
            }
            for (String g : b.qualityGates()) {
                if (!GateCatalogue.isKnown(g)) {
                    errors.add("bindings." + b.formCode() + ": unknown gate " + g);
                }
            }
        }
        if (pkg.bindings().isEmpty()) {
            warnings.add("bindings: no default binding – the instance binding (wizard step 5) must provide one");
        }
        Set<String> names = new HashSet<>();
        for (DriverPackage.TestVector v : pkg.testVectors()) {
            names.add(v.name() == null ? "" : v.name().toLowerCase(Locale.ROOT));
        }
        boolean normal = names.stream().anyMatch(n -> n.contains("normal"));
        boolean malformed = names.stream().anyMatch(n -> n.contains("malformed"));
        boolean edge = names.stream().anyMatch(n -> !n.contains("normal") && !n.contains("malformed"));
        if (!normal || !malformed || !edge) {
            errors.add("testVectors: at least one normal, one malformed and one edge-case vector are required (E31-HLR-10)");
        }
        return new Report(errors.isEmpty(), pkg.packageId(), pkg.version(), pkg.sha256(), errors, warnings,
                new Coverage(fields, mapped, coded, quantities, withUcum), secretFindings);
    }

    /** Literal values for secret-like keys that are not {@code devsec:}, {@code vault:} or {@code PARAMETER:} references. */
    static void scanSecrets(JsonNode n, String path, List<String> findings) {
        if (n == null) {
            return;
        }
        if (n.isObject()) {
            for (Map.Entry<String, JsonNode> e : n.properties()) {
                String p = path.isEmpty() ? e.getKey() : path + "." + e.getKey();
                JsonNode v = e.getValue();
                if (v.isValueNode() && !v.isNull() && SECRET_KEY.matcher(e.getKey()).matches() && !e.getKey().endsWith("Header")) {
                    String s = v.asString();
                    boolean reference = s.startsWith("devsec:") || s.startsWith("vault:") || s.startsWith("PARAMETER:") || s.isBlank()
                            || e.getKey().toLowerCase(Locale.ROOT).endsWith("ref") || e.getKey().toLowerCase(Locale.ROOT).endsWith("url");
                    if (!reference) {
                        findings.add(p);
                    }
                }
                scanSecrets(v, p, findings);
            }
        } else if (n.isArray()) {
            for (int i = 0; i < n.size(); i++) {
                scanSecrets(n.get(i), path + "[" + i + "]", findings);
            }
        }
    }

    /** UCUM unit of E10's breath screen result (e-CCF field in g/210 L). */
    /** P3 pairing values of the E32 option schema (contract §13.3) – "Just Works" only as a security exception (E34-HLR-04). */
    public static final Set<String> P3_PAIRING = Set.of("LE_SECURE_CONNECTIONS_PASSKEY", "LE_SECURE_CONNECTIONS_NUMERIC", "JUST_WORKS_EXCEPTION");

    /**
     * Protocol rules shared with the E32 option packs: BLE pairing must be a P3 schema value; a start command needs
     * {@code startFromForm} and a SERIAL (payload) or MLLP (order message type) transport; historic retrieval needs a method the
     * gateway can run.
     */
    static void protocolRules(DriverPackage pkg, List<String> errors, List<String> warnings) {
        tools.jackson.databind.JsonNode p = pkg.protocol();
        if (p == null) {
            return;
        }
        String pairing = p.path("pairing").asString(null);
        if (pairing != null && !P3_PAIRING.contains(pairing)) {
            errors.add("protocol.pairing: " + pairing + " is not a P3 pairing method (LE_SECURE_CONNECTIONS_PASSKEY | LE_SECURE_CONNECTIONS_NUMERIC | "
                    + "JUST_WORKS_EXCEPTION)");
        }
        if ("JUST_WORKS_EXCEPTION".equals(pairing)) {
            warnings.add("protocol.pairing: Just Works needs a security exception approved by Security Operations for every instance");
        }
        tools.jackson.databind.JsonNode start = p.get("startCommand");
        if (start != null) {
            String transport = start.path("transport").asString("");
            if (!pkg.capabilities().startFromForm()) {
                errors.add("protocol.startCommand: declared without capabilities.startFromForm");
            }
            if ("SERIAL".equals(transport)) {
                String payload = start.path("payload").asString("");
                if (payload.isEmpty() || payload.length() > 256 || "HEX".equals(start.path("encoding").asString("TEXT")) && !payload.matches("([0-9A-Fa-f]{2})+")) {
                    errors.add("protocol.startCommand.payload: 1–256 characters (HEX: pairs of hex digits) required");
                }
            } else if ("MLLP".equals(transport)) {
                if (!Set.of("ORM^O01", "OML^O21").contains(start.path("messageType").asString("ORM^O01"))) {
                    errors.add("protocol.startCommand.messageType: ORM^O01 or OML^O21");
                }
            } else {
                errors.add("protocol.startCommand.transport: SERIAL or MLLP");
            }
        }
        tools.jackson.databind.JsonNode history = p.get("historicQuery");
        if (history != null && !Set.of("HL7_QUERY", "VENDOR_DB").contains(history.path("method").asString(""))) {
            errors.add("protocol.historicQuery.method: HL7_QUERY or VENDOR_DB");
        }
        if (history != null && !pkg.capabilities().historicRetrieval()) {
            errors.add("protocol.historicQuery: declared without capabilities.historicRetrieval");
        }
    }

    public static final String E10_BREATH_UNIT = "g/(210.L)";

    /**
     * A binding into E10's {@code breathScreenResult} must deliver g/210 L: the mapping's target UCUM is {@code g/(210.L)} – a
     * device reporting mg/L is converted by the NORMALISE stage (factor 0.21, {@code TerminologySnapshot.BUILT_IN}) – so E10
     * always receives its own unit and no UI converts.
     */
    static void breathAlcoholUnit(DriverPackage pkg, DriverPackage.Binding b, List<String> errors) {
        for (Map.Entry<String, String> e : b.fields().entrySet()) {
            if (!"breathScreenResult".equals(e.getValue())) {
                continue;
            }
            String ucum = pkg.mapping(e.getKey()).map(DriverPackage.Mapping::ucum).orElse(null);
            if (!E10_BREATH_UNIT.equals(ucum)) {
                errors.add("mappings." + e.getKey() + ": a binding to E10 breathScreenResult needs UCUM " + E10_BREATH_UNIT
                        + " (convert mg/L with factor 0.21)");
            }
        }
    }
}
