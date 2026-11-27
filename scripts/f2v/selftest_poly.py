#!/usr/bin/env python3
"""3-f-retry selftest: poly cutout tile at OUTPUT density vs the old 4px/texel tile.

PIL-only, no JDK. Pins the contract the patch (poly-native-sampler.diff) claims:

  1. NEW tile -- patched Cell.render sizing: pw/ph = the element's own output
     pixel footprint (PPU = 60.5/16 px per model unit, Math.round), feeding the
     UNCHANGED per-pixel loop (ObjMesh.java @ 5f4ef06a lines 720-811, ported
     verbatim: pixel centre -> fan barycentric -> interpolated UV -> NEAREST
     texel x interpolated vertex colour).
  2. REF tile -- a DIRECT UV-SAMPLE reference of the native per-pixel math,
     written independently here with edge-function barycentric coordinates
     (mathematically the same weights, different op order).
  3. assert NEW == REF per pixel  (tile pixel == native screen pixel).
  4. print the old-vs-new boundary-difference count: the OLD tile (current
     texels*4 sizing, ObjMesh.java lines 695-705 ported verbatim) is resampled
     onto the NEW output grid the way the client does it -- GPU-style naive
     bilinear between texels (the "client's bilinear resample" receipt,
     ObjMesh.java ~line 762) -- and counted against NEW (== the truth leg).

Geometry: a 45-degree diamond quad (all four edges diagonal) on a synthetic
16x16 sprite with a unique per-texel pattern; a straight-edged square runs as
the control (evidence: straight edges nearly clean, diagonals race).
"""

import math
from PIL import Image


def round_half_up(x):
    # Java Math.round(double) == floor(x + 0.5) for our positive sizes
    return int(math.floor(x + 0.5))


def make_sprite():
    # unique per-texel pattern: any mis-step lands on a visibly wrong texel
    img = Image.new("RGBA", (16, 16))
    px = img.load()
    for y in range(16):
        for x in range(16):
            px[x, y] = ((17 * x + 9) % 256, (13 * y + 7) % 256,
                        (11 * x + 5 * y) % 256, 255)
    return img


SPRITE = make_sprite()

# per-vertex RGB multipliers (the 3-channel 'c' path; the >=4 channel opacity
# path is not exercised here)
COLORS = [[0.9, 0.8, 1.0], [1.0, 0.9, 0.8], [0.8, 1.0, 0.9], [1.0, 1.0, 1.0]]

# diagonal quad: 45-degree diamond in the Cell (ex,ey) plane, uv window [0,16]^2
DIAG = dict(P=[(8, 0), (16, 8), (8, 16), (0, 8)],
            UV=[(0, 0), (16, 0), (16, 16), (0, 16)])
# straight control (the real pipeline routes this shape to rectElement, so it
# never reaches Cell.render there -- shown only as the clean-edge baseline)
STRAIGHT = dict(P=[(0, 0), (16, 0), (16, 16), (0, 16)],
                UV=[(0, 0), (16, 0), (16, 16), (0, 16)])


# --- verbatim port of ObjMesh.java bary() (lines 826-835) --------------------
def bary(a, b, p0, p1, p2):
    d = (p1[1] - p2[1]) * (p0[0] - p2[0]) + (p2[0] - p1[0]) * (p0[1] - p2[1])
    if abs(d) < 1e-12:
        return None
    l0 = ((p1[1] - p2[1]) * (a - p2[0]) + (p2[0] - p1[0]) * (b - p2[1])) / d
    l1 = ((p2[1] - p0[1]) * (a - p2[0]) + (p0[0] - p2[0]) * (b - p2[1])) / d
    l2 = 1 - l0 - l1
    if l0 < -1e-9 or l1 < -1e-9 or l2 < -1e-9:
        return None
    return (l0, l1, l2)


def snap(v):
    # an interpolated coordinate within 1e-9 of a texel boundary IS the
    # boundary: measure-zero case that only wobbles with floating-point op
    # order (the verbatim Java-order loop vs the edge-function reference);
    # snapped in BOTH samplers so the strict per-pixel assert measures the
    # contract, not cross-ordering ulps. The Java loop itself stays unsnapped.
    r = round(v)
    return float(r) if abs(v - r) < 1e-9 else v


