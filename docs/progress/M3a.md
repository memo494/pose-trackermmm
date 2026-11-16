# M3a — the tracker in `core` (handoff: MERGED, nothing visible yet)

**Plan:** `docs/plans/M3.md`. **Branch:** `m3a-tracker-core`, PR #8. **Builds:** no local Gradle in cloud
sessions, so CI is the build.

## Status (2026-11-16; lead on Fable 5.2, the owner's choice; implementer Sonnet, reviewer Opus)
- **All tasks done and green:** T1–T7 and the review fixes (T8). Last green run: 43948853721 (#114) on `f7624b0`.
  `@Test` = 1129 (905 on `main` before). No protected file changed, no new dependency.
- **Nothing the owner sees changes:** the tracker is not used by the app yet. That is M3b.
- **Owner approvals (chat):** as for M1a (M1 → M3 → M4 without waiting for plan approval; the lead merges after
  green CI and an independent review); "you are the consultant": the lead on Fable 5.2 answered the three
  consults of the plan (C1 lifecycle, C2 assigner objective, C3 the numbers) itself.
- **Review (android-reviewer):** OK WITH FIXES, nothing critical or major; seven minor findings, all fixed in T8
  (plan section "Review of M3a").
- **Interruptions:** the chat lost a turn on 2026-11-16 around noon, and the account's usage limit stopped two
  agents at 19:50 (resumed at 22:56, same agents). The plan's Progress table and the lead's notes carried the
  state; no work was redone.

## What was built (package `core/tracking`, pure Kotlin, allocation-free after construction)
- **Head point:** `HeadMeasurement` (stateless, for association) and `HeadPointEstimator` (per track: keypoint
  hysteresis, learned offsets so a vanishing ear does not move the point, box fallback) writing a `HeadPoint`.
- **`KalmanAxis`:** position/velocity filter for one axis; a track runs three (head x, head y, box height).
- **Association:** `AssociationCost` (IoU and head distance, Mahalanobis gate), `Assigner` / `HungarianAssigner`
  with a **cost limit** (it maximises `sum(limit − cost)`, it does not maximise the number of pairs),
  `DetectionDedupe`, `BoxOverlap`.
- **`Tracker`** (`TrackList`, `TrackState`): `update(frame)`, `onIdle(idleNs)`, `reset()`; TENTATIVE → CONFIRMED
  from the third hit (or by `onIdle` on a still picture) → LOST while missing (shown, coasting) → removed after
  0.8 s or outside the image. Counters for the diagnostics of M3b.
- **`TrackerConfig`:** the head, association and `track…` fields (every number of the tracker).
- **Tests:** unit tests per class; the test tools `SyntheticScene` (seeded synthetic detections with truth),
  `TrackingMetrics`, `ScenarioRunner`; **`TrackerScenarioTest` S1–S14** at 20, 30, 60 fps and 30 fps ± 30 %,
  seeds 1–3, asserting the numbers of `MetricsThresholds.kt` (protected).
- **Reference model:** `docs/plans/M3_sim/` (`tracker_ref.py`, `scenarios.py`): the lead's Python model of the
  whole tracker. The thresholds were measured on it first; `Tracker` is its port. Analysis only, not run by CI.
- **Docs:** ARCHITECTURE v0.8 (§6.2, §6.3, §7.1, §9, §12, §13, §15).

## Decisions (full text: plan, Consults C1–C3)
- Birth at score 0.25 (the architecture said 0.6: most of the owner's people score 0.34 to 0.68), shown from
  the third consecutive hit; a one- or two-frame false positive is never shown.
- A still picture: `onIdle` after 400 ms without a frame shows the tentative tracks.
- Velocity decay on a miss τ 300 ms (was 100), time-step cap 250 ms (was 100), noise inflation only on a gap
  step, no "NIS above the gate" rule, cost limits 0.7 / 0.6, no matching cascade.
- The first T4 objective ("most pairs first") was wrong (ids shifted along a row of people) and was replaced
  during the task (C2).

## Known limits (not asserted, ARCHITECTURE §13)
- Two people crossing slowly in one row can swap ids (no appearance model).
- A person missing for 0.5 s while moving at 2 h/s, and a person faster than about 1 h/s whose picture freezes
  without one frozen frame, get a new id.
- Duplicate boxes of one person with IoU below 0.7 give two tracks.

## Not verified
- **Real detections.** The scenarios use synthetic detections (keypoint and box noise 0.01 h, boxes 0.4 h wide,
  steady scores). The owner's content may flicker, jitter and duplicate differently; the first phone test of
  M3b and a recorded replay (planned: `replay.json` in the diagnostics) are the check.
- **Allocation-freeness of `Tracker.update`** by measurement (checked by reading, lead and reviewer).
- **Degenerate boxes.** A matched detection whose box is far lower than a pixel can spoil a track's learned
  head offsets (the track then leaves the image and is removed; no crash). M3b T1 refuses such boxes.

## Open items / proposals
- Follow-up for the model sweep tool (review of PR #9, OK WITH FIXES): stop on duplicate reference boxes, a
  per-model check on the plain scene, say that the reference is the 640 model's own output, record
  `num_threads`, refuse `publish_tag` `models-sweep`. Small tools-only PR, not started.
- ARCHITECTURE §5 still shows the data flow without the tracker (M3b T5); §6.6 says "confirmed track" where
  "shown track" is meant (M4); §11 item 0 names Opus 5.5 as lead while the owner chose Fable 5.2 for this run.
- Proposals for AGENTS.md (not edited mid-session): sizes are written WIDTHxHEIGHT; a milestone's reference
  model lives in `docs/plans/<milestone>_sim/` and is analysis only.

## Next
M3b, the tracker in the app (plan: `docs/plans/M3.md`, section M3b, tasks T1–T5; branch `m3b-tracker-app`).
It is the first change the owner sees: rings that stay on heads through short misses, `trk N` in the HUD.
