#!/bin/bash
# The 496-window landing receipt (one command): pull the wall-ours-meta
# shards from the landed gallery run, run the diag_diff falsification
# contract per shard, and print the anim census. Run AFTER the run's
# gallery ours s0..s7 jobs are all completed.
# Usage: read_landing_496.sh <run-id>
set -uo pipefail
RUN="${1:?usage: read_landing_496.sh <run-id>}"
REPO="$(git rev-parse --show-toplevel 2>/dev/null || echo /home/z/my-project/work/c-imagine)"
cd "$REPO"
OUT="ci-artifacts/ours-meta-$RUN"
python3 scripts/fetch_run_meta.py "$RUN" "wall-ours-meta" "$OUT" || exit 1

echo "=== the frame contract + the anim law per shard ==="
pass=0; fail=0
for d in "$OUT"/wall-ours-meta-*/; do
    shard=$(basename "$d")
    log=$(find "$d" -name capture-diag.log | head -1)
    if [ -z "$log" ]; then
        echo "$shard: NO capture-diag.log"
        fail=$((fail+1)); continue
    fi
    echo "--- $shard ($(wc -l < "$log") diag lines)"
    if python3 scripts/diag_diff.py "$log"; then
        pass=$((pass+1))
    else
        fail=$((fail+1))
    fi
done
echo "=== shards PASS=$pass FAIL=$fail"

echo "=== the door-diag line census (the e56-style totals) ==="
cat "$OUT"/wall-ours-meta-*/srv-wall/c-imagine/scenes/capture-diag.log 2>/dev/null \
    | grep -c "door-BER\|door-head" || true

echo "=== the frame-generation cross-check (the fresh seed vs the tracked frame) ==="
if [ ! -d "ci-artifacts/seed-captured-meshes-496" ]; then
    python3 scripts/fetch_run_meta.py "$RUN" "seed-captured-meshes" ci-artifacts/seed-captured-meshes-496 >/dev/null 2>&1
fi
if [ -d "ci-artifacts/seed-captured-meshes-496/seed-captured-meshes" ]; then
    python3 scripts/diag_expect.py \
        "ci-artifacts/seed-captured-meshes-496/seed-captured-meshes/srv-wall/c-imagine/scenes" \
        /tmp/e57-expect-496.txt | head -3
    if diff -q ci-artifacts/e57-expect.txt /tmp/e57-expect-496.txt >/dev/null 2>&1; then
        echo "FRAME GENERATION STABLE: the fresh seed's frame == the tracked frame"
    else
        echo "FRAME GENERATION SHIFTED: rerun diag_diff with /tmp/e57-expect-496.txt as the expect file"
    fi
else
    echo "seed artifact unavailable — the tracked frame stands unverified for this run"
fi
