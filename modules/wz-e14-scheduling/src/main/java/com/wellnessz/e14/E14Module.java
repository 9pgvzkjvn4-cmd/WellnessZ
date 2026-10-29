package com.wellnessz.e14;

import java.util.List;

import org.springframework.stereotype.Component;

import com.wellnessz.casekernel.module.CaseModule;

/** Registers E14 with the module registry. */
@Component
public class E14Module implements CaseModule {

    @Override
    public String code() {
        return "E14";
    }

    @Override
    public String name() {
        return "Scheduling, clinic operations & resource management";
    }

    @Override
    public List<String> capabilities() {
        return List.of("E14-F01 appointment scheduling", "E14-F02 campaign & mobile-clinic scheduling", "E14-F03 check-in & queue management", "E14-F04 equipment & cold-chain register", "E14-F05 capacity planning & utilisation");
    }
}
