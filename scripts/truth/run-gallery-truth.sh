#!/usr/bin/env bash
# Truth-leg gallery screenshot session (LOCAL single-command variant).
# The sandbox reaps background processes between tool calls, so the whole
# session (Xvfb + client + join + pins + page screenshots) must live inside
# ONE command. In CI this logic is spread across the parity-diff/mod-matrix
# jobs instead.
#
# Usage: run-gallery-truth.sh <pages>   (default 3)
set -uo pipefail
export JAVA_HOME=/tmp/my-project/artifacts/jdk21
export PATH=$JAVA_HOME/bin:$PATH
A=/tmp/my-project/artifacts
REPO=/home/z/my-project/work/c-imagine
GAMEDIR=$A/truth-client
SRV=$A/srv-gallery
SHOTS=$GAMEDIR/shots
PAGES="${1:-3}"

export XDG_RUNTIME_DIR=/tmp/xdg
mkdir -p "$XDG_RUNTIME_DIR" "$SHOTS"
export DISPLAY=:99
export LIBGL_ALWAYS_SOFTWARE=1
export GALLIUM_DRIVER=llvmpipe
export LD_LIBRARY_PATH=/tmp/xdebs/root/usr/lib/x86_64-linux-gnu:${LD_LIBRARY_PATH:-}

# ---- truth SERVER (must live inside the same command: the sandbox reaps
# background processes between tool calls) ------------------------------------
cd "$SRV"
rm -f console.in boot.log
mkfifo console.in
tail -f console.in | MALLOC_ARENA_MAX=2 java -Xmx2G @user_jvm_args.txt \
    @libraries/net/neoforged/neoforge/21.1.265/unix_args.txt nogui > boot.log 2>&1 &
SPID=$!
echo "server pid $SPID"
for i in $(seq 1 60); do
  grep -q 'Done (.*! For help, type "help")' "$SRV/boot.log" 2>/dev/null && break
  kill -0 $SPID 2>/dev/null || { echo "SERVER DIED"; tail -20 "$SRV/boot.log"; exit 1; }
  sleep 5
done
grep -q 'Done (.*! For help' "$SRV/boot.log" || { echo "SERVER NOT DONE"; tail -10 "$SRV/boot.log"; exit 1; }
echo "SERVER UP"

Xvfb :99 -screen 0 1280x720x24 +extension GLX +render -noreset > /tmp/xvfb.log 2>&1 &
XVPID=$!
sleep 3
java -cp "$REPO/vtools" Shot "$SHOTS/probe.png" || { echo "DISPLAY FAIL"; exit 1; }

cd "$GAMEDIR"
bash -c "$(cat launch.txt)" > client.out 2>&1 &
CPID=$!
echo "client pid $CPID, xvfb pid $XVPID"

JOINED=0
for i in $(seq 1 60); do
  if grep -q "logged in with entity id" "$SRV/boot.log" 2>/dev/null \
     && grep -q "Loaded .* advancements" client.out 2>/dev/null; then JOINED=1; break; fi
  kill -0 $CPID 2>/dev/null || break
  sleep 5
done
if [ "$JOINED" != "1" ]; then echo "NOT JOINED"; tail -30 client.out; kill $XVPID 2>/dev/null; exit 1; fi
echo "JOINED"
sleep 30   # chunks stream in under llvmpipe

# camera-lock environment pins (camera-lock-26-3.md): plain + absolute
echo "gamerule doDaylightCycle false" > "$SRV/console.in"
echo "time set noon" > "$SRV/console.in"
echo "gamerule doWeatherCycle false" > "$SRV/console.in"
echo "weather clear" > "$SRV/console.in"
echo "gamerule doMobSpawning false" > "$SRV/console.in"
sleep 2
echo "kill @e[type=!player]" > "$SRV/console.in"
echo "tp cimviewer 0.5 -60 0.5 0 0" > "$SRV/console.in"
sleep 5
java -cp "$REPO/vtools" Key F1
sleep 1

# gallery page → wait for a NEW done marker (count-based: pages repeat) → shot
wait_page_done() {
  local p=$1
  local before
  before=$(grep -c "gallery done ns=create page=$p " "$SRV/boot.log")
  echo "cimscene gallery page create $p" > "$SRV/console.in"
  for i in $(seq 1 30); do
    local now
    now=$(grep -c "gallery done ns=create page=$p " "$SRV/boot.log")
    [ "$now" -gt "$before" ] && return 0
    sleep 1
  done
  return 1
}

for p in $(seq 0 $((PAGES-1))); do
  if wait_page_done "$p"; then
    sleep 4   # section rebuild + lighting under llvmpipe
    java -cp "$REPO/vtools" Shot "$SHOTS/gal-p$p.png"
    echo "shot page $p"
  else
    echo "page $p NO MARKER"; break
  fi
done

# determinism probe: re-place page 0, shoot again — must equal gal-p0.png
if wait_page_done 0; then
  sleep 4
  java -cp "$REPO/vtools" Shot "$SHOTS/gal-p0-again.png"
  echo "shot page 0 again"
fi

ls -la "$SHOTS"
kill $CPID 2>/dev/null
sleep 2
kill $SPID $XVPID 2>/dev/null
echo "TRUTH GALLERY SESSION DONE"
