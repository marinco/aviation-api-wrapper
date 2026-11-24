package io.github.marinco.aviation.api;

import io.github.marinco.aviation.domain.exception.AirportNotFoundException;
import io.github.marinco.aviation.domain.exception.UpstreamException;
import java.net.URI;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Turns every failure into RFC 9457 Problem Details with the request ID. Never includes stack traces, exception
 * messages or upstream text.
 */
@RestControllerAdvice
class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);
    static final String TYPE_PREFIX = "urn:aviation-api:problem:";

    /**
     * Spring's own errors (unknown path 404, wrong method 405, 406...) come through here: same Problem Details shape,
     * with our type URI and the request ID.
     */
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(
            Exception ex, Object body, HttpHeaders headers, HttpStatusCode statusCode, WebRequest request) {
        // Spring builds the ProblemDetail inside super, so decorate the result rather than the argument.
        ResponseEntity<Object> response = super.handleExceptionInternal(ex, body, headers, statusCode, request);
        if (response != null && response.getBody() instanceof ProblemDetail problem) {
            problem.setType(URI.create(TYPE_PREFIX + "http-" + statusCode.value()));
            problem.setProperty("requestId", MDC.get(RequestIdFilter.MDC_KEY));
        }
        return response;
    }

    @ExceptionHandler(InvalidIcaoException.class)
    ResponseEntity<ProblemDetail> invalidIcao(InvalidIcaoException e) {
        return problem(HttpStatus.BAD_REQUEST, "invalid-icao", "Invalid ICAO code", e.getMessage())
                .build();
    }

    @ExceptionHandler(AirportNotFoundException.class)
    ResponseEntity<ProblemDetail> notFound(AirportNotFoundException e) {
        return problem(
                        HttpStatus.NOT_FOUND,
                        "airport-not-found",
                        "Airport not found",
                        "No airport with ICAO code " + e.icao())
                .cacheControl(CacheControl.maxAge(Duration.ofMinutes(5)))
                .build();
    }

    /** One place decides the status for every upstream failure; the README's failure table mirrors it. */
    @ExceptionHandler(UpstreamException.class)
    ResponseEntity<ProblemDetail> upstreamFailed(UpstreamException e) {
        log.warn("Provider failed ({}): {}", e.kind(), e.getMessage());
        return switch (e.kind()) {
            case TIMEOUT ->
                problem(
                                HttpStatus.GATEWAY_TIMEOUT,
                                "upstream-timeout",
                                "Airport data provider timed out",
                                "Try again later.")
                        .build();
            case RATE_LIMITED, CIRCUIT_OPEN, LOCAL_RATE_LIMIT ->
                problem(
                                HttpStatus.SERVICE_UNAVAILABLE,
                                "upstream-unavailable",
                                "Airport data temporarily unavailable",
                                "Try again later.")
                        .retryAfter(e.retryAfter())
                        .build();
            case IO_ERROR, SERVER_ERROR, FORBIDDEN, CLIENT_ERROR, BAD_RESPONSE ->
                problem(HttpStatus.BAD_GATEWAY, "upstream-error", "Airport data provider failed", "Try again later.")
                        .build();
        };
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ProblemDetail> unexpected(Exception e) {
        log.error("Unexpected error", e);
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "internal-error", "Internal error", null)
                .build();
    }

    private static Builder problem(HttpStatus status, String type, String title, String detail) {
        ProblemDetail body = ProblemDetail.forStatusAndDetail(status, detail);
        body.setType(URI.create(TYPE_PREFIX + type));
        body.setTitle(title);
        body.setProperty("requestId", MDC.get(RequestIdFilter.MDC_KEY));
        return new Builder(status, body);
    }

    /** Small builder so each handler reads as one expression. */
    private static final class Builder {
        private final HttpStatus status;
        private final ProblemDetail body;
        private final HttpHeaders headers = new HttpHeaders();

        Builder(HttpStatus status, ProblemDetail body) {
            this.status = status;
            this.body = body;
        }

        Builder retryAfter(Duration retryAfter) {
            if (retryAfter == null) {
                return this;
            }
            long seconds = Math.max(1, retryAfter.toSeconds() + (retryAfter.toNanosPart() > 0 ? 1 : 0));
            headers.set(HttpHeaders.RETRY_AFTER, Long.toString(seconds));
            return this;
        }

        Builder cacheControl(CacheControl cacheControl) {
            headers.setCacheControl(cacheControl);
            return this;
        }

        ResponseEntity<ProblemDetail> build() {
            return ResponseEntity.status(status).headers(headers).body(body);
        }
    }
}
