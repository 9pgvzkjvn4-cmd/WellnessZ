package io.intimed.cloud;

import io.intimed.common.Hashing;
import io.intimed.common.Json;
import io.intimed.common.Paths2;
import io.intimed.security.secrets.AzureIdentity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.function.Supplier;
import java.util.stream.Stream;

/**
 * Off-cluster, write-once archive of sealed evidence packs and the SIEM audit stream to Azure Blob Storage (IM-E08-BL-005
 * backup verification, IM-E06-BL-005 evidence retention, SC-20): every new {@code evidence/*.zip} and every new segment of
 * {@code siem/audit.jsonl} is uploaded with {@code If-None-Match: *} (never overwrites) into containers that Terraform
 * protects with a time-based immutability policy (WORM). Authentication by Entra workload identity – no storage key.
 * Disabled unless {@code intimed.archive.blob.account-url} is set (e.g. https://stintimeduatqc.blob.core.windows.net).
 */
@Component
public class BlobArchiver {
    private static final Logger LOG = LoggerFactory.getLogger(BlobArchiver.class);
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build();
    private final String account;
    private final String evidenceContainer, auditContainer, prefix;
    private Supplier<String> token;

    public BlobArchiver(@Value("${intimed.archive.blob.account-url:}") String account, @Value("${intimed.archive.blob.evidence-container:evidence}") String ev,
                        @Value("${intimed.archive.blob.audit-container:audit}") String au, @Value("${intimed.archive.blob.prefix:${INTIMED_ENVIRONMENT:LOCAL}}") String prefix) {
        this.account = account == null ? "" : account.replaceAll("/$", "");
        this.evidenceContainer = ev;
        this.auditContainer = au;
        this.prefix = prefix.toLowerCase().replaceAll("[^a-z0-9-]", "");
        if (!this.account.isEmpty()) {
            URI u = URI.create(this.account);
            if (!"https".equals(u.getScheme()) || !u.getHost().endsWith(".blob.core.windows.net"))
                throw new IllegalStateException("intimed.archive.blob.account-url must be https://<account>.blob.core.windows.net");
            AzureIdentity id = new AzureIdentity();
            this.token = () -> id.token("https://storage.azure.com/.default");
        }
    }

    /** Test hook: stub endpoint (http allowed) and static token. */
    public static BlobArchiver forTest(String base, Supplier<String> token) {
        BlobArchiver b = new BlobArchiver("", "evidence", "audit", "test");
        return b.withEndpoint(base, token);
    }

    private String endpoint;

    private BlobArchiver withEndpoint(String base, Supplier<String> tok) {
        this.endpoint = base;
        this.token = tok;
        return this;
    }

    public boolean enabled() { return endpoint != null || !account.isEmpty(); }

    @Scheduled(fixedDelayString = "${intimed.archive.interval-ms:300000}", initialDelayString = "${intimed.archive.initial-delay-ms:120000}")
    public void scheduled() {
        if (!enabled()) return;
        try { archive(); } catch (RuntimeException e) { LOG.warn("blob archive run failed: {}", e.getMessage()); }
    }

    /** One archive pass; returns counts. Idempotent – state in {@code <home>/archive/state.json}. */
    public synchronized ObjectNode archive() {
        Path stateFile = Paths2.dir("archive").resolve("state.json");
        ObjectNode state;
        try { state = Files.exists(stateFile) ? (ObjectNode) Json.parse(Files.readString(stateFile)) : Json.obj(); }
        catch (IOException e) { state = Json.obj(); }
        ObjectNode done = state.withObject("evidence");
        int ev = 0, au = 0;
        try (Stream<Path> s = Files.list(Paths2.dir("evidence"))) {
            for (Path p : (Iterable<Path>) s.filter(f -> f.getFileName().toString().endsWith(".zip")).sorted()::iterator) {
                String name = p.getFileName().toString();
                if (done.has(name)) continue;
                byte[] b = Files.readAllBytes(p);
                String sha = Hashing.sha256(b);
                put(evidenceContainer, prefix + "/" + name, b, "application/zip", sha);
                done.put(name, sha);
                ev++;
            }
        } catch (IOException e) { throw new IllegalStateException("evidence directory unreadable"); }
        Path siem = Paths2.dir("siem").resolve("audit.jsonl");
        long offset = state.path("auditOffset").asLong(0);
        if (Files.exists(siem)) {
            try (RandomAccessFile f = new RandomAccessFile(siem.toFile(), "r")) {
                long len = f.length();
                if (len < offset) offset = 0; // rotated
                if (len > offset) {
                    byte[] seg = new byte[(int) Math.min(len - offset, 64L * 1024 * 1024)];
                    f.seek(offset);
                    f.readFully(seg);
                    int cut = lastNewline(seg);
                    if (cut > 0) {
                        byte[] part = java.util.Arrays.copyOf(seg, cut);
                        String blob = prefix + "/" + LocalDate.now(ZoneOffset.UTC) + "/audit-" + String.format("%015d", offset) + ".jsonl";
                        put(auditContainer, blob, part, "application/x-ndjson", Hashing.sha256(part));
                        offset += cut;
                        au++;
                    }
                }
            } catch (IOException e) { throw new IllegalStateException("SIEM file unreadable"); }
        }
        state.put("auditOffset", offset).put("lastRun", Instant.now().toString());
        try { Files.writeString(stateFile, Json.write(state)); } catch (IOException e) { throw new IllegalStateException("archive state not writable"); }
        return Json.obj().put("evidenceUploaded", ev).put("auditSegmentsUploaded", au).put("auditOffset", offset);
    }

    private static int lastNewline(byte[] b) {
        for (int i = b.length - 1; i >= 0; i--) if (b[i] == '\n') return i + 1;
        return 0;
    }

    private void put(String container, String blob, byte[] body, String type, String sha) {
        String base = endpoint != null ? endpoint : account;
        HttpRequest r = HttpRequest.newBuilder(URI.create(base + "/" + container + "/" + blob)).timeout(Duration.ofSeconds(30))
                .header("Authorization", "Bearer " + token.get()).header("x-ms-version", "2023-11-03").header("x-ms-blob-type", "BlockBlob")
                .header("If-None-Match", "*").header("Content-Type", type).header("x-ms-meta-sha256", sha)
                .PUT(HttpRequest.BodyPublishers.ofByteArray(body)).build();
        try {
            HttpResponse<String> res = http.send(r, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            // 409 BlobAlreadyExists: an earlier pass uploaded it before the state was saved – write-once means done.
            if (res.statusCode() != 201 && res.statusCode() != 409) throw new IllegalStateException("blob upload " + container + "/" + blob + " returned HTTP " + res.statusCode());
        } catch (IOException | InterruptedException e) {
            throw new IllegalStateException("blob storage unavailable: " + e.getClass().getSimpleName());
        }
    }
}
