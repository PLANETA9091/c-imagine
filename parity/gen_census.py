#!/usr/bin/env python3
"""Generate census-driven parity scenarios (families: place-break, interact, item-use, craft).

Usage: python3 -I gen_census.py [--census DIR] [--mods DIR] [--out DIR]
                                [--craft-per-mod N] [--families place-break,interact,item-use,craft]

Inputs (census directory, from the census builder):
  blocks.csv, items.csv, menus.csv, recipes.csv
Inputs (mods directory, read-only): the mod jars. The census CSV keeps the recipe ingredient list
but loses the pattern-key mapping (which character is which item), so shaped recipes are read from
the jar's recipe JSON (data/<ns>/recipe/<path>.json). Only the crafting family needs the jars.

Output: OUT/<family>/<id>.json, one scenario per file (the family directory is rebuilt on each run),
and OUT/_skipped.csv (family, source, reason) for everything the census gave but no scenario was made.

Families and ids (all scenarios use the runner's standard reset prelude: survival, cleared inventory,
platform rebuilt, player at 308 121 308; every scenario also refills its own target cell):
  place-break  pb-<ns>-<name>   give the block item (+ tool from the census tool tag and tier),
               place it at 308 121 311, observe block, break, collect, observe block + inventory.
  interact     ia-<menu>--<ns>-<name>   place the menu block at 308 121 310, interact, observe screen, close.
               Only menus.csv rows with opener_kind=block (item openers are not in this family).
  item-use     fu-<ns>-<name>   food (MODDED_FOOD, from the jar bytecode): food_to 18, use_item hold 2 s,
               observe player and inventory.
  tool         tl-<ns>-<name>   modded tools with a real tool class (MODDED_TOOLS, from the jar bytecode):
               a vanilla block for the tool kind is set at 308 121 311, the tool is selected, the block and the
               inventory (with durability) are observed, the block is broken and collected, observe again.
  craft        cr-<ns>-<recipe>  crafting_shaped only; sample evenly spaced from the feasible recipes of each
               mod (default 20 per mod). Crafting table placed, interact, ingredients click_slot'ed into grid
               cells (left-pick the hotbar stack, right-click one cell per item), observe screen (result).

Assumptions (recorded in each scenario's notes): `give` fills hotbar slots 0,1,2... in setup order
when the inventory is empty (vanilla behaviour; modded `give` on OURS is translated to /cim give,
not verified here). A mismatch shows up in the first observe inventory step of craft scenarios.
"""
import argparse
import csv
import glob
import io
import json
import os
import re
import sys
import zipfile

HERE = os.path.dirname(os.path.abspath(__file__))
FAMILIES = ("place-break", "interact", "item-use", "craft", "tool")

STAND = [308.5, 121, 308.5]       # player stands here (facing +z)
PB_POS = [308, 121, 311]          # place-break target, 3 blocks in front of the player
MENU_POS = [308, 121, 310]        # interact / craft target
TOOL_TAGS = ("pickaxe", "axe", "shovel", "hoe")
TECH_RE = re.compile(r"controller|liquid|_fluid$|void|moving_piston|virtual|debug|_tile$|structure_block|jigsaw|^air$")
SAFE_RE = re.compile(r"[^a-z0-9_.-]")

# Modded food, decided from the jar bytecode (NOT from the census name guess, which picked non-food such
# as create:steam_engine and missed create:sweet_roll). Evidence: create-1.21.1-6.0.10 AllItems.class. Every
# food item's registration calls Item$Properties.food(FoodProperties) inside a properties lambda
# (lambda$static$N). The item is the ldc name string that precedes that lambda's invokedynamic in <clinit>
# (static {}), with only the registrate item(...)/tag(...) calls between them. Method: javap -c -v on the
# class, bootstrap-method table for lambda targets, scan of static {} in order (scratch/censusgen2/map2.py).
# Nutrition/saturation are the FoodProperties$Builder constants of the same lambda.
# Scan coverage: only create has FoodProperties / world/food references (7 classes; the 5 food() calls
# are all in AllItems). biomesoplenty, dynamictrees and the other jars reference no world/food class and
# no DataComponents.FOOD/CONSUMABLE, so they have no food items. create:blaze_cake, create:chocolate_bucket,
# create:pulp and the dynamictrees seeds/branches are census is_food=guess but have no food() call.
MODDED_FOOD = {
    "create:bar_of_chocolate": "AllItems lambda$static$1: nutrition 6, saturation 0.3",
    "create:sweet_roll": "AllItems lambda$static$2: nutrition 6, saturation 0.8 (census said is_food=no)",
    "create:chocolate_glazed_berries": "AllItems lambda$static$3: nutrition 7, saturation 0.8",
    "create:honeyed_apple": "AllItems lambda$static$4: nutrition 8, saturation 0.8",
    "create:builders_tea": "AllItems lambda$static$6: nutrition 16, saturation 0.6, alwaysEdible, effect, stacksTo 16",
}

