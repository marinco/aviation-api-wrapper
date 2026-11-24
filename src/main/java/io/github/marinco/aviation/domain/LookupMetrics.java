package io.github.marinco.aviation.domain;

import io.micrometer.core.instrument.MeterRegistry;
import java.util.Locale;

/**
 * The {@code airport.lookups} counter, in one place: callers say what happened, this class owns the metric name and
 * tag values ({@code outcome}: found, not_found, error, invalid; {@code cache}: hit, miss, stale, none).
 */
public class LookupMetrics {

    static final String NAME = "airport.lookups";

    private final MeterRegistry registry;

    public LookupMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public void found(CacheStatus cacheStatus) {
        count("found", cacheStatus.name().toLowerCase(Locale.ROOT));
    }

    public void notFound(boolean fromCache) {
        count("not_found", fromCache ? "hit" : "miss");
    }

    /** The provider failed and nothing usable was cached. */
    public void failed() {
        count("error", "miss");
    }

    /** The code was rejected before any lookup. */
    public void invalid() {
        count("invalid", "none");
    }

    private void count(String outcome, String cache) {
        registry.counter(NAME, "outcome", outcome, "cache", cache).increment();
    }
}
