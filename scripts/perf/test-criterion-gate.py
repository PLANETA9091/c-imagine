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

    def test_noise_leg_does_not_gate_the_min_wins(self):
        # THE ring_roundtrip_4k shape: one +15% interference leg, one flat
        # leg — the benchmark is the min and it passes.
        self.put(self.base, 100)
        leg_a = Path(self.temp.name) / "leg-a"
        leg_b = Path(self.temp.name) / "leg-b"
        self.put(leg_a, 115)
        self.put(leg_b, 105)
        row = gate.compare(self.base, [leg_a, leg_b])[0]
        self.assertTrue(row["pass"])
        self.assertEqual(row["current_ns"], 105)
        self.assertEqual(row["legs"], 2)

    def test_single_path_argument_still_works(self):
        self.put(self.base, 100)
        self.put(self.current, 105)
        self.assertTrue(gate.compare(self.base, self.current)[0]["pass"])

    def test_reproduced_regression_fails(self):
        self.put(self.base, 100)
        leg_a = Path(self.temp.name) / "leg-a"
        leg_b = Path(self.temp.name) / "leg-b"
        self.put(leg_a, 115)
        self.put(leg_b, 116)
        self.assertFalse(gate.compare(self.base, [leg_a, leg_b])[0]["pass"])

    def test_leg_coverage_mismatch_blocks(self):
        self.put(self.base, 100)
        leg_a = Path(self.temp.name) / "leg-a"
        leg_b = Path(self.temp.name) / "leg-b"
        self.put(leg_a, 100)
        self.put(leg_b, 100, "different")
        with self.assertRaisesRegex(ValueError, "leg coverage changed"):
            gate.compare(self.base, [leg_a, leg_b])

    def test_snapshot_folds_to_the_min_leg(self):
        leg_a = Path(self.temp.name) / "leg-a"
        leg_b = Path(self.temp.name) / "leg-b"
        self.put(leg_a, 115)
        self.put(leg_a, 121, "codec2")
        self.put(leg_b, 105, "codec")
        self.put(leg_b, 120, "codec2")
        gate.snapshot([leg_a, leg_b], self.base, "sha")
        self.assertEqual(json.loads((self.base / "codec/new/estimates.json").read_text())
                         ["median"]["point_estimate"], 105)  # leg-b's min
        self.assertEqual(json.loads((self.base / "codec2/new/estimates.json").read_text())
                         ["median"]["point_estimate"], 120)  # leg-b's min
        self.assertEqual(json.loads((self.base / "provenance.json").read_text())["legs"], 2)


if __name__ == "__main__":
    unittest.main()
