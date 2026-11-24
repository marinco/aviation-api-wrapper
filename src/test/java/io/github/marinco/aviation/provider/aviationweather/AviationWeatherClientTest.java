package io.github.marinco.aviation.provider.aviationweather;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import io.github.marinco.aviation.domain.exception.UpstreamException;
import io.github.marinco.aviation.domain.exception.UpstreamException.Kind;
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
        // Generous: a cold WireMock on a CI runner can take hundreds of ms for its first answers.
        client = clientWithReadTimeout(Duration.ofSeconds(2));
    }

    private static AviationWeatherClient clientWithReadTimeout(Duration readTimeout) {
        var properties =
                new AviationWeatherProperties(server.baseUrl(), Duration.ofSeconds(1), readTimeout, "test-agent");
        return new AviationWeatherClient(
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
        assertKind(Kind.BAD_RESPONSE);

        answer(json("null"));
        assertKind(Kind.BAD_RESPONSE);

        answer(json("{\"icaoId\":\"KJFK\"}"));
        assertKind(Kind.BAD_RESPONSE);
    }

    @Test
    void statusCodesMapToTypedFailures() {
        answer(aResponse().withStatus(403));
        assertKind(Kind.FORBIDDEN);

        answer(aResponse().withStatus(400));
        assertKind(Kind.CLIENT_ERROR);

        answer(aResponse().withStatus(503));
        assertKind(Kind.SERVER_ERROR);

        answer(aResponse().withStatus(429).withHeader("Retry-After", "3"));
        assertThat(failure().retryAfter()).isEqualTo(Duration.ofSeconds(3));
    }

    @Test
    void slowResponseIsATimeout() {
        client = clientWithReadTimeout(Duration.ofMillis(200));
        answer(json("[]").withFixedDelay(1500));
        assertKind(Kind.TIMEOUT);
    }

    private UpstreamException failure() {
        try {
            client.fetch("KJFK");
        } catch (UpstreamException e) {
            return e;
        }
        throw new AssertionError("expected an UpstreamException");
    }

    private void assertKind(Kind kind) {
        assertThat(failure().kind()).isEqualTo(kind);
    }
}