# Modded tools with a real tool class, from the jar bytecode. The census is_tool flag is not used: it
# misses classes such as create:cardboard_sword (census is_tool=no, is_weapon=guess).
# Evidence: CardboardSwordItem.class extends net/minecraft/world/item/SwordItem (invokespecial SwordItem.<init>
# with Tier AllToolMaterials.CARDBOARD); registered as AllItems.CARDBOARD_SWORD (ldc "cardboard_sword").
# AllToolMaterials.CARDBOARD has uses=0 (the durability constant), so the tool may not be damageable at all.
# The scan (javap of every class referencing PickaxeItem/AxeItem/ShovelItem/HoeItem/SwordItem/DiggerItem/
# TieredItem/component/Tool/DataComponents.TOOL) found no other tool classes: AxeItem/ShovelItem only in
# GlitchCore BlockHelper and Create LogStrippingFakeRecipes/LitBlazeBurnerBlock (not items), HoeItem only in
# BOP ModVanillaCompat (not an item), no digger or tool component anywhere else.
MODDED_TOOLS = {
    "create:cardboard_sword": {"kind": "sword", "evidence": "CardboardSwordItem extends SwordItem, tier CARDBOARD (uses 0)"},
}
# Vanilla block broken by each tool kind (vanilla tool-tag fit): stone for a pickaxe, log for an axe, dirt for
# a shovel, hay bale for a hoe, grass for a sword (a sword is not a digger: short grass has hardness 0).
TOOL_BREAK = {
    "pickaxe": "minecraft:stone",
    "axe": "minecraft:oak_log",
    "shovel": "minecraft:dirt",
    "hoe": "minecraft:hay_block",
    "sword": "minecraft:short_grass",
}


def safe(text):
    return SAFE_RE.sub("_", text.lower().replace("/", "_"))


def ns_name(full_id):
    ns, _, name = full_id.partition(":")
    return ns, name


# ------------------------------------------------------------------ census input

class Census(object):
    """Census rows keyed by full id ('ns:name'). Built from CSV directories, or from rows (tests)."""

    def __init__(self, blocks, items, menus, recipes):
        self.blocks = {r["full_id"]: r for r in blocks}
        self.items = {r["full_id"]: r for r in items}
        self.menus = list(menus)
        self.recipes = list(recipes)

    @classmethod
    def from_dir(cls, root):
        def rd(name):
            with io.open(os.path.join(root, name), encoding="utf-8", newline="") as f:
                return list(csv.DictReader(f))
        return cls(rd("blocks.csv"), rd("items.csv"), rd("menus.csv"), rd("recipes.csv"))

    def registered_item(self, full_id):
        """A vanilla id, or a census item that is in the server registry."""
        if full_id.startswith("minecraft:"):
            return True
        row = self.items.get(full_id)
        return row is not None and row["in_server_registry"] == "yes"


class JarRecipes(object):
    """Recipe JSON by recipe id, read from the mod jars (stdlib zipfile, nothing extracted)."""

    def __init__(self, mods_dir):
        self.index = {}
        for jar in sorted(glob.glob(os.path.join(mods_dir, "*.jar"))):
            try:
                names = zipfile.ZipFile(jar).namelist()
            except (OSError, zipfile.BadZipFile):
                continue
            for n in names:
                m = re.match(r"^data/([^/]+)/recipe/(.+)\.json$", n)
                if m:
                    self.index.setdefault("%s:%s" % (m.group(1), m.group(2)), (jar, n))

    def __call__(self, recipe_id):
        if recipe_id not in self.index:
            return None
        jar, member = self.index[recipe_id]
        with zipfile.ZipFile(jar) as z:
            return json.loads(z.read(member).decode("utf-8"))


# ------------------------------------------------------------------ family rules

