package com.wellnessz.e31.terminology;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

/**
 * Terminology and clinical reference data of the engine (E31-F04): MDC → LOINC / UCUM / SNOMED CT maps of the active
 * release, UCUM unit conversions, reference ranges, GLI-2012 coefficients and the OSHA Appendix F age-correction table.
 */
public interface Terminology {

    String releaseVersion();

    /** A code (LOINC, MDC or LOCAL) is listed in the active release. */
    boolean known(String code);

    Optional<Entry> byLoinc(String loinc);

    Optional<Entry> byMdc(String mdc);

    /** Converts with the table {@code value·factor + shift}; empty when no conversion is registered. */
    Optional<BigDecimal> convert(BigDecimal value, String fromUnit, String toUnit);

    Optional<Range> range(String loinc, String sex, Integer ageYears);

    Optional<Gli2012.Coefficients> gli(String parameter, String sex);

    Optional<OshaAgeCorrection.Row> oshaRow(String sex, int age);

    List<Entry> entries(String system, String code);

    record Entry(String system, String code, String mdc, String loinc, String snomed, String display, String ucum) {
    }

    record Range(BigDecimal low, BigDecimal high, String text) {

        /** N | L | H. */
        public String interpret(BigDecimal v) {
            if (v == null) {
                return null;
            }
            if (low != null && v.compareTo(low) < 0) {
                return "L";
            }
            if (high != null && v.compareTo(high) > 0) {
                return "H";
            }
            return "N";
        }
    }
}
