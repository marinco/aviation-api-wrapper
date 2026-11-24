package io.github.marinco.aviation.provider.aviationweather;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.marinco.aviation.domain.Airport;
import io.github.marinco.aviation.domain.Runway;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

class AviationWeatherMapperTest {

    @Test
    void mapsRealKjfkResponse() throws IOException {
        List<AviationWeatherAirportDto> dtos;
        try (InputStream in = getClass().getResourceAsStream("/upstream/kjfk.json")) {
            dtos = JsonMapper.builder().build().readValue(in, new TypeReference<>() {});
        }

        Airport airport = AviationWeatherMapper.toAirport(dtos.getFirst());

        assertThat(airport.icao()).isEqualTo("KJFK");
        assertThat(airport.iata()).isEqualTo("JFK");
        assertThat(airport.name()).isEqualTo("NEW YORK/JOHN F KENNEDY INTL"); // trailing space trimmed
        assertThat(airport.location().region()).isEqualTo("NY");
        assertThat(airport.location().country()).isEqualTo("US");
        assertThat(airport.location().latitude()).isEqualTo(40.6399);
        assertThat(airport.location().longitude()).isEqualTo(-73.7787);
        assertThat(airport.location().elevationM()).isEqualTo(4);
        assertThat(airport.source()).isEqualTo("aviationweather.gov");
        assertThat(airport.runways()).hasSize(4);
        assertThat(airport.runways().getFirst()).isEqualTo(new Runway("04L/22R", 12079, 200, Runway.Surface.CONCRETE));
        assertThat(airport.runways().get(1).surface()).isEqualTo(Runway.Surface.ASPHALT);
    }

    @Test
    void handlesMissingAndOddFields() {
        var dto = new AviationWeatherAirportDto(
                "zzzz",
                " ",
                null,
                null,
                "US",
                null,
                null,
                null,
                List.of(new AviationWeatherAirportDto.RunwayDto("H1", "bad", "X")));

        Airport airport = AviationWeatherMapper.toAirport(dto);

        assertThat(airport.icao()).isEqualTo("ZZZZ");
        assertThat(airport.iata()).isNull();
        assertThat(airport.name()).isNull();
        assertThat(airport.location().elevationM()).isNull();
        assertThat(airport.runways()).containsExactly(new Runway("H1", null, null, Runway.Surface.UNKNOWN));
    }

    @Test
    void decodesSurfaceCodes() {
        assertThat(AviationWeatherMapper.surface("A")).isEqualTo(Runway.Surface.ASPHALT);
        assertThat(AviationWeatherMapper.surface("c")).isEqualTo(Runway.Surface.CONCRETE);
        assertThat(AviationWeatherMapper.surface("G")).isEqualTo(Runway.Surface.UNKNOWN);
        assertThat(AviationWeatherMapper.surface(null)).isEqualTo(Runway.Surface.UNKNOWN);
    }
}
