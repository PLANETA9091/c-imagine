#!/usr/bin/env python3
"""Pre-scenario heal for the parity runner.

Before every scenario each client is brought back to a known state: in the world, no GUI open,
the resource-pack prompt passed. A scenario that ran before can leave a death screen, an open menu
or the pack prompt behind, and every later step then fails in a misleading way.

A stack adapter (stacks.py) provides:
  heal_probe() -> {"kind": str, "buttons": [label, ...], "enabled": [label, ...]}
  heal_respawn()         respawn from the death screen
  heal_close()           close the open screen (never called on the title screen)
  heal_click(label)      click a button by its label
  heal_sleep(seconds)    real-time pause after a respawn or a click
  wait_ready(seconds)    block until in the world with the chunks around the player loaded

heal() never raises. It returns {"ok", "actions", "error"} and the runner records that in the
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


def heal(stack):
    """Bring the stack back to 'playing' in the world. Returns {"ok", "actions", "error"}."""
    result = {"ok": True, "actions": [], "error": None}
    try:
        for _ in range(MAX_ROUNDS):
            probe = stack.heal_probe()
            state = classify(probe)
            if state == "playing":
                break
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
        else:
            if classify(stack.heal_probe()) != "playing":
                raise HealError("still not playing after %d heal rounds" % MAX_ROUNDS)
        stack.wait_ready(READY_S)
        result["actions"].append("wait-ready")
    except Exception as e:  # infra: recorded, never raised
        result["ok"] = False
        result["error"] = "%s: %s" % (type(e).__name__, e)
    return result
