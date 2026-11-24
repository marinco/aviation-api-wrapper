package io.github.marinco.aviation.domain.exception;

import java.time.Duration;

/**
 * We didn't call the provider: the circuit is open, the local rate limit is used up, or too many lookups are in
 * flight. Clients should retry after {@code retryAfter}.
 */
public class UpstreamUnavailableException extends UpstreamException {

    public enum Reason {
        CIRCUIT_OPEN,
        RATE_LIMITED,
        BULKHEAD_FULL
    }

    private final Reason reason;
    private final Duration retryAfter;

    public UpstreamUnavailableException(Reason reason, Duration retryAfter, Throwable cause) {
        super("Upstream call not attempted: " + reason, cause);
        this.reason = reason;
        this.retryAfter = retryAfter;
    }

    public Reason reason() {
        return reason;
    }

    public Duration retryAfter() {
        return retryAfter;
    }
}
