# Artemis Browser regression procedure — first draft

Revision 0.2 · 2026-09-30 · **Design draft; not an executed test report.** The lab's P0 is
implemented (`test-lab/`); only `LAB-SMOKE` is runnable, so every case below still needs its recipe.

Companion: [Phase 15 plan](Phase%2015%20plan.md). The proposed lab controls and scenario names below
are requirements for the test services, not controls that exist today. Startup commands, final
labels and provisioning details must be filled in and verified as those services are implemented.
Existing integration tests supply useful recipes but do not constitute execution of this procedure.

## Baseline and result rules

Current coverage is Phases 1–14 (Phase 13 P3–P6 and Phase 14 P1–P5 merged by 2026-09-30). Use
the commit actually under test to confirm availability. Cases F01–F08 were written as deferred
when those features were planned; they have shipped, so record them **Blocked** (no lab recipe
yet) until P3 supplies fixtures — never Deferred, never a pass. Never mark a missing fixture as a pass.

For each case record **Not run, Pass, Fail, Blocked, or Deferred**, actual result, evidence links,
execution time, operator and defect reference. Pass requires both the verified broker precondition
and the Browser observation. Use Blocked for an unavailable lab recipe/environment and Deferred
only for a feature outside the tested release, with a reason. Repeat applicable cases for both
images in the supported matrix; a result on one version is not a result on the other.

## Run sheet (copy for each broker version)

| Field | Value to record |
|---|---|
| Run ID / date / operator | |
| Browser commit and build | |
| Lab version / recipe revision / seed | |
| Broker image tag, digest, reported version and node ID | |
| Browser URL / lab URL / broker endpoint | |
| Configuration profile and all overridden limits | |
| Browser client/time zone; broker/lab clock offsets | |
| Owned resource manifest / generated message IDs | |
| Unit/integration result locations | |
| Case ID / status / actual result / evidence / issue | One row per executed case |
| Cleanup result / remaining resources | |

## Preparation

1. Build the Browser revision under test. Record the results of `mvn test` and, with Docker
   available, `mvn clean verify -Pintegration`. The latter runs the pinned broker matrix. These are
   existing commands.
   Start the lab with `mvn -f test-lab/pom.xml spring-boot:run` and open `http://localhost:8082`;
   `mvn -f test-lab/pom.xml clean verify -Pintegration` checks the lab itself on both images.
2. Provision an **owned disposable** broker through the lab (*Provision broker*), selecting a
   pinned supported version. Check readiness, endpoint (`127.0.0.1:62616`) and node ID. Do not connect the lab writer to an existing cluster broker.
   Confirm that two simultaneous lab connections report that same broker identity.
3. Launch Browser using the normal README instructions, with a separate temporary remembered-
   connections file for this run. Record configuration, including body, search, export and in-flight
   limits. Defaults referenced below must be rechecked against the tested revision.
4. Connect Browser to the lab's displayed endpoint. Verify broker identity/version on `/broker`.
   Displaying a queue whose name starts with `lab.` alone is not adequate target verification.
5. Create a run prefix `lab.<runId>.`; names below are suffixes under that prefix unless otherwise
   specified. Start from clean fixtures. Do not run conflicting traffic during a static case.
6. For each row: prepare its scenario, wait for the specified state with a bounded deadline, perform
   the Browser actions in order, record expected versus actual, then use its cleanup instruction.
   Always retain failure evidence before resetting.

Proposed recipe aliases: **BASIC**, **BODIES**, **SEARCH**, **DELIVERY**, **SUBSCRIPTIONS**, **RATES**,
**BEHAVIOR**, **PRESSURE**, **FAILURES**, **SCALE**. They are catalog names, not runnable commands.
Unless a row says otherwise, cleanup means stop workers and reset that case's owned fixture.
For asynchronous counts compare settled observations, not the instant a send method returned.

## Connection, overview and page navigation

