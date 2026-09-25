package dev.gatekeeper.simulation;

import dev.gatekeeper.scanlog.InMemoryScanLog;

import java.io.IOException;
import java.io.PrintStream;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Command-line entry point for the partition simulation.
 *
 * <p>Without {@code --url} it runs entirely in-process and checks every
 * ticket's reconciliation against the gates' own logs. With {@code --url} it
 * drives a running server over HTTP with concurrent, retrying gates, reports
 * request latency, and checks the server's totals against the same ground
 * truth.
 *
 * <pre>
 * java -cp target/classes dev.gatekeeper.simulation.SimulationCli --tickets 20000
 * java -cp target/classes dev.gatekeeper.simulation.SimulationCli --url http://localhost:8080 --concurrency 4
 * </pre>
 *
 * Exit status: 0 the run passed, 1 it ran but the result was wrong, 2 bad
 * usage or the server could not be reached.
 */
public final class SimulationCli {

    static final String USAGE = """
            Usage: SimulationCli [options]

            Workload
              --gates N            entrances, each with its own clock            (default 12)
              --tickets N          tickets issued                                (default 5000)
              --shared-rate R      share of attendees whose ticket also reaches
                                   other gates while offline, 0..1               (default 0.06)
              --skew-seconds S     each gate's clock is off by up to +/- S       (default 20)
              --offline-minutes M  how long the gates are cut off                (default 120)
              --max-syncs N        each gate uploads in 1..N overlapping batches (default 3)
              --dup-sync-rate R    chance an upload is sent twice, 0..1          (default 0.15)
              --seed N             makes the run reproducible                    (default 42)

            Target
              --url URL            drive a running server over HTTP instead of in-process
              --concurrency N      simultaneous uploads (HTTP only)              (default 4)
              --batch N            most scans per request, server limit is 10000 (default 1000)
              --attempts N         tries per request before giving up            (default 5)
              --allow-nonempty     run against a server that already holds scans
                                   (skips verification, which needs an empty server)

              --help               show this text
            """;

    private static final Set<String> FLAGS = Set.of("help", "allow-nonempty");
    private static final Set<String> VALUE_OPTIONS = Set.of(
            "gates", "tickets", "shared-rate", "skew-seconds", "offline-minutes", "max-syncs",
            "dup-sync-rate", "seed", "url", "concurrency", "batch", "attempts");

    private SimulationCli() {
    }

    public static void main(String[] args) {
        System.exit(run(args, System.out, System.err));
    }

    static int run(String[] args, PrintStream out, PrintStream err) {
        Map<String, String> options;
        SimulationConfig config;
        HttpSettings http;
        try {
            options = parse(args);
            if (options.containsKey("help")) {
                out.print(USAGE);
                return 0;
            }
            config = configFrom(options);
            // Checked up front with everything else, so a bad value is a usage error
            // and not something that surfaces halfway through a run.
            http = httpSettingsFrom(options);
        } catch (IllegalArgumentException e) {
            err.println("error: " + e.getMessage());
            err.println();
            err.print(USAGE);
            return 2;
        }

        out.println("Gatekeeper partition simulation");
        out.printf(Locale.ROOT, "  %d gates, %,d tickets, seed %d%n", config.gates(), config.tickets(), config.seed());

        if (!options.containsKey("url")) {
            return runInProcess(config, out);
        }
        try {
            return runOverHttp(config, http, options, out, err);
        } catch (IOException e) {
            err.println("error: could not talk to the server: " + e.getMessage());
            return 2;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            err.println("interrupted");
            return 2;
        }
    }

    // ------------------------------------------------------------------ in-process

    private static int runInProcess(SimulationConfig config, PrintStream out) {
        out.println("  target: in-process, in-memory (checks every ticket)");
        out.println();

        SimulationResult result = Simulation.run(config, new InMemoryScanLog());

        out.println(result.describeWorkload());
        out.println();
        out.printf(Locale.ROOT, "Timing: ingest %,d ms, reconcile %,d ms%n", result.ingestMillis(), result.reconcileMillis());
        return report(result.problems(), out,
                "reconciliation matches the gates' own logs for every ticket");
    }

    // ------------------------------------------------------------------ over HTTP

