package com.wellnessz.e03.support;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** A coded concept reference {system, code, display} (SNOMED CT, ICD-10/11, LOINC, ODH or LOCAL for device studies). */
public record CodeRef(@NotBlank @Pattern(regexp = "SNOMED_CT|ICD_10|ICD_11|LOINC|ODH|LOCAL") String system,
                      @NotBlank @Size(max = 50) @Pattern(regexp = "[A-Za-z0-9.\\-]{1,50}") String code,
                      @Size(max = 500) String display) {

    public static CodeRef of(String system, String code, String display) {
        return system == null || code == null ? null : new CodeRef(system, code, display);
    }
}
