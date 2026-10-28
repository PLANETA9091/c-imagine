#!/usr/bin/env python3
"""Cross-stack registration-parity diff (M5.5, mod-parity-harness.md §4).

Joins the two cimscene census TSVs (per-registry per-namespace counts) and
asserts the stacks registered IDENTICAL sets. Any missing row or count delta
is RED — the table names the exact (registry, namespace) rows so the fix loop
knows where to dig.

Usage: census-diff.py <truth.tsv> <layer.tsv>
"""
import sys


def load(path, label):
    d = {}
    with open(path, encoding="utf-8") as f:
        header = f.readline().rstrip("\n")
        if header.split("\t") != ["registry", "namespace", "count"]:
            sys.exit(f"{label}: unexpected header {header!r}")
        for ln, line in enumerate(f, start=2):
            line = line.rstrip("\n")
            if not line:
                continue
            parts = line.split("\t")
            if len(parts) != 3:
                sys.exit(f"{label}:{ln}: bad row {line!r}")
            reg, ns, cnt = parts
            try:
                n = int(cnt)
            except ValueError:
                sys.exit(f"{label}:{ln}: bad count {cnt!r}")
            key = (reg, ns)
            if key in d:
                sys.exit(f"{label}:{ln}: duplicate row {key}")
            d[key] = n
    return d


def main():
    if len(sys.argv) != 3:
        sys.exit("usage: census-diff.py <truth.tsv> <layer.tsv>")
    truth = load(sys.argv[1], "truth")
    layer = load(sys.argv[2], "layer")

    keys = sorted(set(truth) | set(layer))
    red = False
    print(f"truth rows={len(truth)} layer rows={len(layer)}")
    for k in keys:
        t, l = truth.get(k), layer.get(k)
        if t is None:
            print(f"MISSING-IN-TRUTH\t{k[0]}\t{k[1]}\tlayer={l}")
            red = True
        elif l is None:
            print(f"MISSING-IN-LAYER\t{k[0]}\t{k[1]}\ttruth={t}")
            red = True
        elif t != l:
            print(f"DELTA\t{k[0]}\t{k[1]}\ttruth={t}\tlayer={l}")
            red = True
        else:
            print(f"OK\t{k[0]}\t{k[1]}\tcount={t}")

    if red:
        print("CENSUS PARITY: RED — the stacks do not register identical sets")
        sys.exit(1)
    print("CENSUS PARITY: GREEN — registration parity across stacks")


if __name__ == "__main__":
    main()
