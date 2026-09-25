package dev.gatekeeper.simulation;

import dev.gatekeeper.gate.ScanRecord;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What reconciliation is supposed to produce, worked out straight from the
 * gates' own logs with the simplest code that could do it: pool every record,
 * group the accepted ones by ticket, take the earliest as the winner.
 *
 * <p>This is deliberately not a second copy of {@code Reconciler}'s
 * implementation. It is a plain restatement of the same rule that shares none
 * of its code, so a bug in the real one's grouping, ordering or duplicate
 * handling shows up as a disagreement. What it cannot catch is the rule itself
 * being the wrong rule; it encodes the same "earliest scan wins" policy, so it
 * tests the mechanics (merging, deduplication, order-independence,
 * persistence), not the policy.
 *
 * @param distinctRecords number of distinct scans across all gates, counting a scan once however
 *                        many times it was uploaded
 * @param acceptedTickets every ticket accepted at least once, with its expected winner and losers
 */
public record Oracle(int distinctRecords, Map<String, Expected> acceptedTickets) {

    /** The expected outcome for one ticket. */
    public record Expected(ScanRecord winner, List<ScanRecord> losers) {
        public boolean conflict() {
            return !losers.isEmpty();
        }
    }

    private static final Comparator<ScanRecord> EARLIEST_FIRST =
            Comparator.comparing(ScanRecord::scannedAt).thenComparing(ScanRecord::gateId);

    public static Oracle from(Collection<List<ScanRecord>> logs) {
        Set<ScanRecord> distinct = new HashSet<>();
        for (List<ScanRecord> log : logs) {
            distinct.addAll(log);
        }

        Map<String, List<ScanRecord>> acceptedByTicket = new HashMap<>();
        for (ScanRecord record : distinct) {
            if (record.accepted()) {
                acceptedByTicket.computeIfAbsent(record.ticketId(), id -> new ArrayList<>()).add(record);
            }
        }

        Map<String, Expected> expected = new HashMap<>();
        acceptedByTicket.forEach((ticketId, accepted) -> {
            ScanRecord winner = Collections.min(accepted, EARLIEST_FIRST);
            List<ScanRecord> losers = accepted.stream()
                    .filter(record -> !record.equals(winner))
                    .sorted(EARLIEST_FIRST)
                    .toList();
            expected.put(ticketId, new Expected(winner, losers));
        });

        return new Oracle(distinct.size(), Map.copyOf(expected));
    }

    public int acceptedTicketCount() {
        return acceptedTickets.size();
    }

    /** Tickets accepted at more than one gate. */
    public int conflictCount() {
        return (int) acceptedTickets.values().stream().filter(Expected::conflict).count();
    }
}
