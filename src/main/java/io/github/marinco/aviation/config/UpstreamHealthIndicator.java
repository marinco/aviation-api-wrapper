package io.github.marinco.aviation.config;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.boot.health.contributor.Status;
import org.springframework.stereotype.Component;

/**
 * Shows each provider's circuit state in {@code /actuator/health}. Reports DEGRADED, never DOWN, while a circuit is
 * open: the service still answers from the cache. Not part of the readiness group.
 */
@Component("upstream")
class UpstreamHealthIndicator implements HealthIndicator {

    static final Status DEGRADED = new Status("DEGRADED");

    private final CircuitBreakerRegistry circuitBreakers;

    UpstreamHealthIndicator(CircuitBreakerRegistry circuitBreakers) {
        this.circuitBreakers = circuitBreakers;
    }

    @Override
    public Health health() {
        Health.Builder builder = Health.up();
        for (CircuitBreaker cb : circuitBreakers.getAllCircuitBreakers()) {
            builder.withDetail(cb.getName(), cb.getState().name());
            if (cb.getState() == CircuitBreaker.State.OPEN || cb.getState() == CircuitBreaker.State.FORCED_OPEN) {
                builder.status(DEGRADED);
            }
        }
        return builder.build();
    }
}
