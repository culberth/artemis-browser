## Phase 13 P2 — Queue behavior and effective configuration

### Context

P1 (merged, PR #30) explained address pressure by putting usage beside policy. P2 does the same for
queues. It says **what a queue is configured to do and what that looks like from outside**, so
intended behavior stops reading as a fault.

Today the tool parses only `exclusive`, `paused`, `purgeOnNoConsumers`-adjacent identity and the
counters from `listQueues` (`QueueDirectory.toOverview` L196, `onAddress` L110). The queue page has
no configuration panel. Diagnose judges every queue as a plain FIFO. That makes several findings
wrong for specially configured queues:

- `hoarding` (`StuckDiagnosisService` L162) fires on an **exclusive** queue, where one consumer
  holding everything is the whole point. It also fires on a **grouped** queue, where it is group
  affinity.
- "Nothing is reading" and "delivered nothing" (L352, L367) ignore **`consumersBeforeDispatch`**
  and **`delayBeforeDispatch`**. Under those, messages wait with consumers attached, on purpose.
- A **last-value** or **ring** queue holds far fewer messages than were added, and may count what it
  replaced or evicted as killed or acked. `nowhereToGo`'s "dropped after too many delivery attempts"
  could then be a false alarm. This needs verifying.
- On a **non-destructive** queue messages never leave, so a growing count is normal.

Done (PRD L668): specially configured queues show their actual settings and explain their behavior.
Diagnose findings separate observed facts from possible explanations. Integration tests cover the
semantics the findings rely on, on 2.55.0 and 2.57.0.

Work on a new branch from `main`: `phase13-p2-queue-behavior`.

### What a 2.55.0 probe already shows (2026-09-29, listing only; nothing configured yet)

- **`listQueues` already carries almost every P2 setting**, string-quoted like the counters:
  `exclusive`, `lastValue`, `lastValueKey`, `ringSize` (`-1`), `groupRebalance`,
  `groupRebalancePauseDispatch`, `groupBuckets` (`-1`), `groupFirstKey` (`""`),
  `consumersBeforeDispatch` (`0`), `delayBeforeDispatch` (`-1`), `purgeOnNoConsumers`, `maxConsumers`,
  `directDeliver`, `enabled`, `autoDelete`, `filter`, `paused`. So the overview and diagnose get
  configuration **with no extra call**.
- **Non-destructive is in neither the listing nor the per-queue attributes.** `QueueControl` has no
  `NonDestructive` getter. The only source seen so far is the address-settings default
  `defaultNonDestructive`, which is a default and not the queue's effective value.
- Per-queue attributes add `GroupCount` and `ConfigurationManaged`, and there is a `listGroupsAsJSON`
  operation. None of these is parsed yet.

### Step 0: Verify behavior before parsing anything (standing P0 rule)

Against `apache/artemis:2.55.0-alpine` and `2.57.0-alpine`, set up with the test-broker recipe in
`.claude/memory.md` on port 62616. Test setup uses management calls the app refuses: `createQueue`
with a queue-configuration JSON, and `addAddressSettings` for the `default*` keys. Record:

- **The listing on 2.57.0**: the same field set? Is `nonDestructive` present there?
- **Where non-destructive is readable, if anywhere.** Check the listing,
  `queue.<name>.nonDestructive` (expected: none), `listQueues` on a queue created with
  `"non-destructive":true`, and `getAddressSettingsAsJSON` `defaultNonDestructive`. **If no effective
  value is readable, the panel says so and shows the address default as a default only.** It must
  not infer the queue's value from it (PRD: do not infer effective values from omitted fields).
- **Effective vs default.** Create a queue explicitly with `ringSize=3` on an address whose settings
  say `defaultRingSize=10`, and check that the listing reports 3. Also check which `default*` keys
  `getAddressSettingsAsJSON` returns and whether they are absent at their defaults. P1 found that
  keys are left out at default.
- **What each setting does to the counters.** Use one `it-q-*` queue each:
  - **Last-value** (key `k`): send 5 messages with the same key. Record `messageCount`,
    `messagesAdded`, `messagesAcked` and `messagesKilled`. Where do the 4 replaced messages go?
  - **Ring** (`ringSize=3`): send 10. Record the same four counters. **Does eviction raise
    `messagesKilled`?** If so, `nowhereToGo` must not call those drops "too many delivery attempts".
  - **Non-destructive**: send 3, then consume and ack all 3. Record `messageCount`, `messagesAcked`
    and `deliveringCount` afterwards. Do the messages stay, and does browse still show them?
  - **Purge on no consumers**: send 3 with a consumer attached and not acking, then close the
    consumer. Record the counters and where the messages went (killed? acked? gone?).
  - **Exclusive**: 2 consumers and 20 messages. Record `listConsumers` `messagesInTransit` per
    consumer, and confirm that one takes everything.
  - **Groups**: 2 consumers, and messages in 2 groups via `JMSXGroupID`. Record `GroupCount` and the
    `listGroupsAsJSON` shape.
  - **Dispatch gates**: `consumersBeforeDispatch=2` with 1 consumer, and `delayBeforeDispatch=5000`.
    Record `deliveringCount` with a consumer attached and messages waiting.
- **Reading is non-destructive on every one of these.** Run browse, the scheduled list and the
  delivering list. Browsing a last-value or ring queue must not trigger replacement or eviction.

Append the shapes to `.claude/memory.md` under a dated "Queue configuration" section **before**
writing parsers. Any setting a version lacks becomes `UNSUPPORTED`, never a default value.

### Step 1: Model

- **New `QueueBehavior` record** (`broker/`), parsed from a `listQueues` node, with one `Reading`
  per setting:
  - last-value: `lastValue`, `lastValueKey`
  - ring: `ringSize` (`-1` means none)
  - `exclusive`, `purgeOnNoConsumers`, `maxConsumers`
  - groups: `groupRebalance`, `groupRebalancePauseDispatch`, `groupBuckets`, `groupFirstKey`
  - dispatch: `consumersBeforeDispatch`, `delayBeforeDispatch`
  - `enabled`, `filter`
  - `nonDestructive`: `NOT_COLLECTED` or `UNSUPPORTED`, unless Step 0 finds an effective source

  A field that is absent is `UNSUPPORTED`, and a malformed one is `FAILED`. This is the
  `AddressDirectory.reading` pattern from P1; move that helper somewhere shared (for example a
  package-private `ListingFields`) rather than copy it.
- **Helpers on `QueueBehavior`:**
  - `special()`: anything other than a plain FIFO.
  - `dispatchGated(consumerCount)`: consumers are attached but fewer than `consumersBeforeDispatch`.
  - `replacesMessages()` for last-value, and `evicts()` for ring.
  - `singleConsumer()`: exclusive, or `maxConsumers == 1`.
  - `grouped()`.
  - `effects()`: a list of `Effect(setting, value, consequence)` lines, for example "Ring size 3 →
    the oldest message is removed when a fourth arrives".
- **`QueueOverview` and `QueueStats`** carry `QueueBehavior behavior`. Keep the existing positional
  constructors, defaulting it to all `NOT_COLLECTED` (the P1 `AddressOverview` pattern), so tests
  and callers compile unchanged. `Subscription` gets it too, from the same node in `onAddress`.
- **Address defaults, for the queue page only**: `AddressSettings.queueDefaults()` picks the
  `default*` keys (`defaultLastValueQueue`, `defaultLastValueKey`, `defaultRingSize`,
  `defaultExclusiveQueue`, `defaultNonDestructive`, `defaultPurgeOnNoConsumers`,
  `defaultConsumersBeforeDispatch`, `defaultDelayBeforeDispatch`, `defaultGroupBuckets`, …). These
  are shown as "address default", never as the queue's value. Absent means "not set", as in P1.
- **Groups detail** (queue page only, if Step 0 shows it is cheap): `GroupCount` as one attribute
  read. `listGroupsAsJSON` goes on the allowlist only after its shape is recorded, and only if the
  panel needs it. It is a `list*` read, so it passes `ManagementChannelTest.allowlistHoldsOnlyQueries`.

### Step 2: Pages

- **Queue page (`queues.html`)**: add a "Configuration and behavior" panel after the stats panel,
  isolated with `Reading.attempt` and showing `collected(...)`.
  - One row per setting that is not at its default. Each row shows the effective value, the address
    default beside it where it differs, and what the user sees. For example:
    - "Last-value on `k`: a new message replaces the one with the same key, so fewer are held than
      were sent."
    - "Non-destructive: consuming does not remove a message."
    - "Exclusive: one consumer gets everything; the others wait as standbys."
    - "Needs 2 consumers before dispatch: 1 attached, nothing is being delivered."
  - Plain queues get a one-line "Plain FIFO: every setting at its default", with every value in a
    `<details>`.
  - When non-destructive has no effective reading, the panel says so, next to its address default.
- **Overview (`overview.html`)**: small badges from the listing only, such as "last-value",
  "ring 3", "exclusive", "grouped" and "waits for 2 consumers". No per-queue calls.
- **Address page**: add the same badges to the subscription rows, beside the existing exclusive badge.
  Reuse, don't duplicate: the badge fragment goes in `fragments/layout.html`, as `storageBadges` did in P1.

### Step 3: Diagnose, configuration-aware

- **`Finding` gets an `explanation`** (nullable). This is the configured behavior that may account
  for the observed facts, and it is rendered separately on `diagnose.html` as "May be intended:".
  Existing constructors keep working, as `basis` did in P1. The finding's `detail` stays strictly
  observed facts. That is what the PRD asks for.
- **Per-check changes** in `StuckDiagnosisService`:
  - **`hoarding`**: on an exclusive or `maxConsumers=1` queue, **no finding**, because it is the
    configured behavior. On a grouped queue it stays a watch, with the explanation "group affinity:
    each group sticks to one consumer".
  - **"Nothing is reading" / "delivered nothing"**: when `dispatchGated`, the finding is replaced
    by a watch. The title is "'q' is waiting for N consumers before it dispatches"; the detail gives
    the observed counts, and the explanation gives the setting. `delayBeforeDispatch` gets the same
    treatment for a queue whose consumers attached recently. Say that recency cannot be observed,
    if that is what Step 0 finds.
  - **`nowhereToGo`**: if Step 0 shows that ring eviction or last-value replacement raises
    `messagesKilled`, those queues no longer produce "dropped after too many delivery attempts".
    They get an explanation-only note instead.
  - **Non-destructive**: dead-letter "holding N" and durable-subscription "only grows" findings get
    the explanation "messages are not removed when consumed".
  - **Purge on no consumers**: with no consumer attached and messages missing, add the explanation
    "messages are purged when the last consumer leaves" to the relevant finding. Do not add a new
    finding, since the queue being empty is exactly what was configured.
- **`diagnose.html`**: add the explanation line, and reword "nothing wrong" so it doesn't claim more
  than was checked.

### Step 4: Tests

- **Unit tests**, using recorded JSON from Step 0:
  - `QueueBehaviorTest`: each setting parsed from quoted values, defaults (`-1`, `0`, `""`), absent
    → `UNSUPPORTED`, and malformed → `FAILED`. Also `effects()` wording and each helper.
  - `QueueDirectoryTest`: the behavior is attached to overview, stats and subscriptions.
  - `AddressSettings` queue defaults: absent is "not set".
  - `StuckDiagnosisServiceTest`:
    - no hoarding finding on an exclusive queue
    - an explained watch on a grouped queue
    - a dispatch-gated queue gives the gate finding instead of "nothing is reading"
    - ring and last-value killed counts are not called dropped (if Step 0 confirms it)
    - every changed finding keeps its observed detail separate from its explanation
- **`PageRenderingTest`**:
  - the queue page with a special queue
  - a plain queue
  - non-destructive not readable
  - a refused panel
  - overview badges
  - a diagnose finding with an explanation

  Break each new fragment on purpose once and count the failures, as in P1.
- **New `QueueBehaviorIT`** on both matrix versions, one `it-q-*` queue per setting, created through
  management as test setup:
  - Assert the parsed behavior and the queue-page model.
  - Assert the diagnose results: no false hoarding on the exclusive queue, and the gate finding on
    the dispatch-gated one.
  - Hold consumers open for the exclusive, grouped and gated cases, like `AddressDetailIT`'s live consumer.
- **`ReadOnlyGuaranteeIT`**: include the `it-q-*` queues. Browsing a last-value, ring or
  non-destructive queue must leave `messageCount`, `messagesAdded`, `messagesAcked` and
  `messagesKilled` unchanged.
- **`PartialAvailabilityIT`**: if a groups read or the settings read is added, deny it to `viewer`
  and assert the panel says so.

### Step 5: Docs

- **`docs/PRD.md`**: tick the three P2 items, with "Done …" notes on what Step 0 found, especially
  where non-destructive can and cannot be read.
- **`docs/architecture.md`**: a "Queue configuration: effective vs default" subsection. It explains
  why the listing is the source, why address defaults are never shown as effective, and the
  finding-versus-explanation split.
- **`README.md`**: P2 status in the Phase 13 section, the diagnose caveat, and `QueueBehavior` in the Layout.
- **`.claude/memory.md`**: the Step 0 shapes (before the code), plus any traps hit.
- **`CLAUDE.md`**: the test counts and the Phase 13 status line.

### Critical files

- `broker/QueueDirectory.java`, `QueueOverview.java`, `QueueStats.java`, `Subscription.java`,
  `AddressSettings.java`, `StuckDiagnosisService.java`, `Finding.java`; new `QueueBehavior.java`, and
  a shared listing-field helper taken from `AddressDirectory.reading`
- `web/QueueController.java` (address defaults for the panel)
- `templates/queues.html`, `overview.html`, `address.html`, `diagnose.html`, `fragments/layout.html`
- Tests: `QueueDirectoryTest`, `StuckDiagnosisServiceTest`, `PageRenderingTest`, `ReadOnlyGuaranteeIT`,
  `PartialAvailabilityIT`; new `QueueBehaviorTest`, `QueueBehaviorIT`

### Verification

1. `mvn test`: all unit tests and `PageRenderingTest` green.
2. `mvn clean verify -Pintegration`: every IT green on 2.55.0 and 2.57.0.
3. Manual check against the local test broker on port 62616:
   - Create one last-value, one ring, one exclusive (with two consumers) and one dispatch-gated
     queue.
   - Run `mvn spring-boot:run` and open `/overview`, `/queues?name=…` and `/diagnose` in the browser pane.
   - Confirm the badges, and that each panel explains its behavior.
   - Confirm the exclusive queue raises no hoarding finding, and the gated queue says what it is waiting for.
   - Take a screenshot for the PR.
4. `git diff --stat` after the build: the formatter touches files, templates stay LF, and
   `docs/PRD.md` stays CRLF.
