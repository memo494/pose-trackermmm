# Vendored skills — sources

Skills are vendored (copied) into this folder so cloud sessions always have them at a fixed version
(docs/BUILD_PLAN.md §2). `.claude/skills/**` is PROTECTED (AGENTS.md): update a skill only as its own
task, by re-copying from the pinned source below and bumping the commit here. Each skill folder keeps
its upstream `SKILL.md` and a copy of its source repo's `LICENSE`.

## Sources (pinned commits, fetched 2026-11-10)
| Source repo | Commit | License |
|---|---|---|
| github.com/android/skills | `5815d27fda564aa9f07ebdcb5e57ed3e8d904e0d` | Apache-2.0 |
| github.com/chrisbanes/skills | `72628aea92c9081df0ba96600f24af3f07f4a87a` | Apache-2.0 |
| github.com/addyosmani/agent-skills | `301b02b12bff98e4688e6984af6556802322bbde` | MIT |

## From android/skills (Apache-2.0) — official Google Android skills
| Skill | Upstream path | Why |
|---|---|---|
| `camerax` | camera/camerax | camera best practices (resolution, rotation, image analysis) |
| `litert-runtime` | device-ai/litert/litert-runtime | run the model on-device via the new CompiledModel API |
| `testing-setup` | testing/testing-setup | testing strategy |
| `jetpack-compose-performance` | jetpack-compose/jetpack-compose-performance | 120 Hz overlay draw without jank |
| `anr-audit-and-fix` | performance/anr-audit-and-fix | keep the app responsive (no ANRs) |
| `edge-to-edge` | system/edge-to-edge | Android 15 edge-to-edge; affects overlay dot placement |

Note: the official LiteRT skill prefers the Play-services runtime; we deliberately use the **bundled**
runtime for reproducible benchmarks (AGENTS.md / ARCHITECTURE §2, §11.6).

## From chrisbanes/skills (Apache-2.0) — Kotlin/Compose engineering
| Skill | Why |
|---|---|
| `compose-state-and-effects` | deferred state reads + Compose side effects |
| `compose-performance` | deferred reads in the draw phase — the smooth-dot technique (M4) |
| `kotlin-concurrency-and-flow` | coroutines and Flow |

## From addyosmani/agent-skills (MIT) — ways of working
| Skill | Why |
|---|---|
| `incremental-implementation` | small, tested steps |
| `test-driven-development` | test before code |
| `debugging-and-error-recovery` | systematic debugging |
| `source-driven-development` | official docs over guessed APIs |
| `code-review-and-quality` | review before merge |
| `doubt-driven-development` | fresh-context review of important decisions |
| `ci-cd-and-automation` | M0 only (GitHub Actions setup) |

Alternative considered (not used): `obra/superpowers` — do **not** install alongside addyosmani (they conflict).
