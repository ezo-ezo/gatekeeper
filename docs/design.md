# Gatekeeper design notes

## The problem

A venue entrance loses connectivity, or was never meant to depend on one in real time, but
still has to (a) refuse a screenshotted or resold ticket, (b) let a valid ticket in even with
zero network access, and (c) not silently lose track of it if the same ticket gets accepted
at two different gates before either could tell the other.

## Rotating tokens (phase 2)

A ticket's QR code is a TOTP code (RFC 6238, HMAC-SHA1, 30s step), the same construction as
Google Authenticator. Generating and verifying a code are both pure functions of a shared
secret and the clock — no server round trip either way — which is what makes offline
validation possible at all. `TotpTokenGenerator` is cross-checked against the published RFC
6238 and RFC 4648 test vectors, not just self-consistency, so it's provably interoperable
with the reference algorithm.

**Trade-off:** allowing clock drift (`ALLOWED_CLOCK_DRIFT_STEPS` in `Gate`) means a captured
code stays guessable for a short window after capture. Zero tolerance would be more secure
but would fail real scans on any gate whose clock had drifted at all. One step (±30s) was
picked as a reasonable default; it is not tuned against a real device's clock behavior.

## Gate-side offline validation (phase 3)

`Gate` holds a local copy of the tickets it was provisioned with (imagine this pushed to the
device before doors opened, while it still had a connection) and validates every scan
against that local copy only — code check and duplicate check are both local reads. Every
scan attempt, accepted or not, is appended to the gate's own log, which is exactly the data
phase 4's reconciliation consumes.

What a single gate structurally cannot do is know whether the same ticket was also accepted
at a *different* gate before the two synced. That's not a bug to fix at the gate — it's the
actual distributed-systems problem the next phase exists for.

## Conflict reconciliation (phase 4)

`Reconciler` is a CRDT: a grow-only set of scans, merged from as many gates' logs as arrive,
in any order, any number of times.

- **`ingest` only ever adds.** `ScanRecord`'s value equality makes re-syncing an
  already-seen scan (a retried upload, an overlapping resync) a genuine no-op rather than a
  double-count.
- **`reconcile()` is a pure function of the current set.** Two gates' logs ingested in either
  order produce the same report; calling `reconcile()` twice with nothing new produces the
  same report. Both are asserted directly in `ReconcilerTest`, not just claimed.
- **Resolution policy:** when a ticket was accepted at more than one gate, the scan with the
  earliest `scannedAt` wins; every other accepted scan for that ticket is reported as a
  conflict, not discarded. A human sees who else this ticket let in and when.

**Trade-off, stated plainly:** "earliest wins" trusts gate clocks to be reasonably close to
each other. It is simple, deterministic, and requires nothing from the gates beyond a
clock — but a gate whose clock is meaningfully wrong could "win" a conflict it should have
lost. This is not fixed here. The honest way to fix it is a logical clock: have each gate
attach a Lamport timestamp or a hybrid logical clock to its scans, so ordering reflects
causal order (and confirmed network syncs) rather than raw wall-clock time. That is real,
uncommitted future work, not a hidden assumption — see "Not done" below.

It's also worth noting the two problems are already linked: a gate clock drifted enough to
make "who was first" meaningless would already be failing ordinary TOTP validation (phase 2's
±30s tolerance), so by the time a scan is *accepted* at all, its gate's clock is implicitly
bounded to within roughly a TOTP window of correct. That doesn't make the policy exact, but
it's not arbitrary either.

## Sync HTTP layer (phase 5)

`POST /gates/{gateId}/sync` wraps `Reconciler.ingest` with no change to its semantics: the
same no-op-on-resync and order-independence properties hold, now demonstrated over real
HTTP against a real server, not just in-process.

One deliberate choice: **`gateId` comes only from the URL path**, never from the request
body. The request DTO (`ScanRecordRequest`) has no `gateId` field at all, so a single request
can only ever report scans under one identity, the one in the URL. Reconciliation depends on
scans being attributed to the gate that actually made them.

**This is only half of that protection, and the other half is not built.** Nothing
authenticates the caller, so any client can `POST /gates/gate-9/sync` and claim to be gate 9.
Binding the identity in the URL to the caller (a per-gate token, or mutual TLS with the gate
ID in the certificate) is what would actually stop a client manufacturing a fake conflict or
attributing its own duplicate scan to someone else. It is listed under "Not done".

