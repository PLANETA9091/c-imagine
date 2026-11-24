"""Block-entity observation for the parity runner: `{"observe": "be", "pos": [x, y, z], "fields": [...]}`.

TRUTH (NeoForge client, no direct BE access): sends `/data get block x y z`, waits a few ticks, reads
the newest truth-ctl chat line that names the position and carries a `{...}` compound, and parses that
SNBT into JSON (parse_snbt). OURS: runs `/cim inspect be x y z`; the layer's real block entity
(saveWithFullMetadata, NbtJson on the layer side) comes back as the `nbt` object of a CIM-INSPECT line.

The result is a whitelisted projection, so only what the scenario names is compared:
  {"present": bool, "id": "<be type id>" | None, "fields": {<dotted path>: <normalised value> | None}}
Item stacks ({id, Count|count}) normalise to {id, count}; fluid stacks ({id, Amount|amount}) to
{id, amount}; floats round to 4 places. Ids are the real registry ids on both stacks (the layer runs
the real mods), so no id mapping is applied. Pure Python, no runner state: tests in test_be_observe.py.
"""
import re
import time

DATA_GET_TRIES = 3
DATA_GET_WAIT_TICKS = 4
MESSAGES_TAIL = 200

_NUM_RE = re.compile(r"[+-]?(?:\d+\.\d*|\.\d+|\d+)(?:[eE][+-]?\d+)?[bBsSlLfFdD]?\Z")
_BARE_RE = re.compile(r"[A-Za-z0-9._+\-:]+")
_KEY_RE = re.compile(r"[A-Za-z0-9._+\-]+")


class SnbtError(ValueError):
    pass


class _Parser(object):
    def __init__(self, text):
        self.s = text
        self.i = 0

    def fail(self, what):
        raise SnbtError("SNBT %s at %d in %r" % (what, self.i, self.s[:120]))

    def ws(self):
        while self.i < len(self.s) and self.s[self.i] in " \t\r\n":
            self.i += 1

    def peek(self):
        self.ws()
        return self.s[self.i] if self.i < len(self.s) else ""

    def expect(self, ch):
        if self.peek() != ch:
            self.fail("expected %r" % ch)
        self.i += 1

    def value(self):
        c = self.peek()
        if c == "{":
            return self.compound()
        if c == "[":
            return self.array()
        if c in "\"'":
            return self.quoted()
        return self.token()

    def compound(self):
        self.expect("{")
        out = {}
        if self.peek() == "}":
            self.i += 1
            return out
        while True:
            key = self.quoted() if self.peek() in "\"'" else self.bare_key()
            self.expect(":")
            out[key] = self.value()
            c = self.peek()
            self.i += 1 if c in ",}" else 0
            if c == "}":
                return out
            if c != ",":
                self.fail("expected ',' or '}'")

    def array(self):
        self.expect("[")
        # typed arrays: [B;1b,2b] [I;1,2] [L;3l]
        self.ws()
        if self.i + 1 < len(self.s) and self.s[self.i] in "BIL" and self.s[self.i + 1] == ";":
            self.i += 2
        out = []
        if self.peek() == "]":
            self.i += 1
            return out
        while True:
            out.append(self.value())
            c = self.peek()
            self.i += 1 if c in ",]" else 0
            if c == "]":
                return out
            if c != ",":
                self.fail("expected ',' or ']'")

    def quoted(self):
        q = self.s[self.i]
        self.i += 1
        buf = []
        while self.i < len(self.s):
            c = self.s[self.i]
            if c == "\\" and self.i + 1 < len(self.s):
                buf.append(self.s[self.i + 1])
                self.i += 2
                continue
            if c == q:
                self.i += 1
                return "".join(buf)
            buf.append(c)
            self.i += 1
        self.fail("unterminated string")

    def bare_key(self):
        m = _KEY_RE.match(self.s, self.i)
        if not m:
            self.fail("expected a key")
        self.i = m.end()
        return m.group(0)

    def token(self):
        self.ws()
        m = _BARE_RE.match(self.s, self.i)
        if not m:
            self.fail("expected a value")
        self.i = m.end()
        tok = m.group(0)
        if _NUM_RE.match(tok):
            body = tok.rstrip("bBsSlLfFdD") if tok[-1:] in "bBsSlLfFdD" else tok
            if tok[-1:] in "fFdD" or "." in body or "e" in body or "E" in body:
                return float(body)
            return int(body)
        if tok == "true":
            return 1
        if tok == "false":
            return 0
        return tok


