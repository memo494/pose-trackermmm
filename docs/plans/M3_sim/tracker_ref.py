#!/usr/bin/env python3
"""Reference model of the M3 tracker: the lead's analysis tool, NOT production code and not run by CI.

It mirrors core/tracking (HeadMeasurement, HeadPointEstimator, KalmanAxis, AssociationCost, the cost-limit
assignment, DetectionDedupe), the test tools SyntheticScene and TrackingMetrics, and the lifecycle of
docs/plans/M3.md (Consults C1 to C3). The T5 thresholds were measured with it (scenarios.py). `Tracker.update`
is the algorithm T6 ports to Kotlin. Python floats are doubles and `random.Random` is not `java.util.Random`,
so single numbers differ from the Kotlin tests; the thresholds leave room for that.

Run: python3 scenarios.py"""
import math, random

HEAD = 5; KP = 17; INF = float('inf'); NAN = float('nan')
OFFX = [0.0, -0.03, 0.03, -0.07, 0.07, -0.15, 0.15, -0.18, 0.18, -0.17, 0.17, -0.1, 0.1, -0.1, 0.1, -0.1, 0.1]
OFFY = [0.02, -0.01, -0.01, 0.0, 0.0, 0.2, 0.2, 0.4, 0.4, 0.55, 0.55, 0.5, 0.5, 0.7, 0.7, 0.88, 0.88]


class Cfg:
    def __init__(s, **kw):
        s.scoreThreshold = 0.15
        s.headEnter = 0.5; s.headLeave = 0.3; s.headAlpha = 0.2; s.headFallbackTop = 0.10
        s.headNoise = 0.03; s.headFallbackNoise = 0.10; s.headMinMeanConf = 0.05
        s.gateChi2 = 9.21; s.wIou = 0.5; s.wHead = 0.5; s.headScale = 0.5; s.dedupeIou = 0.7
        # lifecycle and filters (Consults C1 and C3); names follow the TrackerConfig fields of T5
        s.highScore = 0.5; s.birthScore = 0.25; s.confirmHits = 3; s.idlePromoteMs = 400.0
        s.maxLostMs = 800.0; s.maxDtMs = 250.0; s.inflate = 10.0; s.missTauMs = 300.0
        s.accelSigma = 8.0; s.heightAccelSigma = 2.0; s.heightNoise = 0.05; s.initVelSigma = 2.0
        s.matchMaxCost = 0.7; s.lowMatchMaxCost = 0.6; s.exitMargin = 0.1
        for k, v in kw.items():
            assert hasattr(s, k), k
            setattr(s, k, v)


# ---------------- scene (mirror of SyntheticScene) ----------------
class Person:
    def __init__(s, pid, path, score=None, dropouts=(), kpDrop=(), dups=()):
        s.id = pid; s.path = path; s.score = score or (lambda t: 0.9)
        s.dropouts = dropouts; s.kpDrop = kpDrop; s.dups = dups

    def at(s, t):
        p = s.path
        if t < p[0][0] or t > p[-1][0]: return None
        if len(p) == 1: return p[0][1:]
        u = 1
        while u < len(p) - 1 and p[u][0] < t: u += 1
        a = p[u - 1]; b = p[u]; f = (t - a[0]) / (b[0] - a[0])
        return tuple(a[i] + f * (b[i] - a[i]) for i in (1, 2, 3))


def inI(t, iv): return iv[0] <= t < iv[1]


