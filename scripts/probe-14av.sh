#!/usr/bin/env bash
# 14-av DECISIVE PROBE: one shot answers BOTH open questions about the 26.3
# ours client — (A) its real rendered focal px (fov law on this build) via a
# REAL block placed at the contract rig, and (B) the item_display composite
# scale (does the block/block parent's fixed 0.5 still apply?) via the CI
# grammar summon with NO extra transform, side by side in the SAME frame.
# Also shoots the F3 overlay (the window/framebuffer size is printed there).
#
# Usage: probe-14av.sh <outRoot> <fovValue> [scale]
set -uo pipefail
ROOT=/home/z/my-project/work/c-imagine
A=/tmp/my-project/artifacts
G=$A/gal
OUTROOT=$1; FOV=$2; SCALE=${3:-1.0}
PAGE=0

JDK25=$A/jdk25/bin/java
SRV=$G/srv-14av
CLI=$G/visual-client
PACK_DIR=/tmp/seed/f2v
SCENES_DIR=/tmp/seed/scenes

mkdir -p "$OUTROOT"
RCON="python3 $ROOT/scripts/cimscene/rcon.py 127.0.0.1 25575 cimgate"

# --- server (reuse a live one) ------------------------------------------------
if [ -f "$SRV/boot.log" ] && [ -n "$($RCON list 2>/dev/null | tail -1)" ]; then
  echo "server already up"
else
  rm -rf "$SRV"; mkdir -p "$SRV/plugins" "$SRV/c-imagine"
  cp "$G/srv-cim/paperclip.jar" "$SRV/"
  cp -r "$G/srv-cim/versions" "$SRV/" 2>/dev/null || true
  cp -r "$G/srv-cim/cache" "$SRV/" 2>/dev/null || true
  cp -r "$PACK_DIR" "$SRV/c-imagine/f2v"
  cp "$SRV/c-imagine/f2v/pack.zip" "$SRV/c-imagine/f2v/pack.zip" 2>/dev/null || true
  SHA1=$(cat "$SRV/c-imagine/f2v/pack.sha1")
  echo "eula=true" > "$SRV/eula.txt"
  cat > "$SRV/server.properties" <<PROPS
online-mode=false
white-list=false
difficulty=peaceful
level-type=minecraft\:flat
level-seed=42
view-distance=6
simulation-distance=4
spawn-protection=0
max-players=5
enable-rcon=true
rcon.port=25575
rcon.password=cimgate
resource-pack=http\://127.0.0.1\:25580/cim-f2v-pack.zip
resource-pack-sha1=$SHA1
PROPS
  cd "$SRV"
  setsid bash -c "$JDK25 -Xmx3G -Dcim.f2v.fastBoot=true -jar paperclip.jar --nogui > boot.log 2>&1" < /dev/null > /dev/null 2>&1 &
  cd "$ROOT"
  UP=""
  for i in $(seq 1 240); do
    grep -q "f2v pack: serving" "$SRV/boot.log" 2>/dev/null && { UP=1; break; }
    grep -q "Done (" "$SRV/boot.log" 2>/dev/null && { UP=1; break; }
    sleep 1
  done
  [ -n "$UP" ] || { echo "SERVER FAILED"; tail -30 "$SRV/boot.log"; exit 1; }
  echo "server up"
fi

$RCON "gamerule doDaylightCycle false" "time set noon" \
  "gamerule doWeatherCycle false" "weather clear" \
  "gamerule doMobSpawning false" > /dev/null 2>&1

