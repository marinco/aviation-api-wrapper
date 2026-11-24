package io.github.marinco.aviation.provider.aviationweather;

import io.github.marinco.aviation.domain.exception.UpstreamBadResponseException;
import io.github.marinco.aviation.domain.exception.UpstreamClientException;
import io.github.marinco.aviation.domain.exception.UpstreamForbiddenException;
import io.github.marinco.aviation.domain.exception.UpstreamIoException;
import io.github.marinco.aviation.domain.exception.UpstreamRateLimitedException;
import io.github.marinco.aviation.domain.exception.UpstreamServerException;
import io.github.marinco.aviation.domain.exception.UpstreamTimeoutException;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * One HTTP call to {@code /api/data/airport}, no retries. Turns every outcome into either a result or a typed
 * exception; the guards around it decide what to do next.
 *
 * <p>Uses the JDK {@link HttpClient} directly with a per-request timeout. Spring's {@code RestClient} read timeout for
 * the JDK client cancels requests early when called from virtual threads (seen against the real upstream: fast
 * responses failing at exactly the timeout), and it reports timeouts as a generic I/O error.
 */
class AviationWeatherClient {

    private static final Logger log = LoggerFactory.getLogger(AviationWeatherClient.class);
    private static final TypeReference<List<AviationWeatherAirportDto>> AIRPORT_LIST = new TypeReference<>() {};

    private final HttpClient httpClient;
    private final AviationWeatherProperties properties;
    private final JsonMapper jsonMapper;
    private final Clock clock;

    AviationWeatherClient(
            HttpClient httpClient, AviationWeatherProperties properties, JsonMapper jsonMapper, Clock clock) {
        this.httpClient = httpClient;
        this.properties = properties;
        this.jsonMapper = jsonMapper;
        this.clock = clock;
    }

    /** @return the airport, or empty when the upstream says the code is unknown */
    Optional<AviationWeatherAirportDto> fetch(String icao) {
        HttpRequest request = HttpRequest.newBuilder(uri(icao))
                .timeout(properties.readTimeout())
                .header("User-Agent", properties.userAgent())
                .header("Accept", "application/json")
                .GET()
                .build();
        long started = System.nanoTime();
        HttpResponse<byte[]> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
        } catch (HttpTimeoutException e) {
            log.warn("Upstream lookup {} timed out after {} ms", icao, ms(started));
            throw new UpstreamTimeoutException("Upstream timed out", e);
        } catch (IOException e) {
            log.warn("Upstream lookup {} failed after {} ms: {}", icao, ms(started), e.toString());
            throw new UpstreamIoException("Upstream I/O error", e);
        } catch (InterruptedException e) {
            // The time limit cancelled us. Not an upstream failure: not retried, not counted by the circuit breaker.
            Thread.currentThread().interrupt();
            throw new CancellationException("Upstream call cancelled");
        }
        log.info("Upstream lookup {} -> {} in {} ms", icao, response.statusCode(), ms(started));
        return handle(icao, response);
    }

    private URI uri(String icao) {
        String base = properties.baseUrl().replaceAll("/+$", "");
        return URI.create(
                base + "/api/data/airport?ids=" + URLEncoder.encode(icao, StandardCharsets.UTF_8) + "&format=json");
    }

    private Optional<AviationWeatherAirportDto> handle(String icao, HttpResponse<byte[]> response) {
        int status = response.statusCode();
        if (status == 204) {
            return Optional.empty();
        }
        if (status == 200) {
            return parse(icao, response.body());
        }
        if (status == 429) {
            throw new UpstreamRateLimitedException(RetryAfter.parse(
                    response.headers().firstValue("Retry-After").orElse(null), clock));
        }
        if (status == 403) {
            log.error("Upstream answered 403 for {}: this service may be blocked", icao);
            throw new UpstreamForbiddenException();
        }
        if (status >= 500) {
            throw new UpstreamServerException(status);
        }
        throw new UpstreamClientException(status);
    }

    private Optional<AviationWeatherAirportDto> parse(String icao, byte[] body) {
        if (body == null || body.length == 0) {
            return Optional.empty();
        }
        try {
            List<AviationWeatherAirportDto> airports = jsonMapper.readValue(body, AIRPORT_LIST);
            if (airports == null) {
                throw new UpstreamBadResponseException("Upstream body was JSON null", null);
            }
            return airports.stream()
                    .filter(Objects::nonNull)
                    .filter(a -> a.icaoId() != null && a.icaoId().trim().equalsIgnoreCase(icao))
                    .findFirst();
        } catch (JacksonException e) {
            throw new UpstreamBadResponseException("Upstream body could not be parsed", e);
        }
    }

    private static long ms(long startedNanos) {
        return (System.nanoTime() - startedNanos) / 1_000_000;
    }
}
