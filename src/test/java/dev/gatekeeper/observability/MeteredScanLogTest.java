package dev.gatekeeper.observability;

import dev.gatekeeper.gate.ScanRecord;
import dev.gatekeeper.reconcile.ReconciliationReport;
import dev.gatekeeper.scanlog.InMemoryScanLog;
import dev.gatekeeper.scanlog.ScanLog;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class MeteredScanLogTest {

    private static final Instant T0 = Instant.ofEpochSecond(1_700_000_000);

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

    /** A delegate that reports fixed answers, so tests control exactly what the wrapper sees. */
    private static final class StubScanLog implements ScanLog {
        int newlyStored;
        long held;

        @Override
        public int store(Collection<ScanRecord> scans) {
            return newlyStored;
        }

        @Override
        public ReconciliationReport reconcile() {
            return new ReconciliationReport(List.of(), 0);
        }

        @Override
        public long totalStoredScans() {
            return held;
        }
    }

    private double counter(String name, String... tags) {
        return registry.get(name).tags(tags).counter().count();
    }

    @Test
    void receivedCountsEveryScanButStoredCountsOnlyTheNewOnes() {
        StubScanLog stub = new StubScanLog();
        stub.newlyStored = 1; // of the three below, only one was new
        MeteredScanLog log = new MeteredScanLog(stub, registry);

        int result = log.store(List.of(
                new ScanRecord("gate-1", "t1", T0, true),
                new ScanRecord("gate-1", "t2", T0, true),
                new ScanRecord("gate-1", "t3", T0, false)));

        assertThat(result).isEqualTo(1); // the delegate's answer passes straight through
        assertThat(counter("gatekeeper.scans.received", "outcome", "accepted")).isEqualTo(2);
        assertThat(counter("gatekeeper.scans.received", "outcome", "rejected")).isEqualTo(1);
        assertThat(counter("gatekeeper.scans.stored")).isEqualTo(1);
        assertThat(registry.get("gatekeeper.store").timer().count()).isEqualTo(1);
    }

    @Test
    void countersAccumulateAcrossCalls() {
        StubScanLog stub = new StubScanLog();
        MeteredScanLog log = new MeteredScanLog(stub, registry);

        stub.newlyStored = 2;
        log.store(List.of(new ScanRecord("gate-1", "t1", T0, true), new ScanRecord("gate-1", "t2", T0, true)));
        stub.newlyStored = 0; // an exact retry: nothing new
        log.store(List.of(new ScanRecord("gate-1", "t1", T0, true), new ScanRecord("gate-1", "t2", T0, true)));

        assertThat(counter("gatekeeper.scans.received", "outcome", "accepted")).isEqualTo(4);
        assertThat(counter("gatekeeper.scans.stored")).isEqualTo(2); // the retry added nothing
    }

    @Test
    void reconcileRecordsItsDurationAndASnapshotOfTheResult() {
        InMemoryScanLog real = new InMemoryScanLog();
        MeteredScanLog log = new MeteredScanLog(real, registry);
        log.store(List.of(
                new ScanRecord("gate-1", "t1", T0, true),
                new ScanRecord("gate-2", "t1", T0.plusSeconds(3), true), // t1 accepted at two gates: a conflict
                new ScanRecord("gate-1", "t2", T0, true)));

        assertThat(registry.get("gatekeeper.conflicts.last").gauge().value()).isZero(); // nothing reconciled yet

        ReconciliationReport report = log.reconcile();

        assertThat(report.conflicts()).hasSize(1);
        assertThat(registry.get("gatekeeper.conflicts.last").gauge().value()).isEqualTo(1);
        assertThat(registry.get("gatekeeper.accepted.tickets.last").gauge().value()).isEqualTo(2);
        assertThat(registry.get("gatekeeper.reconcile").timer().count()).isEqualTo(1);
    }

    @Test
    void heldGaugeReadsTheDelegateAtScrapeTime() {
        StubScanLog stub = new StubScanLog();
        new MeteredScanLog(stub, registry);

        stub.held = 7;
        assertThat(registry.get("gatekeeper.scans.held").gauge().value()).isEqualTo(7);
        stub.held = 9;
        assertThat(registry.get("gatekeeper.scans.held").gauge().value()).isEqualTo(9);
    }

    @Test
    void totalStoredScansPassesThrough() {
        StubScanLog stub = new StubScanLog();
        stub.held = 42;
        assertThat(new MeteredScanLog(stub, registry).totalStoredScans()).isEqualTo(42);
    }

    /**
     * Gate and ticket IDs come from clients. Using either as a metric label
     * would let a client mint unbounded time series, so no meter may carry one.
     */
    @Test
    void noMeterIsLabelledByAnythingAClientControls() {
        new MeteredScanLog(new StubScanLog(), registry);

        for (Meter meter : registry.getMeters()) {
            Set<String> tagKeys = meter.getId().getTags().stream()
                    .map(tag -> tag.getKey())
                    .collect(java.util.stream.Collectors.toSet());
            assertThat(tagKeys)
                    .as("tags on %s", meter.getId().getName())
                    .isSubsetOf("outcome");
        }
    }
}
