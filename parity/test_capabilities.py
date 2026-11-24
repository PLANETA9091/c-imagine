#!/usr/bin/env python3
"""Tests for the runner capabilities: hold modifiers (sneak), respawn, box counts, entity lists, predicate
compare (diff.compare_rule), the 1.21.1 -> 26.3 enchantment rewrite, the OURS /keyboard key path and the
keyboard HUD geometry. No game: the stacks' call/ctl/inspect are replaced by recording fakes.

Run: python3 -I test_capabilities.py
"""
import glob
import json
import os
import re
import sys
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import diff  # noqa: E402
import keyboard_layout as kl  # noqa: E402
import stacks  # noqa: E402
import test_gen_census as tg  # noqa: E402


class Log:
    """Records calls in order; answers with a canned reply per command name."""

    def __init__(self, replies=None):
        self.calls = []
        self.replies = replies or {}

    def __call__(self, cmd, *args, **kw):
        self.calls.append((cmd,) + tuple(str(a) for a in args))
        r = self.replies.get(cmd, {})
        return r(*args) if callable(r) else r

    def names(self):
        return [c[0] for c in self.calls]


def truth_stack(replies=None):
    st = stacks.TruthStack()
    log = Log(replies)
    st.call = lambda cmd, args=None, opts=None, timeout=300.0: log(cmd, *(args or []))
    return st, log


def ours_stack(replies=None, inspect=None):
    st = stacks.OursStack(use_inspect=True, srv_dir="/nonexistent")
    log = Log(replies)
    st.ctl = lambda *args, timeout=300: log(*args)
    if inspect is not None:
        st.inspect = inspect
    return st, log


class HoldTests(unittest.TestCase):
    def test_holds_of(self):
        self.assertEqual(stacks.holds_of({"do": "interact", "sneak": True}), ("sneak",))
        self.assertEqual(stacks.holds_of({"do": "place", "hold": ["sneak"]}), ("sneak",))
        self.assertEqual(stacks.holds_of({"do": "use_item", "hold": 1.7}), ())  # use_item duration, not a modifier
        self.assertEqual(stacks.holds_of({"do": "break", "pos": [1, 2, 3]}), ())
        with self.assertRaises(ValueError):
            stacks.holds_of({"do": "place", "hold": ["shift"]})

    def test_truth_interact_holds_sneak_around_the_click(self):
        st, log = truth_stack({"interact": {"screen": "playing", "block": "minecraft:create:shaft"}})
        st.wait = lambda ticks: log("wait", ticks)
        st.interact([308, 121, 310], "up", holds=("sneak",))
        self.assertEqual(log.calls[0], ("key", "key.sneak", "press"))
        self.assertEqual(log.calls[1], ("wait", "2"))
        self.assertEqual(log.calls[2][0], "interact")
        self.assertEqual(log.calls[-1], ("key", "key.sneak", "release"))

    def test_release_happens_when_the_action_fails(self):
        st, log = truth_stack({})

        def boom(*a):
            raise RuntimeError("no reply")
        st.wait = lambda ticks: None
        log.replies["break"] = boom
        with self.assertRaises(RuntimeError):
            st.break_([1, 2, 3], 20, holds=("sneak",))
        self.assertEqual(log.calls[-1], ("key", "key.sneak", "release"))

    def test_ours_interact_uses_native_sneak_flag(self):
        st, log = ours_stack({"interact": {}, "screen": {"screen": "playing"}})
        st._settle = lambda ticks=6: None
        st._server_block_id = lambda pos: "create:shaft"
        st.interact([308, 121, 310], "up", holds=("sneak",))
        self.assertEqual(log.calls[0], ("interact", "308", "121", "310", "up", "--sneak"))
        self.assertNotIn("key", log.names())  # no shift press: the flag is the hold

    def test_ours_use_item_holds_shift_down_then_up(self):
        st, log = ours_stack({"use-item": {}})
        st._settle = lambda ticks=6: None
        st._counts = lambda: {}
        st.use_item(None, holds=("sneak",))
        self.assertEqual(log.calls[0], ("key", "shift", "down"))
        self.assertEqual(log.calls[1], ("wait", "2"))
        self.assertEqual(log.calls[2], ("use-item",))
        self.assertEqual(log.calls[-1], ("key", "shift", "up"))


