package dev.gatekeeper.api.dto;

import dev.gatekeeper.reconcile.ReconciliationReport;

/**
 * Just the totals from a reconciliation, for callers (dashboards, load tests)
 * that don't want to download every decision. The full report grows with the
 * number of tickets; this stays a few bytes.
 */
public record ReconciliationSummary(int totalScansConsidered, int acceptedTickets, int conflictCount) {

    public static ReconciliationSummary from(ReconciliationReport report) {
        return new ReconciliationSummary(
                report.totalScansConsidered(),
                report.acceptedTicketCount(),
                report.conflicts().size());
    }
}