def technical_reason(census, full_id):
    """Why a block item must not get a place-break scenario, or '' when it is placeable and testable."""
    item = census.items.get(full_id)
    if item is None:
        return "no item row in census"
    if item["in_server_registry"] != "yes":
        return "not in server registry (unobtainable)"
    if item["block_item"] != "yes":
        return "no block item (not placeable)"
    block = census.blocks.get(full_id)
    if block is None:
        return "no block row in census"
    if block["in_server_registry"] != "yes" or block["has_blockstate"] != "yes":
        return "block not registered or no blockstate (technical)"
    if block["category"] == "fluid":
        return "fluid block (not placeable by hand)"
    if TECH_RE.search(block["id"]):
        return "technical name (%s)" % block["id"]
    return ""


def break_tool(block):
    """(tool item or None, note). Tool from the census tool tag, tier from requires_tool."""
    tag = block["tool_tag"]
    if tag not in TOOL_TAGS:
        return None, "bare hand (tool_tag=%s)" % (tag or "-")
    tier = {"yes:iron": "iron", "yes:stone": "stone"}.get(block["requires_tool"], "wooden")
    return "minecraft:%s_%s" % (tier, tag), "tool %s_%s (requires_tool=%s)" % (tier, tag, block["requires_tool"])


def gen_place_break(census, full_id):
    block = census.blocks[full_id]
    tool, tool_note = break_tool(block)
    sid = "pb-" + safe("%s-%s" % ns_name(full_id))
    setup = ["fill 308 120 311 308 120 311 minecraft:stone",
             "setblock 308 121 311 minecraft:air",
             "give {player} %s 1" % full_id]
    steps = [{"do": "tp", "pos": list(STAND), "yaw": 0.0, "pitch": 0.0},
             {"do": "select", "item": full_id},
             {"do": "place", "pos": list(PB_POS)},
             {"observe": "block", "pos": list(PB_POS)}]
    if tool:
        setup.append("give {player} %s 1" % tool)
        steps.append({"do": "select", "item": tool})
    steps += [{"do": "break", "pos": list(PB_POS), "timeout": 20},
              {"do": "collect", "pos": list(PB_POS)},
              {"observe": "block", "pos": list(PB_POS)},
              {"observe": "inventory"}]
    notes = "census %s: loot=%s, category=%s, %s. Break from 3 blocks, then collect the drops." % (
        full_id, block["loot_drops"] or "-", block["category"], tool_note)
    if block["has_loot_table"] == "no":
        notes += " Census: no loot table (expect no drop)."
    return sid, {"id": sid, "notes": notes, "setup": setup, "steps": steps}


def menu_openers(census, menu):
    """Block opener ids for a menus.csv row. Expands '(and the other *_<color>)' notes to all matching blocks."""
    opener = menu["opener_id"]
    if "(and the other" not in menu["note"] and "(and the other" not in opener:
        return [opener.split(" ")[0]]
    base = opener.split(" ")[0]
    ns, name = ns_name(base)
    suffix = "_" + name.rsplit("_", 1)[-1]
    return sorted(k for k in census.blocks if k.startswith(ns + ":") and k.endswith(suffix))


def gen_interact(census, menu, opener):
    ns, name = ns_name(opener)
    sid = "ia-%s--%s" % (safe(menu["menu_id"]), safe("%s-%s" % (ns, name)))
    setup = ["fill 308 120 310 308 120 310 minecraft:stone",
             "setblock 308 121 310 minecraft:air",
             "give {player} %s 1" % opener]
    steps = [{"do": "tp", "pos": list(STAND), "yaw": 0.0, "pitch": 0.0},
             {"do": "select", "item": opener},
             {"do": "place", "pos": list(MENU_POS)},
             {"observe": "block", "pos": list(MENU_POS)},
             {"do": "interact", "pos": list(MENU_POS)},
             {"observe": "screen"},
             {"do": "close"}]
    notes = "menus.csv menu '%s' (confidence %s, %s). Opener block %s placed and right-clicked." % (
        menu["menu_id"], menu["confidence"] or "-", menu["source"] or "-", opener)
    return sid, {"id": sid, "notes": notes, "setup": setup, "steps": steps}


def is_food_candidate(item):
    """(ok, reason). A food is a MODDED_FOOD entry (jar bytecode) or a census is_food=yes row. A census guess
    alone is not enough: the guess picked non-food items."""
    fid = item["full_id"]
    if "/" in fid:
        return False, "path-like id (not a registry item)"
    if item["in_server_registry"] != "yes":
        return False, "not in server registry (unobtainable)"
    if fid in MODDED_FOOD:
        return True, ""
    if item["is_food"] == "yes":
        return True, ""
    return False, "census is_food=%s not confirmed by jar bytecode (no Item$Properties.food call)" % item["is_food"]


