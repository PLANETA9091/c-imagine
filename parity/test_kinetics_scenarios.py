#!/usr/bin/env python3
"""Schema check for scenarios/kinetics/*.json against run.py. Run: python3 -I test_kinetics_scenarios.py

Reuses validate_scenario and runpy_schema from test_gen_census.py (run.py is read with ast, never imported).
Extra rules for hand-made kinetics scenarios: every Create block is placed by a `place` step (no setblock in
setup, no Create id in a fill), and every file has notes and a todo (or an empty todo) field.
"""
import glob
import json
import os
import re
import sys
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import test_gen_census as t  # noqa: E402

KDIR = os.path.join(HERE, "scenarios", "kinetics")
DO, OBS = t.runpy_schema(os.path.join(HERE, "run.py"))


class KineticsScenarios(unittest.TestCase):
    def files(self):
        return sorted(glob.glob(os.path.join(KDIR, "*.json")))

    def test_dir_not_empty(self):
        self.assertGreaterEqual(len(self.files()), 10)

    def test_every_file_validates(self):
        for path in self.files():
            with self.subTest(path=os.path.basename(path)):
                with open(path, encoding="utf-8") as f:
                    sc = json.load(f)
                t.validate_scenario(sc, DO, OBS, where=os.path.basename(path))
                self.assertIn("notes", sc)
                self.assertIn("todo", sc)

    def test_id_matches_file(self):
        for path in self.files():
            with open(path, encoding="utf-8") as f:
                sc = json.load(f)
            self.assertEqual(sc["id"] + ".json", os.path.basename(path))

    def test_no_setblock_or_create_fill(self):
        create_re = re.compile(r"\bcreate:")
        for path in self.files():
            with open(path, encoding="utf-8") as f:
                sc = json.load(f)
            for s in sc["setup"]:
                word = s.split(" ", 1)[0]
                self.assertNotEqual(word, "setblock", os.path.basename(path))
                if word == "fill":
                    self.assertFalse(create_re.search(s), "%s: Create block in setup fill" % os.path.basename(path))

    def test_survival_and_reset_default(self):
        for path in self.files():
            with open(path, encoding="utf-8") as f:
                sc = json.load(f)
            self.assertIsNot(sc.get("reset"), False, os.path.basename(path))


if __name__ == "__main__":
    unittest.main(verbosity=2)
