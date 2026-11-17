import sys,random; sys.path.insert(0,'.')
from scenarios import *
def flick_one(fps,jit,seed,c,p=0.4,score=0.4):
    T=4.0; ts=[f['t'] for f in make_scene([],T,fps,seed,jit)]; rr=random.Random(seed*13+1); e=1e-7
    P=Person(1,[(0,400,Y,H),(T,900,Y,H)],score=lambda t:score,dropouts=[(t-e,t+e) for i,t in enumerate(ts) if rr.random()<p])
    fr=make_scene([P],T,fps,seed,jit); m,av,tr=run(fr,c)
    first=next((i for i in range(len(fr)) if m.f[i]['tracks']),999)
    return first*1000.0/fps
for tm in (0,1,2):
    c=Cfg(tentMaxMisses=tm); print('tentMaxMisses',tm)
    for p in (0.2,0.4,0.6):
        r=[flick_one(20,0,s,c,p=p) for s in range(1,41)]; r.sort()
        print('  miss p=%.1f  first shown ms median %.0f  p90 %.0f  never %d'%(p,r[20],r[36],sum(1 for x in r if x>4000)))
    for name,fn,kw in [('S9',false_positives,{}),('S9+p',false_positives,dict(person=True)),('S13',flicker,{}),('S1',cross,{}),('S12',duplicate,{})]:
        res=[fn(f,j,sd,c,**kw) for f,j in RUNS for sd in range(1,11)]
        print('  ',name,{k:(min(x[k] for x in res),max(x[k] for x in res)) for k in res[0]})
