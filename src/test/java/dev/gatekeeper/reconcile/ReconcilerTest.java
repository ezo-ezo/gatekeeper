package dev.gatekeeper.reconcile;

import dev.gatekeeper.gate.ScanRecord;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ReconcilerTest {

    private static final Instant T0 = Instant.ofEpochSecond(1_700_000_000);

    private static ScanRecord accepted(String gateId, String ticketId, Instant at) {
        return new ScanRecord(gateId, ticketId, at, true);
    }

    private static ScanRecord rejected(String gateId, String ticketId, Instant at) {
        return new ScanRecord(gateId, ticketId, at, false);
    }

    @Test
    void oneGateOneAcceptIsNotAConflict() {
        Reconciler r = new Reconciler();
        r.ingest(List.of(accepted("gate-1", "t1", T0)));

        ReconciliationReport report = r.reconcile();

        assertThat(report.acceptedTicketCount()).isEqualTo(1);
        assertThat(report.conflicts()).isEmpty();
        TicketDecision decision = report.decisions().get(0);
        assertThat(decision.ticketId()).isEqualTo("t1");
        assertThat(decision.acceptedEntry().gateId()).isEqualTo("gate-1");
        assertThat(decision.hasConflict()).isFalse();
    }

    @Test
    void sameTicketAcceptedAtTwoGatesIsAConflictAndEarliestWins() {
        Reconciler r = new Reconciler();
        r.ingest(List.of(accepted("gate-1", "t1", T0.plusSeconds(10))));
        r.ingest(List.of(accepted("gate-2", "t1", T0))); // earlier, arrives second

        ReconciliationReport report = r.reconcile();

        assertThat(report.conflicts()).hasSize(1);
        TicketDecision decision = report.conflicts().get(0);
        assertThat(decision.acceptedEntry().gateId()).isEqualTo("gate-2"); // earliest wins
        assertThat(decision.conflictingEntries()).extracting(ScanRecord::gateId).containsExactly("gate-1");
        assertThat(decision.gateIds()).containsExactlyInAnyOrder("gate-1", "gate-2");
    }

    @Test
    void reingestingTheSameLogIsANoOp() {
        Reconciler r = new Reconciler();
        List<ScanRecord> log = List.of(accepted("gate-1", "t1", T0), rejected("gate-1", "t2", T0));

        r.ingest(log);
        r.ingest(log); // simulates a retried sync of the exact same log
        r.ingest(log);

        ReconciliationReport report = r.reconcile();
        assertThat(report.totalScansConsidered()).isEqualTo(2); // not 6
        assertThat(report.acceptedTicketCount()).isEqualTo(1);
    }

    @Test
    void ingestionOrderDoesNotAffectTheResult() {
        List<ScanRecord> fromGate1 = List.of(accepted("gate-1", "t1", T0.plusSeconds(5)));
        List<ScanRecord> fromGate2 = List.of(accepted("gate-2", "t1", T0));

        Reconciler inOrder = new Reconciler();
        inOrder.ingest(fromGate1);
        inOrder.ingest(fromGate2);

        Reconciler reversed = new Reconciler();
        reversed.ingest(fromGate2);
        reversed.ingest(fromGate1);

        assertThat(inOrder.reconcile()).isEqualTo(reversed.reconcile());
    }

    @Test
    void reconcileIsPureAndRepeatable() {
        Reconciler r = new Reconciler();
        r.ingest(List.of(accepted("gate-1", "t1", T0), accepted("gate-2", "t1", T0.plusSeconds(1))));

        ReconciliationReport first = r.reconcile();
        ReconciliationReport second = r.reconcile();

        assertThat(first).isEqualTo(second);
    }

    @Test
    void ticketNeverAcceptedAnywhereProducesNoDecision() {
        Reconciler r = new Reconciler();
        r.ingest(List.of(rejected("gate-1", "t1", T0), rejected("gate-2", "t1", T0.plusSeconds(1))));

        ReconciliationReport report = r.reconcile();

        assertThat(report.decisions()).isEmpty();
        assertThat(report.totalScansConsidered()).isEqualTo(2);
    }

    @Test
    void acceptedAtOneGateAndRejectedAtAnotherIsNotAConflict() {
        Reconciler r = new Reconciler();
        r.ingest(List.of(
                accepted("gate-1", "t1", T0),
                rejected("gate-2", "t1", T0.plusSeconds(1)))); // e.g. gate-2 correctly refused the duplicate... but
                                                                // offline, so it still had to try

        ReconciliationReport report = r.reconcile();

        assertThat(report.conflicts()).isEmpty();
        assertThat(report.decisions().get(0).acceptedEntry().gateId()).isEqualTo("gate-1");
    }

    @Test
    void threeGatesAcceptingTheSameTicketProducesTwoConflicts() {
        Reconciler r = new Reconciler();
        r.ingest(List.of(
                accepted("gate-1", "t1", T0.plusSeconds(2)),
                accepted("gate-2", "t1", T0),
                accepted("gate-3", "t1", T0.plusSeconds(1))));

        TicketDecision decision = r.reconcile().decisions().get(0);

        assertThat(decision.acceptedEntry().gateId()).isEqualTo("gate-2");
        assertThat(decision.conflictingEntries()).extracting(ScanRecord::gateId)
                .containsExactly("gate-3", "gate-1"); // ordered by scannedAt
    }

    @Test
    void simultaneousScansAtDifferentGatesBreakTiesDeterministicallyByGateId() {
        List<ScanRecord> fromA = List.of(accepted("gate-b", "t1", T0), accepted("gate-a", "t1", T0));

        Reconciler r1 = new Reconciler();
        r1.ingest(fromA);
        Reconciler r2 = new Reconciler();
        r2.ingest(List.of(fromA.get(1), fromA.get(0))); // reversed ingestion order

        TicketDecision d1 = r1.reconcile().decisions().get(0);
        TicketDecision d2 = r2.reconcile().decisions().get(0);

        assertThat(d1.acceptedEntry().gateId()).isEqualTo("gate-a"); // alphabetically first, deterministic tiebreak
        assertThat(d1).isEqualTo(d2);
    }

    @Test
    void reportOrdersDecisionsByTicketIdRegardlessOfIngestionOrder() {
        Reconciler r = new Reconciler();
        r.ingest(List.of(accepted("gate-1", "zzz", T0), accepted("gate-1", "aaa", T0)));

        List<String> order = r.reconcile().decisions().stream().map(TicketDecision::ticketId).toList();

        assertThat(order).containsExactly("aaa", "zzz");
    }

    @Test
    void ingestIsSafeUnderConcurrentSyncsFromManyGates() throws InterruptedException {
        Reconciler r = new Reconciler();
        int gates = 20;
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(10);

        for (int i = 0; i < gates; i++) {
            String gateId = "gate-" + i;
            pool.submit(() -> r.ingest(List.of(accepted(gateId, "shared-ticket", T0.plusSeconds(1)))));
        }
        pool.shutdown();
        assertThat(pool.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();

        ReconciliationReport report = r.reconcile();
        assertThat(report.totalScansConsidered()).isEqualTo(gates);
        assertThat(report.conflicts()).hasSize(1);
        assertThat(report.conflicts().get(0).conflictingEntries()).hasSize(gates - 1);
    }
}
