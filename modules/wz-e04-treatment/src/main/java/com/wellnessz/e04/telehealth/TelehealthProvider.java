package com.wellnessz.e04.telehealth;

/**
 * Provider-agnostic telehealth SPI (E04-F07): turns a session reference and a single-use participant token into the join
 * link of the video / chat platform. The default implementation builds a link on {@code treatment.telehealth.base-url}.
 */
public interface TelehealthProvider {

    String joinUrl(String sessionRef, String mode, String token);
}
