package com.wellnessz.e04.support;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Locale;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import com.wellnessz.common.error.ApiException;

/** Money as exchanged with the UIs: decimal string amount and ISO 4217 currency (contract conventions). */
public record Money(@NotNull @Pattern(regexp = "\\d{1,10}(\\.\\d{1,2})?") String amount, @NotBlank @Pattern(regexp = "[A-Z]{3}") String currency) {

    public static Money of(BigDecimal amount, String currency) {
        return amount == null ? null : new Money(amount.setScale(2, RoundingMode.HALF_UP).toPlainString(), currency);
    }

    public BigDecimal value() {
        try {
            BigDecimal v = new BigDecimal(amount);
            if (v.signum() <= 0) {
                throw ApiException.badRequest("invalid_amount", "Amounts must be positive");
            }
            return v.setScale(2, RoundingMode.HALF_UP);
        } catch (NumberFormatException e) {
            throw ApiException.badRequest("invalid_amount", "Invalid amount");
        }
    }

    public String normalisedCurrency() {
        return currency.toUpperCase(Locale.ROOT);
    }
}
