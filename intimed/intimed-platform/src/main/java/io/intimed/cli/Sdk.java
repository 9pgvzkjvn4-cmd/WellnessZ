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
 * keygen · sign · sign-all · validate · test · simulate · enrol · send · gateway (encrypted store-and-forward) · totp · smoke.
 * Runs from the platform jar without starting the server: {@code java -jar intimed-platform.jar sdk <command> …}.
 */
public final class Sdk {
    private Sdk() { }

    /**
     * Post-deployment smoke test ({@code helm test}): readiness on the management port and the console's TLS endpoint
     * (protocol ≥ TLS 1.2, /api/public/info answers). Certificate trust of the console is verified by Application Gateway
     * and by security-kit script 02 – this check proves reachability and protocol only.
     */
    static int smoke(String host) throws Exception {
        java.net.http.HttpClient plain = java.net.http.HttpClient.newBuilder().version(java.net.http.HttpClient.Version.HTTP_1_1).connectTimeout(java.time.Duration.ofSeconds(5)).build();
        var ready = plain.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://" + host + ":9444/actuator/health/readiness")).GET().build(),
                java.net.http.HttpResponse.BodyHandlers.ofString());
        System.out.println("readiness: HTTP " + ready.statusCode() + " " + ready.body());
        javax.net.ssl.SSLContext ctx = javax.net.ssl.SSLContext.getInstance("TLS");
        ctx.init(null, new javax.net.ssl.TrustManager[]{new javax.net.ssl.X509TrustManager() {
            public void checkClientTrusted(java.security.cert.X509Certificate[] c, String a) { }
            public void checkServerTrusted(java.security.cert.X509Certificate[] c, String a) { }
            public java.security.cert.X509Certificate[] getAcceptedIssuers() { return new java.security.cert.X509Certificate[0]; }
        }}, null);
        javax.net.ssl.SSLParameters p = new javax.net.ssl.SSLParameters();
        p.setEndpointIdentificationAlgorithm(null);
        p.setProtocols(new String[]{"TLSv1.3", "TLSv1.2"});
        java.net.http.HttpClient tls = java.net.http.HttpClient.newBuilder().version(java.net.http.HttpClient.Version.HTTP_1_1).sslContext(ctx).sslParameters(p).connectTimeout(java.time.Duration.ofSeconds(5)).build();
        var info = tls.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create("https://" + host + ":9443/api/public/info")).GET().build(),
                java.net.http.HttpResponse.BodyHandlers.ofString());
        String proto = info.sslSession().map(javax.net.ssl.SSLSession::getProtocol).orElse("none");
        System.out.println("console: HTTP " + info.statusCode() + " " + proto + " " + info.body());
        boolean ok = ready.statusCode() == 200 && info.statusCode() == 200 && info.body().contains("\"product\"") && proto.startsWith("TLSv1.");
        System.out.println(ok ? "SMOKE PASS" : "SMOKE FAIL");
        return ok ? 0 : 1;
    }

    /**
     * One-time PostgreSQL bootstrap (Helm pre-install hook): with the server administrator, create or rotate the
     * application's least-privilege login role and grant it the rights Flyway needs on schema public – nothing more
     * (no superuser, no azure_pg_admin, no CREATEROLE). Passwords come from the environment (Key Vault via CSI), never
     * from arguments, and must be ≥ 24 alphanumeric characters.
     * {@code sdk db-bootstrap --url jdbc:postgresql://host:5432/intimed?sslmode=verify-full --admin intimedadmin --app-user intimed}
     */
    static int dbBootstrap(Map<String, String> o, Map<String, String> env) throws Exception {
        String url = req(o, "url"), admin = req(o, "admin"), app = o.getOrDefault("app-user", "intimed");
        String adminPw = env.containsKey("INTIMED_DB_ADMIN_PASSWORD") ? env.get("INTIMED_DB_ADMIN_PASSWORD") : io.intimed.common.Secrets.get("INTIMED_DB_ADMIN_PASSWORD"),
                appPw = env.containsKey("INTIMED_DB_PASSWORD") ? env.get("INTIMED_DB_PASSWORD") : io.intimed.common.Secrets.get("INTIMED_DB_PASSWORD");
        if (!app.matches("[a-z][a-z0-9_]{2,30}")) throw new IllegalArgumentException("invalid application role name");
        if (adminPw == null || adminPw.isBlank()) throw new IllegalArgumentException("INTIMED_DB_ADMIN_PASSWORD is not set");
        if (appPw == null || !appPw.matches("[A-Za-z0-9]{24,128}")) throw new IllegalArgumentException("INTIMED_DB_PASSWORD must be 24–128 alphanumeric characters");
        if (!url.startsWith("jdbc:postgresql://")) throw new IllegalArgumentException("--url must be a jdbc:postgresql URL");
        try (java.sql.Connection c = java.sql.DriverManager.getConnection(url, admin, adminPw); java.sql.Statement st = c.createStatement()) {
            boolean exists;
            try (java.sql.PreparedStatement ps = c.prepareStatement("select 1 from pg_roles where rolname = ?")) {
                ps.setString(1, app);
                try (java.sql.ResultSet rs = ps.executeQuery()) { exists = rs.next(); }
            }
            String pw = "'" + appPw + "'"; // validated alphanumeric – no quoting issues
            st.execute((exists ? "ALTER ROLE " : "CREATE ROLE ") + app + " LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT PASSWORD " + pw);
            String db = c.getCatalog();
            st.execute("GRANT CONNECT, TEMPORARY ON DATABASE \"" + db.replace("\"", "") + "\" TO " + app);
            st.execute("GRANT USAGE, CREATE ON SCHEMA public TO " + app);
            st.execute("REVOKE CREATE ON SCHEMA public FROM PUBLIC");
            System.out.println("db-bootstrap: role " + app + (exists ? " rotated" : " created") + " on database " + db + " (schema public: USAGE, CREATE)");
        }
        return 0;
    }

    /**
     * SC-10 in-cluster check (security kit script 10 on AKS): connects with the pod's own database credentials and
     * verifies every PHI-bearing document is application-encrypted (enc:v1) and no subject identifier pattern is stored
     * in clear text in any document. Prints counts only.
     */
    static int phiCheck(Map<String, String> env) throws Exception {
        String url = env.get("INTIMED_DB_URL");
        if (url == null) throw new IllegalArgumentException("INTIMED_DB_URL is not set (run inside the IntiMed pod)");
        String kinds = io.intimed.store.DocumentStore.SENSITIVE.stream().map(k -> "'" + k.replace("'", "") + "'").collect(java.util.stream.Collectors.joining(","));
        try (java.sql.Connection c = java.sql.DriverManager.getConnection(url, env.getOrDefault("INTIMED_DB_USER", "intimed"),
                env.containsKey("INTIMED_DB_PASSWORD") ? env.get("INTIMED_DB_PASSWORD") : io.intimed.common.Secrets.get("INTIMED_DB_PASSWORD"));
             java.sql.Statement st = c.createStatement()) {
            long total = count(st, "select count(*) from documents where kind in (" + kinds + ")");
            long plain = count(st, "select count(*) from documents where kind in (" + kinds + ") and data not like 'enc:v1:%'");
            long leak = count(st, "select count(*) from documents where data ~ '\\m(MRN|EMP)-[0-9]{6}\\M'");
            boolean ok = plain == 0 && leak == 0;
            System.out.println("phi-check total=" + total + " notEncrypted=" + plain + " clearIdentifiers=" + leak + " result=" + (ok ? "PASS" : "FAIL"));
            return ok ? 0 : 1;
        }
    }

    private static long count(java.sql.Statement st, String sql) throws java.sql.SQLException {
        try (java.sql.ResultSet rs = st.executeQuery(sql)) { rs.next(); return rs.getLong(1); }
    }

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
            case "smoke" -> smoke(args.length > 1 ? args[1] : "intimed-headless");
            case "db-bootstrap" -> dbBootstrap(o, System.getenv());
            case "phi-check" -> phiCheck(System.getenv());
            case "soak" -> soak(o);
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

    /**
     * Soak / performance test over the real device transport (IM-E01-BL-030): sends simulated payloads of a driver with
     * an enrolled endpoint identity (mutual TLS) at a target rate for a duration, from parallel virtual threads, and
     * reports throughput, ack codes and latency percentiles as JSON ({@code --out report.json}).
     * {@code sdk soak --identity id/ --driver d.json --mllp devices.uat…:2575 --rate 50 --duration 600 --concurrency 16}
     */
    static int soak(Map<String, String> o) throws Exception {
        SSLContext ssl = identity(Path.of(req(o, "identity")));
        DriverPackage d = load(Path.of(req(o, "driver")));
        double rate = Double.parseDouble(o.getOrDefault("rate", "20"));
        long duration = Long.parseLong(o.getOrDefault("duration", "60"));
        int conc = Integer.parseInt(o.getOrDefault("concurrency", "8"));
        java.util.concurrent.ConcurrentLinkedQueue<Long> lat = new java.util.concurrent.ConcurrentLinkedQueue<>();
        java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicLong> acks = new java.util.concurrent.ConcurrentHashMap<>();
        java.util.concurrent.Semaphore inflight = new java.util.concurrent.Semaphore(conc);
        long start = System.nanoTime(), end = start + duration * 1_000_000_000L, n = 0;
        try (var ex = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            while (System.nanoTime() < end) {
                long due = start + (long) (n * 1_000_000_000L / rate);
                long wait = due - System.nanoTime();
                if (wait > 0) Thread.sleep(wait / 1_000_000, (int) (wait % 1_000_000));
                inflight.acquire();
                final long i = n++;
                ex.submit(() -> {
                    long t0 = System.nanoTime();
                    String key;
                    try {
                        String p = Simulator.render(d, Simulator.vary(Simulator.values(d), i), "SOAK-" + (i % 500), Instant.now().truncatedTo(ChronoUnit.SECONDS).plusMillis(i % 1000),
                                d.tests().path("serial").asString("SIM"));
                        String ack = deliver(ssl, o, d, p);
                        key = ack.contains("|AA|") || ack.contains("\"FILED\"") || ack.contains("FILED") ? "ACCEPTED" : ack.contains("DUPLICATE") ? "DUPLICATE" : "OTHER";
                    } catch (Exception e) { key = "ERROR:" + e.getClass().getSimpleName(); }
                    finally { inflight.release(); }
                    lat.add((System.nanoTime() - t0) / 1_000_000);
                    acks.computeIfAbsent(key, k -> new java.util.concurrent.atomic.AtomicLong()).incrementAndGet();
                });
            }
        }
        long[] l = lat.stream().mapToLong(Long::longValue).sorted().toArray();
        double secs = (System.nanoTime() - start) / 1e9;
        tools.jackson.databind.node.ObjectNode r = io.intimed.common.Json.obj().put("driver", d.ref()).put("targetRate", rate).put("durationSec", Math.round(secs))
                .put("sent", l.length).put("throughputPerSec", Math.round(l.length / secs * 10) / 10.0)
                .put("p50Ms", pct(l, 50)).put("p95Ms", pct(l, 95)).put("p99Ms", pct(l, 99)).put("maxMs", l.length == 0 ? 0 : l[l.length - 1]);
        acks.forEach((k, v) -> r.withObject("acks").put(k, v.get()));
        String json = io.intimed.common.Json.pretty(r);
        System.out.println(json);
        if (o.containsKey("out")) Files.writeString(Path.of(o.get("out")), json);
        long errors = acks.entrySet().stream().filter(e -> e.getKey().startsWith("ERROR")).mapToLong(e -> e.getValue().get()).sum();
        double maxErr = Double.parseDouble(o.getOrDefault("max-error-rate", "0.001"));
        return l.length > 0 && errors <= maxErr * l.length ? 0 : 1;
    }

    private static long pct(long[] s, int p) { return s.length == 0 ? 0 : s[Math.min(s.length - 1, (int) Math.ceil(p / 100.0 * s.length) - 1)]; }

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
