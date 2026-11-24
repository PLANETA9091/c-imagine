"""Two stack adapters with one interface: TruthStack (real NeoForge client via truth-ctl)
and OursStack (Horizon client via horizon-ctl, through hz.sh 3 ctl).

Every observation method returns a NORMALIZED dict (see norm_* helpers), so run.py and
diff.py never see stack-specific key names. Stdlib only, run with python3 -I.

Normalized shapes:
  block:      {"state": "ns:id[k=v,...]" | "unloaded", "id": "ns:id"}
  inventory:  {"counts": {id: n}, "carried": id|None, "selected": id|None}
  screen:     {"kind": "playing"|canonical, "menu_type": str|None, "slots": [[index, id, count], ...]}
  break:      {"block_after": state, "drops": {id: n}, "picked_up": {id: n}}
  place:      {"block_after": state, "inventory_change": {id: delta}}
  interact:   {"screen": kind, "block_after": state|None}
  use_item:   {"inventory_change": {id: delta}}
  click_slot: {"carried": id|None, "changed": [[index, id, count], ...]}
  select:     {"item": id}
  player:     {"health": f, "food": i, "saturation": f}
"""
import contextlib
import json
import math
import os
import re
import socket
import subprocess
import sys
import time
import glob

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import keyboard_layout  # noqa: E402  (the /keyboard HUD geometry, see keyboard_layout.py)
import view_ray  # noqa: E402  (the aim ray of use_at, see view_ray.py)

HOME = os.path.expanduser("~")
# Paths and ports come from the environment; the defaults are the current stand layout.
#   PARITY_TRUTH_PORT   truth-ctl TCP port on 127.0.0.1 (default 25599, -Dtruthctl.port)
#   PARITY_HZ           path to the horizon-ctl wrapper hz.sh (default ~/hud-work/horizon-play/hz.sh)
#   PARITY_HZ_N         horizon client number passed to hz.sh (default 3)
#   PARITY_OURS_LOG_DIR OURS server directory holding boot-*.log (default ~/hud-work/horizon-play/srv)
TRUTH_CTL_PORT = int(os.environ.get("PARITY_TRUTH_PORT", "25599"))
TRUTH_PLAYER = "TruthA"
OURS_PLAYER = "Lane_B"
HZ_SH = os.environ.get("PARITY_HZ", os.path.join(HOME, "hud-work/horizon-play/hz.sh"))
HZ_N = os.environ.get("PARITY_HZ_N", "3")
OURS_SRV_DIR = os.environ.get("PARITY_OURS_LOG_DIR", os.path.join(HOME, "hud-work/horizon-play/srv"))
OURS_INSPECT_PREFIX = "CIM-INSPECT "
#   PARITY_INSPECT_LOCK flock file that serializes '/cim inspect' across lane processes on this host
INSPECT_LOCK = os.environ.get("PARITY_INSPECT_LOCK",
                              os.path.join(HOME, "hud-work/horizon-play/scratch/parity-inspect.lock"))


class inspect_lock(object):
    """Context manager: exclusive flock on INSPECT_LOCK. No-op when fcntl is unavailable."""

    def __enter__(self):
        self.f = None
        try:
            import fcntl
        except ImportError:
            return self
        os.makedirs(os.path.dirname(INSPECT_LOCK), exist_ok=True)
        self.f = open(INSPECT_LOCK, "a+")
        fcntl.flock(self.f.fileno(), fcntl.LOCK_EX)
        return self

    def __exit__(self, *exc):
        if self.f is not None:
            self.f.close()  # closing the file releases the flock
        return False

# Canonical screen kinds. Keys are lower-cased raw names from either stack.
SCREEN_KIND = {
    "playing": "playing", "none": "playing", "": "playing", "null": "playing",
    "inventory": "inventory", "inventoryscreen": "inventory",
    "creative": "inventory", "creativemodeinventoryscreen": "inventory",
    "crafting": "crafting", "crafting_table": "crafting", "craftingtable": "crafting",
    "craftingscreen": "crafting", "crafting_table_screen": "crafting",
    "furnace": "furnace", "furnacescreen": "furnace", "furnacemenu": "furnace",
    "chest": "chest", "generic_9x3": "chest", "containerscreen": "chest",
    "chestmenu": "chest",
}


def canon_kind(raw):
    if raw is None:
        return "playing"
    key = str(raw).strip().lower()
    if key in SCREEN_KIND:
        return SCREEN_KIND[key]
    return key



# ------------------------------------------------------------------ abstract slots
# Scenarios name slots semantically: result, grid:N, input, fuel, output, container:N,
# main:M (0-based in the player's main inventory), hotbar:H (0-8), armor:K (0 head .. 3 feet), offhand.
# Each adapter maps those to its own menu indices per screen kind. Screen observations
# map indices back to the same names, so the diff compares semantics, not index numbering.
# A table entry is either an int (fixed index) or (fn, count): fn(n) for n in range(count).
# Every entry was checked against live replies (see runner/results/TRIAGE.md, "slot maps"):
#   truth: vanilla menu.slots order (crafting 0 result, 1-9 grid, 10-36 main, 37-45 hotbar;
#          furnace 0 input, 1 fuel, 2 output, 3-29 main, 30-38 hotbar; generic_9x3 0-26 container,
#          27-53 main, 54-62 hotbar; inventory 0 result, 1-4 grid, 5-8 armor, 9-35 main, 36-44 hotbar, 45 offhand)
#   ours (horizon-ctl): hotbar h = 0-8, main m = 9-35, grid 100+n, result 200, armor 500+k, offhand 504,
#          furnace = container 300/301/302 + player hotbar 0-8, main 9-35 (raw dump, not vanilla order),
#          chest container 300+n (27 slots), inventory grid 100+n (2x2), crafting grid 100+n (3x3)
_TRUTH_SLOTS = {
    "inventory": {"result": 0, "grid": (lambda n: 1 + n, 4), "armor": (lambda k: 5 + k, 4),
                  "main": (lambda m: 9 + m, 27), "hotbar": (lambda h: 36 + h, 9), "offhand": 45},
    "crafting": {"result": 0, "grid": (lambda n: 1 + n, 9), "main": (lambda m: 10 + m, 27),
                 "hotbar": (lambda h: 37 + h, 9)},
    "furnace": {"input": 0, "fuel": 1, "output": 2, "main": (lambda m: 3 + m, 27),
                "hotbar": (lambda h: 30 + h, 9)},
    "chest": {"container": (lambda n: n, 27), "main": (lambda m: 27 + m, 27),
              "hotbar": (lambda h: 54 + h, 9)},
    # ToolboxMenu: addSlots() adds the 8 compartment slots first, then MenuBase's player slots (main, hotbar).
    "toolbox": {"container": (lambda n: n, 8), "main": (lambda m: 8 + m, 27),
                "hotbar": (lambda h: 35 + h, 9)},
}
_TRUTH_SLOTS["create:toolbox"] = _TRUTH_SLOTS["toolbox"]  # the screen kind of a modded menu is its registry id
_OURS_SLOTS = {
    "inventory": {"result": 200, "grid": (lambda n: 100 + n, 4), "armor": (lambda k: 500 + k, 4),
                  "main": (lambda m: 9 + m, 27), "hotbar": (lambda h: h, 9), "offhand": 504},
    "crafting": {"result": 200, "grid": (lambda n: 100 + n, 9), "main": (lambda m: 9 + m, 27),
                 "hotbar": (lambda h: h, 9)},
    # raw dump (scratch/triage/probe_menu.py ours furnace): hotbar 0-8, main 9-35, furnace input/fuel/output 300-302
    "furnace": {"input": 300, "fuel": 301, "output": 302, "main": (lambda m: 9 + m, 27),
                "hotbar": (lambda h: h, 9)},
    "chest": {"container": (lambda n: 300 + n, 27), "main": (lambda m: 9 + m, 27),
              "hotbar": (lambda h: h, 9)},
    # UNVERIFIED (fix/menu-look scenario 56): toolbox compartments assumed 300+n like the chest; check the live
    # screen dump. Ported from the live runner copy (hk-menu-look2); the truth table above is from ToolboxMenu.
    "toolbox": {"container": (lambda n: 300 + n, 8), "main": (lambda m: 9 + m, 27),
                "hotbar": (lambda h: h, 9)},
}
_OURS_SLOTS["create:toolbox"] = _OURS_SLOTS["toolbox"]
_MAPS = {"truth": _TRUTH_SLOTS, "ours": _OURS_SLOTS}


