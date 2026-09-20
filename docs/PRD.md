# artemis-browser — Product Requirements

Status: Phases 1–4 shipped and on `main`. Phase 5 in progress on `phase05`, scoped to all of
P0, P1 and P2 below.
Last updated: 2026-09-19.

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
- **Loopback only.** `server.address=127.0.0.1` *and* `LoopbackHostFilter`. The app has no login of
  its own while holding an authenticated broker connection, so it must not be reachable. If it is
  ever made network-reachable, the filter is not the thing to relax — real authentication is what
  would have to be built first.
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

- [ ] **Test `ManagementChannel` against a real broker.**
      Every other service in `broker/` now has tests; the request/reply plumbing every one of them
      depends on has none, because it needs a live JMS session rather than a fixture — the timeout
      path, the rejected-`manage`-permission path and the reply-correlation are all untested. This
      is a Testcontainers job, not a mocking one.
      *Done when:* a tagged integration test starts a broker, exercises a successful call, a timeout
      and a permission rejection, and is excluded from the default `mvn test` run.

- [ ] **Keep the live verification repeatable.**
      The broker recipe lives in `.claude/memory.md` and is run by hand. The non-destructive
      guarantee — the product's central claim — is checked by remembering to check it.
      *Done when:* one command seeds a broker with text, bytes and multicast messages and asserts
      counters are unchanged after browsing, searching and exporting.

### P2 — product gaps a user will actually hit

- [ ] **Export a search result.**
      `/export` takes one queue name. A cross-queue search — the feature for "I have the ID but not
      the queue" — cannot be exported at all, which is the moment someone most wants the evidence in
      a file.
      *Done when:* a search result exports to one CSV/JSON carrying the queue name per row.

- [ ] **Sort and filter the overview.**
      The all-queues table is static. On a broker with 200 queues, "which ones are stalled" and
      "which is biggest" are the two questions it exists to answer, and neither is answerable
      without reading every row.
      *Done when:* the overview can be sorted by any counter and narrowed by name, without breaking
      auto-refresh.

- [ ] **Say which filter dialect the box wants, where the box is.**
      Filters are Artemis *core* syntax. A JMS-style `JMSPriority = 4` is not rejected — it silently
      matches nothing, which reads as "the message isn't there". The error path names the dialect;
      the success path, where the damage is done, does not.
      *Done when:* every filter input carries the dialect and a couple of working examples inline.

- [ ] **Page a search result.**
      Search stops at `artemis.search-max-per-queue` (50) per queue and flags the result partial.
      There is no way to see message 51.
      *Done when:* a partial result can be continued, or links to the queue view with the filter
      already applied.

### P3 — worth doing, nothing breaks without it

- [ ] **Copy or download a single message** from the detail view, body and properties together.
- [ ] **Show scheduled messages' delivery times** — the counter is on the overview, the times are not
      anywhere.
- [ ] **Surface `PropertiesText` in the list**, so filtering by a property does not require opening
      each message to see what the properties are.
- [ ] **Make a dropped connection say so.** A broker restart currently redirects to the connect form
      with no explanation, which looks like a session timeout.

## Open questions

1. ~~**Does Phase 5 have a theme, or is it a cleanup phase?**~~ **Settled 2026-09-19: Phase 5 is
   everything listed above — P0, P1 and P2.** Consolidation and the four user-facing gaps ship
   together rather than splitting across two phases.
2. **Is a read-only tool that can be *pointed* at production also allowed to be run *in*
   production?** Today loopback-only answers this by making it impossible. If anyone wants it on a
   jump host, that is the authentication conversation, and it should be had deliberately.
3. **How large is the largest queue this must stay usable on?** The paging design assumes tens of
   thousands. Millions would change the search design, not just its limits.
4. **Is there an appetite for a non-destructive "why is this stuck" view** — delivery counts,
   redelivery, DLQ origin — as a first-class page rather than scattered fields?

## Not in scope

Consuming, acknowledging, moving, retrying, deleting or expiring messages. Sending messages.
Creating or deleting queues and addresses. Editing broker configuration. Multi-broker views.
Alerting. Authentication of the tool's own users — while it is loopback-only, that is the
constraint, not a gap; if that changes, it becomes a prerequisite rather than a feature.
