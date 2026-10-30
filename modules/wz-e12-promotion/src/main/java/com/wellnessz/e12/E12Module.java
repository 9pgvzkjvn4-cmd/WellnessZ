package com.wellnessz.e12;

import java.util.List;

import org.springframework.stereotype.Component;

import com.wellnessz.casekernel.module.CaseModule;

/** Registers E12 with the module registry. */
@Component
public class E12Module implements CaseModule {

    @Override
    public String code() {
        return "E12";
    }

    @Override
    public String name() {
        return "Health promotion, wellbeing & prevention campaigns";
    }

    @Override
    public List<String> capabilities() {
        return List.of("E12-F01 campaign planner", "E12-F02 screening events & follow-up", "E12-F03 ergonomic assessment module", "E12-F04 campaign-app & wearable integration", "E12-F05 participation & outcome analytics");
    }
}
