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

Early scaffold: a running Spring Boot service with a health endpoint. The token rotation,
offline validation and reconciliation logic have not been built yet.

## Run

```bash
mvn spring-boot:run
curl localhost:8080/healthz
mvn test
```

## Roadmap

- [x] Project skeleton, health endpoint
- [x] Ticket + rotating token model (TOTP-style secret per ticket)
- [ ] Gate-side offline validation against a signed token, no network required
- [ ] Sync protocol: gates upload their scan log when back online
- [ ] Conflict detection: same ticket scanned at two gates before sync
- [ ] Reconciliation policy (first valid scan wins, flag the rest) with an audit trail
- [ ] Load/partition simulation: gates going offline, then reconnecting, under load
- [ ] Metrics and a short design write-up

## How AI was used

This section is filled in as the project goes.
