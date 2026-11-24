package io.github.marinco.aviation.config;

import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.micrometer.tagged.TaggedCircuitBreakerMetrics;
import io.github.resilience4j.micrometer.tagged.TaggedRateLimiterMetrics;
import io.github.resilience4j.micrometer.tagged.TaggedRetryMetrics;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
import io.github.resilience4j.retry.RetryRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Resilience4j registries (one per guard type) and their Micrometer metrics. */
@Configuration(proxyBeanMethods = false)
class ResilienceConfig {

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
    MeterBinder resilienceMetrics(
            CircuitBreakerRegistry circuitBreakers, RetryRegistry retries, RateLimiterRegistry rateLimiters) {
        return registry -> {
            TaggedCircuitBreakerMetrics.ofCircuitBreakerRegistry(circuitBreakers)
                    .bindTo(registry);
            TaggedRetryMetrics.ofRetryRegistry(retries).bindTo(registry);
            TaggedRateLimiterMetrics.ofRateLimiterRegistry(rateLimiters).bindTo(registry);
        };
    }
}
