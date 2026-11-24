#!/usr/bin/env python3
"""Run one parity scenario on one stack and write normalized observations.

Usage: python3 -I run.py --stack truth|ours SCENARIO.json -o OUT.json [--shots DIR]

Order: standard reset prelude (forceload, survival, clear inventory and nearby items,
platform rebuilt, player teleported to 308 121 308), then the scenario's own setup
commands (with {player} substituted), then the steps. Every step writes one record:
  {"i", "kind": "do"|"observe", "step", "result", "error", "source", "shot"}
A failing step records "error" and the run continues.
"""
import argparse
import copy
import json
import os
import re
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import heal  # noqa: E402
import lanes  # noqa: E402
import stacks  # noqa: E402

PLATFORM_ORIGIN = (308, 121, 308)
SETTLE_TICKS = 10
RESET = [
    "forceload add 296 296 320 320",
    "gamemode survival {player}",
    "clear {player}",
    "kill @e[type=item,x=308,y=121,z=308,distance=..25]",
    "kill @e[type=item_display,x=308,y=121,z=308,distance=..25]",  # modded drops/carriers left by earlier scenarios
    "fill 300 120 300 316 120 316 minecraft:stone",
    "fill 300 121 300 316 130 316 minecraft:air",
    "tp {player} 308 121 308",
]


def sub(text, player):
    return text.replace("{player}", player)


# ------------------------------------------------------------------ lane offset (the one coordinate transform)
# A lane builds at origin (ox, oy, oz); every ABSOLUTE scenario coordinate is shifted by it. Relative
# coordinates (~, ^) are left alone: they are measured from the executing entity, which is already in the
# lane's area. This is the only place coordinates are rewritten. Every position-carrying key and command
# is listed here; anything else that takes coordinates raises instead of silently building in the wrong place.
POSITION_KEYS = ("pos", "point")  # scenario step keys holding [x, y, z]
COORD_COMMANDS = ("setblock", "fill", "tp", "forceload", "summon")  # setup/command heads with absolute coords
UNSUPPORTED_COMMANDS = ("clone", "execute", "place", "spreadplayers", "setworldspawn",
                        "spawnpoint", "teleport", "particle", "playsound", "locate")
SELECTOR_COORD_RE = re.compile(r"(?<=[\[,])([xyz])=(-?\d+(?:\.\d+)?)(?=[,\]])")
NUM_RE = re.compile(r"^-?\d+(?:\.\d+)?$")


def _is_coord(token):
    return bool(NUM_RE.match(token)) or token.startswith(("~", "^"))


def _shift(token, delta):
    """An absolute number token shifted by delta, keeping its int or float spelling. Relative (~ ^) and
    non-numeric tokens are returned unchanged."""
    if not NUM_RE.match(token):
        return token
    if "." not in token:
        return str(int(token) + int(delta))
    return repr(float(token) + delta)


def _coord_indexes(head, tokens):
    """-> list of (token index, axis 'x'|'y'|'z') for the absolute coordinates of a command."""
    if head == "setblock":
        return [(1, "x"), (2, "y"), (3, "z")]
    if head == "fill":
        return [(1, "x"), (2, "y"), (3, "z"), (4, "x"), (5, "y"), (6, "z")]
    if head == "tp":
        # tp x y z [yaw pitch] | tp target x y z [yaw pitch]; tp target destination (no coordinates) is
        # left alone. The yaw and pitch after the coordinates are never shifted.
        if len(tokens) >= 4 and all(_is_coord(t) for t in tokens[1:4]):
            start = 1  # tp x y z ...
        elif len(tokens) >= 5 and all(_is_coord(t) for t in tokens[2:5]):
            start = 2  # tp target x y z ...
        else:
            return []
        return [(start, "x"), (start + 1, "y"), (start + 2, "z")]
    if head == "summon":
        # summon <entity> x y z [nbt]: the coordinates follow the entity id
        return [(2, "x"), (3, "y"), (4, "z")] if len(tokens) >= 5 else []
    if head == "forceload":
        # forceload add|remove x1 z1 [x2 z2] | query [x z]: x and z only (no y)
        if len(tokens) >= 3 and tokens[1] in ("add", "remove"):
            return [(2, "x"), (3, "z"), (4, "x"), (5, "z")]
        if len(tokens) >= 3 and tokens[1] == "query":
            return [(2, "x"), (3, "z")]
        return []
    return []


