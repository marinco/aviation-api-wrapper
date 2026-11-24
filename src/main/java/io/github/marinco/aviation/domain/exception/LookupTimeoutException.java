package io.github.marinco.aviation.domain.exception;

import java.time.Duration;

/** The whole lookup, retries included, ran past its time limit. */
public class LookupTimeoutException extends UpstreamException {

    public LookupTimeoutException(Duration limit, Throwable cause) {
        super("Lookup exceeded " + limit.toMillis() + " ms", cause);
    }
}
