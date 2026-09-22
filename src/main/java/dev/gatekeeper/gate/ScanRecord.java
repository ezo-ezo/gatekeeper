package dev.gatekeeper.gate;

import java.time.Instant;
import java.util.Objects;

/**
 * One scan attempt as the gate itself saw it, kept in the gate's local log
 * regardless of outcome. This is the record that gets synced to the server
 * once connectivity returns, and reconciliation (phase 4) works entirely from
 * these logs across gates.
 */
public record ScanRecord(String gateId, String ticketId, Instant scannedAt, boolean accepted) {

    public ScanRecord {
        Objects.requireNonNull(gateId, "gateId");
        Objects.requireNonNull(ticketId, "ticketId");
        Objects.requireNonNull(scannedAt, "scannedAt");
    }
}
