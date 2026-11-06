#!/usr/bin/env bash
# Opens every baked screen on a real client and clicks its first slot with the real
# mouse. Same environment as click-test.sh, plus SRV (server folder).
set -uo pipefail
$RC 'cim hudlist' >/dev/null; sleep 1
total=0; good=0; noslot=0
while IFS=$'\t' read -r name slot widgets; do
  total=$((total+1))
  # Each aim turns the look. Left to drift it reaches +-90 pitch, where the pointer stalls.
  $RC "tp cimviewer 0.5 -60 0.5 0 0" >/dev/null; sleep 0.4
  $RC "cim hudopen $name" >/dev/null; sleep 0.5
  if [ "$slot" -lt 0 ]; then noslot=$((noslot+1)); echo "noslot $name"; $RC 'cim guiclose' >/dev/null; continue; fi
  m=$(($(wc -l < "$LOG") + 1))
  $RC "cim hudaim s$slot" >/dev/null; sleep 0.7
  $MOUSE left; sleep 0.5
  if tail -n +"$m" "$LOG" | grep -q "click slot=$slot .* in $name"; then good=$((good+1)); else echo "FAIL $name slot $slot"; fi
  [ "$((total % 12))" = 1 ] && $SHOT "$OUT/sweep-$total.png"
  $RC 'cim guiclose' >/dev/null; sleep 0.2
done < "$SRV/c-imagine/hud-screens.txt"
echo "screen sweep: $good of $((total-noslot)) screens took a real click on their slot, $noslot have no slots, $total total"
[ "$good" = "$((total-noslot))" ]
