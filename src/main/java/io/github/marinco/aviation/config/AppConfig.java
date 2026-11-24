package io.github.marinco.aviation.config;

import io.github.marinco.aviation.cache.CacheProperties;
import io.github.marinco.aviation.cache.RedisAirportCache;
import io.github.marinco.aviation.domain.AirportCache;
import io.github.marinco.aviation.domain.AirportProvider;
import io.github.marinco.aviation.domain.AirportService;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import tools.jackson.databind.json.JsonMapper;

/** Wires the service to the active provider and the Redis cache. */
@Configuration(proxyBeanMethods = false)
class AppConfig {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    AirportCache airportCache(
            StringRedisTemplate redis, JsonMapper jsonMapper, CacheProperties properties, MeterRegistry meterRegistry) {
        return new RedisAirportCache(redis, jsonMapper, properties, meterRegistry);
    }

    @Bean
    AirportService airportService(
            AirportProvider provider,
            AirportCache cache,
            CacheProperties properties,
            Clock clock,
            MeterRegistry meterRegistry) {
        return new AirportService(provider, cache, properties.freshFor(), clock, meterRegistry);
    }
}
