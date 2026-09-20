# artemis-browser — Architecture

The design decisions and the reasoning behind them. `CLAUDE.md` carries the short version of
whatever is load-bearing enough to need repeating every session; this is where the "why" lives.
For what the product is and what is planned, see [PRD.md](PRD.md). For the file-by-file inventory,
see the Layout section of the [README](../README.md). For things discovered rather than designed —
verified broker response shapes, environment quirks, traps already hit — see `.claude/memory.md`.

## Read-only is the architecture

Nothing here consumes, acknowledges, moves, expires or deletes a message. That constraint shapes
every read path below, and both of them are verified non-destructive against a real broker rather
than assumed: counters, delivering and acked are checked before and after. It is also why some
obvious-looking shortcuts are absent — `getObject()` on a browsed `ObjectMessage`, for instance,
would deserialize whatever a producer put on the queue inside this process, so the browser reports
the type and stops.

Anything that could change broker state is out of scope until it is deliberately put in scope, and
that decision changes what the tool is rather than what it does.

## Two read paths, on purpose

There are two ways to read messages here, and the split is deliberate.

**The paged, filtered list** goes through Artemis management `browse(page, pageSize, filter)`. The
broker does the paging, so opening a deep page of a 50,000-message dead-letter queue does not stream
the preceding pages through this process — which is exactly what a client-side `QueueBrowser` that
skips would do. An unfiltered `countMessages()` gives the total; a filtered one does not, for the
reason under *A filtered count is a sample* below.

**The single-message detail** uses a JMS `QueueBrowser` with a `JMSMessageID` selector. Management
`browse` only exposes a `text` body, so bytes, map and stream messages would otherwise show nothing.
The selector pushes the lookup to the broker, keeping this a one-message read rather than a scan.

**Export uses both.** The listing comes from management browse, so the broker keeps doing the paging
and the filtering; the bodies are then filled in from a single JMS browser pass. This is because the
broker truncates the `text` attribute of a browse result at its
`management-message-attribute-size-limit` (256 characters by default) and appends a literal
`", + N more"` *to the value itself* — so an export built on browse values ships quarter-messages
carrying that suffix as though they were whole, and has nothing at all for non-text messages.
Deliberately one pass rather than a selector per message: a selector makes the broker scan, so
per-message would turn an n-message export into n scans. The pass is bounded by
`artemis.export-body-scan-limit`; anything it does not reach keeps its management body, flagged
truncated rather than passed off as complete.

## Listing queues is a management call, not JMS

JMS has no notion of "what queues exist". `ManagementChannel` is the request/reply plumbing for
that: a message sent to `activemq.management` naming a resource and an operation, answered on a
temporary reply queue. The broker user needs `manage` permission on that address — the failure mode
is a rejection, not a hang.

Chosen over JMX/Jolokia because it needs no extra port and no second protocol. The cost is that the
replies are JSON documents with their own conventions rather than typed objects, and those
conventions are not uniform: `listQueues` quotes every value including counters, `listAddresses`
encodes `routingTypes` as a JSON array *inside* a JSON string, `listProducersInfoAsJSON` leaves
`msgSent` bare while quoting `creationTime`, and the broker's own attributes come back as a mix of
String, Long and Double. Every one of those is a silent failure mode — a wrong parse yields a
believable number, not an error — which is why that layer is tested against captured reply shapes.

That channel's own temporary reply queue is a real queue as far as the broker is concerned, so it is
excluded from the queue listing; its consumer and its producer are *labelled* rather than hidden in
the consumers and producers views, so a consumer count of 1 on an idle broker still adds up.

`QueueDirectory` reads the whole queue list and every counter in one `listQueues` call. The obvious
alternative — reading each attribute per queue — costs ten round trips per queue, which is fine for
one queue and untenable for an overview page that refreshes on a timer.

## Addresses versus queues

Producers send to an address; consumers read from a queue. Under anycast the two line up one to one
and the distinction is invisible, which is why a queue-only view got Phases 1–2 a long way.

