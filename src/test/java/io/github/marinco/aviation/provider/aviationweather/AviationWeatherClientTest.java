package io.github.marinco.aviation.provider.aviationweather;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import io.github.marinco.aviation.domain.exception.UpstreamBadResponseException;
import io.github.marinco.aviation.domain.exception.UpstreamClientException;
import io.github.marinco.aviation.domain.exception.UpstreamForbiddenException;
import io.github.marinco.aviation.domain.exception.UpstreamRateLimitedException;
import io.github.marinco.aviation.domain.exception.UpstreamServerException;
import io.github.marinco.aviation.domain.exception.UpstreamTimeoutException;
import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** One case per row of the README failure table, against an in-process WireMock. */
class AviationWeatherClientTest {

    private static final WireMockServer server = new WireMockServer(options().dynamicPort());
    private AviationWeatherClient client;

    @BeforeAll
    static void start() {
        server.start();
    }

    @AfterAll
    static void stop() {
        server.stop();
    }

    @BeforeEach
    void setUp() {
        server.resetAll();
        var properties = new AviationWeatherProperties(
                server.baseUrl(), Duration.ofSeconds(1), Duration.ofMillis(300), "test-agent");
        client = new AviationWeatherClient(
                HttpClient.newHttpClient(), properties, JsonMapper.builder().build(), Clock.systemUTC());
    }

    private static void answer(ResponseDefinitionBuilder response) {
        server.stubFor(get(urlPathEqualTo("/api/data/airport")).willReturn(response));
    }

    private static ResponseDefinitionBuilder json(String body) {
        return aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody(body);
    }

    @Test
    void foundAirportIsReturned() {
        answer(json("[{\"icaoId\":\"KJFK\",\"name\":\"Kennedy\"}]"));
        assertThat(client.fetch("KJFK"))
                .hasValueSatisfying(a -> assertThat(a.name()).isEqualTo("Kennedy"));
    }

    @Test
    void notFoundShapesAreEmpty() {
        answer(aResponse().withStatus(204));
        assertThat(client.fetch("ZZZZ")).isEmpty();

        answer(json("[]"));
        assertThat(client.fetch("ZZZZ")).isEmpty();

        answer(json("[null, {\"icaoId\":\"KJFK\"}]"));
        assertThat(client.fetch("ZZZZ")).isEmpty();
    }

    @Test
    void unreadableBodiesAreBadResponses() {
        answer(json("not json"));
        assertThatThrownBy(() -> client.fetch("KJFK")).isInstanceOf(UpstreamBadResponseException.class);

        answer(json("null"));
        assertThatThrownBy(() -> client.fetch("KJFK")).isInstanceOf(UpstreamBadResponseException.class);

        answer(json("{\"icaoId\":\"KJFK\"}"));
        assertThatThrownBy(() -> client.fetch("KJFK")).isInstanceOf(UpstreamBadResponseException.class);
    }

    @Test
    void statusCodesMapToTypedFailures() {
        answer(aResponse().withStatus(403));
        assertThatThrownBy(() -> client.fetch("KJFK")).isInstanceOf(UpstreamForbiddenException.class);

        answer(aResponse().withStatus(400));
        assertThatThrownBy(() -> client.fetch("KJFK")).isInstanceOf(UpstreamClientException.class);

        answer(aResponse().withStatus(503));
        assertThatThrownBy(() -> client.fetch("KJFK")).isInstanceOf(UpstreamServerException.class);

        answer(aResponse().withStatus(429).withHeader("Retry-After", "3"));
        assertThatThrownBy(() -> client.fetch("KJFK"))
                .isInstanceOfSatisfying(
                        UpstreamRateLimitedException.class,
                        e -> assertThat(e.retryAfter()).isEqualTo(Duration.ofSeconds(3)));
    }

    @Test
    void slowResponseIsATimeout() {
        answer(json("[]").withFixedDelay(1000));
        assertThatThrownBy(() -> client.fetch("KJFK")).isInstanceOf(UpstreamTimeoutException.class);
    }
}
