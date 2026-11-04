#!/bin/bash
# Runs INSIDE the sandbox. Refuses to continue if a real display is reachable.
set -uo pipefail
for s in /run/user/1000/wayland-1 /run/user/1000/bus /tmp/.X11-unix/X0 /tmp/.X11-unix/X1; do
  [ -e "$s" ] && { echo "ABORT: real display socket visible: $s"; exit 99; }
done
[ "$DISPLAY" = ":77" ] || { echo "ABORT: DISPLAY=$DISPLAY"; exit 99; }
[ -z "${WAYLAND_DISPLAY:-}" ] || { echo "ABORT: WAYLAND_DISPLAY set"; exit 99; }
mkdir -p /tmp/xdg; chmod 700 /tmp/xdg
export SDL_VIDEODRIVER=x11   # the 26.3 client is SDL-based: never let it pick a driver
# the client asks for an sRGB framebuffer; this Xvfb's GLX has no such config, Mesa EGL has
export SDL_VIDEO_FORCE_EGL=1 EGL_PLATFORM=x11
mkdir -p /tmp/.X11-unix; chmod 1777 /tmp/.X11-unix
# no /dev/nvidia* in here: libEGL_nvidia segfaults Xvfb, so pin the Mesa vendor
export __EGL_VENDOR_LIBRARY_FILENAMES=/usr/share/glvnd/egl_vendor.d/50_mesa.json
Xvfb :77 -screen 0 1280x720x24 +extension GLX +render -noreset -nolisten tcp > /st/q/xvfb.out 2>&1 &
for i in $(seq 1 40); do [ -S /tmp/.X11-unix/X77 ] && break; sleep 0.25; done
[ -S /tmp/.X11-unix/X77 ] || { echo "ABORT: no Xvfb"; exit 98; }
echo "$(date +%T) sandbox up, Xvfb :77 private"
cd /home/btw/stand/srv
: > /st/q/console.in
tail -f /st/q/console.in | java -Xmx2G -Dcim.f2v.fastBoot=true -jar paper.jar --nogui > /st/q/paper.out 2>&1 &
RC="python3 /st/rcon.py 127.0.0.1 25575 cimgate"
for i in $(seq 1 240); do grep -q 'Done (' /st/q/paper.out 2>/dev/null && break; sleep 1; done
grep -q 'Done (' /st/q/paper.out || { echo "ABORT: paper did not boot"; tail -5 /st/q/paper.out; exit 97; }
echo "$(date +%T) paper up"
cd /home/btw/ci74/clients/v263
L=$(sed 's/^java /java -Xmx3G /' launch.txt)
printf 'onboardAccessibility:false\nfov:-0.65\nrenderClouds:false\npreferredGraphicsBackend:opengl\nresourcePacks:["vanilla","file/objtest.zip"]\n' > game/options.txt
setsid bash -c "$L > /st/q/client.out 2>&1" </dev/null >/dev/null 2>&1 &
for i in $(seq 1 180); do $RC list 2>/dev/null | grep -q cimviewer && break; sleep 1; done
$RC list 2>/dev/null | tail -1
echo "$(date +%T) ready; queue /st/q/in"
# one job = one script dropped in q/in; output in q/out/<name>.out
while [ ! -e /st/q/stop ]; do
  for j in /st/q/in/*.sh; do
    [ -e "$j" ] || continue
    n=$(basename "$j" .sh); bash "$j" > "/st/q/out/$n.out" 2>&1; echo "rc=$?" >> "/st/q/out/$n.out"; mv "$j" "/st/q/out/$n.done"
  done
  sleep 1
done
echo "$(date +%T) stop requested"
