package com.wellnessz.e03;

import java.util.List;

import org.springframework.stereotype.Component;

import com.wellnessz.casekernel.module.CaseModule;

/** Registers E03 with the module registry. */
@Component
public class E03Module implements CaseModule {

    @Override
    public String code() {
        return "E03";
    }

    @Override
    public String name() {
        return "Occupational health record & clinical encounter documentation";
    }

    @Override
    public List<String> capabilities() {
        return List.of("E03-F01 employee OH chart", "E03-F02 encounter templates & body map", "E03-F03 terminology services", "E03-F04 orders, results & devices", "E03-F05 clinical decision support", "E03-F06 document generation & management", "E03-F07 AI documentation assistant (consent-gated)", "E03-F08 record segregation & disclosure control");
    }
}
