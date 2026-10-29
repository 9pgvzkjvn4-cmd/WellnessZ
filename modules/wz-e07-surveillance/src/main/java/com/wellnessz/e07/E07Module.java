package com.wellnessz.e07;

import java.util.List;

import org.springframework.stereotype.Component;

import com.wellnessz.casekernel.module.CaseModule;

/** Registers E07 with the module registry. */
@Component
public class E07Module implements CaseModule {

    @Override
    public String code() {
        return "E07";
    }

    @Override
    public String name() {
        return "Medical surveillance, periodic & statutory medicals";
    }

    @Override
    public List<String> capabilities() {
        return List.of("E07-F01 programme & protocol builder", "E07-F02 due-list engine & campaign scheduling", "E07-F03 pre-employment / pre-placement medicals", "E07-F04 crew aeromedical & certificate tracking", "E07-F05 immunisation & titre registry", "E07-F06 exposure registers & IH linkage", "E07-F07 result evaluation & auto-follow-up", "E07-F08 compliance dashboards & audit packs");
    }
}
