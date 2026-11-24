package io.github.marinco.aviation.provider.aviationweather;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class RetryAfterTest {

    private final Clock clock = Clock.fixed(Instant.parse("2026-11-24T12:00:00Z"), ZoneOffset.UTC);

    @Test
    void parsesSeconds() {
        assertThat(RetryAfter.parse("2", clock)).isEqualTo(Duration.ofSeconds(2));
    }

    @Test
    void parsesHttpDate() {
        assertThat(RetryAfter.parse("Tue, 24 Nov 2026 12:00:30 GMT", clock)).isEqualTo(Duration.ofSeconds(30));
    }

    @Test
    void fallsBackToDefaultWhenMissingOrGarbage() {
        assertThat(RetryAfter.parse(null, clock)).isEqualTo(RetryAfter.DEFAULT);
        assertThat(RetryAfter.parse("soon", clock)).isEqualTo(RetryAfter.DEFAULT);
    }

    @Test
    void hugeValuesAreCapped() {
        assertThat(RetryAfter.parse("99999999999999999999", clock)).isEqualTo(RetryAfter.DEFAULT); // not a long
        assertThat(RetryAfter.parse("9223372036854775807", clock)).isEqualTo(RetryAfter.MAX);
        assertThat(RetryAfter.parse("86400", clock)).isEqualTo(RetryAfter.MAX);
    }

    @Test
    void pastDateMeansNoWait() {
        assertThat(RetryAfter.parse("Tue, 24 Nov 2026 11:00:00 GMT", clock)).isZero();
    }
}
