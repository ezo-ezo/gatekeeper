package dev.gatekeeper.api.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/** The body of a {@code POST /gates/{gateId}/sync} request: a gate's scan log, or a slice of it. */
public record SyncRequest(

        @NotNull(message = "must not be null")
        @Size(max = 10_000, message = "must not contain more than 10000 scans; split the sync into batches")
        List<@Valid ScanRecordRequest> scans) {
}
