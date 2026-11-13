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


class AnimFloor(unittest.TestCase):
    """14-dw: the sub-gate animation floor (the lit-candle particle class,
    run 43299753683's measured separation: static 44, particle 2887..42939)."""

    GATE = 60000
    FLOOR = 1000

    def test_the_measured_particle_rows_demote(self):
        # the truth's flame+smoke cloud varies; ours is static (sa_o=0)
        self.assertTrue(gd.animated_class(34489, 0, self.GATE, self.FLOOR))
        self.assertTrue(gd.animated_class(42939, 0, self.GATE, self.FLOOR))
        self.assertTrue(gd.animated_class(2887, 0, self.GATE, self.FLOOR))

    def test_the_static_light_rows_stay_red(self):
        # the 14-dv2 light-calibration class: the truth's own frames agree
        self.assertFalse(gd.animated_class(44, 0, self.GATE, self.FLOOR))
        self.assertFalse(gd.animated_class(12, 3, self.GATE, self.FLOOR))

    def test_the_floor_boundary_is_exact(self):
        self.assertFalse(gd.animated_class(self.FLOOR, 0, self.GATE, self.FLOOR))
        self.assertTrue(gd.animated_class(self.FLOOR + 1, 0, self.GATE, self.FLOOR))
        # ours at the floor disqualifies: ours carries its own animation channel
        self.assertFalse(gd.animated_class(self.FLOOR + 1, self.FLOOR + 1,
                                           self.GATE, self.FLOOR))

    def test_both_legs_varying_sub_gate_stays_red(self):
        self.assertFalse(gd.animated_class(5000, 4000, self.GATE, self.FLOOR))

    def test_the_gate_laws_unchanged(self):
        # the 14-aj gate-shaped rules still hold verbatim
        self.assertTrue(gd.animated_class(self.GATE + 1, 0, self.GATE, self.FLOOR))
        self.assertTrue(gd.animated_class(0, self.GATE + 1, self.GATE, self.FLOOR))
        self.assertFalse(gd.animated_class(None, None, self.GATE, self.FLOOR))
        # no ours self frame: the truth's variance above the floor decides
        self.assertTrue(gd.animated_class(5000, None, self.GATE, self.FLOOR))
        self.assertFalse(gd.animated_class(500, None, self.GATE, self.FLOOR))


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


