#!/usr/bin/env python3
"""Cross-stack registration-parity diff (M5.5, mod-parity-harness.md §4).

Joins the two cimscene census TSVs (per-registry per-namespace counts) and
asserts the stacks registered IDENTICAL sets. Any missing row or count delta
is RED — the table names the exact (registry, namespace) rows so the fix loop
knows where to dig.

When the entry-level listings (scene-manifest-entries.tsv) are also supplied,
the join goes to EXACT set equality per registry: per-namespace counts can
match while the underlying id sets differ (one block missing, one extra
sound) — counts alone are a weak gate, sets don't lie.

Usage:
    census-diff.py <truth.tsv> <layer.tsv> [truth-entries.tsv layer-entries.tsv]
"""
import sys

MAX_ROWS = 200  # bound the printed diff; the full lists live in the artifacts


def load_counts(path, label):
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


def load_entries(path, label):
    """(registry, namespace, id) -> True; keyed by registry -> set of ns:id."""
    by_registry = {}
    with open(path, encoding="utf-8") as f:
        header = f.readline().rstrip("\n")
        if header.split("\t") != ["registry", "namespace", "id"]:
            sys.exit(f"{label}: unexpected entries header {header!r}")
        for ln, line in enumerate(f, start=2):
            line = line.rstrip("\n")
            if not line:
                continue
            parts = line.split("\t")
            if len(parts) != 3:
                sys.exit(f"{label}:{ln}: bad entry row {line!r}")
            reg, ns, ident = parts
            by_registry.setdefault(reg, set()).add(f"{ns}:{ident}")
    return by_registry


def main():
    if len(sys.argv) not in (3, 5):
        sys.exit("usage: census-diff.py <truth.tsv> <layer.tsv> "
                 "[truth-entries.tsv layer-entries.tsv]")
    truth = load_counts(sys.argv[1], "truth")
    layer = load_counts(sys.argv[2], "layer")

    t_entries = l_entries = None
    if len(sys.argv) == 5:
        t_entries = load_entries(sys.argv[3], "truth-entries")
        l_entries = load_entries(sys.argv[4], "layer-entries")

    red = False
    keys = sorted(set(truth) | set(layer))
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

    if t_entries is not None:
        regs = sorted(set(t_entries) | set(l_entries))
        print(f"--- exact set join over {len(regs)} registry/ies ---")
        for reg in regs:
            tset = t_entries.get(reg, set())
            lset = l_entries.get(reg, set())
            only_t = sorted(tset - lset)
            only_l = sorted(lset - tset)
            if not only_t and not only_l:
                print(f"SET-OK\t{reg}\t{len(tset)} ids")
                continue
            red = True
            print(f"SET-DELTA\t{reg}\tmissing-in-layer={len(only_t)} "
                  f"missing-in-truth={len(only_l)}")
            for ident in only_t[:MAX_ROWS]:
                print(f"  ONLY-IN-TRUTH\t{reg}\t{ident}")
            if len(only_t) > MAX_ROWS:
                print(f"  ... +{len(only_t) - MAX_ROWS} more only-in-truth")
            for ident in only_l[:MAX_ROWS]:
                print(f"  ONLY-IN-LAYER\t{reg}\t{ident}")
            if len(only_l) > MAX_ROWS:
                print(f"  ... +{len(only_l) - MAX_ROWS} more only-in-layer")

    if red:
        print("CENSUS PARITY: RED — the stacks do not register identical sets")
        sys.exit(1)
    print("CENSUS PARITY: GREEN — registration parity across stacks")


if __name__ == "__main__":
    main()
