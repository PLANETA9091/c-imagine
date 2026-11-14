#!/usr/bin/env bash
# ci-crussty-pin.sh <srv-dir> [init-log]
#
# WHY THIS EXISTS (measured 2026-11-14, integration run 43611950326 = red):
# `crussty init` resolves the launcher release at runtime via the GitHub API
# (releases/latest). When the API is rate-limited/unreachable from the runner,
# the CLI SILENTLY FALLS BACK to the hardcoded v2.2.5. That launcher's runtime
# agent (libcrussty_runtime.so, the JVMTI rewriter behind dev.crussty.hooks.
# NetHooks) patches ServerHandshakePacketListenerImpl.handleIntention with a
# MISALIGNED tableswitch — the case targets and the StackMapTable frames land
# inside the switch's own constant data (decoded: opcode @34, data @34..59,
# same_frame(@59) points at the last byte of npair-3's offset) → the JVM
# refuses the class with `java.lang.VerifyError: Bad instruction` at the FIRST
# bot channel init → the bot sees `Connection reset by peer` with 0 packets
# read → the M4 wire contract asserts fail.
# v2.9.1's rewriter carries the stackmap/instruction-boundary guards (strings:
# "truncated instruction at offset", "StackMapTable attribute extends past end
# of class file") and is the measured-good launcher on every green boot of
# 2026-11-14 (integration 285e42dc/e384479b/2d0c08dc/e6fc499f all resolved it).
#
# CONTRACT: run AFTER `crussty init <dir>`. Forces the PINNED tag's artifacts
# over whatever init fetched, verifies them, prints a sha256 receipt. Fails
# loudly (exit 1) when the pinned artifacts cannot be produced — a silent
# fallback to a broken launcher is exactly the failure this script exists for.
#
# Env: CRUSSTY_LAUNCHER_TAG (default v2.9.1), GH_TOKEN (gh auth).

set -euo pipefail

DIR="${1:?usage: ci-crussty-pin.sh <srv-dir> [init-log]}"
INIT_LOG="${2:-}"
PIN="${CRUSSTY_LAUNCHER_TAG:-v2.9.1}"
REPO="PLANETA9091/CRUSSTY"

LAUNCHER="$DIR/launcher/launcher.jar"
RUNTIME="$DIR/libcrussty_runtime.so"

resolved=""
if [ -n "$INIT_LOG" ] && [ -f "$INIT_LOG" ]; then
  resolved=$(grep -oE 'releases/download/v[0-9.]+' "$INIT_LOG" | head -1 | sed 's#releases/download/##' || true)
fi
echo "crussty-pin: dir=$DIR init-resolved=${resolved:-<none>} pinned=$PIN"

fetch_pinned() {
  local dest_dir="$1" asset="$2"
  if command -v gh >/dev/null 2>&1 && [ -n "${GH_TOKEN:-}${GITHUB_TOKEN:-}" ]; then
    gh release download "$PIN" -R "$REPO" -p "$asset" -D "$dest_dir" --clobber && return 0
  fi
  # gh unavailable/unauthenticated — the release assets are public; curl works
  # without auth (measured 2026-11-14: both artifacts fetched this way).
  local url="https://github.com/$REPO/releases/download/$PIN/$asset"
  local out="$dest_dir/$asset"
  curl -fL --retry 3 --retry-delay 2 -o "$out" "$url" || return 1
  [ -s "$out" ]
}

need_fetch=0
if [ "${resolved:-}" != "$PIN" ]; then
  echo "crussty-pin: forcing pinned launcher (resolved ${resolved:-none} != $PIN — the v2.2.5 fallback carries the broken handleIntention tableswitch patch)"
  need_fetch=1
fi
[ -s "$LAUNCHER" ] || need_fetch=1
[ -s "$RUNTIME" ] || need_fetch=1

if [ "$need_fetch" = "1" ]; then
  mkdir -p "$DIR/launcher"
  fetch_pinned "$DIR/launcher" launcher.jar || {
    echo "crussty-pin: FAILED to download pinned launcher.jar ($PIN)" >&2
    exit 1
  }
  fetch_pinned "$DIR" libcrussty_runtime.so || {
    echo "crussty-pin: FAILED to download pinned libcrussty_runtime.so ($PIN)" >&2
    exit 1
  }
fi

[ -s "$LAUNCHER" ] || { echo "crussty-pin: $LAUNCHER missing/empty" >&2; exit 1; }
[ -s "$RUNTIME" ] || { echo "crussty-pin: $RUNTIME missing/empty" >&2; exit 1; }
unzip -tqq "$LAUNCHER" >/dev/null || { echo "crussty-pin: $LAUNCHER is not a valid jar" >&2; exit 1; }

echo "crussty-pin: receipt (sha256)"
sha256sum "$LAUNCHER" "$RUNTIME"
echo "crussty-pin: OK — pinned $PIN launcher in place"
