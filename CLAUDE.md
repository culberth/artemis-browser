# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this project is

**artemis-browser** — a Spring Boot web application built with Maven.

**Not JavaFX.** The three sibling projects in `P:\ClaudeCowork\Projects` (`data-blaster`,
`javafx-ribbon-view-switcher`, `track-generator-system`) are JavaFX desktop apps and are otherwise
good models — read `data-blaster/pom.xml` before writing this one's. But don't carry OpenJFX, FXML,
TestFX/Monocle or the ribbon CSS tokens over; this app serves a web UI.

Still undecided: which frontend (Thymeleaf, static bundle, separate SPA) and what the app actually
browses. Ask rather than assume — and ask before adding a frontend build step (npm, Vite,
`frontend/`), which sets the whole dev loop.

## Repo state

`LICENSE` and `README.md` only — no `pom.xml`, no sources, no tests, no `.gitignore`. Branches
`main`, `phase01` and `Milestone001` are identical. Re-run `/init` once real code lands.

House baseline from the siblings, to confirm rather than copy blindly: Spring Boot
`4.1.1` parent, Java 21, groupId `com.culberth.tools`, version `1.0.0-SNAPSHOT`, JaCoCo,
`java-formatter-maven-plugin`.

## Build and test

No Maven wrapper in any sibling project, so use the `mvn` on PATH. Shell is PowerShell — `;` chains
commands, not `&&`.

```bash
mvn clean install                     # full build
mvn test                              # all tests
mvn test -Dtest=SomeTest              # one test class
mvn test -Dtest=SomeTest#someMethod   # one test method
mvn spring-boot:run                   # run the app
```

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
