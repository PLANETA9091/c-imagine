#!/usr/bin/env bash
# Task 14-g single-cell probe v2 — EVERYTHING IN ONE INVOCATION (the sandbox
# recycles background processes between tool calls; the stack must live and
# die inside this script). RAM-tuned: server 700M, client 600M (the measured
# OOM: client 1.74G anon + server 1.4G > 4G sandbox).
#
# Renders the #57 suspect composites in ISOLATION at the probe cell
# (-5,-60,9) = display (-4.5,-59.5,9.5) with the CI walk's transformation
# (scale 1.0126, ty -0.0184, identity rotations) on the exact #57 seed pack
# (sha1 a483f644...) via FAST BOOT, then measures each shot's content bbox
# against the projection contract (center 337.6,427.7, cell ~60 px).
set -uo pipefail
ROOT=/home/z/my-project/work/c-imagine
A=/tmp/my-project/artifacts
G=$A/gal
SRV=$G/srv-probe
CLI=$G/visual-client
OUT=/tmp/probe4
JDK25=$A/jdk25/bin/java
RCON="python3 $ROOT/scripts/cimscene/rcon.py 127.0.0.1 25575 cimgate"
SEED=/tmp/gal57
SHA1=a483f644a6281ad58945f7baf820b44952bb7704
PLUGIN_JAR=$ROOT/paper-plugin/build/offline/cim-paper-plugin.jar

rm -rf "$OUT"; mkdir -p "$OUT" /tmp/.X11-unix /tmp/xdg /tmp/xvfb3
export DISPLAY=:98
export XDG_RUNTIME_DIR=/tmp/xdg
export LIBGL_ALWAYS_SOFTWARE=1
export GALLIUM_DRIVER=llvmpipe
export LP_NUM_THREADS=2
export JAVA_TOOL_OPTIONS="-Djava.awt.headless=false"
export LD_LIBRARY_PATH=$A/xlibs/x/usr/lib/x86_64-linux-gnu${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}
export VK_ICD_FILENAMES=$A/xlibs/x/usr/share/vulkan/icd.d/lvp_icd.json

cleanup() {
  pkill -9 -f "paperclip.jar" 2>/dev/null
  pkill -9 -f "net.minecraft.client.main.Main" 2>/dev/null
  pkill -9 -f "Xvfb :98" 2>/dev/null
}
trap cleanup EXIT

# ---------------- server ----------------
mkdir -p "$SRV/plugins" "$SRV/c-imagine/f2v"
cp "$SEED/pack.zip" "$SRV/c-imagine/f2v/pack.zip"
printf '%s\n' "$SHA1" > "$SRV/c-imagine/f2v/pack.sha1"
cp "$SEED/visual-gallery.resolved.tsv" "$SRV/c-imagine/f2v/" 2>/dev/null || true
cp "$SEED/visual-items.resolved.tsv" "$SRV/c-imagine/f2v/" 2>/dev/null || true
[ -f "$SRV/c-imagine/f2v/visual-blocks.tsv" ] || : > "$SRV/c-imagine/f2v/visual-blocks.tsv"
cp "$PLUGIN_JAR" "$SRV/plugins/cim-paper-plugin.jar"
mv "$SRV/plugins/spark" "$SRV/plugins-disabled/" 2>/dev/null || true
SHA1_PROP=$(printf '%s' "$SHA1" | sed 's/:/\\:/g')
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
resource-pack-sha1=$SHA1_PROP
PROPS
echo "eula=true" > "$SRV/eula.txt"

Xvfb :98 -screen 0 1280x720x24 +extension GLX +render -noreset > /tmp/xvfb3/xvfb.log 2>&1 &
sleep 2

cd "$SRV"
setsid bash -c "cd '$SRV' && $JDK25 -Xmx700M -Dcim.f2v.fastBoot=true -jar paperclip.jar --nogui > $OUT/server.out 2>&1" < /dev/null > /dev/null 2>&1 &
cd /
R=""
for i in $(seq 1 120); do
  R=$($RCON "list" 2>/dev/null | tail -1 || true)
  case "$R" in *players*) break;; esac
  sleep 2
done
case "$R" in *players*) echo "server up: $R";; *) echo SERVER-FAILED; tail -20 "$OUT/server.out"; exit 1;; esac
grep -q "FAST BOOT" "$SRV/logs/latest.log" && echo "FAST BOOT ok"

# ---------------- client ----------------
printf 'onboardAccessibility:false\nfov:-0.65\nrenderClouds:false\npreferredGraphicsBackend:opengl\n' > "$CLI/game/options.txt"
LAUNCH=$(cat "$CLI/launch.txt" | sed "s|^java |$JDK25 -Xmx600M |")
setsid bash -c "cd '$CLI' && $LAUNCH > $OUT/client.out 2>&1" < /dev/null > /dev/null 2>&1 &
L=""
for i in $(seq 1 75); do
  L=$($RCON "list" 2>/dev/null | tail -1)
  case "$L" in *cimviewer*) break;; esac
  sleep 2
done
case "$L" in *cimviewer*) echo "client joined";; *) echo JOIN-FAILED; grep -vE "^\s+at |Caused by" "$OUT/client.out" | tail -12; exit 1;; esac
for i in $(seq 1 30); do
  N=$(grep -c "Reloading ResourceManager" "$OUT/client.out" || true)
  [ "${N:-0}" -ge 2 ] && break
  sleep 1
done
sleep 6

$RCON "gamerule doMobSpawning false" "kill @e[type=!player]" "time set noon" "weather clear" \
      "tp cimviewer 0.5 -60 0.5 0 0" >/dev/null 2>&1
sleep 2
$JDK25 -cp "$G/vtools" Key F1 >/dev/null 2>&1
sleep 1

# ---------------- probe cells ----------------
CELLS=(
  "107444.0 wall_ochrum_control"
  "107680.0 stairs_north_bottom_straight"
  "107688.0 stairs_north_top_outer_right"
  "107700.0 stairs_south_bottom_straight"
  "107710.0 stairs_south_top_straight"
  "107720.0 stairs_west_bottom_straight"
  "111012.0 layered_scoria"
  "111049.0 sail_north"
  "111053.0 seat"
  "116152.0 contact_down"
)
for entry in "${CELLS[@]}"; do
  CMD="${entry%% *}"; NAME="${entry#* }"
  $RCON "kill @e[type=!player]" >/dev/null 2>&1
  sleep 1
  $RCON "summon minecraft:item_display -4.5 -59.5 9.5 {billboard:\"fixed\",brightness:{sky:15b,block:15b},transformation:{translation:[0f,-0.0184f,0f],left_rotation:[0f,0f,0f,1f],right_rotation:[0f,0f,0f,1f],scale:[1.0126f,1.0126f,1.0126f]},item:{id:\"minecraft:paper\",count:1,components:{\"minecraft:custom_model_data\":{floats:[${CMD}f]}}}}" >/dev/null 2>&1
  sleep 2
  $JDK25 -cp "$G/vtools" Shot "$OUT/cell-${NAME}.png" >/dev/null 2>&1
  echo "shot $NAME"
done
echo "== shots: $(ls $OUT/cell-*.png 2>/dev/null | wc -l)"
