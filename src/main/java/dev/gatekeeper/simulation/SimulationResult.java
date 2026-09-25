package dev.gatekeeper.simulation;

import java.util.List;
import java.util.Locale;

/**
 * The outcome of one simulated event: what happened at the gates, what
 * reconciliation was expected to produce, and whether it did.
 *
 * @param problems anything that disagreed with the oracle; empty means the run passed
 */
public record SimulationResult(
        SimulationConfig config,
        SimulatedLogs logs,
        Oracle oracle,
        int uploads,
        long recordsSent,
        List<String> problems,
        long ingestMillis,
        long reconcileMillis) {

    public SimulationResult {
        problems = List.copyOf(problems);
    }

    public boolean passed() {
        return problems.isEmpty();
    }

    /** What happened at the gates and what the answer should be. */
    public String describeWorkload() {
        return String.format(Locale.ROOT, """
                        Workload
                          scans attempted        %,d
                            accepted             %,d
                            rejected: duplicate  %,d  (same gate, same ticket again)
                            rejected: bad code   %,d  (stale or wrong)
                            rejected: forged     %,d  (ticket never issued)
                        Ground truth, from the gates' own logs
                          tickets accepted       %,d
                          accepted at 2+ gates   %,d  (these are the conflicts)
                          distinct scans         %,d
                        Uploads
                          uploads                %,d
                          scan records sent      %,d  (%.1fx the distinct scans, from overlap and retries)""",
                logs.presentations(),
                logs.accepted(), logs.rejectedDuplicate(), logs.rejectedInvalidCode(), logs.rejectedUnknownTicket(),
                oracle.acceptedTicketCount(), oracle.conflictCount(), oracle.distinctRecords(),
                uploads, recordsSent,
                oracle.distinctRecords() == 0 ? 0.0 : (double) recordsSent / oracle.distinctRecords());
    }
}
