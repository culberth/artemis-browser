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
skips would do. Neither count Artemis offers is the total the pager needs. An unfiltered
`countMessages()` is the whole queue, including in-flight and scheduled messages that `browse` never
returns, so the pager takes it less `deliveringCount` and `scheduledCount`. A filtered one is a
sample (see *A filtered count is a sample* below), so a filtered page has no total at all: it
browses one row at the next page's offset to learn whether a next page exists.

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

The rule runs the other way for management. A management resource is named by the bare queue —
`queue.clientId.sub`, never `queue.address::clientId.sub`, which fails with "Cannot find resource".
So the JMS path takes the FQQN and every management call takes the bare name, and a subscription
is addressed both ways on the same page.

### Lag is the age of what is waiting, not a difference in counts

The obvious measure of "which subscriber is behind" — compare `messagesAdded` or `messageCount`
against the busiest subscription — is wrong on any address with a filter. A subscription filtered to
one region is *meant* to receive a fraction of the messages; comparing counts calls its filter lag.
`/address` measures lag as the age of each subscription's oldest message not yet handed to a
consumer (`firstMessageAge`, one read per non-empty queue, which is why it is on the single-address
page and not the index). Age means the same thing whatever the filter.

What that age cannot see is what browse cannot see: messages delivered to a consumer and not yet
acknowledged. They leave the browsable list, so `firstMessageAge` is null while they are all that is
left, and a filtered browse does not find them. The address page's "which subscriptions hold this
message?" therefore reports three outcomes rather than two — waiting here; not here; and not
*waiting* here, with N in flight or scheduled that could not be searched — because rounding the
third into "not here" says a subscriber never got a message it is holding right now.

### An address's messages can go somewhere its subscriptions never see

Two more routes out of an address, both invisible from the queue list. Its **address settings**
name where messages go after too many delivery attempts or on expiry; the broker leaves a setting
out of `getAddressSettingsAsJSON` when it is at its default, so a missing key is "not set", never
zero, and a named dead-letter address that does not exist is where messages go to be dropped. And
an **exclusive divert** takes a message instead of copying it: what it matches never reaches the
address's own queues, with no error anywhere. Both are on `/address`, and the exclusive divert on an
address that has subscribers is a diagnose finding, because "my subscriber is missing messages" is
exactly the report it produces.

### Address pressure: usage beside policy

Why a producer to one address stalls, fails or loses messages is a question of three numbers that
must not stand in for one another. **Address memory** (`addressSize`) is the broker's in-memory
estimate of what the address holds, and it is what `max-size-bytes` and `global-max-size` are
measured against. **Persistent size** is journal bytes per queue. **Disk use** is the whole store's
filesystem, on `/broker`. The address page's *Storage and limits* panel shows the first against its
limits, names the other two, and puts the full policy beside it, because a percentage says nothing
until the policy turns it into a consequence.

The listing's fields do not mean what their names suggest. Measured on 2.55.0 and 2.57.0 against
20KB limits (shapes in `.claude/memory.md`):

- **`paging` means "over its limit".** Under FAIL or DROP a full address reports paging with no
  pages at all. So the index shows "paging" only when `numberOfPages` > 0, and "full" for the other case.
- **`addressLimitPercent` counts bytes only, and reads 0 with no byte limit.** A 0 is therefore not
  a measurement. An address full by `max-size-messages` reads 0 while it refuses sends, so the
  message ratio is computed separately.
- **Under BLOCK the flag stays false while the percentage passes 100.** 418% was measured, because
  producer credit is granted ahead. The percentage, not the flag, shows it.

Paging under PAGE is the policy working and never a finding. Diagnose flags an address only when
its listing entry already shows it near or over a limit, or writing pages near a page limit, and
reads settings only for those. That keeps the page one settings read per troubled address, not one per
address. The one fact the listing lacks is an operator's `block()`, `blockedViaManagement`. It is
read per address up to a cap of 500, and the scan stops at the first refusal, since a denial for one
is a denial for all.

Findings carry a **basis**. A management block is *observed*: the broker reports it. "Producers are
made to wait" is *inferred* from a percentage and a policy, and is marked so. The broker-wide memory
finding names the largest holders of address memory, and says that is where the memory is, not
necessarily why it filled: every address shares that limit.

### Queue configuration: effective values, defaults, and explanations

A queue's behavior settings come from its `listQueues` row. That row reports the queue's
**effective** values: set explicitly, or taken from the address's `default*` settings when the queue
was created. The queue page, the overview badges and diagnose therefore cost no extra call. The
address's `default*` keys (reported by `getAddressSettingsAsJSON` only when set) are shown on the
queue page as defaults, never as the queue's value, because a default changed later does not change
an existing queue.

What was measured on 2.55.0 and 2.57.0 (shapes in `.claude/memory.md`) shapes the wording:

- **A last-value queue reports `lastValue: false`.** The key being set is the signal.
- **Replaced (last-value) and evicted (ring) messages appear in no counter.** "Added far above held"
  is those queues working as configured.
- **Non-destructive is reported nowhere per queue.** It is shown as unreadable. The address default
  appears beside it, labelled as a default, and diagnose uses it only as a possible explanation.
- **Consuming from a non-destructive queue acknowledges nothing**, and **a purge on no consumers
  counts every removed message as killed**. Both would otherwise make a diagnose finding lie.

A finding's `detail` holds only what was read. Configured behavior that may account for it goes in a
separate `explanation`, rendered as "May be intended:". That keeps "this is what we saw" and "this
may be why" apart, which the PRD asks for. Where the configuration makes the observation the
setting itself working, as with one consumer holding everything on an exclusive queue, there is no
finding at all.

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

