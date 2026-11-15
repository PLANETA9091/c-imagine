#!/usr/bin/env python3
"""Budget receipts must reject incomplete or slightly over-budget evidence."""
import importlib.util
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

TOOL = Path(__file__).with_name("profiling-receipt.py")
spec = importlib.util.spec_from_file_location("profiling_receipt", TOOL)
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


def fixture():
    logs = {}
    for leg, rss, boot in (("bare", 1024000, 10), ("modded", 1126400, 11)):
        for index in (1, 2, 3):
            logs[f"{leg}-{index}/rss.log"] = f"1234\n{rss}\n"
            logs[f"{leg}-{index}/boot.log"] = f'Done ({boot}s)! For help\nDone (0.1s)! For help\n'
    return logs


class ReceiptTest(unittest.TestCase):
    def test_complete_reads_first_paper_boot_and_peak_rss(self):
        result = module.summarize(fixture().__getitem__, {})
        self.assertEqual(result["verdict"], "PASS")
        self.assertEqual(result["rss_delta_mib"], 100)
        self.assertEqual(result["boot_ratio"], 1.1)

    def test_every_leg_required(self):
        for missing in fixture():
            logs = fixture()
            del logs[missing]
            with self.subTest(missing=missing), self.assertRaises(KeyError):
                module.summarize(logs.__getitem__, {})

    def test_empty_and_invalid_samples_fail(self):
        for value in ("", "0\n", "0\n1024000\n", "not-a-sample\n"):
            logs = fixture()
            logs["modded-3/rss.log"] = value
            with self.subTest(value=value), self.assertRaises(ValueError):
                module.summarize(logs.__getitem__, {})

    def test_missing_boot_fails(self):
        logs = fixture()
        logs["modded-2/boot.log"] = "server crashed before Done"
        with self.assertRaises(ValueError):
            module.summarize(logs.__getitem__, {})

    def test_rounding_cannot_hide_budget_excess(self):
        for metric in ("rss", "boot"):
            logs = fixture()
            for index in (1, 2, 3):
                logs[f"modded-{index}/{metric}.log"] = (
                    "1433640\n" if metric == "rss" else 'Done (12.50001s)! For help\n')
            self.assertEqual(module.summarize(logs.__getitem__, {})["verdict"], "FAIL")

    def test_transient_rss_peak_cannot_be_hidden_by_a_smaller_final_sample(self):
        logs = fixture()
        for index in (1, 2, 3):
            logs[f"modded-{index}/rss.log"] = "1600000\n1126400\n"
        result = module.summarize(logs.__getitem__, {})
        self.assertEqual(result["verdict"], "FAIL")
        self.assertGreater(result["rss_delta_mib"], 400)

    def test_failed_reuse_replaces_stale_pass_and_exits_nonzero(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            (root / "receipt.json").write_text('{"verdict":"PASS"}')
            result = subprocess.run([sys.executable, str(TOOL), "123", "--out", str(root),
                                     "--logs", str(root / "missing")], capture_output=True)
            self.assertNotEqual(result.returncode, 0)
            self.assertEqual(json.loads((root / "receipt.json").read_text())["verdict"], "INCOMPLETE")


if __name__ == "__main__":
    unittest.main()