| ID / feature | Lab condition and Browser steps | Expected result / evidence | Cleanup |
|---|---|---|---|
| C01 Connect and remember (P1–2) | BASIC: empty broker; try wrong credentials, then correct credentials at `/`; disconnect, reconnect from saved location; forget it. | Failure is useful and exposes no password; correct connection works; saved host/port/user survive as intended, password is absent from stored JSON and not repopulated; forget removes the entry. Capture redacted persistence evidence. | Disconnect; remove only the run's temporary connection file. |
| C02 Session isolation/expiry (P1–2) | Connect in two independent browser sessions; disconnect one. In a separate short-timeout Browser profile, allow the other session to expire. | One session's disconnect does not disconnect the other; expired/disconnected pages return to connection flow; no stale authenticated broker use. | Restore normal timeout. |
| C03 Overview (P2/5/12) | BASIC: empty queue plus queues with distinct waiting/delivering/scheduled/added/acked/expired/killed counts. Visit `/overview`, sort each offered column twice, filter names/address and nonempty state, toggle internal visibility. | Order and displayed counters match independent lab readings; toggles affect the intended resources; the Browser's reply queue is excluded as designed. Record selected rows and sort direction. | Reset. |
| C04 Refresh/navigation (P2/9) | BASIC then bounded traffic: select refresh off and each supported interval, navigate queue/address/client links and every main page; use names containing spaces, `&` and Unicode in a dedicated fixture. | Off stays idle; enabled pages refresh at the selected interval without losing selected controls; names are escaped/encoded and links reach the right resource; pages render without 500s. | Stop traffic; reset. |

## Browsing, bodies, filters and exports

| ID / feature | Lab condition and Browser steps | Expected result / evidence | Cleanup |
|---|---|---|---|
| M01 Empty and paged queues (P1–2/12) | BASIC: 0, 1 and 251 persistent waiting messages with sequence properties. Browse at size 50 through first/next/previous/last; repeat at another offered size. | Empty state is honest; 251 static messages yield six pages at 50 with one final row, no missing/duplicate IDs; properties/headers are visible. Scheduled/delivering messages are tested separately, not counted as browsable rows. | Reset. |
| M02 Core filters (P2/5/7) | SEARCH: 300 messages, only positions 251–260 have `marker='late'`; distinct priority/durability/timestamps. Filter `marker = 'late'`, then priority, durability and timestamp using the page's documented core names; try malformed syntax and `JMSPriority = 4`. | Late matches are found beyond the broker's first 200; filtered navigation makes no false exact-total claim; malformed syntax is explained; UI explicitly calls the dialect core and does not promise JMS aliases work. Keep filter and returned IDs. | Reset. |
| M03 Body types/detail (P2/4) | BODIES: text, bytes, map, stream, harmless object and empty body; include typed properties, Unicode, quotes, newlines and literal HTML. Open each detail page. | Supported text/bytes/map representations agree with sent data; stream/object bodies have explicit unsupported placeholders; no object deserialization or HTML execution. Headers/properties remain readable. | Reset. |
| M04 Long and large messages (P5) | BODIES: 1,000-character text plus 250KB text/bytes, recording broker large-message threshold; open list, detail, search and export. | List previews are bounded; qualifying messages carry large badges; detail/body limits and truncation are honest; content below limits is not replaced by the broker's 256-character management preview. | Reset. |
| M05 Cross-queue search (P3/5/11) | SEARCH: matching properties on two queues; copy a known exact ID; search all queues and follow each queue link; repeat for an absent ID and for more than the per-queue match cap. | Matches and queue links are correct; capped results say at least/limited, not exact; absent results disclose unsearched states. Record exact ID form and core expression used. | Reset. |
| M06 Queue/search exports (P3–5) | BODIES + SEARCH: export filtered queue and cross-queue results as CSV and JSON. Compare IDs/properties and full supported bodies to the send manifest; parse both files. | Only the requested scope is exported; CSV identifies queue, JSON groups as implemented; quoting handles delimiters/newlines; long supported bodies are recovered within limits. Export is a fresh read, not a frozen earlier screen. | Reset. |
| M07 Export bounds (P5) | Relaunch a dedicated Browser profile with export rows 5, body scan 3, per-message body 100 chars and total body budget 150 chars. Use ten long-body messages; place a match beyond the scan limit; export queue and multi-queue search. Isolate each bound in separate subruns. | Row cap applies across search queues; scan/body budgets terminate; unrecovered/shortened bodies are flagged, never presented as complete. Record all overrides and row/body measurements. | Restore default profile; reset. |
| M08 Export content safety (P5) | BODIES: values beginning `=`, `+`, `-`, `@`, with commas/quotes/newlines in fields. Export CSV and inspect raw text without enabling spreadsheet formulas; export JSON. | Each CSV field is quoted and dangerous leading characters defused; JSON remains valid and faithfully represents the intended data. | Reset. |
| M09 Single download/stale result (P6) | Download one message as text and JSON. Separately let a lab consumer acknowledge a previously displayed message, then reopen its detail/download link. | Download identifies correct message/body; missing message is explained without an unrelated body or 500. | Close consumer; reset. |

