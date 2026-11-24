package io.github.marinco.aviation.cache;

import io.github.marinco.aviation.domain.Airport;
import io.github.marinco.aviation.domain.AirportCache;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * Redis-backed {@link AirportCache}. Values are JSON under {@code airport:v1:{ICAO}}; {@code v1} lets the format
 * change without a flush. Every Redis failure is logged, counted and treated as a miss.
 */
public class RedisAirportCache implements AirportCache {

    private static final Logger log = LoggerFactory.getLogger(RedisAirportCache.class);
    static final String KEY_PREFIX = "airport:v1:";

    private final StringRedisTemplate redis;
    private final JsonMapper jsonMapper;
    private final CacheProperties properties;
    private final MeterRegistry meterRegistry;

    public RedisAirportCache(
            StringRedisTemplate redis, JsonMapper jsonMapper, CacheProperties properties, MeterRegistry meterRegistry) {
        this.redis = redis;
        this.jsonMapper = jsonMapper;
        this.properties = properties;
        this.meterRegistry = meterRegistry;
    }

    @Override
    public Optional<CacheEntry> get(String icao) {
        try {
            String json = redis.opsForValue().get(KEY_PREFIX + icao);
            if (json == null) {
                return Optional.empty();
            }
            StoredEntry stored = jsonMapper.readValue(json, StoredEntry.class);
            if (stored.notFound()) {
                return Optional.of(new NotFound());
            }
            if (stored.airport() == null || stored.fetchedAt() == null) {
                throw new IllegalStateException("Incomplete cache entry");
            }
            return Optional.of(new Found(stored.airport(), stored.fetchedAt()));
        } catch (RuntimeException e) {
            failed("get", icao, e);
            return Optional.empty();
        }
    }

    @Override
    public void put(String icao, Airport airport, Instant fetchedAt) {
        write(icao, new StoredEntry(airport, fetchedAt, false), properties.ttl(), "put");
    }

    @Override
    public void putNotFound(String icao) {
        write(icao, new StoredEntry(null, null, true), properties.notFoundTtl(), "put_not_found");
    }

    private void write(String icao, StoredEntry entry, java.time.Duration ttl, String operation) {
        try {
            redis.opsForValue().set(KEY_PREFIX + icao, jsonMapper.writeValueAsString(entry), ttl);
        } catch (RuntimeException e) {
            failed(operation, icao, e);
        }
    }

    private void failed(String operation, String icao, RuntimeException e) {
        log.warn("Redis {} failed for {}, treating as a cache miss: {}", operation, icao, e.toString());
        meterRegistry.counter("airport.cache.errors", "operation", operation).increment();
    }

    /** The JSON shape stored in Redis. */
    record StoredEntry(Airport airport, Instant fetchedAt, boolean notFound) {}
}
