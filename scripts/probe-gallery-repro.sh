#!/usr/bin/env bash
# LOCAL gallery repro probe: boot Paper 26.3 + cim plugin + the CI seed pack,
# join with the stock 26.3 client, replay ONE gallery page + ONE isolated
# wall display, shoot both. Diagnostic tool for the honest-red forensics
# (run #46: ours renders missingno while the client log bakes clean).
#
# Usage: probe-gallery-repro.sh <packDir> <scenesDir> <page> <stateSubstr> <outDir>
set -uo pipefail
ROOT=/home/z/my-project/work/c-imagine
A=/tmp/my-project/artifacts
G=$A/gal
PACK_DIR=$1   # dir with pack.zip, pack.sha1, visual-gallery.resolved.tsv, ...
SCENES_DIR=$2 # dir with gallery-<ns>-p<p>.tsv manifests
PAGE=$3       # page number to replay
STATE=$4      # substring to pick the isolated-display state from the resolved tsv
OUT=$5        # output dir for shots + logs

JDK25=$A/jdk25/bin/java
SRV=$G/srv-probe
CLI=$G/visual-client

rm -rf "$SRV"
mkdir -p "$SRV/plugins" "$SRV/c-imagine" "$OUT"
# the server jar + its extracted cache (paperclip re-extracts when absent)
cp "$G/srv-cim/paperclip.jar" "$SRV/"
cp -r "$G/srv-cim/versions" "$SRV/" 2>/dev/null || true
cp -r "$G/srv-cim/cache" "$SRV/" 2>/dev/null || true
cp -r "$PACK_DIR" "$SRV/c-imagine/f2v"
mkdir -p "$SRV/c-imagine/scenes"
cp -r "$SCENES_DIR"/. "$SRV/c-imagine/scenes/"
cp "$ROOT/paper-plugin/build/offline/cim-paper-plugin.jar" "$SRV/plugins/"
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
UP=""
for i in $(seq 1 240); do
  grep -q "f2v pack: serving" boot.log 2>/dev/null && { UP=1; break; }
  grep -q "Done (" boot.log 2>/dev/null && { UP=1; break; }
  sleep 1
done
[ -n "$UP" ] || { echo "SERVER FAILED"; tail -30 boot.log; exit 1; }
echo "server up (pack sha1=$SHA1)"
RCON="python3 $ROOT/scripts/cimscene/rcon.py 127.0.0.1 25575 cimgate"

# --- Xvfb + client -----------------------------------------------------------
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
  setsid Xvfb :99 -screen 0 1280x720x24 +extension GLX +render -noreset < /dev/null > "$OUT/xvfb.log" 2>&1 &
  for i in $(seq 1 10); do xprobe && break; sleep 1; done
  xprobe || { echo "XVFB FAILED"; cat "$OUT/xvfb.log"; exit 1; }
fi
echo "xvfb ok"

export DISPLAY=:99
export LIBGL_ALWAYS_SOFTWARE=1
export GALLIUM_DRIVER=llvmpipe
export LP_NUM_THREADS=2
export CIM_VTOOLS=$G/vtools
export PATH=$A/jdk21/bin:$PATH
export LD_LIBRARY_PATH=$A/xlibs/x/usr/lib/x86_64-linux-gnu${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}
# the 26.3 renderpearl tries Vulkan FIRST — the lavapipe ICD (absolute
# library_path inside) is the backend that WORKS on this sandbox (measured:
# the GLX visual match fails here; the old local walk ran the same ICD)
export VK_ICD_FILENAMES=$A/xlibs/x/usr/share/vulkan/icd.d/lvp_icd.json
export VK_LOADER_DEBUG=
javac -d "$G/vtools" "$ROOT/scripts/visual/Shot.java" "$ROOT/scripts/visual/Key.java" 2>/dev/null

printf 'onboardAccessibility:false\nfov:0.0\nrenderClouds:false\npreferredGraphicsBackend:opengl\n' > "$CLI/game/options.txt"
python3 "$ROOT/scripts/visual/make_servers_dat.py" "$CLI/game" || exit 1
LAUNCH=$(cat "$CLI/launch.txt" | sed "s|^java |$A/jdk25/bin/java -Xmx1G |")
rm -f "$CLI/client.out"
# ghost guard
for i in $(seq 1 15); do
  $RCON "kick cimviewer Ghost" >/dev/null 2>&1
  L=$($RCON "list" 2>/dev/null | tail -1)
  case "$L" in *"0 of a max"*) break;; esac
  sleep 2
done
sleep 3
setsid bash -c "cd '$CLI' && $LAUNCH > client.out 2>&1" < /dev/null > /dev/null 2>&1 &
echo "client launching"
ONLINE=""
for i in $(seq 1 120); do
  ONLINE=$($RCON "list" 2>/dev/null | tail -1)
  case "$ONLINE" in *cimviewer*) break;; esac
  sleep 2
done
case "$ONLINE" in *cimviewer*) echo "player online: $ONLINE";;
  *) echo "JOIN FAILED: $ONLINE"; tail -25 "$CLI/client.out"; exit 1;; esac
for i in $(seq 1 30); do
  grep -qE "Loaded [0-9]+ advancements" "$CLI/client.out" 2>/dev/null && break
  sleep 2
done
# wait for the pack reload to finish (the SECOND ResourceManager reload)
for i in $(seq 1 30); do
  N=$(grep -c "Reloading ResourceManager" "$CLI/client.out" 2>/dev/null || true)
  [ "${N:-0}" -ge 2 ] && break
  sleep 1
done
sleep 10

# --- probes ------------------------------------------------------------------
$RCON "kill @e[type=!player]" "tp cimviewer 0.5 -60 0.5 0 0"
sleep 5
java -cp "$G/vtools" Key F1
sleep 1
$RCON "kill @e[type=!player]" "cim galleryclear" "cim gallerypage create $PAGE" > "$OUT/rcon-page.log" 2>&1
tail -2 "$OUT/rcon-page.log"
sleep 3
java -cp "$G/vtools" Shot "$OUT/page-$PAGE.png"
test -s "$OUT/page-$PAGE.png" || { echo "SHOT FAILED"; exit 1; }
echo "shot page $PAGE"

CMD=$(grep -m1 "$STATE" "$SRV/c-imagine/f2v/visual-gallery.resolved.tsv" | cut -f2)
echo "isolated state cmd=$CMD ($STATE)"
$RCON "kill @e[type=!player]" "cim galleryclear" \
  "summon minecraft:item_display -1.5 -59.5 9.5 {billboard:\"fixed\",item:{id:\"minecraft:paper\",count:1,components:{\"minecraft:custom_model_data\":{floats:[${CMD}f]}}}}" \
  > "$OUT/rcon-isolated.log" 2>&1
tail -2 "$OUT/rcon-isolated.log"
sleep 3
java -cp "$G/vtools" Shot "$OUT/isolated-$PAGE.png"
echo "shot isolated"
cp "$CLI/client.out" "$OUT/client.out"
echo "PROBE DONE: $OUT"
