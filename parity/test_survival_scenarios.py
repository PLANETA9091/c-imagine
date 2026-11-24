#!/usr/bin/env python3
"""Schema check for the hand-made survival scenarios (scenarios/survival/*.json).

Uses the step schema of run.py (ast) and the validator in test_gen_census.py, so a survival scenario
that run.py would reject or mis-read fails here. Run: python3 -I test_survival_scenarios.py
"""
import glob
import json
import os
import sys
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import test_gen_census as t  # noqa: E402

SURVIVAL = os.path.join(HERE, "scenarios", "survival")


class SurvivalScenarios(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.do_names, cls.obs_names = t.runpy_schema(os.path.join(HERE, "run.py"))
        cls.paths = sorted(glob.glob(os.path.join(SURVIVAL, "*.json")))

    def test_directory_not_empty(self):
        self.assertTrue(self.paths, "no survival scenarios found")

    def test_every_scenario_validates(self):
        for path in self.paths:
            with self.subTest(path=os.path.basename(path)):
                with open(path, encoding="utf-8") as f:
                    sc = json.load(f)
                t.validate_scenario(sc, self.do_names, self.obs_names, where=os.path.basename(path))

    def test_ids_match_filenames_and_are_unique(self):
        ids = set()
        for path in self.paths:
            with open(path, encoding="utf-8") as f:
                sc = json.load(f)
            self.assertEqual(sc["id"] + ".json", os.path.basename(path))
            self.assertNotIn(sc["id"], ids)
            ids.add(sc["id"])

    def test_every_scenario_has_truth_note(self):
        for path in self.paths:
            with open(path, encoding="utf-8") as f:
                sc = json.load(f)
            self.assertIn("TRUTH", sc.get("notes", ""), sc["id"])


if __name__ == "__main__":
    unittest.main()