It stops being invisible with multicast: one address fans out to a queue per subscriber, each
holding its own copy, none of them named after the address. Artemis resolves a bare name against
addresses first, so any multicast subscription must be browsed by its fully-qualified
`address::queue` name — that is what `QueueStats.browseName()` exists for. Get it wrong and the
symptom is "that queue is always empty", not an error, which is the kind of bug that survives a
demo.

## Two filter dialects, and mixing them fails silently

Management operations (`countMessages`, `browse`) take Artemis **core** filter syntax:
`AMQPriority`, `AMQTimestamp`, `AMQDurable`, `AMQSize`, `AMQUserID`, or a custom property by its
bare name. JMS selector syntax belongs only to `createBrowser(queue, selector)`, which is the
single-message detail path.

A JMS-style `JMSPriority = 4` is *not* rejected by the core parser — it returns zero matches on a
queue where every message is priority 4. Any UI that exposes a filter has to name its dialect, or
users get confidently wrong answers.

### A filtered count is a sample, not a count

Artemis examines only the first `management-browse-page-size` messages (200 by default) when
counting with a filter. On a 100,000-message queue, `countMessages("AMQPriority=4")` answers 200,
and for a message at position 99,999 it answers 0. A filtered `browse` has no such window — it scans
the whole queue and finds that message in about 300ms.

Cross-queue search was originally built two-phase on the opposite assumption: count every queue
cheaply, then browse only the ones that matched. That made it silently blind past the first 200
messages of every queue, which is the precise failure the feature exists to prevent. It now browses
every queue with the filter and reports how many it *found* — a floor, not a total. The cost is a
broker-side scan per queue instead of a counter read, and the benefit is that the answer is true.

## The session model

`BrokerSession` is `@SessionScope`: one live connection per HTTP session, so session expiry is
connection expiry. It holds host, port and username, and **never the password** — the password goes
from the form to `connect()` and is dropped. A session hijack or a heap dump therefore does not hand
over broker credentials; the trade is that reconnecting means retyping it. Remembered connections on
disk store the same three fields, and a test asserts the file contains no "password" string.

Everything on the session and the management channel is synchronized: a JMS `Session` is not
thread-safe, and a browser with two tabs open makes concurrent requests happily.

This is also what pins the deployment to a single replica. The session holds a live JMS connection —
an open socket, not a serialisable token — so a second pod serves requests that have never heard of
the user's broker connection, and the failure is immediate rather than gradual. Sticky sessions at
the ingress would be a mitigation, not a fix: the connection still dies with its pod. An HPA would
make the app unusable, not faster.

## Security posture

Three controls, and each one is load-bearing on its own.

**The bind address.** `server.address` is 127.0.0.1 by default, which is how the tool ran for its
first six phases and is still the right answer for a laptop.

**`AllowedHostFilter`.** Every request's `Host` header must be a loopback literal or a name in
`artemis.allowed-hosts`. Binding an address does not decide who reaches it: a page on any website
can point a hostname it controls at this app and drive the UI through the victim's own browser — DNS
rebinding — and the browser sends the attacker's hostname in `Host`, which is what makes checking it
work. This filter runs ahead of authentication on purpose, because a rebinding attack rides a
session that is already signed in.

The host check parses octets individually. `startsWith("127.")` is not a loopback check: it accepts
`127.0.0.1.attacker.com`, a hostname an attacker owns, which defeats the entire filter. A test
covers it; don't simplify it back.

**`ReachabilityGuard`.** Refuses to start when bound beyond loopback without both a configured login
and TLS, and fails at startup rather than warning — a warning in a log is read after the incident,
and this is exactly the misconfiguration nobody notices while it is working fine. An unset bind
address counts as exposed, because Spring Boot's default is every interface and that is the easiest
way to be reachable without deciding to be.

TLS is not optional up there because both passwords that matter cross the wire: the tool's own, and
the broker's, which the connect form asks for on every connection. Authentication over cleartext
would be worse than the loopback-only arrangement it replaced, since it looks protected.

