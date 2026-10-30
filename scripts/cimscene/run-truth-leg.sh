#!/usr/bin/env bash
# TRUTH LEG — one command, everything inside (the sandbox kills all processes
# at command end, so nothing may span commands):
#   1. boot the real NeoForge 21.1.265 server (cimtest + cimscene mods)
#   2. RCON: camera pins + the universal gallery build (same command as CI)
#   3. Xvfb + real NeoForge 1.21.1 client joins (fov 45, 1280x720, quickplay)
#   4. gallery-walk: camera-locked vantage + Robot shot per wall
# Artifacts: gal/truth-srv/{boot.log,c-imagine/scenes/gallery.tsv}, gal/truth-shots/
set -uo pipefail
ROOT=/home/z/my-project/work/c-imagine
G=/tmp/my-project/artifacts/gal
SRV=$G/truth-srv
CLI=$G/truth-client
JDK=/tmp/my-project/artifacts/jdk21/bin
export JAVA_HOME=/tmp/my-project/artifacts/jdk21
export PATH=$JDK:$PATH

log(){ echo "[truth-leg] $*"; }

# --- 1. server -------------------------------------------------------------
cd "$SRV"
rm -f world/session.lock   # sandbox SIGKILLs the previous run: stale lock
mkfifo console.in 2>/dev/null || true
setsid bash -c "tail -f console.in | $JDK/java -Xmx3G @libraries/net/neoforged/neoforge/21.1.265/unix_args.txt --nogui >> boot.log 2>&1" &
for i in $(seq 1 60); do
  grep -q "Done (" boot.log 2>/dev/null && break; sleep 2
done
grep -q "Done (" boot.log || { echo "SERVER BOOT FAILED"; tail -30 boot.log; exit 1; }
log "server up"

# --- 2. camera pins + gallery build (RCON) --------------------------------
python3 "$ROOT/scripts/cimscene/rcon.py" 127.0.0.1 25575 cimgate \
  "gamerule doDaylightCycle false" "time set noon" \
  "gamerule doWeatherCycle false" "weather clear" \
  "gamerule doMobSpawning false" "cimscene gallery 8 1440" | tail -4
for i in $(seq 1 20); do
  test -f "$SRV/c-imagine/scenes/gallery.tsv" && break; sleep 2
done
test -f "$SRV/c-imagine/scenes/gallery.tsv" || { echo "GALLERY TSV MISSING"; tail -20 boot.log; exit 1; }
log "gallery manifest: $(wc -l < "$SRV/c-imagine/scenes/gallery.tsv") lines"

# --- 3. client -------------------------------------------------------------
javac -d "$G/vtools" "$ROOT/scripts/visual/Shot.java" || exit 1
Xvfb :99 -screen 0 1280x720x24 +extension GLX +render -noreset &
XVFB=$!
sleep 3
export DISPLAY=:99
export LIBGL_ALWAYS_SOFTWARE=1
export GALLIUM_DRIVER=llvmpipe
mkdir -p "$CLI/game"
printf 'onboardAccessibility:false\nfov:45.0\n' > "$CLI/game/options.txt"
python3 "$ROOT/scripts/visual/make_servers_dat.py" "$CLI/game" || exit 1
LAUNCH=$(cat "$CLI/launch.txt")
cd "$CLI"
setsid bash -c "cd '$CLI' && $LAUNCH > client.out 2>&1" &
for i in $(seq 1 90); do
  grep -q "joined the game" client.out 2>/dev/null && break; sleep 2
done
grep -q "joined the game" client.out || { echo "CLIENT JOIN FAILED"; tail -30 client.out; exit 1; }
log "client joined"

# --- 4. walk ---------------------------------------------------------------
cd "$ROOT"
python3 scripts/cimscene/gallery-walk.py \
  "$SRV/c-imagine/scenes/gallery.tsv" "$SRV/console.in" "$G/truth-shots"
log "shots: $(ls "$G/truth-shots" 2>/dev/null | wc -l)"
log "DONE"
