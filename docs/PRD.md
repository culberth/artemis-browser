# artemis-browser — Product Requirements

Status: **Phase 11 in progress — reopened 2026-09-27 for in-flight messages.** Phases 1–9 shipped
and the project was declared feature-complete; it was reopened on 2026-09-27 for one theme —
subscription inspection — and Phase 10 merged the same day (PR #19). It was reopened again the same
day, on request, for a second theme: messages delivered to a consumer and not yet acknowledged. See
*Phase 10* and *Phase 11* below. The tool is run by one person, which is what settles the open questions about replicas,
certificates and multi-user login.
Last updated: 2026-09-27.

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

- [ ] **Allowlist `listDeliveringMessagesAsJSON`.**
- [ ] **Measure it at scale**: thousands of small messages in one consumer's buffer — reply size and
      time — and set the cap from the measurement, not a guess.
- [ ] **Check the consumer text from a non-CORE client.** If it does not parse, show it as it came
      rather than guess a client.
- [ ] **`ReadOnlyGuaranteeIT` reads the delivering list** with messages in flight and asserts no
      counter moved.

### P1 — an "In flight" panel on the queue page

- [ ] **Grouped by consumer**: client id, remote address and in-transit count where the consumer can
      be matched; each message's ID, send time and properties. No body, and the panel says why.
- [ ] **The empty-queue note** that says messages are in flight links to the panel.
- [ ] **Ages are from send time.** There is no delivery time in the reply, so the page cannot say how
      long a consumer has held a message, and does not pretend to.

### P2 — search sees in-flight messages, by message ID

- [ ] **Exact message-ID lookup against the delivering lists.** Not general filters: evaluating
      `region = 'eu'` against in-flight messages would mean reimplementing Artemis's filter language,
      which this project declined in Phase 10 for being a new source of silently wrong answers.
- [ ] **The address page's "which subscriptions hold a message"** turns "N in flight could not be
      searched" into "in flight to consumer X" — a real yes — when the lookup is by message ID.

### P3 — diagnose

- [ ] **A consumer hoarding the queue**: one consumer holds everything in flight while others on the
      same queue hold nothing — usually a consumer window set too large.
- [ ] **Messages in flight a long time**: the oldest in-flight message was sent long ago, suggesting
      a consumer stuck mid-processing. Measured from send time, and the finding says so.

### Considered and left out

- **Bodies of in-flight messages.** The broker does not return them.
- **Delivery times.** Not in the reply.
- **Anything that releases, redelivers or re-routes an in-flight message.** Not read-only.

## Queued after Phase 11

Chosen 2026-09-27 from a list of candidates, to be taken up once Phase 11 merges. Not yet phases:
each becomes one, or joins one, when it is started. Every item begins the way Phases 10 and 11 did —
the broker is asked first and its answers recorded, before anything is parsed.

- [ ] **Measure the new pages at scale.** Phase 7 measured every page at 100,000 messages; nothing
      has measured `/address`, which makes one `firstMessageAge` read per non-empty subscription and
      one read per divert field, or Phase 11's in-flight panel, whose reply has no paging. Seed an
      address with a few hundred subscriptions and a consumer buffering thousands of messages, time
      each page end to end, and add the rows to *Measured limits* in `.claude/memory.md`. Done means
      numbers, not a feeling — and a fix, such as capping the per-subscription reads, only if a
      number calls for one.
- [ ] **A page per client.** Every view today starts from a queue or an address; this one starts
      from "what is `billing-svc` doing?" — its connections, sessions, what it consumes and produces,
      and what it holds in flight. Pairs with Phase 11. First establish what identifies a client
      across the listings: whether `listConnectionsAsJSON` carries the client id, and what
      `listSessionsAsJSON(connectionID)` and `listConsumersAsJSON(connectionID)` return.
- [ ] **Show expired and killed counts.** `listQueues` already returns `messagesExpired` and
      `messagesKilled` for every queue, quoted like the other counters; the tool reads neither. They
      answer "where did my messages go" when the dead-letter queue is empty. First confirm on a
      broker what each counts — killed is expected to mean "exceeded max delivery attempts", whether
      then dead-lettered or dropped — then show them on the queue page, the overview and the address
      page, with a diagnose finding for messages killed or expired with no address to go to.

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
