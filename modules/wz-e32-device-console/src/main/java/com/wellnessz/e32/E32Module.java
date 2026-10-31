package com.wellnessz.e32;

import java.util.List;

import org.springframework.stereotype.Component;

import com.wellnessz.casekernel.module.CaseModule;

/** Registers E32 with the module registry (enablement flag {@code modules.e32.enabled}). */
@Component
public class E32Module implements CaseModule {

    @Override
    public String code() {
        return "E32";
    }

    @Override
    public String name() {
        return "Device catalogue, administration & connectivity provisioning console";
    }

    @Override
    public List<String> capabilities() {
        return List.of("E32-F01 device catalogue & regulatory register",
                "E32-F02 provisioning wizard (8 steps)",
                "E32-F03 connectivity option packs P1–P10",
                "E32-F04 calibration, QC & lockout",
                "E32-F05 fleet dashboard & alerts",
                "E32-F06 configuration governance & promotion",
                "E32-F07 vendor security onboarding",
                "E32-F08 request-a-new-model",
                "E32-F09 diagnostics & masked trace",
                "E32-F10 certification record & data-flow summary",
                "E32-F11 decommissioning & key revocation");
    }
}
