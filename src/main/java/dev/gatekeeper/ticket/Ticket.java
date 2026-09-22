package dev.gatekeeper.ticket;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * A single admission ticket. {@link #secret()} is the shared key behind the
 * rotating code printed as the ticket's QR image (see
 * {@link TotpTokenGenerator}); it is generated once at issue time and never
 * changes, but the code derived from it does, every 30 seconds.
 */
public record Ticket(String id, String eventId, String holderName, TicketSecret secret, Instant issuedAt) {

    public Ticket {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(holderName, "holderName");
        Objects.requireNonNull(secret, "secret");
        Objects.requireNonNull(issuedAt, "issuedAt");
        requireNotBlank(id, "id");
        requireNotBlank(eventId, "eventId");
        requireNotBlank(holderName, "holderName");
    }

    /** Issues a new ticket with a freshly generated, random secret. */
    public static Ticket issue(String eventId, String holderName, Instant issuedAt) {
        return new Ticket(UUID.randomUUID().toString(), eventId, holderName, TicketSecret.generate(), issuedAt);
    }

    /** The code that should be showing on this ticket's QR code right now. */
    public String currentCode(Instant now) {
        return TotpTokenGenerator.generate(secret, now);
    }

    private static void requireNotBlank(String value, String field) {
        if (value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }
}