def old_size(P, UV):
    # verbatim port of the CURRENT sizing (ObjMesh.java lines 695-705)
    a0 = min(p[0] for p in P); a1 = max(p[0] for p in P)
    b0 = min(p[1] for p in P); b1 = max(p[1] for p in P)
    size = SPRITE.width
    texels = 1.0
    for i in range(len(P)):
        j = (i + 1) % len(P)
        dp = math.dist(P[i], P[j])
        if dp > 1e-6:
            duv = math.hypot(UV[j][0] - UV[i][0], UV[j][1] - UV[i][1])
            texels = max(texels, duv / 16 * size / dp)
    pw = max(2, min(120, math.ceil((a1 - a0) * texels * 4)))
    ph = max(2, min(120, math.ceil((b1 - b0) * texels * 4)))
    return pw, ph, texels


def new_size(P):
    # the PATCHED sizing (poly-native-sampler.diff): output density, Math.round
    a0 = min(p[0] for p in P); a1 = max(p[0] for p in P)
    b0 = min(p[1] for p in P); b1 = max(p[1] for p in P)
    ppu = 60.5 / 16
    pw = max(2, min(184, round_half_up((a1 - a0) * ppu)))
    ph = max(2, min(184, round_half_up((b1 - b0) * ppu)))
    return pw, ph, ppu


def render_tile(pw, ph, P, UV, bounds=None):
    # verbatim port of Cell.render() (ObjMesh.java lines 720-811) -- the loop
    # the patch does NOT touch; only the pw/ph fed into it changes. Returns
    # (tile, tex) with tex[j][i] = the sampled source texel (tx0, ty0).
    # bounds=None -> the polygon's own bbox (the legacy shape); the 14-e281
    # snap path passes the SNAPPED bounds (X/J ordered) here.
    w, h = SPRITE.size
    fh = w if (h > w and h % w == 0) else h          # frame-0 rule (779-780)
    if bounds is None:
        a0 = min(p[0] for p in P); a1 = max(p[0] for p in P)
        b0 = min(p[1] for p in P); b1 = max(p[1] for p in P)
    else:
        a0, a1, b0, b1 = bounds
    spx = SPRITE.load()
    out = [[(0, 0, 0, 0)] * pw for _ in range(ph)]
    tex = [[None] * pw for _ in range(ph)]
    for j in range(ph):
        b = b1 - (j + 0.5) / ph * (b1 - b0)          # 732
        for i in range(pw):
            a = a0 + (i + 0.5) / pw * (a1 - a0)      # 734
            for t in range(1, len(P) - 1):           # 735: fan (0, t, t+1)
                l = bary(a, b, P[0], P[t], P[t + 1])
                if l is None:
                    continue
                u = l[0] * UV[0][0] + l[1] * UV[t][0] + l[2] * UV[t + 1][0]
                v = l[0] * UV[0][1] + l[1] * UV[t][1] + l[2] * UV[t + 1][1]
                tx0 = min(w - 1, max(0, math.floor(snap(u / 16 * w))))   # 769,786
                ty0 = min(h - 1, max(0, math.floor(snap(v / 16 * fh))))  # 780,787
                r, g, bb, aa = spx[tx0, ty0]                       # 788 NEAREST
                tex[j][i] = (tx0, ty0)
                rgb = []
                for c, base in enumerate((r, g, bb)):
                    m = (l[0] * COLORS[0][c] + l[1] * COLORS[t][c]
                         + l[2] * COLORS[t + 1][c])                # 792
                    rgb.append(min(255, round_half_up(base * m)))  # 794-795
                out[j][i] = (rgb[0], rgb[1], rgb[2], aa)           # alpha kept
                break                                              # 806
    return out, tex