**Testing choice:** the controllers are tested with `MockMvc.standaloneSetup(...)` rather
than `@SpringBootTest`, constructing a fresh in-memory store (`InMemoryScanLog`, introduced
in phase 6; originally a bare `Reconciler`) per test method. A store managed as a Spring
singleton bean would be shared across test methods under Spring's context caching, silently
leaking scan state between tests that have nothing to do with each other.
`standaloneSetup` sidesteps that entirely and boots in milliseconds instead of seconds, at the
cost of not testing the actual Spring wiring: the full-context tests (`HealthControllerTest`,
`ObservabilityIntegrationTest`, `SimulationOverHttpTest`) cover that the real application,
controllers and database-backed store included, boots and works together.

## Persistence (phase 6)

Before this phase, restarting the server lost every scan ever synced — `Reconciler` held
its state in a plain in-memory `Set`. The fix was designed to touch `Reconciler` as little
as possible, because it was already correct and already tested: `Reconciler` itself has
zero changes in this phase, and all 11 of its existing tests pass unmodified.

Instead, a `ScanLog` interface was introduced between the HTTP layer and storage:

- `InMemoryScanLog` — a thin wrapper over `Reconciler`, used by `SyncControllerTest` and
  anywhere durability isn't needed. Zero database, milliseconds to construct.
- `JpaScanLog` — backed by a real database (H2, file-mode). On every read, it loads every
  persisted scan and feeds it into a **brand new** `Reconciler`, then calls `reconcile()`.
  This only works cleanly because `reconcile()` was already designed as a pure function of
  whatever has been ingested — adding persistence didn't require adding any new concept to
  the domain layer, just a place to load the input from.

The database's own unique constraint (`gate_id, ticket_id, scanned_at, accepted`) mirrors,
at the storage layer, the exact same identity `ScanRecord`'s value equality already gives
the in-memory path — the no-op-on-resync property holds at both layers, for the same
reason.

**Verified for real, not just in a test:** synced two gates' scans (including a conflict)
into a running server, force-killed the process (`kill -9`, not a graceful shutdown), started
a brand new JVM against the same database file, and confirmed `GET /reconciliation`
returned byte-for-byte the same report. A unit test proves the repository behaves correctly
in isolation; it doesn't prove a real restart doesn't lose or corrupt anything, which is
the actual thing "persistence" is supposed to guarantee.

**Shortcuts, named rather than hidden:**
- **`ddl-auto: update`** instead of versioned migrations (Flyway/Liquibase). Reasonable at
  this scale and stated explicitly in `application.yml`'s own comments; a real deployment
  would not let Hibernate infer schema changes from entity classes.
- **Inserts are one statement per scan.** `GenerationType.IDENTITY` prevents JDBC batching.
  (Phase 6 originally also checked existence one scan at a time; phase 8 replaced that with
  one lookup per gate, see below.)

**What phase 6 got wrong, found in phase 8.** This phase's crash-and-restart check was
real, but it used whole-second timestamps and one request at a time, so it could not have
shown either of two bugs that the simulation later found in exactly this code: a scan whose
timestamp is finer than the database column stopped matching itself when re-sent, and two
concurrent uploads of overlapping scans made one of them fail with an HTTP 500. Both are
fixed and described under phase 8. The lesson is that a check passing is evidence only about
the cases it exercised.

**Testing choice, and why it differs from phase 5's:** `JpaScanLogTest` uses
`@DataJpaTest`, which wraps each test method in a transaction rolled back afterward — the
officially supported way to get per-test isolation against a real database, in contrast to
`SyncControllerTest`'s `standaloneSetup`, which sidesteps Spring's context entirely. Both
solve the same problem (state leaking between tests via a shared, cached Spring context)
but by different means, because one is testing the database interaction itself and the
other explicitly isn't.

## Metrics (phase 7)

Prometheus metrics at `/actuator/prometheus` (Micrometer). They are added by wrapping any
`ScanLog` in `MeteredScanLog`, wired as the `@Primary` bean, so neither the storage classes
nor `Reconciler` know metrics exist and both stay framework-free.

| Metric | Meaning |
|---|---|
| `gatekeeper_scans_received_total{outcome}` | every scan in every sync request, including ones already stored |
| `gatekeeper_scans_stored_total` | only the scans that were new |
| `gatekeeper_store_conflict_retries_total` | batches re-run after losing a race to a concurrent upload |
| `gatekeeper_store_seconds`, `gatekeeper_reconcile_seconds` | how long storing a batch and reconciling everything take |
| `gatekeeper_conflicts_last`, `gatekeeper_accepted_tickets_last` | as of the most recent reconciliation |
| `gatekeeper_scans_held` | scans currently stored |
| `http_server_requests_seconds` | request latency per route, as a histogram |

