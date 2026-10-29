package com.wellnessz.e03.cds;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Clinician override of a CDS alert; a reason is mandatory (alert-fatigue monitoring, E03-F05). */
public record CdsOverride(@NotBlank @Pattern(regexp = "[A-Z_]{1,30}") String alertCode, @NotBlank @Size(max = 500) String reason) {
}
