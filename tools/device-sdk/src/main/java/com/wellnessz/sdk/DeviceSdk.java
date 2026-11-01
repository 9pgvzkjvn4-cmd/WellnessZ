package com.wellnessz.sdk;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import ca.uhn.fhir.context.FhirContext;

import com.wellnessz.e31.adapter.Adapters;
import com.wellnessz.e31.driver.BundledDrivers;
import com.wellnessz.e31.driver.CanonicalJson;
import com.wellnessz.e31.driver.DriverException;
import com.wellnessz.e31.driver.DriverPackage;
import com.wellnessz.e31.driver.DriverParser;
import com.wellnessz.e31.driver.DriverValidator;
import com.wellnessz.e31.driver.SemVer;
import com.wellnessz.e31.gate.GateEnvironment;
import com.wellnessz.e31.simulator.DryRun;
import com.wellnessz.e31.simulator.GoldenDatasets;
import com.wellnessz.e31.terminology.MigrationTerminology;
import com.wellnessz.e31.terminology.Terminology;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Driver SDK command line (E31-BL-011; contract §6 and §12): works offline with the engine's own parser, validator, simulator and
 * golden datasets, so a package that passes here passes the engine's conformance stage.
 *
 * <pre>
 * wz-device-sdk scaffold    --adapter HL7V2 --package-id vendor.model.hl7 [--family VITALS] [--model MD-027] [--out DIR]
 * wz-device-sdk validate    PACKAGE.json [--terminology V22.sql]
 * wz-device-sdk conformance PACKAGE.json [--golden DIR] [--terminology V22.sql]
 * wz-device-sdk keygen      --out DIR [--key-id DEV-NAME]
 * wz-device-sdk sign        PACKAGE.json (--dev-key DIR/dev-key.pk8 [--key-id ID] | --server URL --token BEARER [--tenant CODE]) [--out FILE]
 * wz-device-sdk verify      PACKAGE.json --public-key DIR/dev-key.pub
 * wz-device-sdk diff        OLD.json NEW.json
 * </pre>
 *
 * Exit codes: 0 success, 1 the package fails (invalid, conformance failure, signature invalid), 2 usage or I/O error.
 */
public final class DeviceSdk {

    static final Set<String> TARGETS = Set.of("E03.RESULT", "E07.IH_MEASUREMENT", "E10.BREATH_ALCOHOL", "E12.SCREENING", "E12.WEARABLE");

    private final PrintStream out;
    private final PrintStream err;
    private final JsonMapper json = JsonMapper.builder().build();
    private final DriverParser parser = new DriverParser(json);
    private Terminology terminology;
    private Adapters adapters;
    private HttpClient http;

    public DeviceSdk(PrintStream out, PrintStream err) {
        this.out = out;
        this.err = err;
    }

    public static void main(String[] args) {
        System.exit(new DeviceSdk(System.out, System.err).run(args));
    }

    /** Usage or input error (exit code 2). */
    static final class UsageException extends Exception {
        private static final long serialVersionUID = 1L;

        UsageException(String message) {
            super(message);
        }
    }

