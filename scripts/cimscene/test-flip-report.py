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
        data = {"rows": [{"verdict": v} for v in
                         ["GREEN", "RED", "DYNAMIC", "SPILL", "AIR", "ANIMATED", "skipped"]]}
        table = self.report({"join-item-create-p0.json": data,
                             "join-fluid-create-p0.json": {"rows": []}})
        r = table[("item", "create")]
        self.assertEqual((r["eff"], r["green"], r["red"], r["skipped"]), (2, 1, 1, 5))
        self.assertEqual(table[("fluid", "create")]["state"], "NO-DATA")
        self.assertNotIn(("block", "create"), table)

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
