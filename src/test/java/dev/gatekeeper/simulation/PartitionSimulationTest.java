package dev.gatekeeper.simulation;

import dev.gatekeeper.gate.ScanRecord;
import dev.gatekeeper.reconcile.ReconciliationReport;
import dev.gatekeeper.reconcile.TicketDecision;
import dev.gatekeeper.scanlog.InMemoryScanLog;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PartitionSimulationTest {

    private static SimulationConfig config(long seed) {
        return SimulationConfig.defaults().withTickets(3_000).withSeed(seed);
    }

    private static ReconciliationReport reconcileAfter(List<SyncEvent> uploads) {
        InMemoryScanLog log = new InMemoryScanLog();
        uploads.forEach(upload -> log.store(upload.records()));
        return log.reconcile();
    }

    private static Set<String> ticketIds(List<TicketDecision> decisions) {
        return decisions.stream().map(TicketDecision::ticketId).collect(Collectors.toSet());
    }

    // ---------------------------------------------------------------- the main property

    @ParameterizedTest
    @ValueSource(longs = {1, 2, 3, 7, 42, 2026})
    void reconciliationMatchesWhatTheGatesActuallyRecordedWhateverTheUploadOrder(long seed) {
        SimulationResult result = Simulation.run(config(seed), new InMemoryScanLog());

        assertThat(result.problems()).isEmpty();

        // Guard against a vacuous pass: the run must actually have exercised every case.
        assertThat(result.oracle().conflictCount()).as("tickets accepted at 2+ gates").isPositive();
        assertThat(result.logs().rejectedDuplicate()).as("same-gate duplicates").isPositive();
        assertThat(result.logs().rejectedInvalidCode()).as("stale codes").isPositive();
        assertThat(result.logs().rejectedUnknownTicket()).as("forged tickets").isPositive();
        assertThat(result.recordsSent()).as("overlapping and repeated uploads")
                .isGreaterThan(result.oracle().distinctRecords());
    }

    @Test
    void exactTiesBetweenGatesAreBrokenTheSameWayEveryTime() {
        // Gates that agree on the time, and copies of a ticket shown within a couple of seconds
        // of each other, so that several are accepted at the very same instant at different gates.
        SimulationConfig tied = config(5)
                .withMaxClockSkew(Duration.ZERO)
                .withSharedGap(Duration.ofSeconds(2))
                .withSharedTicketRate(0.5);

        SimulationResult result = Simulation.run(tied, new InMemoryScanLog());

        assertThat(result.problems()).isEmpty();
        long exactTies = result.oracle().acceptedTickets().values().stream()
                .filter(e -> e.losers().stream().anyMatch(l -> l.scannedAt().equals(e.winner().scannedAt())))
                .count();
        assertThat(exactTies).as("conflicts decided purely by the gate-id tiebreak").isPositive();
    }

    // ---------------------------------------------------------------- order independence

    @Test
    void theOrderUploadsArriveInDoesNotChangeTheResult() {
        SimulationConfig config = config(9);
        SimulatedLogs logs = Workload.generate(config);

        ReconciliationReport oneSchedule = reconcileAfter(SyncSchedule.plan(logs, config, 100));
        ReconciliationReport anotherSchedule = reconcileAfter(SyncSchedule.plan(logs, config, 200));
        List<SyncEvent> wholeLogsInGateOrder = logs.byGate().entrySet().stream()
                .map(e -> new SyncEvent(e.getKey(), e.getValue())).toList();
        ReconciliationReport simplest = reconcileAfter(wholeLogsInGateOrder);

        assertThat(oneSchedule).isEqualTo(anotherSchedule).isEqualTo(simplest);
    }

    @Test
    void whileUploadsArriveNothingAlreadyKnownIsEverTakenBack() {
        SimulationConfig config = config(13).withTickets(1_500);
        SimulatedLogs logs = Workload.generate(config);
        List<SyncEvent> uploads = SyncSchedule.plan(logs, config, 77);

        InMemoryScanLog log = new InMemoryScanLog();
        Set<String> accepted = new HashSet<>();
        Set<String> conflicted = new HashSet<>();
        int scans = 0;
        ReconciliationReport last = null;

        for (SyncEvent upload : uploads) {
            log.store(upload.records());
            last = log.reconcile();

            // Tickets only ever join these sets: more data can add a conflict, never remove one.
            assertThat(ticketIds(last.decisions())).containsAll(accepted);
            assertThat(ticketIds(last.conflicts())).containsAll(conflicted);
            assertThat(last.totalScansConsidered()).isGreaterThanOrEqualTo(scans);

            accepted = ticketIds(last.decisions());
            conflicted = ticketIds(last.conflicts());
            scans = last.totalScansConsidered();
        }

        // ...and once everything has arrived, it is exactly the right answer.
        assertThat(Verifier.verify(last, Oracle.from(logs.byGate().values()))).isEmpty();
    }

    /**
     * The other side of the previous test, and the caveat that goes with it:
     * the set of conflicts only grows, but WHICH gate wins a given conflict can
     * change while uploads are still arriving, because a scan that happened
     * earlier may reach the server later. Nobody should act on a winner until
     * every gate has synced. This test makes that concrete instead of leaving it as a warning.
     */
    @Test
    void aConflictsWinnerCanChangeUntilEveryGateHasSynced() {
        SimulationConfig config = config(13).withTickets(1_500);
        SimulatedLogs logs = Workload.generate(config);
        List<SyncEvent> uploads = SyncSchedule.plan(logs, config, 77);

        InMemoryScanLog log = new InMemoryScanLog();
        Map<String, String> winnerGate = new HashMap<>();
        Set<String> tickets = new HashSet<>();
        int changes = 0;

        for (SyncEvent upload : uploads) {
            log.store(upload.records());
            for (TicketDecision decision : log.reconcile().decisions()) {
                String gate = decision.acceptedEntry().gateId();
                String before = winnerGate.put(decision.ticketId(), gate);
                if (before != null && !before.equals(gate)) {
                    changes++;
                    tickets.add(decision.ticketId());
                }
            }
        }

        assertThat(changes).as("winner changes seen while uploads were arriving").isPositive();
        assertThat(tickets).isNotEmpty();
    }

    // ---------------------------------------------------------------- reproducibility

    @Test
    void theSameSeedReplaysIdentically() {
        SimulationConfig config = config(21);

        SimulatedLogs first = Workload.generate(config);
        SimulatedLogs second = Workload.generate(config);

        assertThat(first.byGate()).isEqualTo(second.byGate());
        assertThat(SyncSchedule.plan(first, config, 5)).isEqualTo(SyncSchedule.plan(second, config, 5));
    }

    @Test
    void differentSeedsProduceDifferentEvents() {
        assertThat(Workload.generate(config(1)).byGate()).isNotEqualTo(Workload.generate(config(2)).byGate());
    }

    // ---------------------------------------------------------------- the verifier can fail

    @Test
    void theVerifierNoticesWhenAScanNeverReachesTheServer() {
        SimulationConfig config = config(3);
        SimulatedLogs logs = Workload.generate(config);
        Oracle oracle = Oracle.from(logs.byGate().values());
        ScanRecord lost = oracle.acceptedTickets().values().stream()
                .filter(Oracle.Expected::conflict).findFirst().orElseThrow().winner();

        InMemoryScanLog log = new InMemoryScanLog();
        for (SyncEvent upload : SyncSchedule.plan(logs, config, 1)) {
            log.store(upload.records().stream().filter(record -> !record.equals(lost)).toList());
        }

        assertThat(Verifier.verify(log.reconcile(), oracle))
                .isNotEmpty()
                .anyMatch(problem -> problem.contains(lost.ticketId()));
    }

    @Test
    void theVerifierNoticesTotalsThatDisagree() {
        Oracle oracle = Oracle.from(Workload.generate(config(3)).byGate().values());

        assertThat(Verifier.verifyCounts(oracle.distinctRecords(), oracle.acceptedTicketCount(), oracle.conflictCount(), oracle))
                .isEmpty();
        assertThat(Verifier.verifyCounts(oracle.distinctRecords() + 1, oracle.acceptedTicketCount(), oracle.conflictCount(), oracle))
                .hasSize(1);
        assertThat(Verifier.verifyCounts(0, 0, 0, oracle)).hasSize(3);
    }

    @Test
    void theGateLogCheckCatchesAGateThatAcceptedATicketTwice() {
        Instant t = Instant.parse("2026-09-26T18:00:00Z");
        SimulatedLogs corrupt = new SimulatedLogs(
                Map.of("gate-01", List.of(
                        new ScanRecord("gate-01", "t1", t, true),
                        new ScanRecord("gate-01", "t1", t.plusSeconds(5), true))),
                2, 0, 0, 0);

        assertThat(Verifier.verifyGateLogs(corrupt)).anyMatch(p -> p.contains("accepted ticket t1 twice"));
    }

    @Test
    void theGateLogCheckCatchesALogThatGoesBackwardsInTime() {
        Instant t = Instant.parse("2026-09-26T18:00:00Z");
        SimulatedLogs corrupt = new SimulatedLogs(
                Map.of("gate-01", List.of(
                        new ScanRecord("gate-01", "t1", t.plusSeconds(10), true),
                        new ScanRecord("gate-01", "t2", t, true))),
                2, 0, 0, 0);

        assertThat(Verifier.verifyGateLogs(corrupt)).anyMatch(p -> p.contains("backwards in time"));
    }

    @Test
    void aFailingRunReportsAReadableNumberOfProblemsNotThousands() {
        Oracle oracle = Oracle.from(Workload.generate(config(3)).byGate().values());

        List<String> problems = Verifier.verify(new ReconciliationReport(List.of(), 0), oracle);

        assertThat(problems.size()).isLessThanOrEqualTo(26); // 25 shown plus one "... and N more"
        assertThat(problems.get(problems.size() - 1)).contains("more");
    }

    // ---------------------------------------------------------------- edges

    @Test
    void aSingleGateCanNeverProduceAConflict() {
        SimulationResult result = Simulation.run(config(4).withGates(1), new InMemoryScanLog());

        assertThat(result.problems()).isEmpty();
        assertThat(result.oracle().conflictCount()).isZero();
        assertThat(result.oracle().acceptedTicketCount()).isPositive();
    }

    @Test
    void aQuietEventWithNoScansAtAllPasses() {
        SimulationConfig quiet = new SimulationConfig(4, 100, 0, 0, 0, 0, 0,
                Duration.ofHours(1), Duration.ofMinutes(5), Duration.ZERO, 2, 0, 1);

        SimulationResult result = Simulation.run(quiet, new InMemoryScanLog());

        assertThat(result.problems()).isEmpty();
        assertThat(result.logs().presentations()).isZero();
        assertThat(result.oracle().acceptedTicketCount()).isZero();
    }

    /**
     * Not a reconciliation problem, but worth pinning down: a gate whose clock is
     * wrong by more than the code tolerance rejects genuine tickets. The system
     * stays consistent (the run passes); the venue just loses throughput.
     */
    @Test
    void gatesWithClocksFarOutRejectGenuineTicketsButStayConsistent() {
        SimulationResult healthy = Simulation.run(config(6), new InMemoryScanLog());
        SimulationResult drifted = Simulation.run(config(6).withMaxClockSkew(Duration.ofMinutes(5)), new InMemoryScanLog());

        assertThat(drifted.problems()).isEmpty();
        assertThat(drifted.logs().rejectedInvalidCode()).isGreaterThan(healthy.logs().rejectedInvalidCode() * 5);
        assertThat(drifted.logs().accepted()).isLessThan(healthy.logs().accepted());
    }

    @Test
    void configRejectsNonsense() {
        SimulationConfig base = SimulationConfig.defaults();
        assertThatThrownBy(() -> base.withGates(0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> base.withTickets(0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> base.withSharedTicketRate(1.5)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> base.withSharedTicketRate(-0.1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> base.withMaxSyncsPerGate(0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> base.withMaxClockSkew(Duration.ofSeconds(-1))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> base.withOfflineWindow(Duration.ZERO)).isInstanceOf(IllegalArgumentException.class);
    }
}
