import sys,math; sys.path.insert(0,'.')
from scenarios import *
def lp(a,x,prev): return a*x+(1-a)*prev
def alpha(fc,dt): r=2*math.pi*fc*dt; return r/(r+1)
def euro(ts,xs,h,mincut,beta,dcut=1.0):
    out=[];px=None;pdx=0.0;pt=None
    for t,x in zip(ts,xs):
        if x!=x: out.append(x); px=None; continue
        if px is None: px=x;pdx=0.0;pt=t;out.append(x);continue
        dt=t-pt; pt=t
        dx=(x-px)/dt; pdx=lp(alpha(dcut,dt),dx,pdx)
        fc=mincut+beta*abs(pdx)/h
        px=lp(alpha(fc,dt),x,px); out.append(px)
    return out
def run_one(kind,fps,seed,mc,beta):
    c=Cfg()
    if kind=='still':
        P=Person(1,[(0,600,Y,H),(10,600,Y,H)]); fr=make_scene([P],10,fps,seed,0.0,**N)
    else:
        P=Person(1,[(0,200,Y,H),(2,1000,Y,H),(4,1000,Y,H)]); fr=make_scene([P],4,fps,seed,0.0,**N)
    m,av,tr=run(fr,c); ts=[f['t'] for f in m.f]
    xs=m.series(1,0); ys=m.series(1,1)
    ex=euro(ts,xs,H,mc,beta); a=round(fps)
    if kind=='still':
        return jitterRms(ex[a:])/jitterRms(m.raw(1,0,a)), jitterRms(xs[a:])/jitterRms(m.raw(1,0,a))
    si=m.first_at(2.0); stop=1000.0
    over=max(0,max((x-stop)/H for x in ex[si:] if x==x))
    # lag while walking: mean (truth - est) over 1..2 s in frames of motion
    i0=m.first_at(1.0); lag=sum(m.truth(i,1)['x']-ex[i] for i in range(i0,si))/(si-i0)/(400/fps)
    lagk=sum(m.truth(i,1)['x']-xs[i] for i in range(i0,si))/(si-i0)/(400/fps)
    return over,lag,lagk
for fps in (20,30):
    print('fps',fps)
    for mc,beta in [(0.5,0.5),(1.0,0.5),(1.0,1.0),(1.5,0.5),(1.5,1.0),(2.0,1.0),(1.0,3.0)]:
        st=[run_one('still',fps,s,mc,beta) for s in range(1,9)]
        mv=[run_one('stop',fps,s,mc,beta) for s in range(1,9)]
        print('  mincut %.1f beta %.1f: still jitter ratio euro %.2f (kalman %.2f) | stop overshoot %.3f h | walking lag %.2f frames (kalman %.2f)'%(mc,beta,max(a for a,b in st),max(b for a,b in st),max(o for o,_,_ in mv),max(l for _,l,_ in mv),max(k for _,_,k in mv)))
