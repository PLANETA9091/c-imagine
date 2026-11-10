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

# THE CAMERA CHECK: real mouse movement must change the look the server sees and must
# not change the view. Only world pixels count: the crop leaves out chat and the pointer.
yaw() { $RC 'data get entity cimviewer Rotation[0]' | tail -1 | grep -oE '[-0-9.]+f' | head -1; }
viewcheck() {
  sleep 2; $SHOT "$1-a.png"; Y1=$(yaw)
  $MOUSE sweep 9 3; sleep 1.5; $SHOT "$1-b.png"; Y2=$(yaw)
  # A still view proves nothing unless the mouse really moved something.
  if [ -z "$Y1" ] || [ "$Y1" = "$Y2" ]; then
    fail "$2: the mouse reaches the server ($Y1 to $Y2)"; fail "$2: the view stays still (not measurable)"; return
  fi
  pass "$2: the mouse reaches the server ($Y1 to $Y2)"
  D=$(magick compare -metric AE -fuzz 2% \( "$1-a.png" -crop "$3" +repage \) \( "$1-b.png" -crop "$3" +repage \) null: 2>&1 \
      | grep -oE '^[0-9.e+]+' | head -1)
  echo "     world pixels moved: $D"
  python3 -c "import sys; sys.exit(0 if float('${D:-1e9}') < 300 else 1)" \
      && pass "$2: the view stays still" || fail "$2: the view stays still"
}

$RC 'op cimviewer' 'clear cimviewer' 'time set noon' 'tp cimviewer 0.5 -60 0.5 0 0' \
    'item replace entity cimviewer hotbar.1 with minecraft:diamond 5' >/dev/null
$RC "item replace entity cimviewer weapon.mainhand with minecraft:paper[minecraft:custom_model_data={floats:[${CARRIER}f]}]" >/dev/null
sleep 1.5

mark; $MOUSE right
expect 'opened' 'right-clicking the item opens its screen' 2
$SHOT "$OUT/1-open.png"

viewcheck "$OUT/cam-screen" 'with a screen open' 900x100+380+0
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

mark; $MOUSE sneak 700; sleep 1.5
expect 'view released' 'closing gives the camera back'
[ "$(count minecraft:diamond)" = "5" ] && pass 'closing returns every item' || fail 'closing returns every item'
$SHOT "$OUT/3-closed.png"
mark; $MOUSE left; sleep 1
seen 'click slot=' && fail 'a closed screen takes no clicks' || pass 'a closed screen takes no clicks'

key() { aim "k$1" >/dev/null; $MOUSE left; }
pos() { $RC 'data get entity cimviewer Pos[2]' | tail -1 | grep -oE '[-0-9.]+d' | head -1; }

$RC 'tp cimviewer 0.5 -60 0.5 0 0' >/dev/null; sleep 1
mark; $MOUSE say /keyboard
expect 'keyboard open' 'typing /keyboard opens the floating keyboard' 2
$SHOT "$OUT/4-keyboard.png"
viewcheck "$OUT/cam-keyboard" 'with the keyboard open' 1280x300+0+0
mark; key 87
expect 'key=87 up' 'a plain key taps down and up'
[ "$(tail -n +"$MARK" "$LOG" | grep -c 'key=87 down')" = "1" ] && pass 'one click is one key press' \
    || fail 'one click is one key press'
mark; key 342
expect 'key=342 down' 'a modifier latches'
$SHOT "$OUT/5-held.png"
mark; key 342
expect 'key=342 up' 'a second click releases it'
mark; key -2; key 82
expect 'key=82 down' 'hold mode keeps a plain key down'
seen 'key=82 up' && fail 'hold mode does not release' || pass 'hold mode does not release'
mark; key 82; expect 'key=82 up' 'held key releases'; key -2
B=$(pos); $MOUSE walk 900; sleep 0.5; A2=$(pos)
[ -n "$B" ] && [ "$B" != "$A2" ] && pass "the player walks with the keyboard open ($B to $A2)" \
    || fail "the player walks with the keyboard open ($B to $A2)"
key -3; sleep 0.5; $SHOT "$OUT/6-mods.png"
mark; key -100
expect 'bindings=\[create' 'a mod binding reaches its key'
key -100; key -3
mark; key -4
expect 'keyboard closed' 'the close key closes it'
$SHOT "$OUT/7-closed.png"
mark; $MOUSE left; sleep 1
seen 'key=' && fail 'a closed keyboard takes no clicks' || pass 'a closed keyboard takes no clicks'
mark; $MOUSE say /keyboard; expect 'keyboard open' 'it opens again' 2
mark; $MOUSE say /keyboard; expect 'keyboard closed' '/keyboard toggles it off' 2

[ "$bad" = 0 ] && echo 'click test: ALL PASS' || echo "click test: $bad FAILED"
exit "$bad"
