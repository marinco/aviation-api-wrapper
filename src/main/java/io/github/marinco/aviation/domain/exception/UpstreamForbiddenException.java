package io.github.marinco.aviation.domain.exception;

/** The provider answered 403; we may have been blocked. Not retried; counts against the circuit breaker. */
public class UpstreamForbiddenException extends UpstreamException {

    public UpstreamForbiddenException() {
        super("Upstream returned 403");
    }
}
