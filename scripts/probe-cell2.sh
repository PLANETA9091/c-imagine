#!/usr/bin/env bash
# Single-cell projection test, VALID this time: F1 pressed right before each
# shot (the HUD/hand poison the frame — measured: the "window at x=1140" was
# the hand item), scale swept over 4 values, the acacia_window sprite bbox
# measured per value. Output: scale -> bbox center/size, to fit the 26.3
# ItemDisplay render rule.
set -uo pipefail
ROOT=/home/z/my-project/work/c-imagine
A=/tmp/my-project/artifacts
G=$A/gal
OUT=/tmp/probe-cell2
CLI=$G/visual-client
RCON="python3 $ROOT/scripts/cimscene/rcon.py 127.0.0.1 25575 cimgate"

mkdir -p "$OUT"
export DISPLAY=:99
export LIBGL_ALWAYS_SOFTWARE=1
export GALLIUM_DRIVER=llvmpipe
export LP_NUM_THREADS=2
export CIM_VTOOLS=$G/vtools
export PATH=$A/jdk21/bin:$PATH
export LD_LIBRARY_PATH=$A/xlibs/x/usr/lib/x86_64-linux-gnu${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}
export VK_ICD_FILENAMES=$A/xlibs/x/usr/share/vulkan/icd.d/lvp_icd.json

L=$($RCON "list" 2>/dev/null | tail -1)
case "$L" in
  *cimviewer*) echo "client joined";;
  *) echo "NO CLIENT"; exit 1;;
esac

CMD=$(awk -F'\t' '$1=="create:acacia_window"{print $2; exit}' /tmp/seed/f2v/visual-gallery.resolved.tsv)
echo "cmd=$CMD"

for MODE in s10126 s100 s075 s050; do
  case $MODE in
    s10126) SC=1.0126 ;;
    s100)   SC=1.0 ;;
    s075)   SC=0.75 ;;
    s050)   SC=0.5 ;;
  esac
  $RCON "kill @e[type=!player]" "time set noon" "weather clear" >/dev/null 2>&1
  sleep 1
  $RCON "summon minecraft:item_display -4.5 -59.5 9.5 {billboard:\"fixed\",transformation:{translation:[0f,0f,0f],left_rotation:[0f,0f,0f,1f],right_rotation:[0f,0f,0f,1f],scale:[${SC}f,${SC}f,${SC}f]},item:{id:\"minecraft:paper\",count:1,components:{\"minecraft:custom_model_data\":{floats:[${CMD}f]}}}}" >/dev/null 2>&1
  sleep 2
  $RCON "tp cimviewer 0.5 -60 0.5 0 0" >/dev/null 2>&1
  sleep 3
  java -cp "$G/vtools" Key F1
  sleep 2
  java -cp "$G/vtools" Shot "$OUT/cell-$MODE.png"
  python3 - "$OUT/cell-$MODE.png" "$MODE" "$SC" <<'PY'
import sys
import numpy as np
from PIL import Image
a = np.asarray(Image.open(sys.argv[1]).convert('RGB')).astype(np.int64)
mode, sc = sys.argv[2], sys.argv[3]
# hud guard: hotbar slots are grey bars at y>=650 -> if present, F1 failed
hud = a[650:710, 400:900]
hud_grey = ((abs(hud[:, :, 0] - hud[:, :, 1]) < 12)
            & (abs(hud[:, :, 1] - hud[:, :, 2]) < 12)
            & (hud[:, :, 0] > 60) & (hud[:, :, 0] < 210)).mean()
# acacia sprite: reddish-brown (R>G+20, R>B+20), anywhere in the frame
mask = (a[:, :, 0] > a[:, :, 1] + 20) & (a[:, :, 0] > a[:, :, 2] + 20)
ys, xs = np.nonzero(mask)
if len(xs) == 0:
    print(f'{mode} scale={sc}: NO SPRITE (hud={hud_grey:.2f})')
else:
    print(f'{mode} scale={sc}: bbox x {xs.min()}..{xs.max()} y {ys.min()}..{ys.max()} '
          f'w={xs.max()-xs.min()+1} h={ys.max()-ys.min()+1} '
          f'center=({(xs.min()+xs.max())/2:.0f},{(ys.min()+ys.max())/2:.0f}) hud={hud_grey:.2f}')
print('  contract: center (338, 428); a 1-block cube = 60x60 px')
PY
done
