#!/usr/bin/env bash
# Download window: run BOTH client runtime fetches concurrently; the sandbox
# kills every process at command end, so this is run in repeated windows until
# both report "fetched/verified" complete. Both scripts skip existing objects
# (size-checked), so windows are resumable by construction.
set -uo pipefail
ROOT=/home/z/my-project/work/c-imagine
G=/tmp/my-project/artifacts/gal
JDK=/tmp/my-project/artifacts/jdk25/bin
mkdir -p "$G"

python3 "$ROOT/scripts/truth/fetch-truth-client.py" \
  /tmp/my-project/artifacts/clients/nfclient "$G/truth-client" --assets \
  > "$G/fetch-truth.log" 2>&1 &
P1=$!
python3 "$ROOT/scripts/fetch-visual-client.py" 26.3 "$G/visual-client" --assets \
  > "$G/fetch-visual.log" 2>&1 &
P2=$!

# hard window: leave 30s of the 600s budget for reporting
( sleep 550; kill $P1 $P2 2>/dev/null ) &
WATCH=$!
wait $P1; R1=$?
wait $P2; R2=$?
kill $WATCH 2>/dev/null

echo "=== truth rc=$R1 ==="
tail -3 "$G/fetch-truth.log"
echo "=== visual rc=$R2 ==="
tail -3 "$G/fetch-visual.log"
echo "=== sizes ==="
du -sh "$G/truth-client" "$G/visual-client" 2>/dev/null
