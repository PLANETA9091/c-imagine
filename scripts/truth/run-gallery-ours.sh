#!/usr/bin/env bash
# OURS-leg gallery session (LOCAL single-command variant): Paper 26.3 +
# cim-paper-plugin + the F2V gallery pack + the vanilla 26.3 client replaying
# the TRUTH-leg page manifests as ItemDisplay rigs (one per cell, CMD through
# the pack). The sandbox reaps background processes between tool calls, so
# the whole session (Xvfb + server + client join + pins + page screenshots)
# must live inside ONE command.
#
# Usage: run-gallery-ours.sh <pages>   (default 3)
set -uo pipefail
export JAVA_HOME=/tmp/my-project/artifacts/jdk25
export PATH=$JAVA_HOME/bin:$PATH
A=/tmp/my-project/artifacts
G=/home/z/my-project/work/c-imagine
SRV=$A/srv-ours
CLI=$A/gal/visual-client
SHOTS=$A/ours-shots
PAGES="${1:-3}"

# ---- stage the server dir fresh (idempotent) -------------------------------
rm -rf "$SRV"
mkdir -p "$SRV/plugins" "$SRV/mods" "$SRV/c-imagine/f2v" "$SRV/c-imagine/scenes"
cp -r "$A/gal/srv-cim/libraries" "$A/gal/srv-cim/versions" "$SRV"/
cp "$A/gal/srv-cim/paperclip.jar" "$SRV"/
echo "eula=true" > "$SRV/eula.txt"
cp "$G/paper-plugin/build/offline/cim-paper-plugin.jar" "$SRV/plugins/"
cp "$A/srv-gallery/mods/create-1.21.1-6.0.10.jar" "$SRV/mods/"
cp /tmp/f2v-real/pack.zip /tmp/f2v-real/pack.sha1 /tmp/f2v-real/visual-gallery.tsv "$SRV/c-imagine/f2v/"
touch "$SRV/c-imagine/f2v/visual-items.tsv"
cp "$A/srv-gallery/c-imagine/scenes/"gallery-create-p*.tsv "$SRV/c-imagine/scenes/"
SHA1=$(cat /tmp/f2v-real/pack.sha1)
cat > "$SRV/server.properties" <<PROPS
online-mode=false
white-list=false
enforce-secure-profile=false
difficulty=peaceful
level-type=minecraft\:flat
level-seed=42
view-distance=6
simulation-distance=4
spawn-protection=0
max-players=5
resource-pack=http\://127.0.0.1\:25580/cim-f2v-pack.zip
resource-pack-sha1=$SHA1
PROPS

# ---- Xvfb + server + client --------------------------------------------------
mkdir -p "$SHOTS" /tmp/xdg
export XDG_RUNTIME_DIR=/tmp/xdg DISPLAY=:99 LIBGL_ALWAYS_SOFTWARE=1 GALLIUM_DRIVER=llvmpipe
# X11 + Vulkan runtime deps (reconstructed 2026-10-30 via apt-get download:
# libxtst6 for the Robot tools; mesa-vulkan-drivers lvp ICD for the 26.3
# client's Vulkan fallback — the GLX path has no matching visual under
# Xvfb+llvmpipe, the measured Oct-29 run joined through lvp Vulkan 1.4.305)
export LD_LIBRARY_PATH=/tmp/xdebs/root/usr/lib/x86_64-linux-gnu:${LD_LIBRARY_PATH:-}
export VK_DRIVER_FILES=/tmp/xdebs/root/usr/share/vulkan/icd.d/lvp_icd.json
export VK_ICD_FILENAMES=$VK_DRIVER_FILES
Xvfb :99 -screen 0 1280x720x24 +extension GLX +render -noreset > /tmp/xvfb-ours.log 2>&1 &
XVPID=$!
sleep 3
java -cp "$G/vtools" Shot "$SHOTS/probe.png" || { echo "DISPLAY FAIL"; exit 1; }

cd "$SRV"
mkfifo console.in
# MEASURED (run 2, 4 GB sandbox): server -Xmx2G + client -Xmx900M + llvmpipe
# buffers = kernel OOM-kill of the CLIENT mid-reload (silent SIGKILL — no
# crash report, log ends at atlas creation). Shrink both heaps + glibc arenas.
tail -f console.in | MALLOC_ARENA_MAX=2 java -Xmx768M -jar paperclip.jar --nogui > boot.log 2>&1 &
SPID=$!
cd "$CLI"
bash -c "$(cat launch.txt | sed 's/-Xmx900M/-Xmx700M/')" > client-ours.out 2>&1 &
CPID=$!
echo "client pid $CPID, server pid $SPID, xvfb pid $XVPID"

JOINED=0
for i in $(seq 1 60); do
  if grep -q "logged in with entity id" "$SRV/boot.log" 2>/dev/null \
     && grep -q "Loaded .* advancements" client-ours.out 2>/dev/null; then JOINED=1; break; fi
  kill -0 $CPID 2>/dev/null || break
  sleep 5
done
if [ "$JOINED" != "1" ]; then echo "NOT JOINED"; tail -30 client-ours.out; tail -20 "$SRV/boot.log"; kill $XVPID $SPID 2>/dev/null; exit 1; fi
echo "JOINED"
sleep 30   # chunks + pack apply under llvmpipe

# camera-lock environment pins (camera-lock-26-3.md)
echo "gamerule doDaylightCycle false" > "$SRV/console.in"
echo "time set noon" > "$SRV/console.in"
echo "gamerule doWeatherCycle false" > "$SRV/console.in"
echo "weather clear" > "$SRV/console.in"
echo "gamerule doMobSpawning false" > "$SRV/console.in"
sleep 2
echo "kill @e[type=!player]" > "$SRV/console.in"
echo "tp cimviewer 0.5 -60 0.5 0 0" > "$SRV/console.in"
sleep 5
java -cp "$G/vtools" Key F1
sleep 1

# gallery page -> wait for the command feedback marker -> shot
for p in $(seq 0 $((PAGES-1))); do
  # MEASURED (run 3): passive mobs pre-spawn at world creation and WALK into
  # the frame between pages (the p0-again probe caught a cow) — clear them
  # before EVERY page, not just once after join
  echo "kill @e[type=!player]" > "$SRV/console.in"
  sleep 1
  echo "cim galleryclear" > "$SRV/console.in"
  sleep 1
  echo "cim gallerypage create $p" > "$SRV/console.in"
  ok=0
  for i in $(seq 1 30); do
    grep -q "gallerypage create p$p -> placed=" "$SRV/boot.log" 2>/dev/null && { ok=1; break; }
    sleep 1
  done
  if [ "$ok" != "1" ]; then echo "page $p NO MARKER"; grep "gallery\|gallerypage" "$SRV/boot.log" | tail -5; break; fi
  grep "gallerypage create p$p -> placed=" "$SRV/boot.log" | tail -1
  sleep 5   # section rebuild + entity render under llvmpipe
  java -cp "$G/vtools" Shot "$SHOTS/ours-gal-p$p.png"
  echo "shot page $p"
done

# determinism probe: re-place page 0, shoot again
echo "kill @e[type=!player]" > "$SRV/console.in"
sleep 1
echo "cim galleryclear" > "$SRV/console.in"
sleep 1
echo "cim gallerypage create 0" > "$SRV/console.in"
sleep 6
java -cp "$G/vtools" Shot "$SHOTS/ours-gal-p0-again.png"
echo "shot page 0 again"

ls -la "$SHOTS"
kill $CPID $SPID $XVPID 2>/dev/null
echo "OURS GALLERY SESSION DONE"
