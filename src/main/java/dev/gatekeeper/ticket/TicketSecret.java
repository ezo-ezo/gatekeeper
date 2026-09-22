package dev.gatekeeper.ticket;

import java.security.SecureRandom;
import java.util.Arrays;

/**
 * The shared key behind a ticket's rotating code. Whoever holds this secret
 * can compute the code that should currently be showing on the ticket, so it
 * is handled like a credential: never logged, never printed, and
 * {@link #toString()} deliberately does not reveal it.
 */
public final class TicketSecret {

    /** 160 bits, the size HOTP/TOTP implementations conventionally use with HMAC-SHA1. */
    private static final int KEY_BYTES = 20;

    private static final SecureRandom RANDOM = new SecureRandom();

    private final byte[] key;

    private TicketSecret(byte[] key) {
        this.key = key;
    }

    /** A fresh, random secret for a newly issued ticket. */
    public static TicketSecret generate() {
        byte[] key = new byte[KEY_BYTES];
        RANDOM.nextBytes(key);
        return new TicketSecret(key);
    }

    /** Wraps an existing raw key, e.g. one loaded back out of storage. */
    public static TicketSecret of(byte[] rawKey) {
        if (rawKey.length == 0) {
            throw new IllegalArgumentException("key must not be empty");
        }
        return new TicketSecret(Arrays.copyOf(rawKey, rawKey.length));
    }

    /** Decodes a secret previously written with {@link #toBase32()}. */
    public static TicketSecret fromBase32(String base32) {
        return of(Base32.decode(base32));
    }

    /** The printable form used when persisting a ticket or provisioning a gate. */
    public String toBase32() {
        return Base32.encode(key);
    }

    /** Package-private: only the token generator needs the raw bytes. */
    byte[] keyBytes() {
        return Arrays.copyOf(key, key.length);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof TicketSecret other && Arrays.equals(key, other.key);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(key);
    }

    @Override
    public String toString() {
        return "TicketSecret[REDACTED]";
    }
}
