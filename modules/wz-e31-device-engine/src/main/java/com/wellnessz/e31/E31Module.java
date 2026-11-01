package com.wellnessz.e31;

import java.util.List;

import org.springframework.stereotype.Component;

import com.wellnessz.casekernel.module.CaseModule;

/** Registers E31 with the module registry (enablement flag {@code modules.e31.enabled}). */
@Component("e31E31Module")
public class E31Module implements CaseModule {

    @Override
    public String code() {
        return "E31";
    }

    @Override
    public String name() {
        return "Medical device integration engine – pipeline, adapters, normalisation & routing";
    }

    @Override
    public List<String> capabilities() {
        return List.of("E31-F01 canonical observation pipeline & dead-letter",
                "E31-F02 protocol adapter library P1–P10",
                "E31-F03 driver SDK & declarative driver packages",
                "E31-F04 normalisation & terminology",
                "E31-F05 association engine",
                "E31-F06 quality gates & clinician verification",
                "E31-F07 result-to-form bindings",
                "E31-F08 bidirectional device workflows",
                "E31-F09 edge store-and-forward",
                "E31-F10 simulators & golden datasets",
                "E31-F11 connector observability",
                "E31-F12 multi-tenant partitioning");
    }
}
