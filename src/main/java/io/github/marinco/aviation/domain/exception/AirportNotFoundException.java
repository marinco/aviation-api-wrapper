package io.github.marinco.aviation.domain.exception;

/** The provider says no airport has this ICAO code. */
public class AirportNotFoundException extends RuntimeException {

    private final String icao;

    public AirportNotFoundException(String icao) {
        super("Airport not found: " + icao);
        this.icao = icao;
    }

    public String icao() {
        return icao;
    }
}
