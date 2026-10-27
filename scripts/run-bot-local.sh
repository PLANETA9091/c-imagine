#!/usr/bin/env bash
# M4 local loop: boot bare Paper 26.3 + run the cimbot protocol bot against it.
# One invocation = one full cycle (background processes die between tool calls
# in this sandbox, so server + bot + assertions live in a single command).
#
#   scripts/run-bot-local.sh [--join-only] [--keep-server]
set -uo pipefail

PAPER_DIR=${PAPER_DIR:-/home/z/my-project/artifacts/paper263}
JDK25=${JDK25:-/home/z/my-project/artifacts/jdk25/bin/java}
REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
BOT="$REPO/target/release/cimbot"
BOT_LOG=/tmp/cimbot.log
SERVER_TIMEOUT=${SERVER_TIMEOUT:-420}
BOT_TIMEOUT=${BOT_TIMEOUT:-110}

JOIN_ONLY=0
KEEP=0
for a in "$@"; do
  case "$a" in
    --join-only) JOIN_ONLY=1 ;;
    --keep-server) KEEP=1 ;;
  esac
done

cd "$PAPER_DIR" || exit 2

cleanup() {
  if [ "$KEEP" != "1" ]; then
    pkill -f "paperclip.jar" 2>/dev/null
    for i in $(seq 1 20); do pgrep -f paperclip >/dev/null || break; sleep 1; done
  fi
}
trap cleanup EXIT

if ! pgrep -f paperclip >/dev/null; then
  echo "=== booting Paper 26.3 ==="
  "$JDK25" -Xmx1500m -jar paperclip.jar --nogui > server.log 2>&1 &
  SRV_PID=$!
  UP=0
  for i in $(seq 1 "$SERVER_TIMEOUT"); do
    if grep -q "Done (" server.log 2>/dev/null; then UP=1; break; fi
    if ! kill -0 "$SRV_PID" 2>/dev/null; then echo "SERVER DIED:"; tail -20 server.log; exit 3; fi
    sleep 1
  done
  [ "$UP" = "1" ] || { echo "server never reached Done:"; tail -20 server.log; exit 3; }
  grep "Done (" server.log | tail -1
else
  echo "=== reusing running server ==="
fi

if [ ! -x "$BOT" ]; then
  echo "cimbot missing, building (release)..."
  (cd "$REPO" && "$HOME/.cargo/bin/cargo" build --release -p cim-proto --bin cimbot 2>&1 | tail -2)
fi

ARGS=(--host 127.0.0.1 --port 25565 --name cimbot --timeout "$BOT_TIMEOUT" --report /tmp/bot-report.json)
if [ "$JOIN_ONLY" = "1" ]; then ARGS+=(--join-only --join-grace 12); fi

echo "=== running cimbot (args: ${ARGS[*]}) ==="
"$BOT" "${ARGS[@]}" > "$BOT_LOG" 2>&1
CODE=$?
echo "=== cimbot exit=$CODE ==="
cat "$BOT_LOG"

if [ "$KEEP" != "1" ]; then
  pkill -f "paperclip.jar" 2>/dev/null
  for i in $(seq 1 20); do pgrep -f paperclip >/dev/null || break; sleep 1; done
fi

echo "=== server log tail ==="
grep -E "cimbot|joined the game|logged in|lost connection|Done \(" server.log 2>/dev/null | tail -8
exit $CODE
