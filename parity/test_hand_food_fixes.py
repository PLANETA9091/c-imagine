"""Pure-Python fake stacks: the OURS inspect reply guard (verify-empty 'str has no attribute get') and the
food_to lift used by survival-cr-roll-forward. No server, no client."""
import glob
import json
import os
import unittest

import run
import stacks


class _Fake(object):
    """Minimal stand-in for a Stack: scripted observe/setup answers."""
    player = "Tester"

    def __init__(self, food=20, saturation=0.0):
        self.food = food
        self.saturation = saturation
        self.cmds = []

    def setup_command(self, text):
        self.cmds.append(text)
        if text.startswith("effect give") and "saturation" in text:
            amp = int(text.split()[-1])
            self.food = min(20, self.food + amp + 1)

    def observe_player(self):
        return {"food": self.food, "saturation": self.saturation}

    def wait(self, ticks):
        return None


class InspectReplyGuardTests(unittest.TestCase):
    def _ours(self, replies):
        """OursStack without a live client: inspect() returns the scripted reply per subcommand."""
        s = stacks.OursStack.__new__(stacks.OursStack)
        s.player = "Tester"
        s.use_inspect = True
        s.inspect = lambda *a, **k: replies[a[0]]
        return s

    def test_hud_menu_ignores_non_object_inspect_reply(self):
        s = self._ours({"screen": "just a string"})
        self.assertIsNone(s._hud_menu())

    def test_hud_menu_reads_open_object(self):
        s = self._ours({"screen": {"open": True, "screen": "x", "slots": []}})
        s.inspect = lambda *a, **k: {"open": True, "screen": "x", "slots": []}
        self.assertEqual(s._hud_menu()["screen"], "x")
        s.inspect = lambda *a, **k: {"open": False}
        self.assertIsNone(s._hud_menu())

    def test_server_inv_non_object_is_none(self):
        s = self._ours({"inv": "CIM-INSPECT-like text"})
        self.assertIsNone(s._server_inv())

    def test_verify_empty_observe_does_not_raise_on_str_reply(self):
        # the verify-empty path: observe_screen -> _hud_menu must not raise on a str inspect reply
        s = self._ours({"screen": "text"})
        s.ctl = lambda *a, **k: {"screen": "", "title": None}
        scr = s._screen_reply()
        self.assertEqual(scr.get("screen"), "")

    def test_inspect_skips_bare_json_string_line(self):
        # _inspect must keep reading past a line whose JSON is a string, and return the object
        s = stacks.OursStack.__new__(stacks.OursStack)
        s.use_inspect = True
        s.inspect_hits = s.inspect_misses = 0
        d = os.path.expanduser("~/hud-work/horizon-play/scratch/parity-unit")
        os.makedirs(d, exist_ok=True)
        log = os.path.join(d, "latest.log")
        with open(log, "w") as f:
            f.write("[x] boot\n")

        def ctl(*a, **k):
            # the inspect command's own log output lands after the start offset
            with open(log, "a") as f:
                f.write('[x] CIM-INSPECT "not an object"\n')
                f.write('[x] CIM-INSPECT {"slots": []}\n')
        s._newest_log = lambda: log
        s.ctl = ctl
        real_sleep = stacks.time.sleep
        stacks.time.sleep = lambda _t: None
        try:
            self.assertEqual(s._inspect("inv", "Tester", wait=1.0), {"slots": []})
        finally:
            stacks.time.sleep = real_sleep


class FoodToLiftTests(unittest.TestCase):
    def test_food_below_target_is_lifted_to_exact_value(self):
        f = _Fake(food=14)
        out = run.food_to(f, 20, 600)
        self.assertEqual(out["food"], 20)
        self.assertIn("effect give Tester minecraft:saturation 1 5", f.cmds)
        self.assertIn("effect clear Tester minecraft:hunger", f.cmds)

    def test_food_one_below_target(self):
        f = _Fake(food=19)
        self.assertEqual(run.food_to(f, 20, 600)["food"], 20)
        self.assertIn("effect give Tester minecraft:saturation 1 0", f.cmds)

    def test_food_at_target_needs_no_lift(self):
        f = _Fake(food=20)
        run.food_to(f, 20, 600)
        self.assertFalse(any("saturation" in c for c in f.cmds))


class SurvivalScenarioShapeTests(unittest.TestCase):
    def test_no_modded_setblock_in_survival_scenarios(self):
        here = os.path.join(os.path.dirname(os.path.abspath(__file__)), "scenarios", "survival")
        bad = []
        for p in glob.glob(os.path.join(here, "*.json")):
            with open(p) as fh:
                for s in json.load(fh)["setup"]:
                    parts = s.split()
                    if parts[:1] == ["setblock"] and stacks.MODDED_NS_RE.match(parts[4]):
                        bad.append(os.path.basename(p))
        self.assertEqual(bad, [])


if __name__ == "__main__":
    unittest.main()
