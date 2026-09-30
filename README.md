# artemis-browser

A read-only web browser for ActiveMQ Artemis queues: connect to a broker, list its queues and
addresses, inspect a queue's counters and messages, search across queues, export results, check
broker health, subscribers and clients, and ask why something is not moving — all without consuming,
acknowledging, moving or deleting business messages. Spring Boot, Thymeleaf server-rendered, no npm and no
frontend build step.

It runs on localhost with no login by default. It can also run on a shared host, behind its own
login and TLS, and refuses to start in the half-configured arrangement between the two — see
**Run** below.

Shipped so far:

- **Phase 1** — connect (host/port/username/password), list queues, inspect one queue's counters and
  messages.
- **Phase 2** — all-queues overview with optional auto-refresh, server-side pagination, filtering, a
  per-message detail view, and remembered broker locations.
- **Phase 3** — cross-queue search, CSV/JSON export, a broker health and connections view, and an
  address view showing multicast fan-out.
- **Phase 4** — a producers panel on the broker health page, and exports that read supported
  message bodies through JMS rather than relying on the broker's truncated preview.
- **Phase 5** — a bounded export that says which rows it cut, large-message flags, integration tests
  against a real broker in Docker (including one that proves reading consumes nothing), exporting a
  whole cross-queue search, and a sortable, filterable overview.
- **Phase 6** — scheduled messages, which a browse does not return at all and which therefore made a
  queue report messages above an empty table; message properties in the list, read from the typed
  property tables; downloading a single message as `.txt` or `.json`; and a dropped connection that
  says so instead of looking like a session timeout.
- **Phase 7** — a login of the tool's own, so it can run somewhere other than localhost; a
  *Diagnose* page answering "why is this stuck"; and a measured answer to how large a broker this
  stays usable on — which turned up a correctness bug in cross-queue search rather than a
  performance ceiling (see **Searching** below).
- **Phase 8** — Docker packaging and a Helm chart for a single-user local Kubernetes deployment.
- **Phase 9** — rendered-page tests to catch template failures as well as service/controller errors.
- **Phase 10** — address drilldowns with subscription kinds and filters, consumers, oldest-waiting
  message age, per-subscription search, address settings and diverts.
- **Phase 11** — bounded in-flight message inspection, exact message-ID lookup for in-flight
  messages, and diagnostic findings for consumer imbalance and old messages still in flight.
- **Phase 12** — corrected filtered paging and search exports, expired/killed counters, measured
  ingress and acknowledgment rates, client drilldowns, and scale measurements for the newer views.

## Phase 13: broker visibility

Phase 13 expands broker visibility and explains operational behavior. P0 and all six feature areas
(P1 address pressure, P2 queue behavior, P3 trends, P4 incident snapshots, P5 connectivity and HA,
P6 transactions and permissions) are done, and so are the phase acceptance checks: every page
fetched against a real broker with nothing changed (`PagesIT`), partial states rendered, and costs
measured at 1,000 queues:

1. Address pressure and storage details: limits, page counts, blocking and full-policy consequences.
   **Done.**
2. Queue configuration explanations: last-value, ring, non-destructive, grouping and dispatch behavior.
   **Done.**
3. Bounded session trends for backlog, throughput, expired/killed messages and consumer counts.
   **Done.**
4. Incident snapshots combining counters, settings, diagnostics and collection/completeness metadata.
   **Done.**
5. Connectivity and HA inspection from the connected broker's view, including supported outbound paths.
   **Done.**
6. Read-only prepared-transaction and address-permission inspection.
   **Done.**

Done so far (P0): every health figure, `/broker` panel, diagnose check, scheduled list, divert list
and subscription age says why it is missing — unsupported, not permitted, unavailable, could not be
read — instead of showing zero or failing the page, and a user without the `manage` permission is
told so rather than being sent back to the connect form.

Done in P1: each address page has a *Storage and limits* panel showing address memory, messages and
pages against their limits beside the full policy and what it does to a sender, whether an operator
blocked it, and its share of `global-max-size`. The address index marks an address that is full or
near its limit, and does not mark one that is merely paging. Diagnose names blocked addresses
(observed), and addresses at or near a limit that blocks, rejects or drops (inferred). The global
memory finding names the largest holders without blaming them.

Done in P2: each queue page has a *Configuration and behavior* panel with the queue's own settings
and what each one looks like from outside, plus the address's defaults for new queues. The overview
and subscription rows badge last-value, ring, exclusive, purging and dispatch-gated queues. Diagnose
findings now keep what was observed apart from configuration that may explain it. It no longer
reports an exclusive queue's single busy consumer as hoarding, nor a purge as a failed delivery.

Done in P3: the overview has a *Trend* column, and each queue page a *Recent trend* panel. Both are
built from readings this session takes while pages are open, so there is no background polling. They show
the depth over time, whether the backlog is growing, when acknowledgments stopped or resumed,
expired and killed messages now, and consumer changes. A restart, a recreated queue or a counter
reset breaks the line instead of joining two different things.

