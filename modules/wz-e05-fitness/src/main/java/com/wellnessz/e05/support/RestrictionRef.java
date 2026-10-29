package com.wellnessz.e05.support;

import java.math.BigDecimal;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/** Restriction references used across E05: the request form (code + value) and the functional view (code, label, value). */
public final class RestrictionRef {

    private RestrictionRef() {
    }

    /** A restriction chosen from the catalogue; {@code value} is required for numeric restrictions (e.g. 10 kg). */
    public record Input(@NotBlank @Pattern(regexp = "[A-Z0-9_]{1,40}") String code,
                        @DecimalMin("0") @DecimalMax("500") BigDecimal value) {
    }

    /** Functional wording only – e.g. {@code {code:"LIFT_MAX_KG", label:"No lifting above 10 kg", value:10}}. */
    public record View(String code, String label, BigDecimal value) {
    }
}
