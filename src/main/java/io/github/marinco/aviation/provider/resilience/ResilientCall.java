package io.github.marinco.aviation.provider.resilience;

import io.github.marinco.aviation.domain.exception.UpstreamException;
import io.github.marinco.aviation.domain.exception.UpstreamException.Kind;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.core.IntervalFunction;
import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterConfig;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
import io.github.resilience4j.ratelimiter.RequestNotPermitted;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;
import java.time.Duration;
import java.util.EnumSet;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Runs provider calls behind the guards, outermost first: retry → circuit breaker → rate limiter → the call. Composed
 * by hand so the order is explicit. Every guard instance is named after the provider, so a second provider gets its
 * own.
 *
 * <p>The worst case is bounded without a separate time limit: {@code maxAttempts} × the HTTP read timeout, plus
 * backoff, plus at most one short {@code Retry-After} wait.
 *
 * <p>Failures come out as {@link UpstreamException}, so callers never see a Resilience4j type.
 */
public final class ResilientCall {

    /** Failures that may be gone on the next attempt. */
    static final Set<Kind> RETRYABLE = EnumSet.of(Kind.TIMEOUT, Kind.IO_ERROR, Kind.SERVER_ERROR);

    /** Failures that say the upstream is unhealthy and count toward opening the circuit. */
    static final Set<Kind> UPSTREAM_FAULTS = EnumSet.of(Kind.TIMEOUT, Kind.IO_ERROR, Kind.SERVER_ERROR, Kind.FORBIDDEN);

    private static final Duration LOCAL_REFUSAL_RETRY_AFTER = Duration.ofSeconds(1);

    private final ResilienceProperties properties;
    private final Retry retry;
    private final CircuitBreaker circuitBreaker;
    private final RateLimiter rateLimiter;

    public ResilientCall(
            String name,
            ResilienceProperties properties,
            RetryRegistry retries,
            CircuitBreakerRegistry circuitBreakers,
            RateLimiterRegistry rateLimiters) {
        this.properties = properties;
        this.retry = retries.retry(name, retryConfig(properties));
        this.circuitBreaker = circuitBreakers.circuitBreaker(name, circuitBreakerConfig(properties));
        this.rateLimiter = rateLimiters.rateLimiter(name, rateLimiterConfig(properties));
    }

    public <T> T execute(Supplier<T> call) {
        Supplier<T> guarded = RateLimiter.decorateSupplier(rateLimiter, call);
        guarded = CircuitBreaker.decorateSupplier(circuitBreaker, guarded);
        guarded = Retry.decorateSupplier(retry, guarded);
        try {
            return guarded.get();
        } catch (CallNotPermittedException e) {
            throw UpstreamException.circuitOpen(properties.circuitBreaker().openDuration(), e);
        } catch (RequestNotPermitted e) {
            throw UpstreamException.localRateLimit(LOCAL_REFUSAL_RETRY_AFTER, e);
        }
    }

    private static RetryConfig retryConfig(ResilienceProperties p) {
        ResilienceProperties.Retry r = p.retry();
        IntervalFunction backoff =
                IntervalFunction.ofExponentialRandomBackoff(r.initialBackoff(), r.multiplier(), r.jitter());
        return RetryConfig.custom()
                .maxAttempts(r.maxAttempts())
                .retryOnException(e -> e instanceof UpstreamException u && isRetryable(u, r.maxRetryAfter()))
                .intervalBiFunction((attempt, outcome) -> {
                    if (outcome.isLeft()
                            && outcome.getLeft() instanceof UpstreamException u
                            && u.kind() == Kind.RATE_LIMITED) {
                        return u.retryAfter().toMillis();
                    }
                    return backoff.apply(attempt);
                })
                .failAfterMaxAttempts(false)
                .build();
    }

    private static boolean isRetryable(UpstreamException e, Duration maxRetryAfter) {
        if (e.kind() == Kind.RATE_LIMITED) {
            return e.retryAfter().compareTo(maxRetryAfter) <= 0;
        }
        return RETRYABLE.contains(e.kind());
    }

    private static CircuitBreakerConfig circuitBreakerConfig(ResilienceProperties p) {
        ResilienceProperties.CircuitBreaker cb = p.circuitBreaker();
        return CircuitBreakerConfig.custom()
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(cb.slidingWindowSize())
                .minimumNumberOfCalls(cb.minimumNumberOfCalls())
                .failureRateThreshold(cb.failureRateThreshold())
                .waitDurationInOpenState(cb.openDuration())
                .permittedNumberOfCallsInHalfOpenState(cb.permittedCallsInHalfOpen())
                .automaticTransitionFromOpenToHalfOpenEnabled(true)
                .recordException(e -> e instanceof UpstreamException u && UPSTREAM_FAULTS.contains(u.kind()))
                // Our own refusals and the provider's other answers are neither failures nor successes.
                .ignoreException(e -> e instanceof RequestNotPermitted
                        || (e instanceof UpstreamException u && !UPSTREAM_FAULTS.contains(u.kind())))
                .build();
    }

    /**
     * Permits refill in short steps rather than once a minute, so a burst at the end of one minute and the start of
     * the next can't add up to twice the limit within seconds.
     */
    static RateLimiterConfig rateLimiterConfig(ResilienceProperties p) {
        Duration period = p.rateLimiter().refreshPeriod();
        int perPeriod = (int) Math.max(1, Math.ceil(p.rateLimiter().limitPerMinute() * (period.toMillis() / 60_000.0)));
        return RateLimiterConfig.custom()
                .limitForPeriod(perPeriod)
                .limitRefreshPeriod(period)
                .timeoutDuration(p.rateLimiter().timeout())
                .build();
    }
}
