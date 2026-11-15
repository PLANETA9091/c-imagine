#!/usr/bin/env bash
# Require a real post-window receipt; a crash or inconclusive launch is failure.
set -euo pipefail
launch=$(realpath "$1")
backend=$2
output=$(realpath -m "$3")
case "$backend" in opengl|vulkan) ;; *) echo "unsupported backend: $backend" >&2; exit 2;; esac
limit=${CIM_PROBE_SECONDS:-90}
probe_launch=$(mktemp "$(dirname "$launch")/.cim-window-probe.XXXXXX")
probe_pid=
cleanup() {
  if [ -n "$probe_pid" ]; then
    kill -TERM -- "-$probe_pid" 2>/dev/null || true
    wait "$probe_pid" 2>/dev/null || true
  fi
  rm -f "$probe_launch"
}
trap cleanup EXIT
sed -E "s/--graphicsBackend (opengl|vulkan)/--graphicsBackend $backend/g" "$launch" > "$probe_launch"
grep -q -- "--graphicsBackend $backend" "$probe_launch"
mkdir -p "$(dirname "$output")"
cd "$(dirname "$launch")"
setsid timeout "$limit" env SDL_VIDEO_DRIVER=x11 SDL_VIDEO_FORCE_EGL=1 EGL_PLATFORM=x11 \
  LIBGL_ALWAYS_SOFTWARE=1 GALLIUM_DRIVER=llvmpipe bash "$probe_launch" > "$output" 2>&1 &
probe_pid=$!
for ((elapsed=0; elapsed<limit; elapsed++)); do
  if grep -qE 'Sound engine started|Created: .*atlas' "$output" 2>/dev/null; then
    echo "PROBE: post-window readiness on $backend"
    exit 0
  fi
  if grep -qE 'BackendCreationException|Unable to initialize SDL|opengl_missing|Game crashed!' "$output" 2>/dev/null \
      || ! kill -0 "$probe_pid" 2>/dev/null; then
    echo "PROBE: client failed before window readiness on $backend" >&2
    tail -25 "$output" >&2
    exit 1
  fi
  sleep 1
done
echo "PROBE: no positive window receipt within ${limit}s on $backend" >&2
exit 1