def _entry_fn(v):
    return v[0] if isinstance(v, tuple) else None


def slot_index(stack_name, kind, spec):
    """Abstract slot spec -> this stack's index for screen kind."""
    table = _MAPS[stack_name].get(kind)
    if table is None:
        raise ValueError("no slot table for screen %r on %s" % (kind, stack_name))
    if ":" in spec:
        name, arg = spec.split(":", 1)
        v = table.get(name)
        if not isinstance(v, tuple):
            raise ValueError("slot %r not in %s/%s" % (spec, stack_name, kind))
        return v[0](int(arg))
    v = table.get(spec)
    if v is None or isinstance(v, tuple):
        raise ValueError("slot %r not in %s/%s" % (spec, stack_name, kind))
    return v


def slot_name(stack_name, kind, index):
    """This stack's index for screen kind -> abstract name (or 'raw:<index>' if unmapped).
    Inverse is exact: a tuple entry only claims indices fn(0..count-1)."""
    if index is None:
        return None
    table = _MAPS[stack_name].get(kind, {})
    for name, v in table.items():
        if isinstance(v, tuple):
            for n in range(v[1]):
                if v[0](n) == index:
                    return "%s:%d" % (name, n)
        elif v == index:
            return name
    return "raw:%s" % index


# ------------------------------------------------------------------ normalizers
def norm_id(s):
    """'minecraft:stone' / 'stone' -> 'minecraft:stone'; air/empty -> None."""
    if not s or s in ("minecraft:air", "air", "minecraft:void_air"):
        return None
    s = str(s).split("[", 1)[0].split("{", 1)[0].strip()
    if ":" not in s:
        s = "minecraft:" + s
    return s


def norm_state(s):
    """Block state string (or a {'state'|'block': ...} dict) -> 'ns:id[k=v,...]', or 'unloaded'."""
    if s is None:
        return None
    if isinstance(s, dict):
        if s.get("loaded") is False:
            return "unloaded"
        s = s.get("state") or s.get("block")
        if s is None:
            return None
    s = str(s).strip()
    if s in ("minecraft:void_air", "unloaded", "not_loaded"):
        return "unloaded"
    if "[" not in s:
        return norm_id(s) or "minecraft:air"
    base, rest = s.split("[", 1)
    rest = rest.rstrip("]")
    props = []
    for part in rest.split(","):
        if "=" in part:
            k, v = part.split("=", 1)
            props.append((k.strip(), v.strip()))
    base = base if ":" in base else "minecraft:" + base
    if not props:
        return base
    return base + "[" + ",".join("%s=%s" % kv for kv in sorted(props)) + "]"


def block_id(x):
    """Block reply -> id only (the state is not compared for place/interact/break replies)."""
    s = norm_state(x)
    if s in (None, "unloaded"):
        return s
    return s.split("[", 1)[0]


def norm_counts(d):
    out = {}
    for k, v in (d or {}).items():
        nid = norm_id(k)
        if nid is None or not v:
            continue
        out[nid] = out.get(nid, 0) + int(v)
    return out


def norm_delta(d):
    out = {}
    for k, v in (d or {}).items():
        nid = norm_id(k)
        if nid is None or not v:
            continue
        out[nid] = out.get(nid, 0) + int(v)
    return {k: v for k, v in out.items() if v}


def _slot_list(lst, stack_name, kind):
    out = []
    for s in lst or []:
        item = norm_id(s.get("item"))
        if item is None:
            continue
        idx = s.get("index", s.get("slot"))
        out.append([slot_name(stack_name, kind, idx), item, int(s.get("count", 0))])
    return sorted(out, key=lambda x: str(x[0]))


def ours_kind(reply):
    """horizon-ctl screen -> canonical kind. Horizon reports 'inventory' for the 3x3 crafting table
    (craft_size 3, title Crafting) and 'container' for both furnace and chest; the title tells them apart."""
    raw = str(reply.get("screen") or "").strip().lower()
    if raw in ("", "playing", "none", "null"):
        return "playing"
    title = reply.get("title")
    if isinstance(title, list):
        title = "".join(str(t.get("text", "")) for t in title if isinstance(t, dict))
    t = str(title or "").lower()
    if "furnace" in t:
        return "furnace"
    if "chest" in t:
        return "chest"
    if reply.get("craft_size") == 3:
        return "crafting"
    if raw == "inventory":
        return "inventory"
    return canon_kind(raw)


def menu_kind(reply):
    """The modded menu registry id a screen reply names (e.g. 'create:toolbox'), else None.
    Vanilla ids ('minecraft:*') and no id give None, so vanilla menus keep their screen-name kind.
    A modded menu is drawn by the client as its own Screen class on truth (toolboxscreen) but as a
    HUD screen on OURS (playing), so the menu's registry id is the name both sides can agree on."""
    mt = reply.get("menu_type") if isinstance(reply, dict) else None
    if not mt:
        return None
    mt = str(mt).strip().lower()
    if ":" not in mt or mt.startswith("minecraft:"):
        return None
    return mt


def norm_screen(reply, stack_name):
    """Kind + slots only. A modded menu's kind is its registry id (menu_kind); vanilla menu_type is not
    compared (truth 'minecraft:crafting' vs ours None)."""
    kind = ours_kind(reply) if stack_name == "ours" else canon_kind(reply.get("screen"))
    mk = menu_kind(reply)
    if mk and kind != "playing":
        kind = mk
    slots = reply.get("slots") or (reply.get("menu") or {}).get("slots") or []
    if kind == "playing":
        # no menu open: a slot list here is stale menu data, not an observable player state
        return {"kind": kind, "slots": []}
    return {"kind": kind, "slots": _slot_list(slots, stack_name, kind)}


def _in_radius(pos, center, r=3.0):
    """Euclidean distance from block centre (pos + 0.5) <= r."""
    return sum((float(a) - (float(b) + 0.5)) ** 2 for a, b in zip(pos, center)) <= r * r


def _changed(stack_name, kind, before, after):
    changed = []
    for idx in sorted(set(before) | set(after)):
        if before.get(idx) != after.get(idx):
            cid, cnt = after.get(idx, (None, 0))
            changed.append([slot_name(stack_name, kind, idx), cid, cnt])
    return sorted(changed, key=lambda x: str(x[0]))


def _map_from_screen(reply):
    slots = {}
    for s in reply.get("slots") or []:
        idx = s.get("index", s.get("slot"))
        cnt = int(s.get("count") or 0)
        slots[idx] = (norm_id(s.get("item")) if cnt else None, cnt)
    carried = reply.get("carried")
    cid = norm_id(carried.get("item")) if isinstance(carried, dict) and carried.get("count") else None
    return slots, cid


def norm_inventory(reply):  # player inventory: names by item only
    counts = {}
    for key in ("hotbar", "main", "armor", "offhand"):
        for s in reply.get(key) or []:
            nid = norm_id(s.get("item"))
            if nid:
                counts[nid] = counts.get(nid, 0) + int(s.get("count", 0))
    carried = reply.get("carried")
    cid = norm_id(carried.get("item")) if isinstance(carried, dict) else None
    if cid:
        counts[cid] = counts.get(cid, 0) + int(carried.get("count", 0))
    return {"counts": {k: v for k, v in counts.items() if v}, "carried": cid, "selected": None}


