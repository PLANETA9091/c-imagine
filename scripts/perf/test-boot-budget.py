#!/usr/bin/env python3
import importlib.util
from pathlib import Path
import tempfile
import unittest

spec = importlib.util.spec_from_file_location("gate", Path(__file__).with_name("boot-budget.py"))
gate = importlib.util.module_from_spec(spec)
spec.loader.exec_module(gate)


class Gate(unittest.TestCase):
    boot_dir_n = 0

    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        (self.root / "boot.log").write_text('Done (2.50s)!')
        (self.root / "rss.log").write_text('100\n200\n')

    def boot_dir(self, name, seconds, rss):
        root = self.root / f"{name}{self.boot_dir_n}"
        self.boot_dir_n += 1
        root.mkdir()
        (root / "boot.log").write_text(f'Done ({seconds}s)!')
        (root / "rss.log").write_text(str(rss))
        return root

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

    def test_median_ignores_one_noisy_boot_per_leg(self):
        # The #dfe859bf shape: one red-hot modded sample and one ice-cold bare
        # sample — the medians of 3 must not let either extreme set the ratio.
        bare = [self.boot_dir(f"b{i}", s, 100) for i, s in enumerate((11.0, 12.0, 13.0))]
        modded = [self.boot_dir(f"m{i}", s, 100) for i, s in enumerate((12.0, 13.0, 25.0))]
        ratio, rss = gate.compare(*[d for pair in zip(bare, modded) for d in pair])
        self.assertAlmostEqual(ratio, 13.0 / 12.0)
        self.assertEqual(rss, 0.0)

    def test_median_rss_delta_no_longer_hides_behind_a_noisy_bare_sample(self):
        # The b1302b1 shape (runs 43255194042 vs 43264635188): the max-minus-
        # max subtraction let ONE hot bare sample (1169 MiB vs 942 MiB
        # siblings) flatter the delta by ~200 MiB and pass over budget. The
        # medians see the true ~517 MiB delta and must fail it.
        KB = 1024
        bare = [self.boot_dir(f"nb{i}", 11.0, rss)
                for i, rss in enumerate((942 * KB, 943 * KB, 1169 * KB))]
        modded = [self.boot_dir(f"nm{i}", 12.0, rss)
                  for i, rss in enumerate((1472 * KB, 1482 * KB, 1337 * KB))]
        ratio, rss = gate.compare(*[d for pair in zip(bare, modded) for d in pair])
        expected = (1472 * KB - 943 * KB) / 1024.0
        self.assertAlmostEqual(rss, expected, places=3)
        self.assertGreater(rss, 400, "the honest median delta is over budget")

    def test_median_rss_still_passes_a_tight_pair(self):
        KB = 1024
        bare = [self.boot_dir(f"tb{i}", 11.0, 940 * KB) for i in range(3)]
        modded = [self.boot_dir(f"tm{i}", 12.0, 940 * KB + 300 * KB) for i in range(3)]
        _, rss = gate.compare(*[d for pair in zip(bare, modded) for d in pair])
        self.assertAlmostEqual(rss, 300.0, places=3)
        self.assertLessEqual(rss, 400)

    def test_median_rss_catches_a_real_leak(self):
        # a leak grows EVERY modded sample: the median moves with it
        KB = 1024
        bare = [self.boot_dir(f"lb{i}", 11.0, 940 * KB) for i in range(3)]
        modded = [self.boot_dir(f"lm{i}", 12.0, 940 * KB + 500 * KB) for i in range(3)]
        _, rss = gate.compare(*[d for pair in zip(bare, modded) for d in pair])
        self.assertGreater(rss, 400)

    def test_median_gate_still_fails_on_a_real_regression(self):
        # A real slowdown sits IN the medians, not at one extreme: 3/3 modded
        # boots 40% slower must fail exactly like the single-sample gate.
        bare = [self.boot_dir("rb", 12.0, 100) for _ in range(3)]
        modded = [self.boot_dir("rm", 16.8, 100) for _ in range(3)]
        ratio, _ = gate.compare(*[d for pair in zip(bare, modded) for d in pair])
        self.assertGreater(ratio, 1.25)

    def test_odd_legs_rejected(self):
        other = self.boot_dir('solo', 3.0, 100)
        with self.assertRaises(ValueError):
            gate.compare(self.root, self.root, other)

    def test_cli_multi_sample_flag_form(self):
        bare = [self.boot_dir(f"cb{i}", s, 100) for i, s in enumerate((12.0, 12.0, 12.0))]
        modded = [self.boot_dir(f"cm{i}", s, 100) for i, s in enumerate((13.0, 13.0, 13.0))]
        argv = []
        for d in bare:
            argv += ['--bare', str(d)]
        for d in modded:
            argv += ['--modded', str(d)]
        import sys
        old = sys.argv
        sys.argv = ['boot-budget.py', *argv]
        try:
            self.assertEqual(gate.main(), 0)  # 13/12 = 1.0833 <= 1.25
        finally:
            sys.argv = old

    def test_cli_rejects_unequal_sample_counts(self):
        import sys
        old = sys.argv
        sys.argv = ['boot-budget.py', '--bare', str(self.root), '--modded', str(self.root), '--modded', str(self.root)]
        try:
            self.assertEqual(gate.main(), 1)
        finally:
            sys.argv = old


if __name__ == '__main__':
    unittest.main()