class DissolvedRace(unittest.TestCase):
    """14-dr: a truth CELL the replay's own input names dissolved is the walk's
    self-removal race — RED demotes to DISSOLVED-RACE, never gates."""

    @staticmethod
    def _pair(root):
        random = np.random.default_rng(23)
        truth = random.integers(0, 256, (gd.H, gd.W, 3), dtype=np.uint8)
        ours = truth.copy()
        _, (x0, y0, x1, y1) = gd.crop_cell(truth, 0, 0)
        ours[y0:y1, x0:x1] = random.integers(0, 256, (y1 - y0, x1 - x0, 3), dtype=np.uint8)
        Image.fromarray(truth).save(root / "truth.png")
        Image.fromarray(ours).save(root / "ours.png")

    def test_a_dissolved_replay_position_demotes_and_never_gates(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = pathlib.Path(temporary)
            self._pair(root)
            manifest = root / "gallery-test-p0.tsv"
            manifest.write_text("cell\tx\ty\tz\tstate\tdyn\tlight\n"
                                "cell\t-5\t-60\t9\tfarmersdelight:rope\t0\t0\n")
            replay = root / "scenes"
            replay.mkdir()
            (replay / "gallery-test-p0.tsv").write_text(
                "cell\tx\ty\tz\tstate\tdyn\tlight\n"
                "skip\t-5\t-60\t9\tfarmersdelight:rope_tomatoes\t0\tdissolved\n")
            arguments = ["gallery-diff.py", "--truth", str(root / "truth.png"),
                         "--ours", str(root / "ours.png"), "--manifest", str(manifest),
                         "--replay-manifests", str(replay), "--gate", "100",
                         "--out", str(root / "report.json")]
            with patch.object(sys, "argv", arguments), contextlib.redirect_stdout(io.StringIO()):
                result = gd.main()
            report = json.loads((root / "report.json").read_text())
            self.assertEqual(report["rows"][0]["verdict"], "DISSOLVED-RACE")
            self.assertEqual(report["verdict"], "GREEN")
            self.assertEqual(report["dissolved_race"], 1)
            self.assertEqual(result, 0)

    def test_a_non_dissolved_position_stays_red(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = pathlib.Path(temporary)
            self._pair(root)
            manifest = root / "gallery-test-p0.tsv"
            manifest.write_text("cell\tx\ty\tz\tstate\tdyn\tlight\n"
                                "cell\t-5\t-60\t9\tfarmersdelight:rope\t0\t0\n")
            replay = root / "scenes"
            replay.mkdir()
            # the dissolved position is a DIFFERENT cell: this rope cell was
            # really replayed, its RED is fidelity and must gate
            (replay / "gallery-test-p0.tsv").write_text(
                "cell\tx\ty\tz\tstate\tdyn\tlight\n"
                "skip\t-4\t-60\t9\tsupplementaries:rope_tomatoes\t0\tdissolved\n")
            arguments = ["gallery-diff.py", "--truth", str(root / "truth.png"),
                         "--ours", str(root / "ours.png"), "--manifest", str(manifest),
                         "--replay-manifests", str(replay), "--gate", "100",
                         "--out", str(root / "report.json")]
            with patch.object(sys, "argv", arguments), contextlib.redirect_stdout(io.StringIO()):
                result = gd.main()
            report = json.loads((root / "report.json").read_text())
            self.assertEqual(report["rows"][0]["verdict"], "RED")
            self.assertEqual(report["verdict"], "RED")
            self.assertEqual(report["dissolved_race"], 0)
            self.assertEqual(result, 2)

    def test_without_the_replay_input_nothing_demotes(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = pathlib.Path(temporary)
            self._pair(root)
            manifest = root / "gallery-test-p0.tsv"
            manifest.write_text("cell\tx\ty\tz\tstate\tdyn\tlight\n"
                                "cell\t-5\t-60\t9\tfarmersdelight:rope\t0\t0\n")
            arguments = ["gallery-diff.py", "--truth", str(root / "truth.png"),
                         "--ours", str(root / "ours.png"), "--manifest", str(manifest),
                         "--gate", "100", "--out", str(root / "report.json")]
            with patch.object(sys, "argv", arguments), contextlib.redirect_stdout(io.StringIO()):
                result = gd.main()
            report = json.loads((root / "report.json").read_text())
            self.assertEqual(report["rows"][0]["verdict"], "RED")
            self.assertEqual(result, 2)


class AnimatedStates(unittest.TestCase):
    """14-dx-f: a RED row whose captured mesh samples an animated atlas sprite
    (the pack build's visual-gallery-animation.tsv) demotes to ANIMATED — the
    free-running animation-clock class. A green row stays green; a state the
    manifest does not list stays RED; a missing file is the empty law."""

    @staticmethod
    def _pair(root):
        random = np.random.default_rng(31)
        truth = random.integers(0, 256, (gd.H, gd.W, 3), dtype=np.uint8)
        ours = truth.copy()
        _, (x0, y0, x1, y1) = gd.crop_cell(truth, 0, 0)
        ours[y0:y1, x0:x1] = random.integers(0, 256, (y1 - y0, x1 - x0, 3), dtype=np.uint8)
        Image.fromarray(truth).save(root / "truth.png")
        Image.fromarray(ours).save(root / "ours.png")

    @staticmethod
    def _run(root, manifest, extra=()):
        # the real joins run --gate-dyn (the fluid/block join calls) — the
        # dyn=1 modded rows gate, so the animated-sprite law is reachable
        arguments = ["gallery-diff.py", "--truth", str(root / "truth.png"),
                     "--ours", str(root / "ours.png"), "--manifest", str(manifest),
                     "--gate-dyn", "--gate", "100", "--out", str(root / "report.json"),
                     *extra]
        with patch.object(sys, "argv", arguments), contextlib.redirect_stdout(io.StringIO()):
            gd.main()
        return json.loads((root / "report.json").read_text())

    def test_the_listed_state_demotes_and_counts_loud(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = pathlib.Path(temporary)
            self._pair(root)
            manifest = root / "gallery-test-p0.tsv"
            manifest.write_text("cell\tx\ty\tz\tstate\tdyn\tlight\n"
                                "cell\t-5\t-60\t9\tbiomesoplenty:liquid_null[level=0]\t1\t0\n")
            anim = root / "visual-gallery-animation.tsv"
            anim.write_text("state\tframes\n"
                            "biomesoplenty:liquid_null[level=0]#q7c3cb98e72f85f9d\t9\n")
            report = self._run(root, manifest, ["--animated-states", str(anim)])
            self.assertEqual(report["rows"][0]["verdict"], "ANIMATED")
            self.assertEqual(report["verdict"], "GREEN")
            self.assertEqual(report["animated"], 1)

    def test_a_state_outside_the_list_stays_red(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = pathlib.Path(temporary)
            self._pair(root)
            manifest = root / "gallery-test-p0.tsv"
            manifest.write_text("cell\tx\ty\tz\tstate\tdyn\tlight\n"
                                "cell\t-5\t-60\t9\tbiomesoplenty:liquid_null[level=0]\t1\t0\n")
            anim = root / "visual-gallery-animation.tsv"
            # a DIFFERENT state, and a single-frame row (frames=1 never demotes)
            anim.write_text("state\tframes\n"
                            "biomesoplenty:blood[level=0]\t20\n"
                            "biomesoplenty:liquid_null[level=0]#qstatic\t1\n")
            report = self._run(root, manifest, ["--animated-states", str(anim)])
            self.assertEqual(report["rows"][0]["verdict"], "RED")
            self.assertEqual(report["verdict"], "RED")

    def test_a_green_row_never_flips(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = pathlib.Path(temporary)
            random = np.random.default_rng(37)
            truth = random.integers(0, 256, (gd.H, gd.W, 3), dtype=np.uint8)
            Image.fromarray(truth).save(root / "truth.png")
            Image.fromarray(truth).save(root / "ours.png")
            manifest = root / "gallery-test-p0.tsv"
            manifest.write_text("cell\tx\ty\tz\tstate\tdyn\tlight\n"
                                "cell\t-5\t-60\t9\tbiomesoplenty:liquid_null[level=0]\t1\t0\n")
            anim = root / "visual-gallery-animation.tsv"
            anim.write_text("state\tframes\n"
                            "biomesoplenty:liquid_null[level=0]\t9\n")
            report = self._run(root, manifest, ["--animated-states", str(anim)])
            self.assertEqual(report["rows"][0]["verdict"], "GREEN")
            self.assertEqual(report["animated"], 0)

    def test_a_missing_file_is_the_empty_law(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = pathlib.Path(temporary)
            self._pair(root)
            manifest = root / "gallery-test-p0.tsv"
            manifest.write_text("cell\tx\ty\tz\tstate\tdyn\tlight\n"
                                "cell\t-5\t-60\t9\tbiomesoplenty:liquid_null[level=0]\t1\t0\n")
            report = self._run(root, manifest,
                               ["--animated-states", str(root / "absent.tsv")])
            self.assertEqual(report["rows"][0]["verdict"], "RED")


if __name__ == "__main__":
    unittest.main()
