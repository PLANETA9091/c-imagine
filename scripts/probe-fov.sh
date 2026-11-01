#!/usr/bin/env bash
# FOV probe for the 26.3 client (task 14: the #50 forensics measured the ours
# leg rendering the same scene at scale ~0.60 of the truth leg on BOTH the
# block pages (pack composites) AND the entity pages (real Paper mobs) —
# i.e. the 26.3 client renders a wider field than the 1.21.1 client at the
# same fov:0.0 options pin). This probe boots the stack ONCE and shoots the
# same page at SEVERAL options.txt fov values; the scale fit against the CI
# truth frame then tells which value renders 70 deg on 26.3.
#
# Usage: probe-fov.sh <outRoot> <fov> [<fov> ...]
set -uo pipefail
ROOT=/home/z/my-project/work/c-imagine
A=/tmp/my-project/artifacts
G=$A/gal
OUTROOT=$1; shift
FOVS=("$@")

JDK25=$A/jdk25/bin/java
SRV=$G/srv-probe
CLI=$G/visual-client
PACK_DIR=/tmp/seed/f2v
SCENES_DIR=/tmp/seed/scenes
PAGE=${CIM_PAGE:-0}

mkdir -p "$OUTROOT"

# --- server (reuse a live one; boot only when the RCON port is silent) ------
RCON="python3 $ROOT/scripts/cimscene/rcon.py 127.0.0.1 25575 cimgate"
if [ -f "$SRV/boot.log" ] && [ -n "$($RCON list 2>/dev/null | tail -1)" ]; then
  echo "server already up"
else
  rm -rf "$SRV"
  mkdir -p "$SRV/plugins" "$SRV/c-imagine"
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
  # the same pins the CI legs apply (night/mobs would poison the luma fit)
  $RCON "gamerule doDaylightCycle false" "time set noon" \
    "gamerule doWeatherCycle false" "weather clear" \
    "gamerule doMobSpawning false" > /dev/null 2>&1
fi

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
export CIM_VTOOLS=$G/vtools
export PATH=$A/jdk21/bin:$PATH
export LD_LIBRARY_PATH=$A/xlibs/x/usr/lib/x86_64-linux-gnu${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}
export VK_ICD_FILENAMES=$A/xlibs/x/usr/share/vulkan/icd.d/lvp_icd.json
export VK_LOADER_DEBUG=
mkdir -p "$G/vtools"
javac -d "$G/vtools" "$ROOT/scripts/visual/Shot.java" "$ROOT/scripts/visual/Key.java" 2>/dev/null

