package io.github.marinco.aviation.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.marinco.aviation.domain.exception.AirportNotFoundException;
import io.github.marinco.aviation.domain.exception.UpstreamServerException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

class AirportServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-24T12:00:00Z");
    private static final Duration FRESH = Duration.ofHours(24);
    private static final Airport KJFK =
            new Airport("KJFK", "JFK", "Kennedy", new Location("NY", "US", 40.6, -73.7, 4), List.of(), "test");

    private final FakeCache cache = new FakeCache();
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final FakeProvider provider = new FakeProvider();
    private final AirportService service =
            new AirportService(provider, cache, FRESH, Clock.fixed(NOW, ZoneOffset.UTC), meters);

    @Test
    void missFetchesStoresAndReturns() {
        provider.answer = icao -> Optional.of(KJFK);

        LookupResult result = service.lookup("KJFK");

        assertThat(result.cacheStatus()).isEqualTo(CacheStatus.MISS);
        assertThat(result.airport()).isEqualTo(KJFK);
        assertThat(result.freshFor()).isEqualTo(FRESH);
        assertThat(cache.entries.get("KJFK")).isEqualTo(new AirportCache.Found(KJFK, NOW));
        assertThat(provider.calls).isEqualTo(1);
    }

    @Test
    void freshHitSkipsProvider() {
        cache.entries.put("KJFK", new AirportCache.Found(KJFK, NOW.minus(Duration.ofHours(1))));

        LookupResult result = service.lookup("KJFK");

        assertThat(result.cacheStatus()).isEqualTo(CacheStatus.HIT);
        assertThat(result.freshFor()).isEqualTo(Duration.ofHours(23));
        assertThat(provider.calls).isZero();
        assertThat(meters.counter("airport.lookups", "outcome", "found", "cache", "hit")
                        .count())
                .isEqualTo(1);
    }

    @Test
    void staleEntryIsRefreshedWhenProviderWorks() {
        cache.entries.put("KJFK", new AirportCache.Found(KJFK, NOW.minus(Duration.ofHours(25))));
        provider.answer = icao -> Optional.of(KJFK);

        assertThat(service.lookup("KJFK").cacheStatus()).isEqualTo(CacheStatus.MISS);
        assertThat(provider.calls).isEqualTo(1);
    }

    @Test
    void staleEntryIsServedWhenProviderFails() {
        Instant old = NOW.minus(Duration.ofHours(25));
        cache.entries.put("KJFK", new AirportCache.Found(KJFK, old));
        provider.answer = icao -> {
            throw new UpstreamServerException(500);
        };

        LookupResult result = service.lookup("KJFK");

        assertThat(result.cacheStatus()).isEqualTo(CacheStatus.STALE);
        assertThat(result.retrievedAt()).isEqualTo(old);
        assertThat(result.freshFor()).isZero();
    }

    @Test
    void failureWithNothingCachedPropagates() {
        provider.answer = icao -> {
            throw new UpstreamServerException(503);
        };

        assertThatThrownBy(() -> service.lookup("KJFK")).isInstanceOf(UpstreamServerException.class);
    }

    @Test
    void notFoundIsRememberedAndReplacesStaleCopy() {
        cache.entries.put("KJFK", new AirportCache.Found(KJFK, NOW.minus(Duration.ofHours(25))));
        provider.answer = icao -> Optional.empty();

        assertThatThrownBy(() -> service.lookup("KJFK")).isInstanceOf(AirportNotFoundException.class);
        assertThat(cache.entries.get("KJFK")).isInstanceOf(AirportCache.NotFound.class);

        assertThatThrownBy(() -> service.lookup("KJFK")).isInstanceOf(AirportNotFoundException.class);
        assertThat(provider.calls).isEqualTo(1);
    }

    @Test
    void cacheFailureActsAsMiss() {
        cache.broken = true;
        provider.answer = icao -> Optional.of(KJFK);

        assertThat(service.lookup("KJFK").cacheStatus()).isEqualTo(CacheStatus.MISS);
    }

    private static final class FakeProvider implements AirportProvider {
        Function<String, Optional<Airport>> answer = icao -> Optional.empty();
        int calls;

        @Override
        public String name() {
            return "fake";
        }

        @Override
        public Optional<Airport> findByIcao(String icao) {
            calls++;
            return answer.apply(icao);
        }
    }

    /** Behaves like the real cache contract: a broken cache reads as empty and swallows writes. */
    private static final class FakeCache implements AirportCache {
        final Map<String, CacheEntry> entries = new HashMap<>();
        boolean broken;

        @Override
        public Optional<CacheEntry> get(String icao) {
            return broken ? Optional.empty() : Optional.ofNullable(entries.get(icao));
        }

        @Override
        public void put(String icao, Airport airport, Instant fetchedAt) {
            if (!broken) {
                entries.put(icao, new Found(airport, fetchedAt));
            }
        }

        @Override
        public void putNotFound(String icao) {
            if (!broken) {
                entries.put(icao, new NotFound());
            }
        }
    }
}
