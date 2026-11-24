package io.github.marinco.aviation.api;

import io.github.marinco.aviation.domain.AirportService;
import io.github.marinco.aviation.domain.CacheStatus;
import io.github.marinco.aviation.domain.LookupResult;
import io.micrometer.core.instrument.MeterRegistry;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import java.time.Duration;
import org.springframework.http.CacheControl;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/airports")
class AirportController {

    static final String CACHE_HEADER = "X-Cache";
    private static final Duration MAX_CLIENT_CACHE = Duration.ofHours(1);

    private final AirportService airportService;
    private final MeterRegistry meterRegistry;

    AirportController(AirportService airportService, MeterRegistry meterRegistry) {
        this.airportService = airportService;
        this.meterRegistry = meterRegistry;
    }

    @Operation(summary = "Look up an airport by ICAO code")
    @ApiResponse(
            responseCode = "200",
            description = "Airport found (fresh, or a stale copy while the provider is failing)",
            headers = @Header(name = CACHE_HEADER, description = "HIT, MISS or STALE"))
    @ApiResponse(
            responseCode = "400",
            description = "Not a valid ICAO code",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(
            responseCode = "404",
            description = "No airport with this code",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(
            responseCode = "502",
            description = "The provider failed",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(
            responseCode = "503",
            description = "Provider not called (circuit open or rate limited); see Retry-After",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(
            responseCode = "504",
            description = "The provider timed out",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @GetMapping("/{icao}")
    ResponseEntity<AirportResponse> getAirport(
            @Parameter(description = "4-character ICAO code, e.g. KJFK", example = "KJFK") @PathVariable String icao) {
        String code;
        try {
            code = IcaoCode.normalize(icao);
        } catch (InvalidIcaoException e) {
            meterRegistry
                    .counter("airport.lookups", "outcome", "invalid", "cache", "none")
                    .increment();
            throw e;
        }
        LookupResult result = airportService.lookup(code);
        return ResponseEntity.ok()
                .cacheControl(cacheControl(result))
                .header(CACHE_HEADER, result.cacheStatus().name())
                .body(AirportResponse.from(result));
    }

    private static CacheControl cacheControl(LookupResult result) {
        if (result.cacheStatus() == CacheStatus.STALE) {
            return CacheControl.noCache();
        }
        Duration maxAge = result.freshFor().compareTo(MAX_CLIENT_CACHE) < 0 ? result.freshFor() : MAX_CLIENT_CACHE;
        return CacheControl.maxAge(maxAge);
    }
}
