#!/usr/bin/env python3
"""Pure-Python tests for gen_census.py and the batch options of all.py. Run: python3 -I test_gen_census.py

The step schema is read from run.py (ast): every `do` and `observe` name that run.py dispatches on, and
the keys it reads. A generated scenario must use only those names, with the required keys present and
the value types run.py accepts. If run.py gains a step name that REQUIRED does not know, the test fails.
The real census is used when it exists (PARITY_CENSUS or ~/hud-work/horizon-play/census); the synthetic
census below always runs, so the test never depends on the stand.
"""
import ast
import importlib.util
import json
import os
import re
import shutil
import sys
import tempfile
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import gen_census as g  # noqa: E402


def _load(name):
    spec = importlib.util.spec_from_file_location(name, os.path.join(HERE, name + ".py"))
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


batch = _load("all")

# ------------------------------------------------------------------ schema from run.py


def runpy_schema(path):
    """-> (do_names, observe_names) that run.py dispatches on, from its AST."""
    with open(path, encoding="utf-8") as f:
        tree = ast.parse(f.read())
    do_names, obs_names = set(), set()
    for node in ast.walk(tree):
        if not isinstance(node, ast.Compare) or len(node.ops) != 1 or not isinstance(node.ops[0], ast.Eq):
            continue
        left, right = node.left, node.comparators[0]
        if not (isinstance(right, ast.Constant) and isinstance(right.value, str)):
            continue
        if isinstance(left, ast.Name) and left.id == "do":
            do_names.add(right.value)
        elif isinstance(left, ast.Name) and left.id == "kind":
            obs_names.add(right.value)
    return do_names, obs_names


# keys each step needs: a tuple alternative means "one of these"
REQUIRED = {
    "select": [("item", "slot")],
    "break": [("pos",)],
    "place": [("pos",)],
    "interact": [("pos",)],
    "use_item": [],
    "use_at": [("point",)],
    "key": [("key",)],
    "click_slot": [("slot",)],
    "collect": [("pos",)],
    "open_inventory": [],
    "close": [],
    "tp": [("pos",)],
    "wait": [("ticks", "seconds")],
    "command": [("text",)],
    "food_to": [("value",)],
}
OBSERVE_REQUIRED = {"block": [("pos",)], "inventory": [], "screen": [], "player": [], "motion": []}
POS_KEYS = ("pos", "point")
SLOT_RE = re.compile(r"^(result|input|fuel|output|offhand|(grid|hotbar|main|container|armor):\d+)$")
SLOT_MAX = {"grid": 8, "hotbar": 8, "armor": 3}
IN_KINDS = ("inventory", "crafting", "furnace", "chest")
MODES = ("left", "right", "shift")
SETUP_WORDS = ("give", "setblock", "fill", "effect", "tp", "gamemode", "clear", "kill", "forceload")


def _is_num(x):
    return isinstance(x, (int, float)) and not isinstance(x, bool)


def _pos(x, key):
    if not (isinstance(x, list) and len(x) == 3 and all(_is_num(v) for v in x)):
        raise ValueError("%s must be [x, y, z] numbers, got %r" % (key, x))


