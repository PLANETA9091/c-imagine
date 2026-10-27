#!/usr/bin/env python3
"""Fetch a vanilla Minecraft client RUNTIME for the M5 visual harness.

Assembles everything the stock client needs to launch headlessly under xvfb
from the official piston-meta manifest (measured sources, no third parties):

  outDir/client.jar          the version's client jar
  outDir/libraries/<path>    every OS-applicable library (incl. natives-linux)
  outDir/assets/indexes/<id>.json + assets/objects/<h[:2]>/<h>  (with --assets)
  outDir/classpath.txt       colon-joined classpath (client.jar first)
  outDir/launch.txt          ready java command line (with placeholders resolved)
  outDir/summary.json        machine-readable manifest of what was fetched

Usage:
  fetch-visual-client.sh is a thin alias: fetch-visual-client.py <version> <outDir> [--assets] [--dry-run]

--dry-run resolves everything and prints sizes WITHOUT downloading (CI sanity
+ local light checks).
"""
import argparse
import json
import os
import pathlib
import sys
import urllib.request

MANIFEST = "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json"
RESOURCES = "https://resources.download.minecraft.net"


def http_get(url: str) -> bytes:
    req = urllib.request.Request(url, headers={"User-Agent": "c-imagine-visual-harness/1.0"})
    with urllib.request.urlopen(req, timeout=120) as r:
        return r.read()


def fetch_to(url: str, path: pathlib.Path) -> int:
    data = http_get(url)
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(data)
    return len(data)


def os_name() -> str:
    if sys.platform == "darwin":
        return "osx"
    if sys.platform.startswith("win"):
        return "windows"
    return "linux"


