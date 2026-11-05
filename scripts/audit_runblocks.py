#!/usr/bin/env python3
"""Audit every `run: |` block in every workflow with bash -n (dedented)."""
import glob, re, subprocess

bad = 0
for wf in sorted(glob.glob("/home/z/my-project/work/c-imagine/.github/workflows/*.yml")):
    text = open(wf, encoding="utf-8").read()
    lines = text.splitlines()
    i = 0
    while i < len(lines):
        m = re.match(r"^(\s*)run: \|\s*$", lines[i])
        if not m:
            i += 1
            continue
        indent = len(m.group(1))
        # block scalar content = following lines indented > indent (or blank)
        j = i + 1
        blk = []
        blk_start = j + 1  # 1-based first content line
        while j < len(lines):
            ln = lines[j]
            if ln.strip() == "":
                blk.append("\n")
                j += 1
                continue
            cur = len(ln) - len(ln.lstrip(" "))
            if cur <= indent:
                break
            blk.append(ln)
            j += 1
        # dedent by the block scalar's own indent (first content line's indent)
        first = next(l for l in blk if l.strip())
        base = len(first) - len(first.lstrip(" "))
        ded = []
        for ln in blk:
            if ln.strip() == "":
                ded.append("\n")
            elif ln.startswith(" " * base):
                ded.append(ln[base:] + "\n")
            else:
                ded.append(ln + "\n")  # should not happen in a valid scalar
        open("/tmp/audit_block.sh", "w", encoding="utf-8").write("".join(ded))
        r = subprocess.run(["bash", "-n", "/tmp/audit_block.sh"], capture_output=True, text=True)
        name_line = next((lines[k] for k in range(i, max(0, i - 8), -1) if "name:" in lines[k]), "?")
        if r.returncode != 0:
            bad += 1
            print(f"FAIL {wf.split('/')[-1]}:{blk_start} ({name_line.strip()[:60]})")
            print("   " + r.stderr.strip().replace("\n", "\n   "))
        else:
            print(f"ok   {wf.split('/')[-1]}:{blk_start} ({name_line.strip()[:60]})")
        i = j
print("=== FAILURES:", bad)
