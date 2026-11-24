package io.github.marinco.aviation.api;

import io.github.marinco.aviation.domain.Airport;
import io.github.marinco.aviation.domain.LookupResult;
import io.github.marinco.aviation.domain.Runway;
import java.time.Instant;
import java.util.List;

/** The JSON body of a successful lookup. */
public record AirportResponse(
        String icao,
        String iata,
        String name,
        LocationResponse location,
        List<RunwayResponse> runways,
        String source,
        Instant retrievedAt) {

    public record LocationResponse(
            String region, String country, Double latitude, Double longitude, Integer elevationM) {}

    public record RunwayResponse(String id, Integer lengthFt, Integer widthFt, Runway.Surface surface) {}

    static AirportResponse from(LookupResult result) {
        Airport a = result.airport();
        var loc = a.location();
        return new AirportResponse(
                a.icao(),
                a.iata(),
                a.name(),
                loc == null
                        ? null
                        : new LocationResponse(
                                loc.region(), loc.country(), loc.latitude(), loc.longitude(), loc.elevationM()),
                a.runways().stream()
                        .map(r -> new RunwayResponse(r.id(), r.lengthFt(), r.widthFt(), r.surface()))
                        .toList(),
                a.source(),
                result.retrievedAt());
    }
}
