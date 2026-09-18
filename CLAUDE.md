# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this project is

**artemis-browser** — a read-only web browser for ActiveMQ Artemis queues. Spring Boot 4.1.1 on
Maven, Java 21, Thymeleaf server-rendered (no npm, no build step).

Phase 1, which is built: connect to a broker with host/port/username/password, list every queue on
it, and inspect a selected queue — counters plus the messages themselves — **without consuming
anything**.

**Read-only is the product, not a detail.** Browsing uses a JMS `QueueBrowser`, the one read path
the spec guarantees is non-destructive. A `MessageConsumer` that simply never acknowledges is not an
acceptable substitute: it moves messages into the delivering state. Anything that could consume,
acknowledge, move, or delete is out of scope until it is deliberately put in scope.

**Not JavaFX.** The three sibling projects in `P:\ClaudeCowork\Projects` (`data-blaster`,
`javafx-ribbon-view-switcher`, `track-generator-system`) are JavaFX desktop apps. Don't carry
OpenJFX, FXML, TestFX/Monocle or the ribbon CSS tokens over.

## How it fits together

- `broker/` — everything that talks to Artemis. `BrokerSession` is `@SessionScope`: one live
  connection per HTTP session, holding host/port/username but **never the password**.
  `ManagementChannel` is the request/reply plumbing for the `activemq.management` address, which is
  how queues get listed at all (JMS itself has no "list queues"). `QueueDirectory` reads the queue
  list and counters; `QueueBrowseService` does the non-destructive read.
- `web/` — Thymeleaf controllers plus `LoopbackHostFilter`.

## Security posture

Loopback only: `server.address=127.0.0.1` **and** `LoopbackHostFilter`, which rejects any request
whose `Host` header is not a loopback literal. Both are needed — binding loopback does not stop DNS
rebinding, and this app has no login of its own while holding an authenticated broker connection.

If it is ever made network-reachable, that filter is not the thing to relax; real authentication is
what would have to be built. See `.claude/memory.md` for the specific traps already fixed here.

## Build and test

No Maven wrapper in any sibling project, so use the `mvn` on PATH. Shell is PowerShell — `;` chains
commands, not `&&`.

```bash
mvn clean install                     # full build
mvn test                              # all tests
mvn test -Dtest=SomeTest              # one test class
mvn test -Dtest=SomeTest#someMethod   # one test method
mvn spring-boot:run                   # run the app on http://localhost:8080
```

Dependency resolution goes through a local Nexus (`mirrorOf *`), configured in Maven's own
`conf/settings.xml` rather than `~/.m2`. If Nexus is down, nothing resolves.

To test against a real broker, see the container recipe in `.claude/memory.md` — note it maps
**62616**, because 61616 is already taken on this machine.

## Where things go

- `docs/` — requirements and design notes (siblings use `docs/PRD.md`, `docs/architecture.md`)
- `scripts/` — build and packaging helpers
- `ClaudeOutput/artemis-browser/` — standalone deliverables (analyses, reports), outside the repo
- `target/` — build output, never commit

## Memory

Durable knowledge lives in `.claude/memory.md`, not in this file. This file is for what is true by
design; memory is for what you discover.

- When you discover something valuable for future sessions — architectural decisions, bug fixes,
  gotchas, environment quirks — **immediately** append it to `.claude/memory.md`. Don't wait to be
  asked. Don't wait for session end.
- Keep entries to a line or two: date, what, why.
- **Read `.claude/memory.md` at the start of every session.** It is not auto-loaded the way this
  file is.
- Consolidate occasionally: merge duplicates, drop entries the code now states better.
- Don't record what git history or the code already says.

`.claude/memory.md` is committed on purpose — that is what shares it across Claude Code, Desktop and
Web. Claude's own per-user memory is a separate store and stays user-level: personal preferences and
cross-project context there, project facts here.

If it outgrows one file, split into `.claude/memory/` with `memory.md` as the index plus
`general.md`, `domain/{topic}.md` and `tools/{tool}.md` — load the index at session start and pull
individual files only when relevant.

## Git

Read, branch, and edit freely. Ask before `git push`, force-push, merge, rebasing shared history, or
deleting a branch. `main` is the branch to target for PRs; current work is on `phase01`.