DUR_KEYS = ("damage", "max_damage")


def norm_durability(reply):
    """Per-stack durability from a raw inventory reply (kept OUT of the compared result, see run.py).
    status "reported" when some stack carries a damage or max_damage key, else "not_reported" (this
    includes a reply with no durability fields at all, and a damageable stack whose field is omitted at 0:
    the two cannot be told apart from the reply). stacks: [{group, slot, item, count, damage, max_damage}]
    for every stack with a durability key; fields: the durability keys seen."""
    out, seen = [], set()
    for group in ("hotbar", "main", "armor", "offhand"):
        entries = reply.get(group) if isinstance(reply, dict) else None
        if isinstance(entries, dict):
            entries = [entries]
        for s in entries or []:
            if not isinstance(s, dict):
                continue
            keys = [k for k in DUR_KEYS if k in s]
            if not keys:
                continue
            seen.update(keys)
            out.append({"group": group, "slot": s.get("slot"), "item": norm_id(s.get("item")),
                        "count": int(s.get("count") or 0),
                        "damage": s.get("damage"), "max_damage": s.get("max_damage")})
    return {"status": "reported" if out else "not_reported", "source": "client",
            "fields": sorted(seen), "stacks": out}


def norm_held(reply):
    held = reply.get("held")
    if isinstance(held, dict):
        return norm_id(held.get("item"))
    return None


def _f3(v):
    """Vector as [x, y, z] rounded to 1 decimal, from a list or an {x, y, z} dict; None when absent."""
    if isinstance(v, dict):
        v = [v.get("x"), v.get("y"), v.get("z")]
    if not isinstance(v, (list, tuple)) or len(v) != 3 or any(not isinstance(c, (int, float)) for c in v):
        return None
    return [round(float(c), 1) for c in v]


def _target_pos_face(t):
    """Block [x, y, z] and lower-case face name from a state 'target' dict; (None, None) when absent."""
    if not isinstance(t, dict):
        return None, None
    pos = t.get("pos", t.get("block_pos"))
    if isinstance(pos, dict):
        pos = [pos.get("x"), pos.get("y"), pos.get("z")]
    if not isinstance(pos, list) or len(pos) != 3:
        return None, None
    try:
        pos = [int(v) for v in pos]
    except (TypeError, ValueError):
        return None, None
    face = t.get("face")
    return pos, (face.lower() if isinstance(face, str) else None)


def _vec3(v):
    """[x, y, z] floats from a list or {x, y, z} dict; None when absent or malformed."""
    if isinstance(v, dict):
        v = [v.get("x"), v.get("y"), v.get("z")]
    if not isinstance(v, (list, tuple)) or len(v) != 3:
        return None
    try:
        return [float(c) for c in v]
    except (TypeError, ValueError):
        return None


_FACE_DIRS = {"down": (0, -1, 0), "up": (0, 1, 0), "north": (0, 0, -1), "south": (0, 0, 1),
              "west": (-1, 0, 0), "east": (1, 0, 0)}


def pinned_point(support, face, hit):
    """World point on the clicked FACE of the supporting block: support is [x, y, z] of that block,
    hit is a fraction [fx, fy, fz] inside it (None = centre). The coordinate along the face normal is
    snapped to the face plane, as truth-ctl pinnedHit does."""
    f = list(hit) if hit else [0.5, 0.5, 0.5]
    d = _FACE_DIRS[face]
    for a in range(3):
        if d[a] > 0:
            f[a] = 1.0
        elif d[a] < 0:
            f[a] = 0.0
    return [float(support[a]) + float(f[a]) for a in range(3)]


def support_of(pos, face):
    """The supporting block of a placement at pos clicked on FACE: pos minus the face direction."""
    d = _FACE_DIRS[face]
    return [int(pos[a]) - d[a] for a in range(3)]


