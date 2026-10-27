#!/usr/bin/env python3
"""Deep CRUSSTY-weave analyzer: diff a woven class dump against the original
class on disk, method by method, and report the exact transformation:
injected prefix bytes, offset-shift delta, switch padding handling, branch
target integrity.

Usage:
  python3 analyze-weave.py <woven.class> <orig.jar> <net.minecraft.path>
  python3 analyze-weave.py /tmp/agent-03-...class srv/versions/26.3/paper-26.3.jar \
      net/minecraft/server/network/ServerHandshakePacketListenerImpl.class
"""
import struct
import sys
import zipfile


class Cf:
    def __init__(self, data):
        self.d = data
        assert data[0:4] == b"\xca\xfe\xba\xbe", "not a class file"
        self.minor, self.major = struct.unpack_from(">HH", data, 4)
        n, off = struct.unpack_from(">H", data, 8), 10
        self.cp = [None]  # 1-based
        i = 1
        count = n[0]
        while i < count:
            tag = data[off]
            if tag == 1:
                ln = struct.unpack_from(">H", data, off + 1)[0]
                self.cp.append(("Utf8", data[off + 3 : off + 3 + ln]))
                off += 3 + ln
            elif tag in (7, 8, 16, 19, 20):
                self.cp.append((tag, struct.unpack_from(">H", data, off + 1)[0]))
                off += 3
            elif tag == 15:
                self.cp.append((tag, data[off + 1 : off + 4]))
                off += 4
            elif tag in (3, 4):
                self.cp.append((tag, data[off + 1 : off + 5]))
                off += 5
            elif tag in (5, 6):
                self.cp.append((tag, data[off + 1 : off + 9]))
                self.cp.append(None)  # long/double take two slots
                off += 9
                i += 1
            elif tag in (9, 10, 11, 12, 17, 18):
                self.cp.append((tag, struct.unpack_from(">HH", data, off + 1)))
                off += 5
            else:
                raise ValueError(f"bad cp tag {tag} at {off}")
            i += 1
        self.cp_count = count
        self.after_cp = off

    def utf8(self, idx):
        e = self.cp[idx]
        return e[1].decode("utf-8", "replace") if e and e[0] == 1 else f"<cp{idx}>"

    def methods(self):
        d = self.d
        off = self.after_cp
        off += 6  # access, this, super
        ifaces = struct.unpack_from(">H", d, off)[0]
        off += 2 + 2 * ifaces
        # fields
        fn = struct.unpack_from(">H", d, off)[0]
        off += 2
        for _ in range(fn):
            off = self._skip_member(off)
        # methods
        mn = struct.unpack_from(">H", d, off)[0]
        off += 2
        out = []
        for _ in range(mn):
            acc, name, desc = struct.unpack_from(">HHH", d, off)
            end = self._skip_member(off, collect=out, name=self.utf8(name), desc=self.utf8(desc))
            off = end
        return out

    def _skip_member(self, off, collect=None, name=None, desc=None):
        d = self.d
        an = struct.unpack_from(">H", d, off + 6)[0]
        p = off + 8
        for _ in range(an):
            aname = self.utf8(struct.unpack_from(">H", d, p)[0])
            alen = struct.unpack_from(">I", d, p + 2)[0]
            if aname == "Code" and collect is not None:
                clen = struct.unpack_from(">I", d, p + 6)[0]
                maxs, maxl = struct.unpack_from(">HH", d, p + 10)
                collect.append(
                    {
                        "name": name,
                        "desc": desc,
                        "max_stack": maxs,
                        "max_locals": maxl,
                        "code": d[p + 14 : p + 14 + clen],
                        "attr_off": p,
                    }
                )
            p += 6 + alen
        return p


# opcode lengths (no wide/tableswitch/lookupswitch)
FIXED = {}
for i in range(0x00, 0x100):
    FIXED[i] = 1
