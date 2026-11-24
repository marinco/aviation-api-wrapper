package io.github.marinco.aviation.it;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;

import com.github.tomakehurst.wiremock.client.MappingBuilder;
import com.github.tomakehurst.wiremock.client.WireMock;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.client.RestTestClient;
import org.testcontainers.containers.GenericContainer;

/**
 * Real Redis and a real HTTP fake of aviationweather.gov, both in containers. Every test starts from an empty
 * Redis, no WireMock stubs and closed circuit breakers, so order doesn't matter.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
abstract class IntegrationTestBase {

    static final String AIRPORT_PATH = "/api/data/airport";

    @ServiceConnection(name = "redis")
    static final GenericContainer<?> REDIS = Containers.REDIS;

    static final WireMock UPSTREAM =
            new WireMock(Containers.WIREMOCK.getHost(), Containers.WIREMOCK.getMappedPort(8080));

    @DynamicPropertySource
    static void upstream(DynamicPropertyRegistry registry) {
        registry.add("app.upstream.aviationweather.base-url", Containers::wireMockUrl);
    }

    @LocalServerPort
    int port;

    @Autowired
    StringRedisTemplate redis;

    @Autowired
    CircuitBreakerRegistry circuitBreakers;

    RestTestClient client;

    @BeforeEach
    void resetState() {
        UPSTREAM.resetMappings();
        UPSTREAM.resetRequests();
        UPSTREAM.resetScenarios();
        redis.execute((org.springframework.data.redis.core.RedisCallback<Void>) connection -> {
            connection.serverCommands().flushAll();
            return null;
        });
        circuitBreakers.getAllCircuitBreakers().forEach(CircuitBreaker::reset);
        client = RestTestClient.bindToServer()
                .baseUrl("http://localhost:" + port)
                .build();
    }

    static MappingBuilder airportRequest(String icao) {
        return get(urlPathEqualTo(AIRPORT_PATH)).withQueryParam("ids", equalTo(icao));
    }

    /** Stubs a 200 with the real KJFK response, rewritten to the given code. */
    static void stubFound(String icao) {
        UPSTREAM.register(airportRequest(icao)
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(fixture().replace("KJFK", icao))));
    }

    static String fixture() {
        try (InputStream in = IntegrationTestBase.class.getResourceAsStream("/upstream/kjfk.json")) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    static int upstreamCalls() {
        return UPSTREAM.find(getRequestedFor(urlPathEqualTo(AIRPORT_PATH))).size();
    }

    static int upstreamCalls(String icao) {
        return UPSTREAM.find(getRequestedFor(urlPathEqualTo(AIRPORT_PATH)).withQueryParam("ids", equalTo(icao)))
                .size();
    }
}
