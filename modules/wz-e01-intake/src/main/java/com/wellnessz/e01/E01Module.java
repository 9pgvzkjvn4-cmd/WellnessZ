package com.wellnessz.e01;

import java.util.List;

import org.springframework.stereotype.Component;

import com.wellnessz.casekernel.module.CaseModule;

/** Registers E01 with the module registry (enablement via {@code modules.e01.enabled}). */
@Component
public class E01Module implements CaseModule {

    @Override
    public String code() {
        return "E01";
    }

    @Override
    public String name() {
        return "Case intake & incident-triggered case initiation";
    }

    @Override
    public List<String> capabilities() {
        return List.of("E01-F01 incident feed listener & rule engine", "E01-F02 self / manager / HR referral forms",
                "E01-F03 walk-in / emergency registration", "E01-F04 system-trigger case flags", "E01-F05 case identity & linking",
                "E01-F06 duplicate detection & merge", "E01-F07 recordability classifier", "E01-F08 intake notifications & SLA timers");
    }
}
