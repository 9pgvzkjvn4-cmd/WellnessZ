package com.wellnessz.gateway.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.SplittableRandom;

import org.junit.jupiter.api.Test;

class BackoffTest {

    @Test
    void delaysGrowExponentiallyWithinJitterBoundsAndAreCapped() {
        Backoff b = new Backoff(Duration.ofSeconds(1), Duration.ofSeconds(60), new SplittableRandom(42));
        long[] ceilings = {1, 2, 4, 8, 16, 32, 60, 60, 60};
        for (long c : ceilings) {
            Duration d = b.next();
            assertThat(d.toMillis()).isBetween(c * 500, c * 1000);
        }
        assertThat(b.attempts()).isEqualTo(9);
    }

    @Test
    void resetStartsAgainFromTheInitialDelay() {
        Backoff b = new Backoff(Duration.ofSeconds(1), Duration.ofSeconds(60), new SplittableRandom(1));
        for (int i = 0; i < 6; i++) {
            b.next();
        }
        b.reset();
        assertThat(b.next().toMillis()).isBetween(500L, 1000L);
    }

    @Test
    void retryAfterLongerThanTheComputedDelayWins() {
        Backoff b = new Backoff(Duration.ofSeconds(1), Duration.ofSeconds(60), new SplittableRandom(1));
        assertThat(b.next(Duration.ofSeconds(30))).isEqualTo(Duration.ofSeconds(30));
        assertThat(b.next(Duration.ofMillis(1)).toMillis()).isBetween(1000L, 2000L);
    }

    @Test
    void manyAttemptsDoNotOverflow() {
        Backoff b = new Backoff(Duration.ofMillis(1), Duration.ofMinutes(5), new SplittableRandom(3));
        for (int i = 0; i < 200; i++) {
            assertThat(b.next()).isLessThanOrEqualTo(Duration.ofMinutes(5)).isPositive();
        }
    }

    @Test
    void parsesRetryAfterSeconds() {
        assertThat(Backoff.parseRetryAfter("12")).isEqualTo(Duration.ofSeconds(12));
        assertThat(Backoff.parseRetryAfter("Wed, 21 Oct 2026 07:28:00 GMT")).isNull();
        assertThat(Backoff.parseRetryAfter(null)).isNull();
        assertThat(Backoff.parseRetryAfter("999999")).isEqualTo(Duration.ofHours(1));
    }

    @Test
    void invalidConfigurationIsRejected() {
        assertThatThrownBy(() -> new Backoff(Duration.ZERO, Duration.ofSeconds(1), new SplittableRandom()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Backoff(Duration.ofSeconds(2), Duration.ofSeconds(1), new SplittableRandom()))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
