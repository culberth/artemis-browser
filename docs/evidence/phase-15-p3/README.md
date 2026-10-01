# Phase 15 P3 execution evidence

Executed 2026-09-30 (America/Los_Angeles; manifests use UTC on 2026-10-01), on branch
`codex/phase-15-p3`, based on `a77567b2bbc932fc2b09fc33d0d0b1de572372a2`.

| Evidence | Scope |
|---|---|
| [Browser release checks](browser-release-checks.json) | Full baseline: 644 unit tests, 109 integration tests per broker version; zero failures/errors |
| [Lab matrix](lab-integration-checks.json) | 12 LabBrokerIT + 4 LabProfilesIT tests per version, including the new P3 fixtures and exact XA cleanup; zero failures/errors |
| [Lab unit checks](lab-unit-checks.json) | 37 passing tests, including XA target refusal, error propagation and rendered controls |
| [Expanded connectivity checks](connectivity-checks.json) | 9 passing tests per version, including primary crash, promotion and retained node identity/backlog |
| [Lab fixture records](lab-fixtures.json) | Two P3 runs per version: node identities, recipe revision, generated JMS IDs, assertion history and exact resources after successful cleanup |

The fixture records were extracted from `test-lab/target/p3-evidence/`; the integration test now
retains full run manifests there after cleanup. All 50 fixture/Browser procedure statuses start
NOT_RUN. These records deliberately do not turn automated fixture assertions into operator passes.
Suite summaries retain no credentials or raw broker logs.

The first failover attempt used graceful shutdown and did not activate the backup. A crash attempt
then exposed the small fixture's insufficient default quorum (backup logs showed repeated voting
timeouts). The final harness explicitly sets backup `quorum-size=1` and crashes only its owned
primary after verifying synchronization. This setup tests promotion and explicit Browser
reconnection; it is not a production quorum or partition-safety test.

## Procedure status

No complete human two-version run is claimed by this change: **50 Not run cases per version**
in that separate execution category, including partially automated cases. Automated checks above
are supplementary evidence. E06's owned Helm deployment and a dedicated federation traffic
demonstration remain unexecuted; the earlier E02 defect remains unaccepted/unresolved in the plan.
No exception has been accepted on the operator's behalf. The P3 milestone therefore remains open.

Use the [P3 procedure](../../Phase%2015%20P3%20procedure.md) and the original regression procedure
to finish that acceptance work, retaining separate fixture and Browser results.
