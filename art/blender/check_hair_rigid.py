"""Is the hair rigidly attached to the skull, in a rotation-invariant sense?

THE PREVIOUS MEASUREMENT WAS WRONG and produced three bogus diagnoses. It compared the
component-wise offset (hair_centroid - head_centroid). Under a rotation about the neck, a point
further from the joint travels further, so that offset ROTATES and its components change even when
the two are welded together. It reported "detaching" for geometry that was rigidly bound by
construction.

Distance is invariant under rotation and translation. If the hair is rigidly attached to the skull,
the distance between a hair point and a skull point is constant for every frame, whatever the head
does. That is the test.
"""
import math
import struct

d = open(r"F:\AndroidStudioProjects\Prism\app\src\main\assets\3dmodels\Aether.pskn", "rb").read()
off = 4
ver, bones, intro, loop, fps, nb = struct.unpack_from("<IIIIfI", d, off)
off += 24
S = 40
batches = {}
for _ in range(nb):
    (ln,) = struct.unpack_from("<H", d, off); off += 2
    name = d[off:off + ln].decode(); off += ln
    (lt,) = struct.unpack_from("<H", d, off); off += 2 + lt
    off += 1
    vc, ic = struct.unpack_from("<II", d, off); off += 8
    batches[name] = (vc, off)
    off += vc * S + ic * 4
anim = off


def skin(frame, vstart, v):
    base = vstart + v * S
    px, py, pz = struct.unpack_from("<3f", d, base)
    idx = struct.unpack_from("<4B", d, base + 32)
    wt = struct.unpack_from("<4B", d, base + 36)
    tot = sum(wt)
    if tot == 0:
        return (px, py, pz)
    ox = oy = oz = 0.0
    for k in range(4):
        if wt[k] == 0:
            continue
        w = wt[k] / tot
        m = struct.unpack_from("<12f", d, anim + (frame * bones + idx[k]) * 48)
        ox += w * (m[0] * px + m[1] * py + m[2] * pz + m[3])
        oy += w * (m[4] * px + m[5] * py + m[6] * pz + m[7])
        oz += w * (m[8] * px + m[9] * py + m[10] * pz + m[11])
    return (ox, oy, oz)


def dominant_slot(vstart, v):
    base = vstart + v * S
    idx = struct.unpack_from("<4B", d, base + 32)
    wt = struct.unpack_from("<4B", d, base + 36)
    best = max(range(4), key=lambda k: wt[k])
    return idx[best], wt[best] / max(1, sum(wt))


def pick(name, cap, only_slot=None, purity=0.99):
    """Sample vertices, optionally only those almost entirely driven by one slot."""
    vc, vs = batches[name]
    chosen = []
    step = max(1, vc // (cap * 8))
    for v in range(0, vc, step):
        if only_slot is not None:
            slot, share = dominant_slot(vs, v)
            if slot != only_slot or share < purity:
                continue
        chosen.append(v)
        if len(chosen) >= cap:
            break
    return vs, chosen


def centroid(vs, verts, frame):
    pts = [skin(frame, vs, v) for v in verts]
    n = len(pts)
    return tuple(sum(p[i] for p in pts) / n for i in range(3))


def dist(a, b):
    return math.sqrt(sum((a[i] - b[i]) ** 2 for i in range(3)))


HAIR = "Hair_Transparency_Transparency"
HEAD = "Skin_Head"

# The skull cap: head vertices driven almost entirely by the same bone the hair now rides.
hair_vs, hair_v = pick(HAIR, 300)
skull_vs, skull_v = pick(HEAD, 300, only_slot=138)
jaw_vs, jaw_v = pick(HEAD, 300)          # the whole head, including bones that move on their own

print("sampled %d hair verts, %d skull-cap verts (pure slot 138), %d head verts (any)"
      % (len(hair_v), len(skull_v), len(jaw_v)))
print()
print("  frame   |hair-skull| (cm)   change from frame 0 (cm)")
base = None
worst = 0.0
for f in (0, 10, 20, 40, 74, 75, 100, 150, 200, 300, 434):
    h = centroid(hair_vs, hair_v, f)
    s = centroid(skull_vs, skull_v, f)
    dd = dist(h, s) * 100
    if base is None:
        base = dd
    delta = abs(dd - base)
    worst = max(worst, delta)
    print("  %5d   %14.4f   %14.4f %s" % (f, dd, delta, "  <== MOVED" if delta > 0.1 else ""))

print()
print("worst change in hair-to-skull distance across the animation: %.4f cm" % worst)
print("VERDICT:", "RIGIDLY ATTACHED" if worst < 0.1 else "DETACHING")
