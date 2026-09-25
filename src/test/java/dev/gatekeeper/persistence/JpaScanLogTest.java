package dev.gatekeeper.persistence;

import dev.gatekeeper.gate.ScanRecord;
import dev.gatekeeper.reconcile.ReconciliationReport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code @DataJpaTest} boots just the JPA slice (a real H2 database, per the
 * test-scope {@code application.yml}, not a mock) and wraps every test
 * method in a transaction rolled back afterward. That gives per-test
 * isolation the same way {@link dev.gatekeeper.api.SyncControllerTest}'s
 * {@code standaloneSetup} does for the HTTP layer, but by the framework's
 * own supported mechanism rather than by avoiding Spring entirely — the
 * right tool differs depending on what's actually being tested.
 */
@DataJpaTest
class JpaScanLogTest {

    private static final Instant T0 = Instant.ofEpochSecond(1_700_000_000);

    @Autowired
    private ScanRecordRepository repository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private JpaScanLog scanLog;

    @BeforeEach
    void setUp() {
        scanLog = new JpaScanLog(repository, transactionManager, new SimpleMeterRegistry());
    }

    @Test
    void storedScansAreVisibleThroughAFreshInstanceOverTheSameRepository() {
        scanLog.store(List.of(new ScanRecord("gate-1", "t1", T0, true)));

        // A new JpaScanLog over the same repository, standing in for "the app
        // restarted, the repository is backed by the same database, is the
        // data still there".
        JpaScanLog reloaded = new JpaScanLog(repository, transactionManager, new SimpleMeterRegistry());
        ReconciliationReport report = reloaded.reconcile();

        assertThat(report.totalScansConsidered()).isEqualTo(1);
        assertThat(report.decisions()).hasSize(1);
        assertThat(report.decisions().get(0).acceptedEntry().gateId()).isEqualTo("gate-1");
    }

    @Test
    void storingAnAlreadyStoredScanIsANoOpAndIsReportedAsSuch() {
        ScanRecord scan = new ScanRecord("gate-1", "t1", T0, true);

        int firstStore = scanLog.store(List.of(scan));
        int secondStore = scanLog.store(List.of(scan));

        assertThat(firstStore).isEqualTo(1);
        assertThat(secondStore).isEqualTo(0); // unlike InMemoryScanLog, this counts exactly
        assertThat(scanLog.totalStoredScans()).isEqualTo(1);
    }

    @Test
    void aBatchCanMixNewAndAlreadyStoredScans() {
        ScanRecord known = new ScanRecord("gate-1", "t1", T0, true);
        scanLog.store(List.of(known));

        ScanRecord fresh = new ScanRecord("gate-1", "t2", T0, true);
        int stored = scanLog.store(List.of(known, fresh));

        assertThat(stored).isEqualTo(1);
        assertThat(scanLog.totalStoredScans()).isEqualTo(2);
    }

    @Test
    void aScanListedTwiceInOneBatchIsStoredOnce() {
        ScanRecord scan = new ScanRecord("gate-1", "t1", T0, true);

        int stored = scanLog.store(List.of(scan, scan));

        assertThat(stored).isEqualTo(1);
        assertThat(scanLog.totalStoredScans()).isEqualTo(1);
    }

    @Test
    void aBatchMayCarryScansFromSeveralGates() {
        List<ScanRecord> mixed = List.of(
                new ScanRecord("gate-1", "t1", T0, true),
                new ScanRecord("gate-2", "t1", T0.plusSeconds(4), true),
                new ScanRecord("gate-3", "t2", T0, false));

        assertThat(scanLog.store(mixed)).isEqualTo(3);
        assertThat(scanLog.store(mixed)).isZero();
        assertThat(scanLog.reconcile().conflicts()).hasSize(1); // t1 was accepted at two gates
    }

    @Test
    void theSameTicketAtTheSameGateAtDifferentTimesOrOutcomesAreDistinctScans() {
        // The lookup is by gate and ticket, so it can return a scan that is NOT the one being stored.
        // It must not be mistaken for it.
        scanLog.store(List.of(new ScanRecord("gate-1", "t1", T0, false)));

        int stored = scanLog.store(List.of(
                new ScanRecord("gate-1", "t1", T0, true),                    // same time, other outcome
                new ScanRecord("gate-1", "t1", T0.plusSeconds(30), false))); // same outcome, other time

        assertThat(stored).isEqualTo(2);
        assertThat(scanLog.totalStoredScans()).isEqualTo(3);
    }

    @Test
    void batchesLargerThanOneLookupChunkAreHandledWholeAndRecognisedWhenResent() {
        List<ScanRecord> big = new java.util.ArrayList<>();
        for (int i = 0; i < 1_300; i++) { // more than two lookup chunks
            big.add(new ScanRecord("gate-1", "ticket-" + i, T0, true));
        }

        assertThat(scanLog.store(big)).isEqualTo(1_300);
        assertThat(scanLog.store(big)).isZero();
        assertThat(scanLog.totalStoredScans()).isEqualTo(1_300);
    }

    @Test
    void aBatchOfNothingStoresNothing() {
        assertThat(scanLog.store(List.of())).isZero();
        assertThat(scanLog.totalStoredScans()).isZero();
    }

    @Test
    void reconciliationAcrossGatesWorksFromPersistedData() {
        scanLog.store(List.of(new ScanRecord("gate-1", "t1", T0.plusSeconds(5), true)));
        scanLog.store(List.of(new ScanRecord("gate-2", "t1", T0, true))); // earlier

        ReconciliationReport report = scanLog.reconcile();

        assertThat(report.conflicts()).hasSize(1);
        assertThat(report.conflicts().get(0).acceptedEntry().gateId()).isEqualTo("gate-2");
    }

    @Test
    void totalStoredScansCountsAllRowsRegardlessOfOutcome() {
        scanLog.store(List.of(
                new ScanRecord("gate-1", "t1", T0, true),
                new ScanRecord("gate-1", "t2", T0, false)));

        assertThat(scanLog.totalStoredScans()).isEqualTo(2);
    }

    /**
     * Real clocks are finer than the database column: Windows gives 100ns and
     * Linux 1ns, while the column keeps microseconds. If the stored value were
     * truncated, an exact resend of the same scan would no longer match it,
     * and "re-syncing is a no-op" would quietly stop being true.
     */
    @Test
    void aScanTimestampedFinerThanTheDatabasePrecisionIsStillRecognisedWhenResent() {
        ScanRecord scan = new ScanRecord("gate-1", "t1", Instant.parse("2026-09-26T18:00:00.123456789Z"), true);

        int first = scanLog.store(List.of(scan));
        int resent = scanLog.store(List.of(scan));

        assertThat(first).isEqualTo(1);
        assertThat(resent).isZero();
        assertThat(scanLog.totalStoredScans()).isEqualTo(1);
    }

    @Test
    void eachTestStartsWithAnEmptyDatabase() {
        // Proves the per-test transactional rollback actually works: if the
        // previous tests' inserts had leaked into this one, this would fail.
        assertThat(scanLog.totalStoredScans()).isZero();
        assertThat(scanLog.reconcile().decisions()).isEmpty();
    }
}
