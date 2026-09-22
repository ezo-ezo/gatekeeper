package dev.gatekeeper.reconcile;

import java.util.List;
import java.util.Objects;

/**
 * The result of one {@link Reconciler#reconcile()} run: a decision for every
 * ticket that was accepted anywhere, in a stable order so the same input
 * always prints the same way.
 */
public record ReconciliationReport(List<TicketDecision> decisions, int totalScansConsidered) {

    public ReconciliationReport {
        decisions = List.copyOf(decisions);
    }

    /** Only the tickets that need a human to look at them. */
    public List<TicketDecision> conflicts() {
        return decisions.stream().filter(TicketDecision::hasConflict).toList();
    }

    public int acceptedTicketCount() {
        return decisions.size();
    }
}
