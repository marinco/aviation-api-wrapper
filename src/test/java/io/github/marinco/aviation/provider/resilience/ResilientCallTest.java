package io.github.marinco.aviation.provider.resilience;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import io.github.marinco.aviation.domain.exception.LookupTimeoutException;
import io.github.marinco.aviation.domain.exception.UpstreamBadResponseException;
import io.github.marinco.aviation.domain.exception.UpstreamClientException;
import io.github.marinco.aviation.domain.exception.UpstreamForbiddenException;
import io.github.marinco.aviation.domain.exception.UpstreamRateLimitedException;
import io.github.marinco.aviation.domain.exception.UpstreamServerException;
import io.github.marinco.aviation.domain.exception.UpstreamUnavailableException;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.ratelimiter.RateLimiterConfig;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
import io.github.resilience4j.retry.RetryRegistry;
import io.github.resilience4j.timelimiter.TimeLimiterRegistry;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class ResilientCallTest {

    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final CircuitBreakerRegistry circuitBreakers = CircuitBreakerRegistry.ofDefaults();

    @AfterEach
    void close() {
        executor.shutdownNow();
    }

    private static ResilienceProperties props(Duration timeLimit, int maxConcurrent) {
        return new ResilienceProperties(
                timeLimit,
                new ResilienceProperties.Bulkhead(maxConcurrent),
                new ResilienceProperties.Retry(3, Duration.ofMillis(5), 2.0, 0.1, Duration.ofSeconds(2)),
                new ResilienceProperties.CircuitBreaker(20, 10, 50, Duration.ofSeconds(30), 2),
                new ResilienceProperties.RateLimiter(10_000, Duration.ofSeconds(5), Duration.ofMillis(100)));
    }

    private ResilientCall call(ResilienceProperties p) {
        return new ResilientCall(
                "test",
                p,
                executor,
                TimeLimiterRegistry.ofDefaults(),
                BulkheadRegistry.ofDefaults(),
                RetryRegistry.ofDefaults(),
                circuitBreakers,
                RateLimiterRegistry.ofDefaults());
    }

    @Test
    void timeLimitInterruptsTheCallAndStopsRetrying() throws InterruptedException {
        AtomicInteger attempts = new AtomicInteger();
        AtomicBoolean interrupted = new AtomicBoolean();
        ResilientCall guards = call(props(Duration.ofMillis(100), 5));

        assertThatThrownBy(() -> guards.execute(() -> {
                    attempts.incrementAndGet();
                    try {
                        Thread.sleep(5_000);
                    } catch (InterruptedException e) {
                        interrupted.set(true);
                        Thread.currentThread().interrupt();
                        throw new UpstreamServerException(500); // retryable, but the interrupt must stop the retry
                    }
                    return "never";
                }))
                .isInstanceOf(LookupTimeoutException.class);

        await().atMost(Duration.ofSeconds(1)).untilTrue(interrupted);
        Thread.sleep(200);
        assertThat(attempts).hasValue(1);
    }

    @Test
    void fullBulkheadRefusesAtOnce() throws Exception {
        ResilientCall guards = call(props(Duration.ofSeconds(5), 1));
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch started = new CountDownLatch(1);
        CompletableFuture<String> first = CompletableFuture.supplyAsync(() -> guards.execute(() -> {
            started.countDown();
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return "first";
        }));
        assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();

        assertThatThrownBy(() -> guards.execute(() -> "second"))
                .isInstanceOfSatisfying(
                        UpstreamUnavailableException.class,
                        e -> assertThat(e.reason()).isEqualTo(UpstreamUnavailableException.Reason.BULKHEAD_FULL));

        release.countDown();
        assertThat(first.get(2, TimeUnit.SECONDS)).isEqualTo("first");
    }

    @Test
    void shortRetryAfterIsWaitedOutLongOneIsNot() {
        ResilientCall guards = call(props(Duration.ofSeconds(5), 5));

        AtomicInteger shortAttempts = new AtomicInteger();
        String result = guards.execute(() -> {
            if (shortAttempts.incrementAndGet() == 1) {
                throw new UpstreamRateLimitedException(Duration.ofMillis(50));
            }
            return "ok";
        });
        assertThat(result).isEqualTo("ok");
        assertThat(shortAttempts).hasValue(2);

        AtomicInteger longAttempts = new AtomicInteger();
        assertThatThrownBy(() -> guards.execute(() -> {
                    longAttempts.incrementAndGet();
                    throw new UpstreamRateLimitedException(Duration.ofSeconds(30));
                }))
                .isInstanceOf(UpstreamRateLimitedException.class);
        assertThat(longAttempts).hasValue(1);
    }

    @Test
    void circuitBreakerCountsUpstreamFaultsOnly() {
        ResilientCall guards = call(props(Duration.ofSeconds(5), 5));
        CircuitBreaker.Metrics metrics = circuitBreakers.circuitBreaker("test").getMetrics();

        for (RuntimeException ignoredFailure : new RuntimeException[] {
            new UpstreamClientException(404),
            new UpstreamBadResponseException("bad", null),
            new UpstreamRateLimitedException(Duration.ofSeconds(30))
        }) {
            assertThatThrownBy(() -> guards.execute(() -> {
                        throw ignoredFailure;
                    }))
                    .isSameAs(ignoredFailure);
        }
        assertThat(metrics.getNumberOfFailedCalls()).isZero();

        assertThatThrownBy(() -> guards.execute(() -> {
                    throw new UpstreamForbiddenException();
                }))
                .isInstanceOf(UpstreamForbiddenException.class);
        assertThat(metrics.getNumberOfFailedCalls()).isEqualTo(1);
    }

    @Test
    void rateLimiterRefillsInSmallSteps() {
        ResilienceProperties p = new ResilienceProperties(
                Duration.ofSeconds(6),
                new ResilienceProperties.Bulkhead(25),
                new ResilienceProperties.Retry(3, Duration.ofMillis(200), 2.0, 0.5, Duration.ofSeconds(2)),
                new ResilienceProperties.CircuitBreaker(20, 10, 50, Duration.ofSeconds(30), 2),
                new ResilienceProperties.RateLimiter(80, Duration.ofSeconds(5), Duration.ofMillis(100)));

        RateLimiterConfig config = ResilientCall.rateLimiterConfig(p);

        assertThat(config.getLimitForPeriod()).isEqualTo(7); // ceil(80 / 12)
        assertThat(config.getLimitRefreshPeriod()).isEqualTo(Duration.ofSeconds(5));
    }
}
