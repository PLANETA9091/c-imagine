#!/usr/bin/env python3
"""GALLERY PIXEL PARITY — the honest gate (owner directive 2026-10-30).

Joins two stacks' gallery runs cell-by-cell and diffs the pixels:

  truth stack = real NeoForge 1.21.1 server + real NeoForge client
  cim  stack  = c-imagine (Paper 26.3 + layer + pack) + vanilla 26.3 client

Each stack's /cimscene gallery wrote its own gallery.tsv (wall/col/row ->
absolute x/y/z + block id + exact state string) and one full-frame PNG per
wall (wall_<n>.png, 1280x720). This tool crops the SAME cell index from both
sides (crop geometry from the pinned camera constants), computes the per-cell
absolute pixel error (AE over RGB), and rules:

  GREEN  = 100% of cells AE <= datum. Not "most". 100%.
  RED    = anything else; the failing cells ship as triptych artifacts
           (truth | cim | diff-heat) and a TSV report for the fix backlog.

The vanilla control cells (first 12 blocks in the manifest, ns=minecraft)
define the drift datum: their AE is the cross-version camera/lighting floor
measured on THIS pair of runs. With --calibrate the tool prints the datum
stats and exits 0 without gating (calibration runs only); a gating run takes
--datum <ae> from the calibrated value.

Usage:
  gallery-diff.py TRUTH_TSV TRUTH_SHOTS CIM_TSV CIM_SHOTS OUTDIR
                  [--focal 869] [--cell 108] [--datum 0] [--calibrate]
                  [--max-shift 0] [--top 24]

Camera constants (must match the workflow's client options):
  fov 45 (options.txt "fov:45.0"), 1280x720, vantage 8 blocks from the wall
  plane, eye at wall_mid_height => focal f = 360 / tan(22.5 deg) = 869 px,
  px-per-metre at the wall plane = f / 8 = 108.6 px.
"""

import argparse
import math
import os
import struct
import sys
import zlib


# --- minimal PNG reader/writer (Java-only runners; python3 stdlib only) ----

def read_png(path):
    """Return (w, h, rows) for a truecolor PNG; enough for our own shots."""
    with open(path, "rb") as f:
        data = f.read()
    if data[:8] != b"\x89PNG\r\n\x1a\n":
        raise ValueError(f"{path}: not a PNG")
    pos, w, h, bitd, ctype, idat = 8, 0, 0, 0, 0, b""
    while pos < len(data):
        (ln,) = struct.unpack(">I", data[pos:pos + 4])
        typ = data[pos + 4:pos + 8]
        chunk = data[pos + 8:pos + 8 + ln]
        if typ == b"IHDR":
            w, h, bitd, ctype = struct.unpack(">IIBB", chunk[:10])
        elif typ == b"IDAT":
            idat += chunk
        pos += 12 + ln
    raw = zlib.decompress(idat)
    ch = 3 if ctype == 2 else 4  # truecolor / truecolor+alpha
    stride = w * ch
    rows, prev = [], bytearray(stride)
    i = 0
    for _ in range(h):
        ft = raw[i]; i += 1
        line = bytearray(raw[i:i + stride]); i += stride
        if ft == 1:
            for x in range(ch, stride):
                line[x] = (line[x] + line[x - ch]) & 0xFF
        elif ft == 2:
            for x in range(stride):
                line[x] = (line[x] + prev[x]) & 0xFF
        elif ft == 3:
            for x in range(stride):
                a = line[x - ch] if x >= ch else 0
                line[x] = (line[x] + ((a + prev[x]) >> 1)) & 0xFF
        elif ft == 4:
            for x in range(stride):
                a = line[x - ch] if x >= ch else 0
                b = prev[x]
                c = prev[x - ch] if x >= ch else 0
                p = a + b - c
                pa, pb, pc = abs(p - a), abs(p - b), abs(p - c)
                pr = a if (pa <= pb and pa <= pc) else (b if pb <= pc else c)
                line[x] = (line[x] + pr) & 0xFF
        prev = line
        rows.append(bytes(line))
    return w, h, rows, ch


def write_png(path, w, h, rgb):
    """rgb: flat bytearray of w*h*3."""
    raw = bytearray()
    for y in range(h):
        raw.append(0)
        raw += rgb[y * w * 3:(y + 1) * w * 3]
    comp = zlib.compress(bytes(raw), 6)

    def chunk(typ, payload):
        c = struct.pack(">I", len(payload)) + typ + payload
        return c + struct.pack(">I", zlib.crc32(typ + payload) & 0xFFFFFFFF)

    ihdr = struct.pack(">IIBBBBB", w, h, 8, 2, 0, 0, 0)
    with open(path, "wb") as f:
        f.write(b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", ihdr)
                + chunk(b"IDAT", comp) + chunk(b"IEND", b""))


# --- manifest --------------------------------------------------------------

def read_manifest(path):
    """wall/col/row/x/y/z/ns/id/state -> dict[(wall,col,row)] = row."""
    cells = {}
    with open(path, encoding="utf-8") as f:
        head = f.readline().rstrip("\n").split("\t")
        for line in f:
            vals = line.rstrip("\n").split("\t")
            cells[(int(vals[0]), int(vals[1]), int(vals[2]))] = dict(zip(head, vals))
    return cells


# --- pixel engine ----------------------------------------------------------

