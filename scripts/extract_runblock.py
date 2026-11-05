#!/usr/bin/env python3
"""Extract a `run: |` block from a workflow YAML and bash -n it."""
import re, subprocess, sys

wf = sys.argv[1] if len(sys.argv) > 1 else "/home/z/my-project/work/c-imagine/.github/workflows/gallery.yml"
start = int(sys.argv[2]) if len(sys.argv) > 2 else 1519   # 1-based, first line of block content
end = int(sys.argv[3]) if len(sys.argv) > 3 else 1803     # 1-based, last line of block content

lines = open(wf, encoding="utf-8").read().splitlines(keepends=True)
block = "".join(lines[start - 1 : end])
# dedent the block-scalar indent (10 spaces) so heredoc terminators sit at col 0
ded = []
for ln in block.splitlines(keepends=True):
    if ln.startswith(" " * 10):
        ln = ln[10:]
    elif ln.strip() == "":
        ln = "\n"
    ded.append(ln)
block = "".join(ded)
open("/tmp/runblock.sh", "w", encoding="utf-8").write(block)
r = subprocess.run(["bash", "-n", "/tmp/runblock.sh"], capture_output=True, text=True)
print("bash -n rc:", r.returncode)
if r.stderr:
    print(r.stderr)
