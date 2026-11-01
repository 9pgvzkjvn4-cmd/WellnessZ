package com.wellnessz.e31.pipeline;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.wellnessz.casekernel.device.DeviceObservation;
import com.wellnessz.e31.TestSupport;
import com.wellnessz.e31.adapter.ParseContext;
import com.wellnessz.e31.adapter.ParsedRecord;
import com.wellnessz.e31.driver.BundledDrivers;
import com.wellnessz.e31.driver.DriverPackage;
import com.wellnessz.e31.support.EngineException;

/** NORMALISE: codes, unit conversion, rounding, interpretation, GLI-2012, BMI, OSHA row and clock-skew correction. */
class NormaliserTest {

    static final Instant RECEIVED = Instant.parse("2026-10-30T12:00:00Z");
    final BundledDrivers bundled = TestSupport.bundled();

    Normaliser.Result normalise(String pkg, String dataset, Instant edgeClock) {
        DriverPackage p = bundled.get(pkg, null).orElseThrow();
        ParsedRecord r = TestSupport.adapters().parse(new ParseContext(p, TestSupport.golden().content("golden/" + dataset), null, Map.of(), RECEIVED,
                ZoneOffset.UTC)).get(0);
        return Normaliser.normalise(p, r, TestSupport.terminology(), new Normaliser.Context(RECEIVED, null, edgeClock, 120));
    }

    @Test
    void everyComponentCarriesLoincUcumAndMdcWhereKnown() {
        Normaliser.Result n = normalise("vitals.welchallyn.csm-pcd01", "vitals/csm-pcd01/normal.hl7", null);
        DeviceObservation.Component sys = n.component("systolic").orElseThrow();
        assertThat(sys.loincCode()).isEqualTo("8480-6");
        assertThat(sys.mdcCode()).isEqualTo("MDC_PRESS_BLD_NONINV_SYS");
        assertThat(sys.ucum()).isEqualTo("mm[Hg]");
        assertThat(sys.interpretation()).isEqualTo("N");
        assertThat(n.component("temperatureC").orElseThrow().value()).isEqualByComparingTo("36.8");
        assertThat(n.effectiveAt()).isEqualTo(Instant.parse("2026-10-30T09:14:05Z"));
        assertThat(n.clockSkewMillis()).isZero();
    }

    @Test
    void driverConversionKpaAndTableConversionsFahrenheitAndImperial() {
        Normaliser.Result bp = normalise("vitals.and.ua651ble-blp", "vitals/ua651ble-blp/edge.json", null);
        assertThat(bp.component("systolic").orElseThrow().value()).isEqualByComparingTo("122");
        assertThat(bp.component("systolic").orElseThrow().ucum()).isEqualTo("mm[Hg]");
        assertThat(normalise("vitals.withings.thermo-htp", "vitals/thermo-htp/edge.json", null).component("temperatureC").orElseThrow().value())
                .isEqualByComparingTo("37.0");
        Normaliser.Result w = normalise("vitals.tanita.wss-ble", "vitals/tanita-wss/edge.json", null);
        assertThat(w.component("weightKg").orElseThrow().value()).isEqualByComparingTo("78.4");
        assertThat(w.component("heightCm").orElseThrow().value()).isEqualByComparingTo("180");
    }

    @Test
    void bmiIsComputedFromWeightAndHeight() {
        Normaliser.Result n = normalise("vitals.withings.bodycomp-bcs", "vitals/withings-bcs/normal.json", null);
        assertThat(n.component("bmi").orElseThrow().value()).isEqualByComparingTo("24.2");
        assertThat(n.component("bmi").orElseThrow().loincCode()).isEqualTo("39156-5");
    }

    @Test
    void spirometryGetsGliPredictedValuesLlnAndPercentPredicted() {
        Normaliser.Result n = normalise("spiro.ndd.easyone-connect-gdt", "spirometry/easyone-gdt/normal.gdt", null);
        assertThat(n.demographics().ageYears()).isEqualTo(45);
        assertThat(n.component("fev1PredictedL").orElseThrow().loincCode()).isEqualTo("20149-1");
        assertThat(n.component("fev1PredictedL").orElseThrow().value().doubleValue()).isBetween(3.6, 4.4);
        assertThat(n.component("fev1LlnL").orElseThrow().value()).isLessThan(n.component("fev1PredictedL").orElseThrow().value());
        assertThat(n.component("fvcPredictedL")).isPresent();
        assertThat(n.component("fev1PctPredicted").orElseThrow().value().doubleValue()).isBetween(85.0, 110.0);
        assertThat(n.component("ratioLln")).isPresent();
    }