def validate_step(step, do_names, obs_names):
    """Raise ValueError when run.py would reject or mis-read the step."""
    if not isinstance(step, dict):
        raise ValueError("step is not an object")
    has_obs, has_do = "observe" in step, "do" in step
    if has_obs == has_do:
        raise ValueError("step needs exactly one of observe/do: %r" % step)
    if has_obs:
        name = step["observe"]
        if name not in obs_names:
            raise ValueError("unknown observe %r (run.py: %s)" % (name, sorted(obs_names)))
        for alts in OBSERVE_REQUIRED.get(name, [("__unknown__",)]):
            if not any(k in step for k in alts):
                raise ValueError("observe %s needs %s" % (name, alts))
        if name == "block":
            _pos(step["pos"], "observe block pos")
        return
    name = step["do"]
    if name not in do_names:
        raise ValueError("unknown do %r (run.py: %s)" % (name, sorted(do_names)))
    for alts in REQUIRED.get(name, [("__unknown__",)]):
        if not any(k in step for k in alts):
            raise ValueError("do %s needs %s" % (name, " or ".join(alts)))
    for key in POS_KEYS:
        if key in step:
            _pos(step[key], key)
    if "slot" in step and name == "click_slot":
        slot = step["slot"]
        if not isinstance(slot, str) or not SLOT_RE.match(slot):
            raise ValueError("bad slot %r" % (slot,))
        m = re.match(r"^(grid|hotbar|main|container|armor):(\d+)$", slot)
        if m and m.group(1) in SLOT_MAX and int(m.group(2)) > SLOT_MAX[m.group(1)]:
            raise ValueError("slot %s out of range" % slot)
        if "in" in step and step["in"] not in IN_KINDS:
            raise ValueError("bad in %r" % (step["in"],))
        if step.get("mode", "left") not in MODES:
            raise ValueError("bad mode %r" % (step.get("mode"),))
    if name == "food_to" and not isinstance(step["value"], int):
        raise ValueError("food_to value must be an int")
    if name == "use_item" and "hold" in step and not _is_num(step["hold"]):
        raise ValueError("use_item hold must be a number")
    if name == "break" and "timeout" in step and not _is_num(step["timeout"]):
        raise ValueError("break timeout must be a number")


def validate_scenario(sc, do_names, obs_names, where="scenario"):
    if not isinstance(sc, dict):
        raise ValueError("%s is not an object" % where)
    for key in ("id", "setup", "steps"):
        if key not in sc:
            raise ValueError("%s: missing %s" % (where, key))
    if not isinstance(sc["id"], str) or not sc["id"]:
        raise ValueError("%s: bad id" % where)
    if not isinstance(sc["setup"], list) or not all(isinstance(s, str) and s for s in sc["setup"]):
        raise ValueError("%s: setup must be a list of non-empty strings" % where)
    for s in sc["setup"]:
        if s.split(" ", 1)[0] not in SETUP_WORDS:
            raise ValueError("%s: setup command %r is not one the runner's reset uses" % (where, s))
    if not isinstance(sc["steps"], list) or not sc["steps"]:
        raise ValueError("%s: steps must be a non-empty list" % where)
    for i, step in enumerate(sc["steps"]):
        try:
            validate_step(step, do_names, obs_names)
        except ValueError as e:
            raise ValueError("%s step %d: %s" % (where, i, e))


# ------------------------------------------------------------------ synthetic census

