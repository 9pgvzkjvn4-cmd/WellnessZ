package com.wellnessz.e02.triage;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.HashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import com.wellnessz.e02.model.TriageProtocol;
import com.wellnessz.e02.model.TriageProtocolRepository;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Seeds the draft protocol set from {@code classpath:triage/protocols/*.json} (only codes not yet present – existing
 * versions are never overwritten) and serves the advice catalogue ({@code triage/advice.json}, en/ar/fr with en fallback).
 */
@Component
public class ProtocolCatalog {

    private static final Logger log = LoggerFactory.getLogger(ProtocolCatalog.class);

    private final TriageProtocolRepository protocols;
    private final TransactionTemplate tx;
    private final JsonMapper json;
    private final Clock clock;
    private final Map<String, Map<String, String>> advice = new HashMap<>();

    public ProtocolCatalog(TriageProtocolRepository protocols, TransactionTemplate tx, JsonMapper json, Clock clock) throws IOException {
        this.protocols = protocols;
        this.tx = tx;
        this.json = json;
        this.clock = clock;
        try (InputStream in = getClass().getResourceAsStream("/triage/advice.json")) {
            if (in != null) {
                JsonNode root = json.readTree(new String(in.readAllBytes(), StandardCharsets.UTF_8));
                root.propertyNames().forEach(k -> {
                    Map<String, String> m = new HashMap<>();
                    root.get(k).propertyNames().forEach(l -> m.put(l, root.get(k).get(l).asString()));
                    advice.put(k, m);
                });
            }
        }
    }

    @EventListener(ApplicationReadyEvent.class)
    public void seed() throws IOException {
        Resource[] files = new PathMatchingResourcePatternResolver().getResources("classpath*:triage/protocols/*.json");
        int added = 0;
        for (Resource r : files) {
            String body;
            try (InputStream in = r.getInputStream()) {
                body = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
            JsonNode p = json.readTree(body);
            String code = p.path("code").asString();
            Integer created = tx.execute(s -> {
                if (protocols.existsByCode(code)) {
                    return 0;
                }
                TriageProtocol t = new TriageProtocol();
                t.setCode(code);
                t.setVersion(p.path("version").asInt(1));
                t.setTitle(p.path("title").asString());
                t.setPresentation(p.path("presentation").asString());
                t.setDefinition(body);
                t.setStatus("DRAFT");
                t.setCreatedAt(clock.instant());
                protocols.save(t);
                return 1;
            });
            added += created == null ? 0 : created;
        }
        if (added > 0) {
            log.info("Seeded {} draft triage protocols (CMO approval required)", added);
        }
    }

    public String advice(String key, String lang) {
        Map<String, String> m = advice.get(key);
        if (m == null) {
            return null;
        }
        return m.getOrDefault(lang == null ? "en" : lang, m.get("en"));
    }

    public boolean hasAdvice(String key) {
        return advice.containsKey(key);
    }
}
