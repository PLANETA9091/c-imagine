#!/usr/bin/env python3
"""Measure the iron-block px width in a probe shot: crop the expected band,
find the block edges via the iron texture's brightness vs sky, print F."""
import numpy as np
from PIL import Image
import sys

for p in sys.argv[1:]:
    a = np.asarray(Image.open(p).convert("RGB")).astype(int)
    r, g, b = a[:, :, 0], a[:, :, 1], a[:, :, 2]
    # iron blocks: light gray, LOW saturation, brightness 140-220, ABOVE the grass
    gray = (abs(r - g) < 12) & (abs(g - b) < 12) & (r > 130) & (r < 235)
    # the blocks sit above the horizon band: scan rows 150..380 only
    band = gray[150:380, :]
    colcnt = band.sum(axis=0)
    cols = np.where(colcnt > 40)[0]
    if len(cols) == 0:
        print(f"{p}: no gray content found in the band")
        continue
    # group into runs (blocks), gaps > 8 px separate runs
    runs = []
    start = cols[0]
    prev = cols[0]
    for c in cols[1:]:
        if c - prev > 8:
            runs.append((start, prev))
            start = c
        prev = c
    runs.append((start, prev))
    widths = [b - a + 1 for a, b in runs]
    print(f"{p}: {len(runs)} gray runs, widths={widths}")
