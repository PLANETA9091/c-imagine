#!/usr/bin/env python3
"""Exercise the artifact-directory contract without network or game clients."""
import json
from pathlib import Path
import subprocess
import tempfile
import unittest

TOOL = Path(__file__).with_name("read-landed-gallery.sh")


class LandedReadTest(unittest.TestCase):
    def fixture(self, root):
        for index in range(8):
            (root / f"gallery-report-{index}").mkdir()
            meta = root / f"wall-ours-meta-{index}"
            meta.mkdir()
            (meta / "boot.log").write_text("state=ae2:sample cmd=1 pin=0/15 src=front-air\n")
        report = root / "gallery-report-0"
        (report / "join-ae2-p0.json").write_text(json.dumps({"rows": [
            {"state": "ae2:sample", "verdict": "RED"}]}))
        (report / "diag-iso-ae2-p0.json").write_text(json.dumps({"rows": [
            {"state": "ae2:sample", "verdict": "GREEN"}]}))
        # A ZIP with the same prefix must not be treated as a report directory.
        (root / "gallery-report-0-123.zip").write_text("not a directory")

    def run_tool(self, root):
        return subprocess.run(["bash", str(TOOL), "123", "--out", str(root), "--local"],
                              capture_output=True, text=True)

    def test_root_artifact_layout_reaches_census_and_pin_join(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            self.fixture(root)
            result = self.run_tool(root)
            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertIn("OFFLINE", result.stdout)
            self.assertIn("LEAK-CLEARED: 1", result.stdout)
            self.assertIn("pin= lines total: 8", result.stdout)
            self.assertIn("RED rows with no pin line at all: 0", result.stdout)

    def test_corrupt_census_does_not_print_read_complete(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            self.fixture(root)
            (root / "gallery-report-0/diag-iso-ae2-p0.json").write_text("broken")
            result = self.run_tool(root)
            self.assertNotEqual(result.returncode, 0)
            self.assertNotIn("read complete:", result.stdout)

    def test_missing_shard_does_not_reuse_sibling_zip(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            self.fixture(root)
            (root / "gallery-report-7").rmdir()
            result = self.run_tool(root)
            self.assertNotEqual(result.returncode, 0)
            self.assertIn("eight report directories", result.stderr)


if __name__ == "__main__":
    unittest.main()
