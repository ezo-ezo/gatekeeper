package dev.gatekeeper.simulation;

import dev.gatekeeper.gate.ScanRecord;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SimulationCliTest {

    /** Runs the CLI and captures what it printed and how it exited. */
    private record Run(int exit, String out, String err) {
    }

    private static Run cli(String... args) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int exit = SimulationCli.run(args,
                new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8));
        return new Run(exit, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    }

    // ------------------------------------------------------------------ in-process

    @Test
    void anInProcessRunPassesAndExplainsWhatHappened() {
        Run run = cli("--tickets", "600", "--gates", "4", "--seed", "5");

        assertThat(run.exit()).isZero();
        assertThat(run.out())
                .contains("Result: PASS")
                .contains("accepted at 2+ gates")
                .contains("scans attempted")
                .contains("in-process");
    }

    @Test
    void helpPrintsUsageAndSucceeds() {
        Run run = cli("--help");

        assertThat(run.exit()).isZero();
        assertThat(run.out()).contains("Usage:").contains("--tickets").contains("--url");
    }

    @Test
    void underscoresInNumbersAreAllowedForReadability() {
        assertThat(cli("--tickets", "1_000", "--gates", "3").exit()).isZero();
    }

    // ------------------------------------------------------------------ bad usage

    @Test
    void anUnknownOptionIsRejectedWithUsage() {
        Run run = cli("--ticket", "10");

        assertThat(run.exit()).isEqualTo(2);
        assertThat(run.err()).contains("unknown option --ticket").contains("Usage:");
    }

    @Test
    void anOptionWithoutItsValueIsRejected() {
        Run run = cli("--tickets");

        assertThat(run.exit()).isEqualTo(2);
        assertThat(run.err()).contains("--tickets needs a value");
    }

    @Test
    void aNonNumericValueNamesTheOptionAndTheValue() {
        Run run = cli("--gates", "many");

        assertThat(run.exit()).isEqualTo(2);
        assertThat(run.err()).contains("--gates").contains("'many'");
    }

    @Test
    void anOutOfRangeValueIsRejected() {
        assertThat(cli("--gates", "0").exit()).isEqualTo(2);
        assertThat(cli("--batch", "10001", "--url", "http://localhost:1").exit()).isEqualTo(2);
        assertThat(cli("--shared-rate", "1.5").exit()).isEqualTo(2);
    }

    @Test
    void aBareWordIsRejected() {
        Run run = cli("tickets", "10");

        assertThat(run.exit()).isEqualTo(2);
        assertThat(run.err()).contains("unexpected argument 'tickets'");
    }

    @Test
    void aServerThatCannotBeReachedIsReportedNotThrown() {
        Run run = cli("--url", "http://localhost:1", "--tickets", "50");

        assertThat(run.exit()).isEqualTo(2);
        assertThat(run.err()).contains("could not talk to the server");
    }

    // ------------------------------------------------------------------ the pieces

    @Test
    void requestBodiesAreWellFormedJson() {
        String json = HttpSyncRunner.toJson(List.of(
                new ScanRecord("gate-01", "ticket-1", Instant.parse("2026-09-26T18:00:07.353Z"), true),
                new ScanRecord("gate-01", "odd\"id\\x", Instant.parse("2026-09-26T18:00:08Z"), false)));

        assertThat(json).isEqualTo(
                "{\"scans\":["
                        + "{\"ticketId\":\"ticket-1\",\"scannedAt\":\"2026-09-26T18:00:07.353Z\",\"accepted\":true},"
                        + "{\"ticketId\":\"odd\\\"id\\\\x\",\"scannedAt\":\"2026-09-26T18:00:08Z\",\"accepted\":false}"
                        + "]}");
    }

    @Test
    void anEmptyBatchIsAnEmptyList() {
        assertThat(HttpSyncRunner.toJson(List.of())).isEqualTo("{\"scans\":[]}");
    }

    @Test
    void theSummaryIsReadFromTheServersJson() {
        HttpSyncRunner.Summary summary = HttpSyncRunner.parseSummary(
                "{\"totalScansConsidered\":5391,\"acceptedTickets\":4567,\"conflictCount\":266}");

        assertThat(summary).isEqualTo(new HttpSyncRunner.Summary(5391, 4567, 266));
    }

    @Test
    void aSummaryMissingAFieldIsAnErrorNotAZero() {
        assertThatThrownBy(() -> HttpSyncRunner.parseSummary("{\"totalScansConsidered\":5,\"acceptedTickets\":4}"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("conflictCount");
    }

    @Test
    void percentilesUseTheNearestRankAndToleratesEmptyInput() {
        List<Long> sorted = List.of(10L, 20L, 30L, 40L, 50L, 60L, 70L, 80L, 90L, 100L);

        assertThat(SimulationCli.percentile(sorted, 50)).isEqualTo(50);
        assertThat(SimulationCli.percentile(sorted, 95)).isEqualTo(100);
        assertThat(SimulationCli.percentile(sorted, 100)).isEqualTo(100);
        assertThat(SimulationCli.percentile(sorted, 1)).isEqualTo(10);
        assertThat(SimulationCli.percentile(List.of(), 99)).isZero();
    }
}