def make_scene(people, dur, fps=20.0, seed=1, jitter=0.0, gaps=(), fpos=(), kpNoise=0.0, boxNoise=0.0,
               W=1356, H=610, startNs=5_000_000_000):
    rnd = random.Random(seed)

    def g(sig): return rnd.gauss(0, 1) * sig if sig > 0 else 0.0

    def mk(hx, hy, h, score, low):
        kx = []; ky = []; kc = []; ks = kpNoise * h
        for k in range(KP):
            kx.append(hx + OFFX[k] * h + g(ks)); ky.append(hy + OFFY[k] * h + g(ks)); kc.append(0.1 if k in low else 0.9)
        bs = boxNoise * h; top = hy - 0.1 * h
        left = hx - 0.2 * h + g(bs); topn = top + g(bs); right = hx + 0.2 * h + g(bs); bottom = top + h + g(bs)
        return dict(l=left, t=topn, r=right, b=bottom, s=score, kx=kx, ky=ky, kc=kc)

    frames = []; durNs = round(dur * 1e9); nom = 1e9 / fps; tNs = 0
    while tNs < durNs:
        if not any(round(a * 1e9) <= tNs < round(b * 1e9) for a, b in gaps):
            t = tNs / 1e9; dets = []; truth = []
            for p in people:
                at = p.at(t)
                if at is None: continue
                hx, hy, h = at
                if any(inI(t, iv) for iv in p.dropouts):
                    truth.append(dict(id=p.id, x=hx, y=hy, h=h, mx=NAN, my=NAN)); continue
                low = set()
                for iv, ks in p.kpDrop:
                    if inI(t, iv): low |= set(ks)
                sc = min(1.0, max(0.0, p.score(t)))
                d = mk(hx, hy, h, sc, low); dets.append(d)
                if any(inI(t, iv) for iv in p.dups):
                    sh = 0.03 * h
                    dets.append(dict(l=d['l'] + sh, t=d['t'] + sh, r=d['r'] + sh, b=d['b'] + sh, s=max(0, d['s'] - 0.05),
                                     kx=[x + sh for x in d['kx']], ky=[y + sh for y in d['ky']], kc=list(d['kc'])))
                truth.append(dict(id=p.id, x=hx, y=hy, h=h, mx=sum(d['kx'][:5]) / 5, my=sum(d['ky'][:5]) / 5))
            for (iv, fx, fy, fh, fs) in fpos:
                if inI(t, iv): dets.append(mk(fx, fy, fh, min(1, max(0, fs)), set(range(5))))
            frames.append(dict(i=len(frames), t=t, ns=startNs + tNs, dets=dets, truth=truth, W=W, H=H))
        fac = 1.0 + (rnd.random() * 2 - 1) * jitter if jitter > 0 else 1.0
        tNs += max(1, round(nom * fac))
    return frames


# ---------------- core pieces ----------------
class K:
    def reset(s, x, pp, pv): s.x = x; s.v = 0.0; s.a = pp; s.b = 0.0; s.c = pv

    def predict(s, dt, sa, inf=1.0):
        if not (dt > 0): return
        q = sa * sa * inf; d2 = dt * dt
        s.x += s.v * dt
        s.a, s.b, s.c = s.a + dt * (2 * s.b + dt * s.c) + q * d2 * d2 / 4, s.b + dt * s.c + q * d2 * dt / 2, s.c + q * d2

    def update(s, z, r):
        S = s.a + r; ga = s.a / S; gv = s.b / S; i = z - s.x
        s.x += ga * i; s.v += gv * i
        s.a, s.b, s.c = s.a * r / S, s.b * r / S, s.c - s.b * s.b / S

    def decay(s, dt, tau):
        if dt > 0 and tau > 0: s.v *= math.exp(-dt / tau)


def head_meas(d, c):
    h = d['b'] - d['t']
    if not (h > 0): return None
    xs = ys = ss = 0.0; n = 0
    for k in range(HEAD):
        if d['kc'][k] >= c.headEnter: xs += d['kx'][k]; ys += d['ky'][k]; ss += d['kc'][k]; n += 1
    if n > 0: return (xs / n, ys / n, (c.headNoise * h) ** 2 / max(ss / n, c.headMinMeanConf), False)
    return ((d['l'] + d['r']) / 2, d['t'] + c.headFallbackTop * h, (c.headFallbackNoise * h) ** 2, True)


class HeadEst:
    def __init__(s, c): s.c = c; s.reset()

    def reset(s): s.vis = [False] * 5; s.lrn = [False] * 5; s.ox = [0.0] * 5; s.oy = [0.0] * 5; s.n = 0

    def update(s, d):
        c = s.c; h = d['b'] - d['t']
        if not (h > 0): return None
        for k in range(5):
            s.vis[k] = d['kc'][k] >= (c.headLeave if s.vis[k] else c.headEnter)
        full = all(s.vis)
        if full:
            mx = sum(d['kx'][:5]) / 5; my = sum(d['ky'][:5]) / 5
            for k in range(5):
                tx = (d['kx'][k] - mx) / h; ty = (d['ky'][k] - my) / h
                if s.lrn[k]: s.ox[k] += c.headAlpha * (tx - s.ox[k]); s.oy[k] += c.headAlpha * (ty - s.oy[k])
                else: s.ox[k] = tx; s.oy[k] = ty; s.lrn[k] = True; s.n += 1
        raw = s.n == 0; sw = sx = sy = 0.0; used = 0
        for k in range(5):
            if s.vis[k] and (s.lrn[k] or raw):
                w = d['kc'][k]; sw += w; sx += w * (d['kx'][k] - s.ox[k] * h); sy += w * (d['ky'][k] - s.oy[k] * h); used += 1
        if used == 0 or not (sw > 0):
            return ((d['l'] + d['r']) / 2, d['t'] + c.headFallbackTop * h, (c.headFallbackNoise * h) ** 2, True)
        cx = sx / sw; cy = sy / sw
        if not full:
            for k in range(5):
                if s.vis[k] and not s.lrn[k]:
                    s.ox[k] = (d['kx'][k] - cx) / h; s.oy[k] = (d['ky'][k] - cy) / h; s.lrn[k] = True; s.n += 1
        return (cx, cy, (c.headNoise * h) ** 2 / max(sw / used, c.headMinMeanConf), False)