## Scheduled, in-flight, redelivery and removal states

| ID / feature | Lab condition and Browser steps | Expected result / evidence | Cleanup |
|---|---|---|---|
| D01 Scheduled (P6/12) | DELIVERY: 5 waiting plus 3 messages due in five minutes. Inspect queue and scheduled panel; later wait for delivery time with no consumers. | Initially browse shows 5, scheduled panel 3 with times; pager excludes scheduled messages. After the deadline all 8 become waiting, subject to bounded broker settling. Search does not imply scheduled bodies were searched. | Reset after transition. |
| D02 Held deliveries (P11/12) | DELIVERY: receive 10 messages without acknowledgment on an identified consumer; retain its session. Inspect queue, exact-ID search, client link; acknowledge explicitly in lab and refresh. | Delivering list reports held IDs grouped under the right consumer, without bodies; waiting browse excludes them. Exact-ID lookup finds supported ID forms; after acknowledgment delivering falls and acked rises. Record before/after counters. | Close session; reset. |
| D03 All in flight and budgets (P11/12) | DELIVERY: hold all 250 messages; verify empty browse has no false extra pages. Then use a separate low `artemis.in-flight-limit` profile (e.g. 5), hold 6, and create enough other held queues to exceed Diagnose's aggregate budget. | Consumers/counts remain visible when listing is skipped; ID search and Diagnose state coverage/limits, never claiming complete absence. Capture both per-queue and aggregate omissions. | Close consumers; restore profile. |
| D04 Consumer imbalance and age (P11/13 P2) | DELIVERY: one consumer holds at least 10, another holds none; separately hold a message whose actual send time is over ten minutes old. Visit Diagnose. | Imbalance/old-message findings name the evidence and client where available; age is from send time, not asserted time held. Do not forge age by editing the Browser clock. | Roll back/close holders; reset. |
| D05 Redelivery and DLQ (P7/12) | DELIVERY: explicit DLQ address/queue and low max-delivery-attempts; repeatedly roll back one message until the broker dead-letters it. Observe between attempts and after terminal failure. | Redelivery metadata/counters reflect broker evidence; source killed count and DLQ content/origin are visible where reported; Diagnose points to the backlog. No Browser operation retries it. | Stop redelivery; reset source and DLQ. |
| D06 Expiry (P12) | DELIVERY: TTL messages routed to an expiry address; stop consumers, wait for the broker expiry scan; inspect source/destination and counters. | Source expired count increments, expiry content is inspectable and destination links work. Acknowledgment rate does not count expiry as consumption. | Reset. |
| D07 Missing DLQ/expiry destinations (P12) | Repeat D05/D06 with no configured destination, configured-but-absent address, and address with no bound queue; disable relevant auto-creation in the recipe. | Killed/expired increments are still shown; Diagnose explains each current routing gap and does not claim today's settings prove historical fate. Verify the missing topology before asserting the finding. | Reset each variant. |

## Addresses, subscriptions, clients and rates