class DurabilityObserveTests(unittest.TestCase):
    """observe inventory with "fields": the compared result carries the stacks' durability; without it, none."""

    CLIENT = {"hotbar": [{"slot": 0, "index": 0, "item": "minecraft:air", "count": 0, "damage": 0,
                          "max_damage": None},
                         {"slot": 1, "index": 1, "item": "create:wrench", "count": 1, "damage": 3,
                          "max_damage": 250}],
              "main": [{"slot": 9, "index": 9, "item": "minecraft:stone", "count": 4, "damage": 0,
                        "max_damage": None}],
              "armor": [{"slot": 3, "index": 3, "item": "minecraft:iron_helmet", "count": 1, "damage": 12,
                         "max_damage": 165}],
              "offhand": [{"slot": 0, "index": 0, "item": "minecraft:air", "count": 0, "damage": 0,
                           "max_damage": None}],
              "carried": None}

    def test_truth_fields_damage_only(self):
        st, _ = truth_stack({"inventory": self.CLIENT})
        out = st.observe_inventory(fields=["damage"])
        self.assertEqual(out["durability"], [
            {"slot": "armor.helmet", "item": "minecraft:iron_helmet", "damage": 12},
            {"slot": "hotbar:1", "item": "create:wrench", "damage": 3},
            {"slot": "main:9", "item": "minecraft:stone", "damage": 0}])

    def test_truth_fields_max_damage_too(self):
        st, _ = truth_stack({"inventory": self.CLIENT})
        out = st.observe_inventory(fields=["max_damage", "damage"])
        self.assertEqual(out["durability"][1], {"slot": "hotbar:1", "item": "create:wrench", "damage": 3,
                                                "max_damage": 250})

    def test_no_fields_no_durability_key(self):
        st, _ = truth_stack({"inventory": self.CLIENT})
        self.assertNotIn("durability", st.observe_inventory())

    def test_scenario_fields_validated(self):
        ok = {"observe": "inventory", "fields": ["damage"]}
        tg.validate_step(ok, set(), {"inventory"})
        with self.assertRaises(ValueError):
            tg.validate_step({"observe": "inventory", "fields": ["color"]}, set(), {"inventory"})
        tg.validate_step({"observe": "be", "pos": [1, 2, 3], "fields": ["x"]}, set(), {"be"})  # BE's own fields

    def test_bad_fields_rejected(self):
        st, _ = truth_stack({"inventory": self.CLIENT})
        with self.assertRaises(ValueError):
            st.observe_inventory(fields=["color"])
        with self.assertRaises(ValueError):
            st.observe_inventory(fields=[])

    def test_ours_fields_read_the_server_stacks(self):
        slots = [{"slot": "hotbar", "index": 1, "id": "create:wrench", "count": 1, "damage": 40,
                  "max_damage": None, "damage_source": "layer"},
                 {"slot": "hotbar", "index": 0, "id": None, "count": 0, "damage": 0, "max_damage": None,
                  "damage_source": None},
                 {"slot": "armor.helmet", "index": 36, "id": "minecraft:iron_helmet", "count": 1, "damage": 12,
                  "max_damage": 165, "damage_source": "paper"},
                 {"slot": "cursor", "index": -1, "id": "minecraft:stone", "count": 1, "damage": 0,
                  "max_damage": None, "damage_source": None}]
        st, log = ours_stack({"inventory": self.CLIENT})
        st._server_inv = lambda: slots
        out = st.observe_inventory(fields=["damage"])
        self.assertEqual(out["durability"], [
            {"slot": "armor.helmet", "item": "minecraft:iron_helmet", "damage": 12},
            {"slot": "hotbar:1", "item": "create:wrench", "damage": 40}])

    def test_ours_without_fields_is_the_counts_only(self):
        st, _ = ours_stack({"inventory": self.CLIENT})
        st._server_inv = lambda: [{"slot": "main", "index": 9, "id": "minecraft:stone", "count": 4,
                                   "damage": 0, "max_damage": None, "damage_source": "paper"}]
        out = st.observe_inventory()
        self.assertNotIn("durability", out)
        self.assertEqual(out["counts"], {"minecraft:stone": 4})