def iou(a, b):
    aw = a[2] - a[0]; ah = a[3] - a[1]; bw = b[2] - b[0]; bh = b[3] - b[1]
    if aw <= 0 or ah <= 0 or bw <= 0 or bh <= 0: return 0.0
    iw = min(a[2], b[2]) - max(a[0], b[0]); ih = min(a[3], b[3]) - max(a[1], b[1])
    if iw <= 0 or ih <= 0: return 0.0
    inter = iw * ih; return inter / (aw * ah + bw * bh - inter)


def assoc_cost(tb, db, px, py, mx, my, sx, sy, th, c):
    if sx <= 0 or sy <= 0 or th <= 0: return INF
    dx = mx - px; dy = my - py
    if not (dx * dx / sx + dy * dy / sy <= c.gateChi2): return INF
    return c.wIou * (1 - iou(tb, db)) + c.wHead * min(1.0, math.hypot(dx, dy) / (c.headScale * th))


def assign(cost, rows, cols, maxCost):
    best = [-1.0, [-1] * rows]; r2c = [-1] * rows

    def rec(i, used, gain):
        if i == rows:
            if gain > best[0] + 1e-12: best[0] = gain; best[1] = list(r2c)
            return
        r2c[i] = -1; rec(i + 1, used, gain)
        for j in range(cols):
            cst = cost[i][j]
            if j not in used and cst < maxCost:
                r2c[i] = j; used.add(j); rec(i + 1, used, gain + (maxCost - cst)); used.discard(j); r2c[i] = -1

    rec(0, set(), 0.0)
    return best[1]


def bx(d): return (d['l'], d['t'], d['r'], d['b'])


def dedupe(dets, c):
    order = sorted(range(len(dets)), key=lambda i: (-dets[i]['s'], i)); kept = []
    for i in order:
        if any(iou(bx(dets[i]), bx(dets[j])) >= c.dedupeIou for j in kept): continue
        kept.append(i)
    return sorted(kept)


class Track: pass


