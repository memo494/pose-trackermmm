#!/usr/bin/env python3
"""The T5 acceptance scenarios of docs/plans/M3.md on the reference model (tracker_ref.py).

Prints, per scenario, the worst value over the four frame-rate runs (20, 30, 60 fps and 30 fps +/- 30 %) and
the seeds 1..40. The T5 thresholds were set from this output. Not production code, not run by CI.
Run: python3 scenarios.py [seeds]"""
import math, os, random, sys
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from tracker_ref import *

RUNS = [(20, 0.0), (30, 0.0), (60, 0.0), (30, 0.3)]
H = 200.0; Y = 150.0; W = 1356; N = dict(kpNoise=0.01, boxNoise=0.01)


def ids_made(tr): return tr.nextId - 1


def finite_err(m, pid, i):
    e = m.err(pid, i); return e if e == e else 9.0


def cross(fps, jit, seed, c, dy=0.0):
    A = Person(1, [(0, 250, Y, H), (5, 1050, Y, H)]); B = Person(2, [(0, 1050, Y + dy * H, H), (5, 250, Y + dy * H, H)])
    fr = make_scene([A, B], 5, fps, seed, jit, **N); m, av, tr = run(fr, c)
    a0 = m.first_at(1.5); a1 = m.first_at(3.5)
    keep = all(m.tid(a0, p) is not None and m.tid(a0, p) == m.tid(a1, p) for p in (1, 2)) and m.tid(a0, 1) != m.tid(a0, 2)
    out = Metrics([f for f in m.f if not (2.1 <= f['t'] <= 2.9)])
    return dict(idsKept=int(keep), switchesOutside=out.idSwitches(1) + out.idSwitches(2),
                coverage=min(m.coverage(1, 2), m.coverage(2, 2)), ghosts=m.ghostFrames(), ids=ids_made(tr))


def stop(fps, jit, seed, c):
    P = Person(1, [(0, 200, Y, H), (2, 1000, Y, H), (4, 1000, Y, H)])
    fr = make_scene([P], 4, fps, seed, jit, **N); m, av, tr = run(fr, c)
    return dict(overshoot=m.overshoot(1, 2.0), absLag=abs(m.lagFrames(1, m.first_at(1.0), m.first_at(2.0))),
                coverage=m.coverage(1, 2), ids=ids_made(tr))


def still(fps, jit, seed, c):
    P = Person(1, [(0, 600, Y, H), (20, 600, Y, H)])
    fr = make_scene([P], 20, fps, seed, jit, **N); m, av, tr = run(fr, c); a = round(fps)
    return dict(jitterRatio=max(jitterRms(m.series(1, ax, a)) / jitterRms(m.raw(1, ax, a)) for ax in (0, 1)), ids=ids_made(tr))


def dropout(fps, jit, seed, c, x1=1100):
    P = Person(1, [(0, 300, Y, H), (5, x1, Y, H)], dropouts=[(2.0, 2.5)])
    fr = make_scene([P], 5, fps, seed, jit, **N); m, av, tr = run(fr, c)
    i0 = m.first_at(2.0) - 1; i1 = m.first_at(2.5)
    return dict(sameId=int(m.tid(i0, 1) is not None and m.tid(i0, 1) == m.tid(i1, 1)), switches=m.idSwitches(1),
                coverage=m.coverage(1, 2), ids=ids_made(tr))


def keypoints(fps, jit, seed, c):
    P = Person(1, [(0, 300, Y, H), (4, 1100, Y, H)], kpDrop=[((2.0, 3.0), (0, 1, 3))])
    fr = make_scene([P], 4, fps, seed, jit, **N); m, av, tr = run(fr, c)
    return dict(maxError=m.maxErr(1, 10), switches=m.idSwitches(1), ids=ids_made(tr))


