package io.github.marinco.aviation.provider.aviationweather;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param readTimeout per request; with the JDK client it covers the wait for the response, not socket idle time
 */
@ConfigurationProperties("app.upstream.aviationweather")
public record AviationWeatherProperties(
        String baseUrl, Duration connectTimeout, Duration readTimeout, String userAgent) {}
