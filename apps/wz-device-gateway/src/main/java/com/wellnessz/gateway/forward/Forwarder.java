package com.wellnessz.gateway.forward;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.wellnessz.gateway.config.GatewayProperties;
import com.wellnessz.gateway.core.Backoff;
import com.wellnessz.gateway.core.CoreClient;
import com.wellnessz.gateway.core.CoreResponse;
import com.wellnessz.gateway.queue.EncryptedJournal;
import com.wellnessz.gateway.queue.ItemKind;
import com.wellnessz.gateway.queue.QueueItem;

import io.micrometer.core.instrument.MeterRegistry;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Drains the encrypted queue to the core (contract §16.2): single calls while online, {@code POST /device-ingest/batch}
 * (≤ 100 items, ≤ 10 MB) once a backlog exists. Every item keeps the {@code Idempotency-Key} it was given at capture, so
 * a replay after a lost receipt is answered {@code DUPLICATE} by the core – no loss, no duplication. An item is removed
 * only after a 2xx receipt; deterministic 4xx answers move it to the local dead-letter file; 401
 * {@code endpoint_isolated} / {@code endpoint_revoked} stops forwarding and quarantines the queue; I/O errors, 408, 429
 * and 5xx back off exponentially (honouring {@code Retry-After}).
 */
public class Forwarder {

    private static final Logger log = LoggerFactory.getLogger(Forwarder.class);
    public static final String BATCH_PATH = "/device-ingest/batch";
    public static final String ATTACHMENT_PATH = "/device-ingest/attachments";

    /** Result of delivering one item. */
    public enum Outcome { DELIVERED, DEAD_LETTERED, RETRY, STOPPED }

    private final EncryptedJournal journal;
    private final CoreClient core;
    private final GatewayProperties.Forwarder cfg;
    private final Clock clock;
    private final Backoff backoff;
    private final MeterRegistry metrics;
    private final JsonMapper json = JsonMapper.builder().build();
    private final ReentrantLock sendLock = new ReentrantLock();

    private volatile boolean online;
    private volatile boolean everOffline;
    private volatile Instant nextAttemptAt = Instant.EPOCH;
    private volatile Instant lastSuccessAt;
    private volatile String lastError;
    private volatile Runnable onIsolated = () -> { };

    public Forwarder(EncryptedJournal journal, CoreClient core, GatewayProperties.Forwarder cfg, Clock clock,
                     Backoff backoff, MeterRegistry metrics) {
        this.journal = journal;
        this.core = core;
        this.cfg = cfg;
        this.clock = clock;
        this.backoff = backoff;
        this.metrics = metrics;
    }

    public void onIsolated(Runnable callback) {
        this.onIsolated = callback;
    }

    /**
     * One drain cycle; returns the number of items delivered or dead-lettered. Does nothing while backing off or
     * quarantined.
     */
    public int drain() {
        if (journal.quarantined() || clock.instant().isBefore(nextAttemptAt)) {
            return 0;
        }
        sendLock.lock();
        try {
            int done = 0;
            while (true) {
                List<QueueItem> items = journal.pending(cfg.batchSize(), cfg.batchMaxBytes());
                if (items.isEmpty()) {
                    return done;
                }
                CycleResult r = items.get(0).kind().batchable() ? sendGroup(batchablePrefix(items))
                        : new CycleResult(deliverSingle(items.get(0), true) == Outcome.DELIVERED ? 1 : 0,
                        lastStop);
                done += r.completed();
                if (r.stop()) {
                    return done;
                }
            }
        } finally {
            sendLock.unlock();
        }
    }

    /**
     * Synchronous forward right after capture, used by MLLP / POCT1-A listeners to relay the core's acknowledgement.
     * Returns empty when the item must wait in the queue (offline, backing off, older items of the same instance
     * pending, or the drain loop busy) – the caller then answers from the durable queue write.
     */
    public Optional<CoreResponse> forwardNow(QueueItem item) {
        try {
            return attemptNow(item);
        } finally {
            journal.releaseHold(item.sequence()); // the drain loop takes over whatever is still pending
        }
    }

    private Optional<CoreResponse> attemptNow(QueueItem item) {
        if (journal.quarantined() || clock.instant().isBefore(nextAttemptAt)
                || journal.hasOlderPending(item.sequence(), item.deviceInstanceId())) {
            return Optional.empty();
        }
        boolean locked = false;
        try {
            locked = sendLock.tryLock(cfg.immediateTimeout().toMillis(), TimeUnit.MILLISECONDS);
            if (!locked || !journal.isPending(item.sequence())) {
                return Optional.empty();
            }
            CoreResponse r = post(item, false);
            Outcome o = classify(item, r);
            return o == Outcome.DELIVERED || o == Outcome.DEAD_LETTERED ? Optional.of(r) : Optional.empty();
        } catch (IOException e) {
            offline(e, null);
            return Optional.empty();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        } finally {
            if (locked) {
                sendLock.unlock();
            }
        }
    }