def ref_tile(pw, ph, P, UV, bounds=None):
    # INDEPENDENT implementation of the native per-pixel contract: edge
    # functions instead of the Java bary formula; same epsilon regime
    # (degenerate |2*area| < 1e-12 skip; normalized weights < -1e-9 reject).
    # Returns (tile, tex) like render_tile.
    w, h = SPRITE.size
    fh = w if (h > w and h % w == 0) else h
    if bounds is None:
        a0 = min(p[0] for p in P); a1 = max(p[0] for p in P)
        b0 = min(p[1] for p in P); b1 = max(p[1] for p in P)
    else:
        a0, a1, b0, b1 = bounds
    spx = SPRITE.load()

    def e2(ax, ay, bx, by, cx, cy):  # 2 * signed area of triangle (a, b, c)
        return (bx - ax) * (cy - ay) - (by - ay) * (cx - ax)

    out = [[(0, 0, 0, 0)] * pw for _ in range(ph)]
    tex = [[None] * pw for _ in range(ph)]
    for j in range(ph):
        b = b1 - (j + 0.5) / ph * (b1 - b0)
        for i in range(pw):
            a = a0 + (i + 0.5) / pw * (a1 - a0)
            for t in range(1, len(P) - 1):
                p0, p1, p2 = P[0], P[t], P[t + 1]
                area = e2(p0[0], p0[1], p1[0], p1[1], p2[0], p2[1])
                if abs(area) < 1e-12:
                    continue
                l0 = e2(a, b, p1[0], p1[1], p2[0], p2[1]) / area
                l1 = e2(p0[0], p0[1], a, b, p2[0], p2[1]) / area
                l2 = 1.0 - l0 - l1
                if l0 < -1e-9 or l1 < -1e-9 or l2 < -1e-9:
                    continue
                u = l0 * UV[0][0] + l1 * UV[t][0] + l2 * UV[t + 1][0]
                v = l0 * UV[0][1] + l1 * UV[t][1] + l2 * UV[t + 1][1]
                tx0 = min(w - 1, max(0, math.floor(snap(u / 16 * w))))
                ty0 = min(h - 1, max(0, math.floor(snap(v / 16 * fh))))
                r, g, bb, aa = spx[tx0, ty0]
                tex[j][i] = (tx0, ty0)
                r = min(255, round_half_up(r * (l0 * COLORS[0][0] + l1 * COLORS[t][0] + l2 * COLORS[t + 1][0])))
                g = min(255, round_half_up(g * (l0 * COLORS[0][1] + l1 * COLORS[t][1] + l2 * COLORS[t + 1][1])))
                bb = min(255, round_half_up(bb * (l0 * COLORS[0][2] + l1 * COLORS[t][2] + l2 * COLORS[t + 1][2])))
                out[j][i] = (r, g, bb, aa)
                break
    return out, tex


def naive_bilinear(tile, dw, dh):
    # GPU-style texture resample: dst centre -> src space, lerp the 4
    # neighbours (what a LINEAR-filtered draw does between texels)
    sh, sw = len(tile), len(tile[0])
    out = []
    for j in range(dh):
        sy = (j + 0.5) * sh / dh - 0.5
        sy = min(max(sy, 0.0), sh - 1.0)
        y0 = int(math.floor(sy)); y1 = min(y0 + 1, sh - 1); fy = sy - y0
        row = []
        for i in range(dw):
            sx = (i + 0.5) * sw / dw - 0.5
            sx = min(max(sx, 0.0), sw - 1.0)
            x0 = int(math.floor(sx)); x1 = min(x0 + 1, sw - 1); fx = sx - x0
            px = []
            for c in range(4):
                v00 = tile[y0][x0][c]; v01 = tile[y0][x1][c]
                v10 = tile[y1][x0][c]; v11 = tile[y1][x1][c]
                top = v00 + (v01 - v00) * fx
                bot = v10 + (v11 - v10) * fx
                px.append(round_half_up(top + (bot - top) * fy))
            row.append(tuple(px))
        out.append(row)
    return out


def diff_count(tile_a, tile_b, tol=8):
    n = 0
    for ja in range(len(tile_a)):
        for ia in range(len(tile_a[0])):
            if max(abs(x - y) for x, y in zip(tile_a[ja][ia], tile_b[ja][ia])) > tol:
                n += 1
    return n


