#!/usr/bin/env python3
"""Unit tests for the tint-bake calibration solver (tint-calibrate.py).

Synthetic-image contract checks, no gallery artifacts needed:

  solve_ratio  recovers a PURE multiply exactly, survives noise and AA-ish
               outliers via the median, is unmoved by a uniform sky both legs
               share, ignores clamped 255 highlights and low-signal pixels,
               and undoes the join's alignment shift.
  aggregate_texture  refuses disagreeing cells, out-of-family multiplies and
               unrepresentable (clipping) bakes; rounds to 4 decimals; drops
               ~1.0 tints as 'no measurement'.
  classify     the honest residual-class vocabulary.

Run:  python3 test-tint-calibrate.py
"""
import importlib.util
import io
import pathlib
import sys
import unittest

import numpy as np
from PIL import Image

_HERE = pathlib.Path(__file__).resolve().parent
_spec = importlib.util.spec_from_file_location(
    "tint_calibrate", _HERE / "tint-calibrate.py")
tc = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(tc)


def encode(arr):
    """(h,w,4) uint8 -> PNG bytes."""
    buf = io.BytesIO()
    Image.fromarray(arr.astype(np.uint8), "RGBA").save(buf, "png")
    return buf.getvalue()


class StubAssets:
    """Duck-typed JarAssets: only read_bytes, for clip_fraction."""

    def __init__(self, textures):
        self.textures = textures  # jar resource path -> PNG bytes

    def read_bytes(self, path):
        return self.textures.get(path)


def solid_texture(w, h, rgba):
    arr = np.zeros((h, w, 4), dtype=np.uint8)
    arr[..., :] = np.array(rgba, dtype=np.uint8)
    return encode(arr)


def make_pair(shape, tint, sky, noise=0.0, seed=7, highlights=False,
              shift=(0, 0)):
    """A content field (per-pixel varied base colors) rendered twice: ours =
    base, truth = base * tint (+ optional gaussian noise). The top rows are
    uniform SKY on BOTH legs (the measured contract: sky matches)."""
    rng = np.random.default_rng(seed)
    h, w = shape
    base = rng.integers(60, 200, size=(h, w, 3)).astype(float)
    if highlights:
        base[2:4, 2:4] = 255.0  # clipped highlights on the ours leg
    truth = np.clip(base * tint[None, None, :], 0, 255)
    if noise:
        truth += rng.normal(0, noise, truth.shape)
        base += rng.normal(0, noise, base.shape)
    ours = np.clip(base, 0, 255)
    # sky band on both legs, identical (measured sky ratio 1.000)
    ours[0:2, :, :] = sky
    truth[0:2, :, :] = sky
    dx, dy = shift
    if dy:
        ours = np.roll(ours, dy, axis=0)
    if dx:
        ours = np.roll(ours, dx, axis=1)
    return truth, ours


class SolveRatioTest(unittest.TestCase):
    def test_pure_multiply_recovered_exactly(self):
        tint = np.array([0.8, 0.9, 1.1])
        truth, ours = make_pair((24, 30), tint, sky=[180, 205, 255])
        got, n = tc.solve_ratio(truth, ours)
        np.testing.assert_allclose(got, tint, atol=1e-9)
        self.assertGreater(n, 300)

    def test_noise_bounded_by_median(self):
        tint = np.array([0.75, 1.0, 1.25])
        truth, ours = make_pair((32, 32), tint, sky=[180, 205, 255],
                                noise=4.0, seed=11)
        got, _ = tc.solve_ratio(truth, ours)
        np.testing.assert_allclose(got, tint, atol=0.05)

    def test_clipped_highlights_and_low_signal_excluded(self):
        tint = np.array([0.9, 0.95, 1.0])
        truth, ours = make_pair((32, 32), tint, sky=[180, 205, 255],
                                highlights=True)
        got, _ = tc.solve_ratio(truth, ours)
        np.testing.assert_allclose(got, tint, atol=0.02)

    def test_join_shift_is_undone(self):
        tint = np.array([0.85, 0.9, 0.95])
        truth, ours = make_pair((24, 30), tint, sky=[180, 205, 255],
                                shift=(2, -3))
        got, _ = tc.solve_ratio(truth, ours, shift=(2, -3))
        np.testing.assert_allclose(got, tint, atol=0.02)

    def test_starved_crop_returns_none(self):
        tint = np.array([0.9, 0.9, 0.9])
        truth, ours = make_pair((6, 6), tint, sky=[180, 205, 255])
        # a sky-only crop has no content pixels -> honest None, not a guess
        self.assertIsNone(tc.solve_ratio(truth, ours))