The gap between *received* and *stored* is retry and overlap traffic, the number to watch if
gates are re-sending too much. The `_last` gauges are snapshots from whenever someone last
asked for a reconciliation, not live values: computing them fresh on every scrape would mean
a full reconcile per scrape.

**Nothing is labelled by gate ID or ticket ID.** Both come from clients, so labelling by them
would let a client create unbounded metric series. A test asserts that no meter carries such a
label, and another that a scrape after syncing from `gate-77` contains no trace of it. Request
latency is labelled by route template (`/gates/{gateId}/sync`), so its cardinality is bounded
by the number of routes.

A naming mistake caught before it shipped: a gauge called `gatekeeper.scans.stored.total`
would have collided in Prometheus with the counter `gatekeeper.scans.stored`, because
Prometheus appends `_total` to counters. The gauge is `gatekeeper.scans.held`.

The endpoint is unauthenticated, like the rest of the API.

## Partition and load simulation (phase 8)

Full write-up, method and measurements are in [simulation.md](simulation.md). In short: real
`Gate` objects with skewed clocks play an event offline, upload their logs in overlapping,
repeated, shuffled batches (in-process, through the database, and over real HTTP with
concurrent gates), and reconciliation is checked against an independent restatement of the
rule computed from the gates' own logs. It also asserts that the set of conflicts only ever
grows as uploads arrive, and demonstrates that *which gate wins* a conflict can still change
until every gate has synced. That is the operational caveat that follows from "earliest wins
plus a delayed upload": a conflict verdict is final only once every gate has uploaded its
whole log. Nothing in the system enforces or signals that point today.

**It found two real bugs**, fixed here:

- **Timestamp precision broke idempotency.** Real clocks give 100 ns (Windows) or 1 ns
  (Linux); the database column keeps microseconds and rounds. A stored scan no longer matched
  its own resend, so the resend failed with a constraint violation. The fix is to make a scan's
  identity canonical where every record is created: `ScanRecord` truncates `scannedAt` to
  milliseconds. Found by suspecting it, writing the failing test first, and seeing it fail.
- **A race between concurrent uploads of the same scans** returned HTTP 500 to the loser
  (2 to 5 failed requests per run at 4 to 8 concurrent gates). The unique constraint kept the
  data correct, but the request failed. `JpaScanLog.store` now looks the whole batch up in one
  query per gate and, if a batch loses a race, runs it again in a fresh transaction, at which
  point the winner's rows are committed and skipped. This is optimistic concurrency rather
  than a lock, so it does not depend on there being a single server process, although it was
  only exercised against one. The retry needs `store` to start its own transaction, as the HTTP
  layer's calls do; called inside a caller's transaction a conflict can only be propagated.
  The deterministic regression test failed with the real constraint violation before the fix
  (an earlier version of that test failed for the wrong reason, a Mockito limitation, which is
  why it was rewritten rather than trusted).

**Testing lessons repeated by mistake and corrected:** two new test classes first shared a
Spring context and so leaked state between methods (metrics counters, a server that keeps the
scans it is given). Both now use a fresh context per method. This is the same class of problem
phases 5 and 6 avoided in other ways, and I walked into it twice anyway.

## Not done

- **Authentication.** Nothing verifies who is calling. The gate ID in the URL is not tied to
  the caller's credentials, so a client can claim to be any gate; the reconciliation views and
  the metrics endpoint are open too. See the note under phase 5.
- **Logical/hybrid-logical clocks** instead of wall-clock `scannedAt` for conflict ordering.
- **Signalling that a conflict verdict is provisional.** The winner can change until every
  gate has synced; the API does not say which gates have.
- **`Gate`'s own local log is still in-memory only.** The server side persists; a real gate
  device would need its own local durability (e.g. SQLite) so a scan isn't lost if the device
  loses power before it can sync.
- **Incremental reconciliation.** Every read reloads and reconciles the whole history: linear,
  measured at 0.3 to 0.5 s for 55k to 109k scans.
- **Batched inserts** (a sequence-based id plus JDBC batching), so a large upload is not one
  `INSERT` per scan.
- **Per-attempt identity for rejected scans.** Two rejections of the same ticket at the same
  gate in the same millisecond collapse into one record. Harmless to reconciliation, but the
  audit trail is not exact. A per-gate sequence number would fix it.
- **Tested against more than one server process.** The retry-on-conflict design should hold
  (the unique constraint is the arbiter), but every run here used one.
- **Real hardware and real networks.** The simulated gates run the real `Gate` class but not on
  real devices.
