# Memory — artemis-browser

What has been **discovered**: verified broker response shapes, environment quirks, traps already
hit. Read this at the start of every session; it is not auto-loaded the way `CLAUDE.md` is.

Design decisions and their reasoning live in [docs/architecture.md](../docs/architecture.md); what
the product is and what is next lives in [docs/PRD.md](../docs/PRD.md). Don't re-add either here, and
don't record what git history or the code already says. Append discoveries as you make them —
one or two lines, dated, with the why — and consolidate into the sections below when it drifts.

Last consolidated 2026-09-19, covering work through Phase 4.

## Environment (this machine)

- **Maven resolves through a local Nexus.** `mirrorOf *` →
  `http://localhost:8081/repository/maven-public/`, local repo
  `P:/maven_local_repositories/.m2/spring-boot`, both set in Maven's own `conf/settings.xml`, not
  `~/.m2`. If Nexus is down, nothing resolves. It does proxy Maven Central successfully.
- **The JDK on PATH is 26; the build targets release 21.** JaCoCo 0.8.12 (the sibling projects' pin)
  cannot instrument Java 26 class files — "Unsupported class file major version 70". Pinned 0.8.15.
- **Ports 61616 and 8161 are already taken** by a kind Kubernetes cluster
  (`claude-local-control-plane`), so the test broker maps 62616→61616 and 8162→8161. Connect the app
  to `localhost:62616`.
- **Git Bash mangles container-absolute paths.** `docker exec ... /var/lib/...` becomes
  `C:/Program Files/Git/var/lib/...`; prefix with `MSYS_NO_PATHCONV=1`.

### Test broker recipe

```
docker run -d --name artemis-test -p 62616:61616 -p 8162:8161 \
  -e ARTEMIS_USER=artemis -e ARTEMIS_PASSWORD=artemis apache/artemis:2.55.0-alpine
```

- Readiness log line is "Server is now active", not "live".
- The CLI is at **`/var/lib/artemis-instance/bin/artemis`**, not `./broker/bin/artemis`.
- `artemis producer --destination queue://orders --message-count 5` seeds a queue. `--text-size N`
  makes text messages; `--message-size N` makes **bytes** messages, which is the quick way to
  exercise the non-text body path. `--destination topic://events` publishes to a multicast address.

## Build traps

- **Do NOT define an `artemis.version` property.** spring-boot-dependencies defines one by exactly
  that name and uses it to import `org.apache.artemis:artemis-bom`. Ours silently redirected that
  import to a non-existent coordinate, and the build failed while resolving the parent — before it
  ever read our dependency. Declare `artemis-jakarta-client` with no version and let the BOM manage
  it.
- **Artemis moved groupId to `org.apache.artemis`** (was `org.apache.activemq`), and the
  Jakarta-namespace artifact is `artemis-jakarta-client`. `artemis-jms-client` is the old `javax.jms`
  one and fails at runtime under Spring Boot 4, not at compile time.
- **Spring Boot 4 moved `@WebMvcTest`** into its own `spring-boot-webmvc-test` artifact, package
  `org.springframework.boot.webmvc.test.autoconfigure`. The MVC starter is now
  `spring-boot-starter-webmvc`.
- **Jackson 3 arrives transitively** via `spring-boot-starter-jackson`, so only the import changes:
  `tools.jackson.*`, not `com.fasterxml.jackson.*`. The 2.x `jackson-annotations` is also on the
  classpath, which makes a wrong import look plausible until it fails to resolve.

## Verified broker API shapes

All confirmed against a live broker, not read from docs. These are what the parsing tests are built
from — a wrong parse here yields a believable number rather than an error.

- **`broker.listQueues(filterJson, page, pageSize)`** → JSON String `{"data":[...],"count":N}`, every
  value quoted including counters (`"messageCount":"0"`). Field is `messagesAcked`, **not**
  `messagesAcknowledged`. The filter argument is itself JSON:
  `{"field":"","operation":"","value":""}`. One call gets every queue with all counters, far cheaper
  than per-queue attribute reads.
- **`queue.<name>.browse(page, pageSize[, filter])`** → a Map keyed by the literal string
  `javax.management.openmbean.CompositeData`, whose value is a `CompositeData[]`. Entries carry
  messageID, userID, address, durable, expiration, largeMessage, persistentSize, priority, protocol,
  redelivered, timestamp, type, `text` (text bodies only) and `PropertiesText`.
- **`browse()` truncates `text` broker-side** at `management-message-attribute-size-limit` (default
  256) and appends a literal `", + N more"` **to the value itself** rather than signalling out of
  band. Verified: a 250-char body came back whole, a 500-char body came back as 256 chars plus that
  suffix. Raising `artemis.body-detail-chars` past it does nothing — only the JMS `QueueBrowser`
  path reads the real body. `QueueBrowseService` strips the marker and sets `bodyTruncated`, guarded
  by a 64-char minimum because the marker is ordinary text a real body could end with.
- **`listAddresses(filter, page, pageSize)`** → `{"data":[...]}`, every value string-quoted, and
  `routingTypes` is a JSON array encoded *inside* a JSON string (`"[\"ANYCAST\"]"`) — unwrap it or it
  renders as escaped brackets.
- **`getAcceptorsAsJSON`, `listConnectionsAsJSON`, `listAllConsumersAsJSON`** → plain JSON arrays.
- **`listProducersInfoAsJSON()`** → `{"id","name","connectionID","sessionID","creationTime",
  "destination","lastProducedMessageID","msgSent","msgSizeSent"}`. Unlike most management JSON here,
  `msgSent`/`msgSizeSent` are bare numbers while `creationTime` is quoted epoch millis.
  `destination` is the address it sends to.
- **Health attributes are mixed types**: `version`/`uptime` String, `connectionCount` Long (despite
  an int getter), `diskStoreUsage` Double, `status` a JSON String holding `server.state` and
  `server.nodeId`. **`diskStoreUsage` is a 0..1 ratio, not a percentage** — reading it straight
  showed "0.10%" for an 85%-full disk and meant `diskPressure()` could never trip;
  `BrokerInfoService.health()` multiplies by 100.
- **`browse()` does NOT return scheduled messages.** A queue holding one scheduled message reports
  `messageCount=1`, `scheduledCount=1` and `countMessages=1`, and browse returns zero entries — so
  the list looks empty while the counters say otherwise. Scheduled messages have their own
  operation, `queue.<name>.listScheduledMessagesAsJSON()`.
- **`listScheduledMessagesAsJSON()`** returns a plain JSON array, values NOT string-quoted (unlike
  `listQueues`), each entry carrying `address`, `messageID` (number), `type`, `priority`, `userID`,
  `durable`, `expiration`, `timestamp`, **`_AMQ_SCHED_DELIVERY` as bare epoch millis**, and the
  message's own properties inline at the top level. There is no body field at all.
- **Browsed properties come typed, not just as text.** Alongside `PropertiesText` — which is a Java
  map's `toString()`, `{orderNumber=1, __AMQ_CID=it-client}`, not JSON and not worth parsing — each
  entry carries `StringProperties`, `IntProperties`, `LongProperties`, `DoubleProperties`,
  `FloatProperties`, `ShortProperties`, `BooleanProperties` and `ByteProperties` as `TabularData`,
  null when empty. Each row is a `CompositeData` with exactly `key` and `value`. That is the source
  to read. `__AMQ_CID` and `_AMQ_ROUTING_TYPE` are Artemis's own and worth hiding.
- **A large message is announced differently on each read path.** Management `browse` has a
  `largeMessage` boolean attribute; the JMS read path has no such API and instead carries the
  property **`_AMQ_LARGE_SIZE`** (Artemis's `Message.HDR_LARGE_BODY_SIZE`), whose value is the real
  body size. Both are read, because neither path can see the other's. Verified against a broker
  holding 250KB messages — the default `min-large-message-size` is 100KB, so `--message-size 250000`
  produces one.
- **A filtered `countMessages` only examines the first `management-browse-page-size` messages**
  (200 by default) — it is a sample, not a count. Measured on a 100,000-message queue:
  `countMessages()` unfiltered returned 100000, while `countMessages("AMQPriority=4")` returned 200
  and `countMessages("count=500")` returned 0 for a message that is definitely there. A filtered
  **`browse`** has no such window: it scans the whole queue and finds a match at position 99,999 in
  about 300ms. So browse to find things; never use a filtered count to decide whether something is
  there.
- **There is no "which connection am I" call.** Our own connection is identified by finding the
  consumer sitting on our management reply queue and reading its `connectionID`.

### Subscription shapes (2026-09-27, broker 2.44.0, before Phase 10 parsed any of them)

- **Queue-level management resources take the BARE queue name, never the FQQN.**
  `queue.events::probe-a.sub-a` → "AMQ229067: Cannot find resource"; `queue.probe-a.sub-a` works.
  FQQN is for the JMS read path only. Mixing them up fails loudly here, unlike the silent JMS case.
- **Subscription queue names by kind**: durable `clientId.subName`; shared durable with no client
  id = bare `subName`; shared non-durable `nonDurable.clientId.subName`; plain non-durable = a UUID
  with `temporary:"true"`, `durable:"false"`. Only the last two are distinguishable by flags alone.
- **The stored filter is core syntax even when a JMS selector created it**: subscribing with
  `JMSPriority > 3` stores `AMQPriority > 3`. So a subscription filter can be shown as-is.
- **`listQueues` already carries `filter`, `user`, `temporary`, `exclusive`, `autoCreated`,
  `purgeOnNoConsumers`, `maxConsumers`, `messagesExpired`, `messagesKilled`** — all string-quoted
  like the counters. No per-queue calls needed for subscription identity.
- **`queue.<name>` attribute `firstMessageAge`** → `Long` millis; **`null` on an empty queue**, not 0
  or -1. `firstMessageTimestamp` likewise (epoch millis). Per-queue round trip each.
- **`broker.getAddressSettingsAsJSON(address)`** → a flat JSON object, **numbers bare, not quoted**
  (`"maxSizeBytes":-1`, `"autoCreateQueues":true`), with `deadLetterAddress`, `expiryAddress`,
  `addressFullMessagePolicy`, `redeliveryDelay`, `autoCreate/DeleteQueues/Addresses`,
  `managementBrowsePageSize`. It resolves the `#` match, so defaults show up for any address.
  **`maxDeliveryAttempts` and `retroactiveMessageCount` were absent** at their defaults — treat a
  missing key as "default", not as zero.
- **Diverts have no listing operation**: `listDivertsAsJSON` and `listDiverts` → "AMQ229069: no
  operation". `broker.getDivertNames()` → `Object[]` of names; then `divert.<name>` attributes:
  `address`, `forwardingAddress`, `filter`, `routingType` (`PASS`/`STRIP`/`ANYCAST`/`MULTICAST`) all
  String, `exclusive` **Boolean**, `transformerClassName` null when unset. One round trip each.
- **`address.<name>.bindingNames` includes diverts** alongside queues (`events-audit` appeared there);
  `queueNames` does not. `numberOfMessages` (13) is the sum of every queue's copy, while
  `routedMessageCount` (6) counts messages routed once regardless of fan-out.
- **`broker.listConsumers(filterJson, page, size)`** carries `clientID`, `user`, `remoteAddress`,
  `queue`, `address`, `filter`, `queueType` — **`listAllConsumersAsJSON` has no clientID**. Shape is
  mixed: counters quoted, `lastDeliveredTime` bare, `creationTime` a `Date.toString()` string
  ("Sun Sep 27 20:20:42 GMT 2026"), not millis.

- **In-flight messages are invisible to browse and to `firstMessageAge`** (2026-09-27, 2.44.0). A
  durable subscriber that received 3 messages without acking: `messageCount=3`, `deliveringCount=3`,
  `browse` (filtered or not) → 0 entries, `firstMessageAge` → null. Delivered-but-unacked refs leave
  the browsable list. So "not found by browse" never means "not on the queue" while delivering > 0,
  and `firstMessageAge` is the age of the oldest message *not yet delivered*.

### In-flight messages (2026-09-27, 2.44.0, before Phase 11 parsed any of it)

- **`queue.<bare name>.listDeliveringMessagesAsJSON()`** → a JSON array, one entry per consumer:
  `{"consumerName": "<ServerConsumer toString>", "elements": [ {...}, ... ]}`. Each element is the
  message's headers (`messageID` bare number, `userID`, `address`, `durable`, `priority`, `timestamp`
  bare epoch millis, `expiration`, `type`) with **its properties inline at the top level** — same
  layout as `listScheduledMessagesAsJSON` — plus Artemis's own `__AMQ_CID`, `_AMQ_ROUTING_TYPE`.
  **No body, no delivery time.** `listDeliveringMessages()` (non-JSON) → a HashMap keyed by the same
  toString, values `Object[]`.
- **`consumerName` is a `toString()`**, e.g. `ServerConsumer [id=800977e2:18098baf-…-00155d348692:0,
  filter=null, binding=LocalQueueBinding [address=work, queue=QueueImpl[name=work, …]]]`. The `id` is
  `<connectionID>:<sessionID>:<consumerID within session>` — which `listAllConsumersAsJSON`'s
  `connectionID`/`sessionID`/`consumerID` can match, and whose `sequentialId` is `listConsumers`' `id`
  (where the client id is). Only checked for CORE clients.
- **No paging, no filter**: 300 in-flight messages = 65,813 chars in 11ms. A consumer's buffer
  (consumer window, 1MB default) can hold thousands of small messages.
- **"Delivering" includes the consumer's client-side buffer.** Consumer A called `receive()` twice;
  the third message was also delivering to A, and consumer B on the same queue got nothing.
  `listConsumers` showed A `messagesInTransit=3`, B `0`.
- **Reading it is non-destructive**: messageCount/deliveringCount/messagesAcknowledged identical
  before and after, on a plain queue, a buffered backlog and a durable subscription.
- **Measured at scale (2026-09-27)**: ~280 chars per small message, linear. 1,000 → 278KB/~15ms;
  3,214 → 900KB/~40ms; 20,000 → 5.6MB/~150ms; 100,000 → 28MB/~800ms. A CORE consumer on the
  **default 1MB window saturates at ~3,200** small messages (5,000 sent, 3,214 delivering); only an
  unbounded window (`consumerWindowSize=-1`) takes the lot. Cap is `artemis.in-flight-limit=5000`,
  checked against `deliveringCount` *before* calling, since nothing broker-side shrinks the reply.
- **Non-CORE consumers (2026-09-27)**: AMQP (`artemis consumer --protocol AMQP`) and STOMP give the
  same `id=<conn>:<session>:<consumerID>` shape (STOMP's consumerID is a large number, e.g.
  `126356`). **OpenWire's session id contains colons** — `id=d46364be:ID:HOST-63679-1790…-1:1:1:0` —
  so split at the first and last colon only; that triple matches `listAllConsumersAsJSON` exactly for
  all four. OpenWire elements also carry `__HDR_*` headers (`__HDR_MESSAGE_ID`, `__HDR_ARRIVAL`…)
  as properties. `listConsumers` has `protocol` per consumer. The artemis CLI has no OpenWire
  consumer; `activemq-client` 6.1.7 (jakarta) resolves through Nexus for a throwaway one.
- **Message IDs agree across read paths (2026-09-27, CORE)**: browse's `userID`, the delivering
  list's `userID` and the consumer's `JMSMessageID` are the same string. Core filter
  `AMQUserID = 'ID:…'` finds it; without the `ID:` prefix, or as `JMSMessageID = '…'`, it silently
  matches nothing. OpenWire differs: its own `JMSMessageID` travels as `__HDR_MESSAGE_ID`, while
  `userID` is Artemis-generated — this tool shows and looks up `userID`. Unverified for lookup.
- **Thymeleaf 3.1 in `th:text` rejects an apostrophe inside a string literal** (2026-09-27): prose
  like "each queue's" in `th:text="'...'"` fails with "Could not parse as expression", and only when
  that branch renders — `PageRenderingTest` caught it. Word around it, or use `&rsquo;`.
- **What `countMessages` counts** (2026-09-27, 2.44.0; fixed in Phase 12 P0). Unfiltered it is the
  whole queue: 7 waiting + 3 in flight + 2 scheduled counted 12, browse returned 7. **Filtered it
  counts only waiting messages, and only among the first 200**: 500 matches in 1,000 messages
  counted 100 while a filtered browse paged all ten pages of 50. That undercount also drove the
  cross-queue *export*'s choice of queues, which left out any queue whose matches lay past its first
  200 messages. Nothing now uses a filtered count; a page's next page is found by browsing one row
  at the next offset (`browse(page*size+1, 1, filter)`).
- **`AddressDetailIT.measuresLagByAge` was red on `main`** from af9a664 to 2026-09-27: that commit's
  60s-gap rule for "furthest behind" correctly returns null for a feed seeded in one burst, and the IT
  still expected a mark. Run `-Pintegration` after changing a heuristic, not just unit tests.

### Killed and expired (2026-09-27, 2.44.0, before Phase 12 P1 parsed any of it)

- **`messagesKilled` counts a message that exceeded max delivery attempts, whether it was
  dead-lettered or dropped.** Rolled back 10× on default settings: killed=1, DLQ +1. Rolled back 2×
  on an address with `maxDeliveryAttempts=2` and no dead-letter address: killed=1, message gone.
  **`messagesExpired` is the same**: TTL 1s with the default ExpiryQueue → expired=1, ExpiryQueue +1;
  with no expiry address → expired=1, gone. So the counter never says where a message went; only
  the address settings do.
- **An unset dead-letter/expiry address reads as `""`** from `getAddressSettingsAsJSON` when set
  that way, not only as an absent key. Treat blank and absent alike.
- Both counters are on `listQueues`, string-quoted: `"messagesExpired":"0","messagesKilled":"1"`,
  counted on the queue the message left. An expired message is expired on delivery — a consumer's
  `receive` got nothing — as well as by the periodic scan.
- Test setup can change settings through management: `broker.addAddressSettings(match, json)` with
  e.g. `{"maxDeliveryAttempts":2,"deadLetterAddress":""}`. Not something the app may call.

### Counters across a restart (2026-09-27, 2.44.0, before Phase 12 P2 used them for rates)

- **`messagesAdded` does not reset to zero on restart — it restarts at what the journal reloads.**
  A durable queue holding 3 messages read added=3 before and after a `docker restart`; four
  emptied durable queues went from added=1 (and killed=1) to 0. `messagesAcked`/`Killed` went to 0.
  So a restart can look like a drop, like no change, or — with new traffic after it — like a
  plausible rate. A per-queue "counter went down" check alone cannot see it.
- **A queue's `id` in `listQueues` changes when it is deleted and recreated, and survives a restart**
  (2026-09-29, 2.55.0): `it-p3` was …931, destroyed and created again → …940; after `docker restart`
  still …940, while `messagesAdded` restarted at what was held (ring 10→3, LVQ 5→1). So id change =
  recreated, shorter uptime = restarted; neither interval may be joined in a trend.
- **`broker.uptimeMillis`** is a `Long` attribute (`uptime` is a String like "39.079 seconds"). An
  uptime shorter than the time since the previous reading means the broker restarted in between —
  that, not the counters, is the restart test.

### Clients across the listings (2026-09-27, 2.44.0, before Phase 12 P3 parsed any of it)

- **`listConnectionsAsJSON` has no client id and no protocol** — only `connectionID`,
  `clientAddress`, `creationTime` (bare millis), `implementation`, `sessionCount`. Useless for "which
  client"; the per-connection `listSessionsAsJSON(id)`/`listConsumersAsJSON(id)` route the PRD
  planned was never needed.
- **`broker.listConnections(filter, page, size)`** → `{"data":[...],"count":N}` with `connectionID`,
  `remoteAddress`, `users`, `protocol`, **`clientID`** (`""` when unset), `localAddress`,
  `sessionCount` (bare number), `creationTime` as a **`Date.toString()`** string.
- **`broker.listSessions(filter, page, size)`** → `id` (the session id), `connectionID`, `clientID`,
  `user`, `consumerCount`/`producerCount` (bare), `creationTime` a Date string. **The only listing that
  ties a session to its connection.**
- **`broker.listProducers(filter, page, size)`** → `id`, `name`, `session`, `clientID`, `protocol`,
  `address`, `remoteAddress`, `msgSent`/`msgSizeSent` bare, `creationTime` **quoted epoch millis**
  (unlike the other paged listings' Date strings), **no `connectionID`** — reach it through `session`.
  `listConsumers` likewise carries `session`, not the connection.
- A connection with no client id (2 of 3 here) can only be named by its connection id, remote
  address and user.

### Failure replies: unsupported, missing, denied (2026-09-29, 2.55.0 and 2.44.0, before Phase 13 P0)

- **Images moved**: the cluster runs `apache/artemis:2.55.0-alpine`; `apache/activemq-artemis:latest-alpine`
  on this machine is **2.44.0** and is no longer the current repository. Docker Hub's newest is 2.57.0.
- **Unknown operation** → failed reply `AMQ229069: no operation <name>/<arity>`, on both versions.
- **Unknown attribute, attribute on a missing resource, and an attribute denied by RBAC all read
  the same**: failed reply `Problem while retrieving attribute <name>`. The broker does not say
  which, so an attribute failure can only be shown as "unavailable", never as "unsupported".
- **No `manage` permission** → `JMSSecurityException` (AMQ229032 `permission='MANAGE' on address
  activemq.management`) thrown **from `producer.send`**, and the session stays usable. Before Phase 13
  `ManagementChannel` mapped every `JMSException` to connection-lost.
- **Per-operation denial needs `<management-message-rbac>true`** (off by default; default security
  is all-or-nothing `manage`). Then a denied operation is a failed reply `AMQ229032: User: x does not
  have permission='VIEW' on address mops.broker.<operation>`; attributes are checked as their getter
  (`mops.broker.getUptime`, not `.uptime`). A user also needs createAddress/createNonDurableQueue/
  consume on `#` for the temporary reply queue, or connecting fails with AMQ229213.
- Properties files in the image end without a newline — `echo >>` glues the new line onto the last.
- **`management-message-rbac` is read only at broker startup.** Security settings and users reload live
  from `etc/` (AMQ221056), this flag does not — the RBAC fixture writes it before `artemis run`.
- **2.57.0 splits a factory's connections across brokers (2026-09-29).** With the client default
  `useTopologyForLoadBalancing=true`, a second connection opened while the first is still open
  follows the topology the broker announces — built from its `0.0.0.0:61616` acceptor — and from
  this machine that is **localhost:61616, the kind cluster's broker** (`claude-app/artemis`, node
  `547151d5…`, reached through ingress-nginx). 2.55.0 does not do it. The first 2.57.0 matrix runs
  seeded `it-client-in`, `it-hoard`, `it-inflight.sub` (6 msgs) and `it-shared` into that broker, and
  the "failures" were tests whose data had gone there. IT URLs now carry
  `?useTopologyForLoadBalancing=false`, the fixture refuses to seed unless two concurrent
  connections reach one node id, and `BrokerSession` sets it off too.
- **`localhost` resolves to `::1` first for Java here**, and `wslrelay` holds `[::1]` on Docker's
  published ports; it forwards to the same container, so it is not the cause above — checked by node id.

### Address pressure (2026-09-29, 2.55.0 and 2.57.0 identical, before Phase 13 P1 parsed any of it)

- **`listAddresses` already carries the pressure fields**, string-quoted like the rest:
  `addressLimitPercent`, `numberOfPages`, `numberOfBytesPerPage`, `paging`, `addressSize`, `paused`,
  `queueCount`, `maxPageReadBytes`/`Messages`, `prefetchPageBytes`/`Messages`. **Not** the management
  block: that is only the `address.<name>` attribute **`blockedViaManagement`** (Boolean), one round trip each.
- **`addressLimitPercent` is `addressSize / maxSizeBytes` × 100, bytes only, and `"0"` when there is
  no byte limit** (`maxSizeBytes=-1`) — a zero that is not a measurement. An address full at
  `maxSizeMessages=10` also reads 0 while it refuses sends. Compute a message-limit ratio separately.
- **`paging` means "over its limit", not "writing pages".** Filled past a 20KB limit with 40×1KB:
  PAGE → paging=true, 9 pages, all 40 kept. FAIL → paging=true, **0 pages**, 7 kept, sender got
  `AMQ229102: Address "…" is full`. DROP → paging=true, 0 pages, 7 kept, **routed 40** — the silent
  loss shows only as routed ≫ messages. BLOCK → **paging=false** at **418%** (producer credits granted
  ahead overshoot), sender stalled on `AMQ212054 … is blocked`, `blockedViaManagement` still false.
- **A management `block()`** sets `blockedViaManagement=true`; a CORE producer then fails fast with
  `AMQ219058: Address "…" is full` rather than waiting. The listing does not change.
- **Page limit**: PAGE + `pageLimitMessages=30` + `pageFullMessagePolicy=FAIL` → 8 pages, 37 kept, then
  AMQ229102. `pageFullMessagePolicy` is **absent** from `getAddressSettingsAsJSON` when unset;
  `pageLimitBytes`/`pageLimitMessages`/`maxSizeMessages` read `-1` at default.
- **`addAddressSettings` rejects `maxSizeBytes` below `pageSizeBytes`** (10MB default):
  "pageSize has to be lower than maxSizeBytes". Test fixtures set both. It returns the stored settings.
- Broker `globalMaxSize` → Long bytes (1073741824 = 1GB default here); `addressMemoryUsagePercentage`
  is a whole percent of it, so small brokers read 0.
- Jolokia on the console port is the quickest probe: `curl -u artemis:artemis -H "Origin: http://localhost"
  http://localhost:8162/console/jolokia/list/org.apache.activemq.artemis` lists every attribute/op.

### Queue configuration (2026-09-29, 2.55.0, listing only — before Phase 13 P2)

- **`listQueues` already carries the queue settings**, string-quoted: `exclusive`, `lastValue`,
  `lastValueKey`, `ringSize` (`"-1"` = none), `groupRebalance`, `groupRebalancePauseDispatch`,
  `groupBuckets` (`"-1"`), `groupFirstKey` (`""`), `consumersBeforeDispatch` (`"0"`),
  `delayBeforeDispatch` (`"-1"`), `purgeOnNoConsumers`, `maxConsumers`, `directDeliver`, `enabled`,
  `autoDelete`. No per-queue call needed for configuration.
- **Non-destructive is not readable per queue** on 2.55.0 or 2.57.0: not in the listing, and
  `QueueControl` has no `NonDestructive` attribute ("No such attribute"). Per-queue attributes do add
  `GroupCount` and `ConfigurationManaged`; operation `listGroupsAsJSON` exists.
- **Behavior, verified 2.55.0 and 2.57.0 identical** (queues made with `createQueue(json, true)`,
  keys `ring-size`, `last-value-key`, `non-destructive`, `exclusive`, `purge-on-no-consumers`,
  `consumers-before-dispatch`, `delay-before-dispatch`):
  - **`lastValue` reads `"false"` on a queue made with `last-value-key`** — which behaves as LVQ
    (5 sent with one key → count 1). `lastValueKey` non-empty is the signal, not the flag.
  - **Replaced (LVQ) and evicted (ring 3, sent 10) messages touch no counter**: added 5/10, count 1/3,
    acked 0, killed 0. So "added ≫ held" is these queues working, and nothing claims them as dropped.
  - **Non-destructive**: a consumer received all 3; afterwards count 3, **acked 0**, delivering 0.
    "Delivered nothing since start" (acked == 0) is therefore normal there.
  - **Purge on no consumers raises `messagesKilled`**: 3 in flight, consumer closed → count 0,
    killed 3. Later sends with no consumer are not counted as added on the queue — **the address
    counts them as `unroutedMessageCount`** (seen in the app, 2 sends → unrouted 2). `enabled` stays true.
  - **Exclusive**, two consumers, 20 sent: `messagesInTransit` 20 / 0. **Groups** on a plain queue
    (`_AMQ_GROUP_ID` g1/g2): 10 / 10; `GroupCount` 2; `listGroupsAsJSON` → array of
    `{groupID, consumerID, connectionID, sessionID, browseOnly, creationTime}`.
  - **`consumersBeforeDispatch=2` with one consumer**: delivering 0, count 3 — waits by design.
    `delayBeforeDispatch=6000` did not release dispatch within 9s with one consumer; unexplained, so
    the app states the setting and does not predict when dispatch starts.
  - **Browsing LVQ, ring and non-destructive queues changes no counter.**
- **The listing reports effective values; `getAddressSettingsAsJSON` reports `default*` keys only
  when set.** With `defaultRingSize=10` etc. on `it-d.#`: an explicit `ring-size:3` queue lists 3 and
  inherits the other defaults; an auto-created one lists 10. `it-d.#` works; `it-q-eff#` (no dot)
  matched nothing — a wildcard is a whole word.

### Connectivity and HA (2026-09-30, 2.55.0 and 2.57.0 identical, before Phase 13 P5 parsed any of it)

Fixture: primary A (replication, `group-name`), its backup, peer B, cluster connection `c1` A↔B,
core bridges `to-b` (connected) and `to-nowhere` (unresolvable host), AMQP broker connections
`mirror-b` (`<mirror/>`) and `sender-nowhere`. Over the JMS management channel:

- **Broker attributes**: `active`, `backup`, `replicaSync`, `sharedStore`, `clustered` Boolean;
  `HAPolicy` a String — `"Primary Only"` (standalone), `"Replication Primary w/quorum voting"`,
  `"Replication Backup w/quorum voting"`; `bridgeNames`, `clusterConnectionNames`,
  `connectorServices` → `Object[]` of String (empty, not null, when none); `pendingMirrorAcks` Long.
- **`listNetworkTopology()`** → JSON String array `[{"nodeID","live","primary","backup"?}]`, `live`
  and `primary` both present and equal (`host:port`), `backup` only when one announced. **It lists
  this broker itself.** Standalone → `"[]"`. **A stopped backup stays in it** (still there 70s later)
  while `replicaSync` went false within 8s — so `backup` in the topology is not evidence the backup is
  up; `replicaSync` is. A stopped *peer* dropped out within 20s.
- **`listBrokerConnections()`** → JSON String array `{"name","protocol","started","uri","connected"}`,
  booleans bare. `brokerconnection.<name>` attributes add `user`, `retryInterval`, `reconnectAttempts`.
  No counters anywhere; a mirror's backlog is the internal queue **`$ACTIVEMQ_ARTEMIS_MIRROR_<name>`**
  (in `listQueues`, `internalQueue:"true"`). `uri` is whatever was configured — could carry a password.
- **`bridge.<name>`** attributes: `queueName`, `forwardingAddress` (null when unset: the message keeps
  its address), `filterString` null, `staticConnectors` `Object[]`, `discoveryGroupName` null,
  `started`, `connected`, `HA` Boolean, `retryInterval`/`reconnectAttempts` Long, `metrics` a HashMap of
  Long. **No user/password attribute.** A bridge that cannot reach its target: `started` true,
  `connected` false, its source queue simply holds the messages (6 on `bridge.lost`, delivering 0).
- **`messagesPendingAcknowledgement` on a bridge is cumulative sent, not pending now**: 10 sent and
  acked read acked=10, pending=10, source queue empty. Outstanding = pending − acked.
- **`clusterconnection.<name>`**: `address` `""`, `started`, `nodeID` (this broker), `nodes` a
  HashMap nodeId → `"host/ip:port"` of **connected peers only** (empty when B was stopped, and never
  the backup), `maxHops`, `messageLoadBalancingType`, `staticConnectors`, `metrics` as bridges.
  `topology` is a `toString()` — not worth parsing. Its store-and-forward queue is
  **`$.artemis.internal.sf.<cluster>.<peer nodeId>`**, internal, one per peer.
- **`connectorsAsJSON` leaks secrets**: a connector URI's `user`/`password` come back in `extraProps`
  in clear (`"password":"connectorsecret"`). Read only name/host/port from `params`.
- **A bridge or broker connection that does not exist** → "Problem while retrieving attribute".
- **A replicated backup accepts no client connections** (JMS to it timed out); Jolokia still answers.
  Its `nodeID` is the primary's.
- **Fixture traps**: `cluster-user` must not be a real user — with `cluster-user=artemis` and another
  cluster password, an ordinary `artemis/artemis` login (the mirror's) was rejected and the
  ClusterManager stopped. A backup with no `group-name` paired itself with the wrong primary. The
  image binds the console to localhost unless `artemis create` gets `$EXTRA_ARGS`. broker.xml's
  `<core>` is `xsd:all`: a second `<addresses>` fails validation — insert into the existing one.

### Transactions and roles (2026-09-30, 2.55.0, before Phase 13 P6 parsed any of it)

Fixture: an XA branch that sent 2 to `p6.xa` and received 1 from `p6.xa.src`, `prepare()`d, then its
connection closed without commit or rollback. Over the JMS management channel:

- **`listPreparedTransactions()`** → `Object[]` of String, one per branch:
  `9/30/26, 7:41:25 AM base64: <xid> XidImpl (… formatID:4242 gtxid:<dotted bytes> base64:<xid>`.
  `[]` when none.
- **`listPreparedTransactionDetailsAsJSON()`** → JSON String array, per branch `creation_time`,
  `xid_as_base64`, `xid_format_id` (bare int), `xid_global_txid`, `xid_branch_qual` (as text),
  `tx_related_messages[]` of `{message_operation_type: "(+) send" | "(-) receive", message_type:
  "TextMessage", message_properties: {headers + properties inline, messageID/timestamp bare}}`. No body,
  no queue name — a receive names only the message's address. **`""` (empty string), not `[]`, when
  nothing is prepared.** No paging.
- **`creation_time` is `DateFormat` SHORT/MEDIUM in the broker's locale and zone, no zone written**,
  with U+202F (narrow no-break space) before AM on the image's JDK. The zone comes from
  `listConnections`' `Date.toString()` vs `listConnectionsAsJSON`'s epoch millis for one connection.
- **A message received in a prepared branch stays on its queue**: `messageCount` 1, `deliveringCount`
  1, `messagesAcknowledged` 0, no consumer (`listConsumers` empty), and **browse and
  `listDeliveringMessagesAsJSON` both empty**. Sent messages are on no queue (`messageCount` 0).
- **`listHeuristicCommittedTransactions` / `…RolledBack…`** → `Object[]` of base64 Xids only.
  `commitPreparedTransaction(base64)` (fixture only — never the app) → true; the branch leaves the
  prepared list and joins the heuristic one; its sends arrive.
- **`getRolesAsJSON(address)`** → JSON String array `{"name","send","consume","createDurableQueue",
  "deleteDurableQueue","createNonDurableQueue","deleteNonDurableQueue","manage","browse","createAddress",
  "deleteAddress","view","edit"}`, booleans bare. **It resolves the match itself**: a nonexistent
  address answers with `#`'s roles. `getRoles` is the same as `Object[]` rows. `address.<name>`
  attributes `rolesAsJSON`/`roles` give the same, but only for an existing address ("Problem while
  retrieving attribute" before). `securityEnabled` Boolean, `transactionTimeout` Long (300000).
- **Resolving a branch through management leaves a record** (2026-09-30, cleaning up the probe):
  `rollbackPreparedTransaction` moved the branch from prepared to `listHeuristicRolledBackTransactions`,
  as `commitPreparedTransaction` does to the committed list. No management operation clears either,
  and they are journaled. `XAResource.forget(xid)` from an XA client does — `recover()` lists them too.
  Also: `destroyQueue(name, true, true)` left two of four auto-created addresses behind;
  `deleteAddress` removed them.
- The image's default `#` grants `amq` everything but manage/view/edit; `activemq.management.#` grants
  manage but not browse or durable queues.

### Scheduled message IDs (2026-09-30, 2.55.0 and 2.57.0 identical, before Phase 14 P2 matched on them)

- **`listScheduledMessagesAsJSON`'s `userID` is the sender's `JMSMessageID`, verbatim** (`ID:…`), whether
  scheduled by JMS 2 `setDeliveryDelay` or by setting `_AMQ_SCHED_DELIVERY`; `messageID` is the numeric core id.
  So an exact-ID lookup can string-compare scheduled entries as it does the delivering list.
- **A filtered `browse`/`countMessages` for `AMQUserID = '<scheduled id>'` finds nothing** — browse never sees a
  scheduled message, filtered or not. The scheduled list is the only place it is.

### Core filter syntax (2026-09-30, 2.55.0 and 2.57.0 identical, before Phase 14 P3 wrote any)

Filtered `browse` on one queue of four messages, properties set through JMS or the core message:

- **String literal**: `'O''Brien'` matches; `'O\'Brien'` → `AMQ229020: Invalid filter`. A backslash is
  literal: `'back\slash'` matches a one-backslash value, `'back\\slash'` does not. Non-ASCII is fine.
- **Types are strict**: string `"5"` ≠ `5`; int `2` ≠ `'2'`; int `2` = `2.0`; long past int range
  works; boolean matches `TRUE`/`true`, not `'true'`. `missing = 'x'` → 0, `missing IS NULL` → all.
- **Names**: bare `my-prop = 'h1'` is **valid and matches nothing** (parsed as subtraction); `"my-prop"`
  (double-quoted) matches; `hyphenated_props:my-prop` also works. Bare `my.dotted` → AMQ229020;
  `"my.dotted"` and a quoted reserved word (`"and"`) match. JMS `setStringProperty("my-prop")` throws
  AMQ139012 client-side — set such names on the core message.
- **Headers**: `AMQDurable = 'DURABLE' | 'NON_DURABLE'`; `AMQPriority BETWEEN 4 AND 5` inclusive;
  `AMQTimestamp` = the sender's `JMSTimestamp` in millis, `>=` includes the exact stamp.
- **LIKE**: `ESCAPE '\'` works for `%` and `_` in the value.
- **Every parse failure is `AMQ229020: Invalid filter: <text>`** (`==`, unterminated literal, `AND = 1`)
  — it used to reach the page wrapped in "check the manage permission". Now `InvalidFilterException`.
- **Tomcat answers 400 to a raw `[` or `]` in a query string** (`conditions[0].name=`); a browser's form
  GET percent-encodes them, so only a hand-typed URL hits it. Use `%5B`/`%5D` when testing by URL.

### Dead-letter and expiry metadata (2026-09-30, 2.55.0 and 2.57.0 identical, before Phase 14 P4 grouped on it)

Killed after `maxDeliveryAttempts=2` (anycast, and a durable subscription), expired on delivery (TTL 500ms),
`sendMessageToDeadLetterAddress`, `moveMessages` to an ordinary queue, sent to DLQ directly, and an AMQP sender
(the image's CLI, `artemis producer --protocol amqp`) killed and expired. Seen through management `browse`:

- **Every broker move sets the same four**: `_AMQ_ORIG_ADDRESS`, `_AMQ_ORIG_QUEUE` (StringProperties),
  `_AMQ_ORIG_ROUTING_TYPE` (ByteProperties, 0 multicast / 1 anycast), `_AMQ_ORIG_MESSAGE_ID` (LongProperties, the
  original's core id). A subscription's origin queue is the subscription queue (`client.sub`), address the topic.
- **No reason, no count**: killed, operator-sent and `moveMessages`-moved messages are identical; `redelivered` false,
  `JMSXDeliveryCount` 0 on the JMS path. Only the origin's settings can hint which. A message sent to DLQ directly
  has none of them (just `_AMQ_ROUTING_TYPE`).
- **Expiry adds `_AMQ_ACTUAL_EXPIRY`** (Long epoch millis) and resets `expiration` to 0. Same for an AMQP message.
- **AMQP messages browse with section prefixes**: `extraProperties._AMQ_ORIG_ADDRESS`, `messageAnnotations.x-opt-ORIG-
  ADDRESS` (and `-QUEUE`, `-ROUTING-TYPE`, `-MESSAGE-ID`), `applicationProperties.<name>`, `properties.to`. The JMS
  path shows `_AMQ_ORIG_*` plain plus `JMS_AMQP_MA_x-opt-ORIG-*`. `userID` is the AMQP message id.
- **`autoCreateDeadLetterResources=true`** makes queue `DLQ.<address>` on the dead-letter address, MULTICAST,
  `autoCreated`, filter `_AMQ_ORIG_ADDRESS = '<address>'`.
- A core filter `_AMQ_ORIG_ADDRESS = 'x'` works on a filtered browse. `moveMessages` needs an existing target queue
  (`AMQ229049` otherwise); a dead-letter address with no queue drops the message.

### Message comparison (2026-09-30, 2.55.0 and 2.57.0 identical, Phase 14 P5)

- **The JMS `QueueBrowser` does not return an in-flight message either**, selector or not: a message received by a
  CLIENT_ACKNOWLEDGE consumer and not acked is invisible to the detail read, like management `browse`. So the detail
  and compare paths can never read an in-flight body, by construction (`MessageComparisonIT`).
- **The JMS read path reports property types**: `getObjectProperty` gives `String`/`Integer`/`Long`…, and
  `getPropertyNames` includes `JMSXDeliveryCount` (Integer, 0 on a browse). The CLI's `producer --message` adds
  `ThreadSent` (String) and `count` (Long) to every message — expect them in any CLI-seeded comparison.

## Verified behaviour

- **Both read paths are non-destructive.** Counts, delivering and acked unchanged after paging
  through a 1200-message queue, and after five exports of text, bytes and multicast queues.
- **Export carries whole bodies** (2026-09-19): 1000-char text bodies export intact where they were
  previously 256 chars plus the marker; `--message-size` bytes messages export with a real body where
  they were previously the "no text body" placeholder; a multicast `events::sub-a` subscription
  exports through its FQQN.
- **The `events` address with `sub-a`/`sub-b`** is what exercises the FQQN browse path end to end.
  Worth recreating whenever that path changes — a bare name fails silently, not loudly.

## Measured limits

Taken against one containerised broker on this machine, 100,000 messages of 200 characters in one
queue, with the other queues small. Times are end-to-end HTTP, not broker time.

| What | At 100k |
|---|---|
| Overview (all queues) | ~310ms |
| Queue page 1 | ~400ms |
| Queue **page 2000** | ~310ms — deep paging is flat, which is the whole point of server-side paging |
| Filtered queue page | ~300ms |
| Diagnose | ~300ms |
| Cross-queue search | ~300ms, including a match at position 99,999 |
| Export 5,000 messages | ~1.7s, 2MB |

- **Seeding is the slow part**, not reading: 100,000 messages took 2m22s to produce through the CLI.
- Nothing degraded with depth. The ceiling found at this size was not performance at all — it was
  the filtered-count window above, which made search silently wrong from about 200 messages.

### Phase 10/11 pages at scale (2026-09-27, Phase 12 P0)

One broker holding: an address with **300 durable subscriptions** × 20 messages; a default-window
consumer buffering **4,434** tiny messages; an unbounded one holding **4,900** (under the 5,000
limit) and one holding **8,000** (over it); **50 queues** × 100 in flight. ~355 queues in all.
End-to-end HTTP from the browser, three runs each, warm.

| What | Time | Page |
|---|---|---|
| `/address`, 300 subscriptions (300 `firstMessageAge` reads) | ~240ms | 453KB |
| `/address` find by filter, 300 subscriptions | ~1.2s | 1MB |
| `/address` find by message ID (300 browses + in-flight checks) | ~400ms | 626KB |
| Queue page, 4,434 in flight (200 rows drawn) | ~110ms | 238KB |
| Queue page, 4,900 in flight, one consumer | ~95ms | 188KB |
| Queue page, 8,000 in flight (over the limit, not read) | ~40ms | 34KB |
| Filtered queue page (with the next-page probe) | ~45ms | 111KB |
| Cross-queue search by message ID (reads 52 delivering lists) | ~390ms | 5KB |
| Cross-queue search, ordinary filter, 355 queues | ~1.05s | 2MB |
| Diagnose (reads ~13,000 in flight within its 20,000 budget) | ~390ms | 288KB |

- **No fix was called for.** The slowest, address find by filter, is one browse per subscription
  plus 1MB of HTML for 300 rows of up to 20 matches — linear, and bounded by `FIND_LIMIT`.
- The default 1MB window held 4,434 of these (~30-character bodies), not the ~3,200 measured for the
  earlier probe's messages: the window is bytes, so the count depends on message size.

### Phase 13 pages at scale (2026-09-30, 2.55.0, `ScaleMeasurementIT -Dmeasure=true`)

One broker: 1,000 queues × 20 messages, 100 multicast addresses × 3 durable subscriptions × 10,
150 prepared XA branches (over the 100-branch detail limit). Seeded in 10s. Times are MockMvc in the
test JVM (no HTTP hop), median of three, warm; calls are `ManagementChannel` round trips.

| Page | Time | Response | Calls |
|---|---|---|---|
| `/overview` | 140ms | 1.2MB | 7 |
| `/addresses` | 185ms | 2.3MB | 12 |
| `/broker` | 18ms | 12KB | 17 |
| `/connectivity` (standalone) | 71ms | 7KB | 19 |
| `/transactions` (150 prepared, summaries only) | 18ms | 137KB | 5 |
| `/queues?name=` | 125ms | 101KB | 19 |
| `/address?name=` (3 subscriptions), with or without find | ~100ms | 18KB | 36 |
| `/client` | 8ms | 9KB | 7 |
| `/diagnose` | 438ms | 873KB | 550 |
| `/snapshot?format=json` / `text` | ~700ms | 2.7MB / 337KB | 987 |
| `/search` (1,000 queues) | 526ms | 3KB | 1,013 |

- **List pages do not scan per resource**; `PagesIT` asserts their call counts are unchanged by 30
  more queues. Diagnose (~550: the operator-block check stops at 500 addresses), the snapshot
  (diagnose + 200 settings + 200 roles) and search (one browse per queue, by design) are the
  per-resource ones, and each is bounded or documented.
- **The detail reply for 150 branches plus one of 5,000 messages: 1.8MB in 128ms** — ~360 bytes per
  message. Only the branch count is bounded (100); one huge branch under that still costs its size.
- **Trend history at its cap (240 readings × 500 queues) retained ~10.3MB** of heap per session,
  measured by GC-settled heap delta — rough, but it corrected an earlier "a few MB" claim.
- `/overview` and `/addresses` responses grow with the broker (1–2MB at 1,000 queues); the call
  count does not.

### Phase 14 workflows at scale (2026-09-30, 2.55.0, same broker and method as above)

| Workflow | Time | Response | Calls |
|---|---|---|---|
| `/search` exact ID, found on the last of 1,000 queues (bare or `AMQUserID =`) | ~520ms | 18KB | 1,019 |
| `/search` exact ID nobody sent | 537ms | 18KB | 1,018 |
| `/search?build=run` (priority, or a property condition), every queue | ~505ms | 15KB | 1,014 |
| Queue page with a built filter | 102ms | 113KB | 16 |
| `/saved`, `/compare` (form) | ~1ms | 3–7KB | 0 |
| `/saved/{id}`: every queue / one queue / one address | 1 / 43 / 68ms | 3KB | 0 / 6 / 12 |
| `POST /compare`, two 2.7MB snapshots | 52ms | 15KB | 0 |

- **A saved search's scope check costs the paged listing**: 1 and 2 calls on the small shared broker,
  6 and 12 at 1,000 queues. `PagesIT`'s per-scope bound (0/1/2) holds only for a small broker.
- **Two read snapshots held ~10MB** (GC-settled delta) for 2.7MB of JSON each, trends skipped.

## Traps hit while working

- **`mvn spring-boot:run` forks a JVM, and stopping the Maven process does not stop it.** The
  orphan keeps port 8080, so the next run fails with "Port 8080 was already in use" while the stale
  app keeps serving — against *old* classes and *new* templates, which shows up as a SpringEL error
  for a record accessor that exists in the source. Kill by port, not by task:
  `Get-NetTCPConnection -LocalPort 8080 -State Listen` → `Stop-Process -Force`.

- **Windows Python rewrites line endings** (2026-09-27): a `python -` read/replace/write in Git Bash
  turned LF files into CRLF, and a 2-line template edit showed as a 300-line diff. Sources and
  templates are LF; `docs/PRD.md` is **CRLF again** in the index (seen 2026-09-30), and its working copy was
  silently rewritten to LF once during that day (`core.autocrlf` is false) — a 2,158-line diff — detect with
  `git ls-files --eol`, not `grep -c $'\r'`, which Git Bash's grep reports as 0 on a CRLF file.
- **Python's `open()` defaults to cp1252 here** (2026-09-30): an edit script wrote `—` as byte 0x97
  into a Java file, which javac then rejects as invalid UTF-8. Always pass `encoding='utf-8'`.
  Check `git diff --stat` after any scripted edit.
- **PowerShell 5.1 `Set-Content -Encoding utf8` writes a BOM** (2026-09-30): javac then fails with
  "illegal character: '\ufeff'". And `Get-Content -Raw` read UTF-8 as cp1252, so `—` came back as `â€”`.
  Edit through Python with `encoding='utf-8'` or the Edit tool instead.
- **Jackson 3 fails a record on a missing primitive** (2026-09-30): a saved-search entry without
  `internal` made the whole file unreadable ("Cannot map `null` into type `boolean`") —
  `FAIL_ON_NULL_FOR_PRIMITIVES` is on. `SavedSearchStore` turns it and unknown-property failures off.
- **Stale surefire reports can pass a broken build** (2026-09-30): `mvn -q test | grep "Tests run:"`
  showed nothing, and summing `target/surefire-reports` gave the previous run's green total — the
  compile had failed, and `-q` plus a grep for "Tests run" hid it. Delete the reports first, and grep
  for `ERROR` too. Also: a `'\n'` written through a quoted heredoc reached the Java file as a real
  line break.
- **A multi-line Python heredoc in the Bash tool can fail** with "unexpected EOF while looking for
  matching `''" when the script holds many quotes (2026-09-29). Write the script to the scratchpad
  and run it instead.
- **Jackson 3 `readTree(JsonParser)` fails on "trailing tokens"** (2026-09-30): reading one value
  out of a larger document (streaming past `sections.trends`) threw `FAIL_ON_TRAILING_TOKENS`, which
  3.x enables by default. Disable it on that mapper and check for trailing content yourself.
- **Thymeleaf: `th:replace` outranks `th:if` on the same element** (2026-09-30) — the fragment is
  inserted whatever the condition. Put the condition on a wrapping `th:block`. And a `Map` iterates as
  `LinkedHashMap$Entry`, on which `e.key()` fails (EL1004E); use `e.key`.
- **`@WebMvcTest` here enforces no CSRF** (2026-09-30): a multipart POST without a token got 200. CSRF
  checks belong in `LocalWithoutLoginTest` (full context), which is where `/compare`'s is.
- **Tomcat 11.0.24 answers an oversized multipart upload with 413 itself** (2026-09-30), before
  Spring: a `@ControllerAdvice` for `MaxUploadSizeExceededException` never ran, and putting the CSRF
  token in the URL made no difference (413 either way, not 403). `templates/error/413.html` is what
  shows. `MockMvc` enforces no multipart limit, so only the running app shows this.
- **`docs/PRD.md` was LF in the working copy again at the start of 2026-09-30's P1 session**, before
  anything here touched it; `git checkout -- docs/PRD.md` restored CRLF. Check `git diff --stat` first.
- **Closing an `ActiveMQConnectionFactory` closes the connections it made** (2026-09-30): a test's
  "holder" connection created inside `try (factory)` was gone once the block ended, and its three
  unacked messages were back to waiting — "never reached 3 delivering". Keep that factory open.
- **Docker/WSL can drop containers mid-run** (2026-09-30): one `-Pintegration` run lost
  `TransactionsIT`'s broker ("Session is closed") and `TrendsIT` timed out connecting, while the
  long-running `artemis-test` container exited 255 at the same moment. Re-run before suspecting code;
  check `docker ps -a` for exits.
- **`@WithMockUser` does not authenticate in these MockMvc tests** (2026-09-27, Boot 4.1.1): in the
  full-context `SecurityConfigTest` a request under it was redirected to `/login`, and in
  `PageRenderingTest` the page rendered with no `Principal`. A CSRF test "passed" on that redirect
  from Phase 7 on. Sign in for real (`signedIn()` in `SecurityConfigTest`) and pin the redirect target.
- **A local run needs no login** since 2026-09-27 — before that the default `mvn spring-boot:run`
  opened on a sign-in form nobody could pass, despite the README. See `SecurityConfig.openLocally`.

- **Two apps on `localhost` share cookies across ports** (2026-09-30, Phase 15 lab): with both on the
  default `JSESSIONID`, connecting Artemis Browser (:8080) replaced the lab's (:8082) session and every
  lab form then failed CSRF with a bare 403 and nothing in any log. The lab names its cookie
  `ARTEMISLAB_SESSION`. Any further local app beside Browser needs its own cookie name too.
- **The Bash tool's heredoc collapses a doubled backslash to one** (2026-09-30): `split("\\.")` written
  through `cat <<'EOF'` reached the Java file as `split("\.")` — "illegal escape character" (and this
  very entry lost its backslashes the same way). Write Java
  through the Write tool, not heredocs, whenever it holds a backslash.
- **Testcontainers' reaper took ~40s, not ~10s,** to remove the lab's broker after the lab JVM was killed
  (2026-09-30, Ryuk 0.14.0, Docker Desktop npipe). A lab restarted inside that window finds 62616 still
  held — and lists the container as a leftover, which is what that list is for.
- **A queue browses in priority order, not send order** (2026-09-30, 2.55.0 and 2.57.0): with priority
  `seq % 10` on 300 messages, a JMS `QueueBrowser` returned seq 259 (priority 9) before seq 251, and the
  first "late" marker sat at position ~26. Highest priority first, then arrival. Management `browse`
  and Browser's pages follow the same order. A fixture that needs a message at a queue *position* must
  control priorities (the lab's SEARCH gives its late markers priority 0, landing them at 291–300).
- **Dots in a JMS client id are backslash-escaped in a durable subscription's queue name** (2026-09-30,
  2.55.0): client id `lab.r1` + subscription `durable-all` made queue `lab\.r1.durable-all` — the
  separator dot is not escaped, the ones inside the client id are. Not `lab.r1.durable-all`, as
  `clientId.subName` suggests. The lab uses dot-free client ids. Browser has never been shown such a
  name; worth a fixture.
- **A JMS send to a queue named after an address auto-creates a queue of that name** (2026-09-30,
  observed on 2.55.0): address `orders` with anycast queue `orders-q`; `session.createQueue("orders")`
  and six sends made the client create queue `orders` on the address, and anycast then split the six 3/3.
  Send by FQQN (`orders::orders-q`) to reach a differently named queue.
- **Verified for the lab's worker recipes** (2026-09-30, 2.55.0 and 2.57.0): `addAddressSettings(address,
  json)` takes camelCase keys (`maxDeliveryAttempts`, `deadLetterAddress`, `expiryAddress`,
  `redeliveryDelay`, `autoCreateQueues`, `autoCreateAddresses`) and `removeAddressSettings(match)` undoes
  it; an exclusive divert that matches also skips the address's non-exclusive diverts (audit got only
  the 3 non-eu of 6); `address.<name>` attribute `unRoutedMessageCount` counts multicast publishes with no
  queue; a dead-letter address that does not exist is not auto-created when a message is killed (cleanup
  found nothing left); a container restart keeps the node id and durable messages, and held messages
  return to their queue; a 2s TTL expired within the default 30s scan.
- **A browse-only consumer is not in a queue's `consumerCount`** (2026-09-30, measured on 2.55.0 and
  2.57.0, `QueueBehaviorIT`): a JMS `QueueBrowser` held open mid-enumeration is in
  `listAllConsumersAsJSON` with `browseOnly` true while `listQueues` reads `consumerCount` 0. Diagnose
  used to say "no consumer attached" (procedure Q01); fixed — it counts browsers from the consumer
  listing it already reads and says "Only browsers are attached … nothing is consuming it", and says
  "not checked" when that listing is unreadable.
- **`diskStoreUsage` reads 0 until the broker's first periodic disk check** (2026-09-30, 2.55.0 and 2.57.0):
  read straight after startup it was 0.0 on a broker with `max-disk-usage` 1%; a few seconds later it
  was above 1%. Poll for a reading. With `max-disk-usage` 1% the store counts as full with nothing
  written to fill it — the lab's *Disk threshold reached* profile.
- **Under `management-message-rbac`, a denied *attribute* reads "Problem while retrieving attribute"**
  (2026-09-30, both versions), a denied *operation* AMQ229032 — confirmed through the lab's viewer user.
  Confirm the admin can read an attribute before taking another user's failure as a refusal.
- **A replacement entrypoint that creates the instance only when `etc/broker.xml` is missing survives
  restart and stop/start** (2026-09-30): the lab's profiles kept their RBAC after a 5s and a 20s
  interrupt, same node id. The image's own `/docker-run.sh` uses the same test.
- **For a user without `manage`, `/overview` also says "Queues (0) … This broker reported no
  queues"** under the correct AMQ229032 explanation (2026-09-30, 2.55.0) — an open Browser defect for
  procedure E02, recorded in the Phase 15 plan.
- **A job cancelled through its executor `Future` before it started never ran and never finished**
  (2026-09-30, lab): `Future.cancel` on a not-yet-started task stops it from running, so the lab's job
  stayed RUNNING and blocked its run's cleanup. Cancel by flag plus interrupting the job's own thread.
- **A container is not "launched here" until `start()` returns** (2026-09-30): the lab first recorded
  ownership by container id after start, so while provisioning, its own starting broker was listed as
  a removable leftover. Ownership is now the broker-id label, recorded before the container exists.

## Testing

- **`JMSManagementHelper` refuses a foreign message**: "Cannot send a foreign message as a
  management message". It requires a real `ActiveMQMessage`, so a mocked `Session` cannot get as far
  as sending a request — every management round trip has to be tested against a real broker, not
  mocked. `ManagementChannelTest` is therefore small on purpose; `ManagementChannelIT` carries the
  rest.
- **A timeout is produced deterministically by addressing a non-management address.** Nothing is
  listening there, so the receive runs out. Asking a healthy broker for a slow reply is the flaky
  alternative.
- **Spring Boot 4.1.1 manages `org.testcontainers:testcontainers` (2.0.5) but not its
  `junit-jupiter` module.** Drive the container from `@BeforeAll` and the extra artifact is not
  needed.
- **A JMS durable subscription's queue is named `clientId.subscriptionName`** — `it-client.it-sub`
  for client id `it-client` and subscription `it-sub` — bound to the topic's address. That is the
  cheapest way to create the address != queue name case the FQQN path needs. The subscription must
  exist *before* anything is published, or the publication is dropped with nowhere to route.

## Verified UI behaviour

- **A dropped broker connection** (2026-09-19, verified by killing the container mid-session): the
  app does NOT redirect to the connect form on its own. It stays on the page and renders
  "Management call broker.listQueues() failed: Session is closed", and `isConnected()` still
  answers true because the JMS objects are non-null. `ConnectionLostException` now carries that
  case, deliberately **not** extending `BrokerException` — the controllers catch that one to show
  an inline error, which would swallow it before the advice could redirect.

- **Export of a cross-queue search** (2026-09-19): 9 messages across 3 queues came out with whole
  400-character bodies in both CSV and JSON, where the search page itself shows 200-character
  previews. The search page's "showing first 50" links through to the queue view with the filter
  applied, which pages correctly through 60 matches.
- **The overview sorts and filters server-side**, no JavaScript: column headings are links, and the
  refresh control carries `sort`/`dir`/`q` as hidden inputs. Note the row loop variable in
  `overview.html` is `queue`, not `q` — `q` is the search box, and naming the loop variable `q`
  silently shadows it.

## Cluster deployment (KinD `claude-local`)

- **Helm on PATH is 4.2.2**, not 3.x; `genSelfSignedCert` and `lookup` are both present. Three
  nodes, so `kind load docker-image` pushes to all of them and takes 30–60s.
- **No registry.** `imagePullPolicy` must be `IfNotPresent` and the tag must never be `latest`.
  Rebuilding on the same tag changes nothing in the pod spec, so nothing restarts — `kubectl
  rollout restart` is part of the loop, not an afterthought.
- **Maven cannot run inside a build container here**: Nexus lives at `localhost:8081` via Maven's
  own `conf/settings.xml`. The jar is built on the host and only copied into the image.
- **`server.ssl.enabled=true` must be explicit.** Boot's own `Ssl.enabled` already defaults to
  true, so PEM certs alone serve HTTPS — but `ReachabilityGuard` reads `${server.ssl.enabled:false}`
  and refuses to start with TLS visibly working. Highest confusion per character in the chart.
- **Boot 4.1.1 takes PEM directly** — `server.ssl.certificate` / `server.ssl.certificate-private-key`
  live in `spring-boot-web-server-4.1.1.jar`, so a `kubernetes.io/tls` Secret mounts with no PKCS12
  conversion.
- **`server.forward-headers-strategy=native` is load-bearing** wherever the pod serves TLS behind an
  ingress that does not. Tomcat marks `JSESSIONID` `Secure` for a request that arrived over TLS, a
  browser on `http://` discards it, and the login loops with nothing in any log. `framework` does
  not work: it wraps the request rather than mutating the one the cookie flag is read from.
  Verified: through the ingress the cookie comes back `Path=/; HttpOnly` with no `Secure`.
- **Probes**: `/app.css` with `Host: localhost`. `/login` creates a session per probe (30m timeout);
  the kubelet's default Host is the pod IP, which `AllowedHostFilter` 403s — the pod then never goes
  Ready and the log shows only access denials.
- **Ingress HTTPS (2026-09-27)**: `ingress.tls.secretName` = `artemis-browser-ingress-tls`, an mkcert
  cert from `scripts/new-tls-secret.ps1` (expires 2028-12-27), used only while the Secret exists
  (`lookup` in the ingress template). Separate from `artemis-browser-tls`, the pod's own cert. With
  it, `/login` over HTTPS returns `JSESSIONID ... Secure; HttpOnly` and ingress-nginx adds HSTS.
  This machine's hosts file had no `artemis-browser.claude.local` line at the time.
- **The cluster's own conventions**: ingress-nginx v1.15.1, class `nginx`, `<app>.claude.local` with
  a hosts-file line each (no wildcard); node maps host :80/:443 straight through. Artemis lives in
  **both** `jms` and `claude-app` as Service `artemis` (61616/8161, artemis/artemis). Do not label
  a namespace for Istio injection — a sidecar in front of a pod terminating its own TLS is a second
  interception point nobody designed.

## Template rendering

- **A rendering test is only worth what it fails on** (2026-09-20). Every template was broken in
  turn with `<div th:text="${brokenOnPurpose.nope()}">` before `</body>`, and the failures counted:
  all 19 `PageRenderingTest` cases failed, one template at a time, none passing for an unrelated
  reason. Worth repeating rather than trusting — a shallow `containsString` can match markup the
  layout fragment emits, and would stay green while the page itself was dead.
- **`@WebMvcTest` takes a list of controllers.** One class covers all nine pages; the mocked beans
  are the union of what those controllers inject. Cheaper than a context per controller, and the
  shared `@BeforeEach` "connected, one queue, empty everything else" is what most cases need.
- **`AllowedHostFilter` runs in `@WebMvcTest`.** Every request needs `.header("Host", "localhost")`
  or it is 403 before any template renders — which looks exactly like a broken page.

## Conventions

- **Memory lives in `.claude/memory.md` here**, where all three sibling projects keep `memory.md` at
  the repo root. Deliberate divergence — don't "fix" it by moving the file back. It is committed on
  purpose, so it is shared across Claude Code, Desktop and Web.