def run(name, P, UV):
    pw_o, ph_o, texels = old_size(P, UV)
    pw_n, ph_n, ppu = new_size(P)
    old = render_tile(pw_o, ph_o, P, UV)[0]
    new, new_tex = render_tile(pw_n, ph_n, P, UV)
    ref, ref_tex = ref_tile(pw_n, ph_n, P, UV)

    # the CONTRACT: every output pixel samples the same SOURCE TEXEL the
    # independent native-math reference samples -- exact, no tolerance
    tex_bad = [(i, j) for j in range(ph_n) for i in range(pw_n)
               if new_tex[j][i] != ref_tex[j][i]]
    assert not tex_bad, f"{name}: NEW != REF texel pattern at {len(tex_bad)} px, " \
                        f"first {tex_bad[:5]} new={new_tex[tex_bad[0][1]][tex_bad[0][0]]} " \
                        f"ref={ref_tex[tex_bad[0][1]][tex_bad[0][0]]}"
    # the multiplied colour: same texel x same weights, so only the
    # round-half-up knife edge can differ, and only by 1 ulp of op order
    deltas = [abs(x - y) for j in range(ph_n) for i in range(pw_n)
              for x, y in zip(new[j][i], ref[j][i])]
    assert max(deltas) <= 1, f"{name}: NEW vs REF colour delta {max(deltas)} > 1"
    exact = sum(1 for j in range(ph_n) for i in range(pw_n) if new[j][i] == ref[j][i])

    # client-side resample of the OLD tile onto the output grid, two models:
    gpu = naive_bilinear(old, pw_n, ph_n)                 # GPU LINEAR filter
    old_img = Image.new("RGBA", (pw_o, ph_o))
    opx = old_img.load()
    for j in range(ph_o):
        for i in range(pw_o):
            opx[i, j] = old[j][i]
    pil_img = old_img.resize((pw_n, ph_n), Image.BILINEAR)  # PIL's AA filter
    pil = [[pil_img.load()[i, j] for i in range(pw_n)] for j in range(ph_n)]

    print(f"[{name}]")
    print(f"  sprite {SPRITE.width}x{SPRITE.height}, poly span "
          f"{max(p[0] for p in P) - min(p[0] for p in P)}x"
          f"{max(p[1] for p in P) - min(p[1] for p in P)} model units")
    print(f"  OLD: texels/unit={texels:.5f} -> density={texels * 4:.5f} px/unit, "
          f"tile {pw_o}x{ph_o} (cap 120)")
    print(f"  NEW: PPU={ppu:.5f} px/unit -> tile {pw_n}x{ph_n} (cap 184)")
    print(f"  assert NEW == direct UV-sample REF: texel pattern EXACT "
          f"({pw_n * ph_n} px, "
          f"{sum(1 for j in range(ph_n) for i in range(pw_n) if new[j][i][3] > 0)} inside), "
          f"colours max|delta|={max(deltas)} ({pw_n * ph_n - exact} px at the "
          f"round-half knife edge)")
    print(f"  boundary-diff OLD-resampled vs NEW (>8/channel): "
          f"gpu-bilinear {diff_count(gpu, new)} px, "
          f"PIL-bilinear {diff_count(pil, new)} px of {pw_n * ph_n}")
    return diff_count(gpu, new)


diag_n = run("diagonal diamond quad", DIAG["P"], DIAG["UV"])
straight_n = run("straight square control", STRAIGHT["P"], STRAIGHT["UV"])
print()
print(f"diagonal {diag_n} vs straight {straight_n} boundary-diff px "
      f"(evidence shape: diagonal >> straight)")

# === 14-e281 THE TILE-FIDELITY LAW: the wall channel's screen-grid snap ======
# The law (ObjMesh.java Cell ctor, proposals/tile-fidelity-law.md): for the
# wall channel's axis-aligned wall-facing polys the tile is sized to the
# SCREEN pixels the polygon actually spans (X0 = floor, pw = ceil - floor at
# the polygon's own depth J = -G), the element rect snaps to the same grid
# (bounds = X/J), and the raster samples the screen pixel centres mapped into
# the plane -- texel == pixel 1:1. This battery pins:
#   1. the grid law (spans 1.3/2.5/4/13/16/48 -> pw = ceil-floor, <= 184, the
#      snapped bounds project to integer px EXACTLY),
#   2. the contract: the snapped render == the independent edge-function REF
#      at the SAME snapped bounds (texel EXACT, colour <= 1),
#   3. the evidence: the snapped tile differs from the legacy round()-sized
#      tile resampled (the diamond's fractional edges),
#   4. the mirror law: J < 0 (the wall's sign) exercises the ordered bounds
#      on EVERY case (tile column i = screen px X1-1-i).

WALL_F_PX = 360.0 / math.tan(math.radians(35.0))   # 514.1333, the 720p contract
WALL_CONTENT_Z = 8.993                              # the e91j/e278 content plane
WALL_CAM_Z = 0.5


def wall_g(mz_plane):
    # the polygon's own depth -> its px-per-model-unit (the law's G)
    zw = WALL_CONTENT_Z + (16.0 - mz_plane) / 16.0
    return WALL_F_PX / ((zw - WALL_CAM_Z) * 16.0)


