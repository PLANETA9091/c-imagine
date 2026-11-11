#!/usr/bin/env python3
"""Run-298 RED forensics via resolved.tsv state->mesh mapping.

For every RED cell family: histogram of captured light pairs (cols 39/40) and
flat shade RGB (cols 23-34) over the family's meshes. Flags the discriminating
signal between 'tint helped' (machine bodies) and 'tint broke' (nixie art).
"""
import json, re
from collections import Counter, defaultdict

CM = "/tmp/cm298/seed-pack"
by_state = defaultdict(list)
for line in open(f"{CM}/f2v/visual-gallery.resolved.tsv"):
    if line.startswith("#"):
        continue
    c = line.split("\t")
    if len(c) >= 3 and "#q" in c[0]:
        st, qh = c[0].split("#q", 1)
        by_state[st].append(qh)

def mesh_stats(qh):
    p = f"{CM}/quads/{qh}.tsv"
    pairs = Counter()
    shades = Counter()
    n = 0
    try:
        lines = open(p).read().splitlines()
    except FileNotFoundError:
        return pairs, shades, 0
    for line in lines:
        c = line.rstrip("\n").split("\t")
        if len(c) < 41:
            continue
        n += 1
        pairs[(c[39], c[40])] += 1
        rgbs = [float(v) for v in c[23:35]]
        lo, hi = min(rgbs), max(rgbs)
        if hi - lo < 0.02:
            shades[round((lo + hi) / 2, 2)] += 1
        else:
            shades["grad"] += 1
    return pairs, shades, n

fam = defaultdict(lambda: {"cells": 0, "meshes": {}, "pairs": Counter(), "shades": Counter()})
for r in json.load(open("/tmp/red_rows.json")):
    st = r["state"]
    m = re.match(r"([a-z0-9_]+):([a-z0-9_]+)", st)
    if not m:
        continue
    key = (m.group(1), m.group(2))
    f = fam[key]
    f["cells"] += 1
    for qh in by_state.get(st, ["MISSING"]):
        if qh not in f["meshes"]:
            f["meshes"][qh] = True

out = []
for (mod, f), d in fam.items():
    pairs = Counter()
    shades = Counter()
    for qh in list(d["meshes"])[:40]:
        if qh == "MISSING":
            pairs["NO-MESH"] += 1
            continue
        pr, sh, n = mesh_stats(qh)
        pairs.update(pr)
        shades.update(sh)
    out.append((d["cells"], mod, f, pairs, shades))

out.sort(reverse=True)
for cells, mod, f, pairs, shades in out[:34]:
    print(f"### {mod}:{f}  cells={cells}")
    print("    pairs:", dict(pairs.most_common(5)))
    print("    shades:", dict(shades.most_common(6)))
