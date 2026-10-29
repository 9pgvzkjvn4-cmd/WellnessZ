package com.wellnessz.e03.results;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ResultFlagsTest {

    @Test
    void interpretationCodesWin() {
        assertThat(ResultFlags.flag("N", "999", "1-2")).isEqualTo("NORMAL");
        assertThat(ResultFlags.flag("HH", null, null)).isEqualTo("CRITICAL");
        assertThat(ResultFlags.flag("LL", null, null)).isEqualTo("CRITICAL");
        assertThat(ResultFlags.flag("A", null, null)).isEqualTo("ABNORMAL");
        assertThat(ResultFlags.flag("positive", null, null)).isEqualTo("ABNORMAL");
        assertThat(ResultFlags.flag("H", null, null)).isEqualTo("HIGH");
    }

    @Test
    void numericValuesAreComparedWithTheReferenceRange() {
        assertThat(ResultFlags.flag(null, "11.2", "3.9-7.8")).isEqualTo("HIGH");
        assertThat(ResultFlags.flag(null, "3.1", "3.9-7.8")).isEqualTo("LOW");
        assertThat(ResultFlags.flag(null, "5.0", "3.9-7.8")).isEqualTo("NORMAL");
        assertThat(ResultFlags.flag(null, "-3", "-5-0")).isEqualTo("NORMAL");
        assertThat(ResultFlags.flag(null, "6.0", "<5.7")).isEqualTo("HIGH");
        assertThat(ResultFlags.flag(null, "5.7", "<=5.7")).isEqualTo("NORMAL");
        assertThat(ResultFlags.flag(null, "55", ">60")).isEqualTo("LOW");
        assertThat(ResultFlags.flag(null, "60", ">=60")).isEqualTo("NORMAL");
    }

    @Test
    void nonNumericOrMissingRangesAreNotFlagged() {
        assertThat(ResultFlags.flag(null, "Not detected", "Not detected")).isEqualTo("NORMAL");
        assertThat(ResultFlags.flag(null, "12", null)).isEqualTo("NORMAL");
        assertThat(ResultFlags.flag(null, "12", "abc")).isEqualTo("NORMAL");
        assertThat(ResultFlags.numeric("1e3")).isEqualTo(1000.0);
        assertThat(ResultFlags.numeric("x".repeat(40))).isNull();
    }
}