The same count survived in two more places until Phase 12, both measured on 2.44.0: the queue
page's filtered pager, which counted 500 matches as 100 and stopped paging at page 2 of 10; and the
cross-queue export, which chose its queues by a count greater than zero and so left out any queue
whose matches lay past its first 200 messages. Neither uses a count now. The rule is simply that a
filtered `countMessages` is never used for anything.

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
or a log.

With no login configured the answer depends on where the app listens. On loopback there is no
sign-in at all — the default local run, and how the tool ran for its first six phases, with
`AllowedHostFilter` still refusing any non-loopback `Host` and CSRF still on. Anywhere else
`ReachabilityGuard` refuses to start, and the security chain would demand a sign-in nobody can give
even if it did not. `SecurityConfig` checks both conditions itself rather than trusting the guard
to have run. Until 2026-09-27 the chain demanded a sign-in on loopback too, which made the default
run open onto a form nobody could pass while the README said it needed no login.

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

## A missing value is not a zero

Phase 13 P0. Every read that a page can do without is a `Reading<T>`: a value, or an `Availability`
saying why there is none, with the time it was collected. `Reading.attempt` catches a
`BrokerException` — so a refused or failed read becomes a missing value beside the rest of the page —
and deliberately not `ConnectionLostException`, which still ends the page through the advice.

How far the reasons can be told apart is the broker's decision, not ours, and was checked on 2.44.0,
2.55.0 and 2.57.0 before anything parsed it:

| The broker says | Means | `Availability` |
|---|---|---|
| `AMQ229069: no operation x/n` | the version has no such operation | `UNSUPPORTED` |
| `AMQ229032 … permission='VIEW' on address mops.…` | this user may not (per-operation RBAC only) | `DENIED` |
| `JMSSecurityException` on send, AMQ229032 `MANAGE` | this user may not manage at all | `DENIED` |
| `Problem while retrieving attribute x` | missing attribute, gone resource, *or* denied | `UNAVAILABLE` |
| anything else, a timeout, a malformed value | — | `FAILED` |

The attribute row is why `UNAVAILABLE` exists rather than a guess: the broker gives one phrase for all
three causes. The no-`manage` row used to be treated as a lost connection, which closed the session
and sent a user with a merely under-privileged account back to the connect form for good.

`ManagementChannel` remembers, for the life of its connection, what cannot change while connected:
an unsupported operation (per resource type and arity) and a broker attribute that would not be read.
Not a queue's attribute, whose failure also means "queue gone", and not a denial, which a reloaded
security setting can lift. A different broker is a new channel and is asked afresh.

What this changed on the pages: health reads each attribute on its own, and its pressure checks
answer false only alongside `unchecked()`, which names what could not be checked; `/broker` reads
each panel independently with its read time; diagnose skips only the checks whose input was
refused and lists them under *Could not check*, so no findings never reads as "all is well" when it
means "could not look"; the queue page's scheduled list, the address page's diverts and each
subscription's age stand alone. `DivertDirectory` used to leave out a divert whose attributes failed,
on the theory it had just been destroyed; it now lists the names again and, if the divert is still
there, reports the list as incomplete instead of silently short.

Not converted: the `listQueues` counters. Their shape is verified on every supported version, every
counter is always present, and a queue listing the broker refuses is the page's failure anyway.

## One broker, and only the one named

The Artemis client load-balances a factory's connections across the topology the broker announces,
by default. A standalone broker announces its acceptor, `0.0.0.0:61616`, and from the client's side
that address can be a different broker entirely. On 2.57.0 (not 2.55.0) a second connection opened
while the first was open landed on the broker behind `localhost:61616` — on this machine, the kind
cluster's. The app opens one connection per session so it was never exposed, but that was an accident
of the code; `BrokerSession` now sets `useTopologyForLoadBalancing=false`, and so does every test URL.

## Testing approach

The services that parse management JSON are tested by mocking `BrokerSession` and
`ManagementChannel` and feeding them the reply shapes captured from a live broker in
`.claude/memory.md`. Those tests must sit in the `broker` package to reach the package-private
`requireManagement()` / `requireSession()`.

`ManagementChannel` itself cannot be mocked at all: `JMSManagementHelper` refuses to build a
request from a foreign message, so a mocked `Session` never reaches the send. Its round trip, typed
attribute reads, refusals and timeout live in `ManagementChannelIT`, against a real broker started
by Testcontainers under `mvn verify -Pintegration`. `ReadOnlyGuaranteeIT` runs there too, driving
every read path three times over and asserting messageCount, delivering, acked and added are
unchanged — the non-destructive guarantee is checked by the build rather than by hand.

Every IT runs once per supported broker version, from pinned images in the pom, in separate failsafe
executions. The fixture refuses to seed unless two connections held open together report the same
node id — added after an unguarded 2.57.0 run put test queues into the cluster broker (see above).
`PartialAvailabilityIT` runs its own broker with `management-message-rbac` on, which the broker
reads only at startup, so its entrypoint writes the configuration between creating the instance and
running it: one user denied specific reads, one without `manage` at all.

Neither of those renders a template, and neither do the controller tests, which assert on model
attributes — those populate perfectly right up until the view fails. That gap let `/broker` return
500 from Phase 5 until the cluster work in Phase 8 walked every page. `PageRenderingTest` asserts on
rendered HTML to catch that class of break, and a new page is not covered until it has a case
there.
