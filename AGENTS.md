# AGENTS.md — rules for all AI agents working on pose-tracker-android

Real-time Android app: on-device frames → YOLO26n-pose → a stable, low-latency dot on each
person's head, with tracking, smoothing and tap-to-lock. Full design: `docs/ARCHITECTURE.md`
(read only the sections your task names). Team, models and milestone workflow: `docs/AI_TEAM.md`.
`CLAUDE.md` = this file + a short lead note for the main session.

## Roles (docs/AI_TEAM.md)
The main session is the **lead**: it plans, delegates, reviews and opens PRs, and writes no
production code or tests. Code is written by the **implementer** subagent; milestone review by
**android-reviewer**; hard bugs by **debugger**; hard questions by **consultant**; wide searches by
**Explore**. Escalation: implementer (twice) → debugger → consultant → owner.

## Workflow — one problem per branch and PR
1. Read the ARCHITECTURE sections named in the task, not the whole file. Verify with `scripts/verify.sh`.
2. Branch from `main`; **never push to `main`**. Each PR is one concern and ends with "How to test on the phone".
3. Every fix ships with a test that fails without it: JUnit 5 (Jupiter) in `core`, JUnit 4 in Android modules.
4. CI (GitHub Actions) is the source of truth; check it only with `scripts/ci-status.sh`.

## Protected — change only if the task explicitly says so
`gradle/libs.versions.toml`, the Gradle wrapper, `.github/**`, `core/**/geometry/**`,
test thresholds in `core/src/test/**/Metrics*`, `model/**`, `signing/**`, `.claude/skills/**`.

## Hard rules
1. **Fail closed.** A reader, template or guard that is not sure declines and lets a later step answer.
   Never guess text, positions or numbers.
2. **Never block the main thread** (no full-frame copies, inference, file or network work on it), and a
   reader failure — `Error`s such as `OutOfMemoryError` included — must never crash the service.
3. Never delete, skip or weaken tests to make CI pass. **CI fails if the `@Test` count drops.**
4. Coordinate suffixes `Img/View/Mdl` and the thread table (ARCHITECTURE §5) are mandatory.
5. No magic numbers outside `TrackerConfig`. No deprecated APIs (use `ResolutionSelector`, etc.).
6. **LiteRT:** `CompiledModel` only; never on the main thread; never allocate `TensorBuffer`s per
   frame; never `Accelerator.GPU` without the `litert-gpu` dependency; never mix `org.tensorflow:*`
   with `com.google.ai.edge.litert:*`. The bundled runtime is deliberate (overrides the skill's
   Play-services default).
7. No new dependencies without saying why in the PR. No Dependabot/Renovate; versions are pinned in
   the catalog and bumped deliberately.
8. No per-frame allocations in the analyzer or the draw phase.

## Vendored Ultralytics code (`com/ultralytics/yolo`)
Change it only when the task needs it, keep the upstream file headers, and log every change in
`third_party/ULTRALYTICS.md` (file · what · why). Upstream's own AGENTS.md/CLAUDE.md rules are not
imported (its "tests are out of scope" rule does not apply here).

## Context hygiene (docs/AI_TEAM.md §3)
Build and test only via `scripts/verify.sh`; CI only via `scripts/ci-status.sh`; never paste full
logs; read files by path and line range; wide searches via `Explore`; read ARCHITECTURE by section.

## This file
`AGENTS.md` and `CLAUDE.md` are never edited mid-session (it invalidates the prompt cache); propose
rule changes in the handoff. Keep `AGENTS.md` under ~150 lines.
