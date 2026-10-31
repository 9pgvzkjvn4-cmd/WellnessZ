package com.wellnessz.e34;

import java.util.List;

import org.springframework.stereotype.Component;

import com.wellnessz.casekernel.module.CaseModule;

/** Registers E34 with the module registry (enablement flag {@code modules.e34.enabled}). */
@Component
public class E34Module implements CaseModule {

    @Override
    public String code() {
        return "E34";
    }

    @Override
    public String name() {
        return "Endpoint & integration security";
    }

    @Override
    public List<String> capabilities() {
        return List.of("E34-F01 endpoint identity & PKI",
                "E34-F02 network zoning & policy templates",
                "E34-F03 hardened gateway & Device Host images",
                "E34-F04 secrets & key management",
                "E34-F05 secure development life-cycle & SBOM",
                "E34-F06 vendor security intake & compensating controls",
                "E34-F07 tamper-evident audit & SIEM",
                "E34-F08 anomaly detection & compromise playbook",
                "E34-F09 privacy controls for device data",
                "E34-F10 security test pack",
                "E34-F11 penetration testing & IEC 80001-1 agreement");
    }
}