    public boolean online() {
        return online;
    }

    public Instant lastSuccessAt() {
        return lastSuccessAt;
    }

    public String lastError() {
        return lastError;
    }

    public Instant nextAttemptAt() {
        return nextAttemptAt;
    }

    /** Clears the back-off (e.g. after a token rotation or on an operator request). */
    public void retryNow() {
        nextAttemptAt = Instant.EPOCH;
        backoff.reset();
    }

    // ------------------------------------------------------------------ internals

    private record CycleResult(int completed, boolean stop) {
    }

    private boolean lastStop;

    private static List<QueueItem> batchablePrefix(List<QueueItem> items) {
        List<QueueItem> out = new ArrayList<>();
        for (QueueItem i : items) {
            if (!i.kind().batchable()) {
                break;
            }
            out.add(i);
        }
        return out;
    }

    private CycleResult sendGroup(List<QueueItem> items) {
        if (items.size() == 1 && online && !everOffline) {
            Outcome o = deliverSingle(items.get(0), false);
            return new CycleResult(o == Outcome.DELIVERED || o == Outcome.DEAD_LETTERED ? 1 : 0, lastStop);
        }
        return sendBatch(items);
    }

    private Outcome deliverSingle(QueueItem item, boolean replay) {
        lastStop = false;
        try {
            CoreResponse r = item.kind() == ItemKind.DICOM ? postDicom(item, replay) : post(item, replay);
            Outcome o = classify(item, r);
            lastStop = o == Outcome.RETRY || o == Outcome.STOPPED;
            return o;
        } catch (IOException e) {
            offline(e, null);
            lastStop = true;
            return Outcome.RETRY;
        }
    }

