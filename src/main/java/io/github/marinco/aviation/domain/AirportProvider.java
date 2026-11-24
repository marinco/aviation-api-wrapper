package io.github.marinco.aviation.domain;

import java.util.Optional;

/**
 * Port to an external source of airport data. One adapter per provider; the active one is chosen by the
 * {@code app.provider} property.
 *
 * <p>Implementations return {@link Optional#empty()} when the provider says the airport doesn't exist, and throw an
 * {@link io.github.marinco.aviation.domain.exception.UpstreamException} when the lookup itself failed.
 */
public interface AirportProvider {

    /** Short name used in logs, metrics and the response's {@code source} field. */
    String name();

    Optional<Airport> findByIcao(String icao);
}