class RespawnBoxEntityTests(unittest.TestCase):
    def test_respawn_both_stacks(self):
        st, _ = truth_stack({"respawn": {"ok": True, "respawned": True}})
        self.assertEqual(st.respawn(), {"respawned": True})
        st, _ = ours_stack({"respawn": {"ok": True, "respawned": False}})
        self.assertEqual(st.respawn(), {"respawned": False})

    def test_truth_blocks_drops_air_and_counts(self):
        st, log = truth_stack({"blocks": {"ok": True, "counts": {"minecraft:air": 20, "minecraft:dirt": 1,
                                                                 "dynamictrees:oak_branch": 2}}})
        self.assertEqual(st.observe_blocks([309, 124, 311, 307, 120, 309]),
                         {"minecraft:dirt": 1, "dynamictrees:oak_branch": 2})
        self.assertEqual(log.calls[0], ("blocks", "307", "120", "309", "309", "124", "311"))

    def test_ours_blocks_without_inspect_is_the_client_count(self):
        # no CIM-INSPECT line (inspect answers None): the client count, as before
        st, log = ours_stack({"blocks": {"counts": {"minecraft:stone": 4}}})
        st.inspect = lambda *a, **k: None
        self.assertEqual(st.observe_blocks([0, 0, 0, 1, 1, 1]), {"minecraft:stone": 4})

    def test_ours_blocks_uses_the_bulk_inspect(self):
        st, log = ours_stack({"blocks": {"counts": {"minecraft:stone": 9}}})
        seen = []
        st.inspect = lambda *a, **k: seen.append(a) or {"kind": "blocks",
                                                       "counts": {"create:cogwheel": 1, "minecraft:stone": 3}}
        self.assertEqual(st.observe_blocks([0, 0, 0, 1, 1, 1]), {"create:cogwheel": 1, "minecraft:stone": 3})
        self.assertEqual(seen, [("blocks", 0, 0, 0, 1, 1, 1)])
        self.assertNotIn("blocks", log.names())  # the client count is not read when the bulk answers

    def test_ours_blocks_over_the_bulk_cap_reads_the_client(self):
        st, log = ours_stack({"blocks": {"counts": {"minecraft:stone": 2}}})
        st.inspect = lambda *a, **k: self.fail("a box over 4096 cells is not sent to inspect blocks")
        self.assertEqual(st.observe_blocks([0, 0, 0, 16, 16, 16]), {"minecraft:stone": 2})  # 17^3 = 4913

    def test_ours_blocks_reads_carriers_by_inspect(self):
        cells = {}

        def block(x, y, z):
            return cells[(x, y, z)]
        st, log = ours_stack({"blocks": {"counts": {"minecraft:note_block": 1, "minecraft:stone": 1}}})
        cells = {(0, 0, 0): {"state": "minecraft:note_block[instrument=harp]"},
                 (1, 0, 0): {"state": "minecraft:stone"},
                 (0, 0, 1): {"state": "minecraft:air"}, (1, 0, 1): {"state": "minecraft:air"}}
        st.ctl = lambda *args, timeout=300: block(*[int(a) for a in args[1:]]) if args[0] == "block" \
            else {"counts": {"minecraft:note_block": 1, "minecraft:stone": 1}}
        st.inspect = lambda cmd, *a, wait=8.0: {"state": "create:cogwheel[axis=y]"} if cmd == "block" else {}
        # the box holds 0..1 x 0..0 x 0..1 (4 cells); the air cell is skipped, the carrier is read by inspect
        out = st.observe_blocks([0, 0, 0, 1, 0, 1])
        self.assertEqual(out, {"create:cogwheel": 1, "minecraft:stone": 1})

    def test_ours_blocks_cap(self):
        st, _ = ours_stack({"blocks": {"counts": {"minecraft:barrier": 1}}})
        with self.assertRaises(ValueError):
            st.observe_blocks([0, 0, 0, 40, 40, 40])  # 41^3 cells > 512

    def test_truth_entities_filters_the_sphere_and_reads_items(self):
        ents = [{"type": "minecraft:item", "pos": [308.5, 121.2, 308.1], "item": "create:wrench", "count": 1},
                {"type": "minecraft:item", "pos": [330.0, 121.0, 308.0], "item": "create:wrench", "count": 1},
                {"type": "minecraft:pig", "pos": [309.0, 121.0, 309.0], "health": 10.0}]
        st, log = truth_stack({"state": {"player": {"position": [308.5, 121.0, 308.5]}},
                               "entities": {"entities": ents}})
        out = st.observe_entities([308, 121, 308], 8)["entities"]
        self.assertEqual(out, [{"type": "minecraft:item", "pos": [308, 121, 308], "item": "create:wrench",
                                "count": 1},
                               {"type": "minecraft:pig", "pos": [309, 121, 309]}])
        only_items = st.observe_entities([308, 121, 308], 8, "minecraft:item")["entities"]
        self.assertEqual([e["type"] for e in only_items], ["minecraft:item"])

    def test_ours_entities_from_inspect_skips_carriers_and_keeps_modded_ids(self):
        reply = {"entities": [
            {"type": "minecraft:item", "vanilla": "minecraft:item", "pos": [308.5, 121.1, 308.2],
             "item": {"id": "create:andesite_alloy", "count": 3}},
            {"type": "minecraft:item_display", "vanilla": "minecraft:item_display", "pos": [308.5, 121.0, 308.5]},
            {"type": "minecraft:item", "pos": [400, 121, 400], "item": {"id": "create:wrench", "count": 1}},
        ]}
        st, _ = ours_stack(inspect=lambda cmd, *a, wait=8.0: reply)
        out = st.observe_entities([308, 121, 308], 8)["entities"]
        self.assertEqual(out, [{"type": "minecraft:item", "pos": [308, 121, 308],
                                "item": "create:andesite_alloy", "count": 3}])


