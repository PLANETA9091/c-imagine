#!/usr/bin/env python3
"""Regression checks using the gallery producers' JSON contracts."""
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest


class FlipReportTest(unittest.TestCase):
    def report(self, files):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            for name, data in files.items():
                (root / name).write_text(json.dumps(data))
            out = root / "table.json"
            subprocess.run([sys.executable, str(Path(__file__).with_name("flip-report.py")),
                            "--json", str(out), str(root)], check=True,
                           capture_output=True, text=True)
            return {(r["class"], r["ns"]): r for r in json.loads(out.read_text())}

    def test_skips_do_not_remove_cells_or_pages(self):
        # gallery-diff.cells EXCLUDES skips; dynamic counts include GREEN rows.
        data = {"cells": 3, "skipped": 5, "red": 1, "dynamic": 1,
                "rows": [{"verdict": v} for v in
                         ["GREEN", "GREEN", "RED"] + ["skipped"] * 5]}
        r = self.report({"join-create-p0.json": data})[("block", "create")]
        self.assertEqual((r["pages"], r["eff"], r["green"], r["red"], r["skipped"]),
                         (1, 3, 2, 1, 5))

    def test_excluded_rows_and_namespace_item_class(self):
        # 14-e272 THE EXERCISED LAW: only a manifest skip row (verdict
        # "skipped") is an absence; every demoted verdict rode a measured
        # crop and counts exercised.
        data = {"rows": [{"verdict": v} for v in
                         ["GREEN", "RED", "DYNAMIC", "SPILL", "AIR", "ANIMATED", "skipped"]]}
        table = self.report({"join-item-create-p0.json": data,
                             "join-fluid-create-p0.json": {"rows": []}})
        r = table[("item", "create")]
        self.assertEqual((r["eff"], r["green"], r["red"], r["skipped"], r["demoted"]),
                         (6, 1, 1, 1, 4))
        self.assertEqual(table[("fluid", "create")]["state"], "NO-DATA")
        self.assertNotIn(("block", "create"), table)

    def test_demoted_rows_are_exercised_not_no_data(self):
        # THE 28th-FLIGHT FLUID CLOSURE: every compared fluid cell measured
        # over the gate and demoted ANIMATED (the animation-table + the
        # self-pair floor law) — the class must read EXERCISED, never
        # NO-DATA, and 0 red with demoted rows must NOT read GATED-GREEN.
        data = {"rows": [{"verdict": "ANIMATED"}] * 4}
        r = self.report({"join-fluid-biomesoplenty-p0.json": data})[("fluid", "biomesoplenty")]
        self.assertEqual((r["pages"], r["eff"], r["green"], r["red"],
                          r["skipped"], r["demoted"]), (1, 4, 0, 0, 0, 4))
        self.assertEqual(r["state"], "GATED (0 red, 4 demoted report-only)")

    def test_entity_family_aggregates_series_into_the_ledger_row(self):
        # THE 28th-FLIGHT ENTITY CLOSURE: the mobs gate sees only Mob-kind
        # subjects (everything else is a wrong-series skip row) while the
        # dyn/large series carry the modded types — the census folds the
        # three series into the ledger's one entity row (coverage-gate.py
        # fans `entity` out to the three series; this is the inverse).
        table = self.report({
            "join-e-create-0.json": {"rows": [{"verdict": "skipped"}] * 9},
            "join-e-dyn-create-p0.json": {"rows": [{"verdict": "DYNAMIC"},
                                                   {"verdict": "GREEN"}]},
            "join-e-large-create-p3.json": {"rows": [{"verdict": "skipped"}]},
        })
        self.assertNotIn(("e-dyn", "create"), table)
        self.assertNotIn(("e-large", "create"), table)
        r = table[("e", "create")]
        self.assertEqual((r["pages"], r["eff"], r["green"], r["red"],
                          r["skipped"], r["demoted"]), (3, 2, 1, 0, 10, 1))
        self.assertEqual(r["state"], "GATED (0 red, 1 demoted report-only)")

    def test_diagnostic_entity_series_never_read_gated_green(self):
        # a modded dyn/large join is pjoin 0 (diagnostic): folded into the
        # gated "e" row it must carry its rows as demoted (report-only) and
        # can never read GATED-GREEN while demoted > 0.
        data = {"rows": [{"verdict": "DYNAMIC"}] * 3}
        r = self.report({"join-e-dyn-supplementaries-p0.json": data})[("e", "supplementaries")]
        self.assertEqual((r["eff"], r["demoted"]), (3, 3))
        self.assertEqual(r["state"], "GATED (0 red, 3 demoted report-only)")

    def test_stream_preview_is_read_from_producer_schema(self):
        data = {"verdict": "RED", "truth_count": 5, "ours_count": 4,
                "mapped_preview": {"expected": 5, "position_hits": 4, "order_exact": False}}
        r = self.report({"diag-pstream-create-p0.json": data})[("particle", "create")]
        self.assertEqual((r["eff"], r["green"], r["red"]), (5, 4, 1))

    def test_extra_stream_event_blocks_candidate(self):
        data = {"verdict": "RED", "truth_count": 2, "ours_count": 3,
                "mapped_preview": {"expected": 2, "position_hits": 2, "order_exact": False}}
        r = self.report({"diag-sound-create-p0.json": data})[("sound", "create")]
        self.assertNotIn("FLIP-CANDIDATE", r["state"])
        self.assertGreater(r["red"], 0)

    def test_frame_presence_is_not_pixel_parity_and_inventory_uses_verdict(self):
        table = self.report({"diag-held-create-p0.json": {"verdict": "CONTENT", "ae": 999999},
                             "diag-inv-create-p0.json": {"verdict": "RED", "red_slots": 3}})
        self.assertNotIn("FLIP-CANDIDATE", table[("held", "create")]["state"])
        self.assertEqual(table[("inv", "create")]["red"], 1)

    def test_skipped_only_pages_remain_visible(self):
        r = self.report({"join-e-create-0.json": {"rows": [{"verdict": "skipped"}]}})[("e", "create")]
        self.assertEqual((r["pages"], r["eff"], r["skipped"], r["state"]), (1, 0, 1, "NO-DATA"))

    def test_zero_red_gated_class_is_not_a_flip_candidate(self):
        # 14-e33: the dynamictrees block read (100% green x2) mislabelled
        # itself a FLIP-CANDIDATE — a GATED class already hard-gates, there
        # is nothing to flip; the label belongs to diagnostic classes only.
        gated = self.report({"join-dynamictrees-p0.json": {
            "rows": [{"verdict": "GREEN", "ae": 25502}] * 3}})[("block", "dynamictrees")]
        self.assertEqual(gated["state"], "GATED-GREEN (0 red)")
        self.assertNotIn("FLIP-CANDIDATE", gated["state"])
        # the diagnostic sibling keeps the recommendation label
        diag = self.report({"diag-sound-dynamictrees-p0.json": {
            "verdict": "GREEN", "truth_count": 11, "ours_count": 11,
            "mapped_preview": {"expected": 11, "position_hits": 11,
                               "order_exact": True}}})[("sound", "dynamictrees")]
        self.assertIn("FLIP-CANDIDATE", diag["state"])

    def test_missing_stream_artifact_is_broken(self):
        r = self.report({"diag-pstream-create-p0.json": {"verdict": "ARTIFACT-MISSING"}})[("particle", "create")]
        self.assertEqual(r["state"], "BROKEN (missing artifact)")

    def test_malformed_report_fails_closed(self):
        with tempfile.TemporaryDirectory() as tmp:
            (Path(tmp) / "join-create-p0.json").write_text("{")
            result = subprocess.run([sys.executable, str(Path(__file__).with_name("flip-report.py")), tmp],
                                    capture_output=True, text=True)
            self.assertNotEqual(result.returncode, 0)
            self.assertIn("invalid report", result.stderr)

    def test_expanded_ladder_namespaces_across_producer_classes(self):
        for ns in ("ae2", "mekanism", "sophisticatedbackpacks", "jade",
                   "combat_roll", "future.mod-name", "create_extension"):
            with self.subTest(namespace=ns):
                pixels = {"rows": [{"verdict": "RED"}]}
                table = self.report({
                    f"join-{ns}-p0.json": pixels,
                    f"join-item-{ns}-p1.json": pixels,
                    f"join-e-{ns}-0.json": pixels,
                    f"diag-sound-{ns}-p0.json": {"verdict": "ARTIFACT-MISSING"},
                    f"diag-held-{ns}-p0.json": {"verdict": "CONTENT"},
                })
                self.assertEqual(set(table), {(cls, ns) for cls in
                                             ("block", "item", "e", "sound", "held")})
                self.assertEqual(table[("block", ns)]["red"], 1)

    def test_legacy_entity_pages_use_minecraft(self):
        table = self.report({"join-e0.json": {"rows": [{"verdict": "GREEN"}]}})
        self.assertEqual(set(table), {("e", "minecraft")})


if __name__ == "__main__":
    unittest.main()
