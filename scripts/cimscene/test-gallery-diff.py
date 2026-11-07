#!/usr/bin/env python3
"""Verdict classes and CLI gating, including a deterministic image comparison."""
import importlib.util
import contextlib
import io
import json
import pathlib
import sys
import tempfile
import unittest
from unittest.mock import patch

import numpy as np
from PIL import Image

sys.dont_write_bytecode = True
_spec = importlib.util.spec_from_file_location(
    "gallery_diff", pathlib.Path(__file__).with_name("gallery-diff.py"))
gd = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(gd)


def verdict(over=True, dyn=False, gated=False, air=False, animated=False, spill=False):
    return gd.classify(over, dyn, gated, air, animated, spill)


class Classify(unittest.TestCase):
    def test_under_the_gate_is_green_whatever_the_flags(self):
        self.assertEqual(verdict(over=False, dyn=True, spill=True), "GREEN")

    def test_ungated_dyn_is_excused(self):
        self.assertEqual(verdict(dyn=True), "DYNAMIC")

    def test_gated_dyn_is_red(self):
        self.assertEqual(verdict(dyn=True, gated=True), "RED")

    def test_a_dyn_neighbour_cannot_excuse_a_gated_dyn_cell(self):
        self.assertEqual(verdict(dyn=True, gated=True, spill=True), "RED")

    def test_a_static_cell_keeps_its_spill_excuse(self):
        self.assertEqual(verdict(spill=True), "SPILL")

    def test_static_red_stays_red(self):
        self.assertEqual(verdict(), "RED")

    def test_air_and_animation_keep_their_classes(self):
        self.assertEqual(verdict(air=True), "AIR")
        self.assertEqual(verdict(dyn=True, gated=True, animated=True), "ANIMATED")


class CommandLine(unittest.TestCase):
    def test_gate_hard_workflow_calls_opt_into_dynamic_gating(self):
        workflow = pathlib.Path(__file__).resolve().parents[2] / ".github/workflows/gallery.yml"
        logical_lines = workflow.read_text().replace("\\\n", " ").splitlines()
        calls = [line for line in logical_lines
                 if "python3 scripts/cimscene/gallery-diff.py" in line and "|| GATE=1" in line]
        self.assertGreaterEqual(len(calls), 6, "all hard pixel channels must be audited")
        for call in calls:
            with self.subTest(command=call.strip()):
                self.assertIn("--gate-dyn", call,
                              "hard gate must not silently defer modded dynamic rows")

    def test_dynamic_flag_controls_real_manifest_comparison(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = pathlib.Path(temporary)
            random = np.random.default_rng(19)
            truth = random.integers(0, 256, (gd.H, gd.W, 3), dtype=np.uint8)
            ours = truth.copy()
            _, (x0, y0, x1, y1) = gd.crop_cell(truth, 0, 0)
            ours[y0:y1, x0:x1] = random.integers(
                0, 256, (y1 - y0, x1 - x0, 3), dtype=np.uint8)
            Image.fromarray(truth).save(root / "truth.png")
            Image.fromarray(ours).save(root / "ours.png")
            for namespace, flags, expected, status in (
                ("create", [], "DYNAMIC", 0),
                ("create", ["--gate-dyn"], "RED", 2),
                ("minecraft", [], "RED", 2),
                ("minecraft", ["--gate-dyn"], "RED", 2),
            ):
                with self.subTest(namespace=namespace, flags=flags):
                    manifest = root / "gallery-test-p0.tsv"
                    manifest.write_text("cell\tx\ty\tz\tstate\tdyn\tnote\n"
                                        f"cell\t-5\t-60\t9\t{namespace}:test\t1\tok\n")
                    arguments = ["gallery-diff.py", "--truth", str(root / "truth.png"),
                                 "--ours", str(root / "ours.png"), "--manifest", str(manifest),
                                 "--out", str(root / "report.json"), *flags]
                    with patch.object(sys, "argv", arguments), contextlib.redirect_stdout(io.StringIO()):
                        result = gd.main()
                    report = json.loads((root / "report.json").read_text())
                    self.assertEqual(report["rows"][0]["verdict"], expected)
                    self.assertEqual(result, status)


if __name__ == "__main__":
    unittest.main()
