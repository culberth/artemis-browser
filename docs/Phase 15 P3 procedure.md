# Phase 15 P3 — current-feature fixtures and harness

Recipe revision **2026-10-01.6**. Case IDs F01–F08 remain unchanged. This supplement
provides executable setup for the current features. Fixture assertions, automated Browser
tests and a human's Browser observations are different evidence; never substitute one for another.
The full procedure's completion gate still applies.

Start the lab with `mvn -f test-lab/pom.xml spring-boot:run`, provision one of the two pinned
images, then create a run. Each new manifest includes all 50 cases as **NOT_RUN** for both
fixture and Browser. Run each card below from that run's page; subsequent controls appear only
after successful preparation. A duplicate submission token is still handled by the existing job
runner. Use a fresh run to repeat a recipe, and download its manifest before cleanup.

## F01 — trends

Run **INCIDENT**. The owned `lab.<runId>.incident` queue starts empty. Open Browser's `/overview`, follow the incident queue link to its Recent trend panel,
and collect a baseline, then use **Add ten messages** and refresh after the configured sample
spacing (15 seconds by default). The readiness assertion is count 10. Use **Drain up to ten**:
the actual acknowledged count is recorded and the remaining count asserted. Refresh through an
idle interval without running a producer. Turn refresh off to create a sampling gap; turn it back
on and verify no samples were invented inside the gap.

Use **Recreate incident queue** and refresh: the manifest records the old and new queue IDs.
Trends must break at recreation. Use **Restart broker** for the restart variant; the broker must
retain its node ID and Browser must require honest reconnection/baselines. To inspect history
bounds quickly, start Browser with `--artemis.trends.max-readings=3` and collect more than three
eligible samples. RATES supplies continuously bounded growth/drain when needed. Automated
counter reset and timestamp cases live in `TrendsIT`, `RateTrackerTest` and `QueueTrendTest`.

## F02 / F05 — snapshots and comparison

On INCIDENT, download `/snapshot` JSON and text before growth; retain the collection window,
schema, node ID and completeness. **Add ten messages**, then **Pause incident queue**, and
download a second snapshot. Upload them to `/compare`: backlog and paused-setting changes must
be shown for the same queue identity. **Resume incident queue** before draining. Repeat around
**Recreate incident queue** and **Restart broker**; deltas must be qualified at discontinuities.

For denied reads, provision **Restricted users**, run FAILURES, and connect Browser as `viewer`
(test password `viewer`). The snapshot must retain denial/unavailability beside successful
sections. For bounds start Browser with `--artemis.snapshot.max-rows=1
--artemis.snapshot.max-address-settings=1 --artemis.snapshot.max-address-permissions=1`;
omitted rows and sections must be disclosed. Restore the defaults afterwards.

For rejection cases, keep an unmodified snapshot and make a clearly labelled synthetic copy
with a different node ID or incompatible schema, using the paths and example schema pinned by
`SnapshotJson`, `SnapshotReaderTest` and `SnapshotComparerTest`. These are synthetic parser
tests, not a broker changing identity. `SnapshotIT` and `SnapshotComparisonIT` provide the live
broker assertions. Do not include default message bodies, properties or credentials in snapshots.

## F03 — topology, bridges and failover (external harness)

Run from the Browser repository root:

```powershell
mvn clean verify -Pintegration '-Dit.test=ConnectivityIT'
```

