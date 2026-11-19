"""M4f: id switches of a walker with phone-like whole-detection jumps vs measurement noise, accel sigma and gate floor."""
import sys, os, itertools
sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', 'M3_sim')); sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from tracker_ref import *
from m4f_gatefloor2 import shake
from walk import walker
def sw(c, s, drop, seeds=20, fps=7):
    tot = 0
    for v in (0.3, 0.7, 1.2):
        for sd in range(1, seeds + 1):
            fr = shake(make_scene([walker(v, 1.0, 20, sd)], 20, fps, sd, 0.3, kpNoise=0.02, boxNoise=0.02), s, sd, drop)
            m, av, tr = run(fr, c); tot += m.idSwitches(1)
    return tot / (3 * seeds)
if __name__ == "__main__":
    print('headNoise accel floor | shift0.05 shift0.10 shift0.15')
    for hn, ac, fl in itertools.product((0.03, 0.06, 0.10), (8.0, 4.0, 2.0), (0.0, 0.2)):
        c = Cfg(headNoise=hn, accelSigma=ac, gateMinSigma=fl)
        print(f'  {hn:.2f} {ac:4.1f} {fl:.1f} | ' + '  '.join(f'{sw(c, s, 0.15):8.2f}' for s in (0.05, 0.10, 0.15)), flush=True)
