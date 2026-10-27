#!/usr/bin/env bash
# M4 FULL local loop: CRUSSTY Paper 26.3 + c-imagine module + cimtest mods +
# the protocol bot — the exact integration.yml boot job, on the sandbox.
set -uo pipefail

SRV=${SRV:-/home/z/my-project/artifacts/srv-m4}
REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
BOT="$REPO/target/release/cimbot"
BOT_LOG=/tmp/cimbot-m4.log
JDK25=${JDK25:-/home/z/my-project/artifacts/jdk25}
export JAVA_HOME="$JDK25"
export PATH="$JDK25/bin:$PATH"

cd "$SRV" || exit 2

cleanup() {
  crussty stop >/dev/null 2>&1 || true
  for i in $(seq 1 45); do pgrep -f onload.pidFile >/dev/null || break; sleep 2; done
  pgrep -f onload.pidFile >/dev/null && { pkill -9 -f onload.pidFile || true; sleep 5; }
}
trap cleanup EXIT

echo "=== booting CRUSSTY stack (module + plugin + mods) ==="
timeout 420 crussty run > boot.log 2>&1 &
SRV_PID=$!

UP=0
for i in $(seq 1 300); do
  if grep -q "Done (" boot.log 2>/dev/null; then UP=1; break; fi
  kill -0 "$SRV_PID" 2>/dev/null || { echo "SERVER DIED:"; tail -30 boot.log; exit 3; }
  sleep 1
done
[ "$UP" = "1" ] || { echo "no Done marker:"; tail -30 boot.log; exit 3; }
grep "Done (" boot.log | tail -1

SMOKE=0
for i in $(seq 1 120); do
  if grep -q "bridge smoke: ALL OK" boot.log 2>/dev/null; then SMOKE=1; break; fi
  sleep 1
done
echo "smoke ALL OK: $SMOKE"
grep "f2v net leg: arm=" boot.log | tail -1

echo "=== running cimbot (full contract) ==="
"$BOT" --host 127.0.0.1 --port 25565 --name cimbot --timeout 110 \
  --report /tmp/bot-report-m4.json > "$BOT_LOG" 2>&1
CODE=$?
echo "=== cimbot exit=$CODE ==="
grep -E "cimbot\]" "$BOT_LOG" | grep -v dump | tail -25

sleep 8

echo "=== f2v net markers in boot.log ==="
grep -E "f2v net" boot.log | tail -12

echo "=== bot report ==="
python3 - <<'PY'
import json
try:
    r = json.load(open('/tmp/bot-report-m4.json'))
    print("ok:", r["ok"], "| joined:", r["joined"], "| registries:", r["registries_parsed"],
          "| entity_types:", r["entity_type_count"])
    for c in r["checks"]:
        print(f"  [{'PASS' if c['passed'] else 'FAIL'}] {c['name']} {c['detail']}")
    for o in r["observations"]:
        print(f"  obs: {o['kind']} {o['detail']}")
except Exception as e:
    print("report read failed:", e)
PY

exit $CODE
