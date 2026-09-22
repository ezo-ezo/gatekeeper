package dev.gatekeeper.reconcile;

import dev.gatekeeper.gate.ScanRecord;

import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Merges scan logs synced in from any number of gates into one picture of
 * who is actually inside, and flags any ticket that was accepted at more
 * than one gate before those gates could compare notes.
 *
 * <p>This is deliberately a CRDT, not a "first request wins" server: the
 * state is a grow-only set of scans ({@link #ingest} only ever adds),
 * {@link ScanRecord}'s value equality makes re-syncing an already-seen scan a
 * no-op, and {@link #reconcile()} is a pure function of whatever has been
 * ingested so far. Two consequences follow, both load-bearing for an
 * offline-first system:
 * <ul>
 *   <li><b>Ingestion order doesn't matter.</b> Gate A's log can arrive before
 *       or after Gate B's, or interleaved with retries of either, and the
 *       final report is the same.</li>
 *   <li><b>Re-running is always safe.</b> Calling {@code reconcile()} twice
 *       with nothing new ingested gives an identical report, and a sync that
 *       gets retried after a dropped connection just re-adds scans that are
 *       already in the set.</li>
 * </ul>
 *
 * <p><b>Resolution policy:</b> when a ticket was accepted at more than one
 * gate, the scan with the earliest {@link ScanRecord#scannedAt()} wins; the
 * rest are reported as conflicts for a human to review, not silently
 * dropped. This trusts gate clocks to be reasonably close to each other,
 * which the system already assumes elsewhere — a gate clock drifted enough
 * to make "who was first" meaningless would already be failing ordinary
 * TOTP validation (see {@code docs/design.md} for what a logical-clock
 * ordering would buy instead, and why it isn't done here).
 *
 * <p>Thread-safe: {@link #ingest} may be called concurrently as multiple
 * gates sync at once.
 */
public final class Reconciler {

    private final Set<ScanRecord> scans = ConcurrentHashMap.newKeySet();

    /**
     * Adds one gate's synced scan log to the merged state. Scans already
     * present (by {@link ScanRecord} equality) are ignored, so syncing the
     * same log again — the whole log, or an overlapping tail of it — is safe.
     */
    public void ingest(Collection<ScanRecord> gateLog) {
        scans.addAll(gateLog);
    }

    /** Recomputes the decision for every ticket accepted anywhere, from all scans ingested so far. */
    public ReconciliationReport reconcile() {
        Map<String, List<ScanRecord>> acceptedByTicket = scans.stream()
                .filter(ScanRecord::accepted)
                .collect(Collectors.groupingBy(ScanRecord::ticketId));

        if (acceptedByTicket.isEmpty()) {
            return new ReconciliationReport(List.of(), scans.size());
        }

        List<TicketDecision> decisions = acceptedByTicket.entrySet().stream()
                .map(entry -> decide(entry.getKey(), entry.getValue()))
                .sorted(Comparator.comparing(TicketDecision::ticketId))
                .toList();

        return new ReconciliationReport(decisions, scans.size());
    }

    private TicketDecision decide(String ticketId, List<ScanRecord> acceptedScans) {
        List<ScanRecord> ordered = acceptedScans.stream()
                // Tie-break on gateId so two scans at the exact same instant still
                // resolve the same way regardless of which order they were ingested in.
                .sorted(Comparator.comparing(ScanRecord::scannedAt).thenComparing(ScanRecord::gateId))
                .toList();

        ScanRecord winner = ordered.get(0);
        List<ScanRecord> conflicts = ordered.subList(1, ordered.size());
        return new TicketDecision(ticketId, winner, conflicts);
    }
}