| ID / feature | Lab condition and Browser steps | Expected result / evidence | Cleanup |
|---|---|---|---|
| A01 Routing/FQQN (P3/10) | SUBSCRIPTIONS: anycast queue with a different address name; multicast feed with two queues whose names differ from the address. Send six messages before observation. Open `/addresses`, each address and each queue. | Address grouping/routing and fan-out are correct; both multicast queues expose their own six messages via FQQN, not an apparently empty bare address lookup. | Reset. |
| A02 Subscription types/filter/lag (P10) | Create unfiltered durable, filtered durable (`region='eu'`), shared durable and live non-durable subscribers before publishing alternating eu/us messages. Keep one durable offline and drain another. Inspect address and subscription search. | Kinds/selectors and consumers are shown as available; expected subsets/backlogs differ; waiting age reflects oldest waiting message, not subtraction of added counters; offline durable can be identified. Search stays within the address's queues. | Close live subscriptions then reset. |
| A03 Diverts and unrouted (P10/7) | SUBSCRIPTIONS: compare exclusive and nonexclusive filtered diverts to owned targets; separately send to an address with no matching queues and auto-create disabled. Inspect address links and Diagnose. | Divert source/target/filter/type/exclusivity match actual routing; exclusive diversion is explained; unrouted/no-queue conditions are reported from evidence. | Reset. |
| A04 Broker/client drilldown (P3/4/12) | RATES: keep identified and anonymous producers/consumers open; add multiple sessions; view `/broker`, producer rows, `/client` links and in-flight queue links. | Health units/version/acceptors are plausible and match independent readings; clients show correct connections/sessions/resources; anonymous clients remain navigable; management clients are labelled. | Close all workers; reset. |
| A05 Rates and changing traffic (P12) | RATES: first inspect in a fresh HTTP session, then produce at a bounded target rate; add slower then faster acknowledging consumers; stop all traffic. Record two readings per stage and actual successful operation counts. | First sample is unavailable, subsequent rates use shown elapsed intervals; backlog grows/shrinks as observed. Ack rate counts acknowledgments only; idle interval approaches zero. Compare measured deltas, not an exact requested send rate. | Stop/settle; reset. |
| A06 Rate discontinuities (P12) | RATES: collect a baseline, intentionally restart the owned broker or recreate an owned queue so counters reset; reconnect Browser as needed. | No negative/fabricated throughput is shown; fresh sessions need a baseline and reset intervals are invalidated as implemented. Record lifecycle and sampling times. | Reset. |

## Queue behavior and address pressure

Run behavior variants separately; settings are verified effective values, not only requested inputs.

| ID / feature | Lab condition and Browser steps | Expected result / evidence | Cleanup |
|---|---|---|---|
| Q01 Pause/no consumer/browser-only (P7) | BEHAVIOR: enqueue 5 with no consumer, then attach only a JMS QueueBrowser; separately pause a populated queue using lab management. Inspect counts and Diagnose for each state. | Findings distinguish no consuming client, browsers only, and paused delivery. Browser actions do not resume queues. | Close browser client, resume in lab, reset. |
| Q02 Last value/ring (P13 P2) | Send 5 updates with the same last-value key to an LVQ; send 10 to a ring of size 3, no consumers. Inspect retained IDs, settings and counters. | LVQ retains latest value and ring retains final 3 once settled; settings explain loss; replacement/eviction is not invented as acknowledgment or killed counts. | Reset. |
| Q03 Non-destructive/purge (P13 P2) | Non-destructive queue: receive/ack 3 then close. Purge-on-no-consumers queue: enqueue/hold 3 while a consumer is attached, then disconnect the final consumer. | Non-destructive content remains; Browser distinguishes unavailable effective non-destructive setting from address default. Purge empties queue and killed count rises; Diagnose's explanation accounts for purge. | Reset. |
| Q04 Exclusive/group/dispatch gate (P13 P2) | Two consumers on exclusive queue; two on grouped queue with two group IDs; one on queue requiring two consumers before dispatch. Observe, then add gate's second consumer. | Settings and distribution explain idle consumers; group ownership alone is not a fault; gated queue starts dispatch when requirement is met. Record consumer IDs and deliveries, not assumed perfect balance. | Close workers; reset. |
| P01 PAGE policy (P13 P1) | PRESSURE: small valid max-size/page-size settings (existing recipe uses 20KB/10KB); send bounded 1KB bodies until actual pages exist. Inspect address, broker and Diagnose. | Usage, thresholds, policy/page count have correct units; paging with pages is visible; normal PAGE behavior is not asserted to be producer blockage. | Stop, drain/reset. |
| P02 BLOCK/FAIL/DROP (P13 P1) | On separate low-limit addresses, run bounded producers under each policy. Record timeouts/refusals/success counts independently; view pressure and Diagnose. | Policy-specific consequences are explained with inferred labels where appropriate; FAIL/DROP `paging=true` with zero pages is not labelled disk paging; successful DROP sends do not prove retention. | Cancel producers, unblock/drain/reset; prove workers stopped. |
| P03 Operator block (P13 P1) | Block an owned address with ample capacity via lab management; inspect it; unblock. | Explicit management block is observed evidence, distinguishable from inferred full-policy behavior; unblocking clears the observed state. | Unblock then reset. |
| P04 Global memory/disk health (P3/7/13 P1) | Use an isolated broker profile with safely reduced global memory and disk threshold; bounded traffic for memory. Obtain disk threshold condition without filling the physical disk. Observe broker and Diagnose. | Health percentages/limits and affected addresses are credible; warnings reflect observed thresholds. If a reliable safe disk recipe is unavailable, mark that subcase Blocked and retain automated coverage evidence separately. | Restore/recreate owned broker. |

