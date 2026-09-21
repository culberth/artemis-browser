# artemis-browser — Product Requirements

Status: Phases 1–8 shipped and on `main`. Phase 8 put the tool in the local Kubernetes cluster,
which is also what exposed a broker page that had been returning 500 since Phase 5 — no test
rendered a template, so the controller tests passed while the view blew up. Phase 9 is open on
`phase09`.
Last updated: 2026-09-20.

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

## What's next

Ordered by what it costs to leave undone, not by size. Each task says what "done" looks like so it
can be checked rather than argued about.

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

## Shipped since

- **Phase 8** — a container image and a Helm chart, so it runs in the local Kubernetes cluster and
  reaches a broker by cluster DNS. The pod terminates TLS itself because that is what
  `ReachabilityGuard` requires of anything not bound to loopback; the certificate is self-signed and
  the browser→ingress hop is plaintext, which makes this a **local-cluster arrangement**. Making it
  more than that needs a real certificate on the ingress, browsing over HTTPS, and an external
  session store before more than one replica is possible.

## What's next — Phase 9

- [ ] **Render every page in a test.** `PageRenderingTest` covers `broker` and `addresses`; the
      other seven templates — `connect`, `diagnose`, `login`, `message`, `overview`, `queues`,
      `search` — have no test that renders them, and most use the same shared refresh fragment
      whose parameter list broke `/broker` for three phases without a single test going red. Done
      looks like: a case per template asserting on rendered HTML, each one checked against a
      deliberately broken template first to confirm it fails.

Carried, not scheduled — both are scope changes rather than gaps, and both come from the Phase 8
entry above:

- **An external session store**, which a second replica needs. "One broker per HTTP session" is a
  stated constraint, so this changes the model rather than filling a hole in it.
- **A real certificate on the ingress and browsing over HTTPS**, which only matters if this stops
  being a local-cluster arrangement.

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

## Not in scope

Consuming, acknowledging, moving, retrying, deleting or expiring messages. Sending messages.
Creating or deleting queues and addresses. Editing broker configuration. Multi-broker views.
Alerting. Authentication of the tool's own users — while it is loopback-only, that is the
constraint, not a gap; if that changes, it becomes a prerequisite rather than a feature.
