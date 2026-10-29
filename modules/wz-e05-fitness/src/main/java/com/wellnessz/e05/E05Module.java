package com.wellnessz.e05;

import java.util.List;

import org.springframework.stereotype.Component;

import com.wellnessz.casekernel.module.CaseModule;

/** Registers E05 with the module registry. */
@Component
public class E05Module implements CaseModule {

    @Override
    public String code() {
        return "E05";
    }

    @Override
    public String name() {
        return "Fitness for duty, work restrictions & return to work";
    }

    @Override
    public List<String> capabilities() {
        return List.of("E05-F01 fitness decision module", "E05-F02 restrictions catalogue & job-demand matching", "E05-F03 RTW plan & electronic job offer", "E05-F04 crew fitness & roster-status feed", "E05-F05 food-handler fitness control", "E05-F06 restriction expiry & review alerts", "E05-F07 ill-health retirement / redeployment & appeals");
    }
}
