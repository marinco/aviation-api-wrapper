package io.github.marinco.aviation.provider.aviationweather;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/** One element of the JSON array returned by {@code /api/data/airport}. Only the fields we map are listed. */
@JsonIgnoreProperties(ignoreUnknown = true)
record AviationWeatherAirportDto(
        String icaoId,
        String iataId,
        String name,
        String state,
        String country,
        Double lat,
        Double lon,
        Double elev,
        List<RunwayDto> runways) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    record RunwayDto(String id, String dimension, String surface) {}
}
