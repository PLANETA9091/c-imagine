#!/usr/bin/env python3
"""The inv-diff selftest: the calibration diagnostics must be additive only
(the verdict/gate never read them) and the measured hypotheses must be exact
on a synthetic pair the truth of which is known by construction."""
import importlib.util
import json
import os
import subprocess
import sys
import tempfile

import numpy as np
from PIL import Image

HERE = os.path.dirname(os.path.abspath(__file__))
TOOL = os.path.join(HERE, "inv-diff.py")
spec = importlib.util.spec_from_file_location("inv_diff", TOOL)
inv = importlib.util.module_from_spec(spec)
spec.loader.exec_module(inv)

failures = []

def expect(cond, msg):
    if not cond:
        failures.append(msg)

# --- calibrate_slot: the swap signature by construction -------------------
rng = np.random.default_rng(263)
t = np.zeros((44, 44, 3), dtype=np.int64)
t[4:40, 4:40] = rng.integers(20, 235, size=(36, 36, 3))
o = t[..., ::-1].copy()          # ours = the truth's hard R/B swap
c = inv.calibrate_slot(t, o)
expect(c["ae_identity"] > 100000, f"swap fixture: ae_identity large, got {c['ae_identity']}")
expect(c["ae_rb"] == 0, f"swap fixture: ae_rb collapses to 0, got {c['ae_rb']}")
expect(c["swap_ratio"] == 0.0, f"swap fixture: swap_ratio 0.0, got {c['swap_ratio']}")

# --- the shift signature by construction ----------------------------------
o2 = np.roll(np.roll(t, 2, axis=0), 1, axis=1)
c2 = inv.calibrate_slot(t, o2)
expect(c2["best_shift"] == [-1, -2],
       f"shift fixture: best_shift [-1,-2] (the undo of the +2/+1 roll), got {c2['best_shift']}")
expect(c2["ae_best_shift"] == 0, f"shift fixture: ae_best_shift 0, got {c2['ae_best_shift']}")
expect(c2["ae_rb"] >= 0.5 * c2["ae_identity"],
       f"shift fixture: the swap must NOT collapse a shift signature "
       f"(ae_identity={c2['ae_identity']} vs ae_rb={c2['ae_rb']})")

# --- the end-to-end run: --calibrate rides the JSON, the verdict unchanged -
root = tempfile.mkdtemp(prefix="inv-cal-test-")
W, H = inv.W, inv.H
truth = np.zeros((H, W, 3), dtype=np.uint8)
truth[100:144, 100:144] = rng.integers(30, 220, size=(44, 44, 3))
ours = truth.copy()
ours[100:144, 100:144] = ours[100:144, 100:144][..., ::-1]
tp, op = os.path.join(root, "t.png"), os.path.join(root, "o.png")
Image.fromarray(truth).save(tp)
Image.fromarray(ours).save(op)

# the scale-3 slot geometry puts the first slots near a known origin; make
# BOTH images carry a swapped crop inside a real slot box so the slot is RED
# and its calibration carries the swap signature
boxes = inv.slot_boxes(3)
b0 = boxes[0]
truth2 = np.zeros((H, W, 3), dtype=np.uint8)
truth2[b0[1]:b0[3], b0[0]:b0[2]] = rng.integers(30, 220, size=(b0[3]-b0[1], b0[2]-b0[0], 3))
ours2 = truth2.copy()
ours2[b0[1]:b0[3], b0[0]:b0[2]] = ours2[b0[1]:b0[3], b0[0]:b0[2]][..., ::-1]
Image.fromarray(truth2).save(tp)
Image.fromarray(ours2).save(op)

out = os.path.join(root, "diag.json")
p = subprocess.run([sys.executable, TOOL, "--truth", tp, "--ours", op,
                    "--ns", "test", "--page", "1",
                    "--slot-gate", "100", "--frame-gate", "999999",
                    "--calibrate", "--out", out],
                   capture_output=True, text=True)
expect(p.returncode == 2, f"the red slot must exit 2 (valid RED), got {p.returncode}")
d = json.load(open(out))
expect(d["red_slots"] >= 1, f"the swapped slot must be RED, got {d['red_slots']}")
s0 = d["slots"][0]
expect(s0["calibration"]["ae_rb"] == 0,
       f"the swapped slot's ae_rb must collapse to 0, got {s0['calibration']}")
expect(d.get("calibrated") is True, "the calibrated flag must ride the report")

# --- the flag off = byte-compatible (no calibration key) --------------------
p2 = subprocess.run([sys.executable, TOOL, "--truth", tp, "--ours", op,
                     "--ns", "test", "--page", "1",
                     "--slot-gate", "100", "--frame-gate", "999999",
                     "--out", os.path.join(root, "diag2.json")],
                    capture_output=True, text=True)
d2 = json.load(open(os.path.join(root, "diag2.json")))
expect("calibration" not in d2["slots"][0], "flag off = no calibration keys")
expect(d2.get("calibrated") is False, "flag off = calibrated false")

if failures:
    for f in failures:
        print("  fail:", f)
    sys.exit(f"inv-diff selftest: FAILED ({len(failures)})")
print("inv-diff selftest: ALL OK")
