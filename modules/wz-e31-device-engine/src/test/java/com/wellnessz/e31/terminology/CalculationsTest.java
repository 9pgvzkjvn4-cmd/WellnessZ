package com.wellnessz.e31.terminology;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.wellnessz.e31.TestSupport;

/** GLI-2012 LMS calculation, OSHA Appendix F age correction / STS, unit conversion and reference ranges. */
class CalculationsTest {

    final Terminology t = TestSupport.terminology();

    @Test
    void gliPredictsPlausibleAdultValuesWithLlnBelowPredicted() {
        Gli2012.Prediction fev1 = Gli2012.predict(t.gli("FEV1", "M").orElseThrow(), 40, 175);
        Gli2012.Prediction fvc = Gli2012.predict(t.gli("FVC", "M").orElseThrow(), 40, 175);
        Gli2012.Prediction ratio = Gli2012.predict(t.gli("FEV1FVC", "M").orElseThrow(), 40, 175);
        assertThat(fev1.predicted()).isBetween(3.7, 4.1);
        assertThat(fvc.predicted()).isBetween(4.6, 5.1);
        assertThat(ratio.predicted()).isBetween(0.76, 0.83);
        assertThat(fev1.lln()).isLessThan(fev1.predicted()).isGreaterThan(fev1.predicted() * 0.7);
        assertThat(fev1.zScore(fev1.predicted())).isCloseTo(0.0, within(1e-9));
        assertThat(fev1.zScore(fev1.lln())).isCloseTo(Gli2012.Z_LLN, within(1e-6));
        assertThat(fev1.percentPredicted(fev1.predicted())).isCloseTo(100.0, within(1e-9));
        Gli2012.Prediction female = Gli2012.predict(t.gli("FEV1", "F").orElseThrow(), 40, 165);
        assertThat(female.predicted()).isBetween(2.7, 3.2).isLessThan(fev1.predicted());
        // ageing lowers FEV1 at the same height
        assertThat(Gli2012.predict(t.gli("FEV1", "M").orElseThrow(), 70, 175).predicted()).isLessThan(fev1.predicted());
    }

    @Test
    void gliMatchesTheLmsFormulaOfTheTable() {
        Gli2012.Coefficients c = new Gli2012.Coefficients("FEV1", "M", 0.8866, 0.085, -10.342, 2.2196, 0.0574, -2.3268, 0.0798,
                List.of(new Gli2012.SplinePoint(20, 0.02, 0.01), new Gli2012.SplinePoint(60, 0.06, 0.03)));
        Gli2012.Prediction p = Gli2012.predict(c, 40, 175);
        double lnA = Math.log(40);
        double m = Math.exp(-10.342 + 2.2196 * Math.log(175) + 0.0574 * lnA + 0.04);   // spline interpolated half way
        double s = Math.exp(-2.3268 + 0.0798 * lnA + 0.02);
        double l = 0.8866 + 0.085 * lnA;
        assertThat(p.predicted()).isCloseTo(m, within(1e-12));
        assertThat(p.s()).isCloseTo(s, within(1e-12));
        assertThat(p.lln()).isCloseTo(m * Math.pow(1 - 1.645 * l * s, 1 / l), within(1e-12));
        assertThatThrownBy(() -> Gli2012.predict(c, 2, 175)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void oshaAppendixFTablesAndAgeCorrectedSts() {
        assertThat(t.oshaRow("M", 40).orElseThrow().asMap()).containsEntry("4000", 14).containsEntry("2000", 6);
        assertThat(t.oshaRow("F", 60).orElseThrow().hz6000()).isEqualTo(22);
        assertThat(t.oshaRow("M", 18).orElseThrow().age()).isEqualTo(20);   // younger use 20
        assertThat(t.oshaRow("M", 75).orElseThrow().age()).isEqualTo(60);   // older use 60
        Map<String, Double> base = Map.of("2000", 10.0, "3000", 10.0, "4000", 15.0);
        Map<String, Double> now = Map.of("2000", 20.0, "3000", 25.0, "4000", 30.0);  // raw shift 13.3 dB
        double shift = OshaAgeCorrection.ageCorrectedShift(now, base, "M", 50, 30, (sex, age) -> t.oshaRow(sex, age).orElseThrow());
        // correction 50 vs 30 years (male): (9-4)+(16-6)+(22-9) = 28 / 3 = 9.33 → 13.33 - 9.33 = 4.0
        assertThat(shift).isCloseTo(4.0, within(1e-9));
        assertThat(OshaAgeCorrection.sts(shift)).isFalse();
        assertThat(OshaAgeCorrection.sts(OshaAgeCorrection.ageCorrectedShift(now, base, "M", 31, 30,
                (sex, age) -> t.oshaRow(sex, age).orElseThrow()))).isTrue();
    }

    @Test
    void ucumConversionsAndReferenceRanges() {
        assertThat(t.convert(new BigDecimal("16"), "kPa", "mm[Hg]").orElseThrow().doubleValue()).isCloseTo(120.01, within(0.01));
        assertThat(t.convert(new BigDecimal("98.6"), "[degF]", "Cel").orElseThrow().doubleValue()).isCloseTo(37.0, within(0.001));
        assertThat(t.convert(new BigDecimal("1.80"), "m", "cm").orElseThrow()).isEqualByComparingTo("180");
        assertThat(t.convert(BigDecimal.ONE, "kg", "furlong")).isEmpty();
        assertThat(t.convert(BigDecimal.TEN, "L", "L")).contains(BigDecimal.TEN);
        assertThat(t.range("4548-4", "F", 40).orElseThrow().interpret(new BigDecimal("6.4"))).isEqualTo("H");
        assertThat(t.range("8480-6", "M", 40).orElseThrow().interpret(new BigDecimal("120"))).isEqualTo("N");
        assertThat(t.known("MDC_PRESS_BLD_NONINV_SYS")).isTrue();
        assertThat(t.known("LOCAL:FIT_FACTOR_OVERALL")).isTrue();
        assertThat(t.known("99999-9")).isFalse();
        assertThat(t.byLoinc("8480-6").orElseThrow().mdc()).isEqualTo("MDC_PRESS_BLD_NONINV_SYS");
    }
}
