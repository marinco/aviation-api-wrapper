package io.github.marinco.aviation.provider.aviationweather;

import io.github.marinco.aviation.domain.Airport;
import io.github.marinco.aviation.domain.Location;
import io.github.marinco.aviation.domain.Runway;
import java.util.List;

/** Maps aviationweather.gov's shape to ours: trims names, splits runway dimensions, decodes surface codes. */
final class AviationWeatherMapper {

    static final String SOURCE = "aviationweather.gov";

    private AviationWeatherMapper() {}

    static Airport toAirport(AviationWeatherAirportDto dto) {
        Location location = new Location(
                blankToNull(dto.state()),
                blankToNull(dto.country()),
                dto.lat(),
                dto.lon(),
                dto.elev() == null ? null : (int) Math.round(dto.elev()));
        List<Runway> runways = dto.runways() == null
                ? List.of()
                : dto.runways().stream().map(AviationWeatherMapper::toRunway).toList();
        return new Airport(
                dto.icaoId().trim().toUpperCase(),
                blankToNull(dto.iataId()),
                blankToNull(dto.name()),
                location,
                runways,
                SOURCE);
    }

    static Runway toRunway(AviationWeatherAirportDto.RunwayDto dto) {
        Integer length = null;
        Integer width = null;
        if (dto.dimension() != null) {
            String[] parts = dto.dimension().trim().split("x");
            if (parts.length == 2) {
                length = parseIntOrNull(parts[0]);
                width = parseIntOrNull(parts[1]);
            }
        }
        return new Runway(blankToNull(dto.id()), length, width, surface(dto.surface()));
    }

    /** Codes seen in real responses: A (asphalt) and C (concrete). Anything else is UNKNOWN rather than a guess. */
    static Runway.Surface surface(String code) {
        if (code == null) {
            return Runway.Surface.UNKNOWN;
        }
        return switch (code.trim().toUpperCase()) {
            case "A" -> Runway.Surface.ASPHALT;
            case "C" -> Runway.Surface.CONCRETE;
            default -> Runway.Surface.UNKNOWN;
        };
    }

    private static Integer parseIntOrNull(String s) {
        try {
            return Integer.valueOf(s.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
