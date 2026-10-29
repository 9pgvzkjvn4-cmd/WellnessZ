package com.wellnessz.e04.preauth;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Matches a provider invoice against the approved pre-authorisation: within the tolerance above the approved amount →
 * MATCHED, above it or in another currency → DISPUTED, nothing approved → RECEIVED (manual review).
 */
public final class InvoiceMatcher {

    private InvoiceMatcher() {
    }

    public record Match(String status, BigDecimal variance) {
    }

    public static Match match(BigDecimal amount, String currency, BigDecimal approved, String approvedCurrency, BigDecimal tolerancePct) {
        if (approved == null) {
            return new Match("RECEIVED", null);
        }
        if (approvedCurrency != null && !approvedCurrency.equals(currency)) {
            return new Match("DISPUTED", null);
        }
        BigDecimal variance = amount.subtract(approved).setScale(2, RoundingMode.HALF_UP);
        BigDecimal ceiling = approved.multiply(BigDecimal.ONE.add(tolerancePct.movePointLeft(2)));
        return new Match(amount.compareTo(ceiling) <= 0 ? "MATCHED" : "DISPUTED", variance);
    }
}
