#!/usr/bin/env python3
"""Aggregate gallery-report-N join JSONs: per-ns green share + red mass by state."""
import glob, json, collections, sys, re

BASE = sys.argv[1] if len(sys.argv) > 1 else "/tmp/r110"
per_ns = collections.defaultdict(lambda: collections.Counter())
red_states = collections.Counter()
red_ae = collections.Counter()
dyn_pages = collections.Counter()
weird = []

for d in sorted(glob.glob(f"{BASE}/gallery-report-*")):
    for f in glob.glob(f"{d}/join-*.json"):
        name = f.split("/")[-1][len("join-"):-len(".json")]
        # join-<ns>-p<N> | join-e<...> | join-item-<ns>-pN | join-fluid-<ns>-pN
        m = re.match(r"(item-|fluid-)?([a-z_]+)-p(\d+)$", name)
        if m:
            ns = m.group(2)
            kind = (m.group(1) or "") + "blocks"
        elif name.startswith("e-") or name == "e0" or name.startswith("e-"):
            ns = name.split("-")[1] if name.startswith("e-") and len(name.split("-")) > 1 else "entity"
            kind = "entity"
        else:
            ns, kind = name, "other"
        try:
            j = json.load(open(f))
        except Exception as e:
            weird.append((f, str(e)))
            continue
        c = per_ns[(ns, kind)]
        c["cells"] += j.get("cells", 0)
        c["red"] += j.get("red", 0)
        c["dynamic"] += j.get("dynamic", 0)
        c["spill"] += j.get("spill", 0)
        for row in j.get("rows", []):
            v = row.get("verdict")
            if v == "RED":
                st = row.get("state", "?")
                red_states[st] += 1
                red_ae[st] += row.get("ae", 0)

print(f"{'ns/kind':40s} {'cells':>6s} {'red':>6s} {'dyn':>6s} {'spill':>6s} {'green%':>7s}")
tot = collections.Counter()
for (ns, kind), c in sorted(per_ns.items()):
    cells = c["cells"]
    green = cells - c["red"] - c["dynamic"] - c["spill"]
    g = 100.0 * green / cells if cells else 0
    print(f"{ns+'/'+kind:40s} {cells:6d} {c['red']:6d} {c['dynamic']:6d} {c['spill']:6d} {g:6.2f}%")
    for k in ("cells", "red", "dynamic", "spill"):
        tot[k] += c[k]
g = 100.0 * (tot["cells"] - tot["red"] - tot["dynamic"] - tot["spill"]) / tot["cells"] if tot["cells"] else 0
print(f"{'TOTAL':40s} {tot['cells']:6d} {tot['red']:6d} {tot['dynamic']:6d} {tot['spill']:6d} {g:6.2f}%")
print()
print("TOP-25 RED STATES (cell count, total AE):")
for st, n in red_states.most_common(25):
    print(f"  {n:4d}  ae={red_ae[st]:>9d}  {st[:90]}")
print("distinct red states:", len(red_states))
if weird:
    print("WEIRD:", weird[:5])