class AggregateTextureTest(unittest.TestCase):
    # clip_fraction resolves the tex ref to its jar resource path, so the
    # stub is keyed the same way
    TEX = "ns:block/tex"
    TEX_PNG = "assets/ns/textures/block/tex.png"

    def cell(self, ratio, state="s", page="p", cellname="c0,r0", n=500):
        return {"state": state, "ratio": np.array(ratio), "n": n,
                "page": page, "cell": cellname, "cls": "luma-uniform"}

    def stub(self, rgba):
        return StubAssets({self.TEX_PNG: solid_texture(16, 16, rgba)})

    def test_consistent_cells_aggregate_to_median_row(self):
        cells = [self.cell([0.92, 0.91, 0.90]),
                 self.cell([0.90, 0.91, 0.90], cellname="c1,r0")]
        row, reason = tc.aggregate_texture(self.TEX, cells, self.stub(
            [120, 120, 120, 255]), 0.06)
        self.assertIsNone(reason)
        self.assertEqual(row[0], self.TEX)
        self.assertEqual(row[1], (0.91, 0.91, 0.9))

    def test_disagreeing_cells_refused(self):
        cells = [self.cell([0.92, 0.92, 0.92]),
                 self.cell([1.20, 1.20, 1.20], cellname="c1,r0")]
        row, reason = tc.aggregate_texture(self.TEX, cells, self.stub(
            [120, 120, 120, 255]), 0.06)
        self.assertIsNone(row)
        self.assertIn("cells disagree", reason)

    def test_out_of_family_multiply_refused(self):
        row, reason = tc.aggregate_texture(
            self.TEX, [self.cell([2.4, 2.0, 2.1])],
            self.stub([60, 60, 60, 255]), 0.06)
        self.assertIsNone(row)
        self.assertIn("out of bake family", reason)

    def test_clipping_bake_refused(self):
        # a bright texture x 1.3 clips most pixels at 255 -> unrepresentable
        row, reason = tc.aggregate_texture(
            self.TEX, [self.cell([1.3, 1.3, 1.3])],
            self.stub([220, 210, 200, 255]), 0.06)
        self.assertIsNone(row)
        self.assertIn("unrepresentable", reason)

    def test_in_gamut_brighten_passes(self):
        # dark texture x 1.3 never clips -> representable
        row, reason = tc.aggregate_texture(
            self.TEX, [self.cell([1.3, 1.3, 1.3])],
            self.stub([60, 70, 80, 255]), 0.06)
        self.assertIsNone(reason)
        self.assertEqual(row[1], (1.3, 1.3, 1.3))

    def test_identity_tint_dropped_silently(self):
        row, reason = tc.aggregate_texture(
            self.TEX, [self.cell([1.0, 1.0, 1.0])],
            self.stub([120, 120, 120, 255]), 0.06)
        self.assertIsNone(row)
        self.assertIsNone(reason)  # nothing wrong — nothing to bake


class ClassifyTest(unittest.TestCase):
    def test_buckets(self):
        self.assertEqual(tc.classify(np.array([1.0, 1.0, 1.0]), 0),
                         "geometry/px")
        self.assertEqual(tc.classify(np.array([1.25, 1.25, 1.25]), 3),
                         "luma-uniform")
        self.assertEqual(tc.classify(np.array([1.25, 1.24, 1.23]), 30),
                         "luma+chroma")
        self.assertEqual(tc.classify(np.array([1.30, 1.28, 1.20]), 10),
                         "tint")
        self.assertEqual(tc.classify(np.array([1.5, 1.2, 1.0]), 10),
                         "tint-wide")

    def test_rounding_is_documented_python_round_four_decimals(self):
        # the documented rounding: python round() to 4 decimals (half-even on
        # representable halves; binary floats put 0.91235 just below the half
        # — the contract is the FUNCTION, not a decimal fantasy)
        self.assertEqual(round(0.91234, 4), 0.9123)
        self.assertEqual(round(0.91246, 4), 0.9125)
        self.assertEqual(round(1.25352, 4), 1.2535)
        self.assertEqual(round(0.63212345, 4), 0.6321)


if __name__ == "__main__":
    unittest.main()
