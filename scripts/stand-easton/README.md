# Headless 26.3 client stand on easton-desktop

`tailscale ssh easton@easton-desktop` (plain `ssh easton` does not resolve). That
machine is a live desktop: NOTHING may open a window on its screen. Everything runs
inside one bwrap with private /run and /tmp and its own network namespace, so the
compositor sockets do not exist in there; `driver.sh` aborts if it sees one.

Layout under `~/cim-stand`: `jdk/`, `srv/` (Paper; `plugins/` moved aside for pack-only
tests), `client/` (mounted at the paths `launch.txt` expects), `sandbox.sh`, `driver.sh`,
`rcon.py`, `vtools/` (Shot, Key). The relay link is ~0.5 MB/s, so copy once. The client
needs no `assets/objects`: textures come from client.jar.

Start: `bash sandbox.sh` (a capped, lowest-priority user service, `cim-stand`). Drop a
job script in `q/in/`; output lands in `q/out/`. Stop: `systemctl --user stop cim-stand`.
Stop it when idle.

Three things the client needs here, each found the hard way:
- `__EGL_VENDOR_LIBRARY_FILENAMES` -> Mesa: libEGL_nvidia segfaults Xvfb without /dev/nvidia*.
- `SDL_VIDEODRIVER=x11`: the 26.3 client is SDL-based and picks Wayland when it can.
- `SDL_VIDEO_FORCE_EGL=1`: the client requests an sRGB framebuffer, and this Xvfb's GLX has
  no such config. This path differs from CI's GLX, so use the stand for iteration; the
  gallery stays the verdict.