    /** How to deliver over HTTP. Read from the options whether or not a URL was given, so a bad value is always caught. */
    private record HttpSettings(int concurrency, int batch, int attempts) {
    }

    private static HttpSettings httpSettingsFrom(Map<String, String> options) {
        return new HttpSettings(
                intOption(options, "concurrency", 4, 1, 256),
                intOption(options, "batch", 1_000, 1, 10_000),
                intOption(options, "attempts", 5, 1, 50));
    }

    private static int runOverHttp(SimulationConfig config, HttpSettings http, Map<String, String> options,
                                   PrintStream out, PrintStream err) throws IOException, InterruptedException {
        URI base = URI.create(options.get("url"));
        int attempts = http.attempts();
        boolean allowNonEmpty = options.containsKey("allow-nonempty");

        out.printf(Locale.ROOT, "  target: %s over HTTP, %d concurrent, batches of up to %,d%n",
                base, http.concurrency(), http.batch());

        HttpSyncRunner runner = new HttpSyncRunner(base, http.concurrency(), http.batch(), attempts);

        HttpSyncRunner.Summary before = runner.fetchSummary();
        if (before.totalScansConsidered() != 0 && !allowNonEmpty) {
            err.printf(Locale.ROOT,
                    "error: the server already holds %,d scans, so its totals cannot be compared with this run.%n"
                            + "Start it with an empty database, or pass --allow-nonempty to run without verification.%n",
                    before.totalScansConsidered());
            return 2;
        }

        SimulatedLogs logs = Workload.generate(config);
        Oracle oracle = Oracle.from(logs.byGate().values());
        List<SyncEvent> uploads = SyncSchedule.plan(logs, config, config.seed() + 1);

        List<String> problems = new ArrayList<>(Verifier.verifyGateLogs(logs));
        SimulationResult preview = new SimulationResult(config, logs, oracle, uploads.size(),
                SyncSchedule.recordsSent(uploads), problems, 0, 0);
        out.println();
        out.println(preview.describeWorkload());
        out.println();

        HttpSyncRunner.Outcome outcome = runner.run(uploads);
        printNetwork(outcome, SyncSchedule.recordsSent(uploads), out);

        long summaryStart = System.nanoTime();
        HttpSyncRunner.Summary after = runner.fetchSummary();
        double summaryMillis = (System.nanoTime() - summaryStart) / 1e6;
        out.printf(Locale.ROOT, "%nReconciliation summary fetched in %.0f ms: %,d scans, %,d tickets accepted, %,d conflicts%n",
                summaryMillis, after.totalScansConsidered(), after.acceptedTickets(), after.conflictCount());

        if (outcome.permanentFailures() > 0) {
            problems.add("%d sync requests failed permanently after %d attempts each"
                    .formatted(outcome.permanentFailures(), attempts));
        }
        if (allowNonEmpty && before.totalScansConsidered() != 0) {
            out.println("Verification skipped: the server already held scans before this run.");
        } else {
            problems.addAll(Verifier.verifyCounts(
                    after.totalScansConsidered(), after.acceptedTickets(), after.conflictCount(), oracle));
        }
        return report(problems, out, "the server's totals match the gates' own logs");
    }

    private static void printNetwork(HttpSyncRunner.Outcome outcome, long recordsSent, PrintStream out) {
        List<Long> sorted = new ArrayList<>(outcome.successLatenciesNanos());
        sorted.sort(Long::compare);
        double seconds = outcome.elapsedNanos() / 1e9;

        out.println("Delivery");
        out.printf(Locale.ROOT, "  requests succeeded    %,d%n", outcome.successfulRequests());
        out.printf(Locale.ROOT, "  failed attempts       %,d%s%n", outcome.failedAttempts(), describeFailures(outcome.failuresByStatus()));
        out.printf(Locale.ROOT, "  gave up on            %,d%n", outcome.permanentFailures());
        out.printf(Locale.ROOT, "  wall time             %.2f s   (%,.0f scan records/s sent)%n",
                seconds, seconds == 0 ? 0 : recordsSent / seconds);
        out.printf(Locale.ROOT, "  request latency ms    p50 %.1f   p95 %.1f   p99 %.1f   max %.1f%n",
                millis(percentile(sorted, 50)), millis(percentile(sorted, 95)),
                millis(percentile(sorted, 99)), millis(percentile(sorted, 100)));
    }

