package com.wellnessz.e08.support;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** Contract money value: decimal string with two places and an ISO 4217 currency, e.g. {@code {"amount":"1234.50","currency":"GBP"}}. */
public record Money(String amount, String currency) {

    public static Money of(BigDecimal amount, String currency) {
        return amount == null || currency == null ? null : new Money(scale(amount).toPlainString(), currency);
    }

    public BigDecimal value() {
        return new BigDecimal(amount);
    }

    public static BigDecimal scale(BigDecimal v) {
        return v == null ? null : v.setScale(2, RoundingMode.HALF_UP);
    }
}
