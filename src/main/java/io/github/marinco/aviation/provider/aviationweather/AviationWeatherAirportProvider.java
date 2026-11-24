package io.github.marinco.aviation.provider.aviationweather;

import io.github.marinco.aviation.domain.Airport;
import io.github.marinco.aviation.domain.AirportProvider;
import io.github.marinco.aviation.domain.exception.UpstreamException;
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
        } catch (UpstreamException e) {
            outcome = e.kind().name().toLowerCase(Locale.ROOT);
            throw e;
        } finally {
            sample.stop(meterRegistry.timer("airport.upstream.requests", "provider", NAME, "outcome", outcome));
        }
    }
}
