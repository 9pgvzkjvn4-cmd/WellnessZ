package com.wellnessz.casekernel.module;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.wellnessz.masterdata.ConfigService;

/** Catalogue of all modules of the logical architecture with their delivery and enablement state. */
@Component
public class ModuleRegistry {

    public enum Delivery { PLATFORM, KERNEL, PLANNED, IMPLEMENTED }

    public record ModuleInfo(String code, String name, String group, Delivery delivery, boolean enabled, List<String> capabilities) {
    }

    private static final Map<String, String[]> CATALOGUE = new LinkedHashMap<>();

    static {
        CATALOGUE.put("E01", new String[]{"Intake & incident-to-case rule engine", "core", "KERNEL"});
        CATALOGUE.put("E02", new String[]{"Triage, routing, work queues & SLAs", "core", "PLANNED"});
        CATALOGUE.put("E03", new String[]{"OH record & clinical encounters", "core", "PLANNED"});
        CATALOGUE.put("E04", new String[]{"Treatment coordination, referrals & provider network", "core", "PLANNED"});
        CATALOGUE.put("E05", new String[]{"Fitness for duty, restrictions & return to work", "core", "KERNEL"});
        CATALOGUE.put("E06", new String[]{"Absence & early intervention", "core", "PLANNED"});
        CATALOGUE.put("E07", new String[]{"Surveillance, statutory medicals & immunisation", "prevention", "PLANNED"});
        CATALOGUE.put("E08", new String[]{"Claims & insurance coordination", "core", "PLANNED"});
        CATALOGUE.put("E09", new String[]{"Psychological health & crew peer support – restricted class", "restricted", "PLANNED"});
        CATALOGUE.put("E10", new String[]{"Substance-testing programme", "restricted", "PLANNED"});
        CATALOGUE.put("E11", new String[]{"Travel health, exposures & communicable disease", "prevention", "PLANNED"});
        CATALOGUE.put("E12", new String[]{"Health promotion & campaigns", "cross-cutting", "PLANNED"});
        CATALOGUE.put("E13", new String[]{"Employee & manager self-service", "cross-cutting", "KERNEL"});
        CATALOGUE.put("E14", new String[]{"Scheduling & clinic operations", "core", "PLANNED"});
        CATALOGUE.put("E15", new String[]{"Integration & API gateway", "platform", "PLATFORM"});
        CATALOGUE.put("E16", new String[]{"Identity, roles (ABAC), consent & immutable audit", "platform", "PLATFORM"});
        CATALOGUE.put("E17", new String[]{"Analytics, KPIs, regulatory reporting & predictive models", "platform", "PLATFORM"});
        CATALOGUE.put("E18", new String[]{"Master data, governance & no-code configuration", "platform", "PLATFORM"});
    }

    private final List<CaseModule> modules;
    private final ConfigService config;

    public ModuleRegistry(List<CaseModule> modules, ConfigService config) {
        this.modules = modules;
        this.config = config;
    }

    public List<ModuleInfo> all() {
        List<ModuleInfo> out = new ArrayList<>();
        CATALOGUE.forEach((code, meta) -> {
            CaseModule impl = modules.stream().filter(m -> m.code().equals(code)).findFirst().orElse(null);
            Delivery delivery = impl != null ? Delivery.IMPLEMENTED : Delivery.valueOf(meta[2]);
            boolean defaultEnabled = delivery != Delivery.PLANNED;
            boolean enabled = config.flag("modules." + code.toLowerCase(java.util.Locale.ROOT) + ".enabled", defaultEnabled);
            out.add(new ModuleInfo(code, impl != null ? impl.name() : meta[0], meta[1], delivery, enabled,
                    impl != null ? impl.capabilities() : List.of()));
        });
        out.sort(Comparator.comparing(ModuleInfo::code));
        return out;
    }

    public boolean isEnabled(String code) {
        return all().stream().anyMatch(m -> m.code().equals(code) && m.enabled());
    }

    /** Guard for module entry points: a disabled module (E18 config {@code modules.eXX.enabled=false}) answers 503. */
    public void requireEnabled(String code) {
        if (!isEnabled(code)) {
            throw new com.wellnessz.common.error.ApiException(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
                    "module_disabled", "Module " + code + " is not enabled");
        }
    }
}