    @Test
    void audiometryCarriesTheOshaAgeCorrectionRow() {
        Normaliser.Result n = normalise("audio.benson.cca200-db", "audiometry/cca200-db/normal.csv", null);
        assertThat(n.derived()).containsEntry("ageYears", 41).containsEntry("sex", "F");
        assertThat(n.derived().get("ageCorrection")).isEqualTo(Map.of("1000", 10, "2000", 8, "3000", 8, "4000", 8, "6000", 13));
    }

    @Test
    void clockSkewCorrection() {
        Normaliser.Context ctx = new Normaliser.Context(RECEIVED, null, RECEIVED.plusSeconds(300), 120);
        Normaliser.Timing t = Normaliser.timing(Instant.parse("2026-10-30T11:59:00Z"), ctx);
        assertThat(t.skewMillis()).isEqualTo(300_000);
        assertThat(t.effectiveAt()).isEqualTo(Instant.parse("2026-10-30T11:54:00Z"));
        // device clock far in the future: falls back to the receipt time, the difference counts as skew
        Normaliser.Timing future = Normaliser.timing(RECEIVED.plusSeconds(3600), new Normaliser.Context(RECEIVED, null, null, 120));
        assertThat(future.effectiveAt()).isEqualTo(RECEIVED);
        assertThat(future.skewMillis()).isEqualTo(3_600_000);
        // vendor exports are legitimately older than the receipt
        Normaliser.Timing old = Normaliser.timing(RECEIVED.minusSeconds(86_400), new Normaliser.Context(RECEIVED, null, null, 120));
        assertThat(old.skewMillis()).isZero();
        assertThat(old.effectiveAt()).isEqualTo(RECEIVED.minusSeconds(86_400));
        // no device time: the capture time at the edge, corrected
        Normaliser.Timing none = Normaliser.timing(null, new Normaliser.Context(RECEIVED, RECEIVED.minusSeconds(10), RECEIVED.plusSeconds(5), 120));
        assertThat(none.effectiveAt()).isEqualTo(RECEIVED.minusSeconds(15));
    }

    @Test
    void unmappedQuantityAndUnconvertibleUnitAreDeterministicFailures() {
        DriverPackage p = bundled.get("vitals.welchallyn.csm-pcd01", null).orElseThrow();
        ParsedRecord unknown = new ParsedRecord(0, Map.of("x", new ParsedRecord.Value("x", "QUANTITY", null, BigDecimal.ONE, "1")),
                ParsedRecord.Identity.EMPTY, Map.of(), List.of());
        assertThatThrownBy(() -> Normaliser.normalise(p, unknown, TestSupport.terminology(), new Normaliser.Context(RECEIVED, null, null, 120)))
                .isInstanceOf(EngineException.class).extracting("code").isEqualTo("mapping_missing");
        ParsedRecord furlong = new ParsedRecord(0, Map.of("pulse", new ParsedRecord.Value("pulse", "QUANTITY", null, BigDecimal.ONE, "furlong")),
                ParsedRecord.Identity.EMPTY, Map.of(), List.of());
        assertThatThrownBy(() -> Normaliser.normalise(p, furlong, TestSupport.terminology(), new Normaliser.Context(RECEIVED, null, null, 120)))
                .isInstanceOf(EngineException.class).extracting("code").isEqualTo("unit_unconvertible");
        ParsedRecord flag = new ParsedRecord(0, Map.of("unitKpa", new ParsedRecord.Value("unitKpa", "BOOLEAN", "true", null, null)),
                ParsedRecord.Identity.EMPTY, Map.of(), List.of());
        assertThat(Normaliser.normalise(p, flag, TestSupport.terminology(), new Normaliser.Context(RECEIVED, null, null, 120)).components()).isEmpty();
    }

    @Test
    void ageFromDateOfBirth() {
        ParsedRecord.Identity id = new ParsedRecord.Identity(null, null, null, null, null, null, LocalDate.of(1980, 11, 2), "M", null, null, null, null,
                null, null);
        assertThat(Normaliser.demographics(id, RECEIVED).ageYears()).isEqualTo(45);
    }
}
