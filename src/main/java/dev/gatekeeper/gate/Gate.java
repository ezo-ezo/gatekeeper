package dev.gatekeeper.gate;

import dev.gatekeeper.ticket.Ticket;
import dev.gatekeeper.ticket.TotpTokenGenerator;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A single entry point, e.g. one turnstile or scanner. A gate is provisioned
 * ahead of time with the tickets it may see (see {@link #provision}) and from
 * then on needs no network connection at all to accept or reject a scan: both
 * the code check ({@link TotpTokenGenerator}) and the duplicate check (this
 * gate's own {@link #log}) are local.
 *
 * <p>What a single gate <em>cannot</em> catch on its own is the same ticket
 * being accepted at a <em>different</em> gate before the two have synced —
 * that is a cross-gate conflict, resolved once both logs reach the server
 * (see {@code docs/design.md}, phase 4).
 *
 * <p>Thread-safe: a real device could have this called from more than one
 * input path concurrently.
 */
public final class Gate {

    /**
     * Clock drift tolerated when checking a scanned code, in units of
     * {@link TotpTokenGenerator#TIME_STEP}. A gate's clock is not guaranteed
     * to be perfectly synced while it has no network access.
     */
    private static final int ALLOWED_CLOCK_DRIFT_STEPS = 1;

    private final String id;
    private final Clock clock;
    private final Map<String, Ticket> provisionedTickets = new ConcurrentHashMap<>();
    private final Map<String, ScanRecord> firstAcceptedScanByTicket = new ConcurrentHashMap<>();
    private final List<ScanRecord> log = new CopyOnWriteArrayList<>();

    public Gate(String id, Clock clock) {
        this.id = Objects.requireNonNull(id, "id");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** This gate's identifier, as recorded on every {@link ScanRecord} it produces. */
    public String id() {
        return id;
    }

    /**
     * Loads a ticket into this gate's local copy, as would happen from a sync
     * pulled down while the gate still had connectivity. Provisioning the
     * same ticket again simply replaces the stored copy.
     */
    public void provision(Ticket ticket) {
        provisionedTickets.put(ticket.id(), ticket);
    }

    /**
     * Validates a scanned code against this gate's local data only. Every
     * attempt is appended to {@link #log}, accepted or not, so the full
     * history is available to sync later.
     */
    public ScanOutcome scan(String ticketId, String code) {
        Instant now = clock.instant();
        Ticket ticket = provisionedTickets.get(ticketId);

        if (ticket == null) {
            recordAttempt(ticketId, now, false);
            return new ScanOutcome.RejectedUnknownTicket();
        }

        if (!TotpTokenGenerator.verify(ticket.secret(), code, now, ALLOWED_CLOCK_DRIFT_STEPS)) {
            recordAttempt(ticketId, now, false);
            return new ScanOutcome.RejectedInvalidCode();
        }

        ScanRecord accepted = new ScanRecord(id, ticketId, now, true);
        ScanRecord existing = firstAcceptedScanByTicket.putIfAbsent(ticketId, accepted);
        if (existing != null) {
            recordAttempt(ticketId, now, false);
            return new ScanOutcome.RejectedDuplicate(existing);
        }

        log.add(accepted);
        return new ScanOutcome.Accepted();
    }

    private void recordAttempt(String ticketId, Instant now, boolean accepted) {
        log.add(new ScanRecord(id, ticketId, now, accepted));
    }

    /** The full scan history recorded at this gate, in the order attempted. */
    public List<ScanRecord> log() {
        return List.copyOf(log);
    }
}
