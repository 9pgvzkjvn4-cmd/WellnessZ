package com.wellnessz.e08;

import java.util.List;

import org.springframework.stereotype.Component;

import com.wellnessz.casekernel.module.CaseModule;

/** Registers E08 with the module registry. */
@Component
public class E08Module implements CaseModule {

    @Override
    public String code() {
        return "E08";
    }

    @Override
    public String name() {
        return "Workers' compensation, insurance & claims coordination";
    }

    @Override
    public List<String> capabilities() {
        return List.of("E08-F01 claim record & lifecycle", "E08-F02 statutory & insurer e-reporting", "E08-F03 consent-gated document exchange", "E08-F04 nurse case manager – adjuster collaboration", "E08-F05 cost tracking & finance export", "E08-F06 medico-legal & litigation support");
    }
}
