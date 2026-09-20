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
  -e ARTEMIS_USER=artemis -e ARTEMIS_PASSWORD=artemis apache/activemq-artemis:latest-alpine
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
- **There is no "which connection am I" call.** Our own connection is identified by finding the
  consumer sitting on our management reply queue and reading its `connectionID`.

## Verified behaviour

- **Both read paths are non-destructive.** Counts, delivering and acked unchanged after paging
  through a 1200-message queue, and after five exports of text, bytes and multicast queues.
- **Export carries whole bodies** (2026-09-19): 1000-char text bodies export intact where they were
  previously 256 chars plus the marker; `--message-size` bytes messages export with a real body where
  they were previously the "no text body" placeholder; a multicast `events::sub-a` subscription
  exports through its FQQN.
- **The `events` address with `sub-a`/`sub-b`** is what exercises the FQQN browse path end to end.
  Worth recreating whenever that path changes — a bare name fails silently, not loudly.

## Traps hit while working

- **`mvn spring-boot:run` forks a JVM, and stopping the Maven process does not stop it.** The
  orphan keeps port 8080, so the next run fails with "Port 8080 was already in use" while the stale
  app keeps serving — against *old* classes and *new* templates, which shows up as a SpringEL error
  for a record accessor that exists in the source. Kill by port, not by task:
  `Get-NetTCPConnection -LocalPort 8080 -State Listen` → `Stop-Process -Force`.

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

## Conventions

- **Memory lives in `.claude/memory.md` here**, where all three sibling projects keep `memory.md` at
  the repo root. Deliberate divergence — don't "fix" it by moving the file back. It is committed on
  purpose, so it is shared across Claude Code, Desktop and Web.
