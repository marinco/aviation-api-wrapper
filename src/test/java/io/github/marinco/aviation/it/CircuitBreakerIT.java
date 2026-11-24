package io.github.marinco.aviation.it;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class CircuitBreakerIT extends IntegrationTestBase {

    // A new code per call, so the cache can never answer.
    private final AtomicInteger codes = new AtomicInteger();

    private String nextCode() {
        return "C%03d".formatted(codes.incrementAndGet());
    }

    private int statusOf(String code) {
        return client.get()
                .uri("/api/v1/airports/{icao}", code)
                .exchange()
                .returnResult()
                .getStatus()
                .value();
    }

    private void openCircuit() {
        UPSTREAM.register(get(urlPathEqualTo(AIRPORT_PATH))
                .atPriority(10)
                .willReturn(aResponse().withStatus(500)));
        assertThat(statusOf(nextCode())).isEqualTo(502); // 3 failed attempts, under the minimum of 4 calls
        await().atMost(Duration.ofSeconds(2)).until(() -> statusOf(nextCode()) == 503);
    }

    @Test
    void test06_failingUpstreamOpensTheCircuit() {
        openCircuit();

        int callsWhileOpen = upstreamCalls();
        client.get()
                .uri("/api/v1/airports/{icao}", nextCode())
                .exchange()
                .expectStatus()
                .isEqualTo(503)
                .expectHeader()
                .valueEquals("Retry-After", "2")
                .expectBody()
                .jsonPath("$.type")
                .isEqualTo("urn:aviation-api:problem:upstream-unavailable");
        assertThat(upstreamCalls()).isEqualTo(callsWhileOpen);

        client.get()
                .uri("/actuator/health/readiness")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.status")
                .isEqualTo("UP");
    }

    @Test
    void test07_circuitRecoversWhenUpstreamDoes() {
        openCircuit();

        String code = nextCode();
        stubFound(code); // default priority 5 beats the catch-all 500 at priority 10

        await().atMost(Duration.ofSeconds(5))
                .pollInterval(Duration.ofMillis(250))
                .until(() -> statusOf(code) == 200);
        assertThat(upstreamCalls(code)).isGreaterThanOrEqualTo(1);
    }
}
