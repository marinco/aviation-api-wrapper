package io.github.marinco.aviation.domain;

import java.time.Instant;
import java.util.Optional;

/**
 * Port to the airport cache. Implementations must never throw: a cache failure is logged, counted and treated as a
 * miss, so the service keeps answering from the provider.
 */
public interface AirportCache {

    Optional<CacheEntry> get(String icao);

    void put(String icao, Airport airport, Instant fetchedAt);

    void putNotFound(String icao);

    /** What the cache holds for one ICAO code. */
    sealed interface CacheEntry permits Found, NotFound {}

    record Found(Airport airport, Instant fetchedAt) implements CacheEntry {}

    record NotFound() implements CacheEntry {}
}
