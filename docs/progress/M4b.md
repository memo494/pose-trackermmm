# M4b — fading lost tracks and tap-to-lock (handoff: MERGED)

**Plan:** `docs/plans/M4.md` (M4b). **PR:** #13. CI green, `@Test` 1397. Review OK WITH FIXES, all fixed.

## What the owner sees
- A lost person's ring and brackets fade over 0.8 s (floor 25 %), also on a paused screen (in about two steps).
- A small handle at the right edge: tap it, then tap a person → that person's ring is double and thicker; it
  ends when the person leaves or the phone rotates; tap the handle again to unlock (or to cancel arming).
- Display only: no input is sent to other apps.

## Decided
- No forward prediction: measured, no gain after the M4a smoother and three to six times the jitter.

## Not verified
Everything window- and touch-related only on a phone: checklist section M4b, especially block 4 (taps reach the
app below after arming, Stop and rotation).

## Open
Tuning sheet, re-lock after a loss, sweep-tool fix PR, M1b (benchmark, per-orientation model).
