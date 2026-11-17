import sys,math; sys.path.insert(0,'.')
from scenarios import *
from euro import euro
def run_one(kind,fps,seed,lead_ms,gain=1.0):
    c=Cfg()
    if kind=='walk_stop':
        P=Person(1,[(0,200,Y,H),(2,1000,Y,H),(4,1000,Y,H)]); fr=make_scene([P],4,fps,seed,0.0,**N)
    elif kind=='still':
        P=Person(1,[(0,600,Y,H),(10,600,Y,H)]); fr=make_scene([P],10,fps,seed,0.0,**N)
    else: # zigzag: 2 h/s, reverse every 0.5 s
        pts=[];x=200;t=0;d=1
        while t<=4: pts.append((t,x,Y,H)); t+=0.5; x+=d*H; d=-d
        P=Person(1,pts); fr=make_scene([P],4,fps,seed,0.0,**N)
    tr=Tracker(c); ts=[];xs=[];vs=[]
    for f in fr:
        v=tr.update(f); sh=[t for t in v if t['st']=='C']
        ts.append(f['t']); xs.append(sh[0]['x'] if sh else NAN); vs.append(sh[0]['vx'] if sh else 0.0)
    ex=euro(ts,xs,H,1.0,3.0)
    L=lead_ms/1000.0
    px=[e+gain*v*L if e==e else e for e,v in zip(ex,vs)]
    # truth at display time t+L
    errs=[];
    for f,p in zip(fr,px):
        if p!=p or not f['truth']: continue
        tt=f['t']+L
        at=P.at(min(tt,P.path[-1][0]))
        errs.append(abs(p-at[0])/H)
    a=round(fps)
    jit=jitterRms(px[a:])/max(1e-9,jitterRms([t['mx'] if t else NAN for t in [ (f['truth'][0] if f['truth'] else None) for f in fr]][a:])) if kind=='still' else NAN
    over=max(0,max((p-1000)/H for p,f in zip(px,fr) if p==p and f['t']>=2.0)) if kind=='walk_stop' else NAN
    return sum(errs)/len(errs), max(errs), jit, over
for fps in (20,30):
  for lead in (0,30,50,70):
    for g in (1.0,0.7):
      if lead==0 and g!=1.0: continue
      r={k:[run_one(k,fps,s,lead,g) for s in range(1,7)] for k in ('walk_stop','zigzag','still')}
      print('fps %d lead %2d ms gain %.1f | walk/stop mean err %.3fh overshoot %.3fh | zigzag mean %.3fh max %.3fh | still jitter ratio %.2f'%(fps,lead,g,
        max(x[0] for x in r['walk_stop']),max(x[3] for x in r['walk_stop']),max(x[0] for x in r['zigzag']),max(x[1] for x in r['zigzag']),max(x[2] for x in r['still'])))
