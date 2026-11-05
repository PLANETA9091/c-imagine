#!/bin/bash
set -uo pipefail
for s in /run/user/1000/wayland-1 /run/user/1000/bus /tmp/.X11-unix/X0 /tmp/.X11-unix/X1; do
  [ -e "$s" ] && { echo "ABORT: real display socket visible: $s"; exit 99; }
done
mkdir -p /tmp/xdg /tmp/.X11-unix; chmod 700 /tmp/xdg; chmod 1777 /tmp/.X11-unix
__EGL_VENDOR_LIBRARY_FILENAMES=/usr/share/glvnd/egl_vendor.d/50_mesa.json \
  Xvfb :77 -screen 0 1280x720x24 +extension GLX +render -noreset -nolisten tcp > /st/q/xvfb-dump.out 2>&1 &
sleep 2
export __EGL_VENDOR_LIBRARY_FILENAMES=/usr/share/glvnd/egl_vendor.d/50_mesa.json
cd "$HOME/../nfc-work/truth-client" 2>/dev/null || cd /st/nfc-work/truth-client
L=$(sed "s#^java #java -Xmx2G -Dcim.quaddump=$1 #" launch.txt)
timeout 600 bash -c "$L" > /st/q/dump-client.out 2>&1
echo "client rc=$?"; ls "$1/quads" | wc -l; cat "$1/quads/done" 2>/dev/null
