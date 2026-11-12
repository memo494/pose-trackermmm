# AI team — models, agents, workflow (v1.1)

Single source of truth for how AI agents build this repo. The M0a session creates the files in §4–§6
**exactly as written here**. Changes to this file need the owner's approval.

---

## 1. Roles

| Role | Model | Effort | Does | Never does |
|---|---|---|---|---|
| **lead** = the main session | Opus 5.5 (`opus`) | `xhigh` saved in settings; `max` when the owner types `/effort max` | Reads progress + named ARCHITECTURE sections, writes the milestone plan, delegates every task, checks results, runs review, opens the PR, writes the handoff | Write production code or tests |
| **implementer** (subagent) | Sonnet 5.5 (`sonnet`) | `high` | Implements exactly one planned task: tests first, code, `scripts/verify.sh`, small commits | Change scope, touch protected files, weaken tests, spawn agents |
| **android-reviewer** (subagent) | Opus 5.5 | `high` | Fresh-context, read-only review of the full milestone diff | Edit anything |
| **debugger** (subagent) | Opus 5.5 | `high` | Root cause + regression test + minimal fix, only on escalation | Run for routine tasks |
| **consultant** (subagent) | Fable 5.2 (`fable`) | `xhigh` | Answers ONE focused hard question: knowledge the team lacks, conflicting or missing docs, high-risk design (geometry, tracking, latency), failures the debugger could not fix. Read-only, cites sources | Edit anything; run for routine work |
| **Explore** (overrides built-in) | Haiku 5.5 (`haiku`) | `low` | Cheap read-only search across the repo | Edit anything |
| `scripts/verify.sh`, `scripts/ci-status.sh` | — (plain scripts) | — | Build/test/CI results as short summaries | — |

Why this split:
- Planning, supervision and review decide quality → strongest model at the highest effort.
- Implementation is the bulk of the tokens → Sonnet 5.5 at `high` (5.5 models default to `medium`, so every
  agent sets its level explicitly).
- The hardest questions go to the strongest model (Fable 5.2), only when needed and with a budget (§3),
  because it costs more than Opus.
- Build logs and CI results are read by deterministic scripts, not by a model: cheaper and never "misread".
- Every subagent starts with a fresh context and only its summary returns to the lead, so the lead's
  context stays small across a whole milestone.

Why the lead is the main session and not an agent file: running the main thread as an agent replaces
Claude Code's default system prompt (git, PR and tool-use guidance) with the agent's prompt. The lead pushes
branches and opens PRs, so it keeps the default prompt and gets its role from `CLAUDE.md` (§5) instead.
Settings cannot store `max` effort, so `xhigh` is saved and `max` is a per-session choice.

---

## 2. Milestone workflow (the lead follows this every time)

1. **Orient:** read `AGENTS.md`, the latest `docs/progress/*.md`, `docs/REPO_MAP.md`, and only the
   ARCHITECTURE sections named in the owner's prompt.
2. **Plan:** write `docs/plans/<M>.md` — tasks T1..Tn (max 8), each with: goal · files · tests to write first ·
   acceptance criteria · agent · size S/M (anything larger is split). For high-risk designs (M2 geometry,
   M3 tracking, M4 prediction/latency), ask `consultant` to check the risky decisions first and record its answer
   in the plan. Show the plan to the owner; **wait for "approved"**.
3. **Delegate:** one task at a time to `implementer` (sequential on one branch; parallel worktrees branch from
   the default branch, not from HEAD, so they would miss earlier tasks). Pass the task text verbatim + paths +
   constraints. Never use `general-purpose` or forks for code changes.
4. **Check each report:** `git diff --stat`, the verify summary, `@Test` count not lower, no protected file touched.
   Not acceptable → send back once with precise feedback. **Fails twice → stop:** `debugger` for root cause,
   or split the task and update the plan. An implementer that reports BLOCKED with a knowledge question →
   the lead answers it from docs/Context7, or asks `consultant`.
5. **Review:** all tasks green → `android-reviewer` on the whole milestone diff. Critical = must fix (via
   implementer). Warning = fix or justify in the PR.
6. **Ship:** push, open PR, `scripts/ci-status.sh` until green. Red → implementer with the CI summary;
   red twice → debugger.