## Availability, safety, deployment and scale

| ID / feature | Lab/harness condition and Browser steps | Expected result / evidence | Cleanup |
|---|---|---|---|
| E01 Partial permissions (P13 P0) | FAILURES: full operator and restricted viewer with management RBAC configured at broker startup; deny acceptors, disk usage, diverts/address reads as in `PartialAvailabilityIT`. Connect Browser as viewer. | Remaining panels still render; denied/unavailable fields are not zero/false/empty success. Operations distinguish denial when possible; ambiguous attribute failures say unavailable. | Reconnect full viewer; reset. |
| E02 Required failure/unsupported (P13 P0) | User without management permission cannot list queues; separately use verified unsupported optional-read fixtures. | Required failure is clear; unsupported optional reads leave other panels usable. If the pinned real brokers expose no such unsupported operation, exercise the automated parsing/rendering fixture and label its provenance. | Restore user/profile. |
| E03 Lost connection/recovery (P6) | Connect, then stop owned broker while navigating and refreshing; restart it and reconnect. | Connection-loss explanation/connection flow replaces stale data; requests are bounded by configured timeouts; explicit reconnect restores pages. | Ensure workers reconciled after restart. |
| E04 Read-only guarantee (all) | Use settled persistent non-TTL messages, no moving/scheduled traffic, no expiry; include a separately held unacked set whose consumer stays connected. Capture counts/IDs/acked/delivering before; repeatedly visit every read page, search, export and download; capture after. | Business waiting IDs and counts, acked and held deliveries are unchanged. Ignore Browser's own management traffic/reply resources in comparison. Retain independent before/after evidence and `ReadOnlyGuaranteeIT` results. | Only then close holders/reset. |
| E05 Login/Host/TLS (P7) | Harness: loopback without login; configured login with bad/good password and logout; hostile Host header; missing CSRF token on protected POST; non-loopback startup without auth/TLS; valid auth+TLS profile. | Intended local access works; auth/session protections and Host/CSRF refusals work; unsafe external binding fails startup; valid secure profile works. Do not expose test credentials over a public endpoint. | Restore local profile. |
| E06 Container/Helm (P8–9) | Dedicated lab deployment: package Browser normally; install chart with auth/TLS and seeded test connection; verify probes, login, ingress, refresh and restart through actual deployment URL. | Pod readiness and TLS/login work; no login loop; single replica; startup configuration correct. Rebuild/restart uses expected image; reconnect after process restart is honest about lost sessions. | Uninstall only owned release/resources. |
| E07 Scale and responsiveness (P7/11–13) | SCALE: small preset first, then 100,000 bounded small messages, many queues/clients/subscriptions and held deliveries within resource budget. Exercise overview, late-match filters, search, export, client/address and Diagnose. | Results remain correct and bounded; capture latency, response size and Browser/lab/broker memory plus limits. Set performance budgets before execution; do not invent a universal latency pass threshold. Excess reads are disclosed. | Stop all workers; reset and check resource release. |
| E08 Lab safeguards (P15) | Attempt wrong broker identity, invalid/out-of-range parameters, double Start, reset during a worker, blocked-send cancellation, lab restart and partial cleanup failure. | Wrong target performs no mutation; jobs remain bounded; duplicate requests do not duplicate data; interrupted work/cleanup failures are visible and recoverable; unrelated broker/resources are untouched. | Reconcile exact ownership manifest. |

