package dev.gatekeeper.observability;

import dev.gatekeeper.gate.ScanRecord;
import dev.gatekeeper.reconcile.ReconciliationReport;
import dev.gatekeeper.scanlog.ScanLog;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import java.util.Collection;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Wraps any {@link ScanLog} with metrics, so neither the storage
 * implementations nor {@code Reconciler} need to know metrics exist.
 *
 * <p>What is measured, and why:
 * <ul>
 *   <li>{@code gatekeeper.scans.received} vs {@code gatekeeper.scans.stored}:
 *       received counts every scan in every sync request, stored counts only
 *       the ones that were new. The gap between them is retry and overlap
 *       traffic, which is the number to watch if gates are re-sending too
 *       much.</li>
 *   <li>{@code gatekeeper.store} / {@code gatekeeper.reconcile}: how long
 *       ingesting a batch, and reconciling the whole history, take. Reconciling
 *       reloads everything, so this is the timer that grows with data volume
 *       (see {@code docs/simulation.md}).</li>
 *   <li>{@code gatekeeper.conflicts.last}: tickets accepted at more than one
 *       gate as of the most recent reconciliation. It is a snapshot from
 *       whenever someone last asked, not a live figure: computing it fresh on
 *       every scrape would mean a full reconcile per scrape.</li>
 * </ul>
 *
 * <p>Nothing here is labelled by gate ID or ticket ID. Both come from
 * clients, so labelling by them would let a client create unbounded metric
 * series.
 */
public final class MeteredScanLog implements ScanLog {

    private final ScanLog delegate;

    private final Counter receivedAccepted;
    private final Counter receivedRejected;
    private final Counter stored;
    private final Timer storeTimer;
    private final Timer reconcileTimer;

    // Held as fields: Micrometer gauges only keep a weak reference to their source.
    private final AtomicInteger lastConflictCount = new AtomicInteger();
    private final AtomicInteger lastAcceptedTicketCount = new AtomicInteger();

    public MeteredScanLog(ScanLog delegate, MeterRegistry registry) {
        this.delegate = delegate;

        receivedAccepted = Counter.builder("gatekeeper.scans.received")
                .description("Scans received in sync requests, including ones already stored")
                .tag("outcome", "accepted")
                .register(registry);
        receivedRejected = Counter.builder("gatekeeper.scans.received")
                .description("Scans received in sync requests, including ones already stored")
                .tag("outcome", "rejected")
                .register(registry);
        stored = Counter.builder("gatekeeper.scans.stored")
                .description("Scans newly stored (received minus already-known duplicates)")
                .register(registry);

        storeTimer = Timer.builder("gatekeeper.store")
                .description("Time to store one sync batch")
                .publishPercentileHistogram()
                .register(registry);
        reconcileTimer = Timer.builder("gatekeeper.reconcile")
                .description("Time to reconcile the full scan history")
                .publishPercentileHistogram()
                .register(registry);

        Gauge.builder("gatekeeper.conflicts.last", lastConflictCount, AtomicInteger::get)
                .description("Tickets accepted at more than one gate, as of the most recent reconciliation")
                .register(registry);
        Gauge.builder("gatekeeper.accepted.tickets.last", lastAcceptedTicketCount, AtomicInteger::get)
                .description("Tickets accepted at any gate, as of the most recent reconciliation")
                .register(registry);
        // Not "...stored.total": Prometheus appends _total to the counter above, so that
        // name would collide with it.
        Gauge.builder("gatekeeper.scans.held", delegate, ScanLog::totalStoredScans)
                .description("Scans currently held, across all gates")
                .register(registry);
    }

    @Override
    public int store(Collection<ScanRecord> scans) {
        return storeTimer.record(() -> {
            for (ScanRecord scan : scans) {
                (scan.accepted() ? receivedAccepted : receivedRejected).increment();
            }
            int newlyStored = delegate.store(scans);
            stored.increment(newlyStored);
            return newlyStored;
        });
    }

    @Override
    public ReconciliationReport reconcile() {
        ReconciliationReport report = reconcileTimer.record(delegate::reconcile);
        lastConflictCount.set(report.conflicts().size());
        lastAcceptedTicketCount.set(report.acceptedTicketCount());
        return report;
    }

    @Override
    public long totalStoredScans() {
        return delegate.totalStoredScans();
    }
}
