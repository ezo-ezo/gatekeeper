package dev.gatekeeper.simulation;

import dev.gatekeeper.gate.Gate;
import dev.gatekeeper.gate.ScanOutcome;
import dev.gatekeeper.gate.ScanRecord;
import dev.gatekeeper.ticket.Ticket;
import dev.gatekeeper.ticket.TicketSecret;
import dev.gatekeeper.ticket.TotpTokenGenerator;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Plays a whole event through real {@link Gate} objects while they are cut
 * off from the network: tickets are provisioned up front, then people arrive
 * and scan, and every gate makes its own decisions using only its own copy of
 * the tickets and its own (slightly wrong) clock.
 *
 * <p>Nothing is scripted about the outcomes. The workload only decides who
 * shows what to which gate and when; whether a scan is accepted, rejected as a
 * duplicate, or rejected for a bad code is whatever the gate actually returns.
 * That matters: the expected reconciliation is derived from what the gates
 * really recorded, not from what this class intended.
 */
public final class Workload {

    /** When the gates go offline. */
    public static final Instant START = Instant.parse("2026-09-26T18:00:00Z");

    private static final String EVENT_ID = "simulated-event";

    /** Wider than any real gate's tolerance, so a "stale" code can never be accepted by accident. */
    private static final int STALE_CODE_MARGIN_STEPS = 3;

    private Workload() {
    }

    /** One person showing one code to one gate at one moment. */
    private record Presentation(Instant at, int gate, int ticket, boolean staleCode, String forgedTicketId) {

        static Presentation real(Instant at, int gate, int ticket, boolean staleCode) {
            return new Presentation(at, gate, ticket, staleCode, null);
        }

        static Presentation forged(Instant at, int gate, String ticketId) {
            return new Presentation(at, gate, -1, false, ticketId);
        }
    }

    public static SimulatedLogs generate(SimulationConfig config) {
        Random random = new Random(config.seed());
        SimulationClock trueTime = new SimulationClock(START);

        List<Gate> gates = new ArrayList<>(config.gates());
        for (int g = 0; g < config.gates(); g++) {
            Clock gateClock = Clock.offset(trueTime, randomSkew(random, config.maxClockSkew()));
            gates.add(new Gate("gate-%02d".formatted(g + 1), gateClock));
        }

        List<Ticket> tickets = new ArrayList<>(config.tickets());
        for (int i = 0; i < config.tickets(); i++) {
            Ticket ticket = new Ticket("ticket-%06d".formatted(i), EVENT_ID, "Holder " + i,
                    TicketSecret.generate(), START.minus(Duration.ofDays(7)));
            tickets.add(ticket);
            for (Gate gate : gates) {
                gate.provision(ticket);
            }
        }

        List<Presentation> plan = plan(config, random);
        // List.sort is stable, so equal instants keep their planned order and the run stays reproducible.
        plan.sort(Comparator.comparing(Presentation::at));

        int accepted = 0;
        int duplicates = 0;
        int invalidCodes = 0;
        int unknownTickets = 0;

        for (Presentation p : plan) {
            trueTime.set(p.at());
            Gate gate = gates.get(p.gate());

            String ticketId;
            String code;
            if (p.forgedTicketId() != null) {
                ticketId = p.forgedTicketId();
                code = "000000";
            } else {
                Ticket ticket = tickets.get(p.ticket());
                ticketId = ticket.id();
                code = p.staleCode() ? staleCode(ticket, p.at()) : ticket.currentCode(p.at());
            }

            switch (gate.scan(ticketId, code)) {
                case ScanOutcome.Accepted a -> accepted++;
                case ScanOutcome.RejectedDuplicate d -> duplicates++;
                case ScanOutcome.RejectedInvalidCode i -> invalidCodes++;
                case ScanOutcome.RejectedUnknownTicket u -> unknownTickets++;
            }
        }

        Map<String, List<ScanRecord>> logs = new LinkedHashMap<>();
        for (Gate gate : gates) {
            logs.put(gate.id(), gate.log());
        }
        return new SimulatedLogs(logs, accepted, duplicates, invalidCodes, unknownTickets);
    }

