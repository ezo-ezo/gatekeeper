package dev.gatekeeper.ticket;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.nio.charset.StandardCharsets;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TotpTokenGeneratorTest {

    /**
     * RFC 6238 Appendix B publishes reference HOTP/TOTP values for the ASCII
     * key "12345678901234567890" at a 30s step. Those reference codes are
     * 8 digits (mod 10^8); this generator emits 6 (mod 10^6), which is the
     * same HOTP truncation with a different digit count, so RFC(code) mod
     * 10^6 must equal what we produce. This is a real interop check, not
     * just a self-consistency test.
     */
    private static final TicketSecret RFC_6238_SECRET =
            TicketSecret.of("12345678901234567890".getBytes(StandardCharsets.US_ASCII));

    @ParameterizedTest
    @CsvSource({
            "59, 287082",           // RFC 6238 Appendix B: T=59 -> 94287082
            "1111111109, 081804",   // T=1111111109 -> 07081804
            "1111111111, 050471",   // T=1111111111 -> 14050471 (last 6 digits)
            "1234567890, 005924",   // T=1234567890 -> 89005924
    })
    void matchesRfc6238TestVectors(long epochSecond, String expectedLast6Digits) {
        String code = TotpTokenGenerator.generate(RFC_6238_SECRET, Instant.ofEpochSecond(epochSecond));
        assertThat(code).isEqualTo(expectedLast6Digits);
    }

    @Test
    void codeIsAlwaysSixDigitsEvenWithLeadingZeros() {
        // Regression: String.format zero-pads, a naive Integer.toString would not.
        String code = TotpTokenGenerator.generate(RFC_6238_SECRET, Instant.ofEpochSecond(1111111109));
        assertThat(code).hasSize(6).matches("\\d{6}");
    }

    @Test
    void sameTimeStepProducesTheSameCode() {
        TicketSecret secret = TicketSecret.generate();
        Instant windowStart = alignedToWindowStart(Instant.ofEpochSecond(1_700_000_000));
        String first = TotpTokenGenerator.generate(secret, windowStart);
        String secondsLater = TotpTokenGenerator.generate(secret, windowStart.plusSeconds(29));
        assertThat(secondsLater).isEqualTo(first);
    }

    @Test
    void codeChangesAcrossTimeSteps() {
        TicketSecret secret = TicketSecret.generate();
        Instant start = Instant.ofEpochSecond(1_700_000_000);

        long distinctCodes = java.util.stream.LongStream.range(0, 10)
                .mapToObj(i -> TotpTokenGenerator.generate(secret, start.plusSeconds(i * 30)))
                .distinct()
                .count();

        // Vanishingly unlikely to collide across 10 independent HMAC outputs
        // unless the step boundary is wrong.
        assertThat(distinctCodes).isGreaterThan(1);
    }

    @Test
    void verifyAcceptsExactMatch() {
        TicketSecret secret = TicketSecret.generate();
        Instant now = Instant.ofEpochSecond(1_700_000_000);
        String code = TotpTokenGenerator.generate(secret, now);
        assertThat(TotpTokenGenerator.verify(secret, code, now, 0)).isTrue();
    }

    @Test
    void verifyAcceptsCodeWithinAllowedDrift() {
        TicketSecret secret = TicketSecret.generate();
        Instant issuedAt = alignedToWindowStart(Instant.ofEpochSecond(1_700_000_000));
        String code = TotpTokenGenerator.generate(secret, issuedAt);

        // A gate whose clock is 40s fast: one step (30s) past the code's window, plus
        // 10s into the next - still within 1 step of drift tolerance.
        Instant gateClock = issuedAt.plusSeconds(40);
        assertThat(TotpTokenGenerator.verify(secret, code, gateClock, 1)).isTrue();
    }

    @Test
    void verifyRejectsCodeOutsideAllowedDrift() {
        TicketSecret secret = TicketSecret.generate();
        Instant issuedAt = Instant.ofEpochSecond(1_700_000_000);
        String code = TotpTokenGenerator.generate(secret, issuedAt);

        Instant farFuture = issuedAt.plus(TotpTokenGenerator.TIME_STEP.multipliedBy(5));
        assertThat(TotpTokenGenerator.verify(secret, code, farFuture, 1)).isFalse();
    }

    @Test
    void verifyRejectsWithZeroDriftJustOutsideTheWindow() {
        TicketSecret secret = TicketSecret.generate();
        Instant issuedAt = Instant.ofEpochSecond(1_700_000_000);
        String code = TotpTokenGenerator.generate(secret, issuedAt);

        Instant nextWindow = issuedAt.plus(TotpTokenGenerator.TIME_STEP);
        assertThat(TotpTokenGenerator.verify(secret, code, nextWindow, 0)).isFalse();
    }

    @Test
    void verifyRejectsCodeFromADifferentSecret() {
        TicketSecret secret = TicketSecret.generate();
        TicketSecret otherSecret = TicketSecret.generate();
        Instant now = Instant.ofEpochSecond(1_700_000_000);

        String code = TotpTokenGenerator.generate(secret, now);
        assertThat(TotpTokenGenerator.verify(otherSecret, code, now, 2)).isFalse();
    }

    @Test
    void verifyRejectsWrongLengthCode() {
        TicketSecret secret = TicketSecret.generate();
        Instant now = Instant.ofEpochSecond(1_700_000_000);
        assertThat(TotpTokenGenerator.verify(secret, "123", now, 1)).isFalse();
    }

    @Test
    void verifyRejectsNegativeDrift() {
        TicketSecret secret = TicketSecret.generate();
        assertThatThrownBy(() -> TotpTokenGenerator.verify(secret, "000000", Instant.now(), -1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** Rounds down to the start of the 30s window {@code instant} falls in. */
    private static Instant alignedToWindowStart(Instant instant) {
        long stepSeconds = TotpTokenGenerator.TIME_STEP.getSeconds();
        long aligned = (instant.getEpochSecond() / stepSeconds) * stepSeconds;
        return Instant.ofEpochSecond(aligned);
    }
}
