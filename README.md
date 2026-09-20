# artemis-browser

A read-only web browser for ActiveMQ Artemis queues: connect to a broker, list its queues and
addresses, inspect a queue's counters and messages, search across queues, export results, and check
broker health, connections and producers — all without ever consuming, acknowledging, moving or
deleting a message. Spring Boot, Thymeleaf server-rendered, no npm and no frontend build step.

Shipped so far:

- **Phase 1** — connect (host/port/username/password), list queues, inspect one queue's counters and
  messages.
- **Phase 2** — all-queues overview with optional auto-refresh, server-side pagination, filtering, a
  per-message detail view, and remembered broker locations.
- **Phase 3** — cross-queue search, CSV/JSON export, a broker health and connections view, and an
  address view showing multicast fan-out.
- **Phase 4** — a producers panel on the broker health page.

For the architectural "why" behind these decisions, see `CLAUDE.md`; day-to-day discoveries and
environment quirks are logged in `.claude/memory.md`.

## Requirements

- JDK 21 (`java.version` / `maven.compiler.release` in `pom.xml`)
- Maven on `PATH` (no wrapper is committed)
- Dependency resolution goes through whatever mirror your Maven `settings.xml` points at. In this
  environment that's a local Nexus (`mirrorOf *`), configured in Maven's own `conf/settings.xml`
  rather than `~/.m2` — if it's down, nothing resolves. A different environment with a plain Maven
  Central `settings.xml` should resolve fine, since nothing here is Nexus-specific in the pom itself.
- A reachable ActiveMQ Artemis broker to connect to at runtime (not needed to build or unit-test).

## Build

This is a single-module project — there is no reactor.

```bash
mvn clean install    # full build
mvn test              # all tests
mvn test -Dtest=SomeTest              # one test class
mvn test -Dtest=SomeTest#someMethod   # one test method
```

`java-formatter-maven-plugin` reformats all Java sources on every build (Allman braces, its own
wrapping), bound to the default lifecycle — expect `git status` to show modified source files after
a build even if you changed nothing. Write code in whatever style is natural and let it reformat;
don't hand-match its output. `jacoco-maven-plugin` is pinned to 0.8.15 rather than the 0.8.12 the
sibling projects use, because 0.8.12 can't instrument class files built by the JDK 26 that's on
`PATH` here even though the build targets release 21.

## Run

```bash
mvn spring-boot:run
```

Starts the app on `http://localhost:8080`. It binds `server.address=127.0.0.1` and rejects any
request whose `Host` header isn't a loopback literal (`LoopbackHostFilter`) — it has no login of its
own and holds a live authenticated broker connection instead, so it must not be reachable from the
network. Both the bind address and the filter are load-bearing; binding loopback alone doesn't stop
DNS rebinding.

Once running, open `/` to connect to a broker (host, port, username, password — the password is
never persisted). From there:

| Path | Page |
|---|---|
| `/` | Connect / disconnect, saved broker locations |
| `/overview` | All queues, optional auto-refresh, paged and filtered |
| `/queues` | One queue's messages (paged, filtered) |
| `/message` | Single message detail (full body, any message type) |
| `/addresses` | Addresses and the queues under them (multicast fan-out) |
| `/search` | Cross-queue search |
| `/export` | CSV/JSON download of a search or queue result |
| `/broker` | Broker health, acceptors, connections, consumers, producers |

To test against a real broker rather than mocks, see the container recipe in `.claude/memory.md`
(note it maps host port 62616, not 61616, because 61616 is already taken in this environment).

## Configuration

Keys from `src/main/resources/application.properties`:

| Key | Default | Meaning |
|---|---|---|
| `server.address` | `127.0.0.1` | Loopback-only bind; see Security below |
| `server.port` | `8080` | HTTP port |
| `server.servlet.session.timeout` | `30m` | The broker connection lives in the HTTP session, so session expiry is connection expiry |
| `artemis.management-timeout-ms` | `10000` | Timeout for a management query to the broker |
| `artemis.connection-timeout-ms` | `10000` | Timeout for establishing the broker connection |
| `artemis.body-preview-chars` | `200` | Body characters shown per row in a message list |
| `artemis.body-detail-chars` | `200000` | Body characters shown in the single-message detail view (and used for export) |
| `artemis.connections-file` | *(blank)* | Where remembered broker locations (host/port/username, never passwords) are stored; blank defaults to `${user.home}/.artemis-browser/connections.json` |
| `artemis.search-max-per-queue` | `50` | Messages fetched per matching queue during cross-queue search |
| `artemis.export-max-messages` | `5000` | Upper bound on a single export, so a download can't try to pull an entire large queue |
| `artemis.export-body-scan-limit` | `20000` | How far export's JMS pass will walk a queue to find the bodies it needs (see Exports below) |

