package dev.gatekeeper.persistence;

import dev.gatekeeper.simulation.SimulationConfig;
import dev.gatekeeper.simulation.SimulationResult;
import dev.gatekeeper.simulation.Simulation;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.transaction.PlatformTransactionManager;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The same simulated event as the in-memory tests, but stored through the real
 * database-backed log. The simulation's gates run on clocks with nanosecond
 * digits and upload overlapping, repeated batches in a shuffled order, which
 * is exactly the traffic that exposed the timestamp-precision bug: this is its
 * regression test at the level of a whole event rather than a single scan.
 */
@DataJpaTest
class PersistentSimulationTest {

    @Autowired
    private ScanRecordRepository repository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Test
    void theSameEventReconcilesCorrectlyThroughTheDatabase() {
        SimulationConfig config = SimulationConfig.defaults().withGates(6).withTickets(400).withSeed(11);

        SimulationResult result = Simulation.run(config, new JpaScanLog(repository, transactionManager, new SimpleMeterRegistry()));

        assertThat(result.problems()).isEmpty();
        assertThat(result.oracle().conflictCount()).isPositive();
        assertThat(result.recordsSent()).isGreaterThan(result.oracle().distinctRecords());
    }
}
