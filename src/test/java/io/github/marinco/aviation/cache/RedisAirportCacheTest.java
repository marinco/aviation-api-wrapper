package io.github.marinco.aviation.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.marinco.aviation.domain.Airport;
import io.github.marinco.aviation.domain.Location;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import tools.jackson.databind.json.JsonMapper;

/** The cache must never throw: every Redis failure reads as a miss and is counted by operation. */
class RedisAirportCacheTest {

    private static final Airport KJFK =
            new Airport("KJFK", "JFK", "Kennedy", new Location("NY", "US", 40.6, -73.7, 4), List.of(), "test");

    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);

    @SuppressWarnings("unchecked")
    private final ValueOperations<String, String> values = mock(ValueOperations.class);

    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private RedisAirportCache cache;

    @BeforeEach
    void setUp() {
        when(redis.opsForValue()).thenReturn(values);
        var properties = new CacheProperties(Duration.ofHours(24), Duration.ofDays(7), Duration.ofMinutes(10));
        cache = new RedisAirportCache(redis, JsonMapper.builder().build(), properties, meters);
    }

    private double errors(String operation) {
        return meters.counter("airport.cache.errors", "operation", operation).count();
    }

    @Test
    void readFailureIsAMissAndCounted() {
        when(values.get(anyString())).thenThrow(new RedisConnectionFailureException("down"));

        assertThat(cache.get("KJFK")).isEmpty();
        assertThat(errors("get")).isEqualTo(1);
    }

    @Test
    void writeFailuresAreSwallowedAndCountedSeparately() {
        doThrow(new RedisConnectionFailureException("down"))
                .when(values)
                .set(anyString(), anyString(), any(Duration.class));

        assertThatCode(() -> cache.put("KJFK", KJFK, Instant.now())).doesNotThrowAnyException();
        assertThatCode(() -> cache.putNotFound("ZZZZ")).doesNotThrowAnyException();

        assertThat(errors("put")).isEqualTo(1);
        assertThat(errors("put_not_found")).isEqualTo(1);
    }

    @Test
    void unreadableOrIncompleteEntriesAreMissesAndCounted() {
        when(values.get("airport:v1:BAD1")).thenReturn("not json");
        when(values.get("airport:v1:BAD2")).thenReturn("{\"notFound\":false}");

        assertThat(cache.get("BAD1")).isEmpty();
        assertThat(cache.get("BAD2")).isEmpty();
        assertThat(errors("get")).isEqualTo(2);
    }
}