def face_from_point(point, eye):
    """Block [x, y, z] and face name of the solid surface a view from eye through point hits, when
    the point lies on a block boundary (the fallback when Horizon reports no crosshair target).
    The axis with a boundary coordinate and the largest eye offset decides; (None, None) otherwise."""
    best = None
    for a in range(3):
        c = point[a]
        if abs(c - round(c)) > 1e-6:
            continue
        off = abs(eye[a] - c) if eye else 0.0
        if best is None or off > best[0]:
            best = (off, a)
    if best is None:
        return None, None
    a = best[1]
    names = [("west", "east"), ("down", "up"), ("north", "south")][a]
    blk = [int(v // 1) for v in point]
    if eye and eye[a] > point[a]:
        blk[a] = int(round(point[a])) - 1
        face = names[1]
    else:
        blk[a] = int(round(point[a]))
        face = names[0]
    return blk, face


# Vanilla KeyMapping translation key -> (horizon-ctl command, key or button name).
OURS_KEYS = {
    "key.forward": ("key", "w"), "key.back": ("key", "s"), "key.left": ("key", "a"),
    "key.right": ("key", "d"), "key.jump": ("key", "space"), "key.sneak": ("key", "shift"),
    "key.sprint": ("key", "ctrl"), "key.inventory": ("key", "e"), "key.drop": ("key", "q"),
    "key.swapOffhand": ("key", "f"), "key.attack": ("mouse", "left"), "key.use": ("mouse", "right"),
}
for _n in range(1, 10):
    OURS_KEYS["key.hotbar.%d" % _n] = ("key", str(_n))


def ours_key_of(name):
    """horizon-ctl (command, name) for a vanilla KeyMapping. Mod KeyMappings fail here by design."""
    if name in OURS_KEYS:
        return OURS_KEYS[name]
    raise ValueError(
        "OURS cannot press %s: not a vanilla KeyMapping and not in MOD_KEY_CODES (the /keyboard HUD path)"
        % name)


def norm_player(reply):
    p = reply.get("player") or reply
    return {"health": p.get("health"), "food": p.get("food"), "saturation": p.get("saturation")}


# ------------------------------------------------------------------ base
class Stack:
    _inv_raw = None

    def last_durability(self):
        """Durability of the most recent observe_inventory (raw reply kept by the stack)."""
        return norm_durability(self._inv_raw or {})

    name = "?"
    player = "?"

    def setup_command(self, text):
        raise NotImplementedError

    def select(self, item):
        raise NotImplementedError

    def break_(self, pos, timeout=20):
        raise NotImplementedError

    def place(self, pos, item=None, face=None, hit=None, holds=()):
        raise NotImplementedError

    def interact(self, pos, face=None, holds=()):
        raise NotImplementedError

    def use_item(self, hold=None, holds=()):
        raise NotImplementedError

    def use_at(self, point, holds=()):
        raise NotImplementedError

    def key(self, name, ticks=1):
        raise NotImplementedError

    def respawn(self):
        raise NotImplementedError

    def observe_blocks(self, box):
        raise NotImplementedError

    def observe_entities(self, center, radius, type_=None):
        raise NotImplementedError

    # hold modifiers: a key held down around one action (vanilla sneak for wrench, pickup and place rules)
    def hold_down(self, name):
        raise NotImplementedError

    def hold_up(self, name):
        raise NotImplementedError

    @contextlib.contextmanager
    def holding(self, holds):
        """Holds the named modifiers down for the block, then releases them in reverse order. Two ticks after
        the press, so the input state is set before the action; every release is attempted."""
        pressed = []
        try:
            for name in tuple(holds or ()):
                self.hold_down(name)
                pressed.append(name)
            if pressed:
                self.wait(2)
            yield
        finally:
            errors = []
            for name in reversed(pressed):
                try:
                    self.hold_up(name)
                except Exception as e:  # keep releasing the rest
                    errors.append("%s: %s" % (name, e))
            if errors:
                raise RuntimeError("hold release failed: " + "; ".join(errors))

    def motion(self):
        raise NotImplementedError

    def click_slot(self, index, mode="left", kind="inventory"):
        raise NotImplementedError

    def open_inventory(self):
        raise NotImplementedError

    def close(self):
        raise NotImplementedError

    def observe_block(self, pos):
        raise NotImplementedError

    def observe_inventory(self):
        raise NotImplementedError

    def observe_screen(self):
        raise NotImplementedError

    def observe_player(self):
        raise NotImplementedError

    def screenshot(self, path):
        raise NotImplementedError

    def wait(self, ticks):
        raise NotImplementedError

    def tp(self, x, y, z, yaw=0.0, pitch=0.0):
        raise NotImplementedError

    def wait_ready(self, seconds=60):
        return None

    # heal hooks (heal.py): probe the screen, respawn, close, click a button, pause
    def heal_probe(self):
        raise NotImplementedError

    def heal_respawn(self):
        raise NotImplementedError

    def heal_close(self):
        raise NotImplementedError

    def heal_click(self, label):
        raise NotImplementedError

    def heal_sleep(self, seconds):
        time.sleep(seconds)

    # sources for observations: "client" = what the client renders/holds,
    # "server" = the server's truth (OURS /cim inspect). Stored per record, never diffed.
    def source_of(self, kind):
        return "client"


# ------------------------------------------------------------------ TRUTH
class TruthStack(Stack):
    name = "truth"
    player = TRUTH_PLAYER

    def __init__(self, port=TRUTH_CTL_PORT, host="127.0.0.1", player=TRUTH_PLAYER):
        self.port = port
        self.host = host
        self.player = player  # per lane: the truth player name (see lanes.py)

    def call(self, cmd, args=None, opts=None, timeout=300.0):
        req = {"cmd": cmd, "args": [str(a) for a in (args or [])], "opts": opts or {}}
        line = json.dumps(req) + "\n"
        with socket.create_connection((self.host, self.port), timeout=timeout) as s:
            s.sendall(line.encode("utf-8"))
            buf = b""
            while b"\n" not in buf:
                chunk = s.recv(65536)
                if not chunk:
                    break
                buf += chunk
        text = buf.split(b"\n", 1)[0].decode("utf-8", "replace")
        if not text:
            raise RuntimeError("truth-ctl: no reply to %s" % cmd)
        reply = json.loads(text)
        if isinstance(reply, dict) and "error" in reply:
            raise RuntimeError("truth-ctl %s: %s (%s)" % (cmd, reply.get("error"), reply.get("kind")))
        return reply

    @staticmethod
    def _p(pos):
        return [str(int(v)) if float(v).is_integer() else str(v) for v in pos]

    def setup_command(self, text):
        self.call("command", text.split())
        return None

    def wait_ready(self, seconds=60):
        return self.call("wait-ready", [seconds], timeout=seconds + 30)

    def heal_probe(self):
        # truth-ctl state: "screen" is "playing", "title" or the screen class simple name (DeathScreen).
        # truth-ctl has no button listing, so no resource-pack prompt is ever detected here.
        st = self.call("state")
        if not isinstance(st, dict):
            raise RuntimeError("truth-ctl state returned %r" % (st,))
        return {"kind": st.get("screen") or "playing", "buttons": [], "enabled": []}

    def heal_respawn(self):
        return self.call("respawn")

    def heal_close(self):
        return self.call("close")

    def heal_click(self, label):
        raise RuntimeError("truth-ctl cannot click buttons (no click-button command): %s" % label)

    def select(self, item):
        r = self.call("select", [item])
        return {"item": norm_id(r.get("item"))}

    def break_(self, pos, timeout=20, holds=()):
        with self.holding(holds):
            r = self.call("break", self._p(pos), {"timeout": str(timeout)}, timeout=timeout + 60)
        self.wait(10)  # same sampling point as OURS: 10 ticks after the break
        n = self.item_entities(pos)
        return {
            "block_after": block_id(r.get("block")),
            # truth-ctl 'entities' carries no item id, so the id comes from the break reply's drops
            "drops": norm_counts(r.get("drops")) if n else {},
            "item_entities": n,
            "picked_up": norm_delta(r.get("picked_up")),
        }

    def item_entities(self, pos):
        """Count of dropped item entities within 3 blocks of the block centre, 10 ticks after the break.
        truth-ctl 'entities' radius is measured from the PLAYER, so ask for 5 and filter to the block."""
        r = self.call("entities", ["5"])
        return sum(1 for e in r.get("entities") or []
                   if e.get("type") == "minecraft:item" and _in_radius(e.get("pos") or [0, 0, 0], pos))

    def hold_down(self, name):
        self.call("key", [HOLD_KEYS[name][0], "press"])

    def hold_up(self, name):
        self.call("key", [HOLD_KEYS[name][0], "release"])

    def respawn(self):
        r = self.call("respawn")
        return {"respawned": r.get("respawned") is True}

    def observe_blocks(self, box):
        lo, hi = box_bounds(box)
        r = self.call("blocks", lo + hi)
        return norm_counts(r.get("counts") or {})

    def observe_entities(self, center, radius, type_=None):
        """truth-ctl 'entities' measures its box from the PLAYER: ask for the player-to-centre distance plus the
        radius (a box that holds the sphere), then keep the entities within the sphere."""
        st = self.call("state")
        p = _f3((st.get("player") or {}).get("position")) or [float(v) for v in center]
        dist = math.sqrt(sum((a - (c + 0.5)) ** 2 for a, c in zip(p, center)))
        r = self.call("entities", [str(round(dist + float(radius) + 1.0, 2))])
        out = []
        for e in r.get("entities") or []:
            pos = e.get("pos") or [0, 0, 0]
            if not _in_radius(pos, center, float(radius)):
                continue
            if type_ and norm_id(type_) != norm_id(e.get("type")):
                continue
            out.append(norm_entity(e.get("type"), pos, e.get("item"), e.get("count")))
        return {"entities": sorted(out, key=_entity_key)}

    def place(self, pos, item=None, face=None, hit=None, holds=()):
        args = self._p(pos) + ([item] if item else [])
        if face:
            args += ["--face", face]
        if hit:
            args += ["--hit"] + [str(float(v)) for v in hit]
        with self.holding(holds):
            r = self.call("place", args)
        return {
            "block_after": block_id(r.get("block")),
            "inventory_change": norm_delta(r.get("inventory_change")),
        }

    def interact(self, pos, face=None, holds=()):
        args = self._p(pos) + ([face] if face else [])
        with self.holding(holds):
            r = self.call("interact", args)
        kind = canon_kind(r.get("screen"))
        if kind != "playing":
            mk = menu_kind(self.call("screen"))
            if mk:
                kind = mk
        return {"screen": kind, "block_after": block_id(r.get("block"))}

    def use_item(self, hold=None, holds=()):
        # hold is in SECONDS (horizon-ctl --hold SECONDS); truth-ctl --hold takes TICKS (20 per second)
        with self.holding(holds):
            if hold:
                r = self.call("use-item", ["--hold", max(1, int(round(float(hold) * 20)))])
            else:
                r = self.call("use-item")
        return {"inventory_change": norm_delta(r.get("inventory_change"))}

    def use_at(self, point, holds=()):
        with self.holding(holds):
            r = self.call("use-at", [str(float(v)) for v in point])
        hit = r.get("hit") or {}
        pos = hit.get("pos")
        face = hit.get("face")
        return {
            "hit_pos": [int(v) for v in pos] if pos else None,
            "hit_face": face.lower() if isinstance(face, str) else None,
            "block_after": block_id(r.get("block")),
            "inventory_change": norm_delta(r.get("inventory_change")),
        }

    def key(self, name, ticks=1):
        ticks = int(ticks)
        self.call("key", [name, "tap", "--ticks", str(ticks)], timeout=max(60, ticks / 20.0 + 30))
        return {"pressed": True, "ticks": ticks}

    def motion(self):
        p = self.call("state").get("player") or {}
        return {"position": _f3(p.get("position")), "velocity": _f3(p.get("velocity"))}

    def click_slot(self, index, mode="left", kind="inventory"):
        before, _ = _map_from_screen(self.call("screen"))
        self.call("click-slot", [index, mode])
        self.wait(5)  # results (crafting output) update on the next ticks; read after 5
        after, carried = _map_from_screen(self.call("screen"))
        return {"carried": carried, "changed": _changed(self.name, kind, before, after)}

    def clear_screen_map(self):
        return _map_from_screen(self.call("screen"))

    def open_inventory(self):
        r = self.call("open-inventory")
        return {"screen": canon_kind(r.get("screen"))}

    def close(self):
        r = self.call("close")
        return {"screen": canon_kind(r.get("screen"))}

    def observe_block(self, pos):
        r = self.call("block", self._p(pos))
        if not r.get("loaded", True):
            return {"state": "unloaded", "id": None}
        st = norm_state(r.get("state") or r.get("block"))
        return {"state": st, "id": st.split("[", 1)[0]}

    def observe_inventory(self):
        raw = self.call("inventory")
        self._inv_raw = raw
        return norm_inventory(raw)

    def observe_screen(self):
        return norm_screen(self.call("screen"), self.name)

    def observe_player(self):
        return norm_player(self.call("state"))

    def held(self):
        return norm_held(self.call("state"))

    def screenshot(self, path):
        self.call("screenshot", [path], timeout=60)
        return path

    def wait(self, ticks):
        self.call("wait", [int(ticks)], timeout=max(60, ticks / 20.0 + 30))
        return None

    def tp(self, x, y, z, yaw=0.0, pitch=0.0):
        self.call("command", ["tp", self.player, str(x), str(y), str(z)])
        self.call("look", [str(yaw), str(pitch)])
        return None


# ------------------------------------------------------------------ OURS
# 1.21.1 data components wrap the map in 'levels'; Paper 26.3 takes the bare id->level map.
_ENCH_LEVELS_RE = re.compile(r"(stored_enchantments|enchantments)=\{levels:\{([^{}]*)\}\}")
MODDED_NS_RE = re.compile(r"^(create|biomesoplenty|dynamictrees|combat_roll|cimtest):")

# Hold modifiers a do step may carry: "sneak": true or "hold": ["sneak"]. truth-ctl key name, horizon key.
HOLD_KEYS = {"sneak": ("key.sneak", "shift")}
# Mod KeyMapping (translation key) -> its default GLFW key code on the /keyboard HUD (census defaultKey).
MOD_KEY_CODES = {"keybinds.combat_roll.roll": 82}  # R
BOX_CELL_CAP = 512  # OURS reads a box cell by cell (inspect) only when it holds carriers
CARRIER_IDS = frozenset(["minecraft:note_block", "minecraft:barrier"])  # what a vanilla client draws for mods
DISPLAY_TYPES = frozenset(["minecraft:item_display", "minecraft:block_display", "minecraft:text_display",
                           "minecraft:interaction"])  # carriers and helpers, not entities on the truth side


def holds_of(step):
    """Hold modifiers of a do step as a sorted tuple: "sneak": true and/or "hold": [...]. Unknown names raise."""
    h = step.get("hold")  # a number is use_item's hold duration (seconds), not a modifier list
    names = set(h) if isinstance(h, list) else set()
    if step.get("sneak"):
        names.add("sneak")
    unknown = sorted(names - set(HOLD_KEYS))
    if unknown:
        raise ValueError("unknown hold modifier(s) %s (known: %s)" % (unknown, sorted(HOLD_KEYS)))
    return tuple(sorted(names))


def box_bounds(box):
    """[x1, y1, z1, x2, y2, z2] (any corner order) -> (lo, hi) integer corners."""
    if not (isinstance(box, list) and len(box) == 6 and all(
            isinstance(v, (int, float)) and not isinstance(v, bool) for v in box)):
        raise ValueError("box must be [x1, y1, z1, x2, y2, z2] numbers, got %r" % (box,))
    a = [int(v) for v in box[:3]]
    b = [int(v) for v in box[3:]]
    return [min(a[i], b[i]) for i in range(3)], [max(a[i], b[i]) for i in range(3)]


def box_cells(lo, hi):
    for x in range(lo[0], hi[0] + 1):
        for y in range(lo[1], hi[1] + 1):
            for z in range(lo[2], hi[2] + 1):
                yield [x, y, z]


def norm_entity(type_, pos, item=None, count=None):
    """One entity of an 'observe entities' reply: {type, pos (block-floored), item?, count?}. The item id is
    normalised like every other id; count defaults to 1 when an item is given without one."""
    out = {"type": norm_id(type_) or str(type_), "pos": [int(math.floor(float(v))) for v in pos]}
    iid = norm_id(item)
    if iid:
        out["item"] = iid
        out["count"] = int(count or 1)
    return out


def _entity_key(e):
    return (e["type"], e.get("item") or "", e["pos"])


class OursStack(Stack):
    """Horizon client (hz.sh 3 ctl) on the OURS server. Mod give/setblock become /cim commands.

    Client-visible facts are not enough for modded ids: a vanilla client sees mod items as paper and
    mod blocks as carriers. So for mod ids the adapter takes the SERVER truth from '/cim inspect':
      block after place/break/interact  -> inspect block (real state, id only is compared)
      inventory (counts, deltas, drops)  -> inspect inv (slot -> modded id)
      screen player slots                -> inspect inv ids substituted where the client shows a carrier
      select modded id                   -> inspect inv finds the hotbar slot, horizon select SLOT+1
    """
    name = "ours"
    player = OURS_PLAYER

    def __init__(self, use_inspect=True, srv_dir=OURS_SRV_DIR, hz_n=None, player=None):
        self.use_inspect = use_inspect
        self.srv_dir = srv_dir
        self.hz_n = str(hz_n if hz_n is not None else HZ_N)  # horizon client slot (hz.sh N), per lane
        self.player = player or OURS_PLAYER
        self.inspect_hits = 0
        self.inspect_misses = 0

    def ctl(self, *args, timeout=300):
        cmd = [HZ_SH, self.hz_n, "ctl"] + [str(a) for a in args]
        p = subprocess.run(cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                           timeout=timeout, stdin=subprocess.DEVNULL)
        out = p.stdout.decode("utf-8", "replace").strip()
        if p.returncode != 0:
            raise RuntimeError("horizon-ctl %s rc=%d: %s" % (args[0], p.returncode,
                                                            (p.stderr or p.stdout).decode("utf-8", "replace")[-400:]))
        if not out:
            return {}
        try:
            return json.loads(out)
        except json.JSONDecodeError:
            return {"raw": out}

    @staticmethod
    def _p(pos):
        return [str(int(v)) if float(v).is_integer() else str(v) for v in pos]

    # ---- /cim translation (mod ids only). Vanilla commands pass through unchanged.
    def translate(self, text):
        # Scenarios are written in 1.21.1 data-component syntax (enchantments={levels:{...}} and
        # stored_enchantments={levels:{...}}). Paper 26.3 rejects the 'levels' wrapper ("Malformed
        # minecraft:stored_enchantments ... levels") and takes the bare id->level map. Only the map's
        # wrapper changes, not the intent.
        text = _ENCH_LEVELS_RE.sub(r"\1={\2}", text)
        parts = text.split()
        if not parts:
            return text
        head = parts[0].lstrip("/")
        if head == "give" and len(parts) >= 3 and MODDED_NS_RE.match(parts[2]):
            count = parts[3] if len(parts) > 3 else "1"
            return "cim give %s %s %s" % (parts[1].replace("{player}", self.player), parts[2], count)
        if head == "setblock" and len(parts) >= 5 and MODDED_NS_RE.match(parts[4]):
            # /cim placeblock is viewer-relative (no x y z), so modded blocks are placed with real
            # 'place' steps on both stacks instead of setblock
            raise ValueError("modded setblock is not supported: place it with a place step")
        return text

    def setup_command(self, text):
        self.ctl("command", self.translate(text))
        return None

    def wait_ready(self, seconds=60):
        return self.ctl("wait-ready", seconds, timeout=seconds + 30)

    def heal_probe(self):
        # horizon-ctl screen: "screen" kind, "buttons" (label, enabled). The pack prompt shows as buttons
        # while 'state' still says playing, so the buttons matter, not only the kind.
        r = self.ctl("screen")
        if not isinstance(r, dict):
            raise RuntimeError("horizon-ctl screen returned %r, not an object" % (r,))
        btns = [b for b in (r.get("buttons") or []) if isinstance(b, dict)]
        kind = ours_kind(r)
        if kind == "playing":
            insp = self._hud_menu()
            if insp is not None:
                kind = menu_kind(insp) or canon_kind(insp.get("screen") or "menu")
        return {"kind": kind,
                "buttons": [str(b.get("label")) for b in btns],
                "enabled": [str(b.get("label")) for b in btns if b.get("enabled")]}

    def heal_respawn(self):
        return self.ctl("respawn")

    def heal_close(self):
        if self._hud_menu() is not None:
            # ScreenHud draws the menu on the HUD and closes it on sneak (Esc is not visible to the server)
            self.key("key.sneak", 1)
            return {"closed": "sneak"}
        return self.ctl("close")

    def heal_click(self, label):
        return self.ctl("click-button", label)

    # ---- server truth via /cim inspect (CIM-INSPECT JSON line in the newest boot log)
    def _newest_log(self):
        logs = glob.glob(os.path.join(self.srv_dir, "boot-*.log"))
        return max(logs, key=os.path.getmtime) if logs else None

    def inspect(self, *args, wait=8.0):
        """Run '/cim inspect ARGS' and return the decoded CIM-INSPECT object, or None.
        Serialized across lanes (one inspect in flight at a time, see INSPECT_LOCK): every lane reads the
        same server log, so an unlocked reply could be another lane's CIM-INSPECT line."""
        if not self.use_inspect:
            return None
        with inspect_lock():
            return self._inspect(*args, wait=wait)

    def _inspect(self, *args, wait=8.0):
        if not self.use_inspect:
            return None
        log = self._newest_log()
        if not log:
            return None
        start = os.path.getsize(log)
        self.ctl("command", "cim inspect " + " ".join(str(a) for a in args))
        decoder = json.JSONDecoder()
        deadline = time.time() + wait
        while time.time() < deadline:
            time.sleep(0.25)
            with open(log, "rb") as f:
                f.seek(start)
                tail = f.read().decode("utf-8", "replace")
            for line in tail.splitlines():
                i = line.find(OURS_INSPECT_PREFIX)
                if i >= 0:
                    try:
                        obj, _ = decoder.raw_decode(line[i + len(OURS_INSPECT_PREFIX):])
                        self.inspect_hits += 1
                        return obj
                    except ValueError:
                        pass
        self.inspect_misses += 1
        return None

    def source_of(self, kind):
        return "server" if kind in ("block", "inventory") and self.use_inspect else "client"

    # ---- server-truth helpers
    def _settle(self, ticks=6):
        """Let the server apply the last action (block change, inventory update, drops)."""
        self.ctl("wait", ticks, timeout=120)

    def _server_inv(self):
        """Server inventory: list of {slot, index, id|None, count} from /cim inspect inv, or None."""
        insp = self.inspect("inv", self.player)
        if insp is None or "slots" not in insp:
            return None
        out = []
        for sl in insp["slots"]:
            cnt = int(sl.get("count") or 0)
            out.append({"slot": sl.get("slot"), "index": sl.get("index"),
                        "id": norm_id(sl.get("id")) if cnt else None, "count": cnt})
        return out

    def _server_counts(self):
        slots = self._server_inv()
        if slots is None:
            return None
        counts = {}
        for s in slots:
            if s["id"]:
                counts[s["id"]] = counts.get(s["id"], 0) + s["count"]
        return counts

    def _counts(self):
        srv = self._server_counts()
        if srv is not None:
            return srv
        return norm_inventory(self.ctl("inventory"))["counts"]

    @staticmethod
    def _delta(before, after):
        keys = set(before) | set(after)
        out = {k: after.get(k, 0) - before.get(k, 0) for k in keys}
        return {k: v for k, v in out.items() if v}

    def _server_block_id(self, pos):
        insp = self.inspect("block", *self._p(pos))
        if insp is not None and "state" in insp:
            return norm_state(insp["state"]).split("[", 1)[0]
        return None

    @staticmethod
    def _player_name(slot, index):
        """/cim inspect slot (hotbar|main, index) -> abstract name used by the slot tables."""
        if slot == "hotbar":
            return "hotbar:%d" % index
        if slot == "main":
            return "main:%d" % (index - 9)
        return None

    def _apply_server_ids(self, scr):
        """Replace carrier ids (paper, note_block...) in player slots with the server's modded id.
        Only when the counts agree, so a slot the server and client disagree on stays as the client shows it."""
        self._apply_container_ids(scr)
        srv = self._server_inv()
        if not srv:
            return scr
        kind = ours_kind(scr)
        by_index = {s.get("slot", s.get("index")): s for s in scr.get("slots") or []}
        for s in srv:
            if not s["id"] or not MODDED_NS_RE.match(s["id"]):
                continue
            name = self._player_name(s["slot"], s["index"])
            if name is None:
                continue
            try:
                sidx = slot_index("ours", kind, name)
            except ValueError:
                continue
            entry = by_index.get(sidx)
            if entry is not None and int(entry.get("count") or 0) == s["count"]:
                entry["item"] = s["id"]
        return scr

    def _apply_container_ids(self, scr):
        """Modded ids for a vanilla-container menu (furnace, schematic table): /cim inspect screen lists the
        container's top slots by menu index. Applied only when the counts agree, as for player slots."""
        insp = self.inspect("screen", self.player)
        if not insp or not insp.get("open") or not insp.get("slots"):
            return scr
        srv = {}
        for s in insp["slots"]:
            try:
                srv[int(s.get("index"))] = s
            except (TypeError, ValueError):
                continue
        for entry in scr.get("slots") or []:
            s = srv.get(entry.get("slot", entry.get("index")))
            if s is None:
                continue
            nid = norm_id(s.get("item"))
            if nid and MODDED_NS_RE.match(nid) and int(entry.get("count") or 0) == int(s.get("count") or 0):
                entry["item"] = nid
        return scr

    # ---- actions
    def select(self, item):
        nid = norm_id(item)
        if MODDED_NS_RE.match(nid or ""):
            slots = self._server_inv()
            if slots is None:
                raise RuntimeError("select %s needs /cim inspect (use_inspect)" % nid)
            for s in slots:
                if s["slot"] == "hotbar" and s["id"] == nid and s["count"]:
                    self.ctl("select", s["index"] + 1)  # horizon-ctl select takes hotbar slot 1-9
                    return {"item": nid}
            raise RuntimeError("%s is not in the hotbar (server inventory)" % nid)
        r = self.ctl("select", item)
        held = r.get("held")
        return {"item": norm_id(held.get("item")) if isinstance(held, dict) else norm_id(r.get("item"))}

    def break_(self, pos, timeout=20, holds=()):
        before = self._counts()
        with self.holding(holds):
            r = self.ctl("break", *self._p(pos), "--timeout", timeout, timeout=timeout + 120)
        self._settle(10)  # same sampling point as TRUTH: 10 ticks after the break
        drops, n = self._drops_near(pos)
        self.ctl("wait", 20, timeout=120)  # pickup window; the player stands 2 blocks away, so nothing is taken
        after = self._counts()
        bid = self._server_block_id(pos)
        if bid is None:
            bid = norm_id((r.get("block") or {}).get("block") if isinstance(r.get("block"), dict) else r.get("block"))
        return {
            "block_after": bid,
            "drops": drops,
            "item_entities": n,
            "picked_up": self._delta(before, after),
        }

    def _drops_near(self, pos):
        """Dropped item entities within 3 blocks of the block centre (server truth).
        Returns ({id: count}, entity_count). Placed-block carriers (item_display) are not drops."""
        insp = self.inspect("entities", *self._p(pos), "3")
        if not insp:
            return {}, 0
        out, n = {}, 0
        for e in insp.get("entities") or []:
            if e.get("type") != "minecraft:item" and e.get("vanilla") != "minecraft:item":
                continue
            if not _in_radius(e.get("pos") or [0, 0, 0], pos):
                continue
            n += 1
            it = e.get("item") if isinstance(e.get("item"), dict) else {}
            nid = norm_id(it.get("id"))
            if nid:
                out[nid] = out.get(nid, 0) + int(it.get("count", 1) or 1)
        return out, n

    def hold_down(self, name):
        self.ctl("key", HOLD_KEYS[name][1], "down")

    def hold_up(self, name):
        self.ctl("key", HOLD_KEYS[name][1], "up")

    def respawn(self):
        r = self.ctl("respawn")
        return {"respawned": r.get("respawned") is True}

    def observe_entities(self, center, radius, type_=None):
        """/cim inspect entities (server truth, item stacks with modded ids). Carrier displays are not entities."""
        insp = self.inspect("entities", *self._p(center), "%g" % (float(radius) + 1.0))
        if insp is None:
            raise RuntimeError("inspect entities returned nothing (server log)")
        out = []
        for e in insp.get("entities") or []:
            t, v = norm_id(e.get("type")), norm_id(e.get("vanilla"))
            if t in DISPLAY_TYPES or v in DISPLAY_TYPES:
                continue
            etype = "minecraft:item" if "minecraft:item" in (t, v) else (t or v)
            pos = e.get("pos") or [0, 0, 0]
            if not _in_radius(pos, center, float(radius)):
                continue
            if type_ and norm_id(type_) != etype:
                continue
            it = e.get("item") if isinstance(e.get("item"), dict) else {}
            out.append(norm_entity(etype, pos, it.get("id"), it.get("count")))
        return {"entities": sorted(out, key=_entity_key)}

    def observe_blocks(self, box):
        """Block counts of a box. The client's counts are right for vanilla blocks; carriers (note blocks,
        barriers) are read per cell with /cim inspect block for the modded id (cap BOX_CELL_CAP cells)."""
        lo, hi = box_bounds(box)
        r = self.ctl("blocks", *lo, *hi)
        raw = r.get("counts") if isinstance(r.get("counts"), dict) else r
        counts = norm_counts({k: v for k, v in raw.items() if isinstance(v, int)})
        if not any(k in CARRIER_IDS for k in counts):
            return counts
        volume = (hi[0] - lo[0] + 1) * (hi[1] - lo[1] + 1) * (hi[2] - lo[2] + 1)
        if volume > BOX_CELL_CAP:
            raise ValueError("box holds carriers and %d cells; per-cell inspect is capped at %d (split the box)"
                             % (volume, BOX_CELL_CAP))
        out = {}
        for cell in box_cells(lo, hi):
            c = self.ctl("block", *cell)
            nid = norm_id(norm_state(c.get("state") or c.get("block")).split("[", 1)[0])
            if nid is None:
                continue
            if nid in CARRIER_IDS:
                server = self._server_block_id(cell)
                if server is None:
                    raise RuntimeError("inspect block missed at %s (carrier %s)" % (cell, nid))
                nid = server
            out[nid] = out.get(nid, 0) + 1
        return out

    def place(self, pos, item=None, face=None, hit=None, holds=()):
        if face:
            return self._place_pinned(pos, item, face, hit, holds=holds)
        before = self._counts()
        args = ["place", *self._p(pos)]
        if item:
            nid = norm_id(item)
            if MODDED_NS_RE.match(nid):
                raise ValueError("select a modded item first; place takes no ITEM for mod ids")
            args += ["--item", nid]
        native = "sneak" in holds  # horizon-ctl place --sneak; other holds are key presses
        if native:
            args.append("--sneak")
        with self.holding(tuple(h for h in holds if h != "sneak")):
            r = self.ctl(*args)
        self._settle(6)
        bid = self._server_block_id(pos)
        if bid is None:
            blk = r.get("block")
            bid = norm_id(blk.get("block") if isinstance(blk, dict) else blk)
        after = self._counts()
        return {"block_after": bid, "inventory_change": self._delta(before, after)}

    def _place_pinned(self, pos, item, face, hit, holds=()):
        """horizon-ctl place has no face or hit point: look at the point on the clicked face of the
        supporting block, then right-click (the held item lands on the crosshair's face)."""
        before = self._counts()
        if item:
            self.select(item)
        support = support_of(pos, face)
        point = pinned_point(support, face, hit)
        self.ctl("look-at", *[str(v) for v in point])
        self.ctl("wait", 1)
        with self.holding(holds):
            self.ctl("mouse", "right", "down")
            self.ctl("mouse", "right", "up")
        self._settle(6)
        bid = self._server_block_id(pos)
        return {"block_after": bid, "inventory_change": self._delta(before, self._counts())}

    def interact(self, pos, face=None, holds=()):
        args = ["interact", *self._p(pos)] + ([face] if face else [])
        if "sneak" in holds:
            args.append("--sneak")  # horizon-ctl interact --sneak
        with self.holding(tuple(h for h in holds if h != "sneak")):
            self.ctl(*args)
        self._settle(5)  # truth-ctl reports the screen after 5 ticks; Horizon opens it late
        scr = self._screen_reply()
        return {"screen": norm_screen(scr, self.name)["kind"], "block_after": self._server_block_id(pos)}

    def use_item(self, hold=None, holds=()):
        before = self._counts()
        args = ["use-item"] + (["--hold", hold] if hold else [])
        with self.holding(holds):
            self.ctl(*args)
        self._settle(6)
        return {"inventory_change": self._delta(before, self._counts())}

    def use_at(self, point, holds=()):
        """look-at the point, then a right press and release. The hit is the crosshair target after the
        turn when Horizon reports one (what the click uses); otherwise the first solid cell of the view ray
        from the player's eye along the reported yaw and pitch (view_ray.py). Block and inventory are then read
        like place."""
        before = self._counts()
        self.ctl("look-at", *[str(float(v)) for v in point])
        # The crosshair target refreshes on the client tick; Horizon's target is None for a few ticks
        # after look-at (TRIAGE-3 32/33), so retry it, then trace the view ray from the reported look.
        t_pos, t_face, st = None, None, {}
        for _ in range(6):
            self.ctl("wait", 1)
            st = self.ctl("state")
            t_pos, t_face = _target_pos_face(st.get("target"))
            if t_pos is not None:
                break
        if t_pos is not None:
            hit_pos, hit_face, hit_src = t_pos, t_face, "target"
        else:
            ray = self._view_ray_hit(st)
            hit_pos, hit_face = (ray["pos"], ray["face"]) if ray else (None, None)
            hit_src = "ray" if ray else "none"
        self.last_hit_source = hit_src  # diagnostics only: not part of the observed result
        with self.holding(holds):
            self.ctl("mouse", "right", "down")
            self.ctl("mouse", "right", "up")
        self._settle(5)  # same reply point as truth-ctl use-at: 5 ticks
        return {
            "hit_pos": hit_pos,
            "hit_face": hit_face,
            "block_after": self._server_block_id(hit_pos) if hit_pos else None,
            "inventory_change": self._delta(before, self._counts()),
        }

    def _view_ray_hit(self, st):
        """The first solid cell the player's view ray reaches (view_ray.first_solid_cell), read from the client
        with block queries. None when the player's pose is not in the state reply or nothing solid is in reach
        (or a cell on the way is not loaded: no hit is better than a wrong one)."""
        player = st.get("player") if isinstance(st.get("player"), dict) else {}
        feet = _vec3(player.get("position")) or _vec3(st.get("position"))
        yaw, pitch = st.get("yaw", player.get("yaw")), st.get("pitch", player.get("pitch"))
        if feet is None or yaw is None or pitch is None:
            return None
        eye = _vec3(st.get("eye"))
        cache = {}

        def solid(cell):
            if cell not in cache:
                r = self.ctl("block", *[str(v) for v in cell])
                if r.get("loaded") is False or not (r.get("block") or r.get("state")):
                    cache[cell] = None
                else:
                    cache[cell] = not view_ray.is_air_id(r.get("block") or r.get("state"))
            return cache[cell]

        if eye is not None:
            direction = view_ray.view_direction(float(yaw), float(pitch))
            return view_ray.first_solid_cell(tuple(eye), direction, solid)
        return view_ray.aim_hit(feet, float(yaw), float(pitch), solid)

    def key(self, name, ticks=1):
        if name in MOD_KEY_CODES:
            return self._keyboard_tap(MOD_KEY_CODES[name], int(ticks), name)
        cmd, code = ours_key_of(name)
        ticks = int(ticks)
        self.ctl(cmd, code, "down")
        try:
            self.ctl("wait", ticks, timeout=max(60, ticks / 20.0 + 30))
        finally:
            self.ctl(cmd, code, "up")
        return {"pressed": True, "ticks": ticks}

    def _keyboard_tap(self, code, ticks, name):
        """A mod key on OURS, the way a player presses it: /keyboard opens the HUD keyboard (keys page, pointer
        at the centre of the view at that moment), the view is turned so the pointer lands on the keycap (see
        keyboard_layout.py), a left click is a swing that VirtualKeyboard turns into the key press, and /keyboard
        again closes it. Needs the server build with VirtualKeyboard and the mod key bridge (feat/mod-key-bridge)."""
        if ticks != 1:
            raise ValueError("%s: a /keyboard press is one click (ticks must be 1)" % name)
        state = self.ctl("state")
        view = state.get("player") if isinstance(state.get("player"), dict) else state
        yaw0, pitch0 = float(view["yaw"]), float(view["pitch"])
        dyaw, dpitch = keyboard_layout.aim_deltas(*keyboard_layout.cap_aim_point(code))
        self.ctl("command", "keyboard")
        try:
            self.ctl("wait", 4)  # the HUD and the camera lock come up
            self.ctl("look", "%.4f" % (yaw0 + dyaw), "%.4f" % (pitch0 + dpitch))
            self.ctl("wait", 3)  # the pointer follows the look on each HUD frame
            self.ctl("mouse", "left", "click")
            self.ctl("wait", 5)  # the key press reaches the server as a VirtualKeyEvent
        finally:
            self.ctl("command", "keyboard")  # /keyboard toggles: closes the open keyboard and releases the view
            self.ctl("wait", 3)
            self.ctl("look", "%.4f" % yaw0, "%.4f" % pitch0)
        return {"pressed": True, "ticks": 1}

    def motion(self):
        r = self.ctl("state")
        p = r.get("player") if isinstance(r.get("player"), dict) else r
        return {"position": _f3(p.get("position")), "velocity": _f3(p.get("velocity"))}

    def _screen_map(self):
        scr = self._apply_server_ids(self.ctl("screen"))
        slots = {}
        for s in scr.get("slots") or []:
            idx = s.get("slot", s.get("index"))
            cnt = int(s.get("count") or 0)
            slots[idx] = (norm_id(s.get("item")) if cnt else None, cnt)
        carried = scr.get("carried")
        cid = norm_id(carried.get("item")) if isinstance(carried, dict) and carried.get("count") else None
        if cid and not MODDED_NS_RE.match(cid):
            # The cursor stack is a carrier on the client (paper) but the server holds the modded id:
            # take it from /cim inspect inv 'cursor' when the counts agree (same rule as the slots).
            for s in self._server_inv() or []:
                if s["slot"] == "cursor" and s["id"] and MODDED_NS_RE.match(s["id"]) \
                        and s["count"] == int(carried.get("count") or 0):
                    cid = s["id"]
        return slots, cid

    def click_slot(self, index, mode="left", kind="inventory"):
        # horizon-ctl click-slot replies only with ok; changed/carried come from the screen before/after
        before, _ = self._screen_map()
        args = ["click-slot", index] + ([] if mode == "left" else [mode])
        self.ctl(*args)
        self._settle(5)  # same 5-tick read point as TRUTH
        after, carried = self._screen_map()
        return {"carried": carried, "changed": _changed(self.name, kind, before, after)}

    def clear_screen_map(self):
        return self._screen_map()

    def open_inventory(self):
        return {"screen": ours_kind(self.ctl("open-inventory"))}

    def close(self):
        return {"screen": ours_kind(self.ctl("close"))}

    def observe_block(self, pos):
        insp = self.inspect("block", *self._p(pos))
        if insp is not None and "state" in insp:
            st = norm_state(insp["state"])
            return {"state": st, "id": st.split("[", 1)[0]}
        r = self.ctl("block", *self._p(pos))
        st = norm_state(r.get("state") or r.get("block"))
        return {"state": st, "id": st.split("[", 1)[0]}

    def observe_inventory(self):
        raw = self.ctl("inventory")
        self._inv_raw = raw  # client-side reply: the only place durability is reported (see norm_durability)
        srv = self._server_counts()
        carried = raw.get("carried")
        cid = norm_id(carried.get("item")) if isinstance(carried, dict) and carried.get("count") else None
        if srv is not None:
            counts = dict(srv)
            if cid:
                counts[cid] = counts.get(cid, 0) + int(carried.get("count", 0))
            return {"counts": {k: v for k, v in counts.items() if v}, "carried": cid, "selected": None}
        return norm_inventory(raw)

    def _hud_menu(self):
        """'/cim inspect screen' for the player when a menu is open on the server (drawn on the HUD by
        ScreenHud, which horizon-ctl reports as 'playing'), else None."""
        insp = self.inspect("screen", self.player)
        return insp if insp and insp.get("open") else None

    def _screen_reply(self):
        """horizon-ctl screen; when Horizon says 'playing' and the server has a menu open, that menu
        (menu_type, screen name, slots) in the same shape as a truth-ctl screen reply."""
        r = self.ctl("screen")
        if ours_kind(r) != "playing":
            return r
        insp = self._hud_menu()
        if insp is None:
            return r
        return {"screen": insp.get("screen"), "menu_type": insp.get("menu_type"),
                "title": insp.get("title"), "slots": insp.get("slots") or [], "source": "server"}

    def observe_screen(self):
        return norm_screen(self._apply_server_ids(self._screen_reply()), self.name)

    def observe_player(self):
        return norm_player(self.ctl("state"))

    def screenshot(self, path):
        self.ctl("screenshot", path, timeout=60)
        return path

    def wait(self, ticks):
        self.ctl("wait", int(ticks), timeout=max(60, ticks / 20.0 + 30))
        return None

    def tp(self, x, y, z, yaw=0.0, pitch=0.0):
        self.ctl("tp", x, y, z)
        self.ctl("look", yaw, pitch)
        return None


def make_stack(name, lane=None):
    """lane: None (the single-client stand: TruthA / Lane_B / hz slot 3 / port 25599) or a lane dict from
    lanes.py with truth_player, truth_port, ours_player, hz_slot."""
    if name == "truth":
        if lane is None:
            return TruthStack()
        return TruthStack(port=int(lane["truth_port"]), player=lane["truth_player"])
    if name == "ours":
        if lane is None:
            return OursStack()
        return OursStack(hz_n=lane["hz_slot"], player=lane["ours_player"])
    raise SystemExit("unknown stack: %s" % name)
