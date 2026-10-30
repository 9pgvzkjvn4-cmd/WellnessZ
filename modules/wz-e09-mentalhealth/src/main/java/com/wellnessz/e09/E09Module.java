package com.wellnessz.e09;

import java.util.List;

import org.springframework.stereotype.Component;

import com.wellnessz.casekernel.module.CaseModule;

/** Registers E09 with the module registry. */
@Component
public class E09Module implements CaseModule {

    @Override
    public String code() {
        return "E09";
    }

    @Override
    public String name() {
        return "Mental health, psychosocial risk & peer support programmes";
    }

    @Override
    public List<String> capabilities() {
        return List.of("E09-F01 confidential psychological-health case type", "E09-F02 self-referral, screening & EAP referral", "E09-F03 flight-crew support programme (peer support)", "E09-F04 critical incident stress management", "E09-F05 psychosocial risk register", "E09-F06 safety-critical mental-health fitness assessment");
    }
}
