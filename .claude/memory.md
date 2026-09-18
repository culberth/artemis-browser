# Memory — artemis-browser

Append-only working notes. One or two lines each: date, what, why.
Read this at the start of every session. Consolidate when it gets repetitive.

---

- **2026-09-18 — UI is a web interface, not JavaFX.** Decided explicitly against the pattern of all
  three sibling projects. Why: they're JavaFX desktop apps, so their scene-graph, FXML, TestFX and
  ribbon-CSS code is a trap to copy from here.

- **2026-09-18 — Domain settled: a read-only browser for ActiveMQ Artemis queues.** Phase 1 is
  connect (host/port/user/password) -> list queues -> inspect one without consuming.

- **2026-09-18 — Frontend settled: Thymeleaf, server-rendered, no build step.** Why: three screens
  do not justify npm in the dev loop. Dropdowns self-submit with a one-line inline onchange.

- **2026-09-18 — Security settled: loopback-only, no app login.** `server.address=127.0.0.1` plus
  our own `LoopbackHostFilter`. Why both: binding loopback does not stop DNS rebinding, and this app
  holds a live authenticated broker connection with no login of its own. If it is ever made
  network-reachable, that filter is not the thing to relax — real authentication has to be built.

- **2026-09-18 — The broker password is never retained.** `BrokerCredentials` carries it only from
  the form to `connect()`; the HTTP session keeps `ConnectionInfo` (host/port/user), which has no
  password field. Reconnecting means retyping it. Don't "improve" this by caching it for reconnects.

- **2026-09-18 — `startsWith("127.")` is not a loopback check.** It accepts
  `127.0.0.1.attacker.com`, a hostname an attacker owns — which defeats the whole filter. Octets are
  parsed individually now. A test covers it; don't simplify it back.

- **2026-09-18 — Queue listing is a management call, not JMS.** JMS has no "list queues". We send a
  message to `activemq.management` naming resource `broker`, operation `getQueueNames`, with a
  temporary reply queue. Needs the `manage` permission on that address — the failure mode is a
  rejection, not a hang. Chose this over JMX/Jolokia: no extra port.

- **2026-09-18 — Exclude our own management reply queue from the listing.** It is a real temporary
  queue, so the broker reports it and the dropdown showed a UUID "queue" that was our own plumbing.
  `QueueDirectory` filters it by `ManagementChannel.replyQueueName()`.

- **2026-09-18 — Browse by FQQN when address != queue name.** Artemis resolves a bare name against
  addresses first, so any multicast subscription must be browsed as `address::queue`. Symptom if you
  get it wrong is "that queue is always empty", not an error. `QueueStats.browseName()` handles it.

- **2026-09-18 — Never call `getObject()` on a browsed ObjectMessage.** That deserializes whatever a
  producer put on the queue, inside this process. The browser reports the type and stops.

- **2026-09-18 — No `.gitignore` yet.** Add one before the first `mvn` run or `target/` lands in the
  working tree. If it ignores `.claude/`, carve out `.claude/memory.md` or this file stops being
  shared.

- **2026-09-18 — Sibling convention differs on memory location.** All three siblings keep `memory.md`
  at the repo root; this project uses `.claude/memory.md` per the Substack approach Bo is trying out.
  Why noted: don't "fix" the divergence by moving it back.

- **2026-09-18 — pom trap: do NOT define an `artemis.version` property.** spring-boot-dependencies
  defines a property by exactly that name and uses it to import `org.apache.artemis:artemis-bom`.
  Ours silently redirected that import to a non-existent coordinate, and the build failed while
  resolving the parent — before it ever read our dependency. Declare
  `org.apache.artemis:artemis-jakarta-client` with no version and let the BOM manage it.

- **2026-09-18 — Artemis moved groupId to `org.apache.artemis`** (was `org.apache.activemq`), and
  the Jakarta-namespace artifact is `artemis-jakarta-client`. `artemis-jms-client` is the old
  `javax.jms` one and fails at runtime under Spring Boot 4, not at compile time.

- **2026-09-18 — Spring Boot 4 moved `@WebMvcTest`** out of `spring-boot-test-autoconfigure` into
  its own `spring-boot-webmvc-test` artifact, package
  `org.springframework.boot.webmvc.test.autoconfigure`. Also: the MVC starter is now
  `spring-boot-starter-webmvc`.

