package io.github.marinco.aviation.provider.aviationweather;

import io.github.marinco.aviation.domain.Airport;
import io.github.marinco.aviation.domain.AirportProvider;
import io.github.marinco.aviation.domain.exception.LookupTimeoutException;
import io.github.marinco.aviation.domain.exception.UpstreamBadResponseException;
import io.github.marinco.aviation.domain.exception.UpstreamClientException;
import io.github.marinco.aviation.domain.exception.UpstreamForbiddenException;
import io.github.marinco.aviation.domain.exception.UpstreamIoException;
import io.github.marinco.aviation.domain.exception.UpstreamRateLimitedException;
import io.github.marinco.aviation.domain.exception.UpstreamServerException;
import io.github.marinco.aviation.domain.exception.UpstreamTimeoutException;
import io.github.marinco.aviation.domain.exception.UpstreamUnavailableException;
import io.github.marinco.aviation.provider.resilience.ResilientCall;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.Locale;
import java.util.Optional;

/** {@link AirportProvider} for aviationweather.gov: the HTTP client behind the resilience guards. */
public class AviationWeatherAirportProvider implements AirportProvider {

    public static final String NAME = "aviationweather";

    private final AviationWeatherClient client;
    private final ResilientCall guards;
    private final MeterRegistry meterRegistry;

    AviationWeatherAirportProvider(AviationWeatherClient client, ResilientCall guards, MeterRegistry meterRegistry) {
        this.client = client;
        this.guards = guards;
        this.meterRegistry = meterRegistry;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public Optional<Airport> findByIcao(String icao) {
        Timer.Sample sample = Timer.start(meterRegistry);
        String outcome = "error";
        try {
            Optional<Airport> airport = guards.execute(() -> client.fetch(icao)).map(AviationWeatherMapper::toAirport);
            outcome = airport.isPresent() ? "found" : "not_found";
            return airport;
        } catch (RuntimeException e) {
            outcome = outcomeOf(e);
            throw e;
        } finally {
            sample.stop(meterRegistry.timer("airport.upstream.requests", "provider", NAME, "outcome", outcome));
        }
    }

    /** A fixed set of tag values, so metrics don't change when classes are renamed. */
    private static String outcomeOf(RuntimeException e) {
        return switch (e) {
            case UpstreamTimeoutException ignored -> "timeout";
            case LookupTimeoutException ignored -> "time_limit";
            case UpstreamIoException ignored -> "io_error";
            case UpstreamServerException ignored -> "server_error";
            case UpstreamForbiddenException ignored -> "forbidden";
            case UpstreamRateLimitedException ignored -> "rate_limited";
            case UpstreamClientException ignored -> "client_error";
            case UpstreamBadResponseException ignored -> "bad_response";
            case UpstreamUnavailableException u ->
                "not_called_" + u.reason().name().toLowerCase(Locale.ROOT);
            default -> "other";
        };
    }
}
