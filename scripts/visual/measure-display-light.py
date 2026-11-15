#!/usr/bin/env python3
"""Labeled native diagnostic crops; reports measurements, never a parity gate."""
import argparse
import json
import math
from pathlib import Path
import statistics
from PIL import Image

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('source', type=Path)
parser.add_argument('output', type=Path)
parser.add_argument('--zero-light', action='store_true',
                    help='B was changed to block0/sky0 for the second capture')
args = parser.parse_args()
source, output = args.source, args.output
image = Image.open(source).convert('RGB')
if image.size != (1280, 720):
    raise ValueError(f'Unexpected diagnostic viewport: {image.size}')
output.mkdir(parents=True, exist_ok=True)
focal = 360 / math.tan(math.radians(35))
pitch = math.radians(6)
# Native camera at (0.5,-60+1.62,14.5), facing north. The visible front
# face of each unit cube is at z=4, irrespective of entity light position.
dy, distance = -59.5 - (-60 + 1.62), 14.5 - 4
forward = distance * math.cos(pitch) - dy * math.sin(pitch)
vertical = dy * math.cos(pitch) + distance * math.sin(pitch)
labels = [('A', -5.5, 'opaque/no override'), ('B', -1.5, 'opaque/block0 sky15'),
          ('C', 2.5, 'opaque/block15 sky15'), ('D', 6.5, 'air/block0 sky15')]
if args.zero_light:
    labels[1] = ('B', -1.5, 'opaque/block0 sky0')
receipt = {'diagnostic_only': True, 'source': source.name,
           'zero_light_override': args.zero_light, 'samples': {}}
for label, x, condition in labels:
    cx = round(640 + focal * (x - .5) / forward)
    cy = round(360 - focal * vertical / forward)
    box = (cx - 6, cy - 6, cx + 6, cy + 6)
    crop = image.crop(box)
    crop.save(output / f'{label}.png')
    pixels = list(crop.getdata())
    rgb = [statistics.mean(p[c] for p in pixels) for c in range(3)]
    luma = statistics.mean(sum(p) / 3 for p in pixels)
    receipt['samples'][label] = {'condition': condition, 'box': box,
                                  'mean_rgb': rgb, 'mean_luma': luma}
    print(label, condition, 'RGB', [round(v, 3) for v in rgb], 'luma', round(luma, 3), 'box', box)
(output / 'receipt.json').write_text(json.dumps(receipt, indent=2) + '\n')
