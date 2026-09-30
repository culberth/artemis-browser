## Phase 13 P1 — Address pressure and storage detail

### Context

P0 (merged, PR #29) made every broker value a `Reading<T>` and isolated optional panels. P1 is the first feature on top of it: say **why producers to an address are stalled, failing or losing messages**.

Today the only per-address pressure data is `addressSize` and a `paging` badge from `listAddresses`, shown as raw bytes with no unit (`address.html:43`, `addresses.html`). The policy (`addressFullMessagePolicy`, `maxSizeBytes`) sits in a different panel ("Where else its messages go", `address.html:191`) as display strings. Diagnose has only broker-wide disk/memory findings with no address link (`StuckDiagnosisService.brokerLevel`, L289). And `AddressDirectory.number()` (L179) still turns a missing `addressSize` into 0, which P0's rule forbids once we compute utilization from it.

Done (PRD L650): a user can name the affected address, see the limits and policy behind its behavior, and tell normal paging apart from an observed block or a risk of rejecting/dropping messages. This is checked against real 2.55.0 and 2.57.0 brokers.

Work on a new branch from `main`: `phase13-p1-address-pressure`.

### Step 0: Verify the reads before parsing anything (standing P0 rule)

Against `apache/artemis:2.55.0-alpine` and `2.57.0-alpine` (test-broker recipe in .claude/memory.md, on port 62616), record:

- **`listAddresses` fields beyond the seven parsed today.** Candidates are `numberOfPages`, `addressLimitPercent`, `numberOfBytesPerPage`, `paused` and any blocked flag. Note whether they are quoted, and which version has which.
- **`address.<name>` attributes.** Candidates are `numberOfPages`, `addressLimitPercent`, `paging`, `addressSize`, `numberOfBytesPerPage`, and the management-block state (look for an `isBlocked`-style getter next to `block()`/`unblock()`). Record the value types.
- **Broker attribute `globalMaxSize`** (and `globalMaxMessages` if it exists). Record the units, and how `-1` is reported.
- **`getAddressSettingsAsJSON` paging keys.** `pageSizeBytes`, `pageLimitBytes` and `pageLimitMessages` are already in the fixture. `pageFullMessagePolicy` and `maxSizeMessages` still need checking, including whether they are absent or `-1` at their defaults.
- **Behavior under each policy.** For each of PAGE, BLOCK, FAIL and DROP, on an `it-…` address with a small `maxSizeBytes` set through `addAddressSettings` (the pattern in `KilledAndExpiredIT.manage`):
    - Fill the address past its limit.
    - Record `paging`, `addressLimitPercent`, the number of pages, and what the producer experiences.
    - Record the blocked state after a management `block()` issued by the test only, never by the app.
    - Check whether `addressLimitPercent` means anything when `maxSizeBytes = -1` (bound only by `global-max-size`).

Append the shapes to .claude/memory.md under a new dated "Address pressure" section **before** writing parsers. Anything a version lacks becomes `UNSUPPORTED`/`UNAVAILABLE`, not zero. If the blocked state is not readable at all, drop that field and note it in the PRD rather than inferring it.

### Step 1: Model

- **`AddressOverview`** (broker/AddressOverview.java): add `Reading<Long> numberOfPages`, `Reading<Long> limitPercent` and `Reading<Boolean> blocked`, from the listing when Step 0 shows they are there. Turn `addressSizeBytes` into `Reading<Long>`. Keep the existing convenience constructor so tests and callers keep compiling.
- **`AddressDirectory`**: a `reading(node, field)` helper next to `number()`. A field that is absent becomes `missing(UNSUPPORTED, "not in this broker's address listing")` and a malformed one becomes `failed`. Leave the `listQueues` counters as they are, as P0 deliberately did.
- **Only for the detail page, and only for what the listing lacks**: add `AddressDirectory.pressure(name)` → per-attribute `Reading`s from `management.attribute(ResourceNames.ADDRESS + name, …)`. Attributes need no allowlist change. If Step 0 shows an operation is needed, add it to `ManagementChannel.READ_OPERATIONS`; it must match the `list|get|browse|count|is` rule.
- **`AddressSettings`** (broker/AddressSettings.java): add numeric accessors next to the display strings.
    - `Limit` is a small record with three states: `NOT_SET` (key absent), `UNLIMITED` (`-1`) and `bytes`. It is returned by `maxSizeBytesLimit()`, `maxMessagesLimit()`, `pageLimitBytes()` and `pageLimitMessages()`.
    - `FullPolicy` is an enum `PAGE/BLOCK/FAIL/DROP`, plus `consequence()` text.
    - `pageFullPolicy()`.
    - The existing `maxSize()`/`maxMessages()` strings stay.
- **New `AddressPressure` record** (in `broker/`) joins usage to policy for one address: overview, settings and optional detail attributes. It also carries `BrokerHealth` for the global memory figures.
    - `state()` is one of `NORMAL`, `PAGING` (normal under PAGE), `NEAR_LIMIT`, `AT_LIMIT` or `BLOCKED_BY_MANAGEMENT` (observed).
    - `utilization()` is `Reading<Double>`. It prefers the broker's `addressLimitPercent` and otherwise uses `addressSize / maxSizeBytes`. It is `NOT_COLLECTED` when the limit is unset or unlimited, so the address is bound only by the global limit.
    - `consequence()` is the policy text for when the limit is reached: "pages to disk (normal)", "producers block", "sends fail", "messages dropped silently". There is a separate line for the page limit and its `pageFullMessagePolicy`.
    - Threshold: `NEAR_LIMIT` at ≥ 80%, matching `BrokerHealth.memoryPressure()`.
- **`BrokerInfoService.health()`**: read `globalMaxSize` as another isolated `Reading` in `BrokerHealth`, so memory can be shown as "used / limit". A broker attribute refusal is cached by the channel already.

### Step 2: Address pages

- **`address.html` header**:
    - Size gets a unit and a label: "address memory (estimated)".
    - Add stats for pages, limit utilization (a bar or percentage, with `missing(...)` when not available) and a blocked badge. The blocked badge is a warning; paging stays a neutral badge.
- **New "Storage and limits" panel** under the header, built from `AddressPressure`. It puts usage beside policy in one table:
    
    - address memory against `maxSizeBytes`
    - messages against `maxSizeMessages`
    - pages against `pageLimitBytes`/`pageLimitMessages`
    - the full policy and its consequence
    - global address memory against `globalMaxSize`, as context and not as a cause
    
    A short note sets the terms:
    
    - Address size is Artemis's in-memory estimate.
    - Persistent size on the subscriptions is journal bytes.
    - Disk use is on `/broker`.
    - Paging under PAGE is normal.
    
    Show "not set" (broker default) and "unlimited" separately, as the settings table already does. The panel is collected via `Reading.attempt` and shows `collected(...)`, so a refused read leaves the rest of the page intact.
- **`AddressDetailService.routing()`**: convert the old `settingsError` try/catch to `Reading<AddressSettings>`, which the new panel shares. Keep `AddressRouting`'s accessors, so the template changes stay small.
- **`addresses.html` index**: a compact utilization/blocked badge, from listing fields only. No per-address calls on the index.

### Step 3: Diagnose

- **`Finding`**: add a `basis` field, `OBSERVED` (the default) or `INFERRED`, with an overloaded constructor so every existing call site stays unchanged. `diagnose.html` renders it as a small "observed" or "inferred" badge beside the severity.
- **New `StuckDiagnosisService.addressPressure(...)`**, called beside `addressLevel`. It reuses the address listing already read, and the per-run settings cache pattern from `nowhereToGo` (L439–457). To stay bounded, it reads settings only for addresses that are paging, blocked, or at ≥ 80% utilization. The findings:
    - **Blocked by management**: `STUCK`, `OBSERVED`, linked to the address.
    - **At or near the limit under BLOCK**: `STUCK` when ≥ 100% (`INFERRED`: "producers are likely blocked"), `WATCH` when ≥ 80%.
    - **At or near the limit under FAIL or DROP**: `STUCK` or `WATCH`, `INFERRED`, stating the risk of rejected or silently dropped sends.
    - **Paging and near the page limit**: `WATCH` with the `pageFullMessagePolicy` consequence.
    - **Paging under PAGE with no page limit**: no finding. Paging is normal.
    - A settings read that fails goes to `unchecked` once, the same way `nowhereToGo` handles it.
- **Global memory finding** (`brokerLevel`, L302): when it fires, append the top three addresses by `addressSize` as "largest contributors", worded explicitly as not the sole cause. Link the largest one through `address`.
- Update the "nothing wrong" wording in `diagnose.html:29` to cover address limits too.

### Step 4: Tests

- **Unit tests**, using recorded JSON from Step 0:
    - `AddressDirectoryTest`: new listing fields, both absent (older shape) and malformed.
    - `AddressSettingsTest` (new, or extend `AddressRoutingTest`): the three `Limit` states, and parsing of `FullPolicy`.
    - `AddressPressureTest`: each state, utilization from the percentage or computed, and not-collected when the limit is unlimited.
    - `BrokerInfoServiceTest`: `globalMaxSize`, including refused and garbage values.
    - `StuckDiagnosisServiceTest`: a finding per policy, no finding for normal paging, the observed vs inferred basis, settings reads only for flagged addresses (`verify(never())`), the contributors on the global finding, and the unchecked line.
- **`PageRenderingTest`**:
    - the address page with the pressure panel, and with a refused pressure read
    - the blocked badge
    - the index badge
    - a diagnose finding with the inferred badge
- **New `AddressPressureIT`** on both matrix versions, one `it-press-*` address per policy (PAGE, BLOCK, FAIL, DROP) via `addAddressSettings`, filled past a small `maxSizeBytes`:
    - Assert the page model and the diagnose findings.
    - Management-block one address from the test and assert the `OBSERVED` finding.
    - For BLOCK, send with a timeout or async producer so the test does not hang.
- **`PartialAvailabilityIT`**: deny one address attribute or settings read to `viewer`, and assert the panel says so and diagnose lists it under unchecked.
- **`ReadOnlyGuaranteeIT`**: already opens every address and runs diagnose, so it covers the new reads. Confirm the press addresses' counters are unchanged.

### Step 5: Docs

- **docs/PRD.md**: tick the P1 items, with a "Done …" note on what Step 0 found.
- **docs/architecture.md**: add an "Address pressure" subsection under _Addresses versus queues_ covering the three measures that must not be conflated, the observed vs inferred basis, and why settings are read only for flagged addresses.
- **`README.md`**: P1 status in the Phase 13 section, a diagnose caveat, and the new files in the Layout, adding the missing `Reading`/`Availability`/`ManagementRefusal` there too.
- **.claude/memory.md**: the shapes from Step 0 (before the code), plus any traps hit.
- **`CLAUDE.md`**: the test counts and a Phase 13 status line.

### Critical files

- broker/AddressDirectory.java, `AddressOverview.java`, `AddressSettings.java`, `AddressDetailService.java`, `AddressRouting.java`, `BrokerInfoService.java`, `BrokerHealth.java`, `StuckDiagnosisService.java`, `Finding.java`; new `AddressPressure.java`
- templates/address.html, `addresses.html`, `diagnose.html`, fragments/layout.html (reuse `missing`, `panelMissing` and `collected`)
- Tests: `AddressDirectoryTest`, `AddressRoutingTest`, `BrokerInfoServiceTest`, `StuckDiagnosisServiceTest`, `PageRenderingTest`, `PartialAvailabilityIT`; new `AddressPressureIT`

### Verification

1. `mvn test`: all unit tests and `PageRenderingTest` green.
2. `mvn clean verify -Pintegration`: every IT green on 2.55.0 and 2.57.0.
3. Manual check against the local test broker on port 62616:
    - Create a small-limit BLOCK address and a PAGE address and fill them.
    - `mvn spring-boot:run`, then open `/addresses`, `/address?name=…` and `/diagnose` in the browser pane.
    - Confirm that paging shows as normal, the BLOCK address is flagged with its policy and limit, and the global memory finding names contributors without blaming them.
    - Screenshot for the PR.
4. `git diff --stat` after the build: the formatter will touch files, and templates must stay LF.