#!/usr/bin/env python3
"""The channel-swap audit's selftest (the 14-e9.2 fixture battery, no JDK).

Builds the hand-assembled fixture tree (PIL only — the tool's own imaging
lib) in a tempdir and runs capture-fingerprint.py on it twice (the main
battery + the subtle-swap battery). Asserts the mechanical contract:

  - a cell whose ours crop is the R/B-exchanged capture flags CHANNEL-SWAP
    (ratio ~0 — the exchange kills the error);
  - the identical cell does not (class MATCH, swap_flag false);
  - a x0.5 dim does NOT false-positive (ratio >= 1 — the exchange never
    helps a scaling mechanism);
  - a swap small enough to fit the 0.18 tolerance still flags (the case
    eyeballs could never settle);
  - a gray pair (R == B) reads ratio None and never flags;
  - corrupt/absent/empty inputs degrade honestly (UNREADABLE-OURS /
    NO-OURS-CROP / EMPTY-OURS census names) and the tool exits 0 with the
    additive "channel_swap" block marked PROVISIONAL.

Run: python3 scripts/cimscene/test-capture-fingerprint.py
"""
import json
import os
import subprocess
import sys
import tempfile
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
TOOL = os.path.join(HERE, "capture-fingerprint.py")


def build_tree(root, crop_colors, capture=(200, 120, 40, 255)):
    """The reports/ + seed tree the tool reads; crop_colors maps col->RGBA or None."""
    from PIL import Image
    rd = os.path.join(root, "reports", "gallery-report-0")
    scn = os.path.join(root, "seed", "scenes")
    quads = os.path.join(scn, "quads")
    tex = os.path.join(quads, "textures")
    os.makedirs(tex, exist_ok=True)
    os.makedirs(os.path.join(rd, "diff-0"), exist_ok=True)
    Image.new("RGBA", (8, 8), capture).save(os.path.join(tex, "cafe01.png"))
    with open(os.path.join(quads, "sprite-aliases.tsv"), "w") as f:
        f.write("# sprite\tnative -> ~capture-hash\n")
        f.write("testmod:thing\t~cafe01\n")
    # the ctx stores the hash WITHOUT the leading 'q' (the tool strips it),
    # so the quad file is aaaa.tsv (the Task-3-b fixture learning)
    with open(os.path.join(quads, "aaaa.tsv"), "w") as f:
        f.write("testmod:thing\t0\t0\t16\t16\n")
    with open(os.path.join(scn, "gallery-test-p0.ctx.tsv"), "w") as f:
        for col in range(len(crop_colors)):
            # the join rows ride col==row==c, so the ctx wall coords move on
            # BOTH axes (the tool maps (wx, wy, wz) = (-5+col, -60+row, 9))
            f.write(f"{-5 + col}\t{-60 + col}\t9\tqaaaa\n")
    rows = [{"verdict": "RED", "page": "gallery-test-p0", "col": c, "row": c,
             "state": f"thing[c{c}]"} for c in range(len(crop_colors))]
    with open(os.path.join(rd, "join-1.json"), "w") as f:
        json.dump({"rows": rows}, f, indent=1)
    stem = "gallery-test-p0_c{}_r{}_ours.png"
    for c, rgba in enumerate(crop_colors):
        if rgba is None:
            continue
        Image.new("RGBA", (4, 4), rgba).save(
            os.path.join(rd, "diff-0", stem.format(c, c)))


def run_tool(root):
    out = os.path.join(root, "out.json")
    p = subprocess.run([sys.executable, TOOL, os.path.join(root, "reports"),
                        os.path.join(root, "seed", "scenes"), "--out", out],
                       capture_output=True, text=True)
    return p, json.load(open(out))


