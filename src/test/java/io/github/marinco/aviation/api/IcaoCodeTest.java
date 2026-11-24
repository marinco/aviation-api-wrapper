package io.github.marinco.aviation.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

class IcaoCodeTest {

    @ParameterizedTest
    @ValueSource(strings = {"KJFK", "kjfk", "EgLl", "K0A9"})
    void acceptsFourLettersOrDigitsAndUpperCases(String raw) {
        assertThat(IcaoCode.normalize(raw)).isEqualTo(raw.toUpperCase());
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "KJF", "KJFKX", "KJ*", "KJFK,EGLL", "KJ K", "KJ-K", "ÄJFK"})
    void rejectsEverythingElse(String raw) {
        assertThatThrownBy(() -> IcaoCode.normalize(raw)).isInstanceOf(InvalidIcaoException.class);
    }

    /**
     * Under a Turkish default locale {@code "i".toUpperCase()} is {@code "İ"} (dotted capital I), which would fail
     * validation and change the cache key. {@code Locale.ROOT} keeps the mapping plain A-Z on every machine.
     */
    @Test
    void upperCasingIgnoresTheTurkishDefaultLocale() {
        Locale original = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));
            assertThat("lirf".toUpperCase()).isEqualTo("LİRF"); // the trap
            assertThat(IcaoCode.normalize("lirf")).isEqualTo("LIRF");
        } finally {
            Locale.setDefault(original);
        }
    }
}
