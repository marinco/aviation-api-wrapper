package io.github.marinco.aviation.it;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.client.WireMock;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.client.RestTestClient;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

/** Its own Redis container and Spring context, so pausing Redis can't touch other tests. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class RedisDownIT {

    @ServiceConnection(name = "redis")
    static final GenericContainer<?> OWN_REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:8-alpine")).withExposedPorts(6379);

    static {
        OWN_REDIS.start();
    }

    @DynamicPropertySource
    static void upstream(DynamicPropertyRegistry registry) {
        registry.add("app.upstream.aviationweather.base-url", Containers::wireMockUrl);
    }

    @LocalServerPort
    int port;

    @Autowired
    MeterRegistry meters;

    @AfterEach
    void unpause() {
        var docker = DockerClientFactory.instance().client();
        var state =
                docker.inspectContainerCmd(OWN_REDIS.getContainerId()).exec().getState();
        if (Boolean.TRUE.equals(state.getPaused())) {
            docker.unpauseContainerCmd(OWN_REDIS.getContainerId()).exec();
        }
    }

    @Test
    void test10_redisOutageFallsBackToUpstream() {
        WireMock upstream = new WireMock(Containers.WIREMOCK.getHost(), Containers.WIREMOCK.getMappedPort(8080));
        upstream.register(get(urlPathEqualTo("/api/data/airport"))
                .withQueryParam("ids", equalTo("KRDS"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(IntegrationTestBase.fixture().replace("KJFK", "KRDS"))));
        RestTestClient client = RestTestClient.bindToServer()
                .baseUrl("http://localhost:" + port)
                .build();

        DockerClientFactory.instance()
                .client()
                .pauseContainerCmd(OWN_REDIS.getContainerId())
                .exec();

        client.get()
                .uri("/api/v1/airports/KRDS")
                .exchange()
                .expectStatus()
                .isOk()
                .expectHeader()
                .valueEquals("X-Cache", "MISS")
                .expectBody()
                .jsonPath("$.icao")
                .isEqualTo("KRDS");

        double cacheErrors = meters.find("airport.cache.errors").counters().stream()
                .mapToDouble(c -> c.count())
                .sum();
        assertThat(cacheErrors).isGreaterThanOrEqualTo(1);
    }
}
