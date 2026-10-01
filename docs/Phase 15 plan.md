# Phase 15 — Interactive regression lab

Status: specified 2026-09-29; **P0 merged 2026-09-30** (PR #50) as the standalone Maven
project `test-lab/`; **P1 merged 2026-09-30** (PR #52); **P2 merged 2026-09-30** (PR #55). P3 is not
started.

## Purpose

Use a web interface to create repeatable conditions on disposable Artemis test brokers, then
observe and regression test every implemented Artemis Browser feature. The lab must support both
guided demonstrations and recorded test runs. The first procedure is in
[Phase 15 regression procedure.md](Phase%2015%20regression%20procedure.md); keep its case IDs stable
as the servers, controls and broker fixtures are refined.

The baseline is the current code: Phases 1–14, including Phase 13 P3–P6 and Phase 14's optional P4
and P5, all merged by 2026-09-30. When this plan was drafted (2026-09-29) only Phases 1–12 and
Phase 13 P0–P2 had shipped, so cases F01–F08 were written as deferred; they now cover shipped
features and are **Blocked** (no lab recipe yet, planned for P3), never Deferred or passed. A test
for an unimplemented Browser feature is deferred, not a pass or a regression. Reconcile this
baseline with the commit under test on every release.

## Architecture and boundaries

Artemis Browser remains read-only. Build a separate **Artemis regression lab** application with its
own executable, web port, configuration and write-capable broker client. Do not add lab controllers,
write operations or credentials to the Browser artifact or its management allowlist. A standalone
Maven project under `test-lab/` is the proposed location; it is not part of the normal production
package. Reuse fixture recipes and verified response shapes from the existing integration tests,
not the production read channel for writes.

| Service | Responsibility | Controls and evidence |
|---|---|---|
| Lab web/controller | Scenario catalog, bounded jobs, lifecycle, run records and links into Browser | Prepare, start, stop, step, inspect, reset owned resources, download run manifest |
| Fixture service | Create addresses, queues, routing, policies, diverts and permissions on an owned broker | Versioned recipes; record effective settings and resource names after creation |
| Producer service | Send deterministic messages or bounded traffic | Count, rate, duration, body type/size, properties, priority, persistence, schedule and TTL; record successful sends and IDs |
| Consumer service | Produce observable delivery states | Start/stop, acknowledge, hold unacknowledged, rollback/redeliver, drain, slow consumption; identify connections/sessions/consumers |
| Broker lifecycle service | Start, stop, restart and reset disposable brokers | Pin image/version; readiness; node ID; process/container ownership; logs and endpoint |
| Evidence service | Compare setup expectations with independently observed broker state | Counters, identities, timestamps, bounded snapshots and operator results; no production secrets |

These are logical services; the initial implementation may keep them in one lab process with
separate classes and worker lifecycles. Separate containers are useful for brokers and fault
isolation, not a requirement to turn every service into a deployment.

Initially provision one disposable local broker at a time. Use the supported matrix in `pom.xml`
(currently `apache/artemis:2.55.0-alpine` and `apache/artemis:2.57.0-alpine`) as the compatibility
baseline. Host port 61616 is already used here: propose 62616 for the lab broker and 8082 for the
lab UI, detect conflicts rather than silently choosing a different target. Browser remains at its
configured URL (normally 8080). Show distinct host and container endpoints when relevant. Multi-node
HA/bridge/federation profiles are a later fixture increment tied to the corresponding Browser work.

## Isolation and lifecycle requirements

- Bind the initial web UI and broker's published ports to loopback. Retain CSRF protection, validate
  Host headers and use POST for changes. Any later shared deployment needs authentication and TLS.
- The first implementation may write only to brokers it provisioned. No arbitrary production
  hostname or URL field. An externally managed test broker requires a separately designed opt-in
  ownership mechanism before support is enabled.
- Give each broker and run an identity; give all owned resources a run prefix such as
  `lab.<runId>.`. Retain an exact ownership manifest. Before mutation verify the configured endpoint
  and broker node identity, including each new worker connection; reject an unexpected identity.
  Set `useTopologyForLoadBalancing=false` on every factory. Reconcile an intentional broker
  replacement explicitly; never silently accept a new node ID after a failed read.
- Cleanup acts on exact manifest resources or the owned disposable container/volume. A prefix is
  a naming convention, not sufficient deletion authority. Never enumerate and delete unrelated
  queues. Stop workers and release held deliveries before cleanup; display their effects first.
- Bound count, size, duration, active workers and total generated bytes, with conservative presets
  and enforced server-side maxima. Pressure tests lower broker limits rather than filling the host
  disk. Enforce cancellation and send/receive timeouts, including BLOCK-policy producers.
- Use one JMS session per worker, never concurrently from multiple request threads. Record actual
  sends, receives, commits, rollbacks and failures, and close all resources on completion/cancel.
- A stopped producer is not necessarily a settled broker. Readiness predicates must verify fixture
  state, use deadlines, and expose failures. Do not make a fixed sleep the success criterion.
- Serialize conflicting actions for a run. Duplicate form submissions must not duplicate jobs or
  silently add messages. Reset creates a new run or restores the documented baseline and proves it;
  it is distinct from stopping traffic or rolling back a held consumer.
- Persist a bounded run manifest and results without passwords. A lab restart marks interrupted
  jobs accurately and reconciles owned resources before offering resume or cleanup. No automatic
  unbounded workload resumes on startup.

## Web workflow

1. Select the broker version and provision the lab. Show readiness, node identity and Browser
   connection details. Connect Browser separately; never place passwords in links.
2. Choose a scenario card. Show prerequisites, resource names, limits, expected observations and
   linked regression case IDs. Keep presets usable without understanding Artemis management APIs.
3. Prepare the fixture and wait for its readiness assertions. Show exact counts, send IDs and core
   filters with copy controls. Open the corresponding Browser page with encoded resource names.
4. Start or step the scenario, inspect actual progress, then observe Browser. For held deliveries,
   acknowledge and rollback are separate labelled actions; record their different consequences.
5. Record Pass, Fail, Blocked or Not run with notes and evidence. A successful seed is not a Browser
   test pass. Automatic fixture assertions and human Browser assertions have separate results.
6. Stop, settle, capture evidence and reset. A failed cleanup remains visible and blocks reuse of
   that fixture until reconciled; it must not turn the run green.

Each run records lab recipe revision, Browser commit/version/configuration, broker image/version
and identity, run ID, seed, timestamps/time zone, owned resources, parameters, action history,
generated JMS/core IDs where available, readiness evidence, actual outcomes and per-case results.
Downloads must identify partial data and redact credentials. Human evidence may include screenshots
and downloaded Browser exports. Body fixtures are synthetic, never copied production payloads.

## Implemented (P0, 2026-09-30)

Selected values for the open decisions this plan listed:

| Decision | Selected |
|---|---|
| Location and packaging | `test-lab/`, its own Maven project (`artemis-regression-lab`), not a module of the root pom; `LabIsolationTest` in Browser pins that |
| Lab UI | `http://localhost:8082`, bound to 127.0.0.1; `LoopbackOnlyGuard` refuses any other bind; Host check and CSRF on; session cookie `ARTEMISLAB_SESSION` |
| Brokers | Testcontainers, one at a time, pinned images from `lab.broker.images` (default the pom matrix), labelled `artemis-lab.broker=<id>`, 61616 published on **127.0.0.1:62616** (`lab.broker.port`); a taken port is refused, never moved |
| Identity | node id recorded after two concurrent connections agree; every later connection checked by `TargetGuard` before use; `useTopologyForLoadBalancing=false` and `callTimeout` on the factory URL |
| Run records | one JSON manifest per run under `lab.data-dir` (default `~/.artemis-lab/runs`), at most `lab.limits.max-runs` (50); oldest finished runs pruned; no password field |
| Worker limits | `lab.limits.*`: 1,000 messages per action, 256KiB bodies (raised from 64KB in P1 so `BODIES` can send 250,000-byte large messages), 2 workers, 50MB per run, 10s operations, 30s readiness — each under a hard ceiling in `LabLimits` |
| Restart | STARTED actions without an outcome become INTERRUPTED; open runs become BROKER_GONE; labelled containers the process did not start are listed as leftovers (removable only if still labelled); nothing resumes |

Start it with `mvn -f test-lab/pom.xml spring-boot:run` (Docker required). The one runnable
scenario is `LAB-SMOKE`: an owned anycast queue `lab.<runId>.smoke`, recorded PLANNED only after
the broker is seen not to have it, then a bounded deterministic send and a `messageCount`
assertion with a deadline. Every other catalog card is listed but refuses to run.

## Implemented (P1, 2026-09-30)

Merged in PR #52. All six P1 recipes are runnable — `BASIC`, `BODIES`,
`SEARCH` (one-shot), and `DELIVERY`, `SUBSCRIPTIONS`, `RATES` (with workers). Their exact contents
are on the catalog page and in each recipe's Javadoc.

- **Workers** are clients that outlive the job that started them, owned by a run: held consumers
  (CLIENT_ACKNOWLEDGE on a `consumerWindowSize=0` factory, so they hold exactly what they
  received), live non-durable subscribers, idle identified/anonymous clients, and traffic threads
  at a bounded rate for a bounded time. Bounded by `lab.limits.max-live-workers` (10),
  `max-rate` (200/s) and `max-traffic` (5m). The run page acknowledges or releases held messages;
  cleanup, broker stop and broker restart stop every worker first.
- **Steps** are follow-up actions a recipe offers once it has run: *Roll back once* (D05),
  *Start producer / consumer*, *Stop traffic*, *Recreate rate queue* (A05/A06).
- **Restart broker** (lab page) restarts the container in place and accepts it only if it comes
  back with the same node id; otherwise it is stopped, not adopted.
- **New owned kinds**: diverts and address settings for exactly one owned address (never a
  wildcard), removed in cleanup — diverts first, then queues, addresses, settings.
- **Verified broker behaviour this needed** (recorded in `.claude/memory.md`): dots inside a JMS
  client id or subscription name are backslash-escaped in the subscription queue's name, so
  durable subscriptions use client id `lab-<run>` (queue `lab-<run>.durable-all`); a JMS send to
  a queue named after an address with a differently named anycast queue auto-creates a queue of
  that name and splits the messages, so `orders` is sent by FQQN. Both were caught by ownership —
  an unexpected queue failed the recipe and blocked cleanup, rather than passing silently.

- **Recipes** implement `Recipe` and build with `Fixture` (owned queue creation, guarded `Sender`s,
  one-shot consumption, readiness assertions read from the broker) — so every recipe inherits the
  P0 guarantees. The registry must equal the catalog's runnable cards or the lab does not start.
- **Send manifest**: every accepted message is recorded in the run manifest (queue, `labSeq`, kind,
  JMS message id, size, role), bounded at 2,000 with the rest counted — what M05/M06 compare
  Browser's results and exports against. Every message carries `labRun` and `labSeq`.
- **Assertions** use management counters and, for selections, a JMS `QueueBrowser` over the whole
  queue — never a filtered `countMessages`, which looks at the first 200 only.
- **Priority order**: Artemis keeps a queue in priority order, so `SEARCH`'s late markers take
  priority 0 (everything else 1–9) to sit at browse positions 291–300; with priority `seq % 10`
  the first late marker browsed 26th, defeating the case.

Verified 2026-09-30: `LabBrokerIT` 9/9 on 2.55.0 and 2.57.0, checking each recipe independently
of the lab, a restart, and that cleanup leaves no queue, address or divert with the run's name; live, Artemis Browser showed BASIC's counters (9 / 2 scheduled / 12 added / 3 acked),
six pages of `paged` with one row on the last, the odd name encoded in links, escaped HTML and
intact Unicode in BODIES, large badges, the four formula values neutralised in CSV, and SEARCH's
filters at 10 / 31 / 150 / 50 with cross-queue search listing both late queues and a capped floor
for `search-many`. For the worker recipes, Browser showed the in-flight panel and holder client,
Diagnose's three distinct dead-letter explanations (none configured, missing, no queue) and
expiry with no address, the never-acknowledging consumer, every subscription kind with its filter,
the exclusive and non-exclusive diverts, three unrouted on `nowhere`, client pages by id, and a
rate of exactly 20/s while the lab produced 20/s.

Verified 2026-09-30: `LabBrokerIT` on 2.55.0 and 2.57.0 (provision and identity, wrong target
refused with nothing created, smoke count asserted independently, cleanup proven, taken port
refused, container removed); live, a double-submitted smoke form produced one job and 40
messages, and Artemis Browser connected to the lab broker showed the lab's node id on `/broker`
and the smoke queue with 40 on `/overview`.

## Implemented (P2, 2026-09-30)

Merged in PR #55. Every current procedure case now has a runnable recipe or a
written harness procedure.

- **BEHAVIOR** (Q01–Q04) and **PRESSURE** (P01–P03) — ported from Browser's `QueueBehaviorIT` and
  `AddressPressureIT`, which verified them on both broker versions. Steps resume/pause, add the
  gate's second consumer, and unblock/block the operator-blocked address.
- **Broker profiles** at provisioning, for conditions only startup configuration can make: *Restricted
  users* (`management-message-rbac` with test users `viewer` and `nomanage`, passwords as names),
  *Low global memory* (global-max-size 2MB) and *Disk threshold reached* (max-disk-usage 1%, already
  exceeded — nothing fills the disk). A replacement entrypoint creates the instance once and edits
  it, so a restart or interrupt keeps the configuration. A recipe that needs a profile is refused on
  any other broker.
- **FAILURES** (E01, E02) proves, as each test user, exactly what the profile refuses; **Interrupt
  broker** (E03, lab page) stops the container for chosen seconds and accepts it back only as the
  same node. **LOW-LIMITS** (P04) reads global memory or disk against the lowered limit.
- **READONLY** (E04) reads every owned queue's counters as a baseline; *Compare* after using Browser
  lists any change. Its IT shows it passes after browsing and catches a single consumed message.
- **SCALE** (E07) seeds queues × messages from the form, bounded by `lab.limits.max-scale-messages`
  (100,000).
- **E05/E06** are written harness procedures (in the regression procedure), not lab recipes.

Found along the way, recorded in `.claude/memory.md`:

- A job cancelled before its thread started never ran and never finished, blocking its run — fixed,
  with a regression test that fails on the old code.
- RBAC-denied *attributes* answer "Problem while retrieving attribute", like a missing one, so
  FAILURES confirms the admin can read each attribute before counting the viewer's failure as a
  refusal. Disk usage reads 0 until the broker's first periodic disk check.

**Browser defects found** (procedure results recorded in lab runs; each handled as its own task):

| Case | Defect | Status |
|---|---|---|
| Q01 | Diagnose calls a queue whose only client is a browse-only `QueueBrowser` "no consumer attached": the queue's `consumerCount` excludes browse-only consumers, though `/broker` lists it as "browse only". | Fixed: Diagnose now says only browsers are attached (PR #54). |
| E02 | For a user without `manage`, `/overview` explains the refusal correctly but also renders "Queues (0) … This broker reported no queues" — a zero in place of a refused reading. | Fixed: a refused or failed listing reads "Not shown: the broker did not list its queues", with no count; `/addresses` and `/diagnose` had the same pattern and are fixed alike (branch `fix-overview-refused-listing`). |

Verified 2026-09-30: `LabBrokerIT` 11/11 and `LabProfilesIT` 4/4 on 2.55.0 and 2.57.0. Live, Browser
showed the behavior and pressure findings and badges listed in each card (the non-destructive flag is
not readable per queue on these versions, as already recorded), the operator block clearing after
*Unblock*, `viewer`'s denied panel beside the panels that stand, a clear connection-loss path during a
20s interrupt and a working reconnect, and the two defects above.

## Delivery increments

### P0 — Catalog, isolation and repeatable startup

- [x] Scaffold the independent lab executable, disposable broker launcher and web shell.
- [x] Implement target verification, ownership, job bounds, CSRF/Host defenses and safe teardown.
- [x] Define versioned scenario/run manifests and preserve the procedure's case IDs.
- [x] Start each supported broker version, verify all worker connections reach that broker and
      prove that a refused target produces no mutation.

### P1 — Message and client fixtures

- [x] Baseline queues, mixed bodies/properties, long/large bodies, core filters, paging/search caps,
      export limits and formula-like values. (`BASIC`, `BODIES`, `SEARCH`)
- [x] Scheduled messages, held deliveries, controlled acknowledgment/redelivery, dead lettering,
      expiry and missing-destination variants. (`DELIVERY`)
- [x] Bounded producers/consumers, rate transitions, clients with and without IDs, durable/shared/
      non-durable subscriptions, selectors and divert fixtures. (`SUBSCRIPTIONS`, `RATES`)

### P2 — Broker behavior and failure fixtures

- [x] Queue pause, browse-only clients, last-value/ring/non-destructive/purge behavior, exclusive/
      grouped consumers and dispatch gating. (`BEHAVIOR`)
- [x] PAGE/BLOCK/FAIL/DROP policies and explicit operator block, with independently measured results.
      (`PRESSURE`; global limits in `LOW-LIMITS` on a broker profile)
- [x] Restricted permissions, unavailable optional reads, broker interruption/restart and partial
      availability. Where real brokers cannot produce a failure deterministically, label an
      automated test fixture as such; never claim it was a live demonstration. (`FAILURES`,
      *Interrupt broker*; unsupported optional reads stay covered by Browser's automated tests)
- [x] Scale presets and non-destructive observation checks, plus the application authentication,
      host/TLS and deployment checks that require a harness outside broker traffic generation.
      (`SCALE`, `READONLY`; E05/E06 as written harness procedures)

### P3 — Complete current-feature regression and extend as features land

- [ ] Execute all applicable cases in the draft procedure on both supported broker versions.
- [ ] Implement fixtures for the Phase 13 P3–P6 and Phase 14 features, all now shipped (F01–F08):
      trends, snapshots/comparison, topology/HA, bridges, prepared XA/permissions, message
      investigation, guided/saved searches, triage and message comparison.
- [ ] Promote verified instructions from draft to tested procedure; retain case IDs, evidence,
      version-specific expectations and a revision history.

## Acceptance

Phase 15's current-feature milestone is complete when every baseline case has a reproducible lab
recipe or an explicit external harness procedure, every applicable case has been run on the
supported matrix, and failures are resolved or explicitly accepted with an issue reference.
Deferred future features are listed separately and are never counted as passed current coverage.

Verify the lab itself: fixture results against real brokers, rendered controls, duplicate requests,
input bounds, cancellation under blocked sends, cleanup after partial failure, identity mismatch,
restart recovery, and isolation from the Browser artifact. Existing Browser unit and integration
suites remain release checks; manual regression supplements them.

Open implementation decisions: local Docker versus later cluster profiles, restricted-user
provisioning, and how to inject otherwise unreachable optional-panel failures. UI port,
packaging, run-record retention and worker limits were settled in P0 (above). These do not block drafting the catalog/procedure;
record the selected values and verified setup commands before calling a recipe runnable.
