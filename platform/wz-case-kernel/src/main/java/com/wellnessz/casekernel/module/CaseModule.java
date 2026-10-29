package com.wellnessz.casekernel.module;

import java.util.List;

/**
 * Extension point for business modules E01–E14. Each module registers a Spring bean implementing this interface;
 * the registry exposes enablement (driven by E18 configuration key {@code modules.<code>.enabled}).
 */
public interface CaseModule {

    /** Architecture code, e.g. {@code E05}. */
    String code();

    String name();

    /** Capabilities contributed (forms, workflows, rules, SLAs, reports …). */
    List<String> capabilities();
}
