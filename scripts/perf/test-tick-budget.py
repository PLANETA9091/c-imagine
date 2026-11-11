#!/usr/bin/env python3
import importlib.util
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

SCRIPT = Path(__file__).with_name('tick-budget.py')
spec = importlib.util.spec_from_file_location('gate', SCRIPT)
gate = importlib.util.module_from_spec(spec)
spec.loader.exec_module(gate)
ARM = '[12:00:00 INFO]: [cimtest_heavy] load armed: msPerTick=10 ticks=600\n'
DONE = '[12:00:30 INFO]: [cimtest_heavy] load done: ticks=600 overMsTotal=600 avgOverMsPerTick=1.00\n'


class Gate(unittest.TestCase):
    def test_complete_measurement(self):
        self.assertEqual(gate.measure(ARM + DONE), 1.0)

    def test_missing_malformed_or_unrelated_evidence(self):
        for text in ('', '[other] load done: no measurements', ARM, DONE,
                     ARM + '[cimtest_heavy] load done: ticks=600',
                     ARM.replace('10 ticks', '1 ticks') + DONE,
                     ARM + DONE.replace('ticks=600', 'ticks=1'),
                     ARM + ARM + DONE, ARM + DONE + DONE):
            with self.subTest(text=text), self.assertRaises(ValueError):
                gate.measure(text)

    def test_invalid_average_and_inconsistent_total(self):
        for value in ('NaN', 'inf', '-1', 'missing', '0.0'):
            with self.subTest(value=value), self.assertRaises(ValueError):
                gate.measure(ARM + DONE.replace('1.00', value))

    def test_producer_rounding(self):
        self.assertEqual(gate.measure(ARM + DONE.replace('600 avg', '596 avg')), 1.0)

    def test_cli_enforces_budget_and_missing_evidence(self):
        with tempfile.TemporaryDirectory() as directory:
            log = Path(directory) / 'boot.log'
            for text, expected in ((ARM + DONE, 0),
                                   (ARM + DONE.replace('600 avg', '1200 avg').replace('1.00', '2.00'), 0),
                                   (ARM + DONE.replace('600 avg', '1206 avg').replace('1.00', '2.01'), 1),
                                   ('[other] load done', 1)):
                with self.subTest(text=text):
                    log.write_text(text)
                    result = subprocess.run([sys.executable, str(SCRIPT), str(log)], capture_output=True)
                    self.assertEqual(result.returncode, expected, result.stdout + result.stderr)


if __name__ == '__main__':
    unittest.main()
