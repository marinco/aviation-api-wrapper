package io.github.marinco.aviation.domain.exception;

/** The provider answered 200, but the body couldn't be read. Not retried. */
public class UpstreamBadResponseException extends UpstreamException {

    public UpstreamBadResponseException(String message, Throwable cause) {
        super(message, cause);
    }
}
