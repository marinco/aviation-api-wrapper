package io.github.marinco.aviation.provider.resilience;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.marinco.aviation.domain.exception.UpstreamException;
import io.github.marinco.aviation.domain.exception.UpstreamException.Kind;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.ratelimiter.RateLimiterConfig;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
import io.github.resilience4j.retry.RetryRegistry;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ResilientCallTest {

    private final CircuitBreakerRegistry circuitBreakers = CircuitBreakerRegistry.ofDefaults();

    private static ResilienceProperties props(int limitPerMinute) {
        return new ResilienceProperties(
                new ResilienceProperties.Retry(3, Duration.ofMillis(5), 2.0, 0.1, Duration.ofSeconds(2)),
                new ResilienceProperties.CircuitBreaker(20, 10, 50, Duration.ofSeconds(30), 2),
                new ResilienceProperties.RateLimiter(limitPerMinute, Duration.ofSeconds(5), Duration.ofMillis(10)));
    }

    private final ResilientCall guards = new ResilientCall(
            "test", props(10_000), RetryRegistry.ofDefaults(), circuitBreakers, RateLimiterRegistry.ofDefaults());

    @Test
    void serverErrorsAreRetriedUpToMaxAttempts() {
        AtomicInteger attempts = new AtomicInteger();

        assertThatThrownBy(() -> guards.execute(() -> {
                    attempts.incrementAndGet();
                    throw UpstreamException.serverError(500);
                }))
                .isInstanceOf(UpstreamException.class);

        assertThat(attempts).hasValue(3);
    }

    @Test
    void clientErrorsAreNotRetried() {
        AtomicInteger attempts = new AtomicInteger();

        assertThatThrownBy(() -> guards.execute(() -> {
                    attempts.incrementAndGet();
                    throw UpstreamException.clientError(400);
                }))
                .isInstanceOf(UpstreamException.class);

        assertThat(attempts).hasValue(1);
    }

    @Test
    void shortRetryAfterIsWaitedOutLongOneIsNot() {
        AtomicInteger shortAttempts = new AtomicInteger();
        String result = guards.execute(() -> {
            if (shortAttempts.incrementAndGet() == 1) {
                throw UpstreamException.rateLimited(Duration.ofMillis(50));
            }
            return "ok";
        });
        assertThat(result).isEqualTo("ok");
        assertThat(shortAttempts).hasValue(2);

        AtomicInteger longAttempts = new AtomicInteger();
        assertThatThrownBy(() -> guards.execute(() -> {
                    longAttempts.incrementAndGet();
                    throw UpstreamException.rateLimited(Duration.ofSeconds(30));
                }))
                .isInstanceOfSatisfying(
                        UpstreamException.class, e -> assertThat(e.kind()).isEqualTo(Kind.RATE_LIMITED));
        assertThat(longAttempts).hasValue(1);
    }

    @Test
    void circuitBreakerCountsUpstreamFaultsOnly() {
        CircuitBreaker.Metrics metrics = circuitBreakers.circuitBreaker("test").getMetrics();

        for (UpstreamException ignored : new UpstreamException[] {
            UpstreamException.clientError(404),
            UpstreamException.badResponse("bad", null),
            UpstreamException.rateLimited(Duration.ofSeconds(30))
        }) {
            assertThatThrownBy(() -> guards.execute(() -> {
                        throw ignored;
                    }))
                    .isSameAs(ignored);
        }
        assertThat(metrics.getNumberOfFailedCalls()).isZero();

        assertThatThrownBy(() -> guards.execute(() -> {
                    throw UpstreamException.forbidden();
                }))
                .isInstanceOf(UpstreamException.class);
        assertThat(metrics.getNumberOfFailedCalls()).isEqualTo(1);
    }

    @Test
    void localRateLimitRefusesWithoutCallingUpstream() {
        ResilientCall limited = new ResilientCall(
                "limited", props(1), RetryRegistry.ofDefaults(), circuitBreakers, RateLimiterRegistry.ofDefaults());
        AtomicInteger calls = new AtomicInteger();

        limited.execute(calls::incrementAndGet);
        assertThatThrownBy(() -> limited.execute(calls::incrementAndGet))
                .isInstanceOfSatisfying(
                        UpstreamException.class, e -> assertThat(e.kind()).isEqualTo(Kind.LOCAL_RATE_LIMIT));

        assertThat(calls).hasValue(1);
    }

    @Test
    void rateLimiterRefillsInSmallSteps() {
        RateLimiterConfig config = ResilientCall.rateLimiterConfig(props(80));

        assertThat(config.getLimitForPeriod()).isEqualTo(7); // ceil(80 / 12)
        assertThat(config.getLimitRefreshPeriod()).isEqualTo(Duration.ofSeconds(5));
    }
}
