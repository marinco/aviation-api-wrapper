package io.github.marinco.aviation.provider.resilience;

import io.github.marinco.aviation.domain.exception.LookupTimeoutException;
import io.github.marinco.aviation.domain.exception.UpstreamBadResponseException;
import io.github.marinco.aviation.domain.exception.UpstreamClientException;
import io.github.marinco.aviation.domain.exception.UpstreamForbiddenException;
import io.github.marinco.aviation.domain.exception.UpstreamIoException;
import io.github.marinco.aviation.domain.exception.UpstreamRateLimitedException;
import io.github.marinco.aviation.domain.exception.UpstreamServerException;
import io.github.marinco.aviation.domain.exception.UpstreamTimeoutException;
import io.github.marinco.aviation.domain.exception.UpstreamUnavailableException;
import io.github.marinco.aviation.domain.exception.UpstreamUnavailableException.Reason;
import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadConfig;
import io.github.resilience4j.bulkhead.BulkheadFullException;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
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
import io.github.resilience4j.timelimiter.TimeLimiter;
import io.github.resilience4j.timelimiter.TimeLimiterConfig;
import io.github.resilience4j.timelimiter.TimeLimiterRegistry;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;
import org.slf4j.MDC;

/**
 * Runs provider calls behind the guards, outermost first: time limit → bulkhead → retry → circuit breaker → rate
 * limiter → the call. Composed by hand so the order is explicit. Every guard instance is named after the provider, so
 * a second provider gets its own.
 *
 * <p>Failures come out as {@link io.github.marinco.aviation.domain.exception.UpstreamException} subtypes, so callers
 * never see a Resilience4j type.
 */
public final class ResilientCall {

    private static final Duration LOCAL_REFUSAL_RETRY_AFTER = Duration.ofSeconds(1);

    private final ResilienceProperties properties;
    private final ExecutorService executor;
    private final TimeLimiter timeLimiter;
    private final Bulkhead bulkhead;
    private final Retry retry;
    private final CircuitBreaker circuitBreaker;
    private final RateLimiter rateLimiter;

    public ResilientCall(
            String name,
            ResilienceProperties properties,
            ExecutorService executor,
            TimeLimiterRegistry timeLimiters,
            BulkheadRegistry bulkheads,
            RetryRegistry retries,
            CircuitBreakerRegistry circuitBreakers,
            RateLimiterRegistry rateLimiters) {
        this.properties = properties;
        this.executor = executor;
        this.timeLimiter = timeLimiters.timeLimiter(name, timeLimiterConfig(properties));
        this.bulkhead = bulkheads.bulkhead(name, bulkheadConfig(properties));
        this.retry = retries.retry(name, retryConfig(properties));
        this.circuitBreaker = circuitBreakers.circuitBreaker(name, circuitBreakerConfig(properties));
        this.rateLimiter = rateLimiters.rateLimiter(name, rateLimiterConfig(properties));
    }

    public <T> T execute(Supplier<T> call) {
        Supplier<T> guarded = RateLimiter.decorateSupplier(rateLimiter, call);
        guarded = CircuitBreaker.decorateSupplier(circuitBreaker, guarded);
        guarded = Retry.decorateSupplier(retry, guarded);
        guarded = Bulkhead.decorateSupplier(bulkhead, guarded);
        Callable<T> task = withMdc(guarded);
        // We hold the Future ourselves: Resilience4j cancels it only on its own timeout, not when the calling
        // thread is interrupted, and an abandoned call would keep its bulkhead permit and keep retrying.
        Future<T> future = executor.submit(task);
        try {
            return timeLimiter.executeFutureSupplier(() -> future);
        } catch (TimeoutException e) {
            throw new LookupTimeoutException(properties.timeLimit(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CancellationException("Lookup interrupted");
        } catch (Exception e) {
            throw translate(e);
        } finally {
            if (!future.isDone()) {
                future.cancel(true);
            }
        }
    }

    /** The guarded call runs on another (virtual) thread; carry the request ID over so its logs keep it. */
    private static <T> Callable<T> withMdc(Supplier<T> supplier) {
        Map<String, String> context = MDC.getCopyOfContextMap();
        return () -> {
            if (context != null) {
                MDC.setContextMap(context);
            }
            try {
                return supplier.get();
            } finally {
                MDC.clear();
            }
        };
    }

    private RuntimeException translate(Throwable e) {
        return switch (e) {
            case CallNotPermittedException open ->
                new UpstreamUnavailableException(
                        Reason.CIRCUIT_OPEN, properties.circuitBreaker().openDuration(), open);
            case RequestNotPermitted limited ->
                new UpstreamUnavailableException(Reason.RATE_LIMITED, LOCAL_REFUSAL_RETRY_AFTER, limited);
            case BulkheadFullException full ->
                new UpstreamUnavailableException(Reason.BULKHEAD_FULL, LOCAL_REFUSAL_RETRY_AFTER, full);
            case RuntimeException runtime -> runtime;
            default -> new IllegalStateException("Unexpected failure in guarded call", e);
        };
    }

    private static TimeLimiterConfig timeLimiterConfig(ResilienceProperties p) {
        return TimeLimiterConfig.custom()
                .timeoutDuration(p.timeLimit())
                .cancelRunningFuture(true)
                .build();
    }

    private static BulkheadConfig bulkheadConfig(ResilienceProperties p) {
        return BulkheadConfig.custom()
                .maxConcurrentCalls(p.bulkhead().maxConcurrent())
                .maxWaitDuration(Duration.ZERO)
                .build();
    }

    private static RetryConfig retryConfig(ResilienceProperties p) {
        ResilienceProperties.Retry r = p.retry();
        IntervalFunction backoff =
                IntervalFunction.ofExponentialRandomBackoff(r.initialBackoff(), r.multiplier(), r.jitter());
        return RetryConfig.custom()
                .maxAttempts(r.maxAttempts())
                .retryOnException(e -> !Thread.currentThread().isInterrupted() && isRetryable(e, r.maxRetryAfter()))
                .intervalBiFunction((attempt, outcome) -> {
                    if (outcome.isLeft() && outcome.getLeft() instanceof UpstreamRateLimitedException limited) {
                        return limited.retryAfter().toMillis();
                    }
                    return backoff.apply(attempt);
                })
                .failAfterMaxAttempts(false)
                .build();
    }

    private static boolean isRetryable(Throwable e, Duration maxRetryAfter) {
        return switch (e) {
            case UpstreamTimeoutException ignored -> true;
            case UpstreamIoException ignored -> true;
            case UpstreamServerException ignored -> true;
            case UpstreamRateLimitedException limited -> limited.retryAfter().compareTo(maxRetryAfter) <= 0;
            default -> false;
        };
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
                .recordExceptions(
                        UpstreamTimeoutException.class,
                        UpstreamIoException.class,
                        UpstreamServerException.class,
                        UpstreamForbiddenException.class)
                .ignoreExceptions(
                        UpstreamRateLimitedException.class,
                        UpstreamClientException.class,
                        UpstreamBadResponseException.class,
                        RequestNotPermitted.class,
                        BulkheadFullException.class)
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