    public int run(String... args) {
        if (args.length == 0 || Set.of("help", "--help", "-h").contains(args[0])) {
            usage();
            return args.length == 0 ? 2 : 0;
        }
        Args a = new Args(args);
        try {
            return switch (args[0]) {
                case "scaffold" -> scaffold(a);
                case "validate" -> validate(a);
                case "conformance" -> conformance(a);
                case "keygen" -> keygen(a);
                case "sign" -> sign(a);
                case "verify" -> verify(a);
                case "diff" -> diff(a);
                default -> throw new UsageException("unknown command " + args[0]);
            };
        } catch (UsageException e) {
            err.println("error: " + e.getMessage());
            return 2;
        } catch (IOException | GeneralSecurityException e) {
            err.println("error: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            return 2;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            err.println("error: interrupted");
            return 2;
        }
    }

    void usage() {
        out.println("""
                WellnessZ device driver SDK (wellnessz.device-driver/1)
                  scaffold    --adapter <ADAPTER> --package-id <id> [--family F] [--model MD-xxx] [--out DIR]
                  validate    <package.json> [--terminology V22.sql]
                  conformance <package.json> [--golden DIR] [--terminology V22.sql]
                  keygen      --out DIR [--key-id DEV-NAME]
                  sign        <package.json> (--dev-key FILE [--key-id ID] | --server URL --token BEARER [--tenant CODE]) [--out FILE]
                  verify      <package.json> --public-key FILE
                  diff        <old.json> <new.json>
                Adapters: HL7V2 FHIR POCT1A FILE_CSV FILE_XML FILE_GDT FILE_PDF BLE_GATT SERIAL WEBHOOK_JSON CLOUD_POLL_JSON DICOMWEB OCR MANUAL""");
    }

    // ------------------------------------------------------------------ scaffold

    /** A new package from the engine's bundled sample of the adapter family: ids replaced, signature removed, version 0.1.0. */
    int scaffold(Args a) throws UsageException, IOException {
        String adapter = a.required("--adapter").toUpperCase(Locale.ROOT);
        String packageId = a.required("--package-id");
        if (!packageId.matches("[a-z0-9]+(\\.[a-z0-9\\-]+){2,5}")) {
            throw new UsageException("--package-id must look like vendor-or-family.vendor.model (lower case, dot separated)");
        }
        BundledDrivers bundled = new BundledDrivers(json);
        DriverPackage sample = bundled.forAdapter(adapter).orElseThrow(() -> new UsageException("no template for adapter " + adapter));
        ObjectNode pkg = (ObjectNode) json.readTree(bundled.source(sample.packageId(), sample.version()).orElseThrow());
        pkg.remove("signature");
        pkg.put("packageId", packageId);
        pkg.put("version", "0.1.0");
        if (a.has("--family")) {
            pkg.put("family", a.value("--family").toUpperCase(Locale.ROOT));
        }
        if (a.has("--model")) {
            pkg.putArray("models").add(a.value("--model"));
        }
        Path dir = Path.of(a.valueOr("--out", "."));
        Files.createDirectories(dir);
        Path file = dir.resolve(packageId + "-0.1.0.json");
        if (Files.exists(file)) {
            throw new UsageException(file + " exists");
        }
        Files.writeString(file, json.writerWithDefaultPrettyPrinter().writeValueAsString(pkg) + "\n", StandardCharsets.UTF_8);
        out.println("scaffolded " + file + " from template " + sample.ref() + " (adapter " + adapter + ")");
        out.println("next: edit protocol / parse / mappings / bindings, add golden datasets and test vectors, then 'validate' and 'conformance'");
        return 0;
    }

    // ------------------------------------------------------------------ validate / conformance

    int validate(Args a) throws UsageException, IOException {
        String text = read(a.positional(1, "package"));
        DriverValidator.Report r = DriverValidator.validate(parser, text, terminology(a), TARGETS);
        out.println((r.valid() ? "VALID" : "INVALID") + " " + r.packageId() + "@" + r.version() + (r.sha256() == null ? "" : " sha256=" + r.sha256()));
        r.errors().forEach(e -> out.println("  error   " + e));
        r.warnings().forEach(w -> out.println("  warning " + w));
        if (r.coverage() != null) {
            out.println("  coverage fields=" + r.coverage().fields() + " mapped=" + r.coverage().mapped() + " coded=" + r.coverage().coded()
                    + " quantitiesWithUcum=" + r.coverage().quantitiesWithUcum() + "/" + r.coverage().quantities());
        }
        return r.valid() ? 0 : 1;
    }

    /** Every test vector through the engine simulator (DryRun) against the golden datasets; evidence SHA-256 as the engine computes it. */
    int conformance(Args a) throws UsageException, IOException {
        DriverPackage pkg = parse(read(a.positional(1, "package")));
        GoldenDatasets golden = new GoldenDatasets(json);
        Path dir = a.has("--golden") ? Path.of(a.value("--golden")) : null;
        if (pkg.testVectors().isEmpty()) {
            out.println("FAILED " + pkg.ref() + ": the package has no test vectors");
            return 1;
        }
        StringBuilder evidence = new StringBuilder(pkg.sha256());
        boolean passed = true;
        for (DriverPackage.TestVector v : pkg.testVectors()) {
            byte[] input = input(golden, dir, v.input());
            DryRun.Result r = DryRun.run(adapters(), terminology(a), pkg, input, null, Map.of("X-File-Name", "sdk"), Instant.parse("2026-10-30T12:00:00Z"),
                    null, GateEnvironment.SIMULATOR, 120);
            List<String> diff = DryRun.diff(r, v.expect());
            passed &= diff.isEmpty();
            evidence.append('|').append(v.name()).append(':').append(diff.isEmpty() ? "PASS" : "FAIL");
            out.println("  " + (diff.isEmpty() ? "PASS" : "FAIL") + " " + v.name() + (diff.isEmpty() ? "" : " " + String.join("; ", diff)));
        }
        String sha = sha256Hex(evidence.toString().getBytes(StandardCharsets.UTF_8));
        out.println((passed ? "PASSED " : "FAILED ") + pkg.ref() + " evidenceSha256=" + sha);
        return passed ? 0 : 1;
    }

    byte[] input(GoldenDatasets golden, Path dir, String resource) throws UsageException, IOException {
        if (dir != null) {
            Path p = dir.resolve(resource.startsWith("golden/") ? resource.substring("golden/".length()) : resource).normalize();
            if (!p.startsWith(dir.normalize())) {
                throw new UsageException("test vector input outside the golden directory: " + resource);
            }
            if (Files.exists(p)) {
                return Files.readAllBytes(p);
            }
        }
        try {
            return golden.content(resource);
        } catch (RuntimeException e) {
            throw new UsageException("golden dataset not found: " + resource + (dir == null ? " (use --golden DIR for your own datasets)" : ""));
        }
    }

    // ------------------------------------------------------------------ keys and signatures

    /** Local development key pair (Ed25519): PKCS#8 private key and X.509 public key, base64. Never for production packages. */
    int keygen(Args a) throws UsageException, IOException, GeneralSecurityException {
        Path dir = Path.of(a.required("--out"));
        String keyId = a.valueOr("--key-id", "DEV-" + Instant.now().getEpochSecond());
        if (!keyId.startsWith("DEV-")) {
            throw new UsageException("development key ids start with DEV- (the E34 registry never trusts them)");
        }
        KeyPair pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("dev-key.pk8"), keyId + "\n" + Base64.getEncoder().encodeToString(pair.getPrivate().getEncoded()) + "\n");
        Files.writeString(dir.resolve("dev-key.pub"), keyId + "\n" + Base64.getEncoder().encodeToString(pair.getPublic().getEncoded()) + "\n");
        out.println("development key " + keyId + " written to " + dir + " (dev-key.pk8, dev-key.pub) – keep the .pk8 file out of version control");
        return 0;
    }

