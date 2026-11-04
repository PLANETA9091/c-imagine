#!/bin/bash
# Hard no-window stand for easton-desktop: run EVERYTHING inside one bwrap.
# Private /run and /tmp hide wayland-1 and X0; --unshare-net hides the abstract
# X socket and keeps RCON/server off every real network. No /dev/dri: llvmpipe,
# same as CI. Capped and lowest priority: the desktop always wins.
set -euo pipefail
B="$HOME/cim-stand"
mkdir -p "$B/home" "$B/q/in" "$B/q/out" "$B/shots"
export XDG_RUNTIME_DIR="/run/user/$(id -u)"
systemctl --user reset-failed cim-stand 2>/dev/null || true
exec systemd-run --user --unit=cim-stand --collect --quiet \
  -p MemoryMax=8G -p MemorySwapMax=2G -p CPUWeight=1 -p IOWeight=1 -p Nice=19 \
  -p StandardOutput=append:$B/q/run.out -p StandardError=append:$B/q/run.out \
  bwrap \
  --ro-bind /usr /usr --symlink usr/bin /bin --symlink usr/lib /lib --symlink usr/lib /lib64 --symlink usr/bin /sbin \
  --ro-bind /etc /etc --proc /proc --dev /dev --tmpfs /tmp --tmpfs /run \
  --bind "$B" /st \
  --dir /home/btw/stand --dir /home/btw/ci74/clients \
  --bind "$B/srv" /home/btw/stand/srv --bind "$B/client" /home/btw/ci74/clients/v263 \
  --unshare-net --unshare-ipc --unshare-pid --unshare-uts --new-session \
  --clearenv --setenv PATH /st/jdk/bin:/usr/bin --setenv HOME /st/home \
  --setenv DISPLAY :77 --setenv XDG_RUNTIME_DIR /tmp/xdg \
  --setenv LIBGL_ALWAYS_SOFTWARE 1 --setenv GALLIUM_DRIVER llvmpipe \
  /bin/bash /st/driver.sh
