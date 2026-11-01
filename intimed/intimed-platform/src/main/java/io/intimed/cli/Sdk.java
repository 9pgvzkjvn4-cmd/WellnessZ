package io.intimed.cli;

import io.intimed.common.Hashing;
import io.intimed.common.Json;
import io.intimed.engine.driver.DriverPackage;
import io.intimed.engine.driver.DriverValidator;
import io.intimed.engine.driver.Signatures;
import io.intimed.engine.parse.ParserRegistry;
import io.intimed.engine.sim.Simulator;
import io.intimed.engine.transport.TransportClient;
import io.intimed.security.auth.Totp;
import io.intimed.security.crypto.Aead;
import io.intimed.security.pki.LocalPki;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.bouncycastle.pkcs.PKCS10CertificationRequest;
import org.bouncycastle.pkcs.jcajce.JcaPKCS10CertificationRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import javax.crypto.spec.SecretKeySpec;
import javax.net.ssl.SSLContext;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * mdi-sdk – driver SDK, device simulator and edge gateway CLI (IM-E01-F03 / IM-E01-BL-011, IM-E01-BL-025, IM-E08-BL-008):
 * keygen · sign · sign-all · validate · test · simulate · enrol · send · gateway (encrypted store-and-forward) · totp.
 * Runs from the platform jar without starting the server: {@code java -jar intimed-platform.jar sdk <command> …}.
 */
public final class Sdk {
    private Sdk() { }

    public static int run(String[] args) throws Exception {
        if (args.length == 0) return usage();
        Map<String, String> o = opts(args);
        return switch (args[0]) {
            case "keygen" -> keygen(Path.of(o.getOrDefault("out", ".")));
            case "sign" -> sign(Path.of(req(o, "key")), Path.of(args[args.length - 1]));
            case "sign-all" -> {
                try (Stream<Path> s = Files.list(Path.of(args[args.length - 1]))) {
                    for (Path p : s.filter(f -> f.toString().endsWith(".json")).sorted().toList()) sign(Path.of(req(o, "key")), p);
                }
                yield 0;
            }
            case "validate" -> validate(Path.of(args[args.length - 1]));
            case "test" -> test(Path.of(args[args.length - 1]));
            case "simulate" -> { System.out.println(render(Path.of(args[args.length - 1]), o.getOrDefault("subject", "TEST-0001"))); yield 0; }
            case "enrol" -> enrol(req(o, "server"), req(o, "token"), Path.of(req(o, "ca")), Path.of(o.getOrDefault("out", "identity")), o.getOrDefault("name", "gateway"));
            case "send" -> send(o);
            case "gateway" -> gateway(o);
            case "totp" -> { System.out.println(Totp.now(req(o, "secret"))); yield 0; }
            default -> usage();
        };
    }

    static int usage() {
        System.out.println("""
                IntiMed mdi-sdk – usage: java -jar intimed-platform.jar sdk <command> [options]
                  keygen   --out DIR                                  Ed25519 driver-publisher key pair
                  sign     --key FILE  DRIVER.json                    signs a driver package (writes DRIVER.json.sig)
                  sign-all --key FILE  DIR                            signs every *.json in DIR
                  validate DRIVER.json                                structural validation
                  test     DRIVER.json                                parses the golden session and every negative case
                  simulate [--subject S] DRIVER.json                  prints a simulated payload
                  enrol    --server URL --token enr_… --ca ROOT.pem [--out DIR]   CSR enrolment (key never leaves this machine)
                  send     --identity DIR --config CFG --driver DRIVER.json [--subject S] [--mllp host:port | --ingress host:port]
                  gateway  --identity DIR --config CFG --driver DRIVER.json [--interval 10] [--count 0] [--queue DIR] [--mllp|--ingress host:port]
                  totp     --secret BASE32                            current TOTP code
                """);
        return 2;
    }

    static Map<String, String> opts(String[] a) {
        Map<String, String> m = new HashMap<>();
        for (int i = 1; i < a.length; i++) if (a[i].startsWith("--") && i + 1 < a.length && !a[i + 1].startsWith("--")) { m.put(a[i].substring(2), a[i + 1]); i++; }
        return m;
    }

    static String req(Map<String, String> o, String k) {
        String v = o.get(k);
        if (v == null) throw new IllegalArgumentException("missing --" + k);
        return v;
    }

