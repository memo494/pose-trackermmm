import sys, os
sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', 'M3_sim'))
from tracker_ref import *
import scenarios as S
cfgs = {'base': Cfg(), 'new': Cfg(recover=True, gateMinSigma=0.2)}
seeds = range(1, 41)
for name, fn, kw in S.SUITE:
    for fps, jit in S.RUNS:
        out = {}
        for k, c in cfgs.items():
            res = [fn(fps, jit, sd, c, **kw) for sd in seeds]
            out[k] = {m: (min(r[m] for r in res), max(r[m] for r in res)) for m in res[0]}
        diff = [f'{m}: {tuple(round(x,3) for x in out["base"][m])} -> {tuple(round(x,3) for x in out["new"][m])}' for m in out['base'] if out['base'][m] != out['new'][m]]
        if diff: print(f'{name} @{fps}{"+-30%" if jit else ""}: ' + '; '.join(diff))
print('done')
