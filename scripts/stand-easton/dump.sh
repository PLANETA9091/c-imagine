#!/bin/bash
# Run the real NeoForge client once, inside the no-window sandbox, to dump the
# wall's real quads (see scene-mod QuadDump). Args: the scenes dir under ~/cim-stand.
set -euo pipefail
B="$HOME/cim-stand"
export XDG_RUNTIME_DIR="/run/user/$(id -u)"
exec systemd-run --user --wait --collect --quiet \
  -p MemoryMax=6G -p MemorySwapMax=2G -p CPUWeight=1 -p IOWeight=1 -p Nice=19 \
  -p StandardOutput=append:$B/q/dump.out -p StandardError=append:$B/q/dump.out \
  bwrap \
  --ro-bind /usr /usr --symlink usr/bin /bin --symlink usr/lib /lib --symlink usr/lib /lib64 --symlink usr/bin /sbin \
  --ro-bind /etc /etc --proc /proc --dev /dev --tmpfs /tmp --tmpfs /run \
  --bind "$B" /st --dir /home/easton --bind "$B" "$B" \
  --unshare-net --unshare-ipc --unshare-pid --unshare-uts --new-session \
  --clearenv --setenv PATH /st/jdk/bin:/usr/bin --setenv HOME /st/home \
  --setenv DISPLAY :77 --setenv XDG_RUNTIME_DIR /tmp/xdg \
  --setenv LIBGL_ALWAYS_SOFTWARE 1 --setenv GALLIUM_DRIVER llvmpipe \
  /bin/bash /st/dump-driver.sh "/st/$1"