    static int keygen(Path out) throws Exception {
        Files.createDirectories(out);
        KeyPair kp = Signatures.generate();
        Path k = out.resolve("publisher-ed25519.key");
        Files.writeString(k, Signatures.b64(kp.getPrivate().getEncoded()));
        try { Files.setPosixFilePermissions(k, java.nio.file.attribute.PosixFilePermissions.fromString("rw-------")); } catch (UnsupportedOperationException ignored) { }
        Files.writeString(out.resolve("publisher-ed25519.pub"), Signatures.b64(kp.getPublic().getEncoded()));
        System.out.println("Private key: " + k + " (keep offline / in a vault)\nPublic key:  " + out.resolve("publisher-ed25519.pub") + " (register in Security → trusted publishers)");
        return 0;
    }

    static int sign(Path key, Path driver) throws Exception {
        JsonNode pkg = Json.parse(Files.readString(driver));
        List<String> problems = DriverValidator.validate(pkg);
        if (!problems.isEmpty()) { System.err.println(driver.getFileName() + ": INVALID " + problems); return 1; }
        if (!io.intimed.security.secrets.SecretScanner.scan(Json.write(pkg)).isEmpty()) { System.err.println(driver.getFileName() + ": contains a secret – refused"); return 1; }
        Files.writeString(Path.of(driver + ".sig"), Signatures.sign(Signatures.privateKey(Files.readString(key)), pkg));
        System.out.println("signed " + driver.getFileName());
        return 0;
    }

    static int validate(Path driver) throws Exception {
        List<String> p = DriverValidator.validate(Json.parse(Files.readString(driver)));
        System.out.println(p.isEmpty() ? "VALID" : "INVALID: " + String.join("; ", p));
        return p.isEmpty() ? 0 : 1;
    }

    static DriverPackage load(Path driver) throws Exception { return new DriverPackage(Json.parse(Files.readString(driver)), "LOCAL", "UNVERIFIED", "local"); }

    static String render(Path driver, String subject) throws Exception {
        DriverPackage d = load(driver);
        return Simulator.render(d, Simulator.values(d), subject, Instant.now().truncatedTo(ChronoUnit.SECONDS), d.tests().path("serial").asString("SIM"));
    }

    /** Driver CI: golden session parses and maps every semantic key; negative cases are rejected. */
    static int test(Path driver) throws Exception {
        DriverPackage d = load(driver);
        ParserRegistry reg = new ParserRegistry();
        String nominal = render(driver, "TEST-0001");
        var sets = reg.get(d.grammar().get("type").asString()).parse(d.grammar(), nominal);
        int missing = 0;
        for (JsonNode s : d.semantics()) if (!sets.get(0).values.containsKey(s.get("key").asString())) { System.out.println("  key not parsed: " + s.get("key").asString()); missing++; }
        boolean malformedRejected;
        try { reg.get(d.grammar().get("type").asString()).parse(d.grammar(), Simulator.negative("MALFORMED", d, nominal, Simulator.values(d), "T", Instant.now(), "S")); malformedRejected = false; }
        catch (RuntimeException e) { malformedRejected = true; }
        System.out.println(d.ref() + ": golden " + (missing == 0 ? "OK" : missing + " keys missing") + ", malformed " + (malformedRejected ? "rejected" : "ACCEPTED (fix grammar)"));
        return missing == 0 && malformedRejected ? 0 : 1;
    }

    static int enrol(String server, String token, Path ca, Path out, String name) throws Exception {
        Files.createDirectories(out);
        KeyPair kp = LocalPki.ecKeyPair();
        PKCS10CertificationRequest csr = new JcaPKCS10CertificationRequestBuilder(new X500Name("CN=" + name), kp.getPublic())
                .build(new JcaContentSignerBuilder(LocalPki.SIG).build(kp.getPrivate()));
        X509Certificate root = LocalPki.parseCert(Files.readString(ca));
        SSLContext trust = LocalPki.clientSslContext(root, (java.security.cert.X509Certificate[]) null, null);
        String body = Json.write(Json.obj().put("token", token).put("csr", LocalPki.toPem(csr)));
        var r = TransportClient.https(server + "/api/enrol", trust, "POST", body, Map.of("Content-Type", "application/json"));
        if (r.statusCode() != 200) { System.err.println("enrolment refused: HTTP " + r.statusCode() + " " + r.body()); return 1; }
        JsonNode j = Json.parse(r.body());
        Path key = out.resolve("endpoint.key");
        Files.writeString(key, Base64.getEncoder().encodeToString(kp.getPrivate().getEncoded()));
        try { Files.setPosixFilePermissions(key, java.nio.file.attribute.PosixFilePermissions.fromString("rw-------")); } catch (UnsupportedOperationException ignored) { }
        Files.writeString(out.resolve("endpoint.crt"), j.get("certificate").asString() + j.get("chain").asString());
        Files.writeString(out.resolve("root-ca.pem"), j.get("rootCa").asString());
        Files.writeString(out.resolve("endpoint.json"), Json.pretty(Json.obj().put("endpointId", j.get("endpointId").asString()).put("tenant", j.get("tenant").asString())));
        System.out.println("enrolled endpoint " + j.get("endpointId").asString() + " (tenant " + j.get("tenant").asString() + "); identity in " + out);
        return 0;
    }