def snap_size(P, mz_plane=16.0):
    # verbatim port of the patched Cell sizing (the wall-channel gate branch)
    jx = jy = -wall_g(mz_plane)
    a0 = min(p[0] for p in P); a1 = max(p[0] for p in P)
    b0 = min(p[1] for p in P); b1 = max(p[1] for p in P)
    x0 = math.floor(min(jx * a0, jx * a1)); x1 = math.ceil(max(jx * a0, jx * a1))
    y0 = math.floor(min(jy * b0, jy * b1)); y1 = math.ceil(max(jy * b0, jy * b1))
    pw, ph = x1 - x0, y1 - y0
    bounds = (min(x0 / jx, x1 / jx), max(x0 / jx, x1 / jx),
              min(y0 / jy, y1 / jy), max(y0 / jy, y1 / jy))
    return pw, ph, bounds, jx


print()
print("[tile-fidelity law: the screen-grid snap]")
g16 = wall_g(16.0)
print(f"  wall G at mz=16: {g16:.5f} px/unit (the legacy ppu 3.78125, "
      f"delta {g16 - 60.5 / 16:+.5f})")

# 1. the grid law over the measured span table
for span in (1.3, 2.5, 4.0, 13.0, 16.0, 48.0):
    rect = dict(P=[(2.0, 2.0), (2.0 + span, 2.0), (2.0 + span, 2.0 + span), (2.0, 2.0 + span)],
                UV=[(0, 0), (16, 0), (16, 16), (0, 16)])
    pw, ph, bounds, jx = snap_size(rect["P"])
    lo = min(jx * 2.0, jx * (2.0 + span)); hi = max(jx * 2.0, jx * (2.0 + span))
    assert pw == math.ceil(hi) - math.floor(lo), f"span {span}: pw {pw} != ceil-floor"
    assert pw <= 184 and ph <= 184, f"span {span}: cap blown {pw}x{ph}"
    # the snapped bounds project to integer px exactly (the rect IS the grid)
    for edge in (bounds[0], bounds[1]):
        assert abs(jx * edge - round(jx * edge)) < 1e-9, f"span {span}: rect edge off-grid"
    print(f"  span {span:5.1f}: J*span {jx * span:8.3f} px -> tile {pw}x{ph}, "
          f"rect [{bounds[0]:.4f}, {bounds[1]:.4f}] on-grid")

# 2+3+4. the contract + the evidence on the real fixtures at mz=16
def snap_battery(name, P, UV):
    pw, ph, bounds, jx = snap_size(P)
    snapped, snapped_tex = render_tile(pw, ph, P, UV, bounds=bounds)
    ref, ref_tex = ref_tile(pw, ph, P, UV, bounds=bounds)
    tex_bad = [(i, j) for j in range(ph) for i in range(pw)
               if snapped_tex[j][i] != ref_tex[j][i]]
    assert not tex_bad, f"{name}: SNAPPED != REF at {len(tex_bad)} px"
    deltas = [abs(x - y) for j in range(ph) for i in range(pw)
              for x, y in zip(snapped[j][i], ref[j][i])]
    assert max(deltas) <= 1, f"{name}: SNAPPED vs REF colour delta {max(deltas)} > 1"
    # the legacy tile (round at the FIXED ppu) resampled onto the snapped grid
    pw_o, ph_o, _ = new_size(P)
    legacy = render_tile(pw_o, ph_o, P, UV)[0]
    gpu = naive_bilinear(legacy, pw, ph)
    n = diff_count(gpu, snapped)
    print(f"  {name}: snapped tile {pw}x{ph} (bounds a[{bounds[0]:.3f},{bounds[1]:.3f}] "
          f"b[{bounds[2]:.3f},{bounds[3]:.3f}]) == REF texel EXACT, colours max|d|={max(deltas)}; "
          f"legacy-resampled boundary-diff {n} px of {pw * ph}")
    return n

snap_diag = snap_battery("diagonal diamond @mz16", DIAG["P"], DIAG["UV"])
snap_straight = snap_battery("straight square @mz16", STRAIGHT["P"], STRAIGHT["UV"])
assert snap_diag > 0 or snap_straight > 0, \
    "the evidence assert died: the snapped tile == the legacy tile everywhere"
print(f"  evidence: the snap MOVES the lattice (boundary-diff {snap_diag}/{snap_straight} px) "
      f"-- the harness sees the disease the law removes")
print("TILE-FIDELITY LAW BATTERY OK")
