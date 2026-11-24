package io.github.marinco.aviation.provider.resilience;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Guard settings for provider calls. Every number is configurable; tests shrink them. */
@ConfigurationProperties("app.resilience")
public record ResilienceProperties(
        Duration timeLimit, Bulkhead bulkhead, Retry retry, CircuitBreaker circuitBreaker, RateLimiter rateLimiter) {

    /** @param maxConcurrent lookups in flight per instance before new ones get 503 */
    public record Bulkhead(int maxConcurrent) {}

    /**
     * @param maxAttempts total attempts, the first one included
     * @param maxRetryAfter a 429 is retried only when its {@code Retry-After} is at most this long
     */
    public record Retry(
            int maxAttempts, Duration initialBackoff, double multiplier, double jitter, Duration maxRetryAfter) {}

    public record CircuitBreaker(
            int slidingWindowSize, int minimumNumberOfCalls, float failureRateThreshold, Duration openDuration) {}

    /** @param timeout how long a call may wait for a permit */
    public record RateLimiter(int limitPerMinute, Duration timeout) {}
}
