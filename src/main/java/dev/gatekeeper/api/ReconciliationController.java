package dev.gatekeeper.api;

import dev.gatekeeper.api.dto.ReconciliationSummary;
import dev.gatekeeper.api.dto.ReconciliationView;
import dev.gatekeeper.api.dto.TicketDecisionView;
import dev.gatekeeper.scanlog.ScanLog;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Read-only views onto the current reconciliation state, for staff review. */
@RestController
public class ReconciliationController {

    private final ScanLog scanLog;

    public ReconciliationController(ScanLog scanLog) {
        this.scanLog = scanLog;
    }

    @GetMapping("/reconciliation")
    public ReconciliationView report() {
        return ReconciliationView.from(scanLog.reconcile());
    }

    /** Totals only. Still reconciles the whole history, but returns a few bytes instead of every decision. */
    @GetMapping("/reconciliation/summary")
    public ReconciliationSummary summary() {
        return ReconciliationSummary.from(scanLog.reconcile());
    }

    @GetMapping("/reconciliation/conflicts")
    public List<TicketDecisionView> conflicts() {
        return report().decisions().stream().filter(d -> !d.conflicts().isEmpty()).toList();
    }
}
