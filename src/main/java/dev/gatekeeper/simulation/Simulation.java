package dev.gatekeeper.simulation;

import dev.gatekeeper.reconcile.ReconciliationReport;
import dev.gatekeeper.scanlog.ScanLog;

import java.util.ArrayList;
import java.util.List;

/**
 * Runs a whole simulated event in-process: gates go offline and scan, then
 * reconnect in an awkward order and upload into a {@link ScanLog}, and the
 * resulting reconciliation is checked, ticket by ticket, against what the
 * gates' own logs say it should be.
 */
public final class Simulation {

    private Simulation() {
    }

    public static SimulationResult run(SimulationConfig config, ScanLog scanLog) {
        SimulatedLogs logs = Workload.generate(config);
        Oracle oracle = Oracle.from(logs.byGate().values());
        List<SyncEvent> uploads = SyncSchedule.plan(logs, config, config.seed() + 1);

        long ingestStart = System.nanoTime();
        for (SyncEvent upload : uploads) {
            scanLog.store(upload.records());
        }
        long ingestMillis = (System.nanoTime() - ingestStart) / 1_000_000;

        long reconcileStart = System.nanoTime();
        ReconciliationReport report = scanLog.reconcile();
        long reconcileMillis = (System.nanoTime() - reconcileStart) / 1_000_000;

        List<String> problems = new ArrayList<>(Verifier.verifyGateLogs(logs));
        problems.addAll(Verifier.verify(report, oracle));

        return new SimulationResult(config, logs, oracle, uploads.size(),
                SyncSchedule.recordsSent(uploads), problems, ingestMillis, reconcileMillis);
    }
}
