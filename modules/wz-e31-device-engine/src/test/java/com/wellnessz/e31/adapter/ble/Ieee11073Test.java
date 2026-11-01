package com.wellnessz.e31.adapter.ble;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;

class Ieee11073Test {

    @Test
    void sfloatDecodesMantissaAndSignedExponent() {
        assertThat(Ieee11073.sfloat(0x007C)).isEqualByComparingTo("124");          // exponent 0
        assertThat(Ieee11073.sfloat(0xF16E)).isEqualByComparingTo("36.6");         // exponent -1, mantissa 366
        assertThat(Ieee11073.sfloat(0x2001)).isEqualByComparingTo("100");          // exponent 2
        assertThat(Ieee11073.sfloat(0x0FFF)).isEqualByComparingTo("-1");           // negative mantissa
        assertThat(Ieee11073.sfloat(0xEFFB)).isEqualByComparingTo("-0.05");        // exponent -2, mantissa -5
    }

    @Test
    void sfloatSpecialValuesAreAbsent() {
        assertThat(Ieee11073.sfloat(0x07FF)).isNull(); // NaN
        assertThat(Ieee11073.sfloat(0x0800)).isNull(); // NRes
        assertThat(Ieee11073.sfloat(0x07FE)).isNull(); // +INF
        assertThat(Ieee11073.sfloat(0x0802)).isNull(); // -INF
        assertThat(Ieee11073.sfloat(0x0801)).isNull(); // reserved
    }

    @Test
    void floatDecodesAndSpecialValues() {
        assertThat(Ieee11073.floatValue(0xFF000170L)).isEqualByComparingTo("36.8");
        assertThat(Ieee11073.floatValue(0x00000062L)).isEqualByComparingTo("98");
        assertThat(Ieee11073.floatValue(0x00FFFFFFL)).isEqualByComparingTo("-1");
        assertThat(Ieee11073.floatValue(0x007FFFFFL)).isNull();
        assertThat(Ieee11073.floatValue(0x00800000L)).isNull();
        assertThat(Ieee11073.floatValue(0x007FFFFEL)).isNull();
        assertThat(Ieee11073.floatValue(0x00800002L)).isNull();
    }

    @Test
    void encodersRoundTrip() {
        for (String v : new String[] {"122", "76", "36.8", "0.042", "-4.5", "16.3", "2000"}) {
            assertThat(Ieee11073.sfloat(Ieee11073.toSfloat(new BigDecimal(v)))).as(v).isEqualByComparingTo(v);
            assertThat(Ieee11073.floatValue(Ieee11073.toFloat(new BigDecimal(v)))).as(v).isEqualByComparingTo(v);
        }
        assertThatThrownBy(() -> Ieee11073.toSfloat(new BigDecimal("123456.7"))).isInstanceOf(IllegalArgumentException.class);
    }
}
