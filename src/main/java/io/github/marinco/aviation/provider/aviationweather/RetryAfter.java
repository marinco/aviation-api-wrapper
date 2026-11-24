package io.github.marinco.aviation.provider.aviationweather;

import java.time.Clock;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

/** Parses a {@code Retry-After} header, which is either delta-seconds or an HTTP-date. */
final class RetryAfter {

    /** Used when a 429 carries no usable header: long enough that we don't retry it. */
    static final Duration DEFAULT = Duration.ofSeconds(60);

    private RetryAfter() {}

    static Duration parse(String header, Clock clock) {
        if (header == null || header.isBlank()) {
            return DEFAULT;
        }
        String value = header.trim();
        try {
            return Duration.ofSeconds(Math.max(0, Long.parseLong(value)));
        } catch (NumberFormatException notSeconds) {
            try {
                ZonedDateTime at = ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME);
                Duration wait = Duration.between(clock.instant(), at.toInstant());
                return wait.isNegative() ? Duration.ZERO : wait;
            } catch (DateTimeParseException notDate) {
                return DEFAULT;
            }
        }
    }
}
