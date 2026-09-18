# Memory — artemis-browser

Append-only working notes. One or two lines each: date, what, why.
Read this at the start of every session. Consolidate when it gets repetitive.

---

- **2026-09-18 — UI is a web interface, not JavaFX.** Decided explicitly against the pattern of all
  three sibling projects. Why: they're JavaFX desktop apps, so their scene-graph, FXML, TestFX and
  ribbon-CSS code is a trap to copy from here.

- **2026-09-18 — Spring Boot + Maven is settled; the domain is not.** "What artemis-browser browses"
  is deliberately open (records viewer over an Artemis source? file/asset browser?). Why: it drives
  the dependency set and the security posture, so guessing is expensive to unwind.

- **2026-09-18 — Frontend approach undecided.** Thymeleaf vs static bundle vs separate SPA all open.
  Why: adding npm/Vite/a `frontend/` module sets the dev loop for everyone; needs a decision, not a
  drive-by.

- **2026-09-18 — Open security question: is the HTTP layer loopback-only or genuinely reachable?**
  This is *the* security decision here. `javafx-ribbon-view-switcher` has the local-only pattern
  worth reading first — `web/LoopbackHostFilter.java` plus `server.address=127.0.0.1`: it binds
  loopback *and* rejects non-loopback `Host` headers, because binding loopback alone doesn't stop
  DNS rebinding. Why it matters: if this app is reachable beyond the local machine, that filter is
  not a substitute for authentication.

- **2026-09-18 — No `.gitignore` yet.** Add one before the first `mvn` run or `target/` lands in the
  working tree. If it ignores `.claude/`, carve out `.claude/memory.md` or this file stops being
  shared.

- **2026-09-18 — Sibling convention differs on memory location.** All three siblings keep `memory.md`
  at the repo root; this project uses `.claude/memory.md` per the Substack approach Bo is trying out.
  Why noted: don't "fix" the divergence by moving it back.