def gap(fps, jit, seed, c, pause=False):
    if pause:   # a player's pause: 2 h/s, 0.3 s of frozen frames, 5 s without frames, 2 h/s again
        P = Person(1, [(0, 100, Y, H), (2, 900, Y, H), (7.3, 900, Y, H), (8.3, 1300, Y, H)]); g = (2.3, 7.3); dur = 8.3
    else:       # 0.5 h/s, 5 s without frames, 0.5 h/s again
        P = Person(1, [(0, 300, Y, H), (2, 500, Y, H), (7, 500, Y, H), (9, 700, Y, H)]); g = (2.0, 7.0); dur = 9.0
    fr = make_scene([P], dur, fps, seed, jit, gaps=[g], **N)
    last = max(f['i'] for f in fr if f['t'] < g[0])
    m, av, tr = run(fr, c, idle={last: [500, 1000, 4000]}); i1 = last + 1
    return dict(sameId=int(m.tid(last, 1) is not None and m.tid(last, 1) == m.tid(i1, 1)), ids=ids_made(tr),
                maxErrorFirst3=max(finite_err(m, 1, i1 + k) for k in range(3)), emptyLists=sum(1 for v in av[3:] if not v))


def still_picture(fps, jit, seed, c, n=1, score=0.9):
    P = Person(1, [(0, 600, Y, H), (5, 600, Y, H)], score=lambda t: score)
    fr = make_scene([P], 1.0, fps, seed, jit, **N); tr = Tracker(c)
    for f in fr[:n]: v = tr.update(f)
    shown = lambda v: sum(1 for t in v if t['st'] == 'C')
    before = shown(v); mid = shown(tr.on_idle(c.idlePromoteMs - 1)); v = tr.on_idle(c.idlePromoteMs); after = shown(v)
    err = math.hypot(v[0]['x'] - 600, v[0]['y'] - Y) / H if v else 9.0; pid = v[0]['id'] if v else -1
    for f in fr[n:n + 5]: v = tr.update(f)
    return dict(shownBefore=before + mid, shownAfter=after, error=err, sameIdLater=int(len(v) == 1 and v[0]['id'] == pid), ids=ids_made(tr))


def low_score(fps, jit, seed, c, score=0.30):
    fn = score if callable(score) else (lambda t: score)
    P = Person(1, [(0, 300, Y, H), (4, 1100, Y, H)], score=fn)
    fr = make_scene([P], 4, fps, seed, jit, **N); m, av, tr = run(fr, c)
    first = next((i for i in range(len(fr)) if len(m.f[i]['tracks']) > 0), 99)
    return dict(firstShownFrame=first, coverage=m.coverage(1, 2) if first < 99 else 0.0, ids=ids_made(tr),
                maxTracks=max(len(v) for v in av))


def false_positives(fps, jit, seed, c, person=False):
    t = [f['t'] for f in make_scene([], 3, fps, seed, jit)]; e = 1e-7; n = len(t)
    k1 = n // 6; k2 = n // 3; k3 = n // 2
    fpos = [((t[k1] - e, t[k1] + e), 300, 200, H, 0.55), ((t[k2] - e, t[k2] + e), 700, 250, H, 0.90),
            ((t[k3] - e, t[k3 + 1] + e), 1000, 200, H, 0.40)]
    people = [Person(1, [(0, 500, Y, H), (3, 500, Y, H)])] if person else []
    fr = make_scene(people, 3, fps, seed, jit, fpos=fpos)          # no noise: the frame times stay those of `t`
    assert [f['t'] for f in fr] == t and sum(len(f['dets']) for f in fr) == 4 + (n if person else 0)
    m, av, tr = run(fr, c)
    return dict(ghosts=m.ghostFrames(), maxShown=max(len(f['tracks']) for f in m.f))


