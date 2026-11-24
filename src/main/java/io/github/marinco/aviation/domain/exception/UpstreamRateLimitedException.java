package io.github.marinco.aviation.domain.exception;

import java.time.Duration;

/** The provider answered 429. Retried only when {@code retryAfter} is short; never trips the circuit breaker. */
public class UpstreamRateLimitedException extends UpstreamException {

    private final Duration retryAfter;

    public UpstreamRateLimitedException(Duration retryAfter) {
        super("Upstream rate limited us; retry after " + retryAfter.toSeconds() + " s");
        this.retryAfter = retryAfter;
    }

    public Duration retryAfter() {
        return retryAfter;
    }
}
