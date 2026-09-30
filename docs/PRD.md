# artemis-browser — Product Requirements

Status: **Phase 13 in progress: broker visibility and operational explanation. Phase 14 planned
2026-09-29: incident investigation. Phase 15 specified 2026-09-29: interactive regression lab.**
Phase 12 merged 2026-09-27 (PR #26); Phase 11 merged the same day (PR #24). Phases 1–9 shipped and the project was declared
feature-complete; it was reopened on 2026-09-27 for one theme — subscription inspection — and
Phase 10 merged the same day (PR #19). It was reopened again the same day, on request, for a second
theme: messages delivered to a consumer and not yet acknowledged, which merged as Phase 11. See
*Phase 10*, *Phase 11*, *Phase 12* and the planned *Phase 13* below. The tool is run by one person, which is what settles the open questions about replicas,
certificates and multi-user login.
Last updated: 2026-09-29.

## What this is

A read-only web browser for ActiveMQ Artemis. You give it a broker, and it shows you what is on
that broker — queues, addresses, messages, connections, producers, health — without ever changing
any of it.

The audience is whoever is holding the pager or the ticket: someone who needs to know whether a
message arrived, what is stuck in a dead-letter queue, why an address has unrouted messages, or
which producer is filling a queue up. That person usually has the broker's console available and
does not want it, because the console can also consume, move and delete, and because reaching for
it means reaching for a tool that can do damage on a bad day.

### The product principle

**Read-only is the product, not a detail.** Nothing in this codebase consumes, acknowledges, moves,
expires or deletes a message. That is what makes it safe to hand to someone at 3am, safe to point
at production, and safe to use while an incident is live. Any feature that would break it is out of
scope until it is deliberately, explicitly put in scope — and that decision changes what the tool
*is*, not just what it does.

Two consequences worth stating, because they come up every time:

- Every read path is verified non-destructive against a real broker, not assumed. Counts,
  delivering and acked are checked before and after.
- "Just a retry button" is not a small feature. It is a different product with a different risk
  profile and would need its own confirmation flows, audit trail and probably authentication.

## The jobs it does

| Job | Page |
|---|---|
| Point at a broker; come back to one I used before | `/` |
| See everything on the broker at a glance, and watch it move | `/overview` |
| Read the messages on one queue, narrowed by a filter | `/queues` |
| Read one message in full — any type, every property | `/message` |
| Find a message when I know the ID but not the queue | `/search` |
| Take the evidence away with me | `/export` |
| See where a multicast address fans out to | `/addresses` |
| See who is subscribed to one address, what each gets, and who is behind *(Phase 10)* | `/address` |
| See which consumer is holding which messages it has not acknowledged *(Phase 11)* | `/queues` |
| See whether the broker itself is healthy, and who is attached | `/broker` |

## Constraints

These are decisions already made, not open questions. They are here so a new feature can be checked
against them.

- **Read-only**, as above.
- **Loopback by default, reachable further only with a login and TLS.** `server.address` decides
  where it listens; `AllowedHostFilter` decides which `Host` headers it answers; `ReachabilityGuard`
  refuses to start exposed without both a login and TLS. The constraint used to be "loopback only,
  because there is no login" — Phase 7 built the login rather than relaxing the filter, which is
  what that constraint always said would have to happen first.
- **The broker password is never retained.** It goes from the form to `connect()` and is dropped.
  Remembered connections store host, port and username only.
- **One broker per HTTP session.** Session expiry is connection expiry. Multi-broker comparison is
  not in scope.
- **Server-rendered Thymeleaf, no npm, no build step.** Three-to-eight screens do not justify a
  frontend toolchain in the dev loop.
- **Nothing streams a whole queue through this process** when the broker can do the work. Paging and
  filtering happen broker-side; the exceptions are deliberate, bounded and documented.

## Shipped

- **Phase 1** — connect (host/port/username/password), list queues, inspect one queue's counters and
  messages.
- **Phase 2** — all-queues overview with optional auto-refresh, server-side pagination, core-syntax
  filtering, per-message detail, remembered broker locations.
- **Phase 3** — cross-queue search, CSV/JSON export, broker health and connections view, address
  view with multicast fan-out.
- **Phase 4** — producers panel on the broker health page; disk-usage percentage fix; export now
  reads real bodies over JMS rather than shipping the broker's truncated ones; tests for the
  management-JSON parsing layer. 116 tests.
- **Phase 5** — the P0, P1 and P2 items below: a bounded export, `ManagementChannel` and the
  non-destructive guarantee tested against a real broker, search export, a sortable and filterable
  overview.
- **Phase 6** — the P3 items below: single-message download, scheduled messages (which `browse`
  does not return at all), message properties in the list, and a dropped connection that says so.
- **Phase 7** — a login of its own, so the tool can sit on a jump host rather than only on
  loopback; a "why is this stuck" diagnose page; and the measured scale answer, which found a
  correctness bug rather than a ceiling — a filtered count samples only the broker's browse page
  size, so cross-queue search reported "0 matches" for a message that was definitely there.
- **Phase 8** — a container image and a Helm chart for the local Kubernetes cluster; see *Shipped
  since* below for what that arrangement does and does not claim to be.
- **Phase 9** — every page rendered in a test, after Phase 8 found `/broker` had been returning 500
  since Phase 5 with no test going red. 188 tests.

## The backlog as it was written — all shipped

Kept in full rather than summarised away: each item says what "done" looked like, and several
turned out to be covering something larger than the line suggested, which is worth more than the
tidier list it would collapse into. Phases 5 and 6 are what cleared it.

### P0 — risk introduced or carried

- [x] **Bound what an export holds in memory.** Done 2026-09-19: `artemis.export-body-total-chars`
      (20M characters, ~40MB) caps what the JMS pass keeps, and the pass stops reading once the
      budget is spent — rows past it keep their management body, flagged truncated, so the file says
      which rows were cut. Both writers now stream to the response instead of building the document
      in memory first, which the JSON path was doing.

- [x] **Land Phase 4 on `main`.** ~~`main` was still at the Milestone001 merge and did not reflect
      Phases 3 or 4 at all.~~ Done 2026-09-19: PR #6 `phase04` → `Milestone002`, then PR #7
      `Milestone002` → `main`. Phase 5 branches from there.

- [x] **Flag large messages in the list.** Done 2026-09-19: a `large` badge on the queue list, the
      search results and the message detail, plus a `largeMessage` column in CSV/JSON export. The
      two read paths disagree on how a large message announces itself, so both are read — see
      `.claude/memory.md`. Verified against a broker holding 250KB messages.

### P1 — the gap the last two bugs came through

- [x] **Test `ManagementChannel` against a real broker.** Done 2026-09-19: `ManagementChannelIT`
      covers the successful round trip, a typed attribute read, a refused operation and a timeout,
      against a real broker started by Testcontainers under `mvn verify -Pintegration`. It turned
      out to be more than a preference: `JMSManagementHelper` refuses to build a request from a
      non-Artemis message, so the send path *cannot* be mocked at all. The refusal case is a real
      one the broker rejects rather than a permission denial — a user without `manage` fails at
      connect time instead, which is a different path.

- [x] **Keep the live verification repeatable.** Done 2026-09-19: `mvn verify -Pintegration` seeds
      a broker with long text, bytes and multicast messages, drives every read path three times over
      (list, detail, export, search, broker info) and asserts messageCount, delivering, acked and
      added are all unchanged. `ReadOnlyGuaranteeIT` also pins the things that were only ever
      checked by hand: whole bodies over JMS, bytes bodies, FQQN browsing, and JMS-style filter
      names silently matching nothing.

### P2 — product gaps a user will actually hit

- [x] **Export a search result.** Done 2026-09-19: `/export` without a `name` exports everything a
      search matched. Each matching queue is re-read with export bodies and written before the next
      is fetched, so the memory ceiling is one queue's worth however many matched. CSV names the
      queue per row; JSON groups messages under their queue.

- [x] **Sort and filter the overview.** Done 2026-09-19: every column sorts (click again to
      reverse, arrow shows which way), and a box narrows by queue *or* address name. Name breaks
      every tie so a refresh cannot shuffle equal rows. The shared refresh control now carries the
      view's own parameters, which it previously would have dropped.

- [x] **Say which filter dialect the box wants, where the box is.** Already true when the item was
      written — both filter inputs carry the note and worked examples inline, and the queue page's
      placeholder shows two. Checked rather than rebuilt. `ReadOnlyGuaranteeIT` now also pins the
      underlying behaviour: a JMS-style name matches nothing while the core equivalent matches.

- [x] **Page a search result.** Met by the existing per-queue links, which already carried the
      filter through to the queue view; verified against a queue of 60 matches paging correctly from
      the search page. The "showing first 50" badge is now the link itself and says what it does,
      which is the part that was actually missing.

### P3 — worth doing, nothing breaks without it

All four done in Phase 6 on `phase06`. Two of them turned out to be covering something larger than
the line suggested, which is noted below rather than quietly folded in.

- [x] **Download a single message**, headers, properties and body together, as `.txt` or `.json`
      from the detail view. Attaching a message to a ticket is most of what someone does after
      finding one, and doing it from the page meant three selections and a lost format.
- [x] **Show scheduled messages' delivery times.** Bigger than it read: management `browse` does not
      return scheduled messages *at all*, so a queue holding one reported a message count of 1 and
      displayed an empty table with no explanation. Scheduled messages are now read through
      `listScheduledMessagesAsJSON` and listed in their own panel with delivery times, an *overdue*
      flag when that time has passed, and a note on the empty table saying where they went.
- [x] **Surface message properties in the list** — read from the typed property tables the browse
      reply carries, *not* from `PropertiesText` as the item proposed. `PropertiesText` is a Java
      map's `toString()` with no escaping, so a value containing `", "` or `"="` would corrupt the
      row. Artemis's own `__AMQ_CID` and `_AMQ_ROUTING_TYPE` are hidden; exports carry the rest.
- [x] **Make a dropped connection say so.** The description here was wrong, which the fix depended
      on noticing: a broker restart did *not* redirect to the connect form. It left the user on the
      page with "Management call broker.listQueues() failed: Session is closed" and went on
      believing it was connected. A lost connection is now its own exception — deliberately not a
      `BrokerException`, since the controllers catch those to show an inline error and would have
      swallowed it — which closes the dead session and returns to the connect form saying what
      happened and that nothing on the broker was changed.

## What the cluster deployment claims to be

Worth stating once and plainly, because the gap between "it runs in Kubernetes" and "it is deployed
properly" is exactly where a read-only tool quietly acquires a production footprint nobody decided
on.

The pod terminates TLS itself, because that is what `ReachabilityGuard` requires of anything not
bound to loopback — the alternative was defeating a control this project deliberately built in
Phase 7. But the pod's certificate is self-signed, and the browser→ingress hop is HTTPS only with
a certificate from mkcert's local CA, trusted on this machine alone (since 2026-09-27; plaintext
without it). That makes this a **local-cluster arrangement** and not a deployment. It is a convenient way for one person to
run the tool on their own cluster, and it is not evidence the tool is ready to be shared.

What it would take to be more than that is answered under *Closed as won't do* below — and the
answer is that nothing is asking it to be.

## Phase 9, and the end of the backlog

- [x] **Render every page in a test.** Done 2026-09-20: all nine templates have cases in
      `PageRenderingTest`, and so do the branches — a page's error, empty and populated states are
      different regions of markup, and only the populated one costs a fixture to reach. 19 cases,
      up from 2. Each was checked the only way that means anything: every template was broken in
      turn with an expression that cannot render, and the failures counted. All 19 failed, one
      template at a time, so no case is passing for a reason other than the page rendering. 188
      unit tests, up from 172.

### Closed as won't do — 2026-09-20

The two items the Phase 8 entry left open were closed, not deferred. Both were only ever downstream
of one question — whether anyone other than the author runs this — and the answer is no. They are
recorded here with the reasoning so they are not rediscovered as gaps and quietly reopened. One of
them, HTTPS in the browser, was later done anyway (2026-09-27); the session store stays won't-do.

- **An external session store.** ~~A second replica needs one.~~ **Won't do.** A single replica is
  correct for a single user, and "one broker per HTTP session" is a stated constraint rather than a
  limitation to engineer around. What this would actually buy is surviving a pod restart without
  dropping the session *and* the broker connection with it — which matters to a team and not to one
  person who can simply connect again.
- [x] **A certificate on the ingress, and browsing over HTTPS.** ~~The browser→ingress hop is
  plaintext and the pod's certificate is self-signed.~~ **Done 2026-09-27**, having first been
  closed as won't do. The ingress serves HTTPS, and redirects HTTP to it, whenever the
  `artemis-browser-ingress-tls` Secret exists; `scripts/new-tls-secret.ps1` makes it with mkcert, and
  the install stays plain HTTP until it does. Over HTTPS the session cookie is `Secure; HttpOnly` and
  ingress-nginx adds HSTS. The certificate comes from mkcert's local CA, trusted on this machine
  alone rather than publicly — which is the right fit for a single-user local cluster, and why this
  is still a **local-cluster arrangement** rather than a deployment. The pod's own certificate, which
  only the ingress sees, is still self-signed.

If that answer ever changes, the **first** thing to decide is not a certificate or a session store — it
is the login. The tool authenticates against one configured account, so a team shares one password: no
record of who read which queue, and rotating it means telling everyone at once. That is the same
shape of decision Phase 7 made deliberately rather than by erosion, and it would come before any
certificate or session store.

### Status: feature-complete — until 2026-09-27

All eight jobs in the table above were shipped, the backlog was empty, and there was no Phase 10
pending a reason to exist. Finding nothing left to build was an outcome, not a gap in the planning.
The reason arrived as a request rather than a gap: see below.

## Phase 10 — Subscriptions: what's listening to an address, and what each listener gets

Reopened 2026-09-27 on the author's request, for one theme. `/addresses` answers "where does this
fan out to" — counters, routing type, an unrouted warning, and the bound queues. It cannot answer the
questions that follow once an address has subscribers: who is subscribed and how (durable, shared,
temporary), what each subscription's filter lets through, which subscriber is behind and by how
much, whether subscriber B got message X, where messages go when the address fills or delivery
fails, and whether a divert is taking messages before any subscriber sees them.

Every item is a read. Nothing sends, subscribes, or creates anything.

### P0 — protect read-only before adding new broker calls

- [x] **Only allow read operations through `ManagementChannel`.** Done 2026-09-27: `READ_OPERATIONS`,
      refused before a request exists; 13 mutating names tested as refused with nothing sent. This phase adds about five new
      management operations, and today nothing stops a mutating one except review. A fixed allowlist
      of operation names, refusing anything else, with a test that every name on it is a getter or a
      `list*`. "Verified, not assumed" then covers code not yet written.
- [x] **Check every new response shape against a real broker before parsing it.** Done 2026-09-27
      against 2.44.0 — recorded in `.claude/memory.md`. It changed the plan in three places:
      management resources take the *bare* queue name (FQQN fails there); diverts have no listing
      operation, only `getDivertNames` then one read per divert; and consumer client ids come from
      `listConsumers`, since `listAllConsumersAsJSON` has none.
      `getAddressSettingsAsJSON`, the divert listing, `getFirstMessageAge`, and the `filter` / `user`
      / `exclusive` fields of `listQueues` are all unverified. Every earlier shape surprise — quoted
      counters, JSON inside a string, a 0..1 ratio — parsed into a believable wrong number. Each goes
      into `.claude/memory.md` before a parser is written for it.

### P1 — a page for one address

- [x] **`/address?name=`**, following `/queues?name=`, linked from each heading on `/addresses`, the
      overview's address column, and diagnose findings. `/addresses` stays as the index.
- [x] **A subscriptions table in place of the plain queue list.** Per row: the **kind** (anycast
      queue, durable subscription, shared durable, non-durable/temporary); the **filter**, labelled as
      core syntax; the attached **consumers** with client ID, user and remote address from
      `listAllConsumersAsJSON`; and a **browse link** by FQQN. Client ID and subscription name are
      split from names like `clientId.subName` — a guess, shown as one, since shared and non-durable
      queues are named differently.
      Done 2026-09-27. The guess is marked *(from the name)* unless an attached consumer's client id
      confirms it. Shared durable subscriptions turned out to be indistinguishable from durable ones
      by anything the broker reports — `maxConsumers` is -1 on both — so there is no "shared durable"
      kind; the page says "durable" and does not pretend to know more.
- [x] **Producers sending to this address**, from `BrokerProducer.address`, which today only shows on
      `/broker`.

### P2 — how far behind each subscriber is

- [x] **Lag per subscription**: messages waiting, delivering, and the age of the oldest message
      (`getFirstMessageAge`). One call per queue, so it belongs on the single-address page only,
      never the index.
- [x] **Measure lag by what is waiting, not by differences in `messagesAdded`.** A filtered
      subscription is *meant* to receive fewer messages; comparing `messagesAdded` against the fullest
      subscriber would call every filter lag. Recorded in `architecture.md` beside the FQQN note.
- [x] **"Which subscriptions still hold message X?"** Search limited to one address's queues,
      reusing `MessageSearchService` over a subset. Answers "did subscriber B get it" without
      inferring from counters. Filtered **browse**, never a filtered count, which samples only the
      first 200 messages.

      Done 2026-09-27, and bigger than it read. Checked against the broker first: **a message
      delivered to a consumer and not yet acknowledged is invisible to browse**, filtered or not, and
      to `firstMessageAge`. So the search reports three outcomes, not two — waiting here, not here,
      and "not waiting, but N in flight or scheduled could not be searched" — and lag is the age of
      the oldest *undelivered* message. The same blind spot had the queue page saying "This queue is
      empty." for a queue whose every message was in flight; it now says what is happening.

### P3 — where messages go besides subscribers

- [x] **Address settings**: dead-letter and expiry addresses (linked), max size and full policy, max
      delivery attempts, auto-create/delete, retroactive message count. Done 2026-09-27, with every
      other setting the broker reported behind a disclosure. The broker leaves a setting *out* at its
      default, so an absent one shows as "not set" rather than as zero; and a named dead-letter or
      expiry address that does not exist is flagged, since a message sent there is dropped.
- [x] **Diverts** from and to the address: target, filter, routing type, and whether it is
      exclusive. **An exclusive divert means the address's own subscribers never receive the diverted
      messages**, and nothing reports an error — a silent failure of exactly the kind this project
      keeps a list of. Done 2026-09-27; there is no listing operation, so it is `getDivertNames` and
      one read per field per divert.
- [x] **Two diagnose findings**: an *abandoned durable subscription* (no consumer, still growing —
      the classic way a multicast address fills a disk), and an *exclusive divert* on an address that
      has subscribers. Done 2026-09-27. "Still growing" is not something one snapshot can show, so the
      finding says what the subscription costs rather than claiming a trend; it links both the queue
      and the address. A multicast queue named after its own address is left to the general
      "nothing is reading" finding, since that is a queue someone configured, not a subscriber who
      left.

### Considered and left out

- **Evaluating a filter against a sample message** ("would this reach sub-b?"). It means
  reimplementing Artemis's filter language here — a new source of silently wrong answers. Filters are
  shown, not evaluated.
- **Resolving which concrete addresses a wildcard like `news.#` matches.** The delimiter and wildcard
  characters are set in `broker.xml`, which management does not appear to expose; matching would run
  on a guessed grammar.
- **Sending a test message to see how it routes.** Sending is out of scope.

### Verification

Done as planned, on an `it-feed` address rather than `events` so the earlier fixtures stay as they
were; plus an in-flight subscriber, which P2's broker check made necessary. 233 unit tests, 21
integration.

The integration broker gains an `events` address carrying a filtered durable subscription, an
abandoned durable subscription, a live non-durable subscription, a shared durable subscription, and
an exclusive divert — created by the test setup, never by the app. `ReadOnlyGuaranteeIT` loads the new
pages three times and asserts the counters unchanged; `PageRenderingTest` gains the address page's
empty, populated and broker-error states.

## Phase 11 — In-flight messages: what a consumer has been given and not acknowledged

Reopened 2026-09-27 on the author's request. Phase 10 found that a message delivered to a consumer
and not yet acknowledged is invisible to `browse` and to `firstMessageAge`, so the tool could only
say "N in flight could not be searched" — honest, and a dead end. Artemis does expose them, through
`listDeliveringMessagesAsJSON`; this phase reads it.

What a 2.44.0 broker returned before any of this was planned (details in `.claude/memory.md`):

- **Per consumer, not per message.** Each entry names its consumer only by a `ServerConsumer`
  `toString()` — `id=<connection>:<session>:<n>` inside a longer dump — with no client id and no field
  the consumer listings share. Tying it to a client means parsing that text.
- **Headers and properties, no body.** And the JMS browser cannot see these messages either, so a
  body is not available from anywhere; an in-flight message can be listed, not opened.
- **No paging, no filter.** 300 in-flight messages were a 65KB reply in 11ms. A consumer buffering
  small messages can hold thousands, so the reply needs a cap on this side.
- **"In flight" is "in the consumer's client buffer", not "the application has it".** Consumer A
  received two messages; the third sat in A's buffer, counted as delivering to A, while consumer B on
  the same queue received nothing. That is also exactly how one consumer starves the others.
- Reading it moved no counter.

### P0 — guard and verify

- [x] **Allowlist `listDeliveringMessagesAsJSON`.**
- [x] **Measure it at scale**: thousands of small messages in one consumer's buffer — reply size and
      time — and set the cap from the measurement, not a guess.
      Done 2026-09-27: linear at ~280 characters per small message, from 278KB/15ms at 1,000 to
      28MB/800ms at 100,000. A consumer on the default 1MB window stops at about 3,200 small
      messages; only an unbounded window takes more. So `artemis.in-flight-limit` is 5,000, and it is
      checked against `deliveringCount` *before* the call — the reply cannot be made smaller, only
      not asked for. Above it the page says how many are in flight and that they were not listed.
- [x] **Check the consumer text from a non-CORE client.** If it does not parse, show it as it came
      rather than guess a client.
      Done 2026-09-27 with AMQP, STOMP and OpenWire consumers. All parse, but **OpenWire's session ID
      contains colons** (`ID:host-…-1:1:1`), so the id is split at the first and last colon, never on
      every one; the result matches `listAllConsumersAsJSON` for all four protocols. Text of any
      other shape is kept verbatim with no ids.
- [x] **`ReadOnlyGuaranteeIT` reads the delivering list** with messages in flight and asserts no
      counter moved. Done 2026-09-27: two received and three buffered, unacknowledged, read three
      times alongside every other read path.

### P1 — an "In flight" panel on the queue page

- [x] **Grouped by consumer**: client id, remote address and in-transit count where the consumer can
      be matched; each message's ID, send time and properties. No body, and the panel says why.
      Done 2026-09-27. Matching takes both listings: `listAllConsumersAsJSON` has the
      connection/session/consumer triple the delivering list is keyed by, `listConsumers` has the
      client under the same `sequentialId`. Over the limit the messages are not read but the
      consumers holding them still are, with their counts — who holds them is most of the answer.
      200 rows are drawn per consumer; the rest are counted. The filter box does not apply here,
      and the panel says so.
- [x] **The empty-queue note** that says messages are in flight links to the panel. So do the
      *Delivering* counter and the filtered "could not be searched" note.
- [x] **Ages are from send time.** There is no delivery time in the reply, so the page cannot say how
      long a consumer has held a message, and does not pretend to. Shown as "sent … (4m 12s ago)",
      with the reason under the heading.

### P2 — search sees in-flight messages, by message ID

- [x] **Exact message-ID lookup against the delivering lists.** Not general filters: evaluating
      `region = 'eu'` against in-flight messages would mean reimplementing Artemis's filter language,
      which this project declined in Phase 10 for being a new source of silently wrong answers.
      Done 2026-09-27. A lookup is `AMQUserID = 'ID:…'` and nothing else, or a bare `ID:…` pasted in,
      rewritten to that. Checked on 2.44.0 first: browse, the delivering list and the consumer's
      `JMSMessageID` all carry the same ID; `AMQUserID` needs the `ID:` prefix; `JMSMessageID = '…'`
      matches nothing. Cross-queue search uses it too, and every search now says how many in-flight
      messages it could not look at — it said nothing about them before.
- [x] **The address page's "which subscriptions hold a message"** turns "N in flight could not be
      searched" into "in flight to consumer X" — a real yes — when the lookup is by message ID.
      Done 2026-09-27, and it turns the other way too: a subscription whose in-flight messages were
      checked and did not hold it is a clean "not on this queue". Only a queue over the in-flight limit
      stays "could not be searched", and says why.

### P3 — diagnose

- [x] **A consumer hoarding the queue**: one consumer holds everything in flight while others on the
      same queue hold nothing — usually a consumer window set too large.
      Done 2026-09-27, from the consumer listing diagnose already reads — no new call unless it fires,
      and then one, to name the client. It needs at least 10 held (`HOARDING_MIN`): one message in
      flight while the others idle is a consumer working, not a buffer. Browsers and this tool's own
      consumer are not counted as "the others". Verified against a real broker in `ReadOnlyGuaranteeIT`.
- [x] **Messages in flight a long time**: the oldest in-flight message was sent long ago, suggesting
      a consumer stuck mid-processing. Measured from send time, and the finding says so.
      Done 2026-09-27: ten minutes from send time, naming the message and its holder. Where the queue
      also has messages waiting, the finding says the message may have spent that time in the backlog.
      This is the one diagnose check that reads delivering lists, so it has a budget — four times
      `artemis.in-flight-limit` for the whole page, smallest queue first — and what it leaves unread
      is a note on the page rather than a finding: a limit of this page is not a fault on the broker.

### Considered and left out

- **Bodies of in-flight messages.** The broker does not return them.
- **Delivery times.** Not in the reply.
- **Anything that releases, redelivers or re-routes an in-flight message.** Not read-only.

## Phase 12 — Where messages went, whether they are moving, and who is doing it

Planned 2026-09-27 on the author's request. It takes the three items queued before Phase 11, the
pager bug Phase 11 found, and one new item the author chose from a suggested list: rates. What ties
them together is the question the tool still answers worst — *is anything happening, and to whom?*
Today every page is one snapshot, starts from a queue or an address, and cannot say where a message
went if it is in neither a queue nor the dead-letter queue.

Every item is a read. Every item begins the way Phases 10 and 11 did: the broker is asked first and
its answers recorded in `.claude/memory.md`, before anything is parsed.

### P0 — correct what is there, and measure what is new

- [x] **The pager counts messages browse cannot return.** With 250 messages all in flight, the
      queue page reads "showing 0–0 of 250 … Page 1 of 5" over an empty table: the total comes from
      `countMessages`, which includes in-flight messages — and scheduled ones — while `browse`
      returns neither. First check on a broker what `countMessages(filter)` includes when a filter is
      given; then page by what browse can reach (waiting = messages − delivering − scheduled, when
      unfiltered), and let the in-flight panel and the scheduled list account for the rest.
      Done 2026-09-27, and the check found two worse cases of the same thing. The filtered count
      is waiting-only *and* a sample of the first 200 messages: 500 matches counted 100, so the
      filtered pager stopped at page 2 of 10. And the cross-queue export chose its queues by that
      count, leaving out any queue whose matches lay past its first 200 messages. A filtered page now
      has no total — "showing 51–100, and more after these", no Last link — and finds its next page by
      browsing one row at the next offset; export chooses queues by a one-row browse. Covered against
      a real broker by `FilteredPagingIT`.
- [x] **Measure the new pages at scale.** Phase 7 measured every page at 100,000 messages; nothing
      has measured `/address`, which makes one `firstMessageAge` read per non-empty subscription and
      one read per divert field; Phase 11's in-flight panel, whose reply has no paging; the ID
      lookup, which reads a delivering list on every queue with anything in flight; or diagnose's
      in-flight budget. Seed an address with a few hundred subscriptions and consumers buffering
      thousands of messages, time each page end to end, and add the rows to *Measured limits* in
      `.claude/memory.md`. Done means numbers, not a feeling — and a fix, such as capping the
      per-subscription reads, only where a number calls for one.
      Done 2026-09-27 on one broker with 300 subscriptions on an address, consumers holding 4,434,
      4,900 and 8,000 messages, and 50 more queues with 100 in flight each (~355 queues). Every page
      came in under 1.3s; the slowest was finding a filter across 300 subscriptions (~1.2s, 1MB of
      page), which is linear and bounded. No number called for a fix. Table in `.claude/memory.md`.

### P1 — expired and killed: where messages went when they are not in the dead-letter queue

- [x] **Confirm what each counter counts.** `listQueues` already returns `messagesExpired` and
      `messagesKilled` for every queue, quoted like the other counters; the tool reads neither.
      Killed is expected to mean "exceeded max delivery attempts" — check whether it counts a message
      that was then dead-lettered, one that was dropped for want of a dead-letter address, or both.
      Likewise for expired and the expiry address.
      Done 2026-09-27 on 2.44.0: **both, for both.** A message killed with a dead-letter address
      counted 1 and reached DLQ; one killed with none counted 1 and was gone. Expired the same with
      and without an expiry address. So the counter can never say a message was lost — only the
      address settings can, which is what the finding below is built on. An unset address reads back
      as `""` as well as absent; both mean none.
- [x] **Show them** on the queue page, the overview (sortable, like the other counters) and the
      address page's subscription table. Done 2026-09-27, with a note on each page that the count is
      the same whether a message was kept or dropped, pointing at the address page and diagnose.
- [x] **A diagnose finding for messages killed or expired with nowhere to go**: a non-zero counter
      on a queue whose address settings name no dead-letter or expiry address, or name one that does
      not exist (the address page already detects the latter). Those messages are gone, and nothing
      else reports it. The settings read is one per affected address, only when a counter is non-zero.
      Done 2026-09-27, plus a third case: a dead-letter or expiry address that exists but has no
      queues also drops what is sent to it. Killed-and-dropped is "not moving"; expired-and-dropped is
      "worth a look", since dropping stale messages is often the intent. The settings are today's and
      the counters run from broker start, so each finding says "if the settings were the same when it
      happened". Verified against a real broker in `KilledAndExpiredIT`.

### P2 — rates: is it moving?

- [x] **Messages in and out per second, per queue**, from the difference between two readings of
      `messagesAdded` and `messagesAcknowledged`. Recommended source: the previous reading kept in
      the HTTP session, so the overview's auto-refresh yields a rate on every refresh after the first
      at no extra cost, and a page with no previous reading says so rather than showing zero. A
      counter that went *down* means the broker restarted; that interval shows no rate.
      Done 2026-09-27 (`RateTracker`, session-scoped), with one correction from the broker check:
      **a restart does not make the counters go down reliably.** `messagesAdded` restarts at what
      the journal reloads, so a queue holding 3 read added=3 on both sides of a restart. A restart is
      caught instead from `broker.uptimeMillis` being shorter than the interval; a counter going down
      still drops that one queue's rate (a reset, or a queue made again). Readings under 2s apart keep
      the last rates rather than divide by almost nothing. "Out" is acknowledged — expiring and being
      killed are counted separately.
- [x] **Shown on the overview and the queue page**, with the interval it was measured over. Done
      2026-09-27: a dash where a queue has no earlier reading, and a note saying what it takes to get
      one. Checked live against steady traffic of 10/s in and 5/s out: 9.7 and 4.9 over 5s.
- [x] **Diagnose uses them where one snapshot could not.** The abandoned-subscription finding can
      say "still growing" when it is; the long-in-flight finding can tell "this queue is
      acknowledging N/s" (working through a backlog) from "acknowledged nothing in the last N
      seconds" (stuck). Diagnose takes a second reading a few seconds after the first when the
      session has none recent enough — measured, and said on the page, since it makes the page
      slower. Done 2026-09-27: "recent enough" is five minutes; the wait is 3s, and the page says
      when it waited.

### P3 — a page per client

- [x] **Establish what identifies a client across the listings**, on a broker, before building
      anything: whether `listConnectionsAsJSON` carries the client id; what
      `listSessionsAsJSON(connectionID)` and `listConsumersAsJSON(connectionID)` return; and how a
      client with no client id — common for plain CORE and AMQP clients — can be named at all
      (user and remote address are the likely fallback). Each operation is added to
      `ManagementChannel`'s allowlist only once its shape is recorded.
      Done 2026-09-27, and the route planned here was the wrong one: **`listConnectionsAsJSON` has
      no client id and no protocol**, so the per-connection `…AsJSON` operations were never needed.
      The paged listings do it: `listConnections` carries `clientID` and `protocol`, `listSessions`
      ties each session to its connection, and `listConsumers` and `listProducers` carry the session
      but not the connection. So the chain is client id → connections → sessions → consumers and
      producers. A client with no client id (`""`) is found by its connection, and named by its remote
      address. Allowlisted: `listConnections`, `listSessions`, `listProducers`.
- [x] **`/client`**: a client's connections (remote address, protocol, since when), sessions, the
      queues it consumes from with each consumer's delivered, acknowledged and in-flight counts, and
      the addresses it produces to. Linked from every place a client is named today: the in-flight
      panel, the address page's consumer table, `/broker`, and the hoarding and long-in-flight
      findings. Done 2026-09-27, plus the ID-search hit. `/client?id=` for a client id,
      `/client?connection=` for one without; the page says why an unnamed one is named by address,
      and that it will not follow the client across a reconnect.
- [x] **What it holds in flight**, from the consumer listing's counts, linking to each queue's
      in-flight panel rather than reading every delivering list again. Done 2026-09-27. Verified
      against a real broker in `ClientIT`, and `ReadOnlyGuaranteeIT` now opens every connection's
      client page.

### Considered and left out

- **Alerting on a rate.** Out of scope, as ever: this shows rates, it does not watch them.
- **Rate history or charts.** Left out of Phase 12. Reopened explicitly in Phase 13 for bounded
  history within the HTTP session; persistent monitoring remains outside this phase.
- **Closing a client's connection** from its page. Not read-only.

## Phase 13 — Broker visibility: explain pressure, behavior and change

Planned 2026-09-29 following a review of the implemented services, pages and tests. The next
phase extends the existing message browser into a more complete explanation of the connected
broker: why producers are blocked, why queues behave differently, when conditions changed, and
which broker-side evidence can be saved for an incident. **All six feature areas below belong to
this phase**, with reliability work supporting each increment. **Phase 13 is complete**: P0, all
six areas and the phase acceptance checks, as of 2026-09-30.

Keep the existing strengths: scheduled and in-flight inspection, subscriber lag, rates, client
drilldowns, settings and diverts. Extend those views rather than duplicating them. Every new
operation remains a read, with its actual response shape and availability checked against a real
broker before the parser and UI are built.

### Delivery order and scope

Build the availability and compatibility foundation alongside address pressure and queue behavior
first; then trends and incident snapshots; then connectivity/HA and transactions/permissions.
Connectivity moves earlier if the target deployment uses clustering, bridges, federation or
replication; transaction inspection moves earlier if its applications use XA. These conditions
change delivery order, not the inclusion of these features in Phase 13.

Keep one connected broker per HTTP session, the single-user deployment, server-rendered Thymeleaf,
and the read-only guarantee. Topology means what this broker reports about its peers, not opening
connections to other brokers or introducing a multi-broker dashboard. Session history does not
introduce background monitoring, alerting or an external session store.

### P0 — Trustworthy availability and reproducible compatibility

- [x] **Represent data availability explicitly.** Distinguish a real zero or false from an
      unsupported attribute/operation, permission denial, a failed read, and a value not collected.
      Replace silent numeric fallbacks in the affected parsing paths; missing or malformed health
      values must not look healthy. Carry availability through the view models, findings and exports.
      Done 2026-09-29: `Reading<T>` and `Availability`, from the broker's own wording, checked on
      2.44.0, 2.55.0 and 2.57.0 first. One limit found there: an attribute that does not exist, one
      on a gone resource and one RBAC denies all read "Problem while retrieving attribute", so an
      attribute can only ever be "unavailable". Health is a reading per attribute; diagnose lists
      what it could not check rather than let a missing disk figure pass as a healthy one. Exports
      of messages carry no health; availability reaches exports with the P4 snapshot.
- [x] **Isolate optional reads.** One unsupported or denied panel must not stop later panels from
      loading. Show each panel's collection time and relevant failure, while a genuinely lost
      connection still uses the existing connection-loss handling. Do not repeatedly probe a known
      unsupported capability on every refresh; scope cached capability information to the connection.
      Done 2026-09-29 for `/broker`, diagnose, the queue page's scheduled list and the address page's
      diverts and ages. Found on the way: a user without `manage` got a `JMSSecurityException` from
      send, which was treated as a lost connection — it is now a denial and the session survives.
      And a divert that could not be read was silently left out; the list now says it is incomplete.
- [x] **Define and test supported broker versions.** Pin integration-test images instead of
      `latest-alpine`, record the exact versions tested, and run the supported-version matrix.
      Include the deployed broker version and the selected newer supported version; treat the
      existing 2.44.0 measurements as historical evidence, not a universal API contract.
      Done 2026-09-29: 2.55.0 (deployed) and 2.57.0 (newest), one failsafe execution each, all ITs
      green on both. The image moved to `apache/artemis`. The first 2.57.0 run found something worse
      than an API difference: the client's topology load balancing sent a second concurrent connection
      to the broker behind `localhost:61616` — the kind cluster's — so tests seeded queues there and
      failed on data that had gone elsewhere. Turned off in every factory, app and tests, with a
      fixture guard that refuses to seed unless two connections reach one node.
- [x] **Verify every new read.** Record response shapes and units, add only verified read operations
      to the management allowlist, and cover unsupported, denied, empty and malformed responses.
      Current API documentation guides discovery; tests establish actual compatibility.
      Done 2026-09-29 for P0's reads (no operation was added to the allowlist). Denied is covered
      against a real broker by `PartialAvailabilityIT`, on both versions; unsupported, unavailable,
      empty and malformed by unit tests built from the recorded replies. Standing rule for P1–P6.

Done means an unavailable measurement is never presented as zero, an optional failure leaves
unaffected information usable, and the supported versions and feature differences are documented.

### P1 — Address pressure and storage detail

- [x] **Expand the existing address view.** Alongside its size and paging badge, show page count,
      address limit utilization, management-blocked state, and relevant size, message and paging
      limits where supported. Explain units and distinguish estimated address memory, persistent
      message size and physical disk utilization; do not present one as another.
      Done 2026-09-29: a *Storage and limits* panel, and index badges from the listing alone. Probed on
      2.55.0 and 2.57.0 first: `listAddresses` already carries `addressLimitPercent`, `numberOfPages`
      and `paging`, but not the management block, which is the `address.<name>` attribute
      `blockedViaManagement`. Three fields mean less than their names: `paging` is "over its limit"
      (FAIL/DROP report it with no pages), `addressLimitPercent` is bytes only and 0 with no byte
      limit, and BLOCK overshoots to 418% with `paging` false.
- [x] **Put usage beside policy.** Present current usage, the applicable configured threshold, and
      the consequence of reaching it: PAGE, BLOCK, FAIL or DROP. Preserve absent/default/unlimited
      distinctions already used by address settings. Paging by itself is normal behavior, not a fault.
      Done 2026-09-29: each limit in three states (not set, unlimited, a value) beside the policy's
      consequence as measured: PAGE kept all, BLOCK stalled the sender, FAIL answered AMQ229102, DROP
      accepted and kept nothing. The message-limit ratio is computed, since the broker's counts bytes.
- [x] **Explain pressure in Diagnose.** Link findings to the affected address and observed evidence.
      Separate an observed management block from inferred pressure; identify which address is near
      a limit without claiming it is the sole cause of global disk or memory pressure.
      Done 2026-09-29: findings carry a basis. A management block is observed; the rest are inferred.
      Settings are read only for addresses the listing shows near or over a limit, and the block is
      read per address up to 500, stopping at the first refusal. The global memory finding names the
      three largest holders, "not necessarily why it filled". Exercised on both versions by
      `AddressPressureIT`, and denied reads by `PartialAvailabilityIT`.

Done means a user can identify the affected address, see the policy and limits behind its behavior,
and distinguish normal paging from an observed block or a risk of rejecting/dropping messages.
Exercise paging and applicable full-policy scenarios against real brokers.

### P2 — Queue behavior and effective configuration

- [x] **Add a queue configuration panel.** Read supported settings for last-value behavior and its
      key, ring size, non-destructive delivery, purge-on-no-consumers, exclusive consumption,
      grouping, and dispatch thresholds/delays. Include relevant existing queue filters and routing
      settings so users can understand behavior in one place. Show effective queue values separately
      from address defaults; do not infer effective values from omitted fields.
      Done 2026-09-29: a *Configuration and behavior* panel on the queue page, and badges on the
      overview and subscription rows. Probed on 2.55.0 and 2.57.0 first (identical): `listQueues`
      already carries every setting as the queue's effective value, so nothing new is called per queue.
      Two traps: a last-value queue reports `lastValue` false (its key is the signal), and
      non-destructive is reported nowhere per queue. It is shown as unreadable, with the address's
      `defaultNonDestructive` beside it labelled as a default.
- [x] **Explain the consequences.** Connect each setting to what a user sees: replacement or
      retention of messages, removal when consumers disappear, delivery to one consumer, group
      affinity, or delayed dispatch. Reuse the existing subscription exclusivity information.
      Done 2026-09-29, worded from what was measured: replaced and evicted messages appear in no
      counter; consuming from a non-destructive queue acknowledges nothing; a purge counts as killed;
      an exclusive queue sent 20 to one consumer and 0 to the other. `delayBeforeDispatch` did not
      release dispatch in the time it names, so it is stated as a setting, not a promise.
- [x] **Make diagnostics configuration-aware.** Review findings about missing messages, idle
      consumers and consumer imbalance so intentional queue semantics are not stated as failures.
      Findings must distinguish observed facts from possible explanations.
      Done 2026-09-29: `Finding` gains an explanation, shown as "May be intended:" apart from the
      observed detail.
      - No hoarding finding on an exclusive or single-consumer queue. On other queues it is explained
        by group affinity when `GroupCount` > 0.
      - A queue waiting for more consumers gets its own finding instead of "delivered nothing".
      - Killed messages on a purging queue are no longer called failed deliveries.
      - "Nothing acknowledged" on an address that defaults to non-destructive says it may be that.

      Covered on both versions by `QueueBehaviorIT`, including that browsing these queues changes no
      counter.

Done means representative specially configured queues show their actual settings and explain their
behavior, with integration coverage for the semantics used by diagnostic findings.

### P3 — Bounded short-term trends

- [x] **Extend the existing rate tracker with session history.** Retain bounded observations of
      queue depth, ingress, acknowledgments, expired/killed counts and consumer counts. Collect
      during page reads/refreshes, reuse listings, and expose trends on the overview and queue view.
      Define configurable retention and sample/entity limits so memory stays bounded on large brokers.
      Done 2026-09-29: `RateTracker` keeps a `TrendHistory` beside its one interval. It records depth,
      added, acknowledged, expired, killed and consumers from the listing each page already made, with
      no new broker call. The limits are `artemis.trends.spacing-seconds` (15),
      `artemis.trends.max-readings` (240, about an hour) and `artemis.trends.max-queues` (500). A
      session therefore holds at most 120,000 small points.
- [x] **Show honest time intervals.** Display timestamps and sampling gaps, and distinguish an
      absent sample from zero activity. Handle broker changes/restarts, counter resets, queue
      recreation and session expiry without joining unrelated measurements. Keep acknowledged
      throughput separate from expiry and killed-message rates.
      Done 2026-09-29. Each interval is a measurement or a break. A restart (from uptime), a recreated
      queue (a new `id`) or counters going back is a break: shown, never averaged across, and it
      starts a new line. Verified on 2.55.0 that the id changes on recreation and survives a
      restart. A long wait is a *gap*; a reading the queue was missing from is counted, not taken
      as zero. Another broker, or a new session, starts over. An unreadable uptime is no longer
      read as "not restarted", and the page says a restart could not be checked.
- [x] **Use history to explain change.** Show whether a backlog is growing or shrinking, when
      consumption changed within the observed window, and whether expirations or killed messages
      are increasing now. Do not imply observations exist from before the session began collecting.
      Done 2026-09-29. The queue page has a *Recent trend* panel: a depth line, the backlog's change
      since the latest break, when acknowledgments stopped or resumed, expired and killed messages in
      the latest interval, consumer changes, and the last 12 intervals. The overview has a *Trend*
      column. Every trend is dated "since" this session's first reading. Covered by `QueueTrendTest`,
      and against a real broker by `TrendsIT`, which recreates a queue mid-history.

Done means a user can compare recent behavior across multiple intervals, with bounded memory and
tests for gaps, resets, retention and reconnects. Long-term metrics storage remains an optional
future integration with an existing monitoring system; optional JVM/GC/CPU metrics require a
separately available broker metrics source and are not promised by the management connection.

### P4 — Incident snapshot export

- [x] **Export operational evidence together.** Provide a downloadable snapshot containing broker
      identity/version, collection timestamps, health, queue/address counters, relevant settings,
      clients, available trend samples and Diagnose findings. Include the later connectivity and
      transaction/permission panels when available. Provide structured JSON and a readable summary.
      Done 2026-09-30: `/snapshot?format=json|text`, linked from `/broker` and `/diagnose`. It holds
      broker identity and health, every queue with counters, id and configuration, every address with
      its storage figures, address settings, acceptors, connections, consumers, producers, this
      session's trend readings, and a full diagnose run. Both formats come from one
      `IncidentSnapshot`. Connectivity joined it with P5; permissions join it with P6.
- [x] **Describe completeness.** Include a schema version, collection start/end times, sampling
      intervals, unsupported/denied/failed reads and any truncation or omitted sections. This is a
      sequence of observations, not an atomic broker snapshot. Keep collection bounded and reuse
      collected data where practical to avoid repeated expensive reads.
      Done 2026-09-30. The snapshot carries `schemaVersion`, the collection start and end, and
      `collectedAt` for every section. It records the limits it ran under and the trend spacing. An
      `unavailable` list names each read the broker would not give (section, item, availability,
      detail), and an `omitted` list gives what the bounds left out, with counts.
      - Client listings are capped at `artemis.snapshot.max-rows` (1000).
      - Address settings are read for at most `artemis.snapshot.max-address-settings` (200), those
        with something to explain first.
      - The queue listing is read once and reused as this session's trend reading.
- [x] **Keep evidence safe and useful.** Exclude credentials and message bodies by default, redact
      secrets from configuration/connector data, and keep existing message exports separate.
      Use stable identifiers and units so two saved snapshots can be compared later; an automated
      snapshot-diff viewer is not required for this phase.
      Done 2026-09-30. There are no message bodies (nothing browses), and the connection section has
      host, port and user only. Acceptors keep name, protocols, host and port, never their
      parameters. Setting values under secret-looking keys are masked (`Redaction`) and counted. The
      message exports are untouched. Field names carry units, times are ISO-8601 UTC, and queues are
      identified by name and broker id. A missing value is an object stating why, never zero or null.
      Tests: `SnapshotWriterTest`, `SnapshotServiceTest` and `SnapshotControllerTest`, plus
      `SnapshotIT` (a real broker, no counter changed) and `PartialAvailabilityIT` (a restricted
      user's snapshot lists what was denied).

Done means an incident report can be understood offline, including what could not be collected,
with tests for completeness metadata, bounds, secret exclusion and non-destructive collection.

### P5 — Broker connectivity and high availability

- [x] **Inspect this broker's reported topology and HA state.** Show local identity, active/backup
      role and replication synchronization where applicable, together with the peers and topology
      the connected broker exposes. Mark unsupported or inapplicable states explicitly.
      Done 2026-09-30: `/connectivity`, linked from the nav. Probed first on 2.55.0 and 2.57.0
      (identical) with a replication primary, its backup, a cluster peer, two bridges and two AMQP
      broker connections. The HA panel reads eight broker attributes, each its own reading; replica
      synchronization is "not applicable" under `Primary Only` and shared store, never a failure.
      The topology marks this broker's own entry. A backup listed there is only announced: it stayed
      70s after the backup stopped, while `replicaSync` went false in 8s. A replicated backup takes no
      client connections, so the page is seen from the primary.
- [x] **Inspect outbound messaging paths.** Show configured core bridges and supported broker
      connections, including federation/mirroring information where exposed. Report destination,
      connected/started state and available traffic counters with links to local addresses/queues.
      Never expose connector passwords or infer an end-to-end healthy path from a local connection.
      Done 2026-09-30. Cluster connections with their connected peers and the store-and-forward queue
      waiting for each; bridges with source queue and its depth, target connector (host:port),
      forwarding address, state, acknowledged and outstanding; broker connections with URI, state and,
      for a mirror, its queue's backlog. Every backlog links to its local queue. Traps: a bridge's
      "pending" counter is cumulative sent; `connectorsAsJSON` returns connector credentials in clear,
      so only name, host and port are read; a broker-connection URI is masked. Bridges and cluster
      connections have no listing, so they are read per field, at most 100 of each. Not read: core
      federation (`<federations>`) and connector services.
- [x] **Extend Diagnose and snapshots.** Identify observed disconnected paths or incomplete
      synchronization with evidence and applicability. Describe peer information as this broker's
      view; do not claim to have inspected a remote broker.
      Done 2026-09-30. Diagnose names a stopped or unconnected bridge, broker connection or cluster
      connection, messages waiting for a peer that is not connected, and a replication primary with
      no synchronized backup. Each is not moving when messages wait behind it, otherwise worth a
      look, and each says the far side was not inspected. A connectivity read that fails is listed
      as not checked. The snapshot gains a `connectivity` section, read once and handed to its
      diagnose run. Covered by `ConnectivityServiceTest`, rendered-page tests, and `ConnectivityIT`:
      three brokers per supported version, the app on the primary, secrets checked absent, counters
      unchanged, and the backup stopped to see the replica finding appear.

Done means a user can see where traffic may leave the connected broker and the reported state of
its HA relationships. Verify the supported paths using appropriately configured multi-broker test
fixtures, while the application itself retains a single broker connection context.

### P6 — Transactions and address permissions

- [x] **Inspect prepared transactions.** Show unresolved prepared XA transactions, identifiers,
      reported creation times/ages, and related message/address information where available. Bound
      detail collection and distinguish prepared XA evidence from unobservable application or local
      transaction state. Offer no commit, rollback or transaction-resolution operations.
      Done 2026-09-30: `/transactions`, linked from the nav. Probed first on 2.55.0 with a branch
      left prepared by closing its XA connection, and one committed through management. Each branch
      shows its Xid (base64, format id, global id, branch), the broker's creation time and an age,
      and every message it sends or received, headers and properties. Branches resolved by hand are
      listed. The page says active branches and local transactions are not reported. Bounded by
      `artemis.transactions.detail-limit` (100) branches, past which only summary lines are read,
      and 50 messages per branch. Commit and rollback stay off the allowlist. Two traps: the creation
      time is a locale string with no zone, so the zone is worked out from the connection listings
      and no age is claimed without it; and a message received in a prepared branch stays on its
      queue as delivering with no consumer, invisible to browse and the in-flight list.
- [x] **Inspect address permissions.** Show the roles and permissions reported for an address,
      with clear names for send, consume, browse and management-related rights where exposed.
      Permission to inspect security information may itself be denied; keep the rest of the page
      usable. Do not equate a role definition with proof of a particular client's effective access.
      Done 2026-09-30: a *Permissions* panel on each address page from `getRolesAsJSON`, all twelve
      permissions by name with what each allows, and whether the broker enforces security at all. A
      permission a broker does not report is shown as not reported, not as no. A denied roles call
      shows as not permitted with the rest of the page intact, and is not repeated per address. The
      panel says a role is not a user and the matching setting is not named by the broker.
- [x] **Link the evidence.** Connect transaction findings to related local resources and permission
      information to the address/client investigation workflow. Include both in incident snapshots
      subject to the same availability and secret-exclusion rules.
      Done 2026-09-30. Diagnose names each prepared branch with what it holds and links its address;
      a queue whose messages are all in delivery with no consumer is no longer called waiting, and
      names the transaction when one holds them. The queue page's in-flight panel and the address page
      say which messages prepared branches hold, linking to `/transactions`. The client page shows the
      roles that may send, consume and browse on each address the client uses, beside the user it
      connects as. The snapshot gains `transactions` (headers only, no properties) and `permissions`
      sections, with refusals listed once. Covered by `TransactionServiceTest`,
      `PermissionServiceTest`, rendered-page tests, `TransactionsIT` (prepared and hand-committed
      branches per supported version, counters and prepared set unchanged by reading) and
      `PartialAvailabilityIT` (both reads denied by management RBAC).

Done means unresolved prepared work and broker-reported address permissions are inspectable without
changing them, tested with prepared XA fixtures and users with different inspection permissions.

### Phase acceptance and documentation

- [x] Extend the non-destructive integration guarantee to every new read path, including snapshots
      and transaction inspection. Verify relevant message counters and transaction state remain
      unchanged by inspection in controlled fixtures.
      Done 2026-09-30: `PagesIT` starts the whole application, connects through its own form and
      fetches every route three times — every queue, message, address and client, both snapshot
      formats — with a prepared XA branch on the broker, then asserts no counter moved and the
      prepared set is unchanged. Pages rather than services, so a read a controller adds is covered
      without being listed. `TransactionsIT`, `ConnectivityIT` and `SnapshotIT` do the same for their
      own fixtures.
- [x] Add rendered-page coverage for all new panels, including partial availability and failures.
      Done 2026-09-30. Added the states still missing: transactions read from summaries only, a
      creation time with no age, a heuristic list refused beside a readable one, a refused
      transaction check on the queue page, security switched off, an unreadable security flag, and a
      permission the broker does not report. Then each Phase 13 page and panel was broken in turn
      and `PageRenderingTest` failed every time (1–17 tests per break, 12 of 12 caught).
- [x] Measure collection time, response sizes and session-memory bounds on representative large
      brokers. Record the number of management calls and any collection limits, and ensure optional
      detail reads do not turn the overview into an unbounded per-resource scan.
      Done 2026-09-30 with `ScaleMeasurementIT` (opt-in, `-Dmeasure=true`): 1,000 queues, 300
      subscriptions, 150 prepared branches. `/overview` 140ms, 1.2MB, 7 management calls; `/broker`
      17 calls; `/transactions` 5; `/diagnose` 438ms and 550 calls, bounded by its 500-address block
      check; a snapshot ~700ms, 2.7MB JSON, 987 calls, bounded by its 200-address settings and roles.
      The largest transaction reply measured 1.8MB in 128ms. Trend history at its cap retained about
      10MB per session, which corrected an earlier "a few MB". `PagesIT` asserts that the five list
      pages make the same number of calls with 30 more queues. The table is in `.claude/memory.md`;
      `ManagementCallLog` logs each request's calls at DEBUG for re-measuring.
- [x] Update the README feature list, configuration/reference documentation, architecture and
      verified-response notes as features ship. Document supported broker versions and distinguish
      management-visible facts from data requiring broker logs, application tracing or metrics plugins.
      Done 2026-09-30. The README route table gained `/connectivity`, `/transactions` and `/snapshot`,
      and the configuration table the trend and snapshot keys. Its supported-versions section says
      every Phase 13 read was probed on 2.55.0 and 2.57.0 with no difference. A new section, *What
      management shows, and what it does not*, pairs each thing the pages show with what needs logs,
      tracing, a metrics plugin or another broker instead. The architecture doc covers `PagesIT`, call
      counting and the mutation check.

Phase 13 is complete when all six areas and their supporting reliability checks meet these
criteria. No feature authorizes modifying broker configuration, resolving transactions, sending or
consuming business messages, closing clients, or introducing alerting or multi-user infrastructure.

### API references for implementation discovery

These are current documentation links, not the compatibility baseline; pin and verify the versions
selected under P0 before implementing their fields.

- [Address management API](https://artemis.apache.org/components/artemis/documentation/javadocs/javadoc-latest/org/apache/activemq/artemis/api/core/management/AddressControl.html)
- [Queue management API](https://artemis.apache.org/components/artemis/documentation/javadocs/javadoc-latest/org/apache/activemq/artemis/api/core/management/QueueControl.html)
- [Broker management API](https://artemis.apache.org/components/artemis/documentation/javadocs/javadoc-latest/org/apache/activemq/artemis/api/core/management/ActiveMQServerControl.html)
- [Paging and full policies](https://artemis.apache.org/components/artemis/documentation/latest/paging)
- [Metrics and optional runtime instrumentation](https://artemis.apache.org/components/artemis/documentation/latest/metrics.html)

## Phase 14 — Incident investigation: what changed, and where is my message?

Planned 2026-09-29 following the Phase 13 PRD review. Phase 13 expands the evidence available about
the broker; Phase 14 helps use that evidence to investigate an incident: identify what changed,
locate a message, and repeat a useful search without rebuilding it each time.

### Delivery order and scope

**P1–P3 are the core scope. P4–P5 are optional extensions and do not block phase completion.**
Snapshot comparison depends on Phase 13's incident snapshot format. The other features extend
existing search and message inspection. None moves unfinished Phase 13 work into this phase.

Preserve one broker per HTTP session, the single-user deployment, server-rendered Thymeleaf with
no frontend build step, and the read-only guarantee. Compare snapshots from the same broker over
time, not different brokers. This phase adds no monitoring, alerting, message mutation or broker
configuration changes.

### P1 — Incident snapshot comparison

- [x] **Compare two saved Phase 13 snapshots from the same broker.** Show queue-depth changes,
      settings changes, consumer and connection changes, and new or resolved Diagnose findings.
      Show the collection windows and source evidence beside each difference.
- [x] **Validate comparability.** Validate schema versions and broker identity, and explain when
      snapshots cannot be compared. Distinguish an unavailable or omitted resource from a removed
      one. Handle broker restarts, counter resets and queue recreation without presenting their
      counter differences as traffic; mark uncertain continuity explicitly.
- [x] **Keep comparison bounded and usable offline.** Limit imported file sizes and resource
      counts, treat snapshot contents as untrusted data, and require no live broker reads to compare
      them. Preserve units, availability and truncation metadata in the result.

Done means two saved snapshots can answer what observably changed, without turning missing data
into a change or a reset into throughput. Cover compatible and incompatible schemas, identity
mismatches, incomplete snapshots and counter discontinuities.

**P1 done (2026-09-30, PR #39):** `/compare`, offline. Snapshots now record `uptimeMillis` (an added field,
schema still 1) so a restart between two can be seen; older ones compare with continuity unknown.
Queue depth, counters and configuration, addresses, address settings, consumers, connections and
diagnose findings are compared, each difference beside its section and read times. Refused: other
kinds, unknown schemas, different node ids, or a missing node id with different addresses. Bounded
by `artemis.compare.max-file-bytes` and `artemis.compare.max-rows`; trends are skipped while reading.

### P2 — Unified message-ID investigation

- [ ] **One exact-ID lookup across message states.** Extend the existing lookup to report observed
      matches among waiting, scheduled and in-flight messages, with links to the queue, address
      and identified consumer where available. Keep state and collection time visible for each hit.
- [ ] **Report search coverage.** Show which queues and states were checked, skipped, denied or
      unavailable, and why. Use explicit per-request collection and result budgets, including
      preflight limits for operations whose broker replies cannot be paged.
- [ ] **Explain what a result proves.** A match is an observation during collection, not a delivery
      history. A message may move between reads. No match must never be presented as proof that it
      was consumed, deleted or never arrived. In-flight bodies remain unavailable.

Done means an exact-ID investigation provides a state-labelled result and an understandable coverage
report. Verify scheduled-message ID shapes on supported brokers before implementation, and test
matches in every supported state, partial failures, budget limits and movement between reads.

### P3 — Guided filters and saved searches

- [ ] **Build common Artemis core filters.** Provide controls for property equality, priority,
      timestamp ranges and durability, with the generated expression visible and an advanced text
      input available. Handle types, literal escaping and time zones explicitly. The broker remains
      responsible for evaluating the expression; do not implement a local selector engine.
- [ ] **Save named investigations.** Save a filter and its queue/address scope, then reopen, rename
      or delete it. Define bounded local persistence appropriate to the existing single-user tool;
      store no credentials, message bodies or search results. Make saved values visible and removable.
- [ ] **Handle changed context.** Show the current broker and scope before running a saved search.
      Missing queues or addresses and invalid filters produce useful explanations rather than an
      apparent zero-match result. Never run a saved search in the background.

Done means a user can build and repeat a common search without knowing core syntax, while advanced
filters remain available. Verify generated expressions against real brokers, including quoted
strings and timestamp boundaries, and cover saved-search persistence and missing scopes.

### P4 — Dead-letter and expiry triage (optional)

- [ ] **Summarize a bounded sample.** On an inspected dead-letter or expiry queue, group sampled
      messages by original address/queue and available diagnostic properties. Link to representative
      messages and show the sample size, collection time, limits and excluded or missing metadata.
- [ ] **Keep explanations evidence-based.** Verify origin and diagnostic metadata on supported
      brokers before promising groupings. Unknown origin remains unknown; no failure reason is
      invented from a missing property. Sample proportions are not whole-queue totals.

Done means a user can identify common origins or reported diagnostic patterns in the inspected
sample. Cover heterogeneous and absent metadata, bounded collection and non-destructive reads.
There are no retry, move or delete controls.

### P5 — Message comparison (optional)

- [ ] **Compare two browsable messages.** Show header and property differences, and differences in
      supported text or JSON bodies. Link back to each source and identify when it was read.
- [ ] **Represent limitations.** Bound body and comparison sizes, label truncation and unsupported
      body types, and distinguish absent fields from empty values. A message that disappears between
      selection and reading is unavailable, not an empty message. Never fetch in-flight bodies.

Done means a user can inspect observable differences between two messages without changing either.
Cover structured and plain text, missing fields, truncation, unavailable messages and safe rendering
of untrusted content.

### Phase acceptance and documentation

- [ ] Verify every new broker response shape and read operation against the supported-version
      matrix before relying on it, and extend the management allowlist only for verified reads.
- [ ] Extend the non-destructive integration guarantee to new collection paths. Cover explicit
      availability, collection budgets and rendered empty, populated and partial-failure states.
- [ ] Measure collection cost and memory bounds for the core workflows at representative scale.
      Do not stream whole queues through the application for lookup, grouping or comparison.
- [ ] Document search coverage, comparison limits, saved-search storage and deletion, and the
      distinction between observed evidence and an inferred explanation. Update user documentation
      as features ship.

Phase 14 is complete when P1–P3 and their supporting acceptance checks are complete. P4–P5 remain
optional unless explicitly promoted into the committed scope.

## Phase 15 — Interactive regression lab and regression procedure

Specified 2026-09-29. **Specification and procedure first; implementation has not started.**
Create separate test services with a web interface that prepares repeatable conditions on a test
broker, so every implemented Artemis Browser feature can be demonstrated and regression tested.
The lab may produce, consume, acknowledge and configure its owned disposable brokers; Artemis
Browser itself retains its read-only boundary and production artifact.

The [Phase 15 specification](Phase%2015%20plan.md) defines the service boundaries, isolation,
web workflow, fixture catalog, delivery increments and acceptance criteria. The
[draft regression procedure](Phase%2015%20regression%20procedure.md) defines stable case IDs,
setup conditions, Browser actions, expected results, cleanup and evidence. Update those documents
as the test servers and their controls are refined.

### Scope and delivery

- [ ] **P0 — Independent lab and disposable brokers.** Provision pinned supported broker versions;
      verify identity, ownership and bounded lifecycle operations; provide a scenario catalog and
      run records through a separate web application.
- [ ] **P1 — Message and client services.** Deterministic producers and controlled consumers create
      waiting, scheduled, in-flight, redelivery, dead-letter and expiry conditions, mixed bodies,
      subscriptions, routing, rates and client identities.
- [ ] **P2 — Behavior and failure services.** Create queue-behavior and pressure scenarios, restricted
      reads, connection failures and bounded scale; cover security/deployment through an explicit
      external harness where broker traffic alone cannot test the feature.
- [ ] **P3 — Executed regression coverage.** Run all current-feature cases against the supported
      matrix, retain results/evidence, and refine the first-draft procedure into verified instructions.
      Extend fixtures and cases as the remaining Phase 13 and Phase 14 features land.

The initial baseline is Phases 1–12 and Phase 13 P0–P2. Unimplemented Phase 13/14 features are
reserved future cases, not claimed current coverage. A successfully created broker condition is
not a passed Browser test: both setup evidence and Browser observations must be recorded. Existing
unit and integration tests remain required; the interactive procedure supplements them.

Done means each implemented feature has a repeatable scenario or explicitly documented harness
procedure, applicable cases have results on the supported broker matrix, and reset/cleanup and
the Browser's non-destructive guarantee are verified. Unresolved defects and blocked coverage must
be visible, with any accepted exceptions recorded.

## Open questions

1. ~~**Does Phase 5 have a theme, or is it a cleanup phase?**~~ **Settled 2026-09-19: Phase 5 is
   everything listed above — P0, P1 and P2.** Consolidation and the four user-facing gaps ship
   together rather than splitting across two phases.
2. ~~**Is a read-only tool that can be *pointed* at production also allowed to be run *in*
   production?**~~ **Settled 2026-09-19: yes, with authentication.** The tool gains a login of its
   own so it can sit on a jump host. This is the conversation the constraint was holding open, and
   it has now been had deliberately rather than by erosion — see *Reachability* under Constraints,
   which replaces the old loopback-only entry.
3. ~~**How large is the largest queue this must stay usable on?**~~ **Measured 2026-09-19, and the
   answer was not about size.** At ~197,000 messages across ~52 queues, nothing degraded: the
   overview, a queue's first page, its **two-thousandth** page, the diagnose page and a cross-queue
   search all landed in 280–400ms, and an export of 5,000 messages took 1.7s. Deep paging is flat,
   which is what the server-side paging design was for, and search costs about 3ms per queue.
   Producing the test messages took far longer than reading them ever did.

   What broke instead was correctness, at about **200** messages rather than a million. Artemis
   examines only the first `management-browse-page-size` messages when counting *with a filter*, so
   cross-queue search — which used the count to decide which queues were worth browsing — reported
   "0 matches" for a message at position 99,999 that the queue view could find perfectly well. That
   is the exact failure the search feature exists to prevent, and it was invisible at every size
   this project had tested before. Search now browses every queue and reports a floor rather than a
   total; the fix is in the same phase as the measurement that found it.
4. ~~**Is there an appetite for a non-destructive "why is this stuck" view**~~ **Settled
   2026-09-19: yes, as its own page.** Delivery counts, redelivery, DLQ origin and consumer state
   in one place, for the person holding the pager.

5. ~~**Does this become shared infrastructure, or stay a single-user tool?**~~ **Settled
   2026-09-20: single-user.** It is run by one person, so the self-signed certificate, the single
   replica and the one shared login are all correct rather than compromises. This is the question
   both carried Phase 8 items were downstream of, which is why closing it closed them.

## Not in scope

Consuming, acknowledging, moving, retrying, deleting or expiring messages. Sending messages.
Creating or deleting queues and addresses. Editing broker configuration. Multi-broker views.
Alerting. **Multi-user** authentication — the tool has a login as of Phase 7, but one shared
account, which is a deliberate fit for a single user rather than an unfinished feature. Per-user
accounts, roles and an audit trail are out of scope until someone other than the author runs it.

These mutation exclusions apply to Artemis Browser; Phase 15's separate regression lab is explicitly
allowed to create and change conditions on its owned disposable test brokers.
