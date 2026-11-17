# M4a — faster confirmation and smoothing (handoff: MERGED)

**Plan:** `docs/plans/M4.md`. **PR:** #11. CI green, `@Test` 1281 (1234 after M3b). Review: OK WITH FIXES, all fixed.

## What the owner sees
- A person whose detection flickers gets the ring sooner: a tentative track survives one missed frame
  (`trackTentativeMaxMisses` 1); model p90 600 ms instead of 1050 ms at 40 % missing frames.
- The ring and brackets are smoothed by a OneEuro filter per track (1.0 Hz, beta 3, 1.0 Hz): a still person's
  ring trembles about five times less; lag about a third of a frame; no overshoot after a stop.
- A still picture only promotes tracks seen on the newest frame (no ring on an empty spot during a pause).

## Not verified
On a phone: checklist section M4a. The jitter test margin is thin (0.19 vs 0.2), deterministic.

## Next (M4b)
Forward prediction (tracker velocity on the smoothed point), fading LOST tracks, tap-to-lock, a tuning sheet;
bracket size still follows the raw height. Sweep-tool fix PR still open.
