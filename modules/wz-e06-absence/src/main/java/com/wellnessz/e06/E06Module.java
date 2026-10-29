package com.wellnessz.e06;

import java.util.List;

import org.springframework.stereotype.Component;

import com.wellnessz.casekernel.module.CaseModule;

/** Registers E06 with the module registry. */
@Component
public class E06Module implements CaseModule {

    @Override
    public String code() {
        return "E06";
    }

    @Override
    public String name() {
        return "Absence & sickness management";
    }

    @Override
    public List<String> capabilities() {
        return List.of("E06-F01 absence feed & history", "E06-F02 trigger rules & early-intervention queue", "E06-F03 medical certificate management", "E06-F04 long-term absence pathway", "E06-F05 management advice request & OH advice report", "E06-F06 absence analytics");
    }
}
