package com.wellnessz.e10;

import java.util.List;

import org.springframework.stereotype.Component;

import com.wellnessz.casekernel.module.CaseModule;

/** Registers E10 with the module registry. */
@Component
public class E10Module implements CaseModule {

    @Override
    public String code() {
        return "E10";
    }

    @Override
    public String name() {
        return "Drug & alcohol / psychoactive substance testing programme";
    }

    @Override
    public List<String> capabilities() {
        return List.of("E10-F01 policy & pool management", "E10-F02 post-incident & for-cause triggers", "E10-F03 collection, chain of custody & lab integration", "E10-F04 positive / refusal pathway", "E10-F05 programme reporting");
    }
}
