# M-final — the last milestone (owner-approved scope, 2026-11-22)

Lead: Opus 5.5. Owner said "بلش" to this scope after a Q&A. One concern per branch and PR; each PR: tests that
fail without it, android-reviewer, `ci-status.sh` green, the lead merges. Owner sends no recordings: work from the
diagnostics of 2026-11-22 (v0.1.207, 16090 frames, 487 births, 180 assoc misses, 11 relocks, 101 relock rejects).

## Owner's answers (the scope)
- Problems seen: the dot lags behind movement; the tracker sometimes forgets a person who is still in the picture;
  the lock comes back to the right person only sometimes. (Not reported: dot offset on rotation/notch → M2 dropped.)
- Wants a tuning sheet (sliders, persisted, reset).
- Wants 448x960 (portrait) and 960x448 (landscape) bundled, import kept.
- Wants the widest model compatibility; was told detect-only models stay refused (no head keypoints, unknown
  person class → the hard rule forbids guessing).
- Heat: step down to a smaller model.
- Dropped: benchmark screen, raw goldens from Python, M2 hardening, replay mode.
- Hard rule unchanged: the ring never goes to a wrong person (one accepted risk: a look-alike appearing alone within
  0.6 h of the lost person's place).

## PRs (order; F1/F2 may run in parallel with the tracking work)
| # | Branch | Concern | Agent | Size |
|---|---|---|---|---|
| F1 | `mf-bundled-models` | 448x960 + 960x448 inside the APK as the default portrait/landscape models (built-in per orientation); import and slots unchanged; `model/**` + meta + goldens if any (owner OK for protected `model/**`) | implementer | M |
| F2 | `mf-raw-pose-output` | Accept raw pose output `[1, 4+1+51, N]` channels-first and `[1, N, 56]` channels-last (Ultralytics default `nms=None`, YOLOv8/11/26 pose): pure-Kotlin decode + class-agnostic NMS in `core`, no per-frame allocation, FP32 I/O only; detect-only and INT8 I/O refused with a clear message | implementer | M |
| F3 | `mf-track-keep` | The tracker keeps a person who is still visible: fixes from the debugger's root-cause analysis of the log | debugger → implementer | M |
| F4 | `mf-dot-latency` | Less lag on fast movement without breaking the no-tremble result of M4a (consultant question, then implement) | consultant → implementer | S/M |
| F5 | `mf-relock` | Re-lock comes back reliably to the right person; the wrong-person rule unchanged | debugger → implementer | M |
| F6 | `mf-tuning-sheet` | Sliders (persisted, reset) for score threshold, smoothing, max lost time, match cost, re-lock distance/margin | implementer | M |
| F7 | `mf-thermal` | Thermal ≥ MODERATE (or headroom > 0.9) → switch to the built-in 320 model, back with hysteresis; HUD note + log line | implementer | S/M |
| F8 | `mf-final-checklist` | TEST_CHECKLIST "final", ARCHITECTURE §12/§13 status, handoff | lead | S |

## Consults (budget 3)
| # | Question | Answer | Decision |
|---|---|---|---|
| 1 | Ring lag on moving heads (F4) — Fable had no credits, re-run on Opus per AI_TEAM §7 (owner told) | 75–85 % of the lag is pipeline time; display-only lead with speed gate, stop guard, cap; M4 "no prediction" was scored without latency (pred.py) | Adopted; extra 15→10 ms after review for stop-overshoot margin |

## Progress
| PR | Status | Notes |
|---|---|---|
| #21 | merged | PR #20 guard tests, strengthened (files + bytes); #20 closed (REST cannot un-draft) |
| #22 | merged | M1b-fix-import: narrow auto-assign of a new import, message, log |
| #23 F2 | merged | raw pose output; detect-only / INT8 refused; readFloat ~1.9 MB per frame for raw models (known) |
| #24 F1 | merged | 448x960 / 960x448 bundled; crash marker around the compile; BUILT_IN_FAILED visible; APK +24 MB |
| #25 F3 | merged | birth suppression + shadowed tentatives (IoU ≥ 0.5 AND head ≤ 0.15 h); 1 Critical fixed in review; known limit: person directly behind another |
| #26 F7 | merged | thermal HOT → 320 model; NORMAL after 60 s cool |
| #27 F5 | merged | re-lock: torso-OK judge frames, buckets, ≥ 3 samples, no cross-bucket; 1 Critical + 1 wrong-person warning fixed |
| #28 F4 | merged | display lead; S3 back at 12 fps with the unchanged 0.60 limit (tracker noise scaling) |
| #29 F6 | merged | tuning sheet, 13 sliders, sheet-only bounds (re-lock distance ≤ 0.6, margin ≥ 0.05) |
| F8 | this PR | ARCHITECTURE §12/§13 status, TEST_CHECKLIST "M-final", handoff |

Owner decisions during the milestone: S3 at 12 fps fixed in F4 rather than a new limit (protected file);
"best decision" mandate for auto-assign; the re-lock tie rule (≥ margin) kept as is.
