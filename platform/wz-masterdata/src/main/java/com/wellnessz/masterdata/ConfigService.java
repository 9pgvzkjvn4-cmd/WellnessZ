package com.wellnessz.masterdata;

import java.time.Clock;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.wellnessz.security.audit.AuditService;

/** Typed access to configuration entries; used e.g. for module enablement and analytics thresholds. */
@Service
public class ConfigService {

    private final ConfigEntryRepository repository;
    private final AuditService audit;
    private final Clock clock;

    public ConfigService(ConfigEntryRepository repository, AuditService audit, Clock clock) {
        this.repository = repository;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public Optional<String> get(String key) {
        return repository.findById(key).map(ConfigEntry::getValue);
    }

    public boolean flag(String key, boolean defaultValue) {
        return get(key).map(v -> Boolean.parseBoolean(v.trim())).orElse(defaultValue);
    }

    @Transactional
    public ConfigEntry put(String key, String value, String description, String actor) {
        ConfigEntry entry = repository.findById(key)
                .map(e -> {
                    e.change(value, actor, clock.instant());
                    return e;
                })
                .orElseGet(() -> repository.save(new ConfigEntry(key, value, description, actor, clock.instant())));
        audit.record("CONFIG_CHANGE", "CONFIG", key, AuditService.Outcome.SUCCESS, null);
        return entry;
    }
}
