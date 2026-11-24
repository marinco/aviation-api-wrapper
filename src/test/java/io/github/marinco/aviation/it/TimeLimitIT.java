package io.github.marinco.aviation.it;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

/** The overall time limit, not the per-request read timeout, cuts the lookup off. Own context. */
@TestPropertySource(properties = {"app.resilience.time-limit=500ms", "app.upstream.aviationweather.read-timeout=3s"})
class TimeLimitIT extends IntegrationTestBase {

    @Test
    void timeLimitCancelsTheCallAndNothingRetriesAfterwards() throws InterruptedException {
        UPSTREAM.register(airportRequest("KJFK")
                .willReturn(aResponse()
                        .withStatus(200)
                        .withFixedDelay(2000)
                        .withHeader("Content-Type", "application/json")
                        .withBody(fixture())));

        long started = System.nanoTime();
        client.get()
                .uri("/api/v1/airports/KJFK")
                .exchange()
                .expectStatus()
                .isEqualTo(504)
                .expectBody()
                .jsonPath("$.type")
                .isEqualTo("urn:aviation-api:problem:upstream-timeout");
        long elapsedMs = (System.nanoTime() - started) / 1_000_000;

        assertThat(elapsedMs).isLessThan(1500);
        // Had the cancelled attempt kept going, the retry would send a second request after it.
        Thread.sleep(2500);
        assertThat(upstreamCalls()).isEqualTo(1);
    }
}