## Security posture

Read-only is the product, not a detail: nothing in this codebase consumes, acknowledges, moves, or
deletes a message. Loopback-only binding plus `LoopbackHostFilter` are both required, and if this
app is ever made network-reachable, the filter is not the thing to relax — real authentication would
have to be built first. See `CLAUDE.md` and `.claude/memory.md` for the specific traps already found
and fixed (e.g. a naive `startsWith("127.")` check that a hostname like `127.0.0.1.attacker.com`
would have defeated).

Filters exposed to users (overview, queue, search) use Artemis **core** filter syntax
(`AMQPriority`, `AMQTimestamp`, `AMQDurable`, `AMQSize`, or a property by its bare name) — not JMS
selector syntax. A JMS-style `JMSPriority = 4` is not rejected, it silently matches nothing. JMS
selector syntax applies only to the single-message detail path.

CSV/JSON exports treat message bodies as untrusted content: every field is quoted, and a leading
`=`, `+`, `-` or `@` is prefixed with an apostrophe so a downloaded file isn't evaluated as
spreadsheet formulas (`MessageExporterTest` covers it).

## Exports and message bodies

The message *list* is read through Artemis management `browse`, which truncates a body at the
broker's `management-message-attribute-size-limit` (256 characters by default) and appends a literal
`", + N more"` to the value itself — and which has no body at all for bytes, map or stream messages.
That is fine for a table cell and wrong for a download, so `/export` builds its list from management
browse (the broker still does the paging and the core filtering) and then fills in the bodies that
need it from a single JMS browser pass, which reads real bodies of any type. `bodyTruncated` says
whether what you got is the whole body. The pass is bounded by `artemis.export-body-scan-limit`;
anything it doesn't reach keeps its management body, flagged truncated rather than passed off as
complete. Like every other read here, it consumes nothing — verified against a live broker with
counters unchanged after repeated exports.

## Layout

```
com.culberth.tools.artemisbrowser
├── ArtemisBrowserApplication      Spring Boot entry point
├── broker/                        Everything that talks to Artemis
│   ├── BrokerSession              @SessionScope: one live connection per HTTP session (never the password)
│   ├── BrokerCredentials          Password carrier from the connect form to connect(), not retained
│   ├── ConnectionInfo             What the session keeps after connecting: host/port/username only
│   ├── ManagementChannel          Request/reply plumbing over the activemq.management address
│   ├── QueueDirectory             Lists queues + counters in one listQueues call
│   ├── QueueBrowseService         Owns both read paths: management browse (paged list) and JMS QueueBrowser (single-message detail)
│   ├── AddressDirectory           Groups queues under their addresses (multicast fan-out)
│   ├── MessageSearchService       Cross-queue search (countMessages first, then browse only matches)
│   ├── MessageExporter            CSV/JSON export with formula-injection defusing
│   ├── BrokerInfoService          Broker health, acceptors, connections, consumers, producers
│   ├── ConnectionStore            Persists remembered broker locations to disk, passwords excluded
│   ├── QueueStats / QueueOverview / MessagePage / MessageSummary / MessageDetail
│   │                              Queue and message view models, including FQQN browse-name handling
│   ├── AddressOverview / BrokerConnection / BrokerConsumer / BrokerProducer / BrokerHealth / AcceptorInfo / SearchResult
│   │                              Broker/address/search view models
│   └── BrokerException / NotConnectedException
│                                  Broker-facing error types
├── web/                           Thymeleaf controllers
│   ├── ConnectionController       / connect, disconnect, forget a saved connection
│   ├── QueueController            /overview, /queues, /message
│   ├── BrokerController           /broker, /addresses
│   ├── SearchController           /search, /export
│   ├── ConnectForm                Connect page form backing object
│   ├── LoopbackHostFilter         Rejects any request whose Host header isn't a loopback literal
│   └── BrokerErrorAdvice          Translates broker errors into user-facing pages
└── (resources)
    ├── application.properties     See Configuration above
    └── templates/
        ├── fragments/layout.html  Shared nav — edited once when a page is added
        ├── connect.html, overview.html, queues.html, message.html,
        │   addresses.html, broker.html, search.html
        └── static/app.css
```

See `CLAUDE.md` for the deeper rationale behind these boundaries (why there are two read paths, why
addresses and queues are modeled separately, etc.) and `.claude/memory.md` for verified broker
response shapes, environment-specific gotchas, and what's already been fixed.