def gen_food(item):
    fid = item["full_id"]
    sid = "fu-" + safe("%s-%s" % ns_name(fid))
    flag = "food evidence: " + MODDED_FOOD[fid] if fid in MODDED_FOOD else "census is_food=yes"
    sc = {
        "id": sid,
        "notes": "%s. Health restored to 20 first (instant_health), food_to 18 (needs food >= 18 at start: "
                 "food only rises by eating, so earlier scenarios leave it at >= 18), then one bite/drink with a 2 s hold, "
                 "observe food and saturation." % flag,
        "setup": ["effect give {player} minecraft:instant_health 1 10", "give {player} %s 1" % fid],
        "steps": [{"do": "food_to", "value": 18, "max_ticks": 1200},
                  {"observe": "player"},
                  {"do": "select", "item": fid},
                  {"do": "use_item", "hold": 2.0},
                  {"do": "wait", "ticks": 40},
                  {"observe": "player"},
                  {"observe": "inventory"}],
    }
    return sid, sc


def gen_tool(census, full_id, spec):
    """-> (sid, scenario, reason). Tool kind -> vanilla block to break, the modded tool is the only breaker."""
    item = census.items.get(full_id)
    if item is None:
        return None, None, "not in census"
    if item["in_server_registry"] != "yes":
        return None, None, "not in server registry (unobtainable)"
    block = TOOL_BREAK.get(spec["kind"])
    if block is None:
        return None, None, "no vanilla block for tool kind %s" % spec["kind"]
    sid = "tl-" + safe("%s-%s" % ns_name(full_id))
    setup = ["fill 308 120 311 308 120 311 minecraft:stone",
             "setblock 308 121 311 %s" % block,
             "give {player} %s 1" % full_id]
    steps = [{"do": "tp", "pos": list(STAND), "yaw": 0.0, "pitch": 0.0},
             {"do": "select", "item": full_id},
             {"observe": "block", "pos": list(PB_POS)},
             {"observe": "inventory"},
             {"do": "break", "pos": list(PB_POS), "timeout": 20},
             {"do": "collect", "pos": list(PB_POS)},
             {"observe": "block", "pos": list(PB_POS)},
             {"observe": "inventory"}]
    notes = ("tool %s (%s, %s): breaks %s. Inventory is observed before and after the break, so the durability "
             "delta (per stack damage / max_damage in the record's durability field) is the tool's wear. "
             "Expected from vanilla rules, not verified: a sword does 2 damage only on blocks with destroy speed "
             "> 0, and short grass has destroy speed 0, so no wear is expected. The census tool flag is no. "
             "If the tool is not damageable (CARDBOARD uses 0), no damage field is expected at all." % (
                 full_id, spec["kind"], spec["evidence"], block))
    return sid, {"id": sid, "notes": notes, "setup": setup, "steps": steps}, ""


def parse_shaped(census, rec):
    """Shaped recipe JSON (1.21.1 layout) -> (ingredients, result_item, result_count, reason).
    ingredients: list of (item, [grid cell indices 0..8]) in pattern order. reason non-empty on failure."""
    if rec.get("type") != "minecraft:crafting_shaped":
        return None, None, 0, "not crafting_shaped (%s)" % rec.get("type")
    pattern = rec.get("pattern") or []
    if not pattern or len(pattern) > 3 or any(len(row) > 3 for row in pattern):
        return None, None, 0, "pattern empty or larger than 3x3"
    key = rec.get("key") or {}
    resolved = {}
    for ch, spec in key.items():
        alts = spec if isinstance(spec, list) else [spec]
        choice = None
        for alt in alts:
            if isinstance(alt, dict) and isinstance(alt.get("item"), str):
                cand = alt["item"]
                if census.registered_item(cand):
                    choice = cand
                    break
        if choice is None:
            return None, None, 0, "key %r has no registry item (tag or unknown)" % ch
        resolved[ch] = choice
    order, cells = [], {}
    for r, row in enumerate(pattern):
        for j, ch in enumerate(row):
            if ch == " ":
                continue
            if ch not in resolved:
                return None, None, 0, "pattern char %r missing from key" % ch
            item = resolved[ch]
            if item not in cells:
                order.append(item)
                cells[item] = []
            cells[item].append(r * 3 + j)
    result = rec.get("result") or {}
    result_item = result.get("item") or result.get("id")
    if not isinstance(result_item, str) or not census.registered_item(result_item):
        return None, None, 0, "result not a registry item"
    if any(i == "minecraft:crafting_table" for i in order):
        return None, None, 0, "uses crafting_table as an ingredient"
    if len(order) > 8:
        return None, None, 0, "more than 8 distinct ingredients"
    return [(i, cells[i]) for i in order], result_item, int(result.get("count", 1)), ""


