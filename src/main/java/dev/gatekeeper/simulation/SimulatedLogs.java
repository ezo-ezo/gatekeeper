package dev.gatekeeper.simulation;

import dev.gatekeeper.gate.ScanRecord;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What every gate recorded while cut off from the network, exactly as each
 * gate's own {@code Gate.log()} reports it, plus how those scans came out.
 * This is the raw material a sync would upload.
 */
public record SimulatedLogs(
        Map<String, List<ScanRecord>> byGate,
        int accepted,
        int rejectedDuplicate,
        int rejectedInvalidCode,
        int rejectedUnknownTicket) {

    public SimulatedLogs {
        // Copied into a LinkedHashMap, not Map.copyOf: the latter's iteration order
        // is randomised per JVM, which would make a seeded run non-reproducible.
        Map<String, List<ScanRecord>> ordered = new LinkedHashMap<>();
        byGate.forEach((gateId, log) -> ordered.put(gateId, List.copyOf(log)));
        byGate = Collections.unmodifiableMap(ordered);
    }

    /** Every scan attempt at every gate, accepted or not. */
    public int presentations() {
        return byGate.values().stream().mapToInt(List::size).sum();
    }
}
