package io.github.marinco.aviation.domain;

import java.time.Duration;
import java.time.Instant;

/**
 * A successful lookup.
 *
 * @param retrievedAt when the data was fetched from the provider
 * @param freshFor how much longer the data counts as fresh; zero when {@code STALE}
 */
public record LookupResult(Airport airport, CacheStatus cacheStatus, Instant retrievedAt, Duration freshFor) {}
