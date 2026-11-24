package io.github.marinco.aviation.api;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Validates and normalises an ICAO code. Exactly four letters or digits: this also blocks the upstream's list and
 * wildcard syntax, so one request can never fan out into many upstream lookups.
 */
final class IcaoCode {

    private static final Pattern VALID = Pattern.compile("^[A-Za-z0-9]{4}$");

    private IcaoCode() {}

    static boolean isValid(String raw) {
        return raw != null && VALID.matcher(raw).matches();
    }

    static String normalize(String raw) {
        if (!isValid(raw)) {
            throw new InvalidIcaoException();
        }
        return raw.toUpperCase(Locale.ROOT);
    }
}
