#!/usr/bin/env python3
"""Patch gallery.yml: extend both legs' settle loops with the 14-dz light
poke + re-verify (a stable dark frame passes the two-identical law; the
poke re-sends the light packets and the re-verify demands the SAME frame
again — a starved client integrates on the re-send and fails the check)."""
import re
import sys

P = "/home/z/my-project/work/c-imagine/.github/workflows/gallery.yml"
src = open(P).read()


def patch_leg(src, shots):
    """shots = the leg's shot dir prefix ('truth-shots' or 'ours-shots'),
    plus the file prefix for the final shot line."""
    old = (
        "              PREV=\"\"\n"
        "              for i in $(seq 1 8); do\n"
        f"                rcon \"tp cimviewer 0.5 -60 0.5 0 0\" >/dev/null 2>&1\n"
        f"                bash scripts/visual/shot.sh \"{shots}/.settle-$NS-p$p.png\"\n"
        f"                CUR=$(md5sum \"{shots}/.settle-$NS-p$p.png\" | cut -d' ' -f1)\n"
        "                if [ -n \"$PREV\" ] && [ \"$CUR\" = \"$PREV\" ]; then break; fi\n"
        "                PREV=\"$CUR\"\n"
        "              done\n"
        f"              rm -f \"{shots}/.settle-$NS-p$p.png\"\n"
    )
    new = (
        "              PREV=\"\"\n"
        "              SETTLE_ROUNDS=0\n"
        "              while [ \"$SETTLE_ROUNDS\" -lt 3 ]; do\n"
        "                SETTLE_ROUNDS=$((SETTLE_ROUNDS+1))\n"
        "                for i in $(seq 1 8); do\n"
        "                  rcon \"tp cimviewer 0.5 -60 0.5 0 0\" >/dev/null 2>&1\n"
        f"                  bash scripts/visual/shot.sh \"{shots}/.settle-$NS-p$p.png\"\n"
        f"                  CUR=$(md5sum \"{shots}/.settle-$NS-p$p.png\" | cut -d' ' -f1)\n"
        "                  if [ -n \"$PREV\" ] && [ \"$CUR\" = \"$PREV\" ]; then break; fi\n"
        "                  PREV=\"$CUR\"\n"
        "                done\n"
        "                # 14-dz LIGHT POKE + RE-VERIFY: a stable frame can be a\n"
        "                # STARVED one — the light packets never integrated and the\n"
        "                # dark frame is as stable as a lit one, so the\n"
        "                # two-identical law cannot tell (run 43468177851 measured\n"
        "                # 86% of shard 5's red rows as exactly this class). The\n"
        "                # poke re-sends the page chunks' light packets (cimscene\n"
        "                # lightpoke, the vanilla constructor path) and the re-verify\n"
        "                # demands the SAME frame again: already-lit = idempotent\n"
        "                # packet = identical frame (accept); starved = the re-send\n"
        "                # integrates light = the frame CHANGES (the fake settle is\n"
        "                # rejected and the loop re-runs on the now-live data).\n"
        "                rcon \"cimscene lightpoke 0 0\" >/dev/null 2>&1\n"
        f"                bash scripts/visual/shot.sh \"{shots}/.settle-post-$NS-p$p.png\"\n"
        f"                POST=$(md5sum \"{shots}/.settle-post-$NS-p$p.png\" | cut -d' ' -f1)\n"
        f"                rm -f \"{shots}/.settle-post-$NS-p$p.png\"\n"
        "                if [ \"$POST\" = \"$CUR\" ]; then break; fi\n"
        "                PREV=\"$POST\"\n"
        "              done\n"
        f"              rm -f \"{shots}/.settle-$NS-p$p.png\"\n"
    )
    n = src.count(old)
    assert n == 1, f"anchor count for {shots}: {n}"
    return src.replace(old, new)


src = patch_leg(src, "truth-shots")
src = patch_leg(src, "ours-shots")
open(P, "w").write(src)
print("patched both legs OK")