    private static String describeFailures(Map<Integer, Integer> byStatus) {
        if (byStatus.isEmpty()) {
            return "";
        }
        StringBuilder text = new StringBuilder("  (");
        boolean first = true;
        for (Map.Entry<Integer, Integer> entry : new TreeMap<>(byStatus).entrySet()) {
            if (!first) {
                text.append(", ");
            }
            text.append(entry.getKey() == 0 ? "no response" : "HTTP " + entry.getKey()).append(" x").append(entry.getValue());
            first = false;
        }
        return text.append("; each was retried)").toString();
    }

    // ------------------------------------------------------------------ shared

    private static int report(List<String> problems, PrintStream out, String passMessage) {
        out.println();
        if (problems.isEmpty()) {
            out.println("Result: PASS - " + passMessage);
            return 0;
        }
        out.println("Result: FAIL");
        problems.forEach(problem -> out.println("  - " + problem));
        return 1;
    }

    /** Nearest-rank percentile of an ascending list; 0 for an empty one. */
    static long percentile(List<Long> sorted, double p) {
        if (sorted.isEmpty()) {
            return 0;
        }
        int rank = (int) Math.ceil(p / 100 * sorted.size());
        return sorted.get(Math.min(Math.max(rank, 1), sorted.size()) - 1);
    }

    private static double millis(long nanos) {
        return nanos / 1e6;
    }

    static Map<String, String> parse(String[] args) {
        Map<String, String> options = new HashMap<>();
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if (!arg.startsWith("--")) {
                throw new IllegalArgumentException("unexpected argument '" + arg + "'");
            }
            String name = arg.substring(2);
            if (FLAGS.contains(name)) {
                options.put(name, "true");
            } else if (VALUE_OPTIONS.contains(name)) {
                if (i + 1 >= args.length) {
                    throw new IllegalArgumentException("--" + name + " needs a value");
                }
                options.put(name, args[++i]);
            } else {
                throw new IllegalArgumentException("unknown option --" + name);
            }
        }
        return options;
    }

    static SimulationConfig configFrom(Map<String, String> options) {
        SimulationConfig defaults = SimulationConfig.defaults();
        return new SimulationConfig(
                intOption(options, "gates", defaults.gates(), 1, 1_000),
                intOption(options, "tickets", defaults.tickets(), 1, 10_000_000),
                defaults.attendanceRate(),
                doubleOption(options, "shared-rate", defaults.sharedTicketRate()),
                defaults.sameGateRescanRate(),
                defaults.invalidCodeRate(),
                defaults.forgedRate(),
                Duration.ofMinutes(intOption(options, "offline-minutes", (int) defaults.offlineWindow().toMinutes(), 1, 100_000)),
                defaults.sharedGap(),
                Duration.ofMillis((long) (doubleOption(options, "skew-seconds", defaults.maxClockSkew().toSeconds()) * 1000)),
                intOption(options, "max-syncs", defaults.maxSyncsPerGate(), 1, 100),
                doubleOption(options, "dup-sync-rate", defaults.duplicateSyncRate()),
                longOption(options, "seed", defaults.seed()));
    }

    private static int intOption(Map<String, String> options, String name, int fallback, int min, int max) {
        if (!options.containsKey(name)) {
            return fallback;
        }
        int value;
        try {
            value = Integer.parseInt(options.get(name).replace("_", ""));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("--" + name + " must be a whole number, got '" + options.get(name) + "'");
        }
        if (value < min || value > max) {
            throw new IllegalArgumentException("--" + name + " must be between " + min + " and " + max);
        }
        return value;
    }

    private static long longOption(Map<String, String> options, String name, long fallback) {
        if (!options.containsKey(name)) {
            return fallback;
        }
        try {
            return Long.parseLong(options.get(name));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("--" + name + " must be a whole number, got '" + options.get(name) + "'");
        }
    }

    private static double doubleOption(Map<String, String> options, String name, double fallback) {
        if (!options.containsKey(name)) {
            return fallback;
        }
        try {
            return Double.parseDouble(options.get(name));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("--" + name + " must be a number, got '" + options.get(name) + "'");
        }
    }
}