def offset_command(text, origin):
    """A setup/command string with its absolute coordinates shifted by origin (ox, oy, oz).
    Also shifts x=/y=/z= inside @-selectors ('kill @e[type=item,x=308,...]'). Non-coordinate numbers
    (effect durations, counts, distances) are never touched."""
    ox, oy, oz = origin
    if not (ox or oy or oz):
        return text
    delta = {"x": ox, "y": oy, "z": oz}
    tokens = text.split()
    if not tokens:
        return text
    head = tokens[0].lstrip("/")
    if head in UNSUPPORTED_COMMANDS:
        raise ValueError("lane offset does not support /%s (add it to COORD_COMMANDS with a rule)" % head)
    for idx, axis in _coord_indexes(head, tokens):
        if idx < len(tokens):
            tokens[idx] = _shift(tokens[idx], delta[axis])
    # selectors: x=, y=, z= inside [...] ('kill @e[type=item,x=308,...]'); distance= and dx= are not shifted
    joined = SELECTOR_COORD_RE.sub(
        lambda m: "%s=%s" % (m.group(1), _shift(m.group(2), delta[m.group(1)])), " ".join(tokens))
    return joined


def offset_point(value, origin, key):
    if not isinstance(value, list) or len(value) != 3 or not all(
            isinstance(v, (int, float)) and not isinstance(v, bool) for v in value):
        raise ValueError("%s must be [x, y, z] numbers, got %r" % (key, value))
    return [value[i] + origin[i] for i in range(3)]


def offset_scenario(sc, origin):
    """A deep copy of scenario sc with every position in its setup, command steps and POSITION_KEYS
    shifted by the lane origin. A zero origin returns an equal copy (the legacy single-lane run)."""
    out = copy.deepcopy(sc)
    if not any(origin):
        return out
    out["setup"] = [offset_command(t, origin) for t in sc.get("setup", [])]
    for st in out.get("steps", []):
        for key in POSITION_KEYS:
            if key in st:
                st[key] = offset_point(st[key], origin, key)
        if st.get("do") == "command":
            st["text"] = offset_command(st["text"], origin)
    return out


def offset_reset(commands, origin):
    return [offset_command(t, origin) for t in commands]


CLEAR_TRIES = 3


def clear_inventory(stack, out):
    """Close any open screen (cursor stack goes back into the inventory), clear the inventory, then
    observe: the screen must be 'playing' and the inventory and cursor empty. Retries up to CLEAR_TRIES."""
    last = None
    for attempt in range(1, CLEAR_TRIES + 1):
        try:
            stack.close()
        except Exception:
            pass  # no screen open: close is refused, which is fine
        try:
            stack.setup_command(sub("clear {player}", stack.player))
        except Exception as e:
            last = "clear: %s" % e
        try:
            stack.wait(4)
        except Exception as e:
            last = "wait: %s" % e
            continue
        try:
            scr = stack.observe_screen()
            inv = stack.observe_inventory()
            if scr.get("kind") == "playing" and not inv["counts"] and not inv["carried"]:
                out["setup"].append({"cmd": "verify-empty", "ok": True, "attempt": attempt})
                return True
            last = "screen=%s counts=%s carried=%s" % (scr.get("kind"), inv["counts"], inv["carried"])
        except Exception as e:
            last = "observe: %s" % e
    out["setup"].append({"cmd": "verify-empty", "ok": False, "attempts": CLEAR_TRIES, "error": last})
    return False


