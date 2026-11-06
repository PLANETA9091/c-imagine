#!/usr/bin/env python3
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest

spec = importlib.util.spec_from_file_location("gate", Path(__file__).with_name("criterion-gate.py"))
gate = importlib.util.module_from_spec(spec)
spec.loader.exec_module(gate)


class Gate(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.base = Path(self.temp.name) / "base"
        self.current = Path(self.temp.name) / "current"

    def put(self, root, value, name="codec"):
        path = root / name / "new/estimates.json"
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(json.dumps({"median": {"point_estimate": value}}))

    def test_exact_limit_passes_and_excess_fails(self):
        self.put(self.base, 100)
        for value, expected in [(90, True), (110, True), (110.001, False)]:
            self.put(self.current, value)
            self.assertEqual(gate.compare(self.base, self.current)[0]["pass"], expected)

    def test_missing_or_new_benchmark_blocks(self):
        self.put(self.base, 100)
        self.put(self.current, 100, "different")
        with self.assertRaisesRegex(ValueError, "coverage changed"):
            gate.compare(self.base, self.current)

    def run_dirs(self, values):
        roots = []
        for index, value in enumerate(values, start=1):
            root = Path(self.temp.name) / f"run{index}"
            self.put(root, value)
            roots.append(root)
        return roots

    def test_best_of_three_ignores_a_contended_run(self):
        self.put(self.base, 100)
        rows = gate.compare(self.base, self.run_dirs((115, 103, 120)))
        self.assertEqual(rows[0]["current_ns"], 103)
        self.assertTrue(rows[0]["pass"])  # a single 115/120 ns run fails the 10% gate

    def test_real_regression_fails_best_of_three(self):
        self.put(self.base, 100)
        self.assertFalse(gate.compare(self.base, self.run_dirs((118, 116, 121)))[0]["pass"])

    def test_coverage_change_between_runs_blocks(self):
        self.put(self.base, 100)
        first = Path(self.temp.name) / "first"
        second = Path(self.temp.name) / "second"
        self.put(first, 100)
        self.put(second, 100, "different")
        with self.assertRaisesRegex(ValueError, "coverage changed"):
            gate.compare(self.base, [first, second])

    def test_snapshot_takes_min_and_records_provenance(self):
        gate.snapshot(self.run_dirs((110, 95, 105)), self.base, "abc123")
        self.assertEqual(gate.estimates(self.base)["codec"], 95)
        provenance = json.loads((self.base / "provenance.json").read_text())
        self.assertEqual(provenance["runs"], 3)
        self.assertEqual(provenance["aggregate"], "min")
        self.assertEqual(provenance["source_sha"], "abc123")

    def test_snapshot_replaces_stale_baseline(self):
        self.put(self.base, 1)
        gate.snapshot(self.run_dirs((100, 100)), self.base, "abc123")
        self.assertEqual(gate.estimates(self.base)["codec"], 100)

    def test_no_measurements_blocks(self):
        with self.assertRaisesRegex(ValueError, "no Criterion"):
            gate.estimates(self.base)

    def test_invalid_measurements_block(self):
        for value in (0, -1, float("nan"), float("inf"), "100", None, True):
            self.put(self.base, value)
            with self.assertRaises(ValueError):
                gate.estimates(self.base)

    def test_malformed_measurements_block(self):
        self.put(self.base, 100)
        (self.base / "codec/new/estimates.json").write_text("{}")
        with self.assertRaises(KeyError):
            gate.estimates(self.base)


if __name__ == "__main__":
    unittest.main()
