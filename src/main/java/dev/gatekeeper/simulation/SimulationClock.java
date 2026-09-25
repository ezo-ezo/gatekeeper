package dev.gatekeeper.simulation;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/**
 * The simulation's "true" time, moved by hand to each scan's moment. Every
 * gate wraps it in its own {@link Clock#offset offset}, which is how gates
 * end up disagreeing about what time it is.
 */
public final class SimulationClock extends Clock {

    private volatile Instant now;

    public SimulationClock(Instant start) {
        this.now = start;
    }

    public void set(Instant now) {
        this.now = now;
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return this; // zones are irrelevant: everything here is an Instant
    }

    @Override
    public Instant instant() {
        return now;
    }
}
