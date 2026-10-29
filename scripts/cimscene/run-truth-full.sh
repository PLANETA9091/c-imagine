#!/usr/bin/env bash
# TRUTH LEG — FULL, one command (the sandbox reaper kills setsid'd processes
# nondeterministically between commands, so nothing may span commands):
#   boot real NeoForge server -> RCON pins + gallery -> client join -> walk
# Artifacts: $G/truth-srv/boot.log, gallery.tsv, $G/truth-shots/
set -uo pipefail
ROOT=/home/z/my-project/work/c-imagine
G=/tmp/my-project/artifacts/gal
SRV=$G/truth-srv
JDK=/tmp/my-project/artifacts/jdk21/bin
log(){ echo "[truth-full] $*"; }
export PATH=$JDK:$PATH
export LD_LIBRARY_PATH=/tmp/my-project/artifacts/xlibs/x/usr/lib/x86_64-linux-gnu${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}
export CIM_VTOOLS=$G/vtools

# --- 0. clean slate for ports/locks -----------------------------------------
# SIGTERM is NOT enough: MC java handles/shields it and the setsid'd processes
# survive for HOURS, their stale X window photobombs every shot (measured
# 2026-10-29: a frozen 18:23 client window was "the gallery" until SIGKILL)
pkill -KILL -f unix_args 2>/dev/null
pkill -KILL -f bootstraplauncher 2>/dev/null
pkill -KILL -f "neoforge-21.1.265" 2>/dev/null
sleep 2
pkill -KILL -f "java.*cim" 2>/dev/null
pkill -KILL -f paperclip 2>/dev/null
# the sandbox reaper kills the PREVIOUS command's processes with LAG — a dying
# orphan still answers RCON and then dies mid-gallery ("rcon closed" measured
# 2026-10-29). Require: no MC processes AND both ports free AND a settle beat.
for i in $(seq 1 30); do
  BUSY=$(pgrep -c -f "unix_args|bootstraplauncher" 2>/dev/null || echo 0)
  PORT=$(ss -tln | grep -c ":25565 \|:25575 " 2>/dev/null || echo 0)
  [ "$BUSY" = "0" ] && [ "$PORT" = "0" ] && break
  sleep 2
done
pgrep -a -f "unix_args|bootstraplauncher" | head -3
sleep 5

# --- 1. server ---------------------------------------------------------------
cd "$SRV"
rm -f world/session.lock
mkfifo console.in 2>/dev/null || true
setsid bash -c "cd '$SRV' && tail -f console.in | $JDK/java -Xmx1024M @libraries/net/neoforged/neoforge/21.1.265/unix_args.txt --nogui >> boot.log 2>&1" < /dev/null > /dev/null 2>&1 &
for i in $(seq 1 45); do
  R=$(python3 "$ROOT/scripts/cimscene/rcon.py" 127.0.0.1 25575 cimgate "list" 2>/dev/null | tail -1)
  [ -n "$R" ] && break; sleep 2
done
[ -n "$R" ] || { echo "SERVER/RCON FAILED"; tail -20 boot.log; exit 1; }
log "server+RCON up: $R"

# --- 2. pins + gallery -------------------------------------------------------
python3 "$ROOT/scripts/cimscene/rcon.py" 127.0.0.1 25575 cimgate \
  "gamerule doDaylightCycle false" "time set noon" \
  "gamerule doWeatherCycle false" "weather clear" \
  "gamerule doMobSpawning false" "cimscene gallery 8 1440" > "$G/truth-rcon.log" 2>&1
rg -N "gallery built" "$G/truth-rcon.log" || { echo "GALLERY BUILD FAILED"; cat "$G/truth-rcon.log"; exit 1; }
log "gallery built"

# --- 3. client ---------------------------------------------------------------
bash "$ROOT/scripts/cimscene/run-client-walk.sh" truth \
  "$SRV/c-imagine/scenes/gallery.tsv" "$G/truth-shots" 127.0.0.1:25575:cimgate
RC=$?
log "client-walk rc=$RC"
exit $RC
