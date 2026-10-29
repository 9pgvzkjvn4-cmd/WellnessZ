package com.wellnessz;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * WellnessZ OHCM core API – a modular monolith. Each platform / business module is a Maven module under
 * {@code com.wellnessz.*}; module boundaries are enforced by ArchUnit tests and modules communicate through the
 * transactional-outbox event bus, so any module can later be extracted into its own service without redesign.
 */
@SpringBootApplication
@EnableScheduling
public class WellnessZCoreApplication {

    public static void main(String[] args) {
        SpringApplication.run(WellnessZCoreApplication.class, args);
    }
}
