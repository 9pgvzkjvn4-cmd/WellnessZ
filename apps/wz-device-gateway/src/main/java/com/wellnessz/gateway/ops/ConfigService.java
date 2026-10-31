package com.wellnessz.gateway.ops;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.logging.LogLevel;
import org.springframework.boot.logging.LoggingSystem;

import com.wellnessz.gateway.core.CoreClient;
import com.wellnessz.gateway.core.CoreResponse;
import com.wellnessz.gateway.queue.EncryptedJournal;
import com.wellnessz.gateway.support.AtomicFiles;
import com.wellnessz.secrets.AesGcmCipher;
import com.wellnessz.secrets.CryptoException;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Pulls {@code GET /device-ingest/config} (contract §16.3) on start, on {@code RELOAD_CONFIG} and when the heartbeat
 * reports another {@code configVersion}. The new version is stored encrypted and applied as a whole; the previous one is
 * kept for rollback. Applied at run time: heartbeat interval, queue limits (retention never below 48 h), the BLE bonded
 * peripheral allow-list and the log level. The configuration carries secrets only as references.
 */
public class ConfigService {

    private static final Logger log = LoggerFactory.getLogger(ConfigService.class);
    private static final String AAD = "wz-gateway/config";
    private static final Set<String> LEVELS = Set.of("ERROR", "WARN", "INFO", "DEBUG");

    private final CoreClient core;
    private final AesGcmCipher cipher;
    private final Path current;
    private final Path previous;
    private final RuntimeSettings settings;
    private final EncryptedJournal journal;
    private final LoggingSystem logging;
    private final JsonMapper json = JsonMapper.builder().build();
    private volatile Consumer<List<String>> bleAllowList = l -> { };
    private volatile String configVersion;
    private volatile int instanceCount;

    public ConfigService(CoreClient core, AesGcmCipher cipher, Path dataDir, RuntimeSettings settings,
                         EncryptedJournal journal, LoggingSystem logging) {
        this.core = core;
        this.cipher = cipher;
        this.current = dataDir.resolve("config").resolve("current.enc");
        this.previous = dataDir.resolve("config").resolve("previous.enc");
        this.settings = settings;
        this.journal = journal;
        this.logging = logging;
    }

    public void onBleAllowList(Consumer<List<String>> consumer) {
        this.bleAllowList = consumer;
    }

    public String configVersion() {
        return configVersion;
    }

    public int instanceCount() {
        return instanceCount;
    }

    /** Applies the last stored configuration (offline start). */
    public void loadPersisted() {
        try {
            String stored = AtomicFiles.readOrNull(current);
            if (stored != null) {
                apply(json.readTree(cipher.decrypt(stored.trim(), AAD)));
            }
        } catch (IOException | CryptoException | tools.jackson.core.JacksonException e) {
            log.warn("stored gateway configuration unreadable ({}); waiting for the core", e.getClass().getSimpleName());
        }
    }

    /** Pulls and applies the configuration; returns the version now active. */
    public synchronized String pull() throws IOException {
        CoreResponse r = core.send("GET", "/device-ingest/config", null, Map.of(), null);
        if (!r.success()) {
            throw new IOException("configuration pull refused: HTTP " + r.status());
        }
        JsonNode cfg;
        try {
            cfg = json.readTree(r.text());
        } catch (tools.jackson.core.JacksonException e) {
            throw new IOException("configuration response is not valid JSON", e);
        }
        String version = cfg.path("configVersion").asString(null);
        if (version == null) {
            throw new IOException("configuration has no configVersion");
        }
        if (version.equals(configVersion)) {
            return version;
        }
        String old = AtomicFiles.readOrNull(current);
        if (old != null) {
            AtomicFiles.write(previous, old);
        }
        AtomicFiles.write(current, cipher.encrypt(json.writeValueAsString(cfg), AAD));
        apply(cfg);
        log.info("gateway configuration {} applied ({} instances)", version, instanceCount);
        return version;
    }

    /** Restores the previous configuration version. */
    public synchronized boolean rollback() throws IOException {
        String prev = AtomicFiles.readOrNull(previous);
        if (prev == null) {
            return false;
        }
        String cur = AtomicFiles.readOrNull(current);
        AtomicFiles.write(current, prev);
        if (cur != null) {
            AtomicFiles.write(previous, cur);
        }
        apply(json.readTree(cipher.decrypt(prev.trim(), AAD)));
        return true;
    }

    private void apply(JsonNode cfg) {
        settings.apply(cfg.path("heartbeatSeconds").asInt(0), cfg.path("queue").path("retentionHours").asInt(0));
        long maxBytes = cfg.path("queue").path("maxBytes").asLong(0);
        if (maxBytes > 0) {
            journal.setMaxBytes(maxBytes);
        }
        List<String> allow = new ArrayList<>();
        JsonNode instances = cfg.path("instances");
        for (JsonNode i : instances) {
            for (JsonNode p : i.path("ble").path("peripheralAllowList")) {
                allow.add(p.asString(""));
            }
        }
        if (!allow.isEmpty()) {
            bleAllowList.accept(allow);
        }
        String level = cfg.path("logLevel").asString("");
        if (LEVELS.contains(level) && logging != null) {
            logging.setLogLevel("com.wellnessz.gateway", LogLevel.valueOf(level));
        }
        instanceCount = instances.size();
        configVersion = cfg.path("configVersion").asString(null);
    }
}
