package com.wellnessz.e04.provider;

import java.time.LocalDate;

import com.wellnessz.e04.model.ProviderContract;

/** Contract status of a provider: ACTIVE, EXPIRING (ends within the window), EXPIRED or NONE (no current contract). */
public final class ContractStatus {

    private ContractStatus() {
    }

    public static String of(ProviderContract c, LocalDate today, int expiringDays) {
        if (c == null || c.getStartDate().isAfter(today)) {
            return "NONE";
        }
        if (c.getEndDate().isBefore(today)) {
            return "EXPIRED";
        }
        return c.getEndDate().isAfter(today.plusDays(expiringDays)) ? "ACTIVE" : "EXPIRING";
    }

    /** Referrals may be placed under the contract (ACTIVE or EXPIRING). */
    public static boolean contracted(String status) {
        return "ACTIVE".equals(status) || "EXPIRING".equals(status);
    }
}
