#!/usr/bin/env bash
# Single-cell projection test: ONE item_display with the acacia_window
# composite at cell (col 0, row 0) = (-5,-60,9), shot, then the script
# prints the pixel bounding box of the window sprite (non-sky/non-grass
# pixels in the left half) to compare against the projection contract
# (center sx=337.6, sy=427.7 at fov 70).
set -uo pipefail
ROOT=/home/z/my-project/work/c-imagine
A=/tmp/my-project/artifacts
G=$A/gal
OUT=/tmp/probe-cell
JDK25=$A/jdk25/bin/java
SRV=$G/srv-probe
CLI=$G/visual-client
RCON="python3 $ROOT/scripts/cimscene/rcon.py 127.0.0.1 25575 cimgate"

mkdir -p "$OUT"
$RCON "gamerule doMobSpawning false" "kill @e[type=!player]" "time set noon" "weather clear" >/dev/null 2>&1
export DISPLAY=:99
export LIBGL_ALWAYS_SOFTWARE=1
export GALLIUM_DRIVER=llvmpipe
export LP_NUM_THREADS=2
export CIM_VTOOLS=$G/vtools
export PATH=$A/jdk21/bin:$PATH
export LD_LIBRARY_PATH=$A/xlibs/x/usr/lib/x86_64-linux-gnu${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}
export VK_ICD_FILENAMES=$A/xlibs/x/usr/share/vulkan/icd.d/lvp_icd.json

# client up (reuse the running one if joined)
L=$($RCON "list" 2>/dev/null | tail -1)
case "$L" in
  *cimviewer*) echo "client already joined";;
  *)
    printf 'onboardAccessibility:false\nfov:-0.65\nrenderClouds:false\npreferredGraphicsBackend:opengl\n' > "$CLI/game/options.txt"
    LAUNCH=$(cat "$CLI/launch.txt" | sed "s|^java |$A/jdk25/bin/java -Xmx1G |")
    setsid bash -c "cd '$CLI' && $LAUNCH > client.out 2>&1" < /dev/null > /dev/null 2>&1 &
    for i in $(seq 1 90); do
      L=$($RCON "list" 2>/dev/null | tail -1)
      case "$L" in *cimviewer*) break;; esac
      sleep 2
    done
    case "$L" in *cimviewer*) echo "client joined";; *) echo JOIN-FAILED; exit 1;; esac
    sleep 12
  ;;
esac

java -cp "$G/vtools" Key F1
sleep 1
CMD=$(awk -F'\t' '$1=="create:acacia_window"{print $2; exit}' /tmp/seed/f2v/visual-gallery.resolved.tsv)
echo "cmd=$CMD"
for MODE in half none; do
  case $MODE in
    half) TR='transformation:{translation:[0f,0f,0f],left_rotation:[0f,0f,0f,1f],right_rotation:[0f,0f,0f,1f],scale:[0.5f,0.5f,0.5f]}' ;;
    none) TR='transformation:{translation:[0f,0f,0f],left_rotation:[0f,0f,0f,1f],right_rotation:[0f,0f,0f,1f],scale:[1.0f,1.0f,1.0f]}' ;;
  esac
  $RCON "kill @e[type=!player]" >/dev/null 2>&1
  sleep 1
  $RCON "summon minecraft:item_display -4.5 -59.5 9.5 {billboard:\"fixed\",$TR,item:{id:\"minecraft:paper\",count:1,components:{\"minecraft:custom_model_data\":{floats:[${CMD}f]}}}}" >/dev/null 2>&1
  sleep 3
  java -cp "$G/vtools" Shot "$OUT/cell-$MODE.png"
  python3 - "$OUT/cell-$MODE.png" <<'PY'
import sys
import numpy as np
from PIL import Image
a = np.asarray(Image.open(sys.argv[1]).convert('RGB')).astype(np.int64)
# the window sprite: reddish-brown acacia (R>G>B) anywhere left of x=500
sub = a[:, :520]
mask = (sub[:, :, 0] > sub[:, :, 1] + 20) & (sub[:, :, 0] > sub[:, :, 2] + 20)
ys, xs = np.nonzero(mask)
if len(xs) == 0:
    print(sys.argv[1], 'NO SPRITE FOUND')
else:
    print(f"{sys.argv[1]}: x {xs.min()}..{xs.max()} (w={xs.max()-xs.min()+1}) "
          f"y {ys.min()}..{ys.max()} (h={ys.max()-ys.min()+1}) center=({(xs.min()+xs.max())/2:.0f},{(ys.min()+ys.max())/2:.0f})")
print('projection contract: center (338, 428), cell 60x60')
PY
done
