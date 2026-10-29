package com.wellnessz.bff;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Backend-for-Frontend for the browser apps (employee portal and manager/HR workspace). Implements the
 * OAuth 2.0 for Browser-Based Apps BCP: the BFF is a confidential client, tokens never reach the browser,
 * the browser only holds an HttpOnly session cookie and calls same-origin {@code /bff/api/**}.
 */
@SpringBootApplication(scanBasePackages = {"com.wellnessz.bff", "com.wellnessz.common"})
public class WellnessZBffApplication {

    public static void main(String[] args) {
        SpringApplication.run(WellnessZBffApplication.class, args);
    }
}
