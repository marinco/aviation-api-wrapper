package io.github.marinco.aviation.domain.exception;

/** The connection to the provider failed (refused, reset, DNS). Retried; counts against the circuit breaker. */
public class UpstreamIoException extends UpstreamException {

    public UpstreamIoException(String message, Throwable cause) {
        super(message, cause);
    }
}
