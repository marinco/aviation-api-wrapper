package io.github.marinco.aviation.provider.aviationweather;

import io.github.marinco.aviation.provider.resilience.ResilienceProperties;
import io.github.marinco.aviation.provider.resilience.ResilientCall;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
import io.github.resilience4j.retry.RetryRegistry;
import io.github.resilience4j.timelimiter.TimeLimiterRegistry;
import io.micrometer.core.instrument.MeterRegistry;
import java.net.http.HttpClient;
import java.time.Clock;
import java.util.concurrent.ExecutorService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.json.JsonMapper;

/** Wires the aviationweather.gov adapter. Active when {@code app.provider=aviationweather} (the default). */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "app.provider", havingValue = AviationWeatherAirportProvider.NAME, matchIfMissing = true)
class AviationWeatherConfig {

    @Bean
    AviationWeatherAirportProvider aviationWeatherAirportProvider(
            AviationWeatherProperties properties,
            ResilienceProperties resilience,
            JsonMapper jsonMapper,
            Clock clock,
            ExecutorService upstreamExecutor,
            TimeLimiterRegistry timeLimiters,
            BulkheadRegistry bulkheads,
            RetryRegistry retries,
            CircuitBreakerRegistry circuitBreakers,
            RateLimiterRegistry rateLimiters,
            MeterRegistry meterRegistry) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(properties.connectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();

        ResilientCall guards = new ResilientCall(
                AviationWeatherAirportProvider.NAME,
                resilience,
                upstreamExecutor,
                timeLimiters,
                bulkheads,
                retries,
                circuitBreakers,
                rateLimiters);

        return new AviationWeatherAirportProvider(
                new AviationWeatherClient(httpClient, properties, jsonMapper, clock), guards, meterRegistry);
    }
}