    static SSLContext identity(Path dir) throws Exception {
        PrivateKey k = KeyFactory.getInstance("EC").generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(Files.readString(dir.resolve("endpoint.key")).trim())));
        java.util.List<X509Certificate> chain = new java.util.ArrayList<>();
        for (var c : java.security.cert.CertificateFactory.getInstance("X.509").generateCertificates(Files.newInputStream(dir.resolve("endpoint.crt")))) chain.add((X509Certificate) c);
        X509Certificate root = LocalPki.parseCert(Files.readString(dir.resolve("root-ca.pem")));
        chain.removeIf(c -> c.equals(root));
        return LocalPki.clientSslContext(root, chain.toArray(new X509Certificate[0]), k);
    }

    static String deliver(SSLContext ssl, Map<String, String> o, DriverPackage d, String payload) throws Exception {
        String option = d.options().get(0);
        if ("P4".equals(option)) {
            String[] hp = o.getOrDefault("mllp", "localhost:2575").split(":");
            return TransportClient.mllp(hp[0], Integer.parseInt(hp[1]), ssl, payload).response().replace('\r', '\n');
        }
        String[] hp = o.getOrDefault("ingress", "localhost:8443").split(":");
        ObjectNode env = Json.obj().put("configId", req(o, "config")).put("option", option).put("payload", payload);
        return TransportClient.ingress(hp[0], Integer.parseInt(hp[1]), ssl, env).response();
    }

    static int send(Map<String, String> o) throws Exception {
        DriverPackage d = load(Path.of(req(o, "driver")));
        String payload = o.containsKey("file") ? Files.readString(Path.of(o.get("file"))) : render(Path.of(req(o, "driver")), o.getOrDefault("subject", "MRN-100299"));
        System.out.println(deliver(identity(Path.of(req(o, "identity"))), o, d, payload));
        return 0;
    }

    /** Edge gateway: streams simulated readings; on failure queues them AES-GCM encrypted and flushes in order later. */
    static int gateway(Map<String, String> o) throws Exception {
        Path idDir = Path.of(req(o, "identity"));
        SSLContext ssl = identity(idDir);
        DriverPackage d = load(Path.of(req(o, "driver")));
        Path queue = Path.of(o.getOrDefault("queue", idDir.resolve("queue").toString()));
        Files.createDirectories(queue);
        SecretKeySpec qk = new SecretKeySpec(Hashing.sha256(Files.readString(idDir.resolve("endpoint.key"))).substring(0, 32).getBytes(StandardCharsets.UTF_8), "AES");
        int interval = Integer.parseInt(o.getOrDefault("interval", "10"));
        int count = Integer.parseInt(o.getOrDefault("count", "0"));
        String subject = o.getOrDefault("subject", "MRN-100299");
        for (long i = 0; count == 0 || i < count; i++) {
            try (Stream<Path> s = Files.list(queue)) {
                for (Path q : s.sorted().toList()) {
                    try { deliver(ssl, o, d, Aead.decryptString(qk, Files.readString(q), "queue")); Files.delete(q); System.out.println("flushed " + q.getFileName()); }
                    catch (Exception e) { break; }
                }
            }
            String p = Simulator.render(d, Simulator.vary(Simulator.values(d), i), subject, Instant.now().truncatedTo(ChronoUnit.SECONDS), d.tests().path("serial").asString("SIM"));
            try {
                System.out.println(Instant.now() + " " + deliver(ssl, o, d, p).replace('\n', ' '));
            } catch (Exception e) {
                Path f = queue.resolve(System.currentTimeMillis() + ".msg");
                Files.writeString(f, Aead.encryptString(qk, p, "queue"));
                System.out.println("platform unreachable – queued encrypted " + f.getFileName());
            }
            Thread.sleep(interval * 1000L);
        }
        return 0;
    }
}
