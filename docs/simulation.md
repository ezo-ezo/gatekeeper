# Partition and load simulation

Gatekeeper's claim is that it stays correct when gates are cut off from the network and
reconnect in a mess. This is how that claim is tested, what the tests found, and what the
numbers do and do not show.

## What it does

`dev.gatekeeper.simulation` plays a whole event end to end:

1. **Gates go offline.** A dozen (configurable) real `Gate` objects are provisioned with the
   tickets, each with its own clock that is wrong by up to ±20 s.
2. **People arrive.** Tickets are presented at random moments. Some are shown at more than one
   gate while nobody can tell anybody (resold, relayed, or two doors at once); some are
   scanned twice at the same gate; some show a stale code; some tickets were never issued.
3. **Gates reconnect badly.** Each gate uploads its log in several batches, and every batch is
   the log *so far*, so batches overlap. Some uploads are sent twice, like a retry after a
   timeout. All gates' uploads are shuffled together, so a later batch can arrive before an
   earlier one.
4. **The result is checked.** Reconciliation is compared, ticket by ticket, with what the
   gates' own logs say it should be.

Nothing about the outcomes is scripted. The workload only decides who shows what to which
gate and when; whether a scan is accepted, rejected as a duplicate, or rejected for a bad code
is whatever the gate actually returns. The expected answer is then derived from what the
gates really recorded (`Oracle`), by a plain restatement of the rule that shares no code with
`Reconciler`. Runs are reproducible from a seed.

## Running it

```powershell
mvn test                                     # includes the simulation, in-process and over real HTTP
.\scripts\simulate.ps1                       # starts a throwaway server, drives it over HTTP, stops it
.\scripts\simulate.ps1 -Tickets 100000 -Concurrency 8
.\scripts\simulate.ps1 -SkipBuild -- --shared-rate 0.2 --skew-seconds 5   # extra args go to the CLI
```

Or by hand, in-process (no server needed, checks every ticket) or against a server you started:

```powershell
java -cp target\classes dev.gatekeeper.simulation.SimulationCli --tickets 20000
java -cp target\classes dev.gatekeeper.simulation.SimulationCli --url http://localhost:8080 --concurrency 4
java -cp target\classes dev.gatekeeper.simulation.SimulationCli --help
```

Against a server, the run refuses to start if the server already holds scans (its totals
could not be compared with fresh ground truth) unless `--allow-nonempty`, which skips
verification. `simulate.ps1` uses its own database file and never touches `./data/gatekeeper`.

## What is checked

| Property | How |
|---|---|
| Every ticket's winner and conflicts match the gates' own logs | `Verifier.verify` against the `Oracle`, in-process |
| Same, through the real database-backed store | `PersistentSimulationTest` |
| Same, over real HTTP with concurrent gates | `SimulationOverHttpTest`, and `simulate.ps1` |
| **Upload order does not matter** | two different shuffled schedules and a plain in-order upload produce *equal* reports |
| **Re-sending is harmless** | overlapping and duplicated uploads: more records are sent than there are distinct scans (1.3× to 1.7× in the runs below), and the result is still exact |
| **Nothing is ever taken back** | after every single upload, the set of accepted tickets and of conflicts only grows |
| Each gate is itself sane | never accepts a ticket twice; its log never goes backwards in time |
| Reproducible | the same seed replays identical scans and uploads |

Two of these deserve a note.

**Winners can change until every gate has synced.** The *set* of conflicts only ever grows,
but *which gate wins* a given conflict can flip while uploads are still arriving, because a
scan that happened earlier can reach the server later. `aConflictsWinnerCanChangeUntilEveryGateHasSynced`
demonstrates this on the simulated event instead of leaving it as a warning. The practical
rule: a conflict verdict is final only once every gate has uploaded its whole log.

**Gates with clocks far out reject genuine tickets.** That is not a reconciliation problem
(the run still passes and stays consistent), but with ±5 minutes of skew the simulated venue
rejected several times as many valid codes as with ±20 s. Reconciliation being correct does
not make the venue usable; gate clocks still need to be kept close to true.

## Is the test itself any good?

A verifier that cannot fail proves nothing, so:

- **Mutation check.** I temporarily changed `Reconciler` so the *latest* scan wins instead of
  the earliest, and re-ran the tests: 12 failures across `PartitionSimulationTest`,
  `PersistentSimulationTest` and `ReconcilerTest`. I restored the file and confirmed it was
  byte-identical to the committed version. This was done once, by hand, for one mutation. It
  is not an automated mutation-testing setup.
- **The verifier is tested directly:** dropping one accepted scan from every upload is
  detected and named; wrong totals are detected; a gate that accepted a ticket twice, or whose
  log runs backwards, is detected.
- **Vacuity guards.** Each run asserts that it actually produced conflicts, same-gate
  duplicates, stale codes, forged tickets and overlapping uploads. A run that quietly
  exercised nothing would fail rather than pass.

**What the oracle cannot catch:** it encodes the *same* policy (earliest scan wins), so it
tests the mechanics (merging, deduplication, order-independence, persistence, concurrency),
not whether "earliest wins" is the right policy. That trade-off is discussed in
`design.md`.

## What it found

Two real bugs in the product, both invisible to everything written before, and one in the
tool itself.