def synthetic_census():
    def row(**kw):
        base = {"ns": "", "id": "", "full_id": "", "in_server_registry": "yes", "has_blockstate": "yes",
                "has_loot_table": "yes", "loot_drops": "self", "tool_tag": "none", "requires_tool": "no",
                "category": "decoration", "is_container_or_menu": "", "in_server_registry_item": "yes",
                "block_item": "yes", "is_food": "no", "is_tool": "no", "max_stack": "", "has_recipe": "yes",
                "recipe_types": "", "display_name": ""}
        base.update(kw)
        return base
    blocks = [
        row(ns="create", id="cogwheel", full_id="create:cogwheel", tool_tag="pickaxe", requires_tool="no_tier_tag"),
        row(ns="create", id="zinc_ore", full_id="create:zinc_ore", tool_tag="pickaxe", requires_tool="yes:iron"),
        row(ns="create", id="copper_tiles", full_id="create:copper_tiles", tool_tag="pickaxe", requires_tool="yes:stone"),
        row(ns="create", id="fake_controller", full_id="create:fake_controller", tool_tag="none", requires_tool="no"),
        row(ns="create", id="fluid_x", full_id="create:fluid_x", category="fluid", tool_tag="none"),
        row(ns="create", id="black_toolbox", full_id="create:black_toolbox", tool_tag="axe"),
        row(ns="create", id="green_toolbox", full_id="create:green_toolbox", tool_tag="axe"),
        row(ns="create", id="stock_ticker", full_id="create:stock_ticker", tool_tag="axe"),
        row(ns="biomesoplenty", id="fir_log", full_id="biomesoplenty:fir_log", tool_tag="axe", requires_tool="no_tier_tag"),
        row(ns="biomesoplenty", id="rose", full_id="biomesoplenty:rose", tool_tag="none", has_loot_table="no"),
    ]
    items = [
        row(ns="create", id="cogwheel", full_id="create:cogwheel"),
        row(ns="create", id="zinc_ore", full_id="create:zinc_ore"),
        row(ns="create", id="copper_tiles", full_id="create:copper_tiles"),
        row(ns="create", id="fake_controller", full_id="create:fake_controller"),
        row(ns="create", id="fluid_x", full_id="create:fluid_x", block_item="yes"),
        row(ns="create", id="black_toolbox", full_id="create:black_toolbox"),
        row(ns="create", id="green_toolbox", full_id="create:green_toolbox"),
        row(ns="create", id="stock_ticker", full_id="create:stock_ticker"),
        row(ns="create", id="gone", full_id="create:gone", in_server_registry="no", block_item="yes"),
        row(ns="biomesoplenty", id="fir_log", full_id="biomesoplenty:fir_log"),
        row(ns="biomesoplenty", id="rose", full_id="biomesoplenty:rose"),
        row(ns="biomesoplenty", id="mushroom_stew", full_id="biomesoplenty:mushroom_stew", is_food="yes",
            block_item="no"),
        row(ns="create", id="guess_bar", full_id="create:guess_bar", is_food="guess"),
        row(ns="create", id="path/cog", full_id="create:path/cog", is_food="guess"),
        row(ns="create", id="wrench", full_id="create:wrench", is_tool="guess", block_item="no"),
        row(ns="create", id="cardboard_sword", full_id="create:cardboard_sword", is_tool="no", is_weapon="guess",
            block_item="no"),
        row(ns="create", id="sweet_roll", full_id="create:sweet_roll", is_food="no", block_item="no"),
        row(ns="create", id="honeyed_apple", full_id="create:honeyed_apple", is_food="guess", block_item="no"),
        row(ns="create", id="steam_engine", full_id="create:steam_engine", is_food="guess", block_item="no"),
        row(ns="create", id="andesite_alloy", full_id="create:andesite_alloy", block_item="no"),
        row(ns="create", id="shaft", full_id="create:shaft", block_item="no"),
        row(ns="create", id="andesite", full_id="create:andesite", block_item="no"),
    ]
    menus = [
        {"menu_id": "toolbox", "menu_full_id": "create:toolbox", "opener_kind": "block",
         "opener_id": "create:green_toolbox (and the other *_toolbox colors)", "source": "name",
         "confidence": "high", "note": "colors from registry", "registry_evidence": ""},
        {"menu_id": "schematic_table", "menu_full_id": "create:schematic_table", "opener_kind": "block",
         "opener_id": "create:stock_ticker", "source": "name", "confidence": "high", "note": "",
         "registry_evidence": ""},
        {"menu_id": "filter", "menu_full_id": "create:filter", "opener_kind": "item",
         "opener_id": "create:filter", "source": "name", "confidence": "high", "note": "",
         "registry_evidence": ""},
        {"menu_id": "gone_menu", "menu_full_id": "create:gone_menu", "opener_kind": "block",
         "opener_id": "create:not_a_block", "source": "guess", "confidence": "guess", "note": "",
         "registry_evidence": ""},
    ]
    recipes = [
        {"recipe_id": "biomesoplenty:fir_pattern", "ns": "biomesoplenty", "type": "minecraft:crafting_shaped",
         "result": "x", "ingredients": "", "jar": ""},
        {"recipe_id": "create:tiles_tag", "ns": "create", "type": "minecraft:crafting_shaped",
         "result": "x", "ingredients": "", "jar": ""},
        {"recipe_id": "create:shapeless", "ns": "create", "type": "minecraft:crafting_shapeless",
         "result": "x", "ingredients": "", "jar": ""},
    ]
    return g.Census(blocks, items, menus, recipes)