def gen_craft(census, recipe, rec_json):
    rid = recipe["recipe_id"]
    ingr, result_item, count, reason = parse_shaped(census, rec_json) if rec_json else (None, None, 0, "recipe json not in mods")
    if reason:
        return None, reason
    sid = "cr-" + safe("%s-%s" % ns_name(rid))
    lines = ", ".join("%s x%d (cells %s)" % (i, len(c), c) for i, c in ingr)
    setup = ["fill 308 120 310 308 120 310 minecraft:stone",
             "setblock 308 121 310 minecraft:air"]
    for item, cells in ingr:
        setup.append("give {player} %s %d" % (item, len(cells)))
    setup.append("give {player} minecraft:crafting_table 1")
    steps = [{"observe": "inventory"},
             {"do": "tp", "pos": list(STAND), "yaw": 0.0, "pitch": 0.0},
             {"do": "place", "pos": list(MENU_POS), "item": "minecraft:crafting_table"},
             {"do": "interact", "pos": list(MENU_POS)},
             {"observe": "screen"}]
    for k, (item, cells) in enumerate(ingr):
        steps.append({"do": "click_slot", "in": "crafting", "slot": "hotbar:%d" % k, "mode": "left"})
        for c in cells:
            steps.append({"do": "click_slot", "in": "crafting", "slot": "grid:%d" % c, "mode": "right"})
    steps += [{"observe": "screen"}, {"do": "close"}]
    notes = "recipe %s (shaped, result %s x%d). Ingredients from the mod jar recipe key: %s. Give order = hotbar order " \
            "(hotbar:%s). Observe screen shows the result slot." % (
                rid, result_item, count, lines, ",".join(str(k) for k in range(len(ingr))))
    return sid, {"id": sid, "notes": notes, "setup": setup, "steps": steps}


def sample_even(items, n):
    if n <= 0 or not items:
        return []
    if len(items) <= n:
        return list(items)
    if n == 1:
        return [items[0]]
    return [items[round(i * (len(items) - 1) / float(n - 1))] for i in range(n)]


# ------------------------------------------------------------------ generation

def generate(census, families, craft_per_mod, recipe_json):
    """-> (scenarios {family: [(id, sc)]}, skipped [(family, source, reason)])"""
    out = {f: [] for f in FAMILIES}
    skipped = []
    seen = {}

    def add(fam, sid, sc):
        if sid in seen:
            raise ValueError("duplicate scenario id %s (%s and %s)" % (sid, seen[sid], fam))
        seen[sid] = fam
        out[fam].append((sid, sc))

    if "place-break" in families:
        for full_id in sorted(census.items):
            if census.items[full_id]["block_item"] != "yes":
                continue
            why = technical_reason(census, full_id)
            if why:
                skipped.append(("place-break", full_id, why))
                continue
            sid, sc = gen_place_break(census, full_id)
            add("place-break", sid, sc)

    if "interact" in families:
        for menu in census.menus:
            if menu["opener_kind"] != "block":
                skipped.append(("interact", menu["menu_id"], "item opener (interact family is block openers only)"))
                continue
            openers = menu_openers(census, menu)
            if not openers:
                skipped.append(("interact", menu["menu_id"], "no opener block in census"))
                continue
            for op in openers:
                row = census.blocks.get(op)
                item = census.items.get(op)
                if row is None or item is None or item["block_item"] != "yes" or item["in_server_registry"] != "yes":
                    skipped.append(("interact", "%s/%s" % (menu["menu_id"], op),
                                    "opener %s not a placeable registry block in census" % op))
                    continue
                sid, sc = gen_interact(census, menu, op)
                add("interact", sid, sc)

    if "item-use" in families:
        for full_id in sorted(set(census.items) | set(MODDED_FOOD)):
            item = census.items.get(full_id)
            if item is None:
                if full_id in MODDED_FOOD:
                    skipped.append(("item-use", full_id, "jar-verified food not in census"))
                continue
            if item["is_food"] not in ("yes", "guess") and full_id not in MODDED_FOOD:
                continue
            ok, why = is_food_candidate(item)
            if not ok:
                skipped.append(("item-use", full_id, why))
                continue
            sid, sc = gen_food(item)
            add("item-use", sid, sc)

    if "tool" in families:
        for full_id in sorted(set(census.items) | set(MODDED_TOOLS)):
            spec = MODDED_TOOLS.get(full_id)
            if spec is None:
                item = census.items[full_id]
                if item["is_tool"] in ("yes", "guess"):
                    skipped.append(("tool", full_id,
                                    "census is_tool=%s but no digger/sword class or minecraft:tool component in "
                                    "the jars" % item["is_tool"]))
                continue
            sid, sc, why = gen_tool(census, full_id, spec)
            if why:
                skipped.append(("tool", full_id, why))
                continue
            add("tool", sid, sc)

    if "craft" in families:
        by_ns = {}
        for rec in census.recipes:
            if rec["type"] != "minecraft:crafting_shaped":
                continue
            by_ns.setdefault(rec["ns"], []).append(rec)
        for ns in sorted(by_ns):
            feasible, rows = [], []
            for rec in sorted(by_ns[ns], key=lambda r: r["recipe_id"]):
                js = recipe_json(rec["recipe_id"])
                ingr, _, _, why = parse_shaped(census, js) if js else (None, None, 0, "recipe json not in mods")
                if why:
                    rows.append(("craft", rec["recipe_id"], why))
                    continue
                feasible.append(rec)
            chosen = sample_even(feasible, craft_per_mod)
            picked = set(r["recipe_id"] for r in chosen)
            for rec in feasible:
                if rec["recipe_id"] not in picked:
                    rows.append(("craft", rec["recipe_id"], "not in the sample (%d per mod)" % craft_per_mod))
            for rec in chosen:
                sid, sc = gen_craft(census, rec, recipe_json(rec["recipe_id"]))
                add("craft", sid, sc)
            skipped.extend(rows)

    return out, skipped


