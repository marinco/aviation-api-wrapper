package io.github.marinco.aviation.cache;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param freshFor how long an entry is served without calling the provider
 * @param ttl how long Redis keeps an entry, so it can still be served stale when the provider fails
 * @param notFoundTtl how long a "not found" answer is remembered
 */
@ConfigurationProperties("app.cache")
public record CacheProperties(Duration freshFor, Duration ttl, Duration notFoundTtl) {}
