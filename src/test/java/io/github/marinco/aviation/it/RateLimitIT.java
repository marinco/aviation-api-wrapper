package io.github.marinco.aviation.it;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

/** Own Spring context (different property), so its tiny limit can't affect other tests. */
@TestPropertySource(
        properties = {"app.resilience.rate-limiter.limit-per-minute=2", "app.resilience.rate-limiter.refresh-period=60s"
        })
class RateLimitIT extends IntegrationTestBase {

    @Test
    void localRateLimitProtectsUpstream() {
        stubFound("KAAA");
        stubFound("KBBB");
        stubFound("KCCC");

        client.get().uri("/api/v1/airports/KAAA").exchange().expectStatus().isOk();
        client.get().uri("/api/v1/airports/KBBB").exchange().expectStatus().isOk();
        client.get()
                .uri("/api/v1/airports/KCCC")
                .exchange()
                .expectStatus()
                .isEqualTo(503)
                .expectHeader()
                .exists("Retry-After");

        assertThat(upstreamCalls()).isEqualTo(2);
    }
}
