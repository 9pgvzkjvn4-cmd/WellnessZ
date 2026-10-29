package com.wellnessz.e02;

import java.util.List;

import org.springframework.stereotype.Component;

import com.wellnessz.casekernel.module.CaseModule;

/** Registers E02 with the module registry (enablement via {@code modules.e02.enabled}). */
@Component
public class E02Module implements CaseModule {

    @Override
    public String code() {
        return "E02";
    }

    @Override
    public String name() {
        return "Clinical triage, risk stratification & case assignment";
    }

    @Override
    public List<String> capabilities() {
        return List.of("E02-F01 triage protocol engine", "E02-F02 safety-critical role routing",
                "E02-F03 assignment rules & caseload balancing", "E02-F04 work queues & SLA board", "E02-F05 escalation & second opinion",
                "E02-F06 complexity / risk scoring", "E02-F07 telephone / remote triage mode");
    }
}