Done in P4: `/snapshot` downloads an incident snapshot, as JSON or as a text summary. It is linked
from the broker and diagnose pages. It holds the broker's identity and health, queues, addresses,
settings, clients, this session's trends and a diagnose run. Everything the broker would not give,
and everything the limits left out, is listed. There are no message bodies or credentials.

Done in P5: `/connectivity` shows the broker's HA role and replica synchronization, the cluster
topology it reports, its cluster connections with the backlog waiting for each peer, its core bridges
with their source queue's depth, and its broker connections (AMQP mirrors, federation, senders) with
a mirror's backlog. It is all this broker's view: nothing connects to a peer, backup or target.
Diagnose names an unconnected bridge, mirror or cluster peer with what is waiting behind it, and a
replication primary with no synchronized backup. The snapshot has a connectivity section. Connector
credentials, which the broker returns in clear, are never read, and a password in a URI is masked.

Done in P6: `/transactions` lists the XA branches the broker holds prepared — Xid, creation time and
age, and every message each will send or has received — and the ones an operator resolved by hand.
A message received inside a prepared branch stays on its queue as *delivering* with no consumer, and
neither browse nor the in-flight list shows it; the queue page and Diagnose now say so, and name the
transaction. Each address page has a *Permissions* panel with the roles the broker reports, and each
client page shows which roles may send and consume where that client does. The snapshot gains
transactions (headers only, no properties) and permissions sections. Nothing commits, rolls back or
changes a role. The single-user, one-broker-per-session, read-only design remains.
See [Phase 13 in the PRD](docs/PRD.md#phase-13--broker-visibility-explain-pressure-behavior-and-change)
for delivery order and acceptance criteria.

## Phase 14: incident investigation

Phase 14 helps use that evidence during an incident. P1–P3 are its core scope.

Done in P1: `/compare` compares two saved JSON snapshots of one broker, in either order, and needs
no broker connection. It shows what observably changed: queue depths and running totals, queue and
address settings, consumers and connections that came or went, and diagnose findings that are new,
no longer reported or changed, each beside the section and times it came from. Snapshots of different
brokers or schemas are refused with the reason. A section either side could not read is not
compared, so nothing in it is called removed. Counters are subtracted only across one unbroken run:
a restart (from the snapshot's new `uptimeMillis`), a recreated queue (a changed id) or a counter
that went down shows two readings, not traffic.

Done in P2: a `/search` for an exact message ID (`ID:…`, or `AMQUserID = 'ID:…'`) looks for that one
message in every state a queue can hold it in — scheduled, waiting, in flight to a consumer, and
received or sent inside a prepared XA branch — and reports each sighting with its state, queue,
address, the consumer or branch holding it, and when it was seen. Beside it is the coverage: for
each state, how many queues were checked, had nothing in that state, or were skipped, refused or
unreadable, and why. See **Looking up one message** below.

For the architectural "why" behind these decisions, see [docs/architecture.md](docs/architecture.md);
what the product is and what is planned next is in [docs/PRD.md](docs/PRD.md); day-to-day discoveries
and environment quirks are logged in `.claude/memory.md`.

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
mvn test              # unit and web tests; excludes *IT
mvn test -Dtest=SomeTest              # one test class
mvn test -Dtest=SomeTest#someMethod   # one test method
mvn verify -Pintegration              # unit tests + integration tests (needs Docker)
```

Integration tests are named `*IT` and run only under the `integration` profile, so a normal build
needs nothing but Maven. They start a real Artemis in a container (Testcontainers) and check the two
things a mock cannot: that the management request/reply plumbing works against a real broker, and
that browsing, searching and exporting leave every counter exactly where they found it. That second
one is the product's central claim, and `ReadOnlyGuaranteeIT` is what stops it being merely
believed.

### Supported broker versions

| Version | Image | Why |
|---|---|---|
| **2.55.0** | `apache/artemis:2.55.0-alpine` | what the local cluster runs |
| **2.57.0** | `apache/artemis:2.57.0-alpine` | newest release, checked 2026-09-29 |

Every `*IT` runs once against each, in its own failsafe execution with its own reports directory
(`target/failsafe-reports/deployed`, `…/newest`). The images are pinned in the pom
(`artemis.image.deployed`, `artemis.image.newest`), never `latest`. Measurements recorded against 2.44.0
in earlier phases are historical evidence, not a compatibility promise. Run the matrix after a
`clean`: failsafe merges into an existing summary, and a stale failure fails `verify`.

Every Phase 13 read was probed on both before it was parsed, and none differed between them: the
availability wording (P0), address pressure (P1), queue configuration (P2), counters across restarts
(P3), connectivity and HA (P5), prepared transactions and roles (P6). The recorded shapes are in
`.claude/memory.md`. A version that lacks an operation shows it as *not supported by this broker*,
never as a zero.

Two things the matrix turned up, both handled: the Artemis image moved from `apache/activemq-artemis`
to `apache/artemis`, and on 2.57.0 the client's default topology load balancing can send a second
connection to a different broker than the one named — so every connection factory here turns it off.

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

Starts the app on `http://localhost:8080`, bound to `127.0.0.1`. In that default arrangement it
needs no login: nothing but this machine can reach it, and `AllowedHostFilter` rejects any request
whose `Host` header isn't a loopback literal, which is what stops a remote page driving the UI
through your own browser (DNS rebinding).

To run it anywhere else — a jump host, say — set a login and TLS as well:

```bash
mvn spring-boot:run -Dspring-boot.run.arguments=--hash-password=yourpassword   # prints a bcrypt hash
```

```properties
server.address=0.0.0.0
artemis.auth.username=you
artemis.auth.password-hash=$2a$10$...
artemis.allowed-hosts=jump.example.com
server.ssl.key-store=file:/etc/artemis-browser.p12
server.ssl.key-store-password=...
```

`ReachabilityGuard` refuses to start if the app is bound beyond loopback without both of those, so
the unsafe arrangement fails immediately rather than working until someone notices. With a login
configured, every page requires signing in; the sign-in is the tool's own and is not the broker's,
whose credentials are still asked for per connection and never stored.

Once running, open `/` to connect to a broker (host, port, username, password — the password is
never persisted). From there:

| Path | Page |
|---|---|
| `/` | Connect / disconnect, saved broker locations |
| `/overview` | Sortable/filterable queue overview, counters, ingress/acknowledgment rates and optional auto-refresh |
| `/queues?name=` | One queue's counters and rates, its configuration and what it does, this session's trend, paged/filtered waiting messages, scheduled and in-flight panels (and messages a prepared transaction holds) |
| `/message` | Single message headers, properties and supported body content, subject to the detail limit |
| `/message/download` | One message as a .txt or .json file: headers, properties and body together |
| `/addresses` | Addresses and the queues under them (multicast fan-out) |
| `/address?name=` | One address: storage and limits beside its full policy, its subscriptions (kind, filter, client, lag), the consumers on them, who is sending, which subscriptions hold a given message, its settings and diverts, prepared transactions holding its messages, and its roles |
| `/search` | Cross-queue search (browses every queue; counts shown are a floor, see below). An exact `ID:…` looks for that message in every state, with a coverage report |
| `/export` | CSV/JSON download: one queue with `name`, or a whole cross-queue search without it |
| `/broker` | Broker health, acceptors, connections, consumers, producers |
| `/connectivity` | HA role and replica sync, cluster topology, cluster connections, bridges and broker connections, each with its backlog — as this broker reports them |
| `/transactions` | Prepared XA branches with what they send and hold, and branches resolved by hand |
| `/client?id=` or `/client?connection=` | A client's connections, sessions, consumers, producers, links to queues holding its in-flight messages, and the roles that may send and consume where it does |
| `/diagnose` | Why is this stuck: what on the broker is not moving, and what that usually means |
| `/snapshot?format=json` or `format=text` | An incident snapshot of all of the above, with what could not be collected |
| `/compare` | Compare two saved JSON snapshots of one broker: what changed, and what could not be compared. Needs no connection |

To test against a real broker rather than mocks, see the container recipe in `.claude/memory.md`
(note it maps host port 62616, not 61616, because 61616 is already taken in this environment).

## Run it in Kubernetes

There is a chart in `charts/artemis-browser` and a Dockerfile at the root. Together they run the app
as a pod that reaches a broker over cluster DNS.

```powershell
./scripts/build-image.ps1                                  # jar, image, and `kind load` into the cluster
docker run --rm artemis-browser:0.1.0 --hash-password=yourpassword   # prints a bcrypt hash

helm upgrade --install artemis-browser charts/artemis-browser `
  -n artemis-browser --create-namespace `
  --set-string auth.passwordHash='<the hash>'
```

Then add the hostname to your hosts file (as Administrator) and open it:

```
127.0.0.1 artemis-browser.claude.local
```

For HTTPS in the browser too, trust mkcert's CA once (`winget install FiloSottile.mkcert`, then
`mkcert -install`), run `./scripts/new-tls-secret.ps1`, and upgrade once with
`helm upgrade artemis-browser charts/artemis-browser -n artemis-browser --reset-then-reuse-values`.
The ingress then serves `https://artemis-browser.claude.local` and redirects HTTP to it. It uses
`ingress.tls.secretName` (`artemis-browser-ingress-tls`) only while that secret exists.

**How a request actually travels, and why.** The browser talks to ingress-nginx (HTTPS once the
ingress certificate above exists, HTTP otherwise), which talks **HTTPS** to the pod. The pod terminates TLS itself because it has to: `ReachabilityGuard` refuses to
start bound to anything but loopback without both a login and TLS, and a pod must bind `0.0.0.0` to
be reachable at all. So the chart satisfies that requirement rather than working around it. Without
the ingress certificate the browser→ingress hop is plaintext — acceptable on a single-machine
cluster, and not an arrangement to copy onto a shared one.

Two consequences worth knowing before changing anything:

- `server.forward-headers-strategy=native` is load-bearing whenever the ingress serves plain HTTP. Tomcat marks `JSESSIONID` `Secure` for a
  request that arrived over TLS, and a browser on `http://` discards a `Secure` cookie — the symptom
  is a login that accepts the password and bounces straight back to the form, forever, with nothing
  in any log. Only `native` (Tomcat's `RemoteIpValve`) clears the flag; `framework` looks equivalent
  and is not.
- **One replica, by design.** The broker connection lives in the HTTP session and sessions are in
  memory, so a second replica serves requests that have never heard of your connection. Scaling would
  need sticky sessions at the ingress and still loses everything on a restart. Never add an HPA.

The probes fetch `/app.css` with `Host: localhost` — a static resource rather than `/login` because
rendering the login page creates a session, one per probe, and a loopback Host because
`AllowedHostFilter` always allows those whatever `artemis.allowed-hosts` says.

Rebuilding on the same tag does not restart anything: `./scripts/build-image.ps1 -Restart`, or
`kubectl rollout restart deploy/artemis-browser -n artemis-browser`.

## Regression lab (Phase 15 — specification)

Phase 15 plans a separate web-controlled lab for creating repeatable test-broker conditions and
observing them in Artemis Browser. The lab services are not implemented yet. See the
[service specification](docs/Phase%2015%20plan.md) and
[first-draft regression procedure](docs/Phase%2015%20regression%20procedure.md) for scope, proposed
controls, current-feature test cases, expected results and future coverage. Browser stays read-only.

## Configuration

Keys from `src/main/resources/application.properties`:

| Key | Default | Meaning |
|---|---|---|
| `server.address` | `127.0.0.1` | Where to listen. Anything but loopback requires a login and TLS; see Security below |
| `server.port` | `8080` | HTTP port |
| `server.servlet.session.timeout` | `30m` | The broker connection lives in the HTTP session, so session expiry is connection expiry |
| `artemis.management-timeout-ms` | `10000` | Timeout for a management query to the broker |
| `artemis.connection-timeout-ms` | `10000` | Timeout for establishing the broker connection |
| `artemis.body-preview-chars` | `200` | Body characters shown per row in a message list |
| `artemis.body-detail-chars` | `200000` | Body characters shown in the single-message detail view (and used for export) |
| `artemis.connections-file` | *(blank)* | Where remembered broker locations (host/port/username, never passwords) are stored; blank defaults to `${user.home}/.artemis-browser/connections.json` |
| `artemis.search-max-per-queue` | `50` | Messages fetched per queue during cross-queue search; reaching it is what makes a count a floor |
| `artemis.export-max-messages` | `5000` | Upper bound on a single export, so a download can't try to pull an entire large queue |
| `artemis.export-body-scan-limit` | `20000` | How far export's JMS pass will walk a queue to find the bodies it needs (see Exports below) |
| `artemis.auth.username` | *(blank)* | The tool's own login. Blank on loopback means no sign-in; anywhere else it must be set |
| `artemis.auth.password-hash` | *(blank)* | bcrypt hash for that account, with or without a `{bcrypt}` prefix. Generate with `--hash-password=` |
| `artemis.allowed-hosts` | *(blank)* | Host headers to answer to beyond loopback, comma-separated — the name people will actually type |
| `artemis.export-body-total-chars` | `20000000` | Body characters retained per queue's export pass (~40MB); rows past it keep a truncated body |
| `artemis.in-flight-limit` | `5000` | Most in-flight (delivered, unacknowledged) messages a queue page lists. The broker returns them all at once, so above this they are not read; their consumers are still named |
| `artemis.trends.spacing-seconds` | `15` | Least time between two trend readings; readings are taken only when a page lists the queues anyway |
| `artemis.trends.max-readings` | `240` | Readings a session keeps, oldest dropped first — about an hour at the default spacing |
| `artemis.trends.max-queues` | `500` | Queues a session follows; with the above, at most 120,000 points per session, about 10MB of heap measured at that cap |
| `artemis.snapshot.max-rows` | `1000` | Rows an incident snapshot keeps per client listing; it records how many there were |
| `artemis.snapshot.max-address-settings` | `200` | Addresses whose settings an incident snapshot reads, those with something to explain first |
| `artemis.transactions.detail-limit` | `100` | Most prepared XA branches whose messages a page reads. The broker returns every branch's messages in one reply, so above this only their summary lines (Xid, creation time) are read |
| `artemis.snapshot.max-address-permissions` | `200` | Addresses whose roles an incident snapshot reads, in the same order as address settings |
| `artemis.compare.max-file-bytes` | `67108864` | Largest snapshot file `/compare` reads (64MB); `spring.servlet.multipart.max-file-size` follows it |
| `artemis.compare.max-rows` | `50000` | Most entries in any one section of a compared snapshot; past it the file is refused, not cut short |
| `artemis.investigate.max-queues` | `2000` | Queues a message-ID lookup looks at; the rest are listed as not reached |
| `artemis.investigate.max-hits` | `100` | Sightings a lookup keeps; once full, later queues and prepared transactions are not reached |
| `artemis.investigate.max-in-flight` | `20000` | In-flight messages one lookup may read across all queues (the per-queue limit is `artemis.in-flight-limit`) |
| `artemis.investigate.max-scheduled-per-queue` | `5000` | Most scheduled messages a queue may hold for a lookup to read its scheduled list, which comes in one reply |
| `artemis.investigate.max-scheduled` | `20000` | Scheduled messages one lookup may read across all queues |

## Security posture

Read-only is the product, not a detail: the tool never consumes, acknowledges, moves, or
deletes business messages. Its management channel sends requests and receives replies through its
own temporary reply queue; those management clients are labeled in the broker views. The business
message guarantee is checked rather than asserted — `ReadOnlyGuaranteeIT` drives every read
path against a real broker and compares the counters before and after.

Reachability is three controls, each load-bearing on its own:

- **`server.address`** decides where it listens, and defaults to loopback.
- **`AllowedHostFilter`** requires the `Host` header to be a loopback literal or a configured name.
  Binding an address does not decide who reaches it: a page on any website can point a hostname it
  controls at this app and drive the UI through your own browser. It runs ahead of authentication,
  because that attack rides a session that is already signed in.
- **`ReachabilityGuard`** refuses to start when bound beyond loopback without both a login and TLS,
  and fails at startup rather than warning — a warning is read after the incident.

Behind the login everything is still read-only, so the login is not protecting the broker's data
from modification. It is protecting a live, authenticated broker connection from whoever can reach
the port. The broker's own password is still never stored.

See [docs/architecture.md](docs/architecture.md) and `.claude/memory.md` for the specific traps
already found and fixed — for instance a naive `startsWith("127.")` check, which accepts
`127.0.0.1.attacker.com`, a hostname an attacker owns.

Message filters (queue browsing, cross-queue search and subscription search) use Artemis **core** filter syntax
(`AMQPriority`, `AMQTimestamp`, `AMQDurable`, `AMQSize`, or a property by its bare name) — not JMS
selector syntax. A JMS-style `JMSPriority = 4` is not rejected, it silently matches nothing. JMS
selector syntax applies only to the single-message detail path.

CSV exports treat message bodies as untrusted content: every field is quoted, and a leading
`=`, `+`, `-` or `@` is prefixed with an apostrophe so a downloaded file isn't evaluated as
spreadsheet formulas (`MessageExporterTest` covers it).

## Searching, and why the counts are a floor

Cross-queue search browses every queue with the filter rather than asking each one how many messages
match. Artemis examines only the first `management-browse-page-size` messages (200 by default) when
counting *with a filter*, so on a 100,000-message queue a filtered count answers 200 — or 0 for a
message sitting at position 99,999 that is definitely there. A filtered browse has no such window.

So the number shown per queue is how many were **found**, capped at `artemis.search-max-per-queue`,
and the page says "at least N" when that cap was reached. The broker will not tell us the true total
without scanning, and a number that looks exact and isn't is worse than one that admits its limits.

Filtered queue pages likewise avoid that sampled count: they show whether another page exists,
without claiming an exact total. Unfiltered paging excludes scheduled and in-flight messages from
the browsable total. Those states have their own panels because management browse returns neither.

A general filter can only be handed to browse, which sees waiting messages alone: the page says how
many in-flight messages on the searched queues it could not look at. An empty result therefore does
not prove that a message is absent.

## Looking up one message

A search for an exact message ID is different: comparing one ID needs no filter evaluation, so it
can be answered in the states browse cannot see. Each queue is read in the order a message moves:
its scheduled list, then a filtered browse, then its delivering list; prepared XA branches are read
last, once. Each sighting carries the time its read returned.

- **What a sighting proves**: that the message was in that state when that read returned — not a
  delivery history. A message seen twice either has a copy per subscription on a multicast address,
  or moved between reads (scheduled, then waiting).
- **What no sighting proves**: nothing about consumption. A message may have been acknowledged,
  expired, dead-lettered, diverted or sent to a queue created after the list was read; one returned
  from a consumer to its queue between two reads can be missed by both. The page says so every time.
- **Coverage**: per state, how many queues were checked, had nothing in that state when listed (and
  were not read), or were skipped, not reached, refused or unreadable — and a row per queue that was
  not fully checked, with the limit or the broker's reason. Internal queues are left out unless asked
  for, and counted.
- **Budgets**: the scheduled, delivering and prepared-detail replies cannot be paged, so each queue's
  counts from the queue listing are checked against the `artemis.investigate.*` limits (and
  `artemis.in-flight-limit`, `artemis.transactions.detail-limit`) *before* the call. A queue over one is
  skipped and says which.
- In-flight and prepared messages have no body to show; the broker returns none.

The address page's per-subscription lookup links to this one.

## Rates and diagnostic evidence

Ingress and acknowledgment rates compare two readings in the current HTTP session. The first
reading has no rate; the page shows the measurement interval. A broker restart or counter reset
invalidates the affected interval. Acknowledgments are separate from expired and killed messages.

Trends keep up to `artemis.trends.max-readings` (240) readings, at most one per
`artemis.trends.spacing-seconds` (15), for up to `artemis.trends.max-queues` (500) queues. That is
about an hour while a page is open, and it lasts only as long as the HTTP session. A gap in the line
is time no page was open. Nothing is shown from before this session's first reading.

The address page measures subscriber lag using the oldest undelivered message's age. In-flight
message age is measured from send time, not delivery time, so it cannot prove how long a consumer
has held that message. In-flight inspection is bounded, and Diagnose reports what its budget
left unread. Expired/killed counters record removals whether messages were forwarded or dropped;
current address settings help explain their destination but cannot establish historical settings.

Address pressure findings are marked *inferred* where they reason from a percentage and a policy to
what a producer is going through; only a management `block()` is reported as observed. The broker's
`paging` flag means "over its limit", so an address under FAIL or DROP reads as paging with no pages
written. The pages show "paging" only when pages exist. Whether an address is blocked by an operator
is one read per address, capped at 500 per diagnose run.

Queue settings are the queue's effective values from the queue listing. Non-destructive is the
exception: the broker does not report it per queue, so the page says it cannot tell and shows only
the address default. Messages a last-value queue replaces or a ring evicts appear in no counter.

## Incident snapshots

`/snapshot?format=json` (or `format=text`) collects fresh on each request, and takes a few seconds
because it includes a diagnose run. It is a sequence of observations, not an atomic picture, and
each section carries its own time. Client listings keep at most `artemis.snapshot.max-rows` (1000)
rows. Address settings are read for at most `artemis.snapshot.max-address-settings` (200)
addresses, those near a limit, paging, dropping, or killing and expiring messages first. The JSON
names each unit (`addressSizeBytes`), uses ISO-8601 UTC times, and lists `unavailable` reads and
`omitted` rows, so two snapshots can be compared. Message bodies are never included. Setting values
under keys that look like secrets are masked.

## Comparing snapshots

`/compare` takes two JSON snapshots (not the text summary), puts them in time order, and reads
nothing else: no broker, no session state, and the files are dropped with the request. A file is
refused, with the reason, when it is not an incident snapshot, has a schema this build does not
read, has no collection time, is over `artemis.compare.max-file-bytes`, or has more than
`artemis.compare.max-rows` entries in a section. The pair is refused when two known node ids
differ, or when a node id is missing and the addresses differ too; with a node id missing and the
same address, the comparison goes ahead marked *unconfirmed*.

What it will not do: call something removed when its section was unreadable on one side, call a
consumer or connection gone when that side's listing was cut short, count a value one side could not
read as a change, or present added/acknowledged/expired/killed across a restart, a recreated queue or
a reset as traffic. Without `uptimeMillis` (snapshots taken before this phase) a restart cannot be
ruled out, so those differences are shown only "if unbroken". Trends are not compared. Each
snapshot's own *not collected* and *left out* lists are shown beside the result.

## Connectivity and HA

`/connectivity` reads what this broker reports about its peers and paths; it never opens a connection
to another broker, so *connected* means this broker holds a connection, not that the far side is
healthy. What shows the problem is usually a backlog: a bridge that cannot connect leaves its
messages on its source queue, a cluster peer that is gone leaves them in the store-and-forward queue
`$.artemis.internal.sf.<cluster>.<node>`, and a mirror's backlog is `$ACTIVEMQ_ARTEMIS_MIRROR_<name>`.
Two counters mean less than their names: a bridge's "pending acknowledgement" is a running total of
what it sent, so the page shows sent minus acknowledged as *outstanding*; and a backup listed in the
topology was only announced — it stays listed after it stops, while *replica synchronized* goes false
within seconds. Bridges and cluster connections have no listing operation, so each is read field by
field, at most 100 of each per page. Core federation (`<federations>`) and connector services are not
read.

## Transactions and permissions

`/transactions` shows only what the broker reports: XA branches it holds **prepared**, and branches
committed or rolled back through its management rather than by their transaction manager. An active
branch, a JMS transacted session and the transaction manager's own log are not reported, so they are
not here. What a prepared branch sent is on no queue until it commits; what it received stays on its
queue counted as delivering, with no consumer holding it — invisible to browse and to the in-flight
list, which is why a queue can show one message, one delivering, no consumer and an empty page. The
broker writes a branch's creation time in its own locale and time zone with no zone given; the zone is
worked out from its connection listings, and when it cannot be, the time is shown as written and no
age is claimed. The detail reply has no paging, so past `artemis.transactions.detail-limit` branches
only their summary lines are read, and each branch shows at most 50 messages. Nothing here can commit,
roll back or forget a transaction: those operations are not on the management allowlist.

An address's *Permissions* panel shows the roles of the security setting that matches it most
closely — Artemis uses that one, not a merge — for send, consume, browse, queue and address creation
and deletion, manage, and the management-RBAC `view` and `edit`. A role is not a user: which users
hold which roles is in the broker's login module, which management does not expose, so the panel is
never proof of what a particular client may do. If the broker reports security disabled, the page
says every connection may do everything.

## What management shows, and what it does not

Everything here comes from the broker's management API over one JMS connection: counters, listings,
settings and state, as the broker holds them at the moment of asking. That covers a lot, and it has
edges worth knowing before an incident:

| You can see here | You need something else for |
|---|---|
| Queue and address counters, and their change over *this session* | History before the session, or while no page was open — a metrics plugin (Prometheus, JMX exporter) or the broker's message counters |
| Messages waiting, scheduled, in flight, held by a prepared XA branch | Why a consumer is slow, or what it did with a message — application logs or tracing |
| Addresses at their limits, and the policy that applies | Why disk, memory or GC is under pressure — broker JVM metrics, OS monitoring |
| Bridges, cluster peers and mirrors as *this* broker reports them | Whether the far side received anything — connect to that broker, or its logs |
| Prepared XA branches and those resolved by hand | Active branches, local transactions, the transaction manager's own view — the TM's logs and recovery tools |
| Roles granted per address | Which users hold which roles — the broker's login module (`artemis-roles.properties`, LDAP, …) |
| A refused, unsupported or failed read, named as such | Why the broker refused — its audit and security logs |
| Expired and killed counts | Where each such message went, if anywhere — the address settings say where it *would* go; broker logs say what happened |

The pages say which of these a given figure is: *observed* when the broker reported the thing itself,
*inferred* when a finding reasons from what it reported, and *May be intended* for configuration that
could explain it. None of it is a substitute for logs.

## Exports and message bodies

The message *list* is read through Artemis management `browse`, which truncates a body at the
broker's `management-message-attribute-size-limit` (256 characters by default) and appends a literal
`", + N more"` to the value itself — and which has no body at all for bytes, map or stream messages.
That is fine for a table cell and wrong for a download, so `/export` builds its list from management
browse (the broker still does the paging and the core filtering) and then fills in the bodies that
need it from a single JMS browser pass. Text, bytes and map bodies are supported; object bodies
are deliberately not deserialized, and stream bodies are not read. Those types carry explanatory
placeholders. `bodyTruncated` flags shortened content; it does not turn a placeholder into a body.
The per-message limit is `artemis.body-detail-chars`. The pass is bounded by `artemis.export-body-scan-limit`;
anything it doesn't reach keeps its management body, flagged truncated rather than passed off as
complete. Like every other read here, it consumes nothing — verified against a live broker with
counters unchanged after repeated exports.

A cross-queue search exports the same way, one queue at a time: each matching queue is re-read with
export bodies and written out before the next is fetched, so a search that matched in thirty queues
never holds thirty queues' worth of bodies at once. CSV names the queue on every row; JSON groups
messages under their queue.

`artemis.export-max-messages` caps the total rows across the export. These exports contain browsable
messages, not the scheduled or in-flight panels, and re-read the broker rather than freezing the
earlier search results.

That pass retains multiple message bodies in memory, so it is bounded
twice: `artemis.export-body-scan-limit` caps how far it walks, and `artemis.export-body-total-chars`
caps what it keeps for each queue's export pass. Once the character budget is spent, remaining rows keep their management body
and are flagged truncated, so the file always says which rows were cut. Both the CSV and the JSON
writers stream to the response rather than building the document in memory first.

## Layout

```
com.culberth.tools.artemisbrowser
├── ArtemisBrowserApplication      Spring Boot entry point; --hash-password= prints a bcrypt hash and exits
├── broker/                        Everything that talks to Artemis
│   ├── BrokerSession              @SessionScope: one live connection per HTTP session (never the password)
│   ├── BrokerCredentials          Password carrier from the connect form to connect(), not retained
│   ├── ConnectionInfo             What the session keeps after connecting: host/port/username only
│   ├── ManagementChannel          Request/reply plumbing over activemq.management; refuses any non-read operation
│   ├── QueueDirectory             Lists queues + counters through paged listQueues calls
│   ├── QueueBrowseService         Both read paths (management browse, JMS QueueBrowser), plus scheduled messages
│   ├── InFlightService            Delivered-not-acked messages, which browse cannot see; capped, tied to their clients
│   ├── MessageIdLookup            Recognises an exact message-ID search, the one kind in-flight messages can answer
│   ├── MessageInvestigation       Where one message was seen, state by state, and where the lookup could not look
│   ├── MessageInvestigationService One message ID looked up in every state, under per-request budgets
│   ├── AddressDirectory           Groups queues under their addresses (multicast fan-out)
│   ├── AddressDetailService       One address: subscriptions, consumers, producers, lag, settings, diverts, pressure; per-subscription search
│   ├── DivertDirectory            The broker's diverts: getDivertNames, then one read per field (there is no listing)
│   ├── MessageSearchService       Cross-queue search: browses every queue, because a filtered count is a sample
│   ├── StuckDiagnosisService      "Why is this not moving": cheap reads, in-flight ages within a budget, rates
│   ├── RateTracker / RateService  Per-session previous reading of queue counters, for in/acked per second
│   ├── TrendHistory / Trends / QueueTrend / TrendPoint
│   │                              Bounded session history of queue counters; intervals, breaks and gaps
│   ├── ClientDirectory            One client: connections → sessions → its consumers and producers
│   ├── MessageExporter            CSV/JSON export, per queue or across a search, with formula-injection defusing
│   ├── SnapshotService / SnapshotWriter / IncidentSnapshot / Redaction
│   │                              Incident snapshot: bounded collection, JSON and text, masked secrets
│   ├── BrokerInfoService          Broker health, acceptors, connections, consumers, producers
│   ├── ConnectivityService        HA state, topology, cluster connections, bridges, broker connections; their findings
│   ├── Connectivity / HaState / TopologyMember / ClusterLink / Bridge / BrokerLink / Connector
│   │                              This broker's view of its peers and outbound paths, with backlogs from the listing
│   ├── TransactionService         Prepared and hand-resolved XA branches, the broker's clock zone; their findings
│   ├── Transactions / PreparedTransaction / TransactionMessage
│   │                              What each prepared branch will send and holds received, as the broker reports it
│   ├── PermissionService / Permissions / RoleGrant
│   │                              Roles per address from the matching security setting; roles, not users
│   ├── ConnectionStore            Persists remembered broker locations to disk, passwords excluded
│   ├── QueueBehavior              A queue's effective settings from its listing row, and what each looks like from outside
│   ├── ListingFields              One listing field as a Reading: absent is unsupported, malformed is failed
│   ├── QueueStats / QueueOverview / MessagePage / MessageSummary / MessageDetail / ScheduledMessage
│   │   / InFlight / InFlightConsumer / InFlightMessage / InFlightLookup
│   │                              Queue and message view models, including FQQN browse-name handling
│   ├── AddressDetail / Subscription / SubscriberConsumer / SubscriptionSearch
│   │   / AddressRouting / AddressSettings / AddressPressure / Divert
│   │                              One address as its subscribers see it; kinds and name hints from the broker's naming
│   ├── AddressOverview / BrokerConnection / BrokerConsumer / BrokerProducer / BrokerHealth / AcceptorInfo
│   │   / SearchResult / SavedConnection / Finding / Diagnosis / Rates / QueueRate / ClientView
│   │                              Broker, address, search and diagnosis view models
│   ├── Reading / Availability     A value the broker gave, or why not — never a zero in its place
│   └── BrokerException / ManagementRefusal / NotConnectedException / ConnectionLostException
│                                  Broker-facing error types; the last is deliberately not a BrokerException
├── compare/                       Offline comparison of two saved snapshots; no broker, no session
│   ├── SnapshotReader             Reads an uploaded snapshot as untrusted, bounded JSON; skips trends
│   ├── SnapshotFile / Value       What a comparison needs from one file; a missing value keeps its reason
│   ├── SnapshotComparer           Identity, continuity, and every difference with its evidence
│   └── SnapshotComparison / SnapshotRejected
│                                  The result, or why two files cannot be compared
├── web/                           Thymeleaf controllers, security and filters
│   ├── ConnectionController       / connect, disconnect, forget a saved connection
│   ├── QueueController            /overview, /queues, /message, /message/download
│   ├── BrokerController           /broker, /connectivity, /transactions, /addresses, /address, /client
│   ├── SearchController           /search, /export (one queue, or a whole search)
│   ├── SnapshotController         /snapshot: the incident snapshot as JSON or a text summary
│   ├── CompareController          /compare: two uploaded snapshots compared, no broker contacted
│   ├── DiagnoseController         /diagnose
│   ├── LoginController            /login (the sign-in itself is Spring Security's)
│   ├── ConnectForm                Connect page form backing object
│   ├── SecurityConfig             The login: one configured account, CSRF on, session fixation handled
│   ├── ReachabilityGuard          Refuses to start exposed without both a login and TLS
│   ├── AllowedHostFilter          Host header must be loopback or named; runs ahead of authentication
│   ├── CurrentUserAdvice          Puts the signed-in name on every page
│   └── BrokerErrorAdvice          Sends a request that cannot be served back to the connect form
├── (deployment)
│   ├── Dockerfile                 Packages an already-built jar; the build stays on the host (see scripts/)
│   ├── charts/artemis-browser/    Helm chart: Deployment, Service, Ingress, TLS + auth Secrets, seeded connection
│   └── scripts/build-image.ps1    jar -> image -> `kind load` into the local cluster
└── (resources)
    ├── application.properties     See Configuration above
    ├── templates/
    │   ├── fragments/layout.html  Shared nav — edited once when a page is added
    │   └── login.html, connect.html, overview.html, queues.html, message.html, addresses.html,
    │       address.html, broker.html, client.html, connectivity.html, transactions.html,
    │       search.html, diagnose.html
    └── static/app.css
```

See [docs/architecture.md](docs/architecture.md) for the deeper rationale behind these boundaries
(why there are two read paths, why addresses and queues are modeled separately, etc.) and `.claude/memory.md` for verified broker
response shapes, environment-specific gotchas, and what's already been fixed.