def parse_snbt(text):
    """SNBT text (a compound or any value) to plain Python: dict, list, str, int, float.
    Suffixes b/s/l/f/d are dropped (a `1b` boolean reads as 1). Raises SnbtError on bad input."""
    p = _Parser(text.strip())
    value = p.value()
    if p.peek() != "":
        p.fail("trailing text")
    return value


_COORD_FORMS = ("%d, %d, %d", "%d %d %d", "%d,%d,%d")


def find_be_snbt(lines, pos):
    """The SNBT compound of the newest chat line that names `pos`, or None.
    `/data get block` answers "Block at x, y, z has the following block data: {...}" (wording varies
    by version, so the match is the coordinates plus the first brace)."""
    forms = [f % tuple(int(v) for v in pos) for f in _COORD_FORMS]
    for line in reversed(lines or []):
        if not isinstance(line, str) or not any(f in line for f in forms):
            continue
        brace = line.find("{")
        if brace < 0:
            continue
        return line[brace:]
    return None


def _round(v):
    return round(v, 4) if isinstance(v, float) else v


def normalise(v):
    """Canonical value: item stacks to {id,count}, fluid stacks to {id,amount}, floats to 4 places."""
    if isinstance(v, dict):
        if "id" in v and ("Count" in v or "count" in v):
            out = {"id": v["id"], "count": v.get("Count", v.get("count"))}
            if "Slot" in v:
                out["slot"] = v["Slot"]
            return out
        if "id" in v and ("Amount" in v or "amount" in v):
            return {"id": v["id"], "amount": v.get("Amount", v.get("amount"))}
        return {k: normalise(v[k]) for k in sorted(v)}
    if isinstance(v, list):
        return [normalise(x) for x in v]
    return _round(v)


def _get(nbt, path):
    cur = nbt
    for part in path.split("."):
        if isinstance(cur, dict) and part in cur:
            cur = cur[part]
        elif isinstance(cur, list) and part.isdigit() and int(part) < len(cur):
            cur = cur[int(part)]
        else:
            return None
    return cur


def project(nbt, fields):
    """The whitelisted view of a BE's NBT: {path: normalised value} for each dotted path in `fields`.
    ["*"] is discovery: the whole normalised NBT (use it to find the field names; never a verdict)."""
    if not isinstance(nbt, dict):
        raise SnbtError("block entity NBT is not a compound: %r" % (nbt,))
    if list(fields) == ["*"]:
        return {"*": normalise(nbt)}
    return {f: normalise(_get(nbt, f)) for f in fields}


def result_of(nbt, fields):
    if nbt is None:
        return {"present": False, "id": None, "fields": {}}
    return {"present": True, "id": nbt.get("id"), "fields": project(nbt, fields)}


def _truth_nbt(stack, pos, sleep=time.sleep):
    x, y, z = (int(v) for v in pos)
    for _ in range(DATA_GET_TRIES):
        stack.call("command", ["data", "get", "block", str(x), str(y), str(z)])
        stack.call("wait", [DATA_GET_WAIT_TICKS], timeout=60)
        reply = stack.call("messages", [MESSAGES_TAIL])
        text = find_be_snbt(reply.get("lines") or [], (x, y, z))
        if text is not None:
            return parse_snbt(text)
        sleep(0.1)
    return None


def _ours_nbt(stack, pos):
    x, y, z = (int(v) for v in pos)
    obj = stack.inspect("be", x, y, z)
    if obj is None:
        raise RuntimeError("ours: /cim inspect be returned no CIM-INSPECT line (is the plugin current?)")
    return obj.get("nbt")


def observe(stack, step):
    """Run one `be` observe on a stack ('truth' or 'ours'); returns the normalised result dict."""
    pos = step["pos"]
    fields = list(step.get("fields") or [])
    if stack.name == "truth":
        nbt = _truth_nbt(stack, pos)
    elif stack.name == "ours":
        nbt = _ours_nbt(stack, pos)
    else:
        raise ValueError("be observe: unknown stack %r" % stack.name)
    return result_of(nbt, fields)
