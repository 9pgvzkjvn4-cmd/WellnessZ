package com.wellnessz.e11;

import java.util.List;

import org.springframework.stereotype.Component;

import com.wellnessz.casekernel.module.CaseModule;

/** Registers E11 with the module registry. */
@Component
public class E11Module implements CaseModule {

    @Override
    public String code() {
        return "E11";
    }

    @Override
    public String name() {
        return "Travel health, immunisation campaigns, communicable disease & exposure management";
    }

    @Override
    public List<String> capabilities() {
        return List.of("E11-F01 travel & posting consultation", "E11-F02 communicable-disease case & contact tracing", "E11-F03 exposure pathways with protocol timers", "E11-F04 vaccination campaign manager", "E11-F05 health advisories", "E11-F06 public-health reporting");
    }
}
