package dev.gatekeeper.gate;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;

/**
 * One scan attempt as the gate itself saw it, kept in the gate's local log
 * regardless of outcome. This is the record that gets synced to the server
 * once connectivity returns, and reconciliation (phase 4) works entirely from
 * these logs across gates.
 *
 * <p>Two records with equal fields are the same scan. That is what makes
 * re-syncing a no-op, so the fields must come out identical after every trip
 * a record takes: gate log, JSON, database and back. A timestamp is the field
 * that can break this, because real clocks are finer than what a database
 * column keeps (Windows gives 100ns, Linux 1ns, an H2 column microseconds,
 * and the database rounds rather than truncates), which would make a resent
 * scan fail to match the copy already stored. So {@code scannedAt} is
 * canonicalised to milliseconds here, in the one place every record is
 * created; milliseconds is ample resolution for a person walking through a
 * gate.
 */
public record ScanRecord(String gateId, String ticketId, Instant scannedAt, boolean accepted) {

    public ScanRecord {
        Objects.requireNonNull(gateId, "gateId");
        Objects.requireNonNull(ticketId, "ticketId");
        Objects.requireNonNull(scannedAt, "scannedAt");
        scannedAt = scannedAt.truncatedTo(ChronoUnit.MILLIS);
    }
}
