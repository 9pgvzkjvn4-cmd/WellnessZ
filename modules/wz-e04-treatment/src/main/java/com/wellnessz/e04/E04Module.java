package com.wellnessz.e04;

import java.util.List;

import org.springframework.stereotype.Component;

import com.wellnessz.casekernel.module.CaseModule;

/** Registers E04 with the module registry. */
@Component
public class E04Module implements CaseModule {

    @Override
    public String code() {
        return "E04";
    }

    @Override
    public String name() {
        return "Injury & illness management, treatment coordination & partner referrals";
    }

    @Override
    public List<String> capabilities() {
        return List.of("E04-F01 treatment plan & follow-up scheduler", "E04-F02 e-referral management", "E04-F03 provider network directory", "E04-F04 pre-authorisation & cost tracking", "E04-F05 clinic medication administration & e-prescribing", "E04-F06 outcome & provider performance capture", "E04-F07 telehealth consultation");
    }
}