    private CycleResult sendBatch(List<QueueItem> items) {
        ObjectNode root = json.createObjectNode();
        var arr = root.putArray("items");
        for (QueueItem i : items) {
            ObjectNode n = arr.addObject();
            n.put("kind", i.kind().name());
            n.put("idempotencyKey", i.idempotencyKey());
            n.put("sequence", i.sequence());
            n.put("capturedAt", i.capturedAt().toString());
            n.put("contentType", i.contentType());
            ObjectNode h = n.putObject("headers");
            itemHeaders(i, true).forEach(h::put);
            n.put("body", encodeBody(i));
        }
        CoreResponse r;
        try {
            r = core.send("POST", BATCH_PATH, "application/json",
                    Map.of("Idempotency-Key", "batch:" + items.get(0).idempotencyKey() + ".." + items.size(),
                            "X-Replayed", "true"),
                    json.writeValueAsString(root).getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            offline(e, null);
            return new CycleResult(0, true);
        }
        if (!r.success()) {
            Outcome o = classifyCall(r);
            return new CycleResult(0, o != Outcome.DELIVERED);
        }
        Map<String, JsonNode> results = new HashMap<>();
        try {
            for (JsonNode n : json.readTree(r.text()).path("results")) {
                results.put(n.path("idempotencyKey").asString(""), n);
            }
        } catch (tools.jackson.core.JacksonException e) {
            offline(null, "batch_response_invalid");
            return new CycleResult(0, true);
        }
        int completed = 0;
        boolean stop = false;
        for (QueueItem i : items) {
            JsonNode res = results.get(i.idempotencyKey());
            if (res == null) {
                stop = true; // not processed by the core – retry later, nothing lost
                continue;
            }
            int status = res.path("httpStatus").asInt(0);
            String code = res.path("problem").path("code").asString(null);
            Outcome o = classify(i, status, code, null);
            if (o == Outcome.DELIVERED || o == Outcome.DEAD_LETTERED) {
                completed++;
            } else {
                stop = true;
                if (o == Outcome.STOPPED) {
                    break;
                }
            }
        }
        metrics.counter("wz.gateway.forward.batches").increment();
        if (!stop) {
            healthy();
        }
        return new CycleResult(completed, stop);
    }

    private CoreResponse post(QueueItem item, boolean replay) throws IOException {
        Map<String, String> headers = itemHeaders(item, false);
        headers.put("Idempotency-Key", item.idempotencyKey());
        if (replay || everOffline) {
            headers.put("X-Replayed", "true");
        }
        return core.send("POST", item.kind().path(), item.contentType(), headers, item.body());
    }

    /** DICOM: upload the instance through /attachments, then post the PS3.18 JSON metadata with its archive id. */
    private CoreResponse postDicom(QueueItem item, boolean replay) throws IOException {
        JsonNode envelope = json.readTree(item.body());
        byte[] instance = Base64.getDecoder().decode(envelope.path("instance").asString(""));
        Map<String, String> h = itemHeaders(item, false);
        h.put("Idempotency-Key", item.idempotencyKey() + ":attachment");
        h.put("X-Attachment-Kind", "DICOM");
        h.put("X-Content-Sha256", envelope.path("sha256").asString(""));
        CoreResponse att = core.send("POST", ATTACHMENT_PATH, "application/dicom", h, instance);
        if (!att.success()) {
            return att;
        }
        String archiveId = json.readTree(att.text()).path("archiveId").asString(null);
        ObjectNode meta = json.createObjectNode();
        meta.put("archiveId", archiveId);
        meta.set("metadata", envelope.path("metadata"));
        Map<String, String> h2 = itemHeaders(item, false);
        h2.put("Idempotency-Key", item.idempotencyKey());
        if (replay || everOffline) {
            h2.put("X-Replayed", "true");
        }
        return core.send("POST", ItemKind.DICOM.path(), "application/dicom+json", h2,
                json.writeValueAsString(meta).getBytes(StandardCharsets.UTF_8));
    }

    private static Map<String, String> itemHeaders(QueueItem item, boolean forBatch) {
        Map<String, String> h = new LinkedHashMap<>(item.headers());
        h.put("X-Gateway-Sequence", Long.toString(item.sequence()));
        h.put("X-Captured-At", item.capturedAt().toString());
        if (item.deviceInstanceId() != null) {
            h.put("X-Device-Instance", item.deviceInstanceId());
        }
        if (forBatch) {
            h.put("X-Replayed", "true");
        }
        return h;
    }

    /** Text bodies travel as strings; files (hash-verified bytes) and binary content as base64. */
    static String encodeBody(QueueItem i) {
        String ct = i.contentType() == null ? "" : i.contentType().toLowerCase(Locale.ROOT);
        boolean text = i.kind() != ItemKind.FILE
                && (ct.startsWith("text/") || ct.contains("json") || ct.contains("xml") || ct.contains("hl7"));
        return text ? new String(i.body(), StandardCharsets.UTF_8) : Base64.getEncoder().encodeToString(i.body());
    }

    private Outcome classify(QueueItem item, CoreResponse r) {
        return classify(item, r.status(), r.problemCode().orElse(null), r);
    }

    private Outcome classify(QueueItem item, int status, String code, CoreResponse r) {
        if (status >= 200 && status < 300) {
            journal.acknowledge(item.sequence());
            metrics.counter("wz.gateway.forward.items", "outcome", "delivered", "kind", item.kind().name()).increment();
            healthy();
            return Outcome.DELIVERED;
        }
        if (status == 401) {
            return unauthorised(code);
        }
        if (status == 408 || status == 429 || status >= 500 || status == 0) {
            Duration retryAfter = r == null ? null : Backoff.parseRetryAfter(r.header("Retry-After").orElse(null));
            offline(null, "http_" + status, retryAfter);
            return Outcome.RETRY;
        }
        journal.deadLetter(item.sequence(), code != null ? code : "http_" + status);
        metrics.counter("wz.gateway.forward.items", "outcome", "dead_letter", "kind", item.kind().name()).increment();
        healthy();
        return Outcome.DEAD_LETTERED;
    }

    private Outcome classifyCall(CoreResponse r) {
        if (r.status() == 401) {
            return unauthorised(r.problemCode().orElse(null));
        }
        offline(null, "http_" + r.status(), Backoff.parseRetryAfter(r.header("Retry-After").orElse(null)));
        return Outcome.RETRY;
    }

    private Outcome unauthorised(String code) {
        if ("endpoint_isolated".equals(code) || "endpoint_revoked".equals(code)) {
            log.error("core refused the endpoint ({}); forwarding stopped and queue quarantined", code);
            journal.quarantine(true);
            lastError = code;
            onIsolated.run();
            return Outcome.STOPPED;
        }
        offline(null, code == null ? "unauthorised" : code, null);
        return Outcome.STOPPED;
    }

    private void healthy() {
        online = true;
        lastSuccessAt = clock.instant();
        lastError = null;
        backoff.reset();
        nextAttemptAt = Instant.EPOCH;
        if (journal.stats().depth() == 0) {
            everOffline = false;
        }
    }

    private void offline(IOException e, String code) {
        offline(e, code, null);
    }

    private void offline(IOException e, String code, Duration retryAfter) {
        online = false;
        everOffline = true;
        lastError = code != null ? code : (e == null ? "unknown" : "core_unreachable");
        Duration d = backoff.next(retryAfter);
        nextAttemptAt = clock.instant().plus(d);
        metrics.counter("wz.gateway.forward.failures", "reason", lastError.startsWith("http_") ? lastError
                : (e != null ? "io" : "other")).increment();
        log.warn("core not reachable ({}); next attempt in {} ms", lastError, d.toMillis());
    }
}
