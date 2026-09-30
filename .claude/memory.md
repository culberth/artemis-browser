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

## Traps hit while working

- **`mvn spring-boot:run` forks a JVM, and stopping the Maven process does not stop it.** The
  orphan keeps port 8080, so the next run fails with "Port 8080 was already in use" while the stale
  app keeps serving — against *old* classes and *new* templates, which shows up as a SpringEL error
  for a record accessor that exists in the source. Kill by port, not by task:
  `Get-NetTCPConnection -LocalPort 8080 -State Listen` → `Stop-Process -Force`.

- **Windows Python rewrites line endings** (2026-09-27): a `python -` read/replace/write in Git Bash
  turned LF files into CRLF, and a 2-line template edit showed as a 300-line diff. Sources and
  templates are LF; `docs/PRD.md` was CRLF until 2026-09-29 and is LF since — detect, don't assume.
  Check `git diff --stat` after any scripted edit.
- **A multi-line Python heredoc in the Bash tool can fail** with "unexpected EOF while looking for
  matching `''" when the script holds many quotes (2026-09-29). Write the script to the scratchpad
  and run it instead.
- **`@WithMockUser` does not authenticate in these MockMvc tests** (2026-09-27, Boot 4.1.1): in the
  full-context `SecurityConfigTest` a request under it was redirected to `/login`, and in
  `PageRenderingTest` the page rendered with no `Principal`. A CSRF test "passed" on that redirect
  from Phase 7 on. Sign in for real (`signedIn()` in `SecurityConfigTest`) and pin the redirect target.
- **A local run needs no login** since 2026-09-27 — before that the default `mvn spring-boot:run`
  opened on a sign-in form nobody could pass, despite the README. See `SecurityConfig.openLocally`.

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
