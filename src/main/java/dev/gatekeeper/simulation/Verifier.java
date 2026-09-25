package dev.gatekeeper.simulation;

import dev.gatekeeper.gate.ScanRecord;
import dev.gatekeeper.reconcile.ReconciliationReport;
import dev.gatekeeper.reconcile.TicketDecision;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Compares what reconciliation produced against the {@link Oracle}, and
 * sanity-checks the gates' own logs. Each method returns the problems found;
 * an empty list means everything agreed. Problems are capped so a systematic
 * failure produces a readable report instead of thousands of lines.
 */
public final class Verifier {

    private static final int MAX_PROBLEMS = 25;

    private Verifier() {
    }

    /** Full, per-ticket comparison of a report against the oracle. */
    public static List<String> verify(ReconciliationReport report, Oracle oracle) {
        Problems problems = new Problems();

        if (report.totalScansConsidered() != oracle.distinctRecords()) {
            problems.add("scans considered: got %d, expected %d"
                    .formatted(report.totalScansConsidered(), oracle.distinctRecords()));
        }
        if (report.decisions().size() != oracle.acceptedTicketCount()) {
            problems.add("tickets accepted: got %d decisions, expected %d"
                    .formatted(report.decisions().size(), oracle.acceptedTicketCount()));
        }
        if (report.conflicts().size() != oracle.conflictCount()) {
            problems.add("conflicts: got %d, expected %d"
                    .formatted(report.conflicts().size(), oracle.conflictCount()));
        }

        Map<String, TicketDecision> byTicket = new HashMap<>();
        for (TicketDecision decision : report.decisions()) {
            if (byTicket.put(decision.ticketId(), decision) != null) {
                problems.add("ticket %s has more than one decision".formatted(decision.ticketId()));
            }
        }

        oracle.acceptedTickets().forEach((ticketId, expected) -> {
            TicketDecision actual = byTicket.get(ticketId);
            if (actual == null) {
                problems.add("ticket %s was accepted somewhere but has no decision".formatted(ticketId));
                return;
            }
            if (!actual.acceptedEntry().equals(expected.winner())) {
                problems.add("ticket %s: winner is %s, expected %s"
                        .formatted(ticketId, describe(actual.acceptedEntry()), describe(expected.winner())));
            }
            if (!actual.conflictingEntries().equals(expected.losers())) {
                problems.add("ticket %s: conflicts are %s, expected %s"
                        .formatted(ticketId, actual.conflictingEntries().size(), expected.losers().size()));
            }
        });

        Set<String> expectedTickets = oracle.acceptedTickets().keySet();
        for (String ticketId : byTicket.keySet()) {
            if (!expectedTickets.contains(ticketId)) {
                problems.add("ticket %s has a decision but was never accepted anywhere".formatted(ticketId));
            }
        }
        return problems.list();
    }

    /**
     * The comparison available when only totals can be read back, as when
     * driving a running server over HTTP. Weaker than {@link #verify}: equal
     * totals cannot rule out two errors that cancel out.
     */
    public static List<String> verifyCounts(int scansConsidered, int acceptedTickets, int conflicts, Oracle oracle) {
        Problems problems = new Problems();
        if (scansConsidered != oracle.distinctRecords()) {
            problems.add("scans considered: got %d, expected %d".formatted(scansConsidered, oracle.distinctRecords()));
        }
        if (acceptedTickets != oracle.acceptedTicketCount()) {
            problems.add("tickets accepted: got %d, expected %d".formatted(acceptedTickets, oracle.acceptedTicketCount()));
        }
        if (conflicts != oracle.conflictCount()) {
            problems.add("conflicts: got %d, expected %d".formatted(conflicts, oracle.conflictCount()));
        }
        return problems.list();
    }

    /**
     * Sanity checks on the gates themselves, independent of any server: a gate
     * must never accept the same ticket twice, and its log must be in the
     * order things happened.
     */
    public static List<String> verifyGateLogs(SimulatedLogs logs) {
        Problems problems = new Problems();
        logs.byGate().forEach((gateId, log) -> {
            Set<String> acceptedHere = new HashSet<>();
            ScanRecord previous = null;
            for (ScanRecord record : log) {
                if (!record.gateId().equals(gateId)) {
                    problems.add("%s's log contains a record for %s".formatted(gateId, record.gateId()));
                }
                if (record.accepted() && !acceptedHere.add(record.ticketId())) {
                    problems.add("%s accepted ticket %s twice".formatted(gateId, record.ticketId()));
                }
                if (previous != null && record.scannedAt().isBefore(previous.scannedAt())) {
                    problems.add("%s's log goes backwards in time at ticket %s".formatted(gateId, record.ticketId()));
                }
                previous = record;
            }
        });
        return problems.list();
    }

    private static String describe(ScanRecord record) {
        return "%s@%s".formatted(record.gateId(), record.scannedAt());
    }

    /** Collects problems, keeping the first few and counting the rest. */
    private static final class Problems {
        private final List<String> shown = new ArrayList<>();
        private int total;

        void add(String problem) {
            total++;
            if (shown.size() < MAX_PROBLEMS) {
                shown.add(problem);
            }
        }

        List<String> list() {
            if (total > shown.size()) {
                List<String> withTail = new ArrayList<>(shown);
                withTail.add("... and %d more".formatted(total - shown.size()));
                return withTail;
            }
            return shown;
        }
    }
}
