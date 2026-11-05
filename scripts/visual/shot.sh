#!/bin/bash
# Capture to "$1" through the warm ShotD; falls back to a cold Shot if it stalls.
OUT=$(realpath -m "$1")
FIFO=/tmp/cim-shot-$DISPLAY.fifo
[ -p "$FIFO" ] || mkfifo "$FIFO"
if ! pgrep -f "ShotD $FIFO" >/dev/null; then
  setsid java -cp vtools ShotD "$FIFO" </dev/null >/tmp/cim-shotd.log 2>&1 &
fi
rm -f "$OUT.ok"
timeout 5 bash -c 'echo "$1" > "$2"' _ "$OUT" "$FIFO" || exec java -cp vtools Shot "$1"
for _ in $(seq 1 500); do
  if [ -e "$OUT.ok" ]; then rm -f "$OUT.ok"; echo "shot: $OUT"; exit 0; fi
  sleep 0.01
done
exec java -cp vtools Shot "$1"
