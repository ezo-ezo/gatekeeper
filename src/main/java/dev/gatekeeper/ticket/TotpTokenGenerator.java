package dev.gatekeeper.ticket;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.time.Duration;
import java.time.Instant;

/**
 * Time-based one-time codes (RFC 6238 TOTP over RFC 4226 HOTP, HMAC-SHA1),
 * the same construction behind apps like Google Authenticator. A ticket's QR
 * code encodes the current code, which changes every {@link #TIME_STEP}, so a
 * screenshot of it goes stale instead of staying scannable forever.
 *
 * <p>No network call is involved in either direction: generating a code and
 * verifying one are both pure functions of the secret and the clock, which is
 * what lets a gate validate a ticket with no connectivity (see
 * {@code docs/design.md}).
 */
public final class TotpTokenGenerator {

    /** How often the code changes. */
    public static final Duration TIME_STEP = Duration.ofSeconds(30);

    /** Digits in the printed code. */
    public static final int DIGITS = 6;

    private static final int MODULUS = (int) Math.pow(10, DIGITS);

    private TotpTokenGenerator() {
    }

    /** The code that should be showing on the ticket at {@code instant}. */
    public static String generate(TicketSecret secret, Instant instant) {
        return hotp(secret, timeStepOf(instant));
    }

    /**
     * Whether {@code code} was valid at {@code instant}, tolerating up to
     * {@code driftSteps} time steps of clock skew in either direction. A gate
     * validating offline may not have its clock perfectly synced, so some
     * tolerance is necessary; each extra step of tolerance also extends how
     * long a captured code stays guessable, which is the trade-off this
     * parameter controls.
     */
    public static boolean verify(TicketSecret secret, String code, Instant instant, int driftSteps) {
        if (driftSteps < 0) {
            throw new IllegalArgumentException("driftSteps must not be negative");
        }
        long step = timeStepOf(instant);
        for (long i = -driftSteps; i <= driftSteps; i++) {
            if (constantTimeEquals(hotp(secret, step + i), code)) {
                return true;
            }
        }
        return false;
    }

    private static long timeStepOf(Instant instant) {
        return instant.getEpochSecond() / TIME_STEP.getSeconds();
    }

    /** RFC 4226 HOTP: truncate an HMAC of the counter down to a decimal code. */
    private static String hotp(TicketSecret secret, long counter) {
        byte[] counterBytes = ByteBuffer.allocate(Long.BYTES).putLong(counter).array();
        byte[] hash = hmacSha1(secret.keyBytes(), counterBytes);

        int offset = hash[hash.length - 1] & 0x0F;
        int binary = ((hash[offset] & 0x7F) << 24)
                | ((hash[offset + 1] & 0xFF) << 16)
                | ((hash[offset + 2] & 0xFF) << 8)
                | (hash[offset + 3] & 0xFF);

        return String.format("%0" + DIGITS + "d", binary % MODULUS);
    }

    private static byte[] hmacSha1(byte[] key, byte[] message) {
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(key, "HmacSHA1"));
            return mac.doFinal(message);
        } catch (GeneralSecurityException e) {
            // HmacSHA1 with an arbitrary-length key is part of every standard JCA
            // provider; a failure here means the JVM itself is misconfigured.
            throw new IllegalStateException("HmacSHA1 should always be available", e);
        }
    }

    /** Avoids leaking, via timing, how many leading digits of a guess were right. */
    private static boolean constantTimeEquals(String a, String b) {
        if (a.length() != b.length()) {
            return false;
        }
        int diff = 0;
        for (int i = 0; i < a.length(); i++) {
            diff |= a.charAt(i) ^ b.charAt(i);
        }
        return diff == 0;
    }
}
