package io.github.marinco.aviation.domain.exception;

/** The provider answered 5xx. Retried; counts against the circuit breaker. */
public class UpstreamServerException extends UpstreamException {

    private final int status;

    public UpstreamServerException(int status) {
        super("Upstream returned " + status);
        this.status = status;
    }

    public int status() {
        return status;
    }
}
