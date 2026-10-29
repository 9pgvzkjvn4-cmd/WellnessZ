package com.wellnessz.e04.medication;

/**
 * E-prescribing SPI (E04-BL-013 spike): a national e-prescription service or a partner pharmacy can be plugged in once the
 * legal route is confirmed. The default implementation is a no-op that reports the feature as disabled.
 */
public interface EPrescribingGateway {

    record Status(boolean enabled, String route, String decisionMemo) {
    }

    Status status();
}
