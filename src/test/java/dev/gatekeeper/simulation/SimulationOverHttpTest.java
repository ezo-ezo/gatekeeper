package dev.gatekeeper.simulation;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.annotation.DirtiesContext;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The simulation, driving the real application over real HTTP: a real
 * embedded Tomcat on a real port, the database-backed store, several gates
 * uploading at once in small batches with overlapping and repeated
 * uploads. This is the closest a unit-test run gets to an event happening.
 *
 * <p>The simulation refuses to run against a server that already holds scans,
 * and this server keeps whatever it is given, so each test needs a context (and
 * database) of its own; a shared one would make the result depend on which test
 * happened to run first.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_EACH_TEST_METHOD)
class SimulationOverHttpTest {

    @LocalServerPort
    private int port;

    private record Run(int exit, String out, String err) {
    }

    private Run cli(String... extra) {
        String[] fixed = {"--url", "http://localhost:" + port};
        String[] args = new String[fixed.length + extra.length];
        System.arraycopy(fixed, 0, args, 0, fixed.length);
        System.arraycopy(extra, 0, args, fixed.length, extra.length);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int exit = SimulationCli.run(args,
                new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8));
        return new Run(exit, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    }

    /**
     * Small batches and many concurrent gates, so several uploads carrying
     * overlapping scans really are in flight together. Zero failed attempts is
     * the point: before the store retried a lost race, some of these requests
     * came back as HTTP 500.
     */
    @Test
    void concurrentGatesUploadingOverlappingLogsAllSucceedAndTheServerAgreesWithTheGates() {
        Run run = cli("--tickets", "1200", "--gates", "6", "--concurrency", "8", "--batch", "100", "--seed", "3");

        assertThat(run.err()).isEmpty();
        assertThat(run.out())
                .contains("Result: PASS")
                .containsPattern("failed attempts\\s+0\\b")
                .containsPattern("gave up on\\s+0\\b");
        assertThat(run.exit()).isZero();
    }

    /**
     * The server keeps what it is given, so a second run against it cannot be
     * compared with fresh ground truth. It must say so and stop, not report a
     * confusing "failure" that is really just leftover data.
     */
    @Test
    void aServerThatAlreadyHoldsScansIsRefusedUnlessTheRunIsToldNotToVerify() {
        assertThat(cli("--tickets", "300", "--gates", "4", "--seed", "4").exit()).isZero();

        Run second = cli("--tickets", "300", "--gates", "4", "--seed", "4");
        assertThat(second.exit()).isEqualTo(2);
        assertThat(second.err()).contains("already holds").contains("--allow-nonempty");

        Run unverified = cli("--tickets", "300", "--gates", "4", "--seed", "4", "--allow-nonempty");
        assertThat(unverified.exit()).isZero();
        assertThat(unverified.out()).contains("Verification skipped");
    }
}
