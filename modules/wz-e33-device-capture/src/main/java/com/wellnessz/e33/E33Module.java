package com.wellnessz.e33;

import java.util.List;

import org.springframework.stereotype.Component;

import com.wellnessz.casekernel.module.CaseModule;

/** Registers E33 with the module registry (enablement flag {@code modules.e33.enabled}). */
@Component
public class E33Module implements CaseModule {

    @Override
    public String code() {
        return "E33";
    }

    @Override
    public String name() {
        return "Module-embedded device capture";
    }

    @Override
    public List<String> capabilities() {
        return List.of("E33-F01 capture-from-device control & station awareness",
                "E33-F02 preview, quality flags & verification",
                "E33-F03 audiometry capture & STS support",
                "E33-F04 spirometry capture & ATS/ERS grading",
                "E33-F05 vision capture",
                "E33-F06 ECG, ABPM & Holter capture",
                "E33-F07 vitals & body composition",
                "E33-F08 breath alcohol & drug screen with chain of custody",
                "E33-F09 POC & fit-test capture",
                "E33-F10 mobile batch & kiosk modes",
                "E33-F11 comparison, trend & clinician actions",
                "E33-F12 employee result summary & consent");
    }
}
