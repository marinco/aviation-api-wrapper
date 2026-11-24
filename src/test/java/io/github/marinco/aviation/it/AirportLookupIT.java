package io.github.marinco.aviation.it;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.marinco.aviation.domain.Airport;
import io.github.marinco.aviation.domain.AirportCache;
import io.github.marinco.aviation.domain.Location;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;

class AirportLookupIT extends IntegrationTestBase {

    @Autowired
    AirportCache cache;

    @Autowired
    MeterRegistry meters;

    @Test
    void test01_knownAirportIsFetchedOnceThenServedFromRedis() {
        stubFound("KJFK");

        client.get()
                .uri("/api/v1/airports/kjfk")
                .header("X-Request-Id", "it-request-1")
                .exchange()
                .expectStatus()
                .isOk()
                .expectHeader()
                .valueEquals("X-Cache", "MISS")
                .expectHeader()
                .valueEquals("X-Request-Id", "it-request-1")
                .expectHeader()
                .valueEquals("Cache-Control", "max-age=3600")
                .expectBody()
                .jsonPath("$.icao")
                .isEqualTo("KJFK")
                .jsonPath("$.iata")
                .isEqualTo("JFK")
                .jsonPath("$.name")
                .isEqualTo("NEW YORK/JOHN F KENNEDY INTL")
                .jsonPath("$.location.elevationM")
                .isEqualTo(4)
                .jsonPath("$.runways[0].lengthFt")
                .isEqualTo(12079)
                .jsonPath("$.runways[0].surface")
                .isEqualTo("CONCRETE")
                .jsonPath("$.source")
                .isEqualTo("aviationweather.gov");

        client.get()
                .uri("/api/v1/airports/KJFK")
                .exchange()
                .expectStatus()
                .isOk()
                .expectHeader()
                .valueEquals("X-Cache", "HIT");

        assertThat(upstreamCalls()).isEqualTo(1);
        assertThat(redis.hasKey("airport:v1:KJFK")).isTrue();
    }