    private static List<Presentation> plan(SimulationConfig config, Random random) {
        List<Presentation> plan = new ArrayList<>();
        int windowSeconds = (int) config.offlineWindow().toSeconds();
        int gateCount = config.gates();

        for (int ticket = 0; ticket < config.tickets(); ticket++) {
            if (random.nextDouble() >= config.attendanceRate()) {
                continue;
            }

            int entryGate = random.nextInt(gateCount);
            Instant arrival = Workload.START.plusSeconds(random.nextInt(windowSeconds));
            present(plan, random, config, arrival, entryGate, ticket);

            if (random.nextDouble() < config.sameGateRescanRate()) {
                plan.add(Presentation.real(arrival.plusSeconds(1 + random.nextInt(120)), entryGate, ticket, false));
            }

            if (gateCount >= 2 && random.nextDouble() < config.sharedTicketRate()) {
                // The same ticket turning up at other gates while nobody can tell anybody: usually
                // one extra gate, sometimes two.
                int extraGates = Math.min(gateCount - 1, random.nextDouble() < 0.25 ? 2 : 1);
                List<Integer> others = new ArrayList<>();
                for (int g = 0; g < gateCount; g++) {
                    if (g != entryGate) {
                        others.add(g);
                    }
                }
                java.util.Collections.shuffle(others, random);
                int gapSeconds = (int) config.sharedGap().toSeconds();
                for (int i = 0; i < extraGates; i++) {
                    Instant at = arrival.plusSeconds(random.nextInt(gapSeconds));
                    plan.add(Presentation.real(at, others.get(i), ticket, false));
                }
            }
        }

        int forged = (int) Math.round(config.forgedRate() * config.tickets());
        for (int i = 0; i < forged; i++) {
            plan.add(Presentation.forged(Workload.START.plusSeconds(random.nextInt(windowSeconds)),
                    random.nextInt(gateCount), "forged-%06d".formatted(i)));
        }
        return plan;
    }

    /** A presentation that may show a stale code, in which case the person usually tries again. */
    private static void present(List<Presentation> plan, Random random, SimulationConfig config,
                                Instant at, int gate, int ticket) {
        if (random.nextDouble() < config.invalidCodeRate()) {
            plan.add(Presentation.real(at, gate, ticket, true));
            if (random.nextDouble() < 0.8) {
                plan.add(Presentation.real(at.plusSeconds(5 + random.nextInt(25)), gate, ticket, false));
            }
        } else {
            plan.add(Presentation.real(at, gate, ticket, false));
        }
    }

    /**
     * A code from long ago, checked against the real verifier to be sure it is
     * not accidentally valid now, so a "stale" presentation is guaranteed to be one.
     */
    private static String staleCode(Ticket ticket, Instant at) {
        int stepsAgo = 50;
        String code = TotpTokenGenerator.generate(ticket.secret(), at.minus(TotpTokenGenerator.TIME_STEP.multipliedBy(stepsAgo)));
        while (TotpTokenGenerator.verify(ticket.secret(), code, at, STALE_CODE_MARGIN_STEPS)) {
            stepsAgo++;
            code = TotpTokenGenerator.generate(ticket.secret(), at.minus(TotpTokenGenerator.TIME_STEP.multipliedBy(stepsAgo)));
        }
        return code;
    }

    /** Uniform in [-max, +max] at nanosecond resolution, so scan times carry sub-millisecond digits like a real clock's. */
    private static Duration randomSkew(Random random, Duration max) {
        if (max.isZero()) {
            return Duration.ZERO;
        }
        double fraction = random.nextDouble() * 2 - 1;
        return Duration.ofNanos((long) (fraction * max.toNanos()));
    }
}