RECIPE_JSON = {
    "biomesoplenty:fir_pattern": {
        "type": "minecraft:crafting_shaped",
        "pattern": ["AB ", "B  "],
        "key": {"A": {"item": "biomesoplenty:fir_log"}, "B": [{"item": "minecraft:stick"}, {"item": "minecraft:oak_planks"}]},
        "result": {"item": "minecraft:stick", "count": 4},
    },
    "create:tiles_tag": {
        "type": "minecraft:crafting_shaped",
        "pattern": ["###", " # ", "   "],
        "key": {"#": {"tag": "c:glass_blocks/colorless"}},
        "result": {"item": "create:cogwheel", "count": 1},
    },
}


def synthetic_recipe_json(rid):
    return RECIPE_JSON.get(rid)


# ------------------------------------------------------------------ tests

class TestRulesAndNames(unittest.TestCase):
    def test_break_tool_tier_and_bare_hand(self):
        self.assertEqual(g.break_tool({"tool_tag": "pickaxe", "requires_tool": "no_tier_tag"})[0], "minecraft:wooden_pickaxe")
        self.assertEqual(g.break_tool({"tool_tag": "pickaxe", "requires_tool": "yes:stone"})[0], "minecraft:stone_pickaxe")
        self.assertEqual(g.break_tool({"tool_tag": "pickaxe", "requires_tool": "yes:iron"})[0], "minecraft:iron_pickaxe")
        self.assertEqual(g.break_tool({"tool_tag": "hoe", "requires_tool": "no"})[0], "minecraft:wooden_hoe")
        self.assertIsNone(g.break_tool({"tool_tag": "none", "requires_tool": "no"})[0])

    def test_technical_reasons(self):
        c = synthetic_census()
        self.assertEqual(g.technical_reason(c, "create:cogwheel"), "")
        self.assertIn("technical", g.technical_reason(c, "create:fake_controller"))
        self.assertIn("fluid", g.technical_reason(c, "create:fluid_x"))
        self.assertIn("registry", g.technical_reason(c, "create:gone"))
        self.assertIn("block item", g.technical_reason(c, "create:andesite"))

    def test_menu_expansion_toolbox_and_skip(self):
        c = synthetic_census()
        tb = [m for m in c.menus if m["menu_id"] == "toolbox"][0]
        self.assertEqual(g.menu_openers(c, tb), ["create:black_toolbox", "create:green_toolbox"])
        ss = [m for m in c.menus if m["menu_id"] == "schematic_table"][0]
        self.assertEqual(g.menu_openers(c, ss), ["create:stock_ticker"])

    def test_pattern_grid_and_alternatives(self):
        c = synthetic_census()
        ingr, result, count, why = g.parse_shaped(c, RECIPE_JSON["biomesoplenty:fir_pattern"])
        self.assertEqual(why, "")
        self.assertEqual([i for i, _ in ingr], ["biomesoplenty:fir_log", "minecraft:stick"])  # pattern order
        d = dict(ingr)
        self.assertEqual(d["biomesoplenty:fir_log"], [0])
        self.assertEqual(d["minecraft:stick"], [1, 3])     # first alternative is vanilla, always registered
        self.assertEqual(result, "minecraft:stick")
        self.assertEqual(count, 4)

    def test_tag_key_is_refused(self):
        c = synthetic_census()
        ingr, _, _, why = g.parse_shaped(c, RECIPE_JSON["create:tiles_tag"])
        self.assertIsNone(ingr)
        self.assertIn("tag", why)

    def test_sample_even(self):
        self.assertEqual(g.sample_even(list(range(10)), 3), [0, 4, 9])  # round-half-even at 4.5
        self.assertEqual(g.sample_even(list(range(3)), 20), [0, 1, 2])
        self.assertEqual(g.sample_even([], 5), [])

    def test_shard_partition(self):
        items = ["s%d" % i for i in range(11)]
        parts = [batch.shard_items(items, i, 3) for i in (1, 2, 3)]
        self.assertEqual(sorted(sum(parts, [])), sorted(items))
        self.assertEqual(len(set(sum(parts, []))), 11)
        self.assertEqual(batch.shard_items(items, 1, 1), items)
        with self.assertRaises(ValueError):
            batch.shard_items(items, 4, 3)