simple = {
    0x10: 2, 0x11: 3, 0x12: 2, 0x13: 3, 0x14: 3,
    0x15: 2, 0x16: 2, 0x17: 2, 0x18: 2, 0x19: 2,
    0x3C: 2, 0x3D: 2,
    0xA9: 2,
}
for op in list(range(0x10, 0x14)) + [0x12, 0x13, 0x14, 0x15, 0x16, 0x17, 0x18, 0x19,
                                     0x3C, 0x3D, 0xA9]:
    FIXED[op] = simple[op]
for op in list(range(0x10, 0x14)) + [0x15, 0x16, 0x17, 0x18, 0x19, 0x3C, 0x3D, 0xA9]:
    FIXED[op] = 2 if op in (0x10, 0x12, 0x15, 0x16, 0x17, 0x18, 0x19, 0x3C, 0x3D, 0xA9) else 3
# bipush/sipush
FIXED[0x10] = 2
FIXED[0x11] = 3
FIXED[0x12] = 2
FIXED[0x13] = 3
FIXED[0x14] = 3
# ldc
FIXED[0x12] = 2
# ldc_w/ldc2_w
FIXED[0x13] = 3
FIXED[0x14] = 3
# local var ops
for op in (0x15, 0x16, 0x17, 0x18, 0x19, 0x3C, 0x3D, 0xA9):
    FIXED[op] = 2
# iinc
FIXED[0x84] = 3
# branch (2-byte)
for op in list(range(0x99, 0xA9)) + [0xA8, 0xC6, 0xC7]:
    FIXED[op] = 3
# 4-byte operands
for op in (0xB8, 0xB7, 0xB6, 0xB2, 0xB3, 0xB4, 0xB5, 0xBB, 0xBD, 0xB9, 0xBA, 0xC0, 0xC1,
           0xC5, 0xC8, 0xC9, 0x84 if False else 0xC8):
    pass
for op in (0x84,):
    FIXED[op] = 3
for op in (0x14,):
    FIXED[op] = 3
for op in (0xC5, 0xC8, 0xC9):
    FIXED[op] = 5
FIXED[0xBA] = 5
FIXED[0xC5] = 5
# multianewarray
FIXED[0xC5] = 4
# invokedynamic
FIXED[0xBA] = 5


def walk(code):
    """Yield (pc, len, op) for every instruction. Returns list."""
    out = []
    i = 0
    n = len(code)
    while i < n:
        op = code[i]
        if op == 0xC4:  # wide
            nop = code[i + 1]
            ln = 6 if nop == 0x84 else 4
            out.append((i, ln, op))
            i += ln
            continue
        if op == 0xAA:  # tableswitch
            pad = (4 - ((i + 1) % 4)) % 4
            base = i + 1 + pad
            default, low, high = struct.unpack_from(">iii", code, base)
            ln = 1 + pad + 12 + (high - low + 1) * 4
            out.append((i, ln, op))
            i += ln
            continue
        if op == 0xAB:  # lookupswitch
            pad = (4 - ((i + 1) % 4)) % 4
            base = i + 1 + pad
            default, npairs = struct.unpack_from(">ii", code, base)
            ln = 1 + pad + 8 + npairs * 8
            out.append((i, ln, op))
            i += ln
            continue
        ln = FIXED.get(op)
        if ln is None:
            # known 3-byte group
            if op in (0x84,):
                ln = 3
            elif 0x99 <= op <= 0xA8 or op in (0xC6, 0xC7):
                ln = 3
            elif op in (0x10, 0x12):
                ln = 2
            elif op in (0x11, 0x13, 0x14):
                ln = 3
            elif op in (0x15, 0x16, 0x17, 0x18, 0x19, 0x3C, 0x3D, 0xA9):
                ln = 2
            elif 0x1A <= op <= 0x2E or 0x51 <= op <= 0x83 or op in (0x00, 0x01, 0x02, 0x03,
                    0x04, 0x05, 0x06, 0x07, 0x08, 0x09, 0x0A, 0x0B, 0x0C, 0x0D, 0x0E, 0x0F,
                    0x2F, 0x30, 0x31, 0x32, 0x33, 0x34, 0x35, 0x36, 0x37, 0x38, 0x39, 0x3A,
                    0x3B, 0x3E, 0x3F, 0x40, 0x41, 0x42, 0x43, 0x44, 0x45, 0x46, 0x47, 0x48,
                    0x49, 0x4A, 0x4B, 0x4C, 0x4D, 0x4E, 0x4F, 0x50, 0x91, 0x92, 0x93, 0x94,
                    0x95, 0x96, 0x97, 0x98, 0xBE, 0xBF, 0xAC, 0xAD, 0xAE, 0xAF, 0xB0, 0xB1):
                ln = 1
            else:
                raise ValueError(f"unknown op {op:#x} at {i}")
        out.append((i, ln, op))
        i += ln
    return out