class Tracker:
    def __init__(s, c): s.c = c; s.tracks = []; s.nextId = 1; s.lastNs = None; s.log = []

    def box(s, t):
        h = t.kh.x; return (t.kx.x + t.ol * h, t.ky.x + t.ot * h, t.kx.x + t.orr * h, t.ky.x + t.ob * h)

    def update(s, f):
        c = s.c; dte = 0.0
        if s.lastNs is not None:
            dt = (f['ns'] - s.lastNs) / 1e9
            if dt <= 0: return s.view()
            gap = dt * 1000 > c.maxDtMs; dte = min(dt, c.maxDtMs / 1000)
            for t in s.tracks:
                inf = c.inflate if gap else 1.0
                h = t.kh.x
                t.kx.predict(dte, c.accelSigma * h, inf); t.ky.predict(dte, c.accelSigma * h, inf)
                t.kh.predict(dte, c.heightAccelSigma * h, inf)
        s.lastNs = f['ns']; dets = f['dets']
        keep = [i for i in dedupe(dets, c) if dets[i]['s'] >= c.scoreThreshold]
        meas = {i: head_meas(dets[i], c) for i in keep}
        keep = [i for i in keep if meas[i] is not None]
        high = [i for i in keep if dets[i]['s'] >= c.highScore]; low = [i for i in keep if dets[i]['s'] < c.highScore]
        matched = {}

        def stage(tidx, didx, maxCost):
            if not tidx or not didx: return
            cm = []
            for ti in tidx:
                t = s.tracks[ti]; tb = s.box(t); row = []
                for di in didx:
                    m = meas[di]
                    row.append(assoc_cost(tb, bx(dets[di]), t.kx.x, t.ky.x, m[0], m[1], t.kx.a + m[2], t.ky.a + m[2], t.kh.x, c))
                cm.append(row)
            for a, j in enumerate(assign(cm, len(tidx), len(didx), maxCost)):
                if j >= 0: matched[tidx[a]] = didx[j]; s.log.append((f['i'], s.tracks[tidx[a]].id, cm[a][j]))

        stage(list(range(len(s.tracks))), high, c.matchMaxCost)
        stage([ti for ti in range(len(s.tracks)) if ti not in matched], low, c.lowMatchMaxCost)
        usedD = set(matched.values()); survivors = []
        for ti, t in enumerate(s.tracks):
            if ti in matched:
                d = dets[matched[ti]]; p = t.est.update(d); h = d['b'] - d['t']
                t.kx.update(p[0], p[2]); t.ky.update(p[1], p[2]); t.kh.update(h, (c.heightNoise * t.kh.x) ** 2)
                t.ol = (d['l'] - p[0]) / h; t.ot = (d['t'] - p[1]) / h; t.orr = (d['r'] - p[0]) / h; t.ob = (d['b'] - p[1]) / h
                t.hits += 1; t.lostMs = 0.0; t.lost = False; t.score = d['s']; t.est_flag = p[3]
                if t.state == 'T' and t.hits >= c.confirmHits: t.state = 'C'
                survivors.append(t)
            else:
                if t.state == 'T': continue
                t.lostMs += dte * 1000; t.lost = True; tau = c.missTauMs / 1000
                t.kx.decay(dte, tau); t.ky.decay(dte, tau); t.kh.decay(dte, tau)
                if t.lostMs > c.maxLostMs: continue
                m = c.exitMargin * t.kh.x
                if t.kx.x < -m or t.kx.x > f['W'] + m or t.ky.x < -m or t.ky.x > f['H'] + m: continue
                survivors.append(t)
        s.tracks = survivors
        for i in keep:
            if i in usedD or dets[i]['s'] < c.birthScore: continue
            d = dets[i]; t = Track(); t.id = s.nextId; s.nextId += 1; t.state = 'T'; t.hits = 1; t.lostMs = 0.0; t.lost = False
            t.est = HeadEst(c); p = t.est.update(d); h = d['b'] - d['t']; vv = (c.initVelSigma * h) ** 2
            t.kx = K(); t.ky = K(); t.kh = K()
            t.kx.reset(p[0], p[2], vv); t.ky.reset(p[1], p[2], vv); t.kh.reset(h, (c.heightNoise * h) ** 2, vv)
            t.ol = (d['l'] - p[0]) / h; t.ot = (d['t'] - p[1]) / h; t.orr = (d['r'] - p[0]) / h; t.ob = (d['b'] - p[1]) / h
            t.score = d['s']; t.est_flag = p[3]
            s.tracks.append(t)
        return s.view()

    def on_idle(s, idleMs):
        if idleMs >= s.c.idlePromoteMs:
            for t in s.tracks:
                if t.state == 'T': t.state = 'C'
        return s.view()

    def view(s): return [dict(id=t.id, x=t.kx.x, y=t.ky.x, st=t.state, lost=t.lost, h=t.kh.x, vx=t.kx.v, vy=t.ky.v) for t in s.tracks]