**1. A resent scan could stop matching itself.** Real clocks are finer than the database
column: Windows gives 100 ns, Linux 1 ns, the H2 column keeps microseconds, and the database
*rounds*. A scan with a nanosecond-precision timestamp was stored rounded, so when the gate
sent the identical scan again the "already stored?" check missed it, the insert hit the unique
constraint, and the request failed with a 500 and rolled back its batch. I suspected this while
designing the simulation, wrote a test first, and confirmed it failed with exactly that
constraint violation. Fix: `ScanRecord` canonicalises `scannedAt` to milliseconds where every
record is created, so gate log, JSON and database always agree. The earlier crash-and-restart
demonstration could not have shown this: it used whole-second timestamps.

**2. Concurrent uploads of the same scans returned HTTP 500.** Two requests carrying
overlapping scans (a gate's retry arriving while its first attempt was still being processed)
both checked "already stored?", both got "no", and both inserted. The unique constraint
stopped the duplicate, so nothing wrong was ever stored, but the losing request failed. The
first HTTP runs showed it: **2 failed requests in a 5,000-ticket run at 4 concurrent gates, 5
in an 8,000-ticket run at 8**. The server log gave the cause (`Unique index or primary key
violation: UQ_SCAN_IDENTITY`) and a deterministic regression test forces the exact
interleaving and failed for the same reason before the fix. Fix: look up the whole batch in
one query per gate instead of one query per scan, and if a batch loses a race, re-run it in a
fresh transaction (by then the winner's rows are committed, get skipped, and the batch
completes). Retries are counted in `gatekeeper_store_conflict_retries_total`, so the collisions
are visible even though they no longer surface as errors.

**3. The CLI crashed on a bad `--batch`.** Found by a test; a usage error surfaced as a stack
trace. Fixed by validating every option up front.

## Results

Machine: Intel Core i5-13420H (8 cores / 12 threads), 16 GB, Windows 11. **The load generator
and the server ran on the same machine.** The database is H2 in file mode. Seed 42, 12 gates,
batches of up to 1,000 scans. Each row is a single run.

**In process** (`InMemoryScanLog`, 20,000 tickets): 21,856 scans, 1,113 tickets accepted at two
or more gates. Every ticket's winner and conflicts matched the oracle. Ingest 8 ms, reconcile
47 ms.

**Over HTTP, current code:**

| Tickets | Distinct scans | Records sent | Requests | Concurrent | Failed attempts | Server-side conflict retries | Wall time | Records/s | Median request | Summary fetch |
|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| 20,000 | 21,856 | 33,796 | 43 | 4 | 0 | 1 | 2.6 s | 13,058 | 119 ms | 182 ms |
| 50,000 | 54,547 | 94,776 | 107 | 8 | 0 | 0 | 3.5 s | 26,878 | 133 ms | 333 ms |
| 100,000 | 109,282 | 142,185 | 159 | 8 | 0 | 2 | 5.5 s | 25,831 | 191 ms | 478 ms |

All three: server totals matched the gates' own logs exactly (scans, tickets accepted, and
conflicts). Concurrency 8, 16, 16 and 32 at 8,000 tickets with different seeds also gave zero
failed attempts.

**Before the race fix** (same seed, same machine):

| Tickets | Concurrent | Failed attempts | Records/s |
|---:|---:|---:|---:|
| 5,000 | 4 | 2 (HTTP 500) | 2,737 |
| 8,000 | 8 | 5 (HTTP 500) | 3,899 |
| 8,000 (after the fix) | 8 | 0 | 8,750 |

The fix changed two things at once (one lookup query per gate instead of one per scan, and
batches that no longer fail and get re-sent), and I did not measure them separately, so I
cannot say how much of the speed-up is due to which. These are single runs; read it as
"roughly twice as fast", not as a precise figure.

### How to read these numbers

- **p95 and p99 are not reported here on purpose.** With 43 to 159 requests, they are just the
  slowest request or two, and the slowest are the first ones (cold JVM, first use of
  Hibernate). The median is the figure to trust. `simulate.ps1` prints p50/p95/p99/max anyway.
- **Reconciliation is linear in the amount of data.** Every `GET /reconciliation*` reloads
  every scan and reconciles from scratch: 0.32 s at 55k scans and 0.46 s at 109k scans
  (worst single call, from the server's own timer). Extrapolating linearly, about 4 s at a
  million scans. That extrapolation was **not** measured. Fine for one venue, and the reason a
  dashboard should poll `/reconciliation/summary` rather than the full report; a much larger
  deployment would need incremental reconciliation.
- **Inserts are still one statement per scan.** `GenerationType.IDENTITY` prevents JDBC
  batching. Switching to a sequence with batch inserts is the obvious next speed-up and was
  not done.
- **The simulated gates are code, not hardware.** They exercise the same `Gate` class, but
  real devices add their own failure modes (power loss mid-write, real network behaviour).
- **Over HTTP only totals are compared**, not each ticket. Equal totals cannot rule out two
  errors that cancel out. The per-ticket comparison runs in-process and through the database.
- **Identical rejected attempts collapse.** Two rejections of the same ticket at the same gate
  in the same millisecond are indistinguishable and stored once. Only rejected scans are
  affected; accepted scans (which decide everything) are unique per gate and ticket. A
  per-gate sequence number would give every attempt its own identity.