def check_method(code, label, report):
    """Verify switch padding + report branch target stats."""
    problems = []
    instrs = walk(code)
    n_sw = 0
    for pc, ln, op in instrs:
        if op in (0xAA, 0xAB):
            n_sw += 1
            expect_pad = (4 - ((pc + 1) % 4)) % 4
            actual_pad = 0
            for b in range(3):
                if code[pc + 1 + b] != 0:
                    break
                actual_pad += 1
            # can't always distinguish pad from operand; verify via walk consistency
            if expect_pad != 0 and code[pc + 1] != 0:
                problems.append(f"{label}: switch @ {pc} needs {expect_pad} padding, first byte {code[pc+1]:#x}")
    # branch sanity: every 3-byte branch target must land on an instruction boundary
    starts = {pc for pc, _, _ in instrs}
    bad_branch = 0
    for pc, ln, op in instrs:
        if 0x99 <= op <= 0xA8 or op in (0xC6, 0xC7):
            rel = struct.unpack_from(">h", code, pc + 1)[0]
            tgt = pc + rel
            if tgt not in starts:
                bad_branch += 1
        elif op == 0xA8 or op == 0xC8:  # goto/goto_w
            if op == 0xA8:
                rel = struct.unpack_from(">h", code, pc + 1)[0]
                tgt = pc + rel
                if tgt not in starts:
                    bad_branch += 1
    report.append(f"{label}: instrs={len(instrs)} switches={n_sw} bad_branch_targets={bad_branch}")
    problems and [report.append("  !! " + p) for p in problems]
    return instrs


def first_diff(a, b, limit=24):
    for i in range(min(len(a), len(b))):
        if a[i] != b[i]:
            return i, a[i:i+limit], b[i:i+limit]
    return min(len(a), len(b)), b"same-prefix", b""


def main():
    woven_path, jar_path, entry = sys.argv[1], sys.argv[2], sys.argv[3]
    woven = Cf(open(woven_path, "rb").read())
    with zipfile.ZipFile(jar_path) as z:
        orig = Cf(z.read(entry))

    wm = {m["name"] + m["desc"]: m for m in woven.methods()}
    om = {m["name"] + m["desc"]: m for m in orig.methods()}
    report = []
    for key, ometh in sorted(om.items()):
        wmeth = wm.get(key)
        if not wmeth:
            report.append(f"{key}: MISSING in woven")
            continue
        oc, wcc = ometh["code"], wmeth["code"]
        if oc == wcc:
            continue
        d, a, b = first_diff(oc, wcc)
        report.append(f"=== {key}: orig={len(oc)}B woven={len(wcc)}B delta={len(wcc)-len(oc)}B first_diff@{d}")
        report.append(f"    orig  @ {d}: {a.hex(' ')}")
        report.append(f"    woven @ {d}: {b.hex(' ')}")
        # prefix heuristics
        if d == 0:
            pw = walk(wcc)
            po = walk(oc)
            if pw and pw[0][2] == 0xB8 and po[0][2] != 0xB8:
                plen = pw[0][1]
                report.append(f"    INJECTED PREFIX: invokestatic ({plen}B) at 0; real shift={plen}")
        check_method(wcc, f"woven {key}", report)
        check_method(oc, f"orig  {key}", report)
    print("\n".join(report))


if __name__ == "__main__":
    main()