    int sign(Args a) throws UsageException, IOException, GeneralSecurityException, InterruptedException {
        Path file = Path.of(a.positional(1, "package"));
        String text = read(file.toString());
        DriverPackage pkg = parse(text);
        ObjectNode root = (ObjectNode) json.readTree(text);
        ObjectNode sig;
        if (a.has("--server")) {
            sig = signRemotely(a, text);
        } else {
            List<String> key = Files.readAllLines(Path.of(a.required("--dev-key")));
            if (key.size() < 2) {
                throw new UsageException("the dev key file must hold the key id and the base64 PKCS#8 key");
            }
            PrivateKey priv = KeyFactory.getInstance("Ed25519").generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(key.get(1).strip())));
            Signature s = Signature.getInstance("Ed25519");
            s.initSign(priv);
            s.update(pkg.canonicalJson().getBytes(StandardCharsets.UTF_8));
            sig = json.createObjectNode();
            sig.put("keyId", a.valueOr("--key-id", key.get(0).strip()));
            sig.put("algorithm", "Ed25519");
            sig.put("value", Base64.getEncoder().encodeToString(s.sign()));
        }
        root.set("signature", sig);
        Path target = Path.of(a.valueOr("--out", file.toString()));
        Files.writeString(target, json.writerWithDefaultPrettyPrinter().writeValueAsString(root) + "\n", StandardCharsets.UTF_8);
        out.println("signed " + pkg.ref() + " with " + sig.path("keyId").asString() + " (" + sig.path("algorithm").asString() + ") sha256=" + pkg.sha256()
                + " -> " + target);
        return 0;
    }

    /** E34 {@code POST /api/v1/device-security/packages/sign[?tenantCode=]} (secret scan, tenant's active key; bearer token of a signer). */
    ObjectNode signRemotely(Args a, String text) throws UsageException, IOException, InterruptedException {
        String server = a.value("--server");
        while (server.endsWith("/")) {
            server = server.substring(0, server.length() - 1);
        }
        if (!server.startsWith("https://") && !server.matches("http://(localhost|127\\.0\\.0\\.1)(:\\d+)?")) {
            throw new UsageException("--server must use https (plain http only for localhost)");
        }
        String token = a.required("--token");
        String tenant = a.has("--tenant") ? "?tenantCode=" + a.value("--tenant") : "";
        HttpRequest req = HttpRequest.newBuilder(URI.create(server + "/api/v1/device-security/packages/sign" + tenant)).timeout(Duration.ofSeconds(30))
                .header("Authorization", "Bearer " + token).header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(text))
                .build();
        HttpResponse<String> r = http().send(req, HttpResponse.BodyHandlers.ofString());
        if (r.statusCode() != 200) {
            throw new UsageException("signing refused: HTTP " + r.statusCode() + " " + r.body());
        }
        JsonNode body = json.readTree(r.body());
        if (!(body.path("signature") instanceof ObjectNode s)) {
            throw new UsageException("signing response without a signature member");
        }
        return s;
    }

    int verify(Args a) throws UsageException, IOException, GeneralSecurityException {
        String text = read(a.positional(1, "package"));
        DriverPackage pkg = parse(text);
        List<String> key = Files.readAllLines(Path.of(a.required("--public-key")));
        if (pkg.signature() == null) {
            out.println("UNSIGNED " + pkg.ref());
            return 1;
        }
        PublicKey pub = KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(key.get(1).strip())));
        Signature s = Signature.getInstance("Ed25519");
        s.initVerify(pub);
        s.update(pkg.canonicalJson().getBytes(StandardCharsets.UTF_8));
        boolean ok;
        try {
            ok = key.get(0).strip().equals(pkg.signature().keyId()) && s.verify(Base64.getDecoder().decode(pkg.signature().value()));
        } catch (IllegalArgumentException | java.security.SignatureException e) {
            ok = false;
        }
        out.println((ok ? "VALID " : "INVALID ") + pkg.ref() + " key " + pkg.signature().keyId());
        return ok ? 0 : 1;
    }

    // ------------------------------------------------------------------ diff

    /** Path-level diff of two package versions (signature excluded) and the semantic-version check. */
    int diff(Args a) throws UsageException, IOException {
        JsonNode older = json.readTree(read(a.positional(1, "old package")));
        JsonNode newer = json.readTree(read(a.positional(2, "new package")));
        ObjectNode o = (ObjectNode) json.readTree(CanonicalJson.of(older));
        ObjectNode n = (ObjectNode) json.readTree(CanonicalJson.of(newer));
        Map<String, String> left = new LinkedHashMap<>();
        Map<String, String> right = new LinkedHashMap<>();
        flatten(o, "", left);
        flatten(n, "", right);
        Set<String> paths = new TreeSet<>(left.keySet());
        paths.addAll(right.keySet());
        int changes = 0;
        for (String p : paths) {
            String l = left.get(p);
            String r = right.get(p);
            if (l == null) {
                out.println("+ " + p + " = " + r);
            } else if (r == null) {
                out.println("- " + p + " = " + l);
            } else if (!l.equals(r)) {
                out.println("~ " + p + ": " + l + " -> " + r);
            } else {
                continue;
            }
            changes++;
        }
        String ov = older.path("version").asString("");
        String nv = newer.path("version").asString("");
        boolean newerVersion;
        try {
            newerVersion = SemVer.parse(nv).compareTo(SemVer.parse(ov)) > 0;
        } catch (RuntimeException e) {
            newerVersion = false;
        }
        out.println(changes + " change(s); version " + ov + " -> " + nv + (newerVersion ? "" : " (WARNING: the new version must be greater – versions are immutable)"));
        return 0;
    }

    static void flatten(JsonNode node, String path, Map<String, String> out) {
        if (node.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> it = node.properties().iterator();
            while (it.hasNext()) {
                Map.Entry<String, JsonNode> e = it.next();
                flatten(e.getValue(), path + "/" + e.getKey(), out);
            }
        } else if (node.isArray()) {
            for (int i = 0; i < node.size(); i++) {
                flatten(node.get(i), path + "/" + i, out);
            }
        } else {
            out.put(path.isEmpty() ? "/" : path, node.toString());
        }
    }

    // ------------------------------------------------------------------ helpers

    DriverPackage parse(String text) throws UsageException {
        try {
            return parser.parse(text);
        } catch (DriverException e) {
            throw new UsageException("package does not parse: " + String.join("; ", e.errors()));
        }
    }

    Terminology terminology(Args a) throws UsageException, IOException {
        if (terminology == null) {
            String sql;
            if (a.has("--terminology")) {
                sql = Files.readString(Path.of(a.value("--terminology")), StandardCharsets.UTF_8);
            } else {
                try (InputStream in = DeviceSdk.class.getClassLoader().getResourceAsStream("sdk/V22__e31_device_engine.sql")) {
                    if (in == null) {
                        throw new UsageException("terminology not bundled – pass --terminology <V22__e31_device_engine.sql>");
                    }
                    sql = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                }
            }
            terminology = MigrationTerminology.fromSql(sql);
        }
        return terminology;
    }

    Adapters adapters() {
        if (adapters == null) {
            adapters = new Adapters(FhirContext.forR4(), json);
        }
        return adapters;
    }

    HttpClient http() {
        if (http == null) {
            http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NEVER).build();
        }
        return http;
    }

    static String read(String file) throws IOException {
        return Files.readString(Path.of(file), StandardCharsets.UTF_8);
    }

    static String sha256Hex(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Minimal argument parser: positional arguments and {@code --name value} options. */
    static final class Args {
        private final List<String> positional = new ArrayList<>();
        private final Map<String, String> options = new LinkedHashMap<>();

        Args(String[] args) {
            for (int i = 0; i < args.length; i++) {
                if (args[i].startsWith("--") && i + 1 < args.length) {
                    options.put(args[i], args[++i]);
                } else {
                    positional.add(args[i]);
                }
            }
        }

        boolean has(String name) {
            return options.containsKey(name);
        }

        String value(String name) {
            return options.get(name);
        }

        String valueOr(String name, String fallback) {
            return options.getOrDefault(name, fallback);
        }

        String required(String name) throws UsageException {
            String v = options.get(name);
            if (v == null || v.isBlank()) {
                throw new UsageException(name + " is required");
            }
            return v;
        }

        String positional(int index, String what) throws UsageException {
            if (positional.size() <= index) {
                throw new UsageException(what + " file is required");
            }
            return positional.get(index);
        }
    }
}
