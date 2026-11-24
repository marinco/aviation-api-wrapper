package io.github.marinco.aviation.domain.exception;

import java.time.Duration;

/**
 * A provider lookup failed, or we chose not to call the provider. {@link Kind} says which; the resilience guards and
 * the API layer decide what to do from it. The service serves a stale cached copy for any kind when it has one.
 */
public class UpstreamException extends RuntimeException {

    public enum Kind {
        /** Connect or read timeout. */
        TIMEOUT,
        /** Connection refused or reset, DNS failure. */
        IO_ERROR,
        /** The provider answered 5xx. */
        SERVER_ERROR,
        /** The provider answered 403; we may have been blocked. */
        FORBIDDEN,
        /** The provider answered 429; see {@link #retryAfter()}. */
        RATE_LIMITED,
        /** The provider answered another 4xx: our request was wrong. */
        CLIENT_ERROR,
        /** The provider answered 200 but the body couldn't be read. */
        BAD_RESPONSE,
        /** Not called: the circuit is open. */
        CIRCUIT_OPEN,
        /** Not called: our own rate limit is used up. */
        LOCAL_RATE_LIMIT
    }

    private final Kind kind;
    private final Duration retryAfter;

    private UpstreamException(Kind kind, String message, Duration retryAfter, Throwable cause) {
        super(message, cause);
        this.kind = kind;
        this.retryAfter = retryAfter;
    }

    public static UpstreamException timeout(Throwable cause) {
        return new UpstreamException(Kind.TIMEOUT, "Upstream timed out", null, cause);
    }

    public static UpstreamException ioError(Throwable cause) {
        return new UpstreamException(Kind.IO_ERROR, "Upstream I/O error", null, cause);
    }

    public static UpstreamException serverError(int status) {
        return new UpstreamException(Kind.SERVER_ERROR, "Upstream returned " + status, null, null);
    }

    public static UpstreamException forbidden() {
        return new UpstreamException(Kind.FORBIDDEN, "Upstream returned 403", null, null);
    }

    public static UpstreamException rateLimited(Duration retryAfter) {
        return new UpstreamException(
                Kind.RATE_LIMITED,
                "Upstream returned 429, retry after " + retryAfter.toSeconds() + " s",
                retryAfter,
                null);
    }

    public static UpstreamException clientError(int status) {
        return new UpstreamException(Kind.CLIENT_ERROR, "Upstream returned " + status, null, null);
    }

    public static UpstreamException badResponse(String message, Throwable cause) {
        return new UpstreamException(Kind.BAD_RESPONSE, message, null, cause);
    }

    public static UpstreamException circuitOpen(Duration retryAfter, Throwable cause) {
        return new UpstreamException(Kind.CIRCUIT_OPEN, "Upstream not called: circuit open", retryAfter, cause);
    }

    public static UpstreamException localRateLimit(Duration retryAfter, Throwable cause) {
        return new UpstreamException(
                Kind.LOCAL_RATE_LIMIT, "Upstream not called: local rate limit reached", retryAfter, cause);
    }

    public Kind kind() {
        return kind;
    }

    /** How long the client should wait; set for {@code RATE_LIMITED}, {@code CIRCUIT_OPEN}, {@code LOCAL_RATE_LIMIT}. */
    public Duration retryAfter() {
        return retryAfter;
    }
}
