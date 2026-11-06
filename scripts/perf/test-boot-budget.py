#!/usr/bin/env python3
import importlib.util
from pathlib import Path
import tempfile
import unittest

spec = importlib.util.spec_from_file_location("gate", Path(__file__).with_name("boot-budget.py"))
gate = importlib.util.module_from_spec(spec)
spec.loader.exec_module(gate)


class Gate(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        (self.root / "boot.log").write_text('Done (2.50s)!')
        (self.root / "rss.log").write_text('100\n200\n')

    def test_real_measurements(self):
        self.assertEqual(gate.measure(self.root), (2.5, 200))

    def test_two_missing_boots_cannot_pass(self):
        (self.root / "boot.log").write_text('crashed before boot')
        with self.assertRaisesRegex(ValueError, "missing successful boot"):
            gate.compare(self.root, self.root)

    def test_empty_zero_and_malformed_rss_block(self):
        for data in ('', '0\n', '-1\n', 'missing\n'):
            (self.root / "rss.log").write_text(data)
            with self.assertRaises(ValueError):
                gate.measure(self.root)

    def test_zero_boot_time_blocks(self):
        (self.root / "boot.log").write_text('Done (0s)!')
        with self.assertRaises(ValueError):
            gate.measure(self.root)

    def test_ratios_keep_precision(self):
        other = self.root / 'modded'
        other.mkdir()
        (other / 'boot.log').write_text('Done (3.126s)!')
        (other / 'rss.log').write_text(str(200 + 400 * 1024 + 1))
        ratio, rss = gate.compare(self.root, other)
        self.assertGreater(ratio, 1.25)
        self.assertGreater(rss, 400)


if __name__ == '__main__':
    unittest.main()
