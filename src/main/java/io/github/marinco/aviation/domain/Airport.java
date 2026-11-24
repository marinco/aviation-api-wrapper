package io.github.marinco.aviation.domain;

import java.util.List;

/** An airport as this service exposes it, independent of any data provider. */
public record Airport(String icao, String iata, String name, Location location, List<Runway> runways, String source) {

    public Airport {
        runways = runways == null ? List.of() : List.copyOf(runways);
    }
}
