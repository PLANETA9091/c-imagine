#!/usr/bin/env python3
"""Exercise census CLI output with independent lane coverage and verdicts."""
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

SCRIPT = Path(__file__).with_name("iso-census.py")


class CensusTest(unittest.TestCase):
    def census(self, block, iso):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            for name, rows in (("join-ae2-p0.json", block),
                               ("diag-iso-ae2-p0.json", iso)):
                (root / name).write_text(json.dumps({"rows": rows}))
            return subprocess.run([sys.executable, str(SCRIPT), str(root)],
                                  capture_output=True, text=True)

    def test_missing_and_skipped_lanes_are_incomplete(self):
        result = self.census(
            [{"state": "ae2:block-only", "verdict": "GREEN"},
             {"state": "ae2:red-only", "verdict": "RED"},
             {"state": "ae2:skipped", "verdict": "AIR"}],
            [{"state": "ae2:iso-only", "verdict": "GREEN"},
             {"state": "ae2:skipped", "verdict": "GREEN"}])
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("INCOMPLETE: 4", result.stdout)
        self.assertIn("GREEN-BOTH: 0", result.stdout)
        self.assertIn("ISO-ONLY-RED: 0", result.stdout)

    def test_all_four_complete_classes(self):
        block = []
        iso = []
        for state, b, i in (("cleared", "RED", "GREEN"),
                            ("defect", "RED", "RED"),
                            ("iso-red", "GREEN", "RED"),
                            ("green", "GREEN", "GREEN")):
            block.append({"state": "ae2:" + state, "verdict": b})
            iso.append({"state": "ae2:" + state, "verdict": i})
        result = self.census(block, iso)
        self.assertEqual(result.returncode, 0, result.stderr)
        for label in ("LEAK-CLEARED", "TRUE-DEFECT", "ISO-ONLY-RED", "GREEN-BOTH"):
            self.assertIn(label + ": 1", result.stdout)
        self.assertIn("INCOMPLETE: 0", result.stdout)

    def test_absent_iso_is_failure(self):
        result = self.census([{"state": "ae2:a", "verdict": "GREEN"}], [])
        self.assertEqual(result.returncode, 1)
        self.assertIn("NO ISO DATA", result.stdout)


if __name__ == "__main__":
    unittest.main()
