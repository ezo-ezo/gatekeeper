package dev.gatekeeper.scanlog;

import dev.gatekeeper.gate.ScanRecord;
import dev.gatekeeper.reconcile.ReconciliationReport;

import java.util.Collection;

/**
 * Where synced scans live, and where reconciliation is computed from. Two
 * implementations exist: {@link InMemoryScanLog}, a thin wrapper over
 * {@link dev.gatekeeper.reconcile.Reconciler} used in tests and anywhere
 * persistence isn't needed, and {@code dev.gatekeeper.persistence.JpaScanLog},
 * backed by a real database and used by the running application. Both give
 * the same guarantees: storing an already-seen scan is a no-op, and
 * {@link #reconcile()} is a pure function of everything stored so far.
 */
public interface ScanLog {

    /** Stores scans not already present; returns how many were newly stored. */
    int store(Collection<ScanRecord> scans);

    /** Reconciles the full history stored so far. */
    ReconciliationReport reconcile();

    /** How many scans are currently stored, across all gates. */
    long totalStoredScans();
}