class TranslateTests(unittest.TestCase):
    def test_enchantment_levels_wrapper_is_rewritten_for_26_3(self):
        st, _ = ours_stack()
        self.assertEqual(st.translate('give Lane_B minecraft:enchanted_book 1 '
                                      'stored_enchantments={levels:{"minecraft:mending":1}}'),
                         'give Lane_B minecraft:enchanted_book 1 stored_enchantments={"minecraft:mending":1}')
        self.assertEqual(st.translate('item replace ... enchantments={levels:{"minecraft:sharpness":5}}'),
                         'item replace ... enchantments={"minecraft:sharpness":5}')

    def test_bare_maps_and_other_text_are_untouched(self):
        st, _ = ours_stack()
        text = 'give Lane_B minecraft:diamond_sword 1 enchantments={"minecraft:sharpness":5}'
        self.assertEqual(st.translate(text), text)


class KeyTests(unittest.TestCase):
    def test_vanilla_key_is_unchanged(self):
        st, log = ours_stack()
        st.key("key.jump", 1)
        self.assertEqual(log.calls, [("key", "space", "down"), ("wait", "1"), ("key", "space", "up")])

    def test_unknown_mod_key_still_refused(self):
        st, _ = ours_stack()
        with self.assertRaises(ValueError):
            st.key("keybinds.someone.else", 1)

    def test_keyboard_tap_for_combat_roll(self):
        st, log = ours_stack({"state": {"yaw": 10.0, "pitch": 5.0}})
        out = st.key("keybinds.combat_roll.roll", 1)
        self.assertEqual(out, {"pressed": True, "ticks": 1})
        dyaw, dpitch = kl.aim_deltas(*kl.cap_aim_point(82))
        self.assertEqual(log.calls[0], ("state",))
        self.assertEqual(log.calls[1], ("command", "keyboard"))
        self.assertIn(("mouse", "left", "click"), log.calls)
        look = [c for c in log.calls if c[0] == "look"]
        self.assertEqual(look[0], ("look", "%.4f" % (10.0 + dyaw), "%.4f" % (5.0 + dpitch)))
        self.assertEqual(look[-1], ("look", "10.0000", "5.0000"))  # the view is handed back
        self.assertEqual([c for c in log.calls if c == ("command", "keyboard")].__len__(), 2)  # open, close

    def test_keyboard_closes_even_when_the_click_fails(self):
        def fail_click(*a):
            if a and a[0] == "left":  # Log passes the mouse args without the command name
                raise RuntimeError("horizon down")
            return {}
        st, log = ours_stack({"state": {"yaw": 0.0, "pitch": 0.0}, "mouse": fail_click})
        with self.assertRaises(RuntimeError):
            st.key("keybinds.combat_roll.roll", 1)
        self.assertEqual(log.calls[-3], ("command", "keyboard"))  # closed in the finally, then the view is restored
        self.assertEqual(log.calls[-1], ("look", "0.0000", "0.0000"))

    def test_keyboard_press_is_one_click(self):
        st, log = ours_stack({"state": {"yaw": 0.0, "pitch": 0.0}})
        with self.assertRaises(ValueError):
            st.key("keybinds.combat_roll.roll", 20)
        self.assertEqual(log.calls, [])


