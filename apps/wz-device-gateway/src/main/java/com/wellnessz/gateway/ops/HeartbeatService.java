package com.wellnessz.gateway.ops;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.wellnessz.gateway.connector.Connector;
import com.wellnessz.gateway.connector.ConnectorStatus;
import com.wellnessz.gateway.core.ClockSkew;
import com.wellnessz.gateway.core.CoreClient;
import com.wellnessz.gateway.core.CoreResponse;
import com.wellnessz.gateway.queue.EncryptedJournal;
import com.wellnessz.gateway.queue.QueueStats;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Heartbeat (contract §7): every {@code heartbeatSeconds} (60 by default) reports version, configuration version,
 * uptime, the clock offset, queue figures, disk space, device states, listeners and the hardening self-check; the
 * response carries the server time (clock-skew measurement), the expected configuration version and commands.
 */
public class HeartbeatService {

    private static final Logger log = LoggerFactory.getLogger(HeartbeatService.class);

    private final CoreClient core;
    private final EncryptedJournal journal;
    private final Supplier<List<Connector>> connectors;
    private final HardeningCheck hardening;
    private final ConfigService config;
    private final CommandProcessor commands;
    private final ClockSkew skew;
    private final RuntimeSettings settings;
    private final Clock clock;
    private final Instant startedAt;
    private final String version;
    private final String role;
    private final JsonMapper json = JsonMapper.builder().build();
    private volatile Instant lastSentAt;
    private volatile Instant lastOkAt;
    private volatile String lastError;

    public HeartbeatService(CoreClient core, EncryptedJournal journal, Supplier<List<Connector>> connectors,
                            HardeningCheck hardening, ConfigService config, CommandProcessor commands, ClockSkew skew,
                            RuntimeSettings settings, Clock clock, String version, String role) {
        this.core = core;
        this.journal = journal;
        this.connectors = connectors;
        this.hardening = hardening;
        this.config = config;
        this.commands = commands;
        this.skew = skew;
        this.settings = settings;
        this.clock = clock;
        this.startedAt = clock.instant();
        this.version = version;
        this.role = role;
    }

    /** Sends a heartbeat when the interval has elapsed; returns {@code true} when one was sent successfully. */
    public boolean tick() {
        Instant now = clock.instant();
        if (lastSentAt != null && Duration.between(lastSentAt, now).compareTo(settings.heartbeatInterval()) < 0) {
            return false;
        }
        return send();
    }

    public synchronized boolean send() {
        Instant sent = clock.instant();
        lastSentAt = sent;
        CoreResponse r;
        try {
            r = core.send("POST", "/device-ingest/heartbeat", "application/json", Map.of(),
                    json.writeValueAsString(body()).getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            lastError = "core_unreachable";
            return false;
        }
        Instant received = clock.instant();
        if (!r.success()) {
            lastError = r.problemCode().orElse("http_" + r.status());
            log.warn("heartbeat refused ({})", lastError);
            return false;
        }
        lastOkAt = received;
        lastError = null;
        JsonNode resp;
        try {
            resp = json.readTree(r.text());
        } catch (tools.jackson.core.JacksonException e) {
            lastError = "heartbeat_response_invalid";
            return false;
        }
        try {
            String serverTime = resp.path("serverTime").asString(null);
            if (serverTime != null) {
                skew.record(sent, received, Instant.parse(serverTime));
            }
        } catch (DateTimeParseException e) {
            log.debug("heartbeat serverTime unparseable");
        }
        String expected = resp.path("configVersion").asString(null);
        if (expected != null && !expected.equals(config.configVersion())) {
            try {
                config.pull();
            } catch (IOException e) {
                log.warn("configuration pull failed: {}", e.getMessage());
            }
        }
        for (JsonNode c : resp.path("commands")) {
            commands.handle(c);
        }
        return true;
    }

    Map<String, Object> body() {
        QueueStats q = journal.stats();
        Map<String, Object> queue = new LinkedHashMap<>();
        queue.put("depth", q.depth());
        if (q.oldestCapturedAt() != null) {
            queue.put("oldestCapturedAt", q.oldestCapturedAt().toString());
        }
        queue.put("bytes", q.bytes());
        queue.put("capacityPct", q.capacityPct());
        queue.put("deadLetters", q.deadLetters());
        queue.put("quarantined", q.quarantined());
        List<Map<String, Object>> devices = new ArrayList<>();
        List<Map<String, Object>> listeners = new ArrayList<>();
        for (Connector c : connectors.get()) {
            ConnectorStatus s = c.status();
            if (s.deviceInstanceId() != null) {
                Map<String, Object> d = new LinkedHashMap<>();
                d.put("deviceInstanceId", s.deviceInstanceId());
                if (s.lastSeenAt() != null) {
                    d.put("lastSeenAt", s.lastSeenAt().toString());
                }
                d.put("state", s.state().name());
                devices.add(d);
            }
            if (c instanceof com.wellnessz.gateway.connector.TcpListenerConnector t) {
                listeners.add(Map.of("connector", c.id(), "type", c.type(), "port", t.localPort(),
                        "tls", t.tlsEnabled()));
            }
        }
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("gatewayVersion", version);
        b.put("role", role);
        b.put("configVersion", config.configVersion() == null ? "none" : config.configVersion());
        b.put("uptimeSeconds", Duration.between(startedAt, clock.instant()).toSeconds());
        b.put("ntpOffsetMillis", skew.offsetMillis());
        b.put("queue", queue);
        b.put("diskFreePct", hardening.diskFreePct());
        b.put("devices", devices);
        b.put("listeners", listeners);
        b.put("hardening", hardening.run());
        return b;
    }

    public Instant lastOkAt() {
        return lastOkAt;
    }

    public String lastError() {
        return lastError;
    }
}
