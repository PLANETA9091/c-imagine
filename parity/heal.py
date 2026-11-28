#!/usr/bin/env python3
"""Pre-scenario heal for the parity runner.

Before every scenario each client is brought back to a known state: in the world, no GUI open,
the resource-pack prompt passed, peaceful difficulty (no hostile mob kills a lane mid-scenario) and
a client that accepts commands. A scenario that ran before can leave a death screen, an open menu,
the pack prompt or a refusing chat behind, and every later step then fails in a misleading way.

A stack adapter (stacks.py) provides:
  heal_probe() -> {"kind": str, "buttons": [label, ...], "enabled": [label, ...]}
  heal_respawn()         respawn from the death screen
  heal_close()           close the open screen (never called on the title screen)
  heal_click(label)      click a button by its label
  heal_sleep(seconds)    real-time pause after a respawn or a click
  wait_ready(seconds)    block until in the world with the chunks around the player loaded
Optional:
  heal_setup()           the per-scenario setup ('difficulty peaceful'); raises when the client refuses it
  heal_chat_recover()    after a refused setup: {"ok", "actions", "relaunch"}; relaunch=True means the lane
                         client was restarted and the heal runs again from the top (once)

heal() never raises. It returns {"ok", "actions", "error", "screen"} and the runner records that in the
setup list, so a failed heal shows up as an infra error in the scenario, not as a parity difference.
"""

MAX_ROUNDS = 4
RESPAWN_SETTLE_S = 2.0
PACK_SETTLE_S = 8.0
READY_S = 60


class HealError(RuntimeError):
    pass


def classify(probe):
    """Probe reply -> one of: death, pack, title, playing, screen (any other open GUI)."""
    kind = str(probe.get("kind") or "playing").strip().lower()
    buttons = [str(b) for b in (probe.get("buttons") or [])]
    enabled = [str(b) for b in (probe.get("enabled") if probe.get("enabled") is not None else buttons)]
    if "death" in kind or any(b.strip().lower() == "respawn" for b in buttons):
        return "death"
    if any(b.strip().lower() == "proceed" for b in enabled):
        return "pack"
    if kind == "title":
        return "title"
    if kind in ("playing", "none", "null", ""):
        return "playing"
    return "screen"


def _heal_screens(stack, result):
    """Probe and clear screens until the client is playing. Raises HealError when it cannot."""
    for _ in range(MAX_ROUNDS):
        probe = stack.heal_probe()
        result["screen"] = probe.get("kind")
        state = classify(probe)
        if state == "playing":
            return
        if state == "title":
            raise HealError("title screen open, not in a world (close not attempted)")
        if state == "death":
            stack.heal_respawn()
            result["actions"].append("respawn")
            stack.heal_sleep(RESPAWN_SETTLE_S)
        elif state == "pack":
            stack.heal_click("Proceed")
            result["actions"].append("click Proceed")
            stack.heal_sleep(PACK_SETTLE_S)
        else:
            kind = probe.get("kind")
            stack.heal_close()
            result["actions"].append("close %s" % kind)
    final = stack.heal_probe()
    result["screen"] = final.get("kind")
    if classify(final) != "playing":
        raise HealError("still not playing after %d heal rounds" % MAX_ROUNDS)


def _setup(stack, result, relaunch_ok):
    """difficulty peaceful. A refused setup goes to heal_chat_recover; a relaunch returns the nested heal's
    result (it already ran wait-ready), otherwise None."""
    if not hasattr(stack, "heal_setup"):
        return None
    try:
        stack.heal_setup()
    except Exception as first:
        recover = getattr(stack, "heal_chat_recover", None)
        if recover is None:
            raise
        rec = recover()
        result["actions"].append("setup refused (%s)" % first)
        result["actions"].extend(rec.get("actions") or [])
        if not rec.get("ok"):
            raise HealError("client refused setup and recovery failed: %s" % first)
        if rec.get("relaunch") and relaunch_ok:
            nested = heal(stack, True, _relaunch_ok=False)
            nested["actions"] = result["actions"] + nested["actions"]
            return nested
    result["actions"].append("difficulty peaceful")
    return None


def heal(stack, ready=True, _relaunch_ok=True):
    """Bring the stack back to 'playing' in the world. Returns {"ok", "actions", "error", "screen"}.

    ready=False is the scenario teardown: close whatever screen is open and verify it, no setup, no wait-ready.
    "screen" is the kind of the last probe that decided the outcome."""
    result = {"ok": True, "actions": [], "error": None, "screen": None}
    try:
        _heal_screens(stack, result)
        if ready:
            nested = _setup(stack, result, _relaunch_ok)
            if nested is not None:
                return nested
            stack.wait_ready(READY_S)
            result["actions"].append("wait-ready")
    except Exception as e:  # infra: recorded, never raised
        result["ok"] = False
        result["error"] = "%s: %s" % (type(e).__name__, e)
    return result