class KeyboardLayoutTests(unittest.TestCase):
    def test_r_cap_position(self):
        self.assertEqual(kl.key_cap(82), (-91, 38))  # row 2, column 4 of the keys page
        self.assertEqual(kl.cap_aim_point(82), (-79, 45))
        self.assertEqual(kl.LAYOUT_KEYS.index(82), 34)

    def test_every_layout_key_has_a_cap_inside_the_screen(self):
        for k in kl.LAYOUT_KEYS:
            if k < 0:
                continue
            x, y = kl.key_cap(k)
            self.assertTrue(-213 <= x and x + kl.KEY_W <= 213, (k, x))
            self.assertTrue(y + kl.CAP_H <= 98, (k, y))

    def test_matches_the_java_source_when_present(self):
        path = os.path.expanduser("~/c-imagine/paper-plugin/src/main/java/dev/cim/hud/KeyboardState.java")
        if not os.path.exists(path):
            self.skipTest("KeyboardState.java not in this checkout")
        with open(path, encoding="utf-8") as fh:
            src = fh.read()
        body = src[src.index("LAYOUT = List.of("):]
        body = body[:body.index(");")]
        codes = []
        for m in re.finditer(r'c\("(?:[^"\\]|\\.)*", ([^)]+)\)', body):
            v = m.group(1).strip()
            codes.append({"CELL_HOLD": -2, "CELL_PAGE": -3}.get(v) if not v.lstrip("-").isdigit() else int(v))
        self.assertEqual(codes, kl.LAYOUT_KEYS)


