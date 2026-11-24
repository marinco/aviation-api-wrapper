package io.github.marinco.aviation.domain.exception;

/** A single provider call timed out (connect or read). Retried; counts against the circuit breaker. */
public class UpstreamTimeoutException extends UpstreamException {

    public UpstreamTimeoutException(String message, Throwable cause) {
        super(message, cause);
    }
}