## The login

One configured account — `artemis.auth.username` and a bcrypt `artemis.auth.password-hash` — with
no sign-up, no reset and no user list, because a tool one person runs on a jump host does not need
them and each would be another thing to get wrong. `--hash-password=` generates the hash with the
bcrypt already on the classpath, printing and exiting so the password never reaches a running server
or a log. An unconfigured login means nobody can sign in rather than everybody.

Everything behind the login is still read-only, so this is not protecting the broker's data from
modification. It is protecting a live, authenticated broker connection from whoever can reach the
port.

## Running it behind an ingress

The cluster deployment satisfies the reachability rules rather than working around them, and that
shaped it more than anything else.

A pod has to bind `0.0.0.0` to be reachable by a Service, which is exactly the case `ReachabilityGuard`
refuses without a login and TLS. So **the pod terminates TLS itself**: the chart generates a
self-signed certificate, mounts it as a `kubernetes.io/tls` Secret, and the ingress is told to speak
HTTPS to the backend. The alternative — letting the ingress terminate TLS and serving plain HTTP from
the pod — would have required defeating the guard, which is a strange thing to do to a control this
project deliberately built two phases earlier.

What that leaves is a plaintext browser→ingress hop on a single-machine cluster. That is a real
compromise and worth naming rather than glossing: on a shared cluster it would want a certificate on
the ingress and browsing over HTTPS.

Splitting the scheme across the hop has one consequence that is architectural rather than
operational. Tomcat marks the session cookie `Secure` for any request that arrived over TLS, and a
browser on `http://` silently discards a `Secure` cookie — so the login accepts the password and
bounces straight back to the form, forever, with nothing in any log to say why.
`server.forward-headers-strategy=native` installs Tomcat's `RemoteIpValve`, which reads the
ingress's `X-Forwarded-Proto` and mutates the internal request the flag is taken from. The
`framework` strategy looks equivalent and is not: it wraps the request instead.

`artemis.allowed-hosts` has to name the ingress hostname, because that is the `Host` the pod
actually sees. The probes deliberately do not appear in it — they present `Host: localhost`, which
`AllowedHostFilter` allows unconditionally, so the probes cannot be broken by a configuration change
and the allowlist stays a short, reviewable list rather than something that varies per pod.

## Exports are untrusted content

A message body is whatever a producer wrote, and a CSV export gets opened in a spreadsheet. Every
field is quoted rather than only the ones that look dangerous, and a leading `=`, `+`, `-` or `@`
is prefixed with an apostrophe so the file is not evaluated as formulas. `MessageExporterTest`
covers it. See the README for the user-facing account of what an export contains.

## The frontend, and what it deliberately isn't

Thymeleaf, server-rendered, no npm and no build step: a handful of screens do not justify a
frontend toolchain in the dev loop. Dropdowns self-submit with a one-line inline `onchange`. The nav
lives in `templates/fragments/layout.html`, so adding a page means editing it in one place.

**Not JavaFX.** The three sibling projects in `P:\ClaudeCowork\Projects` (`data-blaster`,
`javafx-ribbon-view-switcher`, `track-generator-system`) are JavaFX desktop apps. Their scene-graph,
FXML, TestFX/Monocle and ribbon-CSS patterns are a trap to copy from here.

## Testing approach

The services that parse management JSON are tested by mocking `BrokerSession` and
`ManagementChannel` and feeding them the reply shapes captured from a live broker in
`.claude/memory.md`. Those tests must sit in the `broker` package to reach the package-private
`requireManagement()` / `requireSession()`.

`ManagementChannel` itself is not yet covered: its timeout, permission-rejection and
reply-correlation paths need a live JMS session rather than a fixture, which is a Testcontainers job
and is tracked in the [PRD](PRD.md). The non-destructive guarantee is verified by hand against the
container recipe in `.claude/memory.md` — making that repeatable is tracked there too.
