"""M4f: phone-like head jumps (whole-detection shifts, correlated) vs the gate floor."""
import sys, os, math, random
sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', 'M3_sim')); sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from tracker_ref import *
from walk import walker
FLOORS = [0.0, 0.10, 0.15, 0.20, 0.25]
def shake(fr, s, seed, drop):
    r = random.Random(seed * 13 + 5)
    for f in fr:
        if r.random() < drop: f['dets'] = []; continue
        for d in f['dets']:
            h = d['b'] - d['t']; dx = r.gauss(0, s * h); dy = r.gauss(0, s * h)
            d['l'] += dx; d['r'] += dx; d['t'] += dy; d['b'] += dy
            d['kx'] = [x + dx for x in d['kx']]; d['ky'] = [y + dy for y in d['ky']]
    return fr
class Count(Tracker):
    pass
def gate_stats(fr, c):
    tr = Tracker(c); m, av, t = run(fr, c); return m
def walkers(fps, s, drop, seeds=30):
    print(f'one walker {fps} fps, whole-detection shift sigma {s} h, {int(drop*100)}% missed frames: id switches / 20 s')
    for g in FLOORS:
        c = Cfg(gateMinSigma=g); row = []
        for v in (0.3, 0.7, 1.2):
            sw = 0
            for sd in range(1, seeds + 1):
                fr = shake(make_scene([walker(v, 1.0, 20, sd)], 20, fps, sd, 0.3, kpNoise=0.02, boxNoise=0.02), s, sd, drop)
                m, av, tr = run(fr, c); sw += m.idSwitches(1)
            row.append(f'v{v}:{sw/seeds:.2f}')
        print(f'  floor {g:.2f}: ' + '  '.join(row))
def crossing(fps, s, seeds=30):
    print(f'two walkers crossing {fps} fps, shift sigma {s} h: runs where an id moved to the other person / switches')
    for g in FLOORS:
        c = Cfg(gateMinSigma=g); bad = 0; sw = 0
        for sd in range(1, seeds + 1):
            for dy in (0.0, 0.3, 0.6):
                A = Person(1, [(0, 250, 150, 200), (5, 1050, 150, 200)]); B = Person(2, [(0, 1050, 150 + dy * 200, 200), (5, 250, 150 + dy * 200, 200)])
                fr = shake(make_scene([A, B], 5, fps, sd, 0.3, kpNoise=0.02, boxNoise=0.02), s, sd, 0.05); m, av, tr = run(fr, c)
                a0 = m.first_at(1.0); a1 = m.first_at(4.0)
                ida = (m.tid(a0, 1), m.tid(a1, 1)); idb = (m.tid(a0, 2), m.tid(a1, 2))
                if ida[0] is not None and idb[0] is not None and (ida[1] == idb[0] or idb[1] == ida[0]): bad += 1
                sw += m.idSwitches(1) + m.idSwitches(2)
        print(f'  floor {g:.2f}: swapped {bad} of {seeds*3}, switches {sw}')
def side(fps, s, gapH, seeds=30):
    print(f'two people standing side by side {gapH} h apart, {fps} fps, shift {s} h: swaps / switches in 10 s')
    for g in FLOORS:
        c = Cfg(gateMinSigma=g); bad = 0; sw = 0
        for sd in range(1, seeds + 1):
            A = Person(1, [(0, 500, 150, 200), (10, 520, 150, 200)]); B = Person(2, [(0, 500 + gapH * 200, 150, 200), (10, 480 + gapH * 200, 150, 200)])
            fr = shake(make_scene([A, B], 10, fps, sd, 0.3, kpNoise=0.02, boxNoise=0.02), s, sd, 0.05); m, av, tr = run(fr, c)
            a0 = m.first_at(1.0); a1 = m.first_at(9.0)
            if m.tid(a0, 1) is not None and m.tid(a0, 1) == m.tid(a1, 2): bad += 1
            sw += m.idSwitches(1) + m.idSwitches(2)
        print(f'  floor {g:.2f}: swapped {bad} of {seeds}, switches {sw}')
if __name__ == '__main__':
    walkers(7, 0.10, 0.15); walkers(7, 0.15, 0.15); crossing(7, 0.10); side(7, 0.10, 0.4); side(7, 0.10, 0.25)
