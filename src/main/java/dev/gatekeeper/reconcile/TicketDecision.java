package dev.gatekeeper.reconcile;

import dev.gatekeeper.gate.ScanRecord;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * The reconciled outcome for one ticket: which accepted scan stands as "this
 * person is inside", and which other accepted scans for the same ticket lost
 * out and need a human's attention.
 */
public record TicketDecision(String ticketId, ScanRecord acceptedEntry, List<ScanRecord> conflictingEntries) {

    public TicketDecision {
        Objects.requireNonNull(ticketId, "ticketId");
        Objects.requireNonNull(acceptedEntry, "acceptedEntry");
        conflictingEntries = List.copyOf(conflictingEntries);
    }

    /** True when this ticket was accepted at more than one gate before they synced. */
    public boolean hasConflict() {
        return !conflictingEntries.isEmpty();
    }

    /** Every gate that accepted this ticket, winner included. */
    public Set<String> gateIds() {
        return Stream.concat(Stream.of(acceptedEntry), conflictingEntries.stream())
                .map(ScanRecord::gateId)
                .collect(Collectors.toUnmodifiableSet());
    }
}