# --- xvfb ---------------------------------------------------------------------
mkdir -p /tmp/.X11-unix 2>/dev/null; chmod 1777 /tmp/.X11-unix 2>/dev/null
xprobe() { python3 -c "
import socket,sys
try:
    s=socket.socket(socket.AF_UNIX); s.connect('/tmp/.X11-unix/X99'); sys.exit(0)
except Exception: sys.exit(1)
"; }
if ! xprobe; then
  rm -f /tmp/.X99-lock /tmp/.X11-unix/X99
  pkill -x Xvfb 2>/dev/null; sleep 1
  setsid Xvfb :99 -screen 0 1280x720x24 +extension GLX +render -noreset < /dev/null > "$OUTROOT/xvfb.log" 2>&1 &
  for i in $(seq 1 10); do xprobe && break; sleep 1; done
  xprobe || { echo "XVFB FAILED"; cat "$OUTROOT/xvfb.log"; exit 1; }
fi
echo "xvfb ok"

export DISPLAY=:99
export LIBGL_ALWAYS_SOFTWARE=1
export GALLIUM_DRIVER=llvmpipe
export LP_NUM_THREADS=2
export PATH=$A/jdk21/bin:$PATH
export LD_LIBRARY_PATH=$A/xlibs/x/usr/lib/x86_64-linux-gnu${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}
export VK_ICD_FILENAMES=$A/xlibs/x/usr/share/vulkan/icd.d/lvp_icd.json
mkdir -p "$G/vtools"
javac -d "$G/vtools" "$ROOT/scripts/visual/Shot.java" "$ROOT/scripts/visual/Key.java" 2>/dev/null

TAG=$(echo "$FOV" | sed 's/[-.]/_/g')_s$(echo "$SCALE" | sed 's/[-.]/_/g')
OUT="$OUTROOT/fov$TAG"; mkdir -p "$OUT"

$RCON "kill @e[type=!player]" "kick cimviewer Ghost" > /dev/null 2>&1
sleep 2
printf 'onboardAccessibility:false\nfov:%s\nrenderClouds:false\npreferredGraphicsBackend:opengl\n' "$FOV" > "$CLI/game/options.txt"
python3 "$ROOT/scripts/visual/make_servers_dat.py" "$CLI/game" || exit 1
LAUNCH=$(cat "$CLI/launch.txt" | sed "s|^java |$JDK25 -Xmx1G |")
rm -f "$CLI/client.out"
setsid bash -c "cd '$CLI' && $LAUNCH > client.out 2>&1" < /dev/null > /dev/null 2>&1 &
ONLINE=""
for i in $(seq 1 150); do
  ONLINE=$($RCON "list" 2>/dev/null | tail -1)
  case "$ONLINE" in *cimviewer*) break;; esac
  sleep 2
done
case "$ONLINE" in
  *cimviewer*) echo "player online";;
  *) echo "JOIN FAILED at fov $FOV"; tail -15 "$CLI/client.out"; exit 1;;
esac
for i in $(seq 1 30); do
  grep -qE "Loaded [0-9]+ advancements" "$CLI/client.out" 2>/dev/null && break
  sleep 2
done
sleep 8

$RCON "kill @e[type=!player]" "time set noon" "weather clear" \
  "tp cimviewer 0.5 -60 0.5 0 0"
sleep 3

# THE RIG: real blocks at x=-2..0 (y=-58, z=9) via setblock; the composite
# display (CI grammar, NO extra transform, raw model scale) at x=1..2.
CMD=$(grep -m1 "create:andesite_casing" "$SRV/c-imagine/f2v/visual-gallery.resolved.tsv" | cut -f2)
[ -n "$CMD" ] || { echo "no casing cmd"; exit 1; }
$RCON "setblock -2 -58 9 minecraft:iron_block" \
      "setblock -1 -58 9 minecraft:iron_block" \
      "setblock 0 -58 9 minecraft:iron_block" \
      "summon minecraft:item_display 1.5 -58.5 9.5 {billboard:\"fixed\",brightness:{sky:15b,block:15b},item:{id:\"minecraft:paper\",count:1,components:{\"minecraft:custom_model_data\":{floats:[${CMD}f]}}}}" \
      "summon minecraft:item_display 2.5 -58.5 9.5 {billboard:\"fixed\",brightness:{sky:15b,block:15b},item:{id:\"minecraft:paper\",count:1,components:{\"minecraft:custom_model_data\":{floats:[${CMD}f]}}}}" \
      > "$OUT/rcon-rig.log" 2>&1
sleep 5
java -cp "$G/vtools" Shot "$OUT/rig-f1.png"
java -cp "$G/vtools" Key F3
sleep 2
java -cp "$G/vtools" Shot "$OUT/rig-f3.png"
java -cp "$G/vtools" Key F3
echo "PROBE DONE fov=$FOV scale=$SCALE -> $OUT"
