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
import json
import os
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import heal  # noqa: E402
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
            return stack.observe_inventory()
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
    return {"food": p["food"], "saturation": p["saturation"], "ticks": ticks}


def main(argv=None):
    ap = argparse.ArgumentParser()
    ap.add_argument("--stack", required=True, choices=["truth", "ours"])
    ap.add_argument("scenario")
    ap.add_argument("-o", "--out", required=True)
    ap.add_argument("--shots", default=None)
    ap.add_argument("--no-reset", action="store_true")
    args = ap.parse_args(argv)

    with open(args.scenario, encoding="utf-8") as f:
        sc = json.load(f)
    stack = stacks.make_stack(args.stack)
    player = stack.player
    shots_dir = None
    if args.shots:
        shots_dir = os.path.join(args.shots, args.stack)
        os.makedirs(shots_dir, exist_ok=True)

    out = {"scenario": sc["id"], "stack": args.stack, "setup": [], "steps": [],
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
        for text in RESET:
            cmd(sub(text, player))
        try:
            stack.tp(*PLATFORM_ORIGIN, yaw=sc.get("yaw", 0.0), pitch=sc.get("pitch", 0.0))
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