def crop(rows, w, ch, cx, cy, size):
    """Centered square crop, clamped to the frame; returns flat RGB."""
    half = size // 2
    out = bytearray(size * size * 3)
    for oy in range(size):
        sy = min(max(cy - half + oy, 0), len(rows) - 1)
        line = rows[sy]
        for ox in range(size):
            sx = min(max(cx - half + ox, 0), w - 1)
            di = (oy * size + ox) * 3
            si = sx * ch
            out[di] = line[si]
            out[di + 1] = line[si + 1]
            out[di + 2] = line[si + 2]
    return out


def ae(a, b):
    return sum(abs(x - y) for x, y in zip(a, b))


def heat(a, b, size):
    """Diff heat triptych row: truth | cim | per-pixel-abs heat."""
    out = bytearray(size * size * 3 * 3)
    o = 0
    for i in range(size * size):
        out[o:o + 3] = a[i * 3:i * 3 + 3]; o += 3
    for i in range(size * size):
        out[o:o + 3] = b[i * 3:i * 3 + 3]; o += 3
    for i in range(size * size):
        d = abs(a[i * 3] - b[i * 3]) + abs(a[i * 3 + 1] - b[i * 3 + 1]) + abs(a[i * 3 + 2] - b[i * 3 + 2])
        v = min(255, d * 2)
        out[o] = v; out[o + 1] = 255 - v; out[o + 2] = 0; o += 3
    return out


# --- main ------------------------------------------------------------------

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("truth_tsv"); ap.add_argument("truth_shots")
    ap.add_argument("cim_tsv"); ap.add_argument("cim_shots")
    ap.add_argument("outdir")
    ap.add_argument("--focal", type=float, default=869.0)
    ap.add_argument("--dist", type=float, default=8.0)
    ap.add_argument("--cell", type=int, default=108)
    ap.add_argument("--datum", type=int, default=0)
    ap.add_argument("--calibrate", action="store_true")
    ap.add_argument("--shift-x", type=int, default=0)
    ap.add_argument("--shift-y", type=int, default=0)
    ap.add_argument("--top", type=int, default=24)
    args = ap.parse_args()

    truth = read_manifest(args.truth_tsv)
    cim = read_manifest(args.cim_tsv)
    os.makedirs(args.outdir, exist_ok=True)

    missing = sorted(set(truth) ^ set(cim))
    common = sorted(set(truth) & set(cim))
    for k in missing[:20]:
        side = "truth" if k in truth else "cim"
        row = truth.get(k) or cim.get(k)
        print(f"MISSING-CELL\t{side}\t{k}\t{row.get('ns')}:{row.get('id')}")

    px = args.focal / args.dist  # px per metre at the wall plane
    size = args.cell

    # per-wall shot cache
    shots = {}

    def load(side_dir, wall):
        key = (side_dir, wall)
        if key not in shots:
            p = os.path.join(side_dir, f"wall_{wall}.png")
            shots[key] = read_png(p)
        return shots[key]

    report = ["cell\twall\tcol\trow\tns\tid\tstate\tAE\tverdict"]
    fails, aes, ctrl_aes = [], [], []
    for k in common:
        wall, col, row = k
        r = truth[k]
        dx = (col - 3) * 2.0              # cols 0..5, stride 2, centred at col 3
        dy = (row + 1.5) - 3.62           # block centre minus eye height
        cx = int(640 + args.focal * dx / args.dist) + args.shift_x
        cy = int(360 - args.focal * dy / args.dist) + args.shift_y
        tw, th, trows, tch = load(args.truth_shots, wall)
        cw, chh, crows, cch = load(args.cim_shots, wall)
        ta = crop(trows, tw, tch, cx, cy, size)
        ca = crop(crows, cw, cch, cx, cy, size)
        err = ae(ta, ca)
        verdict = "GREEN" if err <= args.datum else "RED"
        tag = f"{r['ns']}:{r['id']}"
        report.append(f"{k[0]},{k[1]},{k[2]}\t{wall}\t{col}\t{row}\t{r['ns']}\t{r['id']}\t{r['state']}\t{err}\t{verdict}")
        aes.append(err)
        if r["ns"] == "minecraft":
            ctrl_aes.append(err)
        if verdict == "RED":
            fails.append((err, k, tag, ta, ca, size))

    # artifacts: worst-N triptychs
    fails.sort(key=lambda t: -t[0])
    for err, k, tag, ta, ca, sz in fails[:args.top]:
        write_png(os.path.join(args.outdir, f"cell_{k[0]}_{k[1]}_{k[2]}_{tag.replace(':', '_')}.png"),
                  sz * 3, sz, heat(ta, ca, sz))

    with open(os.path.join(args.outdir, "gallery-diff.tsv"), "w", encoding="utf-8") as f:
        f.write("\n".join(report) + "\n")

    n = len(common)
    nfail = len(fails)
    datum_meas = (min(ctrl_aes), sum(ctrl_aes) // max(len(ctrl_aes), 1), max(ctrl_aes)) if ctrl_aes else (0, 0, 0)
    print(f"cells={n} matched red={nfail} green={n - nfail}")
    print(f"vanilla-control AE (min/avg/max) = {datum_meas[0]}/{datum_meas[1]}/{datum_meas[2]}")
    print(f"gate datum = {args.datum}")
    if args.calibrate:
        print("GALLERY CALIBRATION: measured (no gate) — pass --datum from the control stats once stable")
        return 0
    if missing:
        print(f"GALLERY PARITY: RED — {len(missing)} cells present on one stack only")
        return 1
    if nfail == 0 and n > 0:
        print("GALLERY PARITY: GREEN — 100% of cells within the datum")
        return 0
    print(f"GALLERY PARITY: RED — {nfail}/{n} cells above the datum; triptychs in {args.outdir}")
    return 1


if __name__ == "__main__":
    sys.exit(main())
