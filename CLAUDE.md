# CLAUDE.md

Guidance for Claude Code (claude.ai/code) working in this repository.

## What this project is

**artemis-browser** — a read-only web browser for ActiveMQ Artemis queues. Spring Boot 4.1.1 on
Maven, Java 21, Thymeleaf server-rendered (no npm, no build step). Phases 1–4 shipped: connect,
queue overview, message browsing and detail, cross-queue search, CSV/JSON export, broker health and
producers, address view. Phase 6 is in progress on `phase06`. 141 unit tests, 12 integration.

**Read-only is the product, not a detail.** Nothing consumes, acknowledges, moves, expires or
deletes a message, and anything that could is out of scope until deliberately put in scope. Read
paths are verified non-destructive against a real broker, not assumed.

## The six things that cause silent bugs here

Silent, meaning a wrong answer rather than an error. Each is one line plus where the reasoning
lives — read [docs/architecture.md](docs/architecture.md) before changing any of them.

1. **Two read paths, on purpose.** The paged list uses management `browse` so the broker does the
   paging; the single-message detail uses a JMS `QueueBrowser` because management only exposes
   `text` bodies. Export uses both — management for the listing, one JMS pass for real bodies.
2. **Filters are Artemis *core* syntax, not JMS selectors.** `AMQPriority`, `AMQTimestamp`,
   `AMQDurable`, or a property by its bare name. A JMS-style `JMSPriority = 4` is not rejected — it
   matches nothing. Any UI exposing a filter must name the dialect.
3. **Browse by FQQN when address ≠ queue name.** Artemis resolves a bare name against addresses
   first, so a multicast subscription must be `address::queue` — `QueueStats.browseName()`. Symptom
   if you get it wrong: "that queue is always empty".
4. **Management JSON is not uniform.** `listQueues` quotes every value including counters;
   `listAddresses` nests a JSON array inside a JSON string; producer counters are bare while
   timestamps are quoted; broker attributes mix String, Long and Double. Verified shapes are in
   `.claude/memory.md`; the parsing is tested against them.
5. **Exports are untrusted content.** Every CSV field is quoted and a leading `=`, `+`, `-` or `@`
   gets an apostrophe, so a body cannot become a spreadsheet formula. Don't "simplify" it.
6. **`browse` does not return scheduled messages.** They are counted by the queue and read through
   `listScheduledMessagesAsJSON` instead, so a queue can report messages and browse as empty.

## Security posture

Loopback only: `server.address=127.0.0.1` **and** `LoopbackHostFilter` (Host-header check). Both are
needed — binding loopback does not stop DNS rebinding, and this app has no login of its own while
holding an authenticated broker connection. The broker password is never retained.

If it is ever made network-reachable, that filter is not the thing to relax; real authentication is
what would have to be built. Details and the traps already fixed:
[docs/architecture.md](docs/architecture.md).

## Build and test

No Maven wrapper in any sibling project, so use the `mvn` on PATH. Shell is PowerShell — `;` chains
commands, not `&&`.

```bash
mvn clean install                     # full build
mvn test                              # all tests
mvn test -Dtest=SomeTest              # one test class
mvn test -Dtest=SomeTest#someMethod   # one test method
mvn test -Dtest=OneTest,TwoTest       # several (comma, not +)
mvn spring-boot:run                   # run the app on http://localhost:8080
mvn verify -Pintegration              # + integration tests: starts a real broker in Docker
```

- `*IT` tests run only under `-Pintegration`, so `mvn clean install` needs nothing but Maven. They
  cover what a mock cannot: the management round trip, and that reading consumes nothing.
- Dependency resolution goes through a local Nexus (`mirrorOf *`), configured in Maven's own
  `conf/settings.xml` rather than `~/.m2`. If Nexus is down, nothing resolves.
- `java-formatter-maven-plugin` reformats sources on every build, so expect `git status` to show
  modified files after a build. Write code normally rather than hand-matching its style.
- Spring Boot 4.1.1 ships **Jackson 3** — imports are `tools.jackson.databind`, not
  `com.fasterxml.jackson`.
- To test against a real broker, see the container recipe in `.claude/memory.md` — note it maps
  **62616**, because 61616 is already taken on this machine.

## Not JavaFX

The three sibling projects in `P:\ClaudeCowork\Projects` (`data-blaster`,
`javafx-ribbon-view-switcher`, `track-generator-system`) are JavaFX desktop apps. Don't carry
OpenJFX, FXML, TestFX/Monocle or the ribbon CSS tokens over.

## Where things go

- `docs/` — [PRD.md](docs/PRD.md) (what the product is, what's next) and
  [architecture.md](docs/architecture.md) (design decisions and their reasoning)
- `README.md` — how to build, run and configure it, plus the file-by-file layout
- `.claude/memory.md` — what has been discovered: verified broker response shapes, environment
  quirks, traps already hit
- `scripts/` — build and packaging helpers
- `ClaudeOutput/artemis-browser/` — standalone deliverables (analyses, reports), outside the repo
- `target/` — build output, never commit

Roughly: this file is what is true by design and needed every session; `docs/` is the reasoning in
full; memory is what was learned the hard way.

## Memory

Durable knowledge lives in `.claude/memory.md`, not in this file.

- When you discover something valuable for future sessions — architectural decisions, bug fixes,
  gotchas, environment quirks — **immediately** append it. Don't wait to be asked or for session end.
- Keep entries to a line or two: date, what, why.
- **Read `.claude/memory.md` at the start of every session.** It is not auto-loaded the way this
  file is.
- Consolidate occasionally: merge duplicates, drop entries the code or `docs/` now states better.
- Don't record what git history or the code already says.

`.claude/memory.md` is committed on purpose — that is what shares it across Claude Code, Desktop and
Web. Claude's own per-user memory is a separate store and stays user-level: personal preferences and
cross-project context there, project facts here.

If it outgrows one file, split into `.claude/memory/` with `memory.md` as the index plus
`general.md`, `domain/{topic}.md` and `tools/{tool}.md` — load the index at session start and pull
individual files only when relevant.

## Git

Read, branch, and edit freely. Ask before `git push`, force-push, merge, rebasing shared history, or
deleting a branch. `main` is the branch to target for PRs; current work is on `phase05`, which
merges up through `Milestone003`.
