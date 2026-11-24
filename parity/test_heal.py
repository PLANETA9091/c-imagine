#!/usr/bin/env python3
"""Unit tests for the pre-scenario heal (heal.py) and the infra verdict in diff.py.

Pure Python, no network, no game: fake stack objects replay scripted probe replies.
Run: python3 -I test_heal.py   (or python3 -I -m unittest test_heal from this directory)
"""
import os
import sys
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)

import diff  # noqa: E402
import heal  # noqa: E402


class FakeStack:
    """Replays probe replies in order (the last one repeats). Records every action."""

    def __init__(self, probes, respawn_fixes=True, close_fixes=True, click_fixes=True, ready_error=None):
        self.probes = list(probes)
        self.calls = []
        self.respawn_fixes = respawn_fixes
        self.close_fixes = close_fixes
        self.click_fixes = click_fixes
        self.ready_error = ready_error

    def heal_probe(self):
        self.calls.append("probe")
        p = self.probes.pop(0) if len(self.probes) > 1 else self.probes[0]
        if isinstance(p, Exception):
            raise p
        return p

    def heal_respawn(self):
        self.calls.append("respawn")
        if self.respawn_fixes:
            self.probes = [PLAYING]

    def heal_close(self):
        self.calls.append("close")
        if self.close_fixes:
            self.probes = [PLAYING]

    def heal_click(self, label):
        self.calls.append("click:%s" % label)
        if self.click_fixes:
            self.probes = [PLAYING]

    def heal_sleep(self, seconds):
        self.calls.append("sleep")

    def wait_ready(self, seconds=60):
        self.calls.append("wait-ready")
        if self.ready_error:
            raise self.ready_error


PLAYING = {"kind": "playing", "buttons": [], "enabled": []}
DEATH = {"kind": "death", "buttons": ["Respawn", "Title Screen"], "enabled": ["Respawn", "Title Screen"]}
PACK = {"kind": "playing", "buttons": ["Proceed", "Disconnect"], "enabled": ["Proceed", "Disconnect"]}
INV = {"kind": "inventory", "buttons": [], "enabled": []}
TITLE = {"kind": "title", "buttons": ["Singleplayer"], "enabled": ["Singleplayer"]}


class ClassifyTest(unittest.TestCase):
    def test_states(self):
        self.assertEqual(heal.classify(PLAYING), "playing")
        self.assertEqual(heal.classify(DEATH), "death")
        self.assertEqual(heal.classify(PACK), "pack")
        self.assertEqual(heal.classify(INV), "screen")
        self.assertEqual(heal.classify(TITLE), "title")

    def test_truth_death_screen_name(self):
        # truth-ctl reports the DeathScreen simple class name and no buttons
        self.assertEqual(heal.classify({"kind": "DeathScreen", "buttons": [], "enabled": []}), "death")

    def test_pack_needs_enabled_proceed(self):
        disabled = {"kind": "playing", "buttons": ["Proceed"], "enabled": []}
        self.assertEqual(heal.classify(disabled), "playing")

    def test_death_wins_over_pack(self):
        both = {"kind": "playing", "buttons": ["Proceed", "Respawn"], "enabled": ["Proceed", "Respawn"]}
        self.assertEqual(heal.classify(both), "death")

    def test_missing_fields_mean_playing(self):
        self.assertEqual(heal.classify({}), "playing")
        self.assertEqual(heal.classify({"kind": None}), "playing")


class HealTest(unittest.TestCase):
    def test_already_playing_only_waits_ready(self):
        st = FakeStack([PLAYING])
        r = heal.heal(st)
        self.assertTrue(r["ok"], r)
        self.assertEqual(r["actions"], ["wait-ready"])
        self.assertNotIn("respawn", st.calls)
        self.assertNotIn("close", st.calls)

    def test_death_screen_respawns(self):
        st = FakeStack([DEATH])
        r = heal.heal(st)
        self.assertTrue(r["ok"], r)
        self.assertEqual(r["actions"], ["respawn", "wait-ready"])
        self.assertLess(st.calls.index("respawn"), st.calls.index("wait-ready"))

    def test_open_gui_is_closed(self):
        st = FakeStack([INV])
        r = heal.heal(st)
        self.assertTrue(r["ok"], r)
        self.assertEqual(r["actions"], ["close inventory", "wait-ready"])

    def test_pack_prompt_clicks_proceed_and_waits(self):
        st = FakeStack([PACK])
        r = heal.heal(st)
        self.assertTrue(r["ok"], r)
        self.assertEqual(r["actions"], ["click Proceed", "wait-ready"])
        self.assertIn("click:Proceed", st.calls)
        self.assertIn("sleep", st.calls)

    def test_death_then_gui_then_playing(self):
        st = FakeStack([DEATH, INV, PLAYING], respawn_fixes=False, close_fixes=False)
        r = heal.heal(st)
        self.assertTrue(r["ok"], r)
        self.assertEqual(r["actions"], ["respawn", "close inventory", "wait-ready"])

    def test_title_screen_is_not_closed(self):
        st = FakeStack([TITLE])
        r = heal.heal(st)
        self.assertFalse(r["ok"])
        self.assertIn("title", r["error"])
        self.assertNotIn("close", st.calls)
        self.assertNotIn("wait-ready", st.calls)

    def test_still_dead_after_rounds_is_an_error(self):
        st = FakeStack([DEATH], respawn_fixes=False)
        r = heal.heal(st)
        self.assertFalse(r["ok"])
        self.assertIn("still not playing", r["error"])
        self.assertEqual(st.calls.count("respawn"), heal.MAX_ROUNDS)

    def test_probe_exception_is_reported_not_raised(self):
        st = FakeStack([RuntimeError("horizon-ctl screen returned 'x', not an object")])
        r = heal.heal(st)
        self.assertFalse(r["ok"])
        self.assertIn("RuntimeError", r["error"])

    def test_wait_ready_failure_is_reported(self):
        st = FakeStack([PLAYING], ready_error=RuntimeError("timeout"))
        r = heal.heal(st)
        self.assertFalse(r["ok"])
        self.assertIn("timeout", r["error"])