    @Test
    void test02_unknownAirportIs404AndRemembered() {
        UPSTREAM.register(airportRequest("ZZZZ").willReturn(aResponse().withStatus(204)));

        for (int i = 0; i < 2; i++) {
            client.get()
                    .uri("/api/v1/airports/ZZZZ")
                    .exchange()
                    .expectStatus()
                    .isNotFound()
                    .expectHeader()
                    .contentType("application/problem+json")
                    .expectBody()
                    .jsonPath("$.type")
                    .isEqualTo("urn:aviation-api:problem:airport-not-found")
                    .jsonPath("$.requestId")
                    .exists();
        }

        assertThat(upstreamCalls()).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"KJ*", "KJFK,EGLL", "KJF", "KJFKX"})
    void test03_invalidCodesAre400AndNeverReachUpstream(String code) {
        client.get()
                .uri("/api/v1/airports/{icao}", code)
                .exchange()
                .expectStatus()
                .isBadRequest()
                .expectBody()
                .jsonPath("$.type")
                .isEqualTo("urn:aviation-api:problem:invalid-icao");

        assertThat(upstreamCalls()).isZero();
    }

    @Test
    void test04_serverErrorsAreRetried() {
        UPSTREAM.register(airportRequest("KJFK")
                .inScenario("flaky")
                .whenScenarioStateIs(STARTED)
                .willReturn(aResponse().withStatus(500))
                .willSetStateTo("second"));
        UPSTREAM.register(airportRequest("KJFK")
                .inScenario("flaky")
                .whenScenarioStateIs("second")
                .willReturn(aResponse().withStatus(500))
                .willSetStateTo("third"));
        UPSTREAM.register(airportRequest("KJFK")
                .inScenario("flaky")
                .whenScenarioStateIs("third")
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(fixture())));

        client.get().uri("/api/v1/airports/KJFK").exchange().expectStatus().isOk();

        assertThat(upstreamCalls()).isEqualTo(3);
    }

    @Test
    void test05a_shortUpstreamRateLimitIsWaitedOut() {
        UPSTREAM.register(airportRequest("KJFK")
                .inScenario("429")
                .whenScenarioStateIs(STARTED)
                .willReturn(aResponse().withStatus(429).withHeader("Retry-After", "1"))
                .willSetStateTo("ok"));
        UPSTREAM.register(airportRequest("KJFK")
                .inScenario("429")
                .whenScenarioStateIs("ok")
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(fixture())));

        client.get().uri("/api/v1/airports/KJFK").exchange().expectStatus().isOk();

        assertThat(upstreamCalls()).isEqualTo(2);
    }

    @Test
    void test05b_longUpstreamRateLimitIs503WithoutTrippingTheCircuit() {
        UPSTREAM.register(
                airportRequest("KJFK").willReturn(aResponse().withStatus(429).withHeader("Retry-After", "30")));

        client.get()
                .uri("/api/v1/airports/KJFK")
                .exchange()
                .expectStatus()
                .isEqualTo(503)
                .expectHeader()
                .valueEquals("Retry-After", "30");

        assertThat(upstreamCalls()).isEqualTo(1);
        assertThat(circuitBreakers.circuitBreaker("aviationweather").getState().name())
                .isEqualTo("CLOSED");
    }

    @Test
    void test08_slowUpstreamIs504AndNothingKeepsRunning() throws InterruptedException {
        UPSTREAM.register(airportRequest("KJFK")
                .willReturn(aResponse()
                        .withStatus(200)
                        .withFixedDelay(1500)
                        .withHeader("Content-Type", "application/json")
                        .withBody(fixture())));

        client.get()
                .uri("/api/v1/airports/KJFK")
                .exchange()
                .expectStatus()
                .isEqualTo(504)
                .expectBody()
                .jsonPath("$.type")
                .isEqualTo("urn:aviation-api:problem:upstream-timeout");

        int callsAtResponse = upstreamCalls();
        Thread.sleep(600);
        assertThat(callsAtResponse).isEqualTo(3);
        assertThat(upstreamCalls()).isEqualTo(callsAtResponse);
    }

    @Test
    void test09_staleCopyIsServedWhenUpstreamFails() {
        Airport kjfk = new Airport(
                "KJFK", "JFK", "Kennedy", new Location("NY", "US", 40.6, -73.7, 4), List.of(), "aviationweather.gov");
        cache.put("KJFK", kjfk, Instant.now().minus(Duration.ofHours(25)));
        UPSTREAM.register(airportRequest("KJFK").willReturn(aResponse().withStatus(500)));

        client.get()
                .uri("/api/v1/airports/KJFK")
                .exchange()
                .expectStatus()
                .isOk()
                .expectHeader()
                .valueEquals("X-Cache", "STALE")
                .expectHeader()
                .valueEquals("Cache-Control", "no-cache")
                .expectBody()
                .jsonPath("$.name")
                .isEqualTo("Kennedy");
    }

    @Test
    void corruptCacheEntryIsTreatedAsAMiss() {
        redis.opsForValue().set("airport:v1:KJFK", "{\"notFound\":false}");
        stubFound("KJFK");

        client.get()
                .uri("/api/v1/airports/KJFK")
                .exchange()
                .expectStatus()
                .isOk()
                .expectHeader()
                .valueEquals("X-Cache", "MISS");
    }

    @Test
    void springErrorsAreProblemDetailsWithRequestId() {
        client.post()
                .uri("/api/v1/airports/KJFK")
                .exchange()
                .expectStatus()
                .isEqualTo(405)
                .expectHeader()
                .contentType("application/problem+json")
                .expectBody()
                .jsonPath("$.type")
                .isEqualTo("urn:aviation-api:problem:http-405")
                .jsonPath("$.requestId")
                .exists();

        client.get()
                .uri("/api/v1/nothing-here")
                .exchange()
                .expectStatus()
                .isNotFound()
                .expectBody()
                .jsonPath("$.type")
                .isEqualTo("urn:aviation-api:problem:http-404")
                .jsonPath("$.requestId")
                .exists();
    }

    @Test
    void entriesAreStoredWithTheirTtls() {
        stubFound("KJFK");
        UPSTREAM.register(airportRequest("ZZZZ").willReturn(aResponse().withStatus(204)));

        client.get().uri("/api/v1/airports/KJFK").exchange().expectStatus().isOk();
        client.get().uri("/api/v1/airports/ZZZZ").exchange().expectStatus().isNotFound();

        assertThat(redis.getExpire("airport:v1:KJFK"))
                .isBetween(
                        Duration.ofDays(7).toSeconds() - 60, Duration.ofDays(7).toSeconds());
        assertThat(redis.getExpire("airport:v1:ZZZZ"))
                .isBetween(
                        Duration.ofMinutes(10).toSeconds() - 60,
                        Duration.ofMinutes(10).toSeconds());
    }

    @Test
    void hitNearTheEndOfFreshnessGetsAShorterMaxAge() {
        Airport kjfk = new Airport(
                "KJFK", "JFK", "Kennedy", new Location("NY", "US", 40.6, -73.7, 4), List.of(), "aviationweather.gov");
        cache.put("KJFK", kjfk, Instant.now().minus(Duration.ofMinutes(23 * 60 + 30))); // 30 min of freshness left

        String cacheControl = client.get()
                .uri("/api/v1/airports/KJFK")
                .exchange()
                .expectStatus()
                .isOk()
                .expectHeader()
                .valueEquals("X-Cache", "HIT")
                .returnResult()
                .getResponseHeaders()
                .getCacheControl();

        int maxAge = Integer.parseInt(cacheControl.replace("max-age=", ""));
        assertThat(maxAge).isBetween(1790, 1800);
        assertThat(upstreamCalls()).isZero();
    }

    @Test
    void upstreamTimerIsTaggedWithTheOutcome() {
        double found = upstreamTimerCount("found");
        double notFound = upstreamTimerCount("not_found");
        double clientError = upstreamTimerCount("client_error");
        stubFound("KJFK");
        UPSTREAM.register(airportRequest("ZZZZ").willReturn(aResponse().withStatus(204)));
        UPSTREAM.register(airportRequest("KERR")
                .willReturn(aResponse().withStatus(400))); // not retried, not counted by the circuit

        client.get().uri("/api/v1/airports/KJFK").exchange().expectStatus().isOk();
        client.get().uri("/api/v1/airports/ZZZZ").exchange().expectStatus().isNotFound();
        client.get().uri("/api/v1/airports/KERR").exchange().expectStatus().isEqualTo(502);

        assertThat(upstreamTimerCount("found")).isEqualTo(found + 1);
        assertThat(upstreamTimerCount("not_found")).isEqualTo(notFound + 1);
        assertThat(upstreamTimerCount("client_error")).isEqualTo(clientError + 1);
    }

    private double upstreamTimerCount(String outcome) {
        var timer = meters.find("airport.upstream.requests")
                .tags("provider", "aviationweather", "outcome", outcome)
                .timer();
        return timer == null ? 0 : timer.count();
    }
}
