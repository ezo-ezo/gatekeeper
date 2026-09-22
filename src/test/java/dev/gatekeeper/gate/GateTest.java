package dev.gatekeeper.gate;

import dev.gatekeeper.ticket.Ticket;
import dev.gatekeeper.ticket.TotpTokenGenerator;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class GateTest {

    private static final Instant START = Instant.ofEpochSecond(1_700_000_010); // aligned to a 30s window

    @Test
    void acceptsAValidScanOfAProvisionedTicket() {
        MutableClock clock = new MutableClock(START);
        Gate gate = new Gate("gate-1", clock);
        Ticket ticket = Ticket.issue("event-1", "Asha Rao", START);
        gate.provision(ticket);

        ScanOutcome outcome = gate.scan(ticket.id(), ticket.currentCode(START));

        assertThat(outcome).isInstanceOf(ScanOutcome.Accepted.class);
        assertThat(gate.log()).containsExactly(new ScanRecord("gate-1", ticket.id(), START, true));
    }

    @Test
    void rejectsATicketThisGateWasNeverProvisionedWith() {
        Gate gate = new Gate("gate-1", new MutableClock(START));

        ScanOutcome outcome = gate.scan("unknown-ticket", "000000");

        assertThat(outcome).isInstanceOf(ScanOutcome.RejectedUnknownTicket.class);
        assertThat(gate.log()).hasSize(1);
        assertThat(gate.log().get(0).accepted()).isFalse();
    }

    @Test
    void rejectsAnInvalidCode() {
        Gate gate = new Gate("gate-1", new MutableClock(START));
        Ticket ticket = Ticket.issue("event-1", "Asha Rao", START);
        gate.provision(ticket);

        ScanOutcome outcome = gate.scan(ticket.id(), "000000");

        assertThat(outcome).isInstanceOf(ScanOutcome.RejectedInvalidCode.class);
    }

    @Test
    void rejectsTheSameTicketScannedTwiceAtTheSameGate() {
        MutableClock clock = new MutableClock(START);
        Gate gate = new Gate("gate-1", clock);
        Ticket ticket = Ticket.issue("event-1", "Asha Rao", START);
        gate.provision(ticket);

        ScanOutcome first = gate.scan(ticket.id(), ticket.currentCode(START));
        assertThat(first).isInstanceOf(ScanOutcome.Accepted.class);

        clock.advance(Duration.ofSeconds(5));
        ScanOutcome second = gate.scan(ticket.id(), ticket.currentCode(clock.instant()));

        assertThat(second).isInstanceOf(ScanOutcome.RejectedDuplicate.class);
        ScanRecord firstAccepted = ((ScanOutcome.RejectedDuplicate) second).firstAcceptedScan();
        assertThat(firstAccepted.scannedAt()).isEqualTo(START);
        assertThat(firstAccepted.gateId()).isEqualTo("gate-1");
    }

    @Test
    void toleratesOneStepOfClockDriftBetweenTicketAndGate() {
        MutableClock gateClock = new MutableClock(START);
        Gate gate = new Gate("gate-1", gateClock);
        Ticket ticket = Ticket.issue("event-1", "Asha Rao", START);
        gate.provision(ticket);

        String code = ticket.currentCode(START);
        gateClock.advance(Duration.ofSeconds(40)); // gate's clock is running fast

        ScanOutcome outcome = gate.scan(ticket.id(), code);

        assertThat(outcome).isInstanceOf(ScanOutcome.Accepted.class);
    }

    @Test
    void rejectsACodeFromTooLongAgo() {
        MutableClock gateClock = new MutableClock(START);
        Gate gate = new Gate("gate-1", gateClock);
        Ticket ticket = Ticket.issue("event-1", "Asha Rao", START);
        gate.provision(ticket);

        String code = ticket.currentCode(START);
        gateClock.advance(TotpTokenGenerator.TIME_STEP.multipliedBy(5));

        ScanOutcome outcome = gate.scan(ticket.id(), code);

        assertThat(outcome).isInstanceOf(ScanOutcome.RejectedInvalidCode.class);
    }

    @Test
    void logRecordsEveryAttemptInOrderIncludingRejections() {
        MutableClock clock = new MutableClock(START);
        Gate gate = new Gate("gate-1", clock);
        Ticket ticket = Ticket.issue("event-1", "Asha Rao", START);
        gate.provision(ticket);

        gate.scan(ticket.id(), "000000");                   // rejected: bad code
        gate.scan(ticket.id(), ticket.currentCode(START));   // accepted
        gate.scan(ticket.id(), ticket.currentCode(START));   // rejected: duplicate

        List<ScanRecord> log = gate.log();
        assertThat(log).hasSize(3);
        assertThat(log.get(0).accepted()).isFalse();
        assertThat(log.get(1).accepted()).isTrue();
        assertThat(log.get(2).accepted()).isFalse();
    }

    @Test
    void reprovisioningATicketReplacesTheStoredCopy() {
        Gate gate = new Gate("gate-1", new MutableClock(START));
        Ticket original = Ticket.issue("event-1", "Asha Rao", START);
        gate.provision(original);

        Ticket reissued = new Ticket(original.id(), original.eventId(), original.holderName(),
                dev.gatekeeper.ticket.TicketSecret.generate(), START);
        gate.provision(reissued);

        // The old secret's code must no longer verify; only the newly provisioned one does.
        assertThat(gate.scan(original.id(), original.currentCode(START)))
                .isInstanceOf(ScanOutcome.RejectedInvalidCode.class);
        assertThat(gate.scan(reissued.id(), reissued.currentCode(START)))
                .isInstanceOf(ScanOutcome.Accepted.class);
    }

    @Test
    void exactlyOneOfManyConcurrentScansOfTheSameTicketIsAccepted() throws Exception {
        Gate gate = new Gate("gate-1", new MutableClock(START));
        Ticket ticket = Ticket.issue("event-1", "Asha Rao", START);
        gate.provision(ticket);
        String code = ticket.currentCode(START);

        int attempts = 50;
        ExecutorService pool = Executors.newFixedThreadPool(16);
        CountDownLatch ready = new CountDownLatch(attempts);
        CountDownLatch go = new CountDownLatch(1);
        AtomicInteger accepted = new AtomicInteger();

        List<Future<?>> futures = IntStream.range(0, attempts)
                .mapToObj(i -> pool.submit(() -> {
                    ready.countDown();
                    awaitUnchecked(go);
                    if (gate.scan(ticket.id(), code) instanceof ScanOutcome.Accepted) {
                        accepted.incrementAndGet();
                    }
                }))
                .collect(Collectors.toList());

        ready.await(5, TimeUnit.SECONDS);
        go.countDown();
        for (Future<?> f : futures) {
            f.get(5, TimeUnit.SECONDS);
        }
        pool.shutdown();

        assertThat(accepted.get()).isEqualTo(1);
        assertThat(gate.log()).hasSize(attempts);

        Set<Boolean> outcomes = gate.log().stream().map(ScanRecord::accepted).collect(Collectors.toSet());
        assertThat(outcomes).contains(true, false); // both an accept and rejections happened
    }

    private static void awaitUnchecked(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }
    }
}