def exit_right(fps, jit, seed, c):
    P = Person(1, [(0, 956, Y, H), (1, W, Y, H)])
    fr = make_scene([P], 3, fps, seed, jit, **N); m, av, tr = run(fr, c)
    late = [f['t'] - 1.0 for f in m.f if f['t'] > 1.0 and f['tracks']]
    beyond = max([max(-t['x'], t['x'] - W, -t['y'], t['y'] - 610) / t['h'] for v in av for t in v] + [-9.0])
    return dict(shownAfterS=max(late + [0.0]), beyondInH=beyond, ids=ids_made(tr), coverage=m.coverage(1, 2))


def duplicate(fps, jit, seed, c):
    P = Person(1, [(0, 300, Y, H), (3, 900, Y, H)], dups=[(0.0, 3.0)])
    fr = make_scene([P], 3, fps, seed, jit, **N); m, av, tr = run(fr, c)
    return dict(maxTracks=max(len(v) for v in av), ids=ids_made(tr), coverage=m.coverage(1, 2))


def flicker(fps, jit, seed, c, p=0.3):
    T = 4.0; ts = [f['t'] for f in make_scene([], T, fps, seed, jit)]; rr = random.Random(seed * 31 + 7); e = 1e-7
    ppl = [Person(k + 1, [(0, 200 + 90 * k, Y, H), (T, 200 + 90 * k + 640, Y, H)],
                  dropouts=[(t - e, t + e) for i, t in enumerate(ts) if i >= 5 and rr.random() < p]) for k in range(3)]
    fr = make_scene(ppl, T, fps, seed, jit); assert [f['t'] for f in fr] == ts
    m, av, tr = run(fr, c)
    return dict(switches=sum(m.idSwitches(k + 1) for k in range(3)), ids=ids_made(tr), ghosts=m.ghostFrames(),
                coverage=min(m.coverage(k + 1, 5) for k in range(3)))


def approach(fps, jit, seed, c):
    P = Person(1, [(0, 400, 100, 100), (3, 700, 100, 250)])
    fr = make_scene([P], 3, fps, seed, jit, **N); m, av, tr = run(fr, c)
    return dict(maxError=m.maxErr(1, 10), ids=ids_made(tr), coverage=m.coverage(1, 2))


SUITE = [('S1 cross, one row', cross, {}), ('S1 cross, 0.3 h lower', cross, dict(dy=0.3)), ('S2 stop', stop, {}),
         ('S3 still', still, {}), ('S4 dropout, walking', dropout, {}), ('S4 dropout, standing', dropout, dict(x1=300)),
         ('S5 keypoints', keypoints, {}), ('S6 gap', gap, {}), ('S6 gap, player pause', gap, dict(pause=True)),
         ('S7 still picture n=1', still_picture, dict(n=1)), ('S7 still picture n=2', still_picture, dict(n=2)),
         ('S7 still picture n=1 score 0.30', still_picture, dict(n=1, score=0.30)),
         ('S8 score 0.30', low_score, {}), ('S8 score 0.25 + 0.08 sin 9t', low_score, dict(score=lambda t: 0.25 + 0.08 * math.sin(9 * t))),
         ('S9 false positives', false_positives, {}), ('S9 false positives + person', false_positives, dict(person=True)),
         ('S10 score 0.20', low_score, dict(score=0.20)), ('S11 exit', exit_right, {}), ('S12 duplicate', duplicate, {}),
         ('S13 flicker', flicker, {}), ('S14 approach', approach, {})]

if __name__ == '__main__':
    seeds = range(1, int(sys.argv[1]) + 1 if len(sys.argv) > 1 else 41); c = Cfg()
    f3 = lambda x: ('%.3f' % x).rstrip('0').rstrip('.')
    for name, fn, kw in SUITE:
        print(name)
        for fps, jit in RUNS:
            res = [fn(fps, jit, sd, c, **kw) for sd in seeds]
            print('  %2d fps %s  ' % (fps, '+-30%' if jit else '     ') +
                  '  '.join('%s %s..%s' % (k, f3(min(r[k] for r in res)), f3(max(r[k] for r in res))) for k in res[0]))
