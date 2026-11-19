#!/usr/bin/env python3
"""M4e: id switches of one walking person on the reference tracker (M3_sim) by capture rate, speed and turns.
Not production code, not run by CI. Run: python3 walk.py"""
import sys, os, random, math
sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', 'M3_sim'))
from tracker_ref import *
# a person walking on a security-camera picture: speed v (heights/s), direction change every turnS, captured at fps
def walker(v, turnS, dur, seed, H=120.0, W=1356, Hh=610):
    rnd = random.Random(seed*7+1); pts=[]; t=0; x=W/2; y=Hh/2
    while t <= dur:
        pts.append((t, x, y, H)); a = rnd.uniform(0, 2*math.pi); step = v*H*turnS
        x = min(W-100, max(100, x + math.cos(a)*step)); y = min(Hh-150, max(60, y + math.sin(a)*step*0.4)); t += turnS
    return Person(1, pts)
def trial(fps, v, turnS, jit, seed, c, kn=0.02, drop=0.0):
    P = walker(v, turnS, 20, seed)
    fr = make_scene([P], 20, fps, seed, jit, kpNoise=kn, boxNoise=kn)
    if drop:
        r = random.Random(seed)
        for f in fr:
            if r.random() < drop: f['dets'] = []
    m, av, tr = run(fr, c)
    return m.idSwitches(1), tr.nextId-1
def table(c, label, drop=0.0):
    print(label)
    for fps in (8, 12, 20):
        row=[]
        for v in (0.5, 1.0, 2.0):
            for turn in (0.5, 1.5):
                sw=0; ids=0
                for s in range(1, 21):
                    a,b = trial(fps, v, turn, 0.3, s, c, drop=drop); sw+=a; ids+=b
                row.append(f"v{v}/t{turn}:{sw/20:.1f}")
        print(f"  {fps:>2}fps", " ".join(row))
if __name__ == "__main__":
    table(Cfg(), "base: id switches per 20 s walk (mean of 20 seeds)")
    table(Cfg(), "base + 10% missed detections", drop=0.1)
