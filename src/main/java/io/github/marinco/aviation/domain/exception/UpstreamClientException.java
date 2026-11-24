package io.github.marinco.aviation.domain.exception;

/** The provider answered a 4xx other than 403 or 429: our request was wrong. Not retried. */
public class UpstreamClientException extends UpstreamException {

    private final int status;

    public UpstreamClientException(int status) {
        super("Upstream returned " + status);
        this.status = status;
    }

    public int status() {
        return status;
    }
}