class CompareTests(unittest.TestCase):
    def test_range_counts_within_bounds(self):
        spec = {"mode": "range", "field": "drops", "keys": {"minecraft:lapis_lazuli": [4, 9]}}
        self.assertEqual(diff.compare_rule({"drops": {"minecraft:lapis_lazuli": 4}, "x": 1},
                                           {"drops": {"minecraft:lapis_lazuli": 9}, "x": 1}, spec)[0], "equal")
        self.assertEqual(diff.compare_rule({"drops": {"minecraft:lapis_lazuli": 4}},
                                           {"drops": {"minecraft:lapis_lazuli": 10}}, spec)[0], "different")

    def test_same_set_of_ids_is_required_without_optional_ids(self):
        spec = {"mode": "range", "field": "drops", "keys": {"a": [1, 5]}}
        verdict, why = diff.compare_rule({"drops": {"a": 2}}, {"drops": {"b": 2}}, spec)
        self.assertEqual(verdict, "different")
        self.assertIn("present on one side", why)

    def test_optional_id_may_be_absent_on_one_side(self):
        spec = {"mode": "range", "field": "drops", "keys": {"dynamictrees:oak_seed": [0, 1]}}
        self.assertEqual(diff.compare_rule({"drops": {"dynamictrees:oak_seed": 1}},
                                           {"drops": {}}, spec)[0], "equal")
        self.assertEqual(diff.compare_rule({"drops": {"dynamictrees:oak_seed": 2}},
                                           {"drops": {}}, spec)[0], "different")

    def test_present_mode_only_checks_the_set(self):
        spec = {"mode": "present", "field": "drops"}
        self.assertEqual(diff.compare_rule({"drops": {"a": 1}}, {"drops": {"a": 99}}, spec)[0], "equal")
        self.assertEqual(diff.compare_rule({"drops": {"a": 1}}, {"drops": {"b": 1}}, spec)[0], "different")

    def test_ids_filter_ignores_other_ids(self):
        spec = {"mode": "range", "field": "drops", "ids": ["minecraft:oak_log"],
                "keys": {"minecraft:oak_log": [1, 16]}}
        self.assertEqual(diff.compare_rule({"drops": {"minecraft:oak_log": 3, "minecraft:stick": 1}},
                                           {"drops": {"minecraft:oak_log": 2}}, spec)[0], "equal")

    def test_entity_list_is_counted_by_item(self):
        spec = {"mode": "range", "field": "entities", "keys": {"create:wrench": [1, 1]}}
        t = {"entities": [{"type": "minecraft:item", "pos": [1, 2, 3], "item": "create:wrench", "count": 1}]}
        o = {"entities": [{"type": "minecraft:item", "pos": [9, 9, 9], "item": "create:wrench", "count": 1}]}
        self.assertEqual(diff.compare_rule(t, o, spec)[0], "equal")

    def test_other_fields_must_still_match(self):
        spec = {"mode": "present", "field": "drops"}
        verdict, why = diff.compare_rule({"drops": {"a": 1}, "block_after": "x"},
                                         {"drops": {"a": 1}, "block_after": "y"}, spec)
        self.assertEqual(verdict, "different")

    def test_compare_in_compare_step_uses_the_step_spec(self):
        t = {"step": {"do": "break", "compare": {"mode": "present", "field": "drops"}},
             "result": {"drops": {"a": 1}}}
        o = {"step": {"do": "break"}, "result": {"drops": {"a": 7}}}
        self.assertEqual(diff.compare(t, o)[0], "equal")
        o_plain = {"step": {"do": "break"}, "result": {"drops": {"a": 7}}}
        t_plain = {"step": {"do": "break"}, "result": {"drops": {"a": 1}}}
        self.assertEqual(diff.compare(t_plain, o_plain)[0], "different")

    def test_bad_spec_is_different_not_a_crash(self):
        t = {"step": {"compare": {"mode": "fuzzy"}}, "result": {}}
        o = {"step": {}, "result": {}}
        self.assertEqual(diff.compare(t, o)[0], "different")


class ValidatorTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.do, cls.obs = tg.runpy_schema(os.path.join(HERE, "run.py"))

    def test_new_steps_validate(self):
        ok = [
            {"do": "interact", "pos": [1, 2, 3], "face": "up", "sneak": True},
            {"do": "place", "pos": [1, 2, 3], "hold": ["sneak"]},
            {"do": "use_item", "hold": 1.7},
            {"do": "respawn"},
            {"observe": "blocks", "box": [0, 0, 0, 2, 2, 2]},
            {"observe": "entities", "pos": [1, 2, 3], "radius": 4, "type": "minecraft:item"},
            {"do": "break", "pos": [1, 2, 3], "compare": {"mode": "range", "field": "drops", "keys": {"a": [1, 2]}}},
        ]
        for step in ok:
            tg.validate_step(step, self.do, self.obs)

    def test_bad_steps_are_rejected(self):
        bad = [
            {"do": "break", "pos": [1, 2, 3], "sneak": "yes"},
            {"do": "select", "item": "x", "hold": ["sneak"]},
            {"do": "place", "pos": [1, 2, 3], "hold": ["shift"]},
            {"observe": "blocks"},
            {"observe": "entities", "pos": [1, 2, 3]},
            {"do": "break", "pos": [1, 2, 3], "compare": {"mode": "fuzzy"}},
            {"observe": "blocks", "box": [0, 0, 0]},
        ]
        for step in bad:
            with self.assertRaises(ValueError, msg=repr(step)):
                tg.validate_step(step, self.do, self.obs)

    def test_rewritten_survival_scenarios_validate(self):
        for f in glob.glob(os.path.join(HERE, "scenarios", "survival", "*.json")):
            with open(f, encoding="utf-8") as fh:
                tg.validate_scenario(json.load(fh), self.do, self.obs, f)


if __name__ == "__main__":
    unittest.main()