# ---------------- metrics (mirror of TrackingMetrics) ----------------
class Metrics:
    def __init__(s, frames): s.f = frames; s.m = [s._match(fr) for fr in frames]

    def _match(s, fr):
        cand = []
        for p in fr['truth']:
            for ti, tr in enumerate(fr['tracks']):
                d = math.hypot(tr[1] - p['x'], tr[2] - p['y'])
                if d <= 0.5 * p['h']: cand.append((d, p['id'], ti))
        cand.sort(); res = {}; used = set()
        for d, pid, ti in cand:
            if pid in res or ti in used: continue
            res[pid] = ti; used.add(ti)
        return res

    def truth(s, i, pid):
        if i < 0 or i >= len(s.f): return None
        for p in s.f[i]['truth']:
            if p['id'] == pid: return p
        return None

    def track(s, i, pid):
        if i < 0 or i >= len(s.f): return None
        ti = s.m[i].get(pid)
        return None if ti is None else s.f[i]['tracks'][ti]

    def first_at(s, t):
        for i, fr in enumerate(s.f):
            if fr['t'] >= t - 1e-9: return i
        return len(s.f)

    def tid(s, i, pid):
        tr = s.track(i, pid); return None if tr is None else tr[0]

    def idSwitches(s, pid):
        sw = 0; prev = None
        for i in range(len(s.f)):
            tr = s.track(i, pid)
            if tr is None: continue
            if prev is not None and prev != tr[0]: sw += 1
            prev = tr[0]
        return sw

    def coverage(s, pid, a=0, b=None):
        b = len(s.f) if b is None else b; pres = mt = 0
        for i in range(max(0, a), min(b, len(s.f))):
            if s.truth(i, pid) is None: continue
            pres += 1
            if s.track(i, pid) is not None: mt += 1
        return NAN if pres == 0 else mt / pres

    def ghostFrames(s):
        g = 0
        for fr in s.f:
            for tr in fr['tracks']:
                best = None; bd = 1e300
                for p in fr['truth']:
                    d = math.hypot(tr[1] - p['x'], tr[2] - p['y'])
                    if d < bd: bd = d; best = p
                if best is None or bd > 0.5 * best['h']: g += 1
        return g

    def err(s, pid, i):
        p = s.truth(i, pid); tr = s.track(i, pid)
        if p is None or tr is None: return NAN
        return math.hypot(tr[1] - p['x'], tr[2] - p['y']) / p['h']

    def maxErr(s, pid, a=0, b=None):
        b = len(s.f) if b is None else b; w = NAN
        for i in range(max(0, a), min(b, len(s.f))):
            e = s.err(pid, i)
            if e == e and (w != w or e > w): w = e
        return w

    def series(s, pid, ax, a=0, b=None):
        b = len(s.f) if b is None else b; out = []
        for i in range(max(0, a), min(b, len(s.f))):
            tr = s.track(i, pid); out.append(NAN if tr is None else tr[1 + ax])
        return out

    def raw(s, pid, ax, a=0, b=None):
        b = len(s.f) if b is None else b; out = []
        for i in range(max(0, a), min(b, len(s.f))):
            p = s.truth(i, pid); out.append(NAN if p is None else (p['mx'], p['my'])[ax])
        return out

    def overshoot(s, pid, stopT):
        si = s.first_at(stopT); stop = s.truth(si, pid)
        if stop is None: return NAN
        dx = dy = ln = 0.0; b = si - 1
        while b >= 0 and ln < 1e-6:
            e = s.truth(b, pid)
            if e is None: return NAN
            dx = stop['x'] - e['x']; dy = stop['y'] - e['y']; ln = math.hypot(dx, dy); b -= 1
        if ln < 1e-6: return NAN
        best = 0.0
        for i in range(si, len(s.f)):
            tr = s.track(i, pid)
            if tr is None: continue
            best = max(best, ((tr[1] - stop['x']) * dx + (tr[2] - stop['y']) * dy) / ln / stop['h'])
        return best

    def lagFrames(s, pid, a=0, b=None):
        b = len(s.f) if b is None else b; ls = ss = 0.0; n = 0
        for i in range(max(2, a), min(b, len(s.f))):
            o = s.truth(i - 2, pid); m = s.truth(i - 1, pid); nw = s.truth(i, pid); tr = s.track(i, pid)
            if o is None or m is None or nw is None or tr is None: continue
            d0 = s.f[i - 1]['t'] - s.f[i - 2]['t']; d1 = s.f[i]['t'] - s.f[i - 1]['t']
            if d0 <= 0 or d1 <= 0: continue
            sx = nw['x'] - m['x']; sy = nw['y'] - m['y']; st = math.hypot(sx, sy)
            if st < 1e-6: continue
            v0 = ((m['x'] - o['x']) / d0, (m['y'] - o['y']) / d0); v1 = (sx / d1, sy / d1)
            if math.hypot(v1[0] - v0[0], v1[1] - v0[1]) > 1e-6 * math.hypot(*v1): continue
            ls += ((nw['x'] - tr[1]) * sx + (nw['y'] - tr[2]) * sy) / st; ss += st; n += 1
        return NAN if n == 0 else ls / ss


def jitterRms(x):
    q = 0.0; n = 0
    for i in range(1, len(x) - 1):
        d = x[i + 1] - 2 * x[i] + x[i - 1]
        if d != d: continue
        q += d * d; n += 1
    return NAN if n == 0 else math.sqrt(q / n)


def run(frames, cfg, idle=None):
    """idle: dict frameIndex -> list of idleMs calls made after that frame. Returns (Metrics, per-frame all-track views, tracker)."""
    tr = Tracker(cfg); mf = []; allv = []
    for fr in frames:
        v = tr.update(fr)
        if idle and fr['i'] in idle:
            for ms in idle[fr['i']]: v = tr.on_idle(ms)
        allv.append(v)
        mf.append(dict(t=fr['t'], truth=fr['truth'], tracks=[(t['id'], t['x'], t['y']) for t in v if t['st'] == 'C']))
    return Metrics(mf), allv, tr