This owns a disposable Testcontainers network, replication primary/backup and peer for each
supported image. The backup uses `quorum-size=1` for this bounded failover demonstration;
this small fixture does not validate production quorum or network-partition behavior. See the
[Artemis quorum documentation](https://artemis.apache.org/components/artemis/documentation/latest/network-isolation.html)
for the additional brokers required for legitimate quorum testing.
It seeds five messages into a working bridge and three into an unreachable
bridge. It asserts replica synchronization, topology addresses, cluster peers, bridge counters,
connected mirror and disconnected AMQP sender, redacted snapshots and unchanged counters after
reads. It stops its backup and verifies the primary's loss-of-sync report, then recreates the
backup, waits for synchronization, crashes its primary, and explicitly reconnects Browser's read
service to the promoted backup. The logical node ID and three unforwarded messages must survive.
All containers and the network are removed by the harness. No shared cluster is used.

This is automated service evidence, not a lab web control or an observed UI failover. It exercises
AMQP mirror/sender connectivity; it does **not** claim a dedicated federation traffic fixture.
Record any required federation-specific demonstration as Blocked until one exists. Browser's
local topology report is never proof of complete cluster health.

## F04 / F06 — prepared XA, permissions and exact IDs

Run **INVESTIGATION**. Read the exact JMS IDs from its send manifest:

| Queue suffix | Independently asserted condition |
|---|---|
| invest-waiting | 1 waiting message |
| invest-scheduled | 1 scheduled message, eligible five minutes after send |
| invest-held | 1 delivery held by the investigation holder |
| invest-xa | 1 prepared receive, delivering with no consumer |
| invest-xa-target | 1 uncommitted send in that same XA branch |

Look up each ID in `/search`, checking per-state observations, timestamps and queue/state
coverage. Look at `/transactions` and address permissions. Branch identity is exactly
`lab.<runId>.xa-branch`, format ID 4242, branch qualifier `lab-1`; the connection is closed after
prepare. Browser reads must not commit or roll it back. The uncommitted send is recorded as such
and must not be treated as an ordinary waiting message.

Use the worker's **Stop** action to return its held receive, then search the same ID again.
In a fresh fixture use **Acknowledge** instead and verify it disappears without claiming a reason
from absence alone. Use **Roll back prepared branch** to return the XA receive and discard the
XA send. Cleanup also rolls back that exact owned branch before deleting any queues; failure
blocks cleanup. A lab restart does not adopt the old broker or resume the branch; remove the
labelled disposable leftover through the lab's existing reconciliation flow.

On the Restricted users profile, repeat Browser reads as `viewer`; prepared-transaction and
permission reads must say denied/unavailable, not empty. Use Browser's documented
`artemis.investigate.*` settings to force small preflight budgets; retain explicit unsearched
coverage. `TransactionsIT`, `MessageInvestigationIT`, `PartialAvailabilityIT` and their unit tests
cover the independent metadata, budget and permission assertions on the matrix.

## F07 / F08 — guided filters, saved searches, triage and comparison

Run **INVESTIGATION-CONTENT**. On `guided`, build and run these core filters:

| Filter | Whole-queue expected matches |
|---|---:|
| `owner = 'O''Brien'` | 2 |
| `amount >= 30 AND enabled = TRUE` | 1 |
| `eventTime >= 1790812800000` | 3 |

The last field is an explicit epoch-millisecond **message property**, centered on
2026-10-01T00:00:00Z; it is not JMS send time. For the builder's timestamp control use the actual
send timestamps and verify its displayed time zone/core epoch syntax. Save with a name, reopen,
rename and delete under `/saved`; opening alone must not run it. Save a scoped filter before
cleanup and retry afterwards to observe missing scope; connect to a newly provisioned broker
to observe changed context. Use a separate temporary saved-search file for the run.

`compare` has five messages in send order: JSON `{a:1,b:2}`; equivalent reordered JSON; changed
JSON plus `typed` changing from integer 7 to string "7"; 1,000-character text; an unsupported
object body. Use their recorded IDs at `/message/compare`. Ignore expected differences in JMS
IDs/timestamps when judging body equivalence. Set `--artemis.message-compare.max-body-chars=100`
for a truncation variant; incomplete bodies must not be claimed equal. After cleanup the old
IDs must be unavailable. INVESTIGATION's held message supplies the in-flight unavailable variant.

`triage` contains one direct message without origin metadata and two messages genuinely
dead-lettered from `triage-a` and `triage-b` after their first delivery rollback. Each source has
count zero and killed count one; target count is three. `/triage` must show two recorded origins
and unknown origin, never an invented failure reason. Choose a sample of one to show incompleteness.
DELIVERY supplies real expiry metadata. `DeadLetterTriageIT`, `MessageComparisonIT`, guided-filter
and saved-search unit/controller tests provide automated counterparts.

## Evidence and release gate

Run `mvn -f test-lab/pom.xml clean verify -Pintegration` to verify all recipes and cleanup on
both pinned versions, and the Browser's normal `mvn clean verify -Pintegration` release suite.
Committed machine-readable suite summaries are in `evidence/phase-15-p3/`; counts represent
automated tests, not the 50 procedure cases. Raw reports remain under each project's `target/`.

The complete manual two-version procedure, E06's owned Helm deployment, and any required
federation-specific demonstration must retain their own results. A full P3 milestone is not
certified by adding fixtures or by passing these suites. Preserve the E02 required-failure defect
until it is resolved or explicitly accepted with an issue reference.
