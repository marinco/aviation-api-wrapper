package io.github.marinco.aviation.domain.exception;

/**
 * Base type for every way a provider lookup can fail. The service serves a stale copy for any of these when it has
 * one; the API layer maps each subtype to a status code.
 */
public abstract class UpstreamException extends RuntimeException {

    protected UpstreamException(String message) {
        super(message);
    }

    protected UpstreamException(String message, Throwable cause) {
        super(message, cause);
    }
}
