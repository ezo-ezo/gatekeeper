# Gatekeeper

An offline-first venue entry system: gates validate tickets without a network connection,
and duplicate or conflicting scans are caught and reconciled once gates reconnect.

Built in Java (Spring Boot). The problem isn't the "let this ticket in" happy path — it's
staying correct when the network can't be trusted:

1. **Tickets must not be resellable via screenshot.** A ticket's QR code rotates over time
   (TOTP-style), so a captured image goes stale.
2. **Gates must work with no connectivity.** A gate validates a scanned token against a
   locally-held signed secret, with no round trip to a server.
3. **Two offline gates can both "accept" the same ticket** before either syncs. The system
   has to detect that after the fact and decide what happened, not just take whichever
   scan arrived at the server first.

## Status

The three core pieces exist as a tested Java library, wired to nothing yet:

- `dev.gatekeeper.ticket` — rotating TOTP tokens, cross-checked against the RFC 6238 and
  RFC 4648 test vectors.
- `dev.gatekeeper.gate` — offline scan validation and duplicate detection at a single gate.
- `dev.gatekeeper.reconcile` — a CRDT-based merge of multiple gates' scan logs that flags
  a ticket accepted at more than one gate and picks a winner.

Design rationale and the trade-offs behind each is in [docs/design.md](docs/design.md).
Not yet built: an HTTP layer for gates to actually sync, persistence, and a
load/partition simulation.

## Run

```bash
mvn spring-boot:run
curl localhost:8080/healthz
mvn test
```

## Roadmap

- [x] Project skeleton, health endpoint
- [x] Ticket + rotating token model (TOTP-style secret per ticket)
- [x] Gate-side offline validation against a signed token, no network required
- [x] Conflict detection and reconciliation: same ticket accepted at two gates before sync, earliest scan wins, the rest are flagged with an audit trail (see `Reconciler`)
- [ ] Sync protocol: an HTTP endpoint for gates to upload their scan log when back online
- [ ] Load/partition simulation: gates going offline, then reconnecting, under load
- [ ] Metrics

## How AI was used

This section is filled in as the project goes.
