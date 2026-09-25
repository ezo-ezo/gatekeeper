package dev.gatekeeper.scanlog;

import dev.gatekeeper.gate.ScanRecord;
import dev.gatekeeper.reconcile.ReconciliationReport;
import dev.gatekeeper.reconcile.Reconciler;

import java.util.Collection;

/**
 * A {@link ScanLog} with nothing behind it but memory: used in tests, and
 * anywhere the durability a real database gives isn't needed. {@link #store}
 * always reports the batch size rather than the exact newly-stored count,
 * since {@link Reconciler#ingest} already de-duplicates internally and
 * distinguishing "new" from "already seen" isn't something this
 * implementation's callers rely on; {@code JpaScanLog} reports it exactly.
 */
public final class InMemoryScanLog implements ScanLog {

    private final Reconciler reconciler = new Reconciler();

    @Override
    public int store(Collection<ScanRecord> scans) {
        reconciler.ingest(scans);
        return scans.size();
    }

    @Override
    public ReconciliationReport reconcile() {
        return reconciler.reconcile();
    }

    @Override
    public long totalStoredScans() {
        return reconciler.reconcile().totalScansConsidered();
    }
}
