package dev.gatekeeper.gate;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A test-only {@link Clock} whose instant can be moved forward by hand, so
 * tests can simulate a gate's clock advancing (or drifting) without
 * {@code Thread.sleep}.
 */
final class MutableClock extends Clock {

    private final AtomicReference<Instant> now;
    private final ZoneId zone;

    MutableClock(Instant start) {
        this(start, ZoneOffset.UTC);
    }

    private MutableClock(Instant start, ZoneId zone) {
        this.now = new AtomicReference<>(start);
        this.zone = zone;
    }

    void advance(java.time.Duration by) {
        now.updateAndGet(i -> i.plus(by));
    }

    @Override
    public ZoneId getZone() {
        return zone;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return new MutableClock(now.get(), zone);
    }

    @Override
    public Instant instant() {
        return now.get();
    }
}
