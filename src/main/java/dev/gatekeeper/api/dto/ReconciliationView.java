package dev.gatekeeper.api.dto;

import dev.gatekeeper.reconcile.ReconciliationReport;

import java.util.List;

/** The full picture: every ticket that was accepted anywhere, and how many need review. */
public record ReconciliationView(List<TicketDecisionView> decisions, int totalScansConsidered, int conflictCount) {

    public static ReconciliationView from(ReconciliationReport report) {
        List<TicketDecisionView> decisions = report.decisions().stream().map(TicketDecisionView::from).toList();
        long conflicts = decisions.stream().filter(d -> !d.conflicts().isEmpty()).count();
        return new ReconciliationView(decisions, report.totalScansConsidered(), (int) conflicts);
    }
}
