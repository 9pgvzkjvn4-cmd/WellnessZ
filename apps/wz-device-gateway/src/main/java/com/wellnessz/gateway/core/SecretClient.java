package com.wellnessz.gateway.core;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import tools.jackson.databind.json.JsonMapper;

/**
 * Resolves secret references bound to this endpoint's instances through {@code GET /device-ingest/secrets/{ref}}
 * (P2 vendor-database credentials for the Device Host agent). Values are resolved on demand, kept in memory only for
 * the duration of the call that needs them and never logged.
 */
public class SecretClient {

    private final CoreClient core;
    private final JsonMapper json = JsonMapper.builder().build();

    public SecretClient(CoreClient core) {
        this.core = core;
    }

    public String resolve(String reference) throws IOException {
        if (reference == null || !reference.matches("(devsec|vault):[A-Za-z0-9._/:-]{1,200}")) {
            throw new IllegalArgumentException("invalid secret reference");
        }
        CoreResponse r = core.send("GET", "/device-ingest/secrets/" + URLEncoder.encode(reference, StandardCharsets.UTF_8),
                null, Map.of("Cache-Control", "no-store"), null);
        if (!r.success()) {
            throw new IOException("secret resolution refused: HTTP " + r.status());
        }
        String value = json.readTree(r.text()).path("value").asString(null);
        if (value == null) {
            throw new IOException("secret resolution returned no value");
        }
        return value;
    }
}
