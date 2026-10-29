package com.wellnessz.e04.medication;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.wellnessz.e04.support.TreatmentSupport;

/** Registers the disabled default {@link EPrescribingGateway} unless another implementation is present. */
@Configuration
public class EPrescribingConfig {

    static final String MEMO = "E04-BL-013: e-prescribing is not enabled. Clinic medicines are administered from the clinic formulary and "
            + "recorded in the OH record; prescriptions are issued on paper or by the employee's own doctor until Legal confirms the route "
            + "(national e-prescription service vs. partner pharmacy).";

    @Bean
    @ConditionalOnMissingBean(EPrescribingGateway.class)
    EPrescribingGateway noOpEPrescribingGateway(TreatmentSupport support) {
        return () -> new EPrescribingGateway.Status(false, support.configString("treatment.eprescribing.route", "NONE"), MEMO);
    }
}