7. **Handoff:** `docs/progress/<M>.md` (≤ 1 page: done · decisions · open issues · next step), update
   `docs/REPO_MAP.md` if files moved, PR description ending with **"How to test on the phone"**. Stop; the owner merges.

**Escalation ladder for failures:** implementer (twice) → debugger → consultant → owner.

Escalate to the owner (don't guess) when: a decision is irreversible, scope must change, a protected file must
change, or the same failure survives the consultant's advice.

---

## 3. Context rules (cost and quality)

- Builds and tests only through `scripts/verify.sh`; CI only through `scripts/ci-status.sh`. Never paste full logs.
- Open files by path and line range; use `Explore` for wide searches; read ARCHITECTURE by section.
- Keep `AGENTS.md` under ~150 lines and `CLAUDE.md` minimal.
- Never edit `AGENTS.md` / `CLAUDE.md` mid-session (it invalidates the prompt cache) — propose rule changes in the handoff.
- If the session will pause for more than an hour (e.g. a usage limit), write the handoff first; the owner
  continues in a new session instead of resuming a huge one.
- If usage drains too fast: the owner skips `/effort max` (the saved `xhigh` applies).
- **Consultant budget:** up to 3 consults per milestone without asking; more needs the owner's OK. Each consult is
  ONE focused question with file paths (never "review everything"), and is logged in the plan file as
  question · short answer · decision taken.

---

## 4. `.claude/settings.json`

```json
{
  "model": "opus",
  "effortLevel": "xhigh",
  "env": { "CLAUDE_CODE_SUBAGENT_MODEL": "sonnet" },
  "permissions": { "deny": ["Agent(fork)"] }
}
```
- `model: opus` → every session's main thread (the lead) runs on Opus 5.5.
- `effortLevel: xhigh` → the highest level settings can store; `/effort max` raises it for one session.
- `CLAUDE_CODE_SUBAGENT_MODEL=sonnet` → any subagent without its own `model` runs on Sonnet, not Opus.
- Forks are denied: they copy the whole conversation and run on the main model (Opus).
- Do **not** set `CLAUDE_CODE_EFFORT_LEVEL` anywhere: it would override every agent's own `effort`.

---

## 5. `CLAUDE.md` and agent files

### `CLAUDE.md` (repo root)
```markdown
@AGENTS.md

## Main session = lead
If you are the main session (not a subagent), you are the lead described in docs/AI_TEAM.md:
plan and supervise, follow docs/AI_TEAM.md §2 (workflow) and §3 (context rules), and do not write
production code or tests yourself — delegate them to `implementer` (or `debugger` on escalation);
use `Explore` for wide searches and `consultant` for hard questions (within the §3 budget). You may edit only docs/plans/**, docs/progress/**, docs/REPO_MAP.md
and PR text. Start each session by stating the milestone, your model, and the plan file.
Subagents: follow your own agent instructions; this section does not apply to you.
```

### `.claude/agents/implementer.md`
```markdown
---
name: implementer
description: Implements exactly one planned task from docs/plans (tests + code) and reports back. Use for every code change.
model: sonnet
effort: high
tools: Read, Edit, Write, Grep, Glob, Bash
skills:
  - test-driven-development
  - incremental-implementation
maxTurns: 80
---
You implement ONE task given by the lead. The task text is your whole scope.
Process: read only the files you need (line ranges for big files) → write or adjust tests first and see them fail →
implement → run scripts/verify.sh → small commits with clear messages.
Rules: follow AGENTS.md. Never touch protected files, never delete/skip/weaken tests, no new dependencies,
no per-frame allocations, coordinate suffixes Img/View/Mdl. If you edit vendored code under
com/ultralytics, keep the upstream header and add a line to third_party/ULTRALYTICS.md.
If the task is unclear, too big, needs a protected file, or needs knowledge you don't have: stop and report
BLOCKED with the exact question. Do not improvise or guess an API.
Report (max 40 lines): DONE | BLOCKED · files changed · tests added/changed and new @Test count ·
verify.sh summary · deviations from the plan · questions.
```

### `.claude/agents/android-reviewer.md`
```markdown
---
name: android-reviewer
description: Read-only reviewer. Use once a milestone's tasks pass, on the full diff against AGENTS.md, ARCHITECTURE.md and the milestone plan.
model: opus
effort: high
tools: Read, Grep, Glob, Bash
---
You review; you never edit. Start with `git diff --stat main...HEAD`, then the changed files.
Check: plan acceptance criteria; AGENTS.md rules (protected files, tests not weakened, thread table,
coordinate suffixes, LiteRT rules, no per-frame allocations, no magic numbers outside TrackerConfig,
vendored-code changes logged in third_party/ULTRALYTICS.md); math/geometry correctness; lifecycle and leaks;
missing tests.
Output (max 40 lines): Critical / Warning / Suggestion, each with file:line, why, and a concrete fix.
If nothing is critical, say so explicitly.
```

### `.claude/agents/debugger.md`
```markdown
---
name: debugger
description: Root-cause specialist. Use only when a task failed twice, CI is red twice, or for a phone bug report with a diagnostics zip.
model: opus
effort: high
tools: Read, Edit, Write, Grep, Glob, Bash
skills:
  - debugging-and-error-recovery
---
Reproduce → localize → find the root cause with evidence → write a regression test that fails →
minimal fix → scripts/verify.sh. Never weaken tests or touch protected files.
Report (max 40 lines): root cause + evidence · fix · regression test · verify result · remaining risks.
```

### `.claude/agents/consultant.md`
```markdown
---
name: consultant
description: Senior expert on Fable 5.2, used only for hard questions - knowledge the team lacks, conflicting or missing docs, high-risk design decisions (geometry, tracking, latency), or failures the debugger could not fix. Read-only; advises, never edits.
model: fable
effort: xhigh
tools: Read, Grep, Glob, Bash, WebSearch, WebFetch
---
You are the team's senior consultant. You receive ONE focused question with the relevant file paths.
Investigate: read code by line range, official documentation and primary sources. Never edit anything.
Answer (max 60 lines): recommendation · reasoning · confidence (high / medium / low) · how to verify it
(a test, a measurement or a log line) · risks and what would change your mind · sources.
If the question is under-specified, say exactly what is missing instead of guessing.
```

### `.claude/agents/Explore.md` (replaces the built-in Explore, which would otherwise run on the lead's Opus)
```markdown
---
name: Explore
description: Fast read-only codebase search. Use to find files, symbols and usages across the repo.
model: haiku
effort: low
tools: Read, Grep, Glob
---
Search and report locations as file:line with short excerpts. Never edit. Keep the report under 30 lines.
```

---

## 6. Scripts (`scripts/`)

- **`verify.sh`** — runs `./gradlew spotlessCheck test lint assembleDebug` with plain console, writes the full
  log to `build/verify.log`, and prints only: PASS/FAIL per stage; failing test names with the first 15 lines
  of the first failure; Kotlin compile errors (first 20); lint error count + top 5. Non-zero exit on failure.
  Prints the log path so a specific part can be read by line range.
- **`ci-status.sh`** — for the current branch, polls the GitHub REST API (`gh api`, no GraphQL) until the latest
  run completes (max 20 min); prints conclusion, failed job/step names, and the last 40 lines of each failed step.
- **`cloud-setup.sh`** — Android SDK setup for the cloud environment (see BUILD_PLAN M0a).

---

## 7. Notes

- Skills are vendored in `.claude/skills/` (see `SOURCES.md`); subagents get them automatically, and
  `skills:` in frontmatter preloads the full text for the ones they always need.
- Subagent usage counts toward the same plan limits; the split above moves the bulk of the work to Sonnet.
- **Fable availability:** included on Max, Ultra and premium Team/Enterprise seats (where it can use up to half
  of the weekly limit); on Pro and standard seats it runs on paid usage credits. If a consult fails because Fable
  is not available, the lead re-runs the same consult with the per-invocation model `opus` and tells the owner.
- New `.claude/agents/` directories are picked up after a restart, so the team is usable from M0b on.
- Simpler alternative (not used): `/model opusplan` — Opus in plan mode, Sonnet when executing, in one
  context, without a separate reviewer or debugger.
