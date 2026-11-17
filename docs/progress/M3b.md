# M3b — the tracker in the app (handoff: MERGED; the first change the owner sees)

**Plan:** `docs/plans/M3.md` (section M3b). **Branch:** `m3b-tracker-app`, PR #10.

## Status (2026-11-17; lead on Fable 5.2 / Opus 5.5 as the owner switched; implementer Sonnet; reviewer Opus)
- T1–T6 done, CI green (run 44009329745 on `1acc841`), `@Test` 1234 (1129 after M3a). No protected file,
  no new dependency. Review (android-reviewer): OK WITH FIXES, nothing critical or major; five fixes in T6.
- Stopped twice by the account's usage limit; the agents were resumed, no work was redone.

## What the owner sees
- Rings and corner brackets come from **tracks**: shown from the third frame of a person (about 0.1 s), kept
  at the predicted place for up to 0.8 s when the detector misses, removed when the person leaves.
- A still picture (paused video, photo) gets its marks 0.4 to 0.9 s after the last frame; a paused video keeps
  its rings; a person who left just before the pause loses the ring after 0.8 s.
- HUD `det 2/3 trk 2 max …`; `summary.txt` gains seven `track…` lines. With **Debug marks** the raw boxes,
  keypoints and the track ids are drawn too.
- Rotation and other geometry changes reset the tracker (new ids).

## Not verified
- Nothing on a phone yet: the checklist section **M3** (`docs/TEST_CHECKLIST.md`) is the test.
- Below about 2.5 processed frames per second a still picture and a slow stream look alike: tentative tracks
  are then shown early.

## Open items for M4 (from the review)
- Forward prediction needs velocity and a timestamp in the snapshot and a decision who republishes.
- Fading LOST tracks needs `isLost` in the redraw decision and `lostMs` in the snapshot.
- Tap-to-lock needs a thread-safe path for the locked id to the capture thread; a reset ends the lock.
- Follow-up PR for the sweep tool (review of PR #9) is still open.

## Next
M4: smoothing (forward prediction, OneEuro), fading, tap-to-lock, tuning (plan to be written).
