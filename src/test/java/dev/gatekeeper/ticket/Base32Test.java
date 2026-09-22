package dev.gatekeeper.ticket;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Checks against the RFC 4648 test vectors (section 10), with the usual
 * '=' padding stripped since that is how TOTP secrets are conventionally
 * written.
 */
class Base32Test {

    @ParameterizedTest
    @CsvSource({
            "'',''",
            "f,MY",
            "fo,MZXQ",
            "foo,MZXW6",
            "foob,MZXW6YQ",
            "fooba,MZXW6YTB",
            "foobar,MZXW6YTBOI",
    })
    void matchesRfc4648Vectors(String input, String expected) {
        byte[] data = input.getBytes(StandardCharsets.US_ASCII);
        assertThat(Base32.encode(data)).isEqualTo(expected);
        assertThat(Base32.decode(expected)).isEqualTo(data);
    }

    @ParameterizedTest
    @CsvSource({"mzxw6ytboi", "MzXw6YtBoI"})
    void decodeIsCaseInsensitive(String mixedCase) {
        assertThat(Base32.decode(mixedCase)).isEqualTo("foobar".getBytes(StandardCharsets.US_ASCII));
    }

    @org.junit.jupiter.api.Test
    void decodeRejectsInvalidCharacters() {
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, () -> Base32.decode("not-base32!"));
    }
}
