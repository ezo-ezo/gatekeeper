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

## Not done

- **HTTP sync endpoint.** `Reconciler` is a pure library right now; a gate has no way to
  actually deliver its log to it yet. Next phase.
- **Logical/hybrid-logical clocks** instead of wall-clock `scannedAt` for conflict ordering.
- **Persistence.** Both `Gate`'s log and `Reconciler`'s scan set are in-memory.
- **Load/partition simulation:** gates going offline mid-event, then reconnecting under
  load, the way Evenr's `scripts/bench.sh` exercises upstream failure scenarios.
- **Metrics** on conflict rate, sync lag, and gate clock drift observed in practice.
