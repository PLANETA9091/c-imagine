#!/usr/bin/env python3
"""14-e179 regression checks for the second-frame policy producer.

The suite pins two contracts:
  1. THE EXACTNESS LAW — the producer's census is byte-equivalent to the
     inline awk it replaced ($1=="cell" && $6=="1", raw field, no strip):
     every fixture is cross-checked against the real awk in a subprocess
     (the incumbent producer = the replay oracle).
  2. THE POLICY LAW — legacy probes only census>0 pages; widened probes
     every existing manifest; a missing manifest degrades to no probes
     on both policies (the awk died under set -e; the producer must not).
"""
import pathlib
import subprocess
import sys
import tempfile
import unittest

SCRIPT = pathlib.Path(__file__).with_name("second-frames.py")

CELL_HEADER = "cell\tx\ty\tz\tstate\tdyn\n"

# A 77-cell-shaped page mix (the real block-manifest layout: field 0 =
# the literal `cell`, fields 1-3 = x/y/z, field 4 = the state, field 5 =
# the raw dyn flag): static cells, exact dyn=1 cells, padded dyn fields
# the awk must REJECT ("1 ", "01", "true"), short rows, a foreign row,
# and the trailing-newline contract of the real manifests.
MIXED_MANIFEST = (
    CELL_HEADER
    + "cell\t0\t0\t0\tminecraft:stone\t0\n"
    + "cell\t1\t0\t0\tcreate:piston\t1\n"          # dyn=1 exact
    + "cell\t2\t0\t0\tminecraft:oak_leaves\t1 \n"  # padded — NOT dyn
    + "cell\t3\t0\t0\tminecraft:dirt\t01\n"        # "01" — NOT dyn
    + "cell\t4\t0\t0\tminecraft:sand\ttrue\n"      # NOT dyn
    + "cell\t5\t0\t0\tsupp:jar\t1\n"               # dyn=1 exact
    + "cell\t6\t0\t0\tshort\n"                     # <6 fields — NOT dyn
    + "notcell\t7\t0\t0\tminecraft:tnt\t1\n"       # field0 != cell
    + "cell\t8\t0\t0\tbop:willow\t1\n"             # dyn=1 exact
)


def run_plan(manifest, policy):
    cmd = [sys.executable, str(SCRIPT), "plan", "--manifest", str(manifest),
           "--policy", policy, "--ns", "testns", "--page", "7"]
    return subprocess.run(cmd, capture_output=True, text=True)


def awk_census(manifest):
    """The incumbent producer (the inline law being replaced)."""
    out = subprocess.run(
        ["awk", "-F", "\\t", '$1=="cell" && $6=="1"{n++} END{print n+0}',
         str(manifest)],
        capture_output=True, text=True)
    return out.stdout.strip()


class SecondFramesTest(unittest.TestCase):
    def plan(self, files, policy):
        with tempfile.TemporaryDirectory() as tmp:
            manifest = pathlib.Path(tmp) / "gallery-testns-p7.tsv"
            manifest.write_text(files)
            return run_plan(manifest, policy)

    def test_legacy_dyn_page_probes(self):
        r = self.plan(MIXED_MANIFEST, "legacy")
        self.assertEqual(r.returncode, 0, r.stderr)
        self.assertEqual(r.stdout.split(), ["t0", "t1"])
        self.assertIn("census=3", r.stderr)
        self.assertIn("policy=legacy", r.stderr)

    def test_legacy_static_page_does_not_probe(self):
        r = self.plan(CELL_HEADER + "cell\t0\t0\t0\tminecraft:stone\t0\n"
                      + "cell\t1\t0\t0\tminecraft:dirt\t0\n", "legacy")
        self.assertEqual(r.returncode, 0, r.stderr)
        self.assertEqual(r.stdout, "")
        self.assertIn("-> none", r.stderr)

    def test_widened_static_page_probes(self):
        # The R-c rung: EVERY existing manifest probes, static included.
        r = self.plan(CELL_HEADER + "cell\t0\t0\t0\tminecraft:stone\t0\n", "widened")
        self.assertEqual(r.returncode, 0, r.stderr)
        self.assertEqual(r.stdout.split(), ["t0", "t1"])
        self.assertIn("census=0", r.stderr)
        self.assertIn("policy=widened", r.stderr)

    def test_widened_dyn_page_probes(self):
        r = self.plan(MIXED_MANIFEST, "widened")
        self.assertEqual(r.stdout.split(), ["t0", "t1"])

    def test_missing_manifest_degrades_to_no_probes(self):
        with tempfile.TemporaryDirectory() as tmp:
            missing = pathlib.Path(tmp) / "absent.tsv"
            for policy in ("legacy", "widened"):
                r = run_plan(missing, policy)
                self.assertEqual(r.returncode, 0, r.stderr)
                self.assertEqual(r.stdout, "")
                self.assertIn("census=missing", r.stderr)

    def test_census_is_byte_equivalent_to_the_incumbent_awk(self):
        # THE REPLAY LAW: for every fixture the producer's census equals the
        # inline awk's — the replacement is behavior-identical at the census.
        fixtures = {
            "mixed": MIXED_MANIFEST,
            "static": CELL_HEADER + "0\t0\t0\t0\t0\tminecraft:stone\t\n",
            "all_dyn": CELL_HEADER + "".join(
                f"cell\t{i}\t0\t0\tcreate:cogwheel\t1\n" for i in range(77)),
            "empty": "",
            "header_only": CELL_HEADER,
        }
        with tempfile.TemporaryDirectory() as tmp:
            for name, text in fixtures.items():
                manifest = pathlib.Path(tmp) / f"{name}.tsv"
                manifest.write_text(text)
                awk = awk_census(manifest)
                self.assertTrue(awk.isdigit(), f"awk failed on {name}")
                r = run_plan(manifest, "legacy")
                produced = r.stderr.split("census=")[1].split()[0]
                self.assertEqual(produced, awk,
                                 f"census mismatch on {name}: "
                                 f"producer={produced} awk={awk}")

    def test_empty_manifest_does_not_probe_legacy(self):
        r = self.plan("", "legacy")
        self.assertEqual(r.returncode, 0, r.stderr)
        self.assertEqual(r.stdout, "")


if __name__ == "__main__":
    unittest.main(verbosity=2)