def run_step(stack, step, shots_dir, idx, rec):
    """Dispatch one step. Returns the normalized result dict (or raises)."""
    if "observe" in step:
        kind = step["observe"]
        rec["kind"] = "observe"
        if kind == "block":
            rec["source"] = stack.source_of("block")
            return stack.observe_block(step["pos"])
        if kind == "inventory":
            rec["source"] = stack.source_of("inventory")
            res = stack.observe_inventory()
            # durability is recorded beside the result, not in it: diff.py compares results only
            rec["durability"] = stack.last_durability()
            return res
        if kind == "screen":
            stack.wait(5)  # menus update over the next ticks after a click; read after 5
            return stack.observe_screen()
        if kind == "player":
            return stack.observe_player()
        if kind == "motion":
            return stack.motion()
        raise ValueError("unknown observe: %s" % kind)

    do = step["do"]
    rec["kind"] = "do"
    if do == "select":
        return stack.select(step.get("item", step.get("slot")))
    if do == "break":
        return stack.break_(step["pos"], step.get("timeout", 20))
    if do == "place":
        return stack.place(step["pos"], step.get("item"))
    if do == "interact":
        return stack.interact(step["pos"], step.get("face"))
    if do == "use_item":
        return stack.use_item(step.get("hold"))
    if do == "use_at":
        point = step["point"]
        if not isinstance(point, list) or len(point) != 3:
            raise ValueError("use_at point must be [x, y, z]")
        return stack.use_at(point)
    if do == "key":
        return stack.key(step["key"], step.get("ticks", 1))
    if do == "click_slot":
        kind = step.get("in", "inventory")
        idx = stacks.slot_index(stack.name, kind, step["slot"])
        return stack.click_slot(idx, step.get("mode", "left"), kind)
    if do == "collect":
        # walk onto the block position so pickup happens (the player stood 2 blocks away before)
        x, y, z = step["pos"]
        stack.tp(x + 0.5, y, z + 0.5, yaw=step.get("yaw", 0.0), pitch=step.get("pitch", 0.0))
        stack.wait(20)
        return {"pos": step["pos"]}
    if do == "open_inventory":
        return stack.open_inventory()
    if do == "close":
        return stack.close()
    if do == "tp":
        stack.tp(*step["pos"], yaw=step.get("yaw", 0.0), pitch=step.get("pitch", 0.0))
        return {"pos": step["pos"]}
    if do == "wait":
        ticks = step["ticks"] if "ticks" in step else int(step["seconds"] * 20)
        stack.wait(ticks)
        return {"waited_ticks": ticks}
    if do == "command":
        stack.setup_command(sub(step["text"], stack.player))
        return {"sent": True}
    if do == "food_to":
        return food_to(stack, int(step["value"]), int(step.get("max_ticks", 600)))
    raise ValueError("unknown do: %s" % do)


def food_to(stack, value, max_ticks):
    """Deterministic food prelude (vanilla commands only). Hunger amplifier 3 drains one food point per 200
    ticks (saturation first), so a 20-tick poll cannot overshoot and the clear (command latency of a few
    ticks) cannot add a drop: food is exactly `value` and saturation 0 when the effect is cleared.
    max_ticks bounds the drain; the effect is cleared afterwards."""
    stack.setup_command(sub("effect give {player} minecraft:hunger 300 3", stack.player))
    ticks = 0
    try:
        p = stack.observe_player()
        if p["food"] < value:
            raise RuntimeError("food %s already below %s" % (p["food"], value))
        while p["food"] > value and ticks < max_ticks:
            stack.wait(20)
            ticks += 20
            p = stack.observe_player()
    finally:
        stack.setup_command(sub("effect clear {player} minecraft:hunger", stack.player))
    if p["food"] != value:
        raise RuntimeError("food %s after %d ticks, wanted %s" % (p["food"], ticks, value))
    # ticks is the poll count, not game state: it differs by one 20-tick poll between runs (09: 2160 vs
    # 2100) with identical food and saturation, so it is kept out of the compared result.
    return {"food": p["food"], "saturation": p["saturation"]}


