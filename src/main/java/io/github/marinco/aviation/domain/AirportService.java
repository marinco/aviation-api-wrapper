package io.github.marinco.aviation.domain;

import io.github.marinco.aviation.domain.AirportCache.Found;
import io.github.marinco.aviation.domain.AirportCache.NotFound;
import io.github.marinco.aviation.domain.exception.AirportNotFoundException;
import io.github.marinco.aviation.domain.exception.UpstreamException;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Looks up airports: cache first, then the provider. Serves a stale cached copy when the provider fails, but never
 * when the provider says the airport doesn't exist.
 */
public class AirportService {

    private static final Logger log = LoggerFactory.getLogger(AirportService.class);

    private final AirportProvider provider;
    private final AirportCache cache;
    private final Duration freshFor;
    private final Clock clock;
    private final MeterRegistry meterRegistry;

    public AirportService(
            AirportProvider provider, AirportCache cache, Duration freshFor, Clock clock, MeterRegistry meterRegistry) {
        this.provider = provider;
        this.cache = cache;
        this.freshFor = freshFor;
        this.clock = clock;
        this.meterRegistry = meterRegistry;
    }

    /**
     * @param icao an already validated, upper-case ICAO code
     * @throws AirportNotFoundException when the provider says the airport doesn't exist
     * @throws UpstreamException when the provider failed and nothing is cached
     */
    public LookupResult lookup(String icao) {
        Optional<AirportCache.CacheEntry> cached = cache.get(icao);

        if (cached.isPresent() && cached.get() instanceof NotFound) {
            count("not_found", "hit");
            throw new AirportNotFoundException(icao);
        }

        Found stale = null;
        if (cached.isPresent() && cached.get() instanceof Found found) {
            Duration age = Duration.between(found.fetchedAt(), clock.instant());
            if (age.compareTo(freshFor) < 0) {
                count("found", "hit");
                return new LookupResult(found.airport(), CacheStatus.HIT, found.fetchedAt(), freshFor.minus(age));
            }
            stale = found;
        }

        Optional<Airport> fetched;
        try {
            fetched = provider.findByIcao(icao);
        } catch (UpstreamException e) {
            if (stale != null) {
                log.warn(
                        "Provider {} failed for {}, serving stale copy from {}: {}",
                        provider.name(),
                        icao,
                        stale.fetchedAt(),
                        e.getMessage());
                count("found", "stale");
                return new LookupResult(stale.airport(), CacheStatus.STALE, stale.fetchedAt(), Duration.ZERO);
            }
            count("error", "miss");
            throw e;
        }

        if (fetched.isEmpty()) {
            // A real "not found" replaces any stale copy.
            cache.putNotFound(icao);
            count("not_found", "miss");
            log.info("Airport {} not found at provider {}", icao, provider.name());
            throw new AirportNotFoundException(icao);
        }

        Instant now = clock.instant();
        cache.put(icao, fetched.get(), now);
        count("found", "miss");
        return new LookupResult(fetched.get(), CacheStatus.MISS, now, freshFor);
    }

    private void count(String outcome, String cacheTag) {
        meterRegistry
                .counter("airport.lookups", "outcome", outcome, "cache", cacheTag)
                .increment();
    }
}
