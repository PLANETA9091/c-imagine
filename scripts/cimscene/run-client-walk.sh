#!/usr/bin/env bash
# Client leg for a RUNNING gallery server (truth or cim) — Xvfb, join, walk.
# The server survives commands via setsid; Xvfb + client too (usually — the
# sandbox reaper is nondeterministic, so the walk runs in the SAME command).
# Usage: run-client-walk.sh <stack:truth|cim> <gallery.tsv> <shots-dir> <rcon h:p:pw>
set -uo pipefail
ROOT=/home/z/my-project/work/c-imagine
G=/tmp/my-project/artifacts/gal
STACK=$1; TSV=$2; SHOTS=$3; RCON=$4
log(){ echo "[$STACK-client] $*"; }

xprobe() { python3 - <<'EOF'
import socket, sys
# AWT (Shot.java) connects via the FILESYSTEM socket only; a zombie Xvfb can
# serve the ABSTRACT socket and pass too-easy probes (measured 2026-10-29).
try:
    s = socket.socket(socket.AF_UNIX); s.connect('/tmp/.X11-unix/X99'); s.close(); sys.exit(0)
except Exception:
    sys.exit(1)
EOF
}

# --- Xvfb -------------------------------------------------------------------
mkdir -p /tmp/.X11-unix 2>/dev/null; chmod 1777 /tmp/.X11-unix 2>/dev/null
if ! xprobe; then
  rm -f /tmp/.X99-lock /tmp/.X11-unix/X99
  pkill -x Xvfb 2>/dev/null; sleep 1
  setsid Xvfb :99 -screen 0 1280x720x24 +extension GLX +render -noreset < /dev/null > "$G/xvfb.log" 2>&1 &
  for i in $(seq 1 10); do xprobe && break; sleep 1; done
  xprobe || { echo "XVFB FAILED"; cat "$G/xvfb.log"; exit 1; }
fi
log "xvfb ok"

export DISPLAY=:99
export LIBGL_ALWAYS_SOFTWARE=1
export LP_NUM_THREADS=2
export VK_ICD_FILENAMES=/tmp/my-project/artifacts/xlibs/x/usr/share/vulkan/icd.d/lvp_icd.json
export VK_LOADER_DEBUG=
export GALLIUM_DRIVER=llvmpipe
export CIM_VTOOLS=$G/vtools
# AWT Robot (Shot.java) needs a jdk WITH libawt_xawt.so (the system java is a
# headless-only JRE) + libXtst (apt libs are not installable without root —
# unpacked to artifacts/xlibs). PATH order matters: gallery-walk calls "java".
export PATH=/tmp/my-project/artifacts/jdk21/bin:$PATH
export LD_LIBRARY_PATH=/tmp/my-project/artifacts/xlibs/x/usr/lib/x86_64-linux-gnu${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}
javac -d "$G/vtools" "$ROOT/scripts/visual/Shot.java" 2>/dev/null

# --- client runtime ---------------------------------------------------------
if [ "$STACK" = truth ]; then
  CLI=$G/truth-client
  # the REAL NeoForge client must carry the SAME mods as the server — the
  # sync-phase registry data contains every modded key and a modless client
  # is kicked ("registries with unknown keys") — measured 2026-10-29
  mkdir -p "$CLI/game/mods"
  SRV_MODS=$G/truth-srv/mods
  cp -f $SRV_MODS/*.jar "$CLI/game/mods/" 2>/dev/null
  log "client mods: $(ls "$CLI/game/mods" 2>/dev/null | tr '\n' ' ')"
else
  CLI=$G/visual-client
fi
mkdir -p "$CLI/game"
# backend pin: 26.3 (renderpearl) tries Vulkan FIRST and dies without
# VK_KHR_surface (mesa-vulkan absent) even with --graphicsBackend opengl on
# the CLI — pin the in-game option too (measured 2026-10-29)
if [ "$STACK" = cim ]; then
  printf 'onboardAccessibility:false\nfov:-0.625\nrenderClouds:false\npreferredGraphicsBackend:opengl\n' > "$CLI/game/options.txt"
else
  printf 'onboardAccessibility:false\nfov:-0.625\nrenderClouds:false\n' > "$CLI/game/options.txt"
fi
python3 "$ROOT/scripts/visual/make_servers_dat.py" "$CLI/game" || exit 1
LAUNCH=$(cat "$CLI/launch.txt")
# the 26.3 client is class 69 (Java 25); the truth 1.21.1 client is Java 21.
# PATH stays jdk21-first for the AWT Shot tool — pin the CLIENT's java here.
if [ "$STACK" = cim ]; then
  LAUNCH=$(echo "$LAUNCH" | sed "s|^java |$G/../../artifacts/jdk25/bin/java |")
fi
rm -f "$CLI/client.out"
RHOST=${RCON%%:*}; REST=${RCON#*:}; RPORT=${REST%%:*}; RPW=${REST#*:}
# ghost guard, STRICT: kick any previous cimviewer and WAIT for a truly empty
# roster — a ghost that answers "list" but vanishes at "tp" poisons the walk
# (measured 2026-10-29: tp -> "No entity was found", shots of nothing)
for i in $(seq 1 15); do
  python3 "$ROOT/scripts/cimscene/rcon.py" "$RHOST" "$RPORT" "$RPW" "kick cimviewer Ghost" >/dev/null 2>&1
  L=$(python3 "$ROOT/scripts/cimscene/rcon.py" "$RHOST" "$RPORT" "$RPW" "list" 2>/dev/null | tail -1)
  case "$L" in *"0 of a max"*) break;; esac
  sleep 2
done
case "$L" in *"0 of a max"*) log "roster empty: $L";; *) log "roster NOT empty: $L"; exit 1;; esac
sleep 3
setsid bash -c "cd '$CLI' && $LAUNCH > client.out 2>&1" < /dev/null &
log "client launching"

ONLINE=""
for i in $(seq 1 100); do
  ONLINE=$(python3 "$ROOT/scripts/cimscene/rcon.py" "$RHOST" "$RPORT" "$RPW" "list" 2>/dev/null | tail -1)
  case "$ONLINE" in *cimviewer*) break;; esac
  sleep 2
done
case "$ONLINE" in
  *cimviewer*) log "player online: $ONLINE";;
  *) log "JOIN FAILED — last rcon: $ONLINE"; tail -15 "$CLI/client.out" 2>/dev/null;
     DISPLAY=:99 java -cp "$G/vtools" Shot "$G/${STACK}-join-stall.png" 2>/dev/null || true; exit 1;;
esac
# world-ready: "player online" fires while the CLIENT is still loading terrain
# — the first tp lands server-side but the camera catches up late and the shot
# frames the wrong range (measured 2026-10-29, wall_0 vs wall_1 drift). Wait
# for the client-side world-join marker, then settle.
for i in $(seq 1 30); do
  grep -qE "Loaded [0-9]+ advancements" "$CLI/client.out" 2>/dev/null && break
  sleep 2
done
sleep 10  # settle: first chunk render + light (llvmpipe is slow)

# --- walk -------------------------------------------------------------------
cd "$ROOT"
python3 scripts/cimscene/gallery-walk.py "$TSV" "rcon:$RHOST:$RPORT:$RPW" "$SHOTS" || exit 1
log "shots: $(ls "$SHOTS" 2>/dev/null | wc -l)"
log "DONE"