- **2026-09-18 — Environment: Maven resolves through a local Nexus.** `mirrorOf *` ->
  `http://localhost:8081/repository/maven-public/`, local repo
  `P:/maven_local_repositories/.m2/spring-boot`, both set in Maven's own `conf/settings.xml`, not
  `~/.m2`. If Nexus is down, nothing resolves. It does proxy Maven Central successfully.

- **2026-09-18 — Environment: JDK on PATH is 26, but we target release 21.** JaCoCo 0.8.12 (the
  sibling projects' pin) cannot instrument Java 26 class files — "Unsupported class file major
  version 70". Pinned 0.8.15 here.

- **2026-09-18 — Environment: ports 61616 and 8161 are already taken** by a kind Kubernetes cluster
  (`claude-local-control-plane`). The test broker container therefore maps 62616->61616 and
  8162->8161. Connect the app to `localhost:62616`, not 61616.

- **2026-09-18 — Environment: Git Bash mangles container-absolute paths.** `docker exec ... /var/lib/...`
  becomes `C:/Program Files/Git/var/lib/...`. Prefix commands with `MSYS_NO_PATHCONV=1`.

- **2026-09-18 — Test broker recipe.** `docker run -d --name artemis-test -p 62616:61616 -p 8162:8161
  -e ARTEMIS_USER=artemis -e ARTEMIS_PASSWORD=artemis apache/activemq-artemis:latest-alpine`, then
  seed with the bundled CLI (`artemis producer --destination queue://orders --message-count 5`).
  Readiness log line is "Server is now active", not "live".

- **2026-09-18 — `java-formatter-maven-plugin` rewrites sources during the build.** It is bound to
  run on every `mvn` invocation and restyles Java to Allman braces with its own wrapping. Write code
  normally and let it run; don't hand-match the style, and don't be surprised when `git status` shows
  source files modified after a build you thought was read-only.

- **2026-09-18 — Phase 1 shipped.** PR #1 merged into `Milestone001`; work continues on `phase02`.

- **2026-09-18 — Two filter dialects exist, and mixing them fails SILENTLY.** Management operations
  (`countMessages`, `browse`) take Artemis *core* filter syntax: `AMQPriority`, `AMQTimestamp`,
  `AMQDurable`, `AMQSize`, `AMQUserID`, custom properties by bare name. A JMS-style
  `JMSPriority = 4` is not an error — it returns 0 matches on a queue where all 5 messages are
  priority 4. JMS selector syntax belongs only on `session.createBrowser(queue, selector)`.
  Whatever the UI exposes must name its dialect, or users get confidently wrong answers.

- **2026-09-18 — `broker.listQueues(filterJson, page, pageSize)` returns a JSON String**
  `{"data":[...],"count":N}`, every value quoted as a string (`"messageCount":"0"`). One call gets
  every queue with all counters — far cheaper than per-queue attribute reads. Field is
  `messagesAcked`, not `messagesAcknowledged`. Filter arg is JSON:
  `{"field":"","operation":"","value":""}`.

- **2026-09-18 — `queue.<n>.browse(page, pageSize[, filter])` returns a Map** keyed by the literal
  string `javax.management.openmbean.CompositeData` whose value is a `CompositeData[]`. Each entry
  carries messageID, userID, address, durable, expiration, largeMessage, persistentSize, priority,
  protocol, redelivered, timestamp, type, `text` (for text bodies) and `PropertiesText`. Verified
  non-destructive: counts and delivering/acked were unchanged after browsing.

- **2026-09-18 — Phase 2 architecture: two read paths, deliberately.** The paged/filtered LIST uses
  management `browse` (server-side paging, so a 50k DLQ does not stream through the client, plus
  `countMessages` for accurate filtered totals). The single-message DETAIL uses a JMS `QueueBrowser`
  with a `JMSMessageID` selector, because management browse only exposes `text` bodies and we want
  full fidelity for bytes/map messages. Both are non-destructive.

- **2026-09-18 — Spring Boot 4.1.1 ships Jackson 3.** Package root is `tools.jackson.*`
  (`tools.jackson.databind.ObjectMapper`, `tools.jackson.core.type.TypeReference`), not
  `com.fasterxml.jackson.*`. It arrives transitively via `spring-boot-starter-jackson`, so no extra
  dependency is needed — only the right import. The 2.x `jackson-annotations` is also on the
  classpath, which makes a wrong import look plausible until it fails to resolve.

- **2026-09-18 — Phase 2 shipped**: overview page with auto-refresh, server-side pagination,
  core-syntax filtering, per-message detail, and saved connections (host/port/username, never
  passwords — a test asserts the file contains no "password" string). 52 tests.