class ChannelSwapAudit(unittest.TestCase):

    def test_main_battery(self):
        with tempfile.TemporaryDirectory() as root:
            build_tree(root, [
                (200, 120, 40, 255),   # c0 identical -> MATCH, no flag
                (40, 120, 200, 255),   # c1 R/B-swapped -> CHANNEL-SWAP
                (100, 60, 20, 255),    # c2 x0.5 dim -> no flag (ratio >= 1)
                None,                  # c3 absent -> NO-OURS-CROP
                (0, 0, 0, 0),          # c4 empty -> EMPTY-OURS
            ])
            p, d = run_tool(root)
            self.assertEqual(p.returncode, 0, p.stderr)
            swap = d["channel_swap"]
            self.assertTrue(swap["status"].startswith("PROVISIONAL"))
            rows = {r["state"]: r for r in d["rows"]}
            self.assertFalse(rows["thing[c0]"]["swap_flag"])
            self.assertEqual(rows["thing[c0]"]["class"], "MATCH")
            self.assertTrue(rows["thing[c1]"]["swap_flag"])
            self.assertLessEqual(rows["thing[c1]"]["swap_ratio"], 0.5)
            self.assertEqual(swap["flagged"], 1)
            self.assertFalse(rows["thing[c2]"]["swap_flag"])
            self.assertGreaterEqual(rows["thing[c2]"]["swap_ratio"], 1.0)
            self.assertEqual(rows["thing[c2]"]["class"], "DIM")
            # the absent/empty crops degrade honestly: named in the census,
            # absent from the compared rows (no pair to audit)
            self.assertNotIn("thing[c3]", rows)
            self.assertNotIn("thing[c4]", rows)
            self.assertEqual(d["census"]["NO-OURS-CROP"], 1)
            self.assertEqual(d["census"]["EMPTY-OURS"], 1)
            self.assertEqual(swap["pairs"], 3)

    def test_subtle_swap_inside_tolerance_still_flags(self):
        # (100,80,120) expected vs (120,80,100) ours: the relative MAE 0.122
        # fits the 0.18 tolerance (class MATCH — the eyeball-invisible case);
        # the exchange maps ours onto the capture exactly (ratio 0) — FLAG.
        with tempfile.TemporaryDirectory() as root:
            build_tree(root, [(120, 80, 100, 255)], capture=(100, 80, 120, 255))
            p, d = run_tool(root)
            self.assertEqual(p.returncode, 0, p.stderr)
            rows = {r["state"]: r for r in d["rows"]}
            r0 = rows["thing[c0]"]
            self.assertEqual(r0["class"], "MATCH")
            self.assertTrue(r0["swap_flag"])

    def test_gray_pair_never_flags(self):
        # R == B: the exchange is a no-op, ratio ~1, no flag (the visually
        # invisible swap stays honest by design).
        with tempfile.TemporaryDirectory() as root:
            build_tree(root, [(90, 90, 90, 255)], capture=(90, 90, 90, 255))
            p, d = run_tool(root)
            self.assertEqual(p.returncode, 0, p.stderr)
            rows = {r["state"]: r for r in d["rows"]}
            self.assertFalse(rows["thing[c0]"]["swap_flag"])
            self.assertIn(rows["thing[c0]"]["swap_ratio"], (None, 1.0))

    def test_pixel_swap_audit_catches_the_mean_invisible_case(self):
        # 14-e280 THE PIXEL AUDIT: ours = a two-half red/blue sprite, truth =
        # the same sprite R/B-exchanged, the capture = ours' own mean
        # ((127,0,127) — R == B, so the MEAN exchange is a no-op and the
        # mean metric reads ratio ~1 while the pair fits (MATCH)). The
        # per-pixel exchange maps ours onto the truth EXACTLY (ratio ~0) —
        # the pixel audit flags what the mean metric structurally cannot.
        # Also proves the mean-flag zone's dispatch reading: the two metrics
        # separate (the mask artifact flags means, never pixels).
        with tempfile.TemporaryDirectory() as root:
            build_tree(root, [(127, 0, 127, 255)], capture=(127, 0, 127, 255))
            from PIL import Image
            rd = os.path.join(root, "reports", "gallery-report-0")
            dd = os.path.join(rd, "diff-0")
            ours = Image.new("RGBA", (8, 8))
            truth = Image.new("RGBA", (8, 8))
            for x in range(8):
                for y in range(8):
                    if x < 4:
                        ours.putpixel((x, y), (255, 0, 0, 255))
                        truth.putpixel((x, y), (0, 0, 255, 255))
                    else:
                        ours.putpixel((x, y), (0, 0, 255, 255))
                        truth.putpixel((x, y), (255, 0, 0, 255))
            ours.save(os.path.join(dd, "gallery-test-p0_c0_r0_ours.png"))
            truth.save(os.path.join(dd, "gallery-test-p0_c0_r0_truth.png"))
            p, d = run_tool(root)
            self.assertEqual(p.returncode, 0, p.stderr)
            rows = {r["state"]: r for r in d["rows"]}
            r0 = rows["thing[c0]"]
            # the mean metric: the pair fits, the exchange cannot help (R==B)
            self.assertEqual(r0["class"], "MATCH")
            self.assertIn(r0["swap_ratio"], (None, 1.0))
            self.assertFalse(r0["swap_flag"])
            # the pixel metric: the exchange kills the aligned error
            ps = d["pixel_swap"]
            self.assertEqual(ps["pairs"], 1)
            self.assertEqual(ps["flagged"], 1)
            self.assertTrue(r0["pixel_flag"])
            self.assertLessEqual(r0["pixel_ratio"], 0.5)
            self.assertGreaterEqual(r0["pixel_mae"], 2.0)

    def test_pixel_swap_audit_skips_when_no_truth_crop(self):
        # the existing fixtures carry no _truth.png: the pixel audit degrades
        # honestly (0 pairs), the mean audit unchanged — the additive law.
        with tempfile.TemporaryDirectory() as root:
            build_tree(root, [(40, 120, 200, 255)])
            p, d = run_tool(root)
            self.assertEqual(p.returncode, 0, p.stderr)
            self.assertEqual(d["pixel_swap"]["pairs"], 0)
            self.assertEqual(d["channel_swap"]["flagged"], 1)

    def test_fixture_liveness(self):
        # the detector-liveness fixture (the gate-flip precondition): the
        # synthetic hard-swap pair MUST flag — a dead detector receipted.
        import importlib.util
        spec = importlib.util.spec_from_file_location(
            "capture_fingerprint", TOOL)
        cf = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(cf)
        with tempfile.TemporaryDirectory() as root:
            integrity = cf.fixture_liveness(root)
        self.assertTrue(integrity["fixture"]["detected"],
                        "the synthetic hard R/B swap must flag")
        pair = integrity["fixture"]["pair"]
        self.assertIsNotNone(pair)
        self.assertLessEqual(pair["pixel_ratio"], cf.SWAP_FACTOR)
        self.assertGreater(pair["pixel_mae"], cf.PIXEL_SWAP_FLOOR)

    def test_fixture_only_mode(self):
        # --fixture-only runs without reports_root/seed_scenes and lands the
        # capture-integrity json (the verdict-job shape); rc 0 = detected.
        with tempfile.TemporaryDirectory() as root:
            out = os.path.join(root, "ci.json")
            p = subprocess.run(
                [sys.executable, TOOL, "--fixture-only", "--out", out],
                capture_output=True, text=True)
            self.assertEqual(p.returncode, 0, p.stderr)
            d = json.load(open(out))
            self.assertTrue(d["fixture"]["detected"])


if __name__ == "__main__":
    unittest.main()
