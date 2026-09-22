package dev.gatekeeper.gate;

/**
 * The result of one scan attempt at a gate. A sealed interface rather than an
 * enum because {@link Rejected} carries a reason and {@link Accepted} could
 * later carry more detail without weakening the type — the compiler forces
 * every {@code switch} over a {@code ScanOutcome} to handle each case.
 */
public sealed interface ScanOutcome {

    /** The code was valid and this gate had not already let this ticket in. */
    record Accepted() implements ScanOutcome {
    }

    /** The code was valid, but this same gate already accepted this ticket. */
    record RejectedDuplicate(ScanRecord firstAcceptedScan) implements ScanOutcome {
    }

    /** The code did not verify against the gate's local copy of the secret. */
    record RejectedInvalidCode() implements ScanOutcome {
    }

    /** This gate holds no ticket with the given ID (not provisioned to it, or unknown). */
    record RejectedUnknownTicket() implements ScanOutcome {
    }
}