class TestSyntheticGeneration(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.do_names, cls.obs_names = runpy_schema(os.path.join(HERE, "run.py"))
        cls.census = synthetic_census()
        cls.scen, cls.skipped = g.generate(cls.census, list(g.FAMILIES), 20, synthetic_recipe_json)

    def test_schema_covers_run_py(self):
        self.assertEqual(self.do_names - set(REQUIRED), set(), "run.py do-names without a REQUIRED entry")
        self.assertEqual(self.obs_names - set(OBSERVE_REQUIRED), set(), "run.py observe-names without an entry")
        self.assertIn("food_to", self.do_names)

    def test_all_synthetic_validate(self):
        for fam, items in self.scen.items():
            for sid, sc in items:
                validate_scenario(sc, self.do_names, self.obs_names, where=sid)

    def test_place_break_contents(self):
        ids = {sid: sc for sid, sc in self.scen["place-break"]}
        self.assertIn("pb-create-cogwheel", ids)
        self.assertNotIn("pb-create-fake_controller", ids)
        sc = ids["pb-create-zinc_ore"]
        self.assertIn("give {player} minecraft:iron_pickaxe 1", sc["setup"])
        ops = [s.get("do") or s.get("observe") for s in sc["steps"]]
        self.assertEqual(ops[:4], ["tp", "select", "place", "block"])
        self.assertIn("break", ops)
        bare = ids["pb-biomesoplenty-rose"]
        self.assertFalse(any("pickaxe" in s or "axe" in s for s in bare["setup"]))

    def test_interact_and_skips(self):
        ids = [sid for sid, _ in self.scen["interact"]]
        self.assertIn("ia-toolbox--create-green_toolbox", ids)
        self.assertIn("ia-schematic_table--create-stock_ticker", ids)
        reasons = " | ".join("%s %s %s" % r for r in self.skipped)
        self.assertIn("item opener", reasons)
        self.assertIn("not a placeable registry block", reasons)

    def test_craft_scenario_shape(self):
        ids = {sid: sc for sid, sc in self.scen["craft"]}
        sc = ids["cr-biomesoplenty-fir_pattern"]
        clicks = [s for s in sc["steps"] if s.get("do") == "click_slot"]
        self.assertEqual(clicks[0]["slot"], "hotbar:0")
        self.assertTrue(all(c["in"] == "crafting" for c in clicks))
        self.assertTrue(any(s.startswith("give {player} biomesoplenty:fir_log 1") for s in sc["setup"]))

    def test_ids_unique_and_safe(self):
        seen = set()
        for fam, items in self.scen.items():
            for sid, _ in items:
                self.assertNotIn(sid, seen)
                seen.add(sid)
                self.assertRegex(sid, r"^[a-z0-9_.:-]+$")

    def test_write_and_reread(self):
        tmp = tempfile.mkdtemp(prefix="gencensus-")
        try:
            for fam in g.FAMILIES:
                g.write_family(tmp, fam, self.scen[fam])
            g.write_skipped(tmp, self.skipped)
            for fam in g.FAMILIES:
                files = [f for f in os.listdir(os.path.join(tmp, fam)) if f.endswith(".json")]
                self.assertEqual(len(files), len(self.scen[fam]))
                for f in files:
                    with open(os.path.join(tmp, fam, f), encoding="utf-8") as fh:
                        sc = json.load(fh)
                    self.assertEqual(f, sc["id"] + ".json")
                    validate_scenario(sc, self.do_names, self.obs_names, where=f)
        finally:
            shutil.rmtree(tmp, ignore_errors=True)

    def test_validator_rejects_bad_steps(self):
        base = {"id": "x", "setup": [], "steps": [{"do": "break", "pos": [1, 2, 3]}]}
        validate_scenario(base, self.do_names, self.obs_names)
        bad = [
            {"do": "smash", "pos": [1, 2, 3]},
            {"do": "break"},
            {"do": "break", "pos": [1, 2]},
            {"observe": "entities"},
            {"observe": "block"},
            {"do": "click_slot", "slot": "grid:9", "in": "crafting"},
            {"do": "click_slot", "slot": "hotbar:0", "in": "anvil"},
            {"do": "food_to", "value": 18.5},
            {"do": "select"},
            {"do": "break", "pos": [1, 2, 3], "observe": "block"},
        ]
        for step in bad:
            sc = {"id": "x", "setup": [], "steps": [step]}
            with self.assertRaises(ValueError, msg=repr(step)):
                validate_scenario(sc, self.do_names, self.obs_names)
        with self.assertRaises(ValueError):
            validate_scenario({"id": "x", "setup": ["smash everything"], "steps": [{"observe": "inventory"}]},
                              self.do_names, self.obs_names)


class TestFoodAndToolFamilies(unittest.TestCase):
    """Food comes from MODDED_FOOD (jar bytecode), not the census guess; tools from MODDED_TOOLS."""

    @classmethod
    def setUpClass(cls):
        cls.census = synthetic_census()
        cls.scen, cls.skipped = g.generate(cls.census, list(g.FAMILIES), 20, synthetic_recipe_json)
        cls.food = {sid: sc for sid, sc in cls.scen["item-use"]}
        cls.tool = {sid: sc for sid, sc in cls.scen["tool"]}

    def test_food_from_jar_evidence_only(self):
        self.assertIn("fu-create-honeyed_apple", self.food)   # census guess, jar-confirmed
        self.assertIn("fu-create-sweet_roll", self.food)      # census said no, jar-confirmed
        self.assertIn("fu-biomesoplenty-mushroom_stew", self.food)  # census is_food=yes
        self.assertNotIn("fu-create-steam_engine", self.food)  # census guess, not food
        self.assertNotIn("fu-create-guess_bar", self.food)
        skipped = {(f, src) for f, src, _ in self.skipped}
        self.assertIn(("item-use", "create:steam_engine"), skipped)
        self.assertIn("food evidence", self.food["fu-create-honeyed_apple"]["notes"])

    def test_every_modded_food_is_in_table(self):
        for fid in g.MODDED_FOOD:
            self.assertTrue(fid.startswith("create:"), fid)

    def test_tool_family_contents(self):
        sc = self.tool["tl-create-cardboard_sword"]
        self.assertIn("setblock 308 121 311 minecraft:short_grass", sc["setup"])
        self.assertIn("give {player} create:cardboard_sword 1", sc["setup"])
        ops = [s.get("do") or s.get("observe") for s in sc["steps"]]
        self.assertEqual(ops, ["tp", "select", "block", "inventory", "break", "collect", "block", "inventory"])
        self.assertNotIn("tl-create-steam_engine", self.tool)
        self.assertIn(("tool", "create:wrench"), {(f, s) for f, s, _ in self.skipped})

    def test_tool_kind_blocks_cover_every_kind(self):
        kinds = set(v["kind"] for v in g.MODDED_TOOLS.values())
        self.assertTrue(kinds <= set(g.TOOL_BREAK))
        self.assertEqual(set(g.TOOL_BREAK), {"pickaxe", "axe", "shovel", "hoe", "sword"})


class TestDurabilityNormalization(unittest.TestCase):
    """The runner records durability beside the compared result (run.py), from the raw inventory reply."""

    @classmethod
    def setUpClass(cls):
        cls.stacks = _load("stacks")

    def test_truth_reply_has_no_durability_fields(self):
        reply = {"hotbar": [{"slot": 0, "index": 0, "item": "minecraft:diamond_pickaxe", "count": 1}],
                 "main": [], "armor": [], "offhand": {"slot": 0, "index": 0, "item": "minecraft:air", "count": 0},
                 "carried": None}
        d = self.stacks.norm_durability(reply)
        self.assertEqual(d["status"], "not_reported")
        self.assertEqual(d["stacks"], [])

    def test_horizon_reply_damage_only(self):
        reply = {"hotbar": [{"count": 1, "item": "minecraft:oak_planks", "slot": 0},
                            {"count": 1, "damage": 9, "item": "minecraft:diamond_pickaxe", "slot": 3,
                             "components": "{\"minecraft:damage\":9}"}],
                 "main": [], "armor": [], "carried": None}
        d = self.stacks.norm_durability(reply)
        self.assertEqual(d["status"], "reported")
        self.assertEqual(d["fields"], ["damage"])
        self.assertEqual(d["stacks"], [{"group": "hotbar", "slot": 3, "item": "minecraft:diamond_pickaxe",
                                        "count": 1, "damage": 9, "max_damage": None}])

    def test_run_py_keeps_durability_out_of_result(self):
        with open(os.path.join(HERE, "run.py"), encoding="utf-8") as f:
            src = f.read()
        self.assertIn('rec["durability"] = stack.last_durability()', src)


class TestRealCensus(unittest.TestCase):
    """Generates from the real census when present and validates every file (the lead's acceptance gate)."""

    def test_real_census_validates(self):
        root = os.environ.get("PARITY_CENSUS", os.path.expanduser("~/hud-work/horizon-play/census"))
        mods = os.environ.get("PARITY_MODS", os.path.expanduser("~/hud-work/horizon-play/mods"))
        if not os.path.isfile(os.path.join(root, "blocks.csv")):
            self.skipTest("no census at %s" % root)
        census = g.Census.from_dir(root)
        scen, skipped = g.generate(census, list(g.FAMILIES), 20, g.JarRecipes(mods))
        do_names, obs_names = runpy_schema(os.path.join(HERE, "run.py"))
        self.assertEqual(do_names - set(REQUIRED), set())
        tmp = tempfile.mkdtemp(prefix="gencensus-real-")
        try:
            n = 0
            for fam in g.FAMILIES:
                g.write_family(tmp, fam, scen[fam])
                for sid, sc in scen[fam]:
                    validate_scenario(sc, do_names, obs_names, where=sid)
                    n += 1
            g.write_skipped(tmp, skipped)
            self.assertGreater(len(scen["place-break"]), 900)
            self.assertGreater(len(scen["craft"]), 0)
            print("\nreal census: validated %d scenarios; per family %s" % (
                n, ", ".join("%s=%d" % (f, len(scen[f])) for f in g.FAMILIES)))
        finally:
            shutil.rmtree(tmp, ignore_errors=True)



FAKE_RUN = """#!/usr/bin/env python3
import json, sys
args = sys.argv[1:]
stack = args[args.index("--stack") + 1]
scen = args[args.index("-o") - 1]
out = args[args.index("-o") + 1]
sc = json.load(open(scen, encoding="utf-8"))
sid = sc["id"]
if "crash" in sid:
    print("boom"); sys.exit(1)
rec_setup = [{"cmd": "heal", "ok": True}]
if "infra" in sid and stack == "ours":
    rec_setup.append({"cmd": "wait-ready", "ok": False, "error": "timed out"})
steps = []
for i, step in enumerate(sc["steps"]):
    val = {"v": 1}
    if "diffme" in sid and stack == "ours" and "observe" in step:
        val = {"v": 2}
    steps.append({"i": i, "step": step, "kind": "observe" if "observe" in step else "do",
                  "result": val, "error": None})
json.dump({"scenario": sid, "stack": stack, "setup": rec_setup, "steps": steps}, open(out, "w"))
print("%s %s: %d steps, 0 errors -> %s" % (stack, sid, len(steps), out))
"""


class TestBatch(unittest.TestCase):
    """all.py batch mode on fake stacks: verdicts, CSV report, shards, resume. Never touches a stand."""

    @classmethod
    def setUpClass(cls):
        cls.tmp = tempfile.mkdtemp(prefix="batch-test-")
        for name in ("all.py", "diff.py"):
            shutil.copy(os.path.join(HERE, name), os.path.join(cls.tmp, name))
        with open(os.path.join(cls.tmp, "run.py"), "w", encoding="utf-8") as f:
            f.write(FAKE_RUN)
        sdir = os.path.join(cls.tmp, "scen", "fam")
        os.makedirs(sdir)
        steps = [{"do": "select", "item": "minecraft:stone"}, {"observe": "block", "pos": [1, 2, 3]}]
        for sid in ("ok-a", "ok-b", "diffme-c", "crash-d", "infra-e", "ok-f"):
            with open(os.path.join(sdir, sid + ".json"), "w", encoding="utf-8") as f:
                json.dump({"id": sid, "setup": [], "steps": steps}, f)
        with open(os.path.join(cls.tmp, "scen", "fam", "_skip.json"), "w", encoding="utf-8") as f:
            json.dump({"id": "skipme", "setup": [], "steps": steps}, f)

    @classmethod
    def tearDownClass(cls):
        shutil.rmtree(cls.tmp, ignore_errors=True)

    def _run(self, extra, results):
        import subprocess
        env = dict(os.environ, PARITY_RESULTS=results)
        cmd = [sys.executable, "-I", os.path.join(self.tmp, "all.py"), "--dir", "scen/fam", "--no-shots"] + extra
        p = subprocess.run(cmd, cwd=self.tmp, env=env, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=300)
        self.assertEqual(p.returncode, 0, p.stdout.decode("utf-8", "replace"))

    def _rows(self, path):
        import csv
        with open(path, encoding="utf-8", newline="") as f:
            return {r["scenario"]: r for r in csv.DictReader(f)}

    def test_verdicts_and_report(self):
        results = os.path.join(self.tmp, "res1")
        csv_path = os.path.join(results, "REPORT.csv")
        self._run(["--csv", csv_path], results)
        rows = self._rows(csv_path)
        self.assertEqual(set(rows), {"ok-a", "ok-b", "diffme-c", "crash-d", "infra-e", "ok-f"})  # _ files skipped
        self.assertEqual(rows["ok-a"]["verdict"], "EQUAL")
        self.assertEqual(rows["diffme-c"]["verdict"], "DIFFERENT")
        self.assertEqual(rows["diffme-c"]["first_diff_step"], "1")
        self.assertIn("observe block", rows["diffme-c"]["reason"])
        self.assertEqual(rows["crash-d"]["verdict"], "ERROR")          # run.py rc=1: infra, never DIFFERENT
        self.assertIn("run.py failed", rows["crash-d"]["reason"])
        self.assertEqual(rows["infra-e"]["verdict"], "ERROR")          # failed wait-ready on a stack
        self.assertIn("wait-ready", rows["infra-e"]["reason"])
        self.assertTrue(os.path.exists(os.path.join(results, "SUMMARY.md")))

    def test_shards_partition_and_resume(self):
        results = os.path.join(self.tmp, "res2")
        csv_path = os.path.join(results, "shards.csv")
        self._run(["--shard", "1/2", "--csv", csv_path], results)
        first = set(self._rows(csv_path))
        self._run(["--shard", "2/2", "--csv", csv_path], results)
        both = set(self._rows(csv_path))
        self.assertEqual(len(both), 6)
        self.assertEqual(len(first) + 3, len(both))
        self.assertTrue(os.path.exists(os.path.join(results, "SUMMARY-1of2.md")))
        self._run(["--resume", "--csv", csv_path], results)            # nothing new to run
        self.assertEqual(len(self._rows(csv_path)), 6)


if __name__ == "__main__":
    unittest.main(verbosity=2)