class InfraVerdictTest(unittest.TestCase):
    def test_observation_infra_error_is_error_not_different(self):
        t = {"result": {"state": "x"}}
        o = {"result": None, "error": "AttributeError: 'str' has no attribute 'get'", "infra": True}
        verdict, why = diff.compare(t, o)
        self.assertEqual(verdict, "error")
        self.assertIn("ours", why)

    def test_equal_and_different_unchanged(self):
        self.assertEqual(diff.compare({"result": 1}, {"result": 1})[0], "equal")
        self.assertEqual(diff.compare({"result": 1}, {"result": 2})[0], "different")

    def test_do_step_error_still_missing(self):
        verdict, _ = diff.compare({"result": None, "error": "boom"}, {"result": 1})
        self.assertEqual(verdict, "missing")


class TeardownAndGhostTests(unittest.TestCase):
    """Scenario teardown (heal ready=False) and the OURS ghost-container close (stacks.OursStack.heal_close)."""

    def test_teardown_closes_and_skips_wait_ready(self):
        st = FakeStack([INV])
        r = heal.heal(st, ready=False)
        self.assertTrue(r["ok"], r)
        self.assertEqual(r["actions"], ["close inventory"])
        self.assertNotIn("wait-ready", st.calls)
        self.assertEqual(r["screen"], "playing")

    def test_teardown_reports_a_screen_it_could_not_close(self):
        st = FakeStack([INV], close_fixes=False)
        r = heal.heal(st, ready=False)
        self.assertFalse(r["ok"])
        self.assertEqual(r["screen"], "inventory")

    def _ours(self, ctl_fn, hud_states, key_calls):
        import stacks

        st = object.__new__(stacks.OursStack)
        st.player = "Lane_B"
        st.ctl = ctl_fn
        st.key = lambda name, ticks=1: key_calls.append((name, ticks))
        states = list(hud_states)
        st._hud_menu = lambda: states.pop(0) if len(states) > 1 else states[0]
        return st

    def test_ours_ghost_container_closed_by_horizon_close(self):
        sent = []

        def ctl(*args, **kw):
            sent.append(args[0])
            if args[0] == "screen":
                return {"screen": "playing"}
            return {"screen": "playing"}

        keys = []
        # server has the furnace open before the close and not after it
        st = self._ours(ctl, [{"open": True, "menu_type": "furnace"}, None], keys)
        r = st.heal_close()
        self.assertEqual(r["closed"], "server-ghost")
        self.assertEqual(sent, ["screen", "close"])
        self.assertEqual(keys, [])

    def test_ours_hud_menu_falls_back_to_sneak(self):
        import stacks

        sent = []

        def ctl(*args, **kw):
            sent.append(args[0])
            if args[0] == "screen":
                return {"screen": "playing"}
            raise RuntimeError("horizon-ctl close rc=1: no menu open")

        keys = []
        open_hud = {"open": True, "menu_type": "create:toolbox"}
        st = self._ours(ctl, [open_hud, open_hud], keys)
        r = st.heal_close()
        self.assertEqual(r["closed"], "sneak")
        self.assertEqual(keys, [("key.sneak", 1)])
        self.assertIn("close", sent)

    def test_ours_world_with_no_menu_only_closes_once(self):
        sent = []

        def ctl(*args, **kw):
            sent.append(args[0])
            return {"screen": "playing"}

        keys = []
        st = self._ours(ctl, [None], keys)
        st.heal_close()
        self.assertEqual(sent, ["screen", "close"])
        self.assertEqual(keys, [])


if __name__ == "__main__":
    unittest.main()
