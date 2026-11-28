#!/usr/bin/env python3
"""The registry-census selftest: a synthetic scenes dir + entries manifest,
the measured gap must be exact (the counts are the contract, never invented).
"""
import json
import os
import subprocess
import sys
import tempfile

root = tempfile.mkdtemp(prefix="rc-test-")
scenes = os.path.join(root, "scenes")
os.makedirs(scenes, exist_ok=True)

# the registered census: block/ae2 = charger + io_port; item/ae2 = compass;
# sound/create = wrench_rotate. minecraft (registered, never exercised) rides.
with open(os.path.join(root, "entries.tsv"), "w") as fh:
    fh.write("registry\tnamespace\tid\n")
    fh.write("block\tae2\tcharger\n")
    fh.write("block\tae2\tio_port\n")
    fh.write("item\tae2\tcompass\n")
    fh.write("sound\tcreate\twrench_rotate\n")
    fh.write("block\tminecraft\tstone\n")

def page(name, header, rows):
    with open(os.path.join(scenes, name), "w") as fh:
        fh.write(header + "\n")
        for r in rows:
            fh.write(r + "\n")

# the block lane: charger exercised (one state-qualified row), io_port absent
page("gallery-ae2-p1.tsv", "cell\tx\ty\tz\tstate\tdyn\tlight", [
    "cell\t-5\t-60\t9\tae2:charger[facing=north,spin=1]\t1\t0",
    "cell\t-4\t-60\t9\tae2:charger[facing=north,spin=2]\t1\t0",
])
# an iso page replays the same states — must NOT double-count
page("gallery-blockiso-ae2-p1.tsv", "cell\tx\ty\tz\tstate\tdyn\tlight", [
    "cell\t-5\t-60\t9\tae2:charger[facing=north,spin=1]\t1\t0",
])
# the item lane: compass exercised
page("gallery-item-ae2-p1.tsv", "cell\tx\ty\tz\titem\tdyn\tnote", [
    "cell\t-5\t-60\t9\tae2:compass\t0\tok",
])
# the sound lane: wrench_rotate exercised, one skip row = an absence
page("gallery-sound-create-p1.tsv", "cell\tx\ty\tz\tsound\tnote", [
    "cell\t-5\t-60\t9\tcreate:wrench_rotate\tok",
    "skip\t-1\t-1\t-1\tcreate:cardboard_bonk\twrong-series:dyn",
])
# a class with no id column (biome) must be skipped, not counted
page("gallery-biome-ae2-p1.tsv", "cell\tx\ty\tz\tbiome\tdyn\tnote", [
    "cell\t-5\t-60\t9\tminecraft:plains\t0\tok",
])

out = subprocess.run(
    [sys.executable,
     os.path.join(os.path.dirname(__file__), "registry-census.py"),
     "--scenes", scenes, "--entries", os.path.join(root, "entries.tsv"),
     "--json", os.path.join(root, "rc.json")],
    capture_output=True, text=True)
if out.returncode != 0:
    print(out.stdout); print(out.stderr)
    sys.exit("registry-census selftest: the tool failed")

rc = json.load(open(os.path.join(root, "rc.json")))
failures = []

def expect(cond, msg):
    if not cond:
        failures.append(msg)

b = rc["registries"].get("block", {}).get("ae2", {})
expect(b.get("registered") == 2, f"block/ae2 registered=2, got {b.get('registered')}")
expect(b.get("exercised") == 1, f"block/ae2 exercised=1 (the iso lane must not double-count), got {b.get('exercised')}")
expect(b.get("unexercised_count") == 1, f"block/ae2 unexercised=1, got {b.get('unexercised_count')}")
expect(b.get("unexercised") == ["io_port"], f"block/ae2 unexercised=[io_port], got {b.get('unexercised')}")
i = rc["registries"].get("item", {}).get("ae2", {})
expect(i.get("exercised") == 1, f"item/ae2 exercised=1, got {i.get('exercised')}")
s = rc["registries"].get("sound", {}).get("create", {})
expect(s.get("exercised") == 1, f"sound/create exercised=1 (the skip row is an absence), got {s.get('exercised')}")
expect(s.get("reverse_count") == 0, f"sound/create reverse=0, got {s.get('reverse_count')}")
expect("biome" not in rc["registries"], "biome must never join the census (no registry lane)")
t = rc["totals"]
expect(t.get("registered") == 5, f"totals registered=5, got {t.get('registered')}")
expect(t.get("exercised") == 3, f"totals exercised=3, got {t.get('exercised')}")
gaps = rc["integrity"].get("named_gaps")
expect(gaps == ["menu", "block_entity"], f"named gaps receipted, got {gaps}")

if failures:
    for f in failures:
        print("  fail:", f)
    sys.exit(f"registry-census selftest: FAILED ({len(failures)})")
print("registry-census selftest: ALL OK")
