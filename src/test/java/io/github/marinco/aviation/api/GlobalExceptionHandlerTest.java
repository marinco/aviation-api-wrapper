package io.github.marinco.aviation.api;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.marinco.aviation.domain.exception.AirportNotFoundException;
import io.github.marinco.aviation.domain.exception.LookupTimeoutException;
import io.github.marinco.aviation.domain.exception.UpstreamBadResponseException;
import io.github.marinco.aviation.domain.exception.UpstreamForbiddenException;
import io.github.marinco.aviation.domain.exception.UpstreamRateLimitedException;
import io.github.marinco.aviation.domain.exception.UpstreamServerException;
import io.github.marinco.aviation.domain.exception.UpstreamTimeoutException;
import io.github.marinco.aviation.domain.exception.UpstreamUnavailableException;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void invalidIcaoIs400WithRequestId() {
        MDC.put(RequestIdFilter.MDC_KEY, "abc-123");

        ResponseEntity<ProblemDetail> response = handler.invalidIcao(new InvalidIcaoException());

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody().getType().toString()).isEqualTo("urn:aviation-api:problem:invalid-icao");
        assertThat(response.getBody().getProperties()).containsEntry("requestId", "abc-123");
    }

    @Test
    void notFoundIs404AndCacheable() {
        ResponseEntity<ProblemDetail> response = handler.notFound(new AirportNotFoundException("ZZZZ"));

        assertThat(response.getStatusCode().value()).isEqualTo(404);
        assertThat(response.getHeaders().getCacheControl()).isEqualTo("max-age=300");
    }

    @Test
    void localRefusalIs503WithRetryAfter() {
        var e = new UpstreamUnavailableException(
                UpstreamUnavailableException.Reason.CIRCUIT_OPEN, Duration.ofSeconds(30), null);

        ResponseEntity<ProblemDetail> response = handler.unavailable(e);

        assertThat(response.getStatusCode().value()).isEqualTo(503);
        assertThat(response.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("30");
    }

    @Test
    void upstream429Is503WithItsRetryAfter() {
        ResponseEntity<ProblemDetail> response =
                handler.rateLimited(new UpstreamRateLimitedException(Duration.ofSeconds(30)));

        assertThat(response.getStatusCode().value()).isEqualTo(503);
        assertThat(response.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("30");
    }

    @Test
    void timeoutsAre504() {
        assertThat(handler.timeout(new LookupTimeoutException(Duration.ofSeconds(5), null))
                        .getStatusCode()
                        .value())
                .isEqualTo(504);
        assertThat(handler.timeout(new UpstreamTimeoutException("t", null))
                        .getStatusCode()
                        .value())
                .isEqualTo(504);
    }

    @Test
    void otherUpstreamFailuresAre502WithoutUpstreamText() {
        for (var e : new RuntimeException[] {
            new UpstreamServerException(500),
            new UpstreamForbiddenException(),
            new UpstreamBadResponseException("secret upstream text", null)
        }) {
            ResponseEntity<ProblemDetail> response =
                    handler.upstreamFailed((io.github.marinco.aviation.domain.exception.UpstreamException) e);
            assertThat(response.getStatusCode().value()).isEqualTo(502);
            assertThat(response.getBody().getDetail()).doesNotContain("secret");
        }
    }

    @Test
    void unexpectedErrorsHideTheMessage() {
        ResponseEntity<ProblemDetail> response = handler.unexpected(new IllegalStateException("db password is x"));

        assertThat(response.getStatusCode().value()).isEqualTo(500);
        assertThat(response.getBody().getDetail()).isNull();
    }
}
