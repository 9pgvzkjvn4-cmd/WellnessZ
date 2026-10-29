package com.wellnessz.e04.telehealth;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.wellnessz.e04.support.TreatmentSupport;

/** Default link-based {@link TelehealthProvider} unless a platform adapter is registered. */
@Configuration
public class LinkTelehealthProviderConfig {

    @Bean
    @ConditionalOnMissingBean(TelehealthProvider.class)
    TelehealthProvider linkTelehealthProvider(TreatmentSupport support) {
        return (ref, mode, token) -> {
            String base = support.configString("treatment.telehealth.base-url", "https://telehealth.wellnessz.local/room/");
            return (base.endsWith("/") ? base : base + "/") + URLEncoder.encode(ref, StandardCharsets.UTF_8) + "?mode="
                    + ("CHAT".equals(mode) ? "chat" : "video") + "&token=" + URLEncoder.encode(token, StandardCharsets.UTF_8);
        };
    }
}