def rule_ok(rules: list | None, our_os: str) -> bool:
    """Mojang rule evaluation: last matching rule wins; absence = allow."""
    if not rules:
        return True
    allowed = False
    for rule in rules:
        action = rule.get("action")
        os_rule = rule.get("os")
        if os_rule is None or os_rule.get("name") == our_os:
            allowed = action == "allow"
    return allowed


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("version")
    ap.add_argument("out")
    ap.add_argument("--assets", action="store_true",
                    help="download the full asset index + objects (heavy)")
    ap.add_argument("--dry-run", action="store_true")
    args = ap.parse_args()

    # ABSOLUTE paths everywhere: the launch line must survive any cwd
    # (measured: relative classpath + cd visual-client => ClassNotFound)
    out = pathlib.Path(args.out).resolve()
    manifest = json.loads(http_get(MANIFEST))
    entry = next((v for v in manifest["versions"] if v["id"] == args.version), None)
    if entry is None:
        print(f"ERROR: version {args.version} not in the piston manifest", file=sys.stderr)
        return 1
    vjson = json.loads(http_get(entry["url"]))
    our_os = os_name()

    # --- client jar -----------------------------------------------------------
    client_url = vjson["downloads"]["client"]["url"]
    client_size = vjson["downloads"]["client"]["size"]

    # --- libraries ------------------------------------------------------------
    libs = []          # (url, relpath, size)
    for lib in vjson.get("libraries", []):
        if not rule_ok(lib.get("rules"), our_os):
            continue
        dl = lib.get("downloads", {})
        art = dl.get("artifact")
        if art and art.get("url"):
            libs.append((art["url"], art["path"], art.get("size", 0)))
        natives = dl.get(f"natives-{our_os}")
        if natives and natives.get("url"):
            libs.append((natives["url"], natives["path"], natives.get("size", 0)))
    lib_total = sum(s for _, _, s in libs)

    # --- assets ----------------------------------------------------------------
    asset_index = vjson.get("assetIndex") or {}
    asset_id = asset_index.get("id")
    asset_total = asset_index.get("totalSize", 0)
    index_url = asset_index.get("url")

    summary = {
        "version": args.version,
        "mainClass": vjson["mainClass"],
        "client": {"url": client_url, "size": client_size},
        "libraries": {"count": len(libs), "bytes": lib_total},
        "assets": {"id": asset_id, "index_url": index_url,
                   "total_size": asset_total, "downloaded": args.assets},
        "jvm_args": vjson.get("arguments", {}).get("jvm", []),
        "game_args": vjson.get("arguments", {}).get("game", []),
        "java_major": vjson.get("javaVersion", {}).get("majorVersion"),
    }

    print(f"version {args.version}: client {client_size/1e6:.1f} MB, "
          f"{len(libs)} libraries {lib_total/1e6:.1f} MB, "
          f"assets[{asset_id}] {asset_total/1e6:.1f} MB, "
          f"java {summary['java_major']}")

    if args.dry_run:
        print(json.dumps(summary, indent=2)[:2000])
        return 0

    out.mkdir(parents=True, exist_ok=True)
    cp = [str(out / "client.jar")]
    n_dl = 0
    if not (out / "client.jar").exists() or (out / "client.jar").stat().st_size != client_size:
        fetch_to(client_url, out / "client.jar")
        n_dl += 1
    for url, rel, size in libs:
        target = out / "libraries" / rel
        if not target.exists() or (size and target.stat().st_size != size):
            fetch_to(url, target)
            n_dl += 1
        cp.append(str(target))
    (out / "classpath.txt").write_text(":".join(cp))

    if args.assets:
        index = json.loads(http_get(index_url))
        fetch_to(index_url, out / "assets" / "indexes" / f"{asset_id}.json")
        objects = index.get("objects", {})
        for name, obj in objects.items():
            h = obj["hash"]
            target = out / "assets" / "objects" / h[:2] / h
            if target.exists() and target.stat().st_size == obj.get("size"):
                continue
            fetch_to(f"{RESOURCES}/{h[:2]}/{h}", target)
            n_dl += 1
        print(f"assets: {len(objects)} objects fetched/verified")

    # --- launch line -------------------------------------------------------------
    jvm = " ".join(a for a in summary["jvm_args"] if isinstance(a, str))
    resolved_jvm = (jvm
                    .replace("${natives_directory}", str(out / "natives"))
                    .replace("${launcher_name}", "c-imagine")
                    .replace("${launcher_version}", "1")
                    .replace("${classpath}", (out / "classpath.txt").read_text())
                    .replace("${classpath_separator}", ":")
                    .replace("${library_directory}", str(out / "libraries"))
                    .replace("${version_name}", args.version))
    game = " ".join(a for a in summary["game_args"] if isinstance(a, str))
    resolved_game = (game
                     .replace("${auth_player_name}", "cimviewer")
                     .replace("${version_name}", args.version)
                     .replace("${game_directory}", str(out / "game"))
                     .replace("${assets_root}", str(out / "assets"))
                     .replace("${assets_index_name}", asset_id or "")
                     .replace("${auth_uuid}", "00000000-0000-0000-0000-000000000000")
                     .replace("${auth_access_token}", "0")
                     .replace("${user_type}", "legacy")
                     .replace("${version_type}", vjson.get("type", "release"))
                     .replace("${resolution_width}", "1280")
                     .replace("${resolution_height}", "720")
                     .replace("${clientid}", "c-imagine")
                     .replace("${auth_xuid}", "0")
                     .replace("${user_properties}", "{}"))
    # MEASURED (visual run 3): 26.3 renders through the new "renderpearl"
    # stack — the Vulkan backend dies on CI runners (no ICD); the client
    # ships an OpenGL backend switchable by launch argument
    # (net.minecraft.client.PreferredGraphicsApi: default/vulkan/opengl).
    launch = (f"java {resolved_jvm} {vjson['mainClass']} {resolved_game} "
              f"--graphicsBackend opengl "
              f"--quickPlayMultiplayer 127.0.0.1:25565")
    (out / "launch.txt").write_text(launch + "\n")

    (out / "summary.json").write_text(json.dumps(summary, indent=2))
    print(f"fetched {n_dl} files -> {out}")
    print(f"launch: {launch[:200]}...")
    return 0


if __name__ == "__main__":
    sys.exit(main())
