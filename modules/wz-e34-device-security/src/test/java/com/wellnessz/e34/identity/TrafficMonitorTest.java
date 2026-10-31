package com.wellnessz.e34.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import com.wellnessz.casekernel.device.DeviceCodes;
import com.wellnessz.e34.anomaly.AnomalyRuleRepository;
import com.wellnessz.e34.anomaly.SiteHours;
import com.wellnessz.e34.events.SecurityEventService;

/** Rate limits against baseline and ceiling, one spike event per minute, throttling after a sustained spike, first-seen events. */
class TrafficMonitorTest {

    static final class MutableClock extends Clock {
        Instant now = Instant.parse("2026-10-31T10:00:05Z");

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    @Test
    void dynamicLimitUsesFiveTimesP95CappedByTheCeiling() {
        assertThat(TrafficMonitor.dynamicLimit(-1, 600)).isEqualTo(600);
        assertThat(TrafficMonitor.dynamicLimit(2, 600)).isEqualTo(30);
        assertThat(TrafficMonitor.dynamicLimit(40, 600)).isEqualTo(200);
        assertThat(TrafficMonitor.dynamicLimit(400, 600)).isEqualTo(600);
    }

    @Test
    void spikeIsReportedOncePerMinuteAndSustainedSpikeThrottles() {
        MutableClock clock = new MutableClock();
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        EndpointRepository repo = mock(EndpointRepository.class);
        AnomalyRuleRepository rules = mock(AnomalyRuleRepository.class);
        SiteHours hours = mock(SiteHours.class);
        SecurityEventService events = mock(SecurityEventService.class);
        when(rules.get("MESSAGE_RATE_SPIKE")).thenReturn(Optional.of(new AnomalyRuleRepository.AnomalyRule("MESSAGE_RATE_SPIKE", "d", "MEDIUM", true,
                5, 2, "THROTTLE", null, "t", Instant.EPOCH)));
        when(repo.markFirstSeen(eq("EP-1"), any())).thenReturn(1);
        TrafficMonitor m = new TrafficMonitor(jdbc, repo, rules, hours, events, clock);
        String path = "/api/v1/device-ingest/hl7v2";

        for (int i = 0; i < 8; i++) {
            assertThat(m.record("EP-1", "LHR", path).throttled()).isFalse();
        }
        verify(events, times(1)).record(eq("MESSAGE_RATE_SPIKE"), eq(DeviceCodes.Severity.MEDIUM), eq("EP-1"), isNull(), anyString(),
                anyString(), anyMap());
        verify(events, times(1)).record(eq("ENDPOINT_FIRST_AUTHENTICATED"), any(), eq("EP-1"), isNull(), anyString(), anyString(), anyMap());

        // second minute over the limit -> two consecutive minutes = sustained window -> throttled from the third minute
        clock.now = clock.now.plusSeconds(60);
        for (int i = 0; i < 8; i++) {
            m.record("EP-1", "LHR", path);
        }
        clock.now = clock.now.plusSeconds(60);
        assertThat(m.record("EP-1", "LHR", path).throttled()).isTrue();
        // a quiet minute lifts the throttle again
        clock.now = clock.now.plusSeconds(120);
        assertThat(m.record("EP-1", "LHR", path).throttled()).isFalse();
        verify(events, never()).record(eq("OFF_HOURS_TRAFFIC"), any(), any(), any(), any(), any(), anyMap());
        assertThat(m.flush()).isGreaterThan(0);
        verify(repo, atLeastOnce()).touchLastSeen(eq("EP-1"), any());
    }

    @Test
    void offHoursDataTrafficIsReportedOncePerDay() {
        MutableClock clock = new MutableClock();
        AnomalyRuleRepository rules = mock(AnomalyRuleRepository.class);
        when(rules.get(anyString())).thenReturn(Optional.empty());
        SiteHours hours = mock(SiteHours.class);
        when(hours.outsideHours(eq("LHR"), any())).thenReturn(true);
        SecurityEventService events = mock(SecurityEventService.class);
        TrafficMonitor m = new TrafficMonitor(mock(JdbcTemplate.class), mock(EndpointRepository.class), rules, hours, events, clock);
        m.record("EP-2", "LHR", "/api/v1/device-ingest/heartbeat");
        verify(events, never()).record(eq("OFF_HOURS_TRAFFIC"), any(), any(), any(), any(), any(), anyMap());
        m.record("EP-2", "LHR", "/api/v1/device-ingest/hl7v2");
        m.record("EP-2", "LHR", "/api/v1/device-ingest/hl7v2");
        verify(events, times(1)).record(eq("OFF_HOURS_TRAFFIC"), eq(DeviceCodes.Severity.LOW), eq("EP-2"), isNull(), anyString(), anyString(), anyMap());
    }
}