def main(argv=None):
    ap = argparse.ArgumentParser()
    ap.add_argument("--stack", required=True, choices=["truth", "ours"])
    ap.add_argument("scenario")
    ap.add_argument("-o", "--out", required=True)
    ap.add_argument("--shots", default=None)
    ap.add_argument("--no-reset", action="store_true")
    ap.add_argument("--lane", type=int, default=None,
                    help="lane id from the lane file: its player names, hz slot, truth port and area origin")
    ap.add_argument("--lanes-file", default=lanes.DEFAULT_LANES_FILE)
    args = ap.parse_args(argv)

    lane = lanes.lane_by_id(lanes.load_lanes(args.lanes_file), args.lane) if args.lane is not None else None
    origin = tuple(lane["origin"]) if lane else (0, 0, 0)  # no lane = the single-client stand, at the origin

    with open(args.scenario, encoding="utf-8") as f:
        sc = offset_scenario(json.load(f), origin)
    stack = stacks.make_stack(args.stack, lane)
    player = stack.player
    shots_dir = None
    if args.shots:
        shots_dir = os.path.join(args.shots, args.stack)
        os.makedirs(shots_dir, exist_ok=True)

    out = {"scenario": sc["id"], "stack": args.stack, "setup": [], "steps": [],
           "lane": lane["lane"] if lane else None, "origin": list(origin), "player": player,
           "started": time.strftime("%Y-%m-%dT%H:%M:%S")}
    t0 = time.time()

    # pre-scenario heal (heal.py): death screen -> respawn, other GUI -> close, pack prompt -> Proceed,
    # then wait-ready. Runs before every scenario, with or without the reset prelude.
    healed = heal.heal(stack)
    out["setup"].append({"cmd": "heal", "ok": healed["ok"], "actions": healed["actions"],
                         "error": healed["error"]})

    def cmd(text):
        rec = {"cmd": text}
        try:
            stack.setup_command(text)
            rec["ok"] = True
        except Exception as e:  # keep going, record the failure
            rec["ok"] = False
            rec["error"] = str(e)
        out["setup"].append(rec)

    if not args.no_reset and sc.get("reset", True):
        try:
            stack.wait_ready(60)
        except Exception as e:
            out["setup"].append({"cmd": "wait-ready", "ok": False, "error": str(e)})
        for text in offset_reset(RESET, origin):
            cmd(sub(text, player))
        try:
            stack.tp(*offset_point(list(PLATFORM_ORIGIN), origin, "PLATFORM_ORIGIN"),
                     yaw=sc.get("yaw", 0.0), pitch=sc.get("pitch", 0.0))
            stack.wait(20)
            stack.wait_ready(60)  # client must hold the platform chunks before any observation
        except Exception as e:
            out["setup"].append({"cmd": "tp", "ok": False, "error": str(e)})
        clear_inventory(stack, out)
    for text in sc.get("setup", []):
        cmd(sub(text, player))
    try:
        stack.wait(20)
    except Exception as e:
        out["setup"].append({"cmd": "wait", "ok": False, "error": str(e)})

    for i, step in enumerate(sc["steps"]):
        rec = {"i": i, "step": step, "kind": None, "result": None, "error": None,
               "source": None, "shot": None}
        is_obs = "observe" in step
        try:
            res = run_step(stack, step, shots_dir, i, rec)
            if is_obs and not isinstance(res, dict):
                raise TypeError("observation returned %s, not an object" % type(res).__name__)
            rec["result"] = res
            if step.get("block_only") and isinstance(rec["result"], dict):
                # random drops (seed chance) are not compared; only the block after the action
                rec["result"] = {"block_after": rec["result"].get("block_after")}
        except Exception as e:
            rec["error"] = "%s: %s" % (type(e).__name__, e)
            if is_obs:
                # a failed observation is an infra error, never a parity difference (diff.py ERROR)
                rec["infra"] = True
        if "do" in step:
            # fixed settle so drops, pickups and block updates land before the next observation
            try:
                stack.wait(SETTLE_TICKS)
            except Exception as e:
                rec["settle_error"] = str(e)
        if shots_dir:
            path = os.path.join(shots_dir, "%s-step%02d.png" % (sc["id"], i))
            try:
                stack.screenshot(path)
                rec["shot"] = path
            except Exception as e:
                rec["shot_error"] = str(e)
        out["steps"].append(rec)

    out["wall_s"] = round(time.time() - t0, 1)
    if hasattr(stack, "inspect_hits"):
        out["inspect"] = {"hits": stack.inspect_hits, "misses": stack.inspect_misses}
    with open(args.out, "w", encoding="utf-8") as f:
        json.dump(out, f, indent=2, sort_keys=True)
        f.write("\n")
    errs = sum(1 for s in out["steps"] if s["error"])
    print("%s %s: %d steps, %d errors -> %s" % (args.stack, sc["id"], len(out["steps"]), errs, args.out))
    return 0


if __name__ == "__main__":
    sys.exit(main())
