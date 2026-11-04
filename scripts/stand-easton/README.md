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
  no such config. The EGL path measures equal to CI (below); the
  gallery stays the verdict.

## Parity with CI, measured

Same seed pack, plugin, manifests, server.properties and commands as the ours leg, compared
pixel by pixel with CI's ours shots (run 41892119307, pages 105/113/121 and item page 1):
the wall is identical; 98% of all pixels are exact; mean difference 0.05 of 765; no shift;
no block-page pixel over 30 beyond a handful. What remains is faint noise on the floor
texture. The stand's verdicts can be trusted for iteration, using `gallery-diff.py` against
CI truth frames.
