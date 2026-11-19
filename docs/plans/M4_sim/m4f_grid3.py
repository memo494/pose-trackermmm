import sys, os
sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', 'M3_sim')); sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from tracker_ref import *
from m4f_grid import sw
print('switches/20 s, 7 fps, 15% missed | shift0.05 shift0.10 shift0.15')
for name, kw in [('base', {}), ('floor .20', dict(gateMinSigma=0.2)), ('recover', dict(recover=True)),
                 ('recover + floor .15', dict(recover=True, gateMinSigma=0.15)), ('recover + floor .20', dict(recover=True, gateMinSigma=0.20)),
                 ('recover + floor .25', dict(recover=True, gateMinSigma=0.25)),
                 ('recover + floor .20, accel 4', dict(recover=True, gateMinSigma=0.20, accelSigma=4.0)),
                 ('recover + floor .20, hn .06', dict(recover=True, gateMinSigma=0.20, headNoise=0.06))]:
    c = Cfg(**kw); print(f'  {name:30}| ' + '  '.join(f'{sw(c, s, 0.15):8.2f}' for s in (0.05, 0.10, 0.15)), flush=True)
