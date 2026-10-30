package com.wellnessz.e11.exposure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDate;

import org.junit.jupiter.api.Test;

class ProtocolTimersTest {

    static final Instant EXPOSED = Instant.parse("2026-10-30T08:00:00Z");

    @Test
    void dueTimesAndSerologySchedule() {
        assertThat(ProtocolTimers.dueAt(EXPOSED, 60)).isEqualTo(Instant.parse("2026-10-30T09:00:00Z"));
        assertThat(ProtocolTimers.dueAt(EXPOSED, 120)).isEqualTo(Instant.parse("2026-10-30T10:00:00Z"));
        assertThat(ProtocolTimers.dueOn(EXPOSED, "P42D")).isEqualTo(LocalDate.of(2026, 12, 11));
        assertThat(ProtocolTimers.dueOn(EXPOSED, "P3M")).isEqualTo(LocalDate.of(2027, 1, 30));
        assertThat(ProtocolTimers.dueOn(EXPOSED, "P6M")).isEqualTo(LocalDate.of(2027, 4, 30));
        assertThatThrownBy(() -> ProtocolTimers.dueOn(EXPOSED, "six weeks")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void timerStatus() {
        Instant due = ProtocolTimers.dueAt(EXPOSED, 120);
        assertThat(ProtocolTimers.status(due, null, due.minusSeconds(60))).isEqualTo("PENDING");
        assertThat(ProtocolTimers.status(due, null, due)).isEqualTo("PENDING");
        assertThat(ProtocolTimers.status(due, null, due.plusSeconds(1))).isEqualTo("OVERDUE");
        assertThat(ProtocolTimers.status(due, due.plusSeconds(600), due.plusSeconds(3600))).isEqualTo("DONE");
        LocalDate dueOn = LocalDate.of(2026, 12, 11);
        assertThat(ProtocolTimers.status(dueOn, false, dueOn)).isEqualTo("PENDING");
        assertThat(ProtocolTimers.status(dueOn, false, dueOn.plusDays(1))).isEqualTo("OVERDUE");
        assertThat(ProtocolTimers.status(dueOn, true, dueOn.plusDays(10))).isEqualTo("DONE");
    }

    @Test
    void alertsOncePerLevel() {
        Instant due = ProtocolTimers.dueAt(EXPOSED, 120);
        assertThat(ProtocolTimers.alert(due, due.minusSeconds(31 * 60), 30, false, false)).isEmpty();
        assertThat(ProtocolTimers.alert(due, due.minusSeconds(30 * 60), 30, false, false)).contains(ProtocolTimers.Alert.DUE_SOON);
        assertThat(ProtocolTimers.alert(due, due.minusSeconds(10 * 60), 30, true, false)).isEmpty();
        assertThat(ProtocolTimers.alert(due, due.plusSeconds(60), 30, true, false)).contains(ProtocolTimers.Alert.OVERDUE);
        assertThat(ProtocolTimers.alert(due, due.plusSeconds(60), 30, false, false)).contains(ProtocolTimers.Alert.OVERDUE);
        assertThat(ProtocolTimers.alert(due, due.plusSeconds(3600), 30, true, true)).isEmpty();
    }

    @Test
    void protocolWindowKpi() {
        Instant due = ProtocolTimers.dueAt(EXPOSED, 120);
        assertThat(ProtocolTimers.withinWindow(due, due.minusSeconds(1))).isTrue();
        assertThat(ProtocolTimers.withinWindow(due, due)).isTrue();
        assertThat(ProtocolTimers.withinWindow(due, due.plusSeconds(1))).isFalse();
        assertThat(ProtocolTimers.withinWindow(due, null)).isFalse();
    }

    @Test
    void incidentExposureTypesMapToPathways() {
        assertThat(ExposurePathwayService.pathwayFor("NEEDLESTICK")).contains("NEEDLESTICK_BBP");
        assertThat(ExposurePathwayService.pathwayFor("body_fluid")).contains("NEEDLESTICK_BBP");
        assertThat(ExposurePathwayService.pathwayFor("CHEMICAL")).contains("CHEMICAL_DGR");
        assertThat(ExposurePathwayService.pathwayFor("FUME")).contains("CABIN_FUME");
        assertThat(ExposurePathwayService.pathwayFor("HEAT")).contains("HEAT_ILLNESS");
        assertThat(ExposurePathwayService.pathwayFor("INSECT_STING")).contains("BITE_STING");
        assertThat(ExposurePathwayService.pathwayFor("NOISE")).isEmpty();
        assertThat(ExposurePathwayService.pathwayFor(null)).isEmpty();
    }
}