## Shipped features awaiting lab recipes

Written as reserved coverage before these features shipped; all have now merged. Record them
Blocked until P3 provides fixtures.

| ID / planned feature | Required fixture and future assertion |
|---|---|
| F01 Trends (P13 P3) | Bounded growth/drain/idle/gap/restart timeline; verify honest timestamps, history bounds and counter discontinuities. |
| F02 Snapshot export (P13 P4) | Healthy and partially denied/budget-limited runs; verify schema, collection window, completeness, bounded/redacted evidence and no default bodies/credentials. |
| F03 HA/connectivity (P13 P5) | Owned multi-broker live/backup plus bridge/federation profiles; interrupt a path and fail over; compare local reported topology to provisioned state and expose unknowns. |
| F04 XA/permissions (P13 P6) | Prepared XA left unresolved and controlled role grants/denials; inspect reported metadata without commit/rollback or permission changes from Browser. Lab owns resolution. |
| F05 Snapshot comparison (P14 P1) | Before/after settings and backlog changes; same/different broker identities, incompatible schema, omissions, restart and recreation; reject or qualify invalid comparisons. |
| F06 Unified ID investigation (P14 P2) | Same investigation across waiting/scheduled/in-flight and transitions, partial denial and budgets; verify state-labelled observations and explicit coverage. |
| F07 Guided/saved searches (P14 P3) | Quoted/typed values, time-zone boundaries, save/reopen/rename/delete, missing scope and broker context change; compare generated core filters to known fixture matches. |
| F08 Triage/message comparison (P14 P4–5, optional) | Heterogeneous/absent DLQ origin metadata, bounded samples and paired supported/truncated/unsupported bodies; preserve uncertainty and sample limits. |

## Execution order, evidence and completion

Smoke subset: C01, C03, M01, M03, M05, M06, D01, D02, A01, A04, E03 and E04. A smoke pass is
not a full regression pass. Full runs execute all current cases, with RBAC, deployment and scale
profiles recorded separately. Run E04 in a stable fixture, not during rate/expiry/pressure tests.

For each failure save the exact case/recipe revision, configuration, generated IDs, lab action log,
independent broker readings, Browser screenshot/export and relevant redacted logs. Record timing
and poll deadlines for asynchronous failures. Reproduce in a clean run before attributing a failure
to the Browser; fixture errors and product defects are separate results.

At completion stop producers/consumers, reconcile held transactions, capture final evidence, remove
only owned resources, and disconnect Browser. Verify no lab worker or unintended resource remains.
Archive the run sheet and manifest. Report totals by version for every status, list unresolved
defects/blocked coverage, and record who accepted any exception. No blank cases or deferred future
features count toward a current-feature pass.

## Procedure maintenance

When a recipe ships, replace the proposed control names with verified navigation and startup
instructions; record its readiness predicates, deadlines, seed and precise settings. Keep IDs stable
and split additions with suffixes if necessary. Link actual evidence and update version-specific
expectations only after observation. Add new shipped features to the baseline and move their future
cases into the appropriate execution group. Maintain a feature-to-case review at each release.

| Revision | Change | Execution status |
|---|---|---|
| 0.1 — 2026-09-29 | Initial current-feature catalog, proposed fixtures, expected observations, cleanup and future coverage | Not run; lab services pending |
| 0.2 — 2026-09-30 | Baseline moved to Phases 1–14; F01–F08 are shipped features awaiting recipes (Blocked, not Deferred); lab P0 start/verify commands | Not run; only LAB-SMOKE runnable (E08 partial) |