for FOV in "${FOVS[@]}"; do
  TAG=$(echo "$FOV" | sed 's/[-.]/_/g')
  OUT="$OUTROOT/fov$TAG"; mkdir -p "$OUT"
  echo "=== fov $FOV -> $OUT"
  # pins EVERY run (a reused server may have skipped its boot pins; mobs /
  # night poison the luma fit — measured on the -0.65 run with horses)
  $RCON "gamerule doDaylightCycle false" "time set noon" \
    "gamerule doWeatherCycle false" "weather clear" \
    "gamerule doMobSpawning false" > /dev/null 2>&1
  $RCON "kill @e[type=!player]" >/dev/null 2>&1
  $RCON "kick cimviewer Ghost" >/dev/null 2>&1
  sleep 2
  printf 'onboardAccessibility:false\nfov:%s\nrenderClouds:false\npreferredGraphicsBackend:opengl\n' "$FOV" > "$CLI/game/options.txt"
  python3 "$ROOT/scripts/visual/make_servers_dat.py" "$CLI/game" || exit 1
  LAUNCH=$(cat "$CLI/launch.txt" | sed "s|^java |$A/jdk25/bin/java -Xmx1G |")
  rm -f "$CLI/client.out"
  setsid bash -c "cd '$CLI' && $LAUNCH > client.out 2>&1" < /dev/null > /dev/null 2>&1 &
  ONLINE=""
  for i in $(seq 1 120); do
    ONLINE=$($RCON "list" 2>/dev/null | tail -1)
    case "$ONLINE" in *cimviewer*) break;; esac
    sleep 2
  done
  case "$ONLINE" in *cimviewer*) echo "player online";;
    *) echo "JOIN FAILED at fov $FOV"; tail -15 "$CLI/client.out"; exit 1;; esac
  for i in $(seq 1 30); do
    grep -qE "Loaded [0-9]+ advancements" "$CLI/client.out" 2>/dev/null && break
    sleep 2
  done
  for i in $(seq 1 30); do
    N=$(grep -c "Reloading ResourceManager" "$CLI/client.out" 2>/dev/null || true)
    [ "${N:-0}" -ge 2 ] && break
    sleep 1
  done
  sleep 10
  $RCON "kill @e[type=!player]" "time set noon" "weather clear" \
    "tp cimviewer 0.5 -60 0.5 0 0"
  sleep 5
  java -cp "$G/vtools" Key F1
  sleep 1
  # PAPERCLIP GOTCHA (measured): paperclip relaunches the JVM, so the -D
  # flags die and the server rebuilds the pack WITHOUT the mod jars ->
  # empty gallery rows -> "cim gallerypage" answers placed=0. The page is
  # therefore placed DIRECTLY: one summon per manifest cell, the CMD comes
  # from the seed's visual-gallery.resolved.tsv (state -> cmd), the same
  # summon grammar the plugin's galleryPage uses.
  SUMMONS="kill @e[type=!player]"
  python3 - "$SCENES_DIR/gallery-create-p$PAGE.tsv" "$SRV/c-imagine/f2v/visual-gallery.resolved.tsv" > "$OUT/summons.txt" <<'PY'
import sys
man, res = sys.argv[1], sys.argv[2]
cmd = {}
for line in open(res, encoding='utf-8'):
    p = line.rstrip('\n').split('\t')
    if len(p) >= 3 and p[0] != 'state':
        cmd[p[0]] = p[1]
for line in open(man, encoding='utf-8'):
    p = line.rstrip('\n').split('\t')
    if len(p) >= 5 and p[0] == 'cell':
        x, y, z, state = p[1], p[2], p[3], p[4]
        c = cmd.get(state)
        if c is None:
            continue
        pos = f"{int(x)+0.5} {int(y)+0.5} {int(z)+0.5}"
        # SCALE-0.5 PROBE (task 14): the 26.3 ItemDisplay renders block models
        # at 2x (the model json "fixed" scale 0.5 no longer applies — measured:
        # the acacia_window cube fills 2 cells on screen). Half scale WITHOUT
        # translation: the display renders centered on the entity position,
        # so the half-scale cube spans exactly the [x, x+1] block cell.
        print(f'summon minecraft:item_display {pos} '
              f'{{billboard:"fixed",transformation:{{translation:[0f,0.5f,0f],'
              f'left_rotation:[0f,0f,0f,1f],right_rotation:[0f,0f,0f,1f],'
              f'scale:[0.5f,0.5f,0.5f]}},'
              f'item:{{id:"minecraft:paper",count:1,'
              f'components:{{"minecraft:custom_model_data":{{floats:[{c}f]}}}}}}}}')
PY
  N=$(grep -c summon "$OUT/summons.txt" || true)
  echo "summons: $N"
  $RCON --f "$OUT/summons.txt" > "$OUT/rcon-page.log" 2>&1
  tail -1 "$OUT/rcon-page.log"
  sleep 3
  java -cp "$G/vtools" Shot "$OUT/page-$PAGE.png"
  test -s "$OUT/page-$PAGE.png" || { echo "SHOT FAILED at fov $FOV"; exit 1; }
  echo "shot done fov=$FOV"
done
echo "FOV PROBE DONE: $OUTROOT"
