package io.github.marinco.aviation.api;

/** The path variable isn't a valid ICAO code. */
public class InvalidIcaoException extends RuntimeException {

    public InvalidIcaoException() {
        super("ICAO code must be exactly 4 letters or digits");
    }
}
