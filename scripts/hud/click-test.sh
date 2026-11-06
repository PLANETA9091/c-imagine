#!/usr/bin/env bash
# Real-client HUD test: real mouse input on a vanilla 26.3 client, verdicts from what
# the server saw. Needs a joined cimviewer, -Dcim.hud.debug=true, guiScale 3 at 1280x720.
# Env: RC (rcon command), MOUSE (java Mouse command), LOG (server log), SHOT (screenshot
# command taking a path), OUT (directory), CARRIER (float id of the opener item).
set -uo pipefail
bad=0
pass() { echo "ok   $1"; }
fail() { echo "FAIL $1"; bad=$((bad+1)); }
seen() { tail -n +"$MARK" "$LOG" | grep -q "$1"; }
mark() { MARK=$(($(wc -l < "$LOG") + 1)); }
expect() { sleep "${3:-1}"; if seen "$1"; then pass "$2"; else fail "$2 (no: $1)"; fi; }
count() { $RC "clear cimviewer $1 0" | tail -1 | grep -oE 'Found [0-9]+' | grep -oE '[0-9]+' || echo 0; }
aim() { $RC "cim hudaim $1" | tail -1; sleep 0.8; }

$RC 'op cimviewer' 'clear cimviewer' 'time set noon' 'tp cimviewer 0.5 -60 0.5 0 0' \
    'item replace entity cimviewer hotbar.1 with minecraft:diamond 5' >/dev/null
$RC "item replace entity cimviewer weapon.mainhand with minecraft:paper[minecraft:custom_model_data={floats:[${CARRIER}f]}]" >/dev/null
sleep 1.5

mark; $MOUSE right
expect 'opened' 'right-clicking the item opens its screen' 2
$SHOT "$OUT/1-open.png"

[ "$(count minecraft:diamond)" = "5" ] && pass 'the test holds 5 diamonds' || fail 'the test holds 5 diamonds'
mark; A=$(aim s28); echo "     $A"; $MOUSE left
expect 'left click slot=28' 'left click lands on the aimed player slot'
mark; aim s36 >/dev/null; $MOUSE left
expect 'left click slot=36' 'the stack is placed in a menu slot'
[ "$(count minecraft:diamond)" = "0" ] && pass 'the stack left the inventory' || fail 'the stack left the inventory'
$SHOT "$OUT/2-placed.png"

mark; aim 2,2 >/dev/null; $MOUSE left
expect 'click slot=-1' 'a click on bare panel hits nothing'

mark; aim s36 >/dev/null; $MOUSE right
expect 'right click slot=36' 'right click reaches the screen, once' 
[ "$(tail -n +"$MARK" "$LOG" | grep -c 'right click slot=36')" = "1" ] && pass 'one click is one event' \
    || fail 'one click is one event'

if echo "$A" | grep -qE 'widgets=[1-9]'; then
  mark; aim w0 >/dev/null; $MOUSE left
  expect " widget '" 'a click on a button resolves to that button'
else
  fail 'the screen carries its buttons'
fi

mark; $MOUSE sneak 700; sleep 1
[ "$(count minecraft:diamond)" = "5" ] && pass 'closing returns every item' || fail 'closing returns every item'
$SHOT "$OUT/3-closed.png"
mark; $MOUSE left; sleep 1
seen 'click slot=' && fail 'a closed screen takes no clicks' || pass 'a closed screen takes no clicks'

$RC 'tp cimviewer 0.5 -60 0.5 0 0' >/dev/null; sleep 1
mark; $MOUSE say /keyboard; sleep 2
seen 'issued server command: /keyboard' && pass 'typing /keyboard reaches the server' || fail 'typing /keyboard reaches the server'
$SHOT "$OUT/4-keyboard.png"
mark; $MOUSE click 352 299
expect 'key=34[0-9] down' 'a keyboard button sends a key'
mark; $MOUSE click 352 497
expect 'key=87 up' 'a plain key taps down and up'
$SHOT "$OUT/5-keyboard.png"

[ "$bad" = 0 ] && echo 'click test: ALL PASS' || echo "click test: $bad FAILED"
exit "$bad"
