package dev.gatekeeper.api;

import dev.gatekeeper.api.dto.ReconciliationView;
import dev.gatekeeper.api.dto.TicketDecisionView;
import dev.gatekeeper.reconcile.Reconciler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Read-only views onto the current reconciliation state, for staff review. */
@RestController
public class ReconciliationController {

    private final Reconciler reconciler;

    public ReconciliationController(Reconciler reconciler) {
        this.reconciler = reconciler;
    }

    @GetMapping("/reconciliation")
    public ReconciliationView report() {
        return ReconciliationView.from(reconciler.reconcile());
    }

    @GetMapping("/reconciliation/conflicts")
    public List<TicketDecisionView> conflicts() {
        return report().decisions().stream().filter(d -> !d.conflicts().isEmpty()).toList();
    }
}
