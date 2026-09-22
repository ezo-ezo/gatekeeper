package dev.gatekeeper.api.dto;

import dev.gatekeeper.reconcile.TicketDecision;

import java.util.List;

/** The reconciled state of one ticket: who let it in, and who else tried to. */
public record TicketDecisionView(String ticketId, ScanRecordView accepted, List<ScanRecordView> conflicts) {

    public static TicketDecisionView from(TicketDecision decision) {
        return new TicketDecisionView(
                decision.ticketId(),
                ScanRecordView.from(decision.acceptedEntry()),
                decision.conflictingEntries().stream().map(ScanRecordView::from).toList());
    }
}