def write_family(out_dir, family, items):
    fdir = os.path.join(out_dir, family)
    os.makedirs(fdir, exist_ok=True)
    for old in glob.glob(os.path.join(fdir, "*.json")):
        os.remove(old)
    for sid, sc in items:
        path = os.path.join(fdir, sid + ".json")
        with io.open(path, "w", encoding="utf-8") as f:
            f.write(json.dumps(sc, indent=1, ensure_ascii=False, sort_keys=False) + "\n")


def write_skipped(out_dir, skipped):
    path = os.path.join(out_dir, "_skipped.csv")
    with io.open(path, "w", encoding="utf-8", newline="") as f:
        w = csv.writer(f)
        w.writerow(["family", "source", "reason"])
        for row in skipped:
            w.writerow(row)


def main(argv=None):
    ap = argparse.ArgumentParser()
    ap.add_argument("--census", default=os.environ.get("PARITY_CENSUS", os.path.expanduser("~/hud-work/horizon-play/census")))
    ap.add_argument("--mods", default=os.environ.get("PARITY_MODS", os.path.expanduser("~/hud-work/horizon-play/mods")))
    ap.add_argument("--out", default=os.path.join(HERE, "scenarios", "census"))
    ap.add_argument("--craft-per-mod", type=int, default=20)
    ap.add_argument("--families", default=",".join(FAMILIES))
    args = ap.parse_args(argv)
    families = [f for f in args.families.split(",") if f]
    for f in families:
        if f not in FAMILIES:
            ap.error("unknown family %s" % f)
    census = Census.from_dir(args.census)
    recipe_json = JarRecipes(args.mods)
    scenarios, skipped = generate(census, families, args.craft_per_mod, recipe_json)
    os.makedirs(args.out, exist_ok=True)
    for fam in families:
        write_family(args.out, fam, scenarios[fam])
    write_skipped(args.out, skipped)
    print("out: %s" % args.out)
    for fam in families:
        print("%-12s scenarios %5d" % (fam, len(scenarios[fam])))
    reasons = {}
    for fam, _, why in skipped:
        key = (fam, re.sub(r"\(.*", "", why).strip()[:70])
        reasons[key] = reasons.get(key, 0) + 1
    print("skipped rows: %d (details in %s)" % (len(skipped), os.path.join(args.out, "_skipped.csv")))
    for (fam, why), n in sorted(reasons.items(), key=lambda kv: (-kv[1], kv[0])):
        print("  %-12s %5d  %s" % (fam, n, why))
    return 0


if __name__ == "__main__":
    sys.exit(main())
