package io.github.marinco.aviation.config;

import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.micrometer.tagged.TaggedBulkheadMetrics;
import io.github.resilience4j.micrometer.tagged.TaggedCircuitBreakerMetrics;
import io.github.resilience4j.micrometer.tagged.TaggedRateLimiterMetrics;
import io.github.resilience4j.micrometer.tagged.TaggedRetryMetrics;
import io.github.resilience4j.micrometer.tagged.TaggedTimeLimiterMetrics;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
import io.github.resilience4j.retry.RetryRegistry;
import io.github.resilience4j.timelimiter.TimeLimiterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Resilience4j registries (one per guard type) and their Micrometer metrics. */
@Configuration(proxyBeanMethods = false)
class ResilienceConfig {

    /** Runs guarded upstream calls so the time limit can cancel them. Virtual threads: blocking I/O is cheap. */
    @Bean(destroyMethod = "close")
    ExecutorService upstreamExecutor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }

    @Bean
    CircuitBreakerRegistry circuitBreakerRegistry() {
        return CircuitBreakerRegistry.ofDefaults();
    }

    @Bean
    RetryRegistry retryRegistry() {
        return RetryRegistry.ofDefaults();
    }

    @Bean
    RateLimiterRegistry rateLimiterRegistry() {
        return RateLimiterRegistry.ofDefaults();
    }

    @Bean
    BulkheadRegistry bulkheadRegistry() {
        return BulkheadRegistry.ofDefaults();
    }

    @Bean
    TimeLimiterRegistry timeLimiterRegistry() {
        return TimeLimiterRegistry.ofDefaults();
    }

    @Bean
    MeterBinder resilienceMetrics(
            CircuitBreakerRegistry circuitBreakers,
            RetryRegistry retries,
            RateLimiterRegistry rateLimiters,
            BulkheadRegistry bulkheads,
            TimeLimiterRegistry timeLimiters) {
        return registry -> {
            TaggedCircuitBreakerMetrics.ofCircuitBreakerRegistry(circuitBreakers)
                    .bindTo(registry);
            TaggedRetryMetrics.ofRetryRegistry(retries).bindTo(registry);
            TaggedRateLimiterMetrics.ofRateLimiterRegistry(rateLimiters).bindTo(registry);
            TaggedBulkheadMetrics.ofBulkheadRegistry(bulkheads).bindTo(registry);
            TaggedTimeLimiterMetrics.ofTimeLimiterRegistry(timeLimiters).bindTo(registry);
        };
    }
}
