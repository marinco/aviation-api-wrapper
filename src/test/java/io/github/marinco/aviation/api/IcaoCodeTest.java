package io.github.marinco.aviation.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
}
