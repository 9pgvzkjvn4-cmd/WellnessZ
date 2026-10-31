package com.wellnessz.e34.support;

import java.time.Clock;
import java.time.Instant;

import org.springframework.stereotype.Component;

import com.wellnessz.casekernel.module.ModuleRegistry;
import com.wellnessz.masterdata.ConfigService;

/**
 * Business configuration of E34 (E18 {@code masterdata.config_entry}, seeded by V21 / V25) with safe caps, the module guard
 * and the clock. Values are read on every use so a change by an administrator applies without a restart.
 */
@Component
public class E34Settings {

    public static final String MODULE = "E34";

    private final ConfigService config;
    private final ModuleRegistry modules;
    private final Clock clock;

    public E34Settings(ConfigService config, ModuleRegistry modules, Clock clock) {
        this.config = config;
        this.modules = modules;
        this.clock = clock;
    }

    /** 503 {@code module_disabled} when E34 is switched off (entry points). */
    public void requireEnabled() {
        modules.requireEnabled(MODULE);
    }

    public boolean enabled() {
        return modules.isEnabled(MODULE);
    }

    public Instant now() {
        return clock.instant();
    }

    public Clock clock() {
        return clock;
    }

    /** One-time enrolment token validity, at most 72 hours (contract §15.2). */
    public int enrolmentTokenHours() {
        return clamp(intValue("device.security.enrolment-token-hours", 72), 1, 72);
    }

    /** Per-endpoint token validity, at most 90 days (contract §15.1). */
    public int endpointTokenDays() {
        return clamp(intValue("device.security.endpoint-token-days", 90), 1, 90);
    }

    public int tokenGraceMinutes() {
        return clamp(intValue("device.security.token-grace-minutes", 15), 0, 60);
    }

    /** Isolation playbook deadline (E34-HLR-09), 1–15 minutes by default policy; configurable up to 60. */
    public int isolationSlaMinutes() {
        return clamp(intValue("device.security.isolation-sla-minutes", 15), 1, 60);
    }

    public int certExpiryWarningDays() {
        return clamp(intValue("device.security.cert-expiry-warning-days", 30), 1, 365);
    }

    public String socEmail() {
        return config.get("device.security.soc-email").filter(s -> s.contains("@")).orElse(null);
    }

    private int intValue(String key, int fallback) {
        try {
            return config.get(key).map(String::strip).map(Integer::parseInt).orElse(fallback);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }
}
