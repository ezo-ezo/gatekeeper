package dev.gatekeeper.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;

/**
 * One scan as a gate reports it during sync. There is deliberately no
 * {@code gateId} field here: it comes from the sync request's URL path, so a
 * gate can only ever report scans under its own identity, never anyone
 * else's.
 */
public record ScanRecordRequest(

        @NotBlank(message = "must not be blank")
        String ticketId,

        @NotNull(message = "must not be null")
        Instant scannedAt,

        @NotNull(message = "must be true or false, not omitted")
        Boolean accepted) {
}
