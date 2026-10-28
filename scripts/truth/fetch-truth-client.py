#!/usr/bin/env python3
"""Fetch + assemble the REAL NeoForge 1.21.1 client for the parity-diff job.

The ground-truth side of the F2F owner directive ("modded content must render
on the stock vanilla client IDENTICALLY to the modded client"): this script
prepares a REAL NeoForge 21.1.265 client with the cimtest mod installed, to be
launched under xvfb against a real NeoForge server. The screenshots it
produces are the pixel ground truth the c-imagine visual run is diffed against.

Measured launch contract (all javap/decompile-grounded, 2026-10-28):

  - installer --installClient produces:
      versions/1.21.1/1.21.1.json                       (vanilla profile)
      versions/neoforge-21.1.265/neoforge-21.1.265.json (inheritsFrom 1.21.1)
      libraries/... (47 neoforge libs; vanilla client jar in versions/)
      libraries/net/minecraft/client/1.21.1-20240808.144430/
          client-...-extra.jar   (game resources — "client-extra")
          client-...-srg.jar     (SRG-renamed classes, processor intermediate)
      libraries/net/neoforged/neoforge/21.1.265/
          neoforge-21.1.265-client.jar     (binary-patched game classes)
          neoforge-21.1.265-universal.jar  (FML core)
  - mainClass = cpw.mods.bootstraplauncher.BootstrapLauncher
    (decompiled contract: -DlegacyClassPath (fallback java.class.path) is
    split into module candidates; -DignoreList entries are matched by
    filename STARTSWITH and kept OUT of the module layer — so the resources
    jar must be named exactly "client-extra.jar");
  - jvm args from the neoforge profile resolve ${library_directory},
    ${classpath_separator}, ${version_name}; the vanilla profile contributes
    the natives/launcher properties and the trailing `-cp ${classpath}`;
  - game args = vanilla template + the FML flags (--launchTarget forgeclient);
  - assetIndex "17" (826 MB) — downloaded with --assets like the 26.3 harness.

Outputs (under --out, absolute paths everywhere — the launch line must
survive any cwd, the measured 26.3 lesson):

  out/libraries/...           the full library tree (installer + vanilla)
  out/client-extra.jar        copy of the resources jar (ignoreList name)
  out/assets/...              asset index + objects (--assets)
  out/game/                   game dir (servers.dat/options.txt by the caller)
  out/classpath.txt           colon-joined classpath
  out/launch.txt              ready java command line
  out/summary.json            machine-readable manifest

Usage:
  fetch-truth-client.py <nfcRoot> <outDir> [--assets] [--skip-installer]
    <nfcRoot>  the installer target dir (kept+cached across runs)
    --skip-installer  nfcRoot already installed (cache hit path)
"""
import argparse
import json
import pathlib
import shutil
import sys
import urllib.request

VANILLA_JSON = "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json"
RESOURCES = "https://resources.download.minecraft.net"
NFC_VERSION = "21.1.265"
NFC_ID = f"neoforge-{NFC_VERSION}"
MC_ID = "1.21.1"
NFC_MAVEN = ("https://maven.neoforged.net/releases/net/neoforged/neoforge/"
             f"{NFC_VERSION}/neoforge-{NFC_VERSION}-installer.jar")


def http_get(url: str) -> bytes:
    req = urllib.request.Request(url, headers={"User-Agent": "c-imagine-truth/1.0"})
    with urllib.request.urlopen(req, timeout=180) as r:
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


def rule_ok(rules, our_os: str) -> bool:
    if not rules:
        return True
    allowed = False
    for rule in rules:
        if rule.get("features"):
            return False  # feature-gated args (demo, quickPlay) — not ours
        os_rule = rule.get("os")
        if os_rule is None or os_rule.get("name") == our_os:
            allowed = rule.get("action") == "allow"
    return allowed


def merge_args(args_list, our_os: str):
    """Vanilla manifest arg merger: plain strings always; ruled lists when ok.
    Feature-gated rules (is_demo_user, has_custom_resolution) never match —
    the launcher would only enable them for the corresponding game feature
    (measured: --demo must not leak into the line)."""
    out = []
    for a in args_list:
        if isinstance(a, str):
            out.append(a)
        elif rule_ok(a.get("rules"), our_os):
            v = a.get("value")
            if isinstance(v, list):
                out.extend(x for x in v if isinstance(x, str))
            elif isinstance(v, str):
                out.append(v)
    return out


def drop_unresolved(args, resolved_known: set) -> list:
    """Post-resolution cleanup: drop any remaining ${...} placeholders TOGETHER
    WITH their flag (args are flag/value pairs — dropping only the value would
    leave a dangling --username etc.). resolved_known lists the placeholders
    the caller substitutes BEFORE this pass."""
    out = []
    skip_next = False
    for a in args:
        if skip_next:
            skip_next = False
            continue
        if "${" in a:
            # value still unresolved: drop it; if the previous token was its
            # flag, drop that too
            if out and out[-1].startswith("-"):
                out.pop()
            continue
        out.append(a)
    return out


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("nfc_root")
    ap.add_argument("out")
    ap.add_argument("--assets", action="store_true")
    ap.add_argument("--skip-installer", action="store_true",
                    help="nfc_root was installed by a previous (cached) run")
    args = ap.parse_args()

    nfc = pathlib.Path(args.nfc_root).resolve()
    out = pathlib.Path(args.out).resolve()
    our_os = os_name()

    # --- 1. installer (cache-miss path) ---------------------------------------
    if not args.skip_installer or not (nfc / "versions" / NFC_ID).is_dir():
        nfc.mkdir(parents=True, exist_ok=True)
        (nfc / "launcher_profiles.json").write_text(
            '{"profiles":{"cim":{"name":"cim"}}}')
        installer = nfc / "nf-installer.jar"
        fetch_to(NFC_MAVEN, installer)
        import subprocess
        r = subprocess.run(
            ["java", "-jar", str(installer), "--installClient", str(nfc)],
            capture_output=True, text=True, timeout=900)
        print(r.stdout[-1200:])
        if r.returncode != 0 or "Successfully installed" not in r.stdout:
            print(r.stderr[-1200:])
            print("ERROR: neoforge installer failed", file=sys.stderr)
            return 1

    nf_json = json.loads((nfc / "versions" / NFC_ID / f"{NFC_ID}.json").read_text())
    manifest = json.loads(http_get(VANILLA_JSON))
    entry = next(v for v in manifest["versions"] if v["id"] == MC_ID)
    vjson = json.loads(http_get(entry["url"]))

    # --- 2. vanilla client jar (the installer keeps it in versions/) ----------
    client_url = vjson["downloads"]["client"]["url"]
    client_size = vjson["downloads"]["client"]["size"]
    vanilla_jar = nfc / "versions" / MC_ID / f"{MC_ID}.jar"
    if not vanilla_jar.exists() or vanilla_jar.stat().st_size != client_size:
        fetch_to(client_url, vanilla_jar)

    # --- 3. libraries: neoforge (installed) + vanilla (artifact+natives) ------
    cp = []
    # 3a. the game jars FIRST (order is stable, not load-bearing for modules)
    neo_dir = nfc / "libraries" / "net" / "neoforged" / "neoforge" / NFC_VERSION
    for name in (f"{NFC_ID}-client.jar", f"{NFC_ID}-universal.jar"):
        p = neo_dir / name
        if not p.exists():
            print(f"ERROR: missing installer artifact {p}", file=sys.stderr)
            return 1
        cp.append(str(p))
    # 3b. the resources jar under the EXACT ignoreList name (decompiled
    # BootstrapLauncher: filename.startsWith(filter) — "client-extra")
    mc_dir = nfc / "libraries" / "net" / "minecraft" / "client" / f"{MC_ID}-20240808.144430"
    extra_src = next(mc_dir.glob("client-*-extra.jar"), None)
    if extra_src is None:
        print(f"ERROR: no client extra jar under {mc_dir}", file=sys.stderr)
        return 1
    client_extra = out / "client-extra.jar"
    client_extra.parent.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(extra_src, client_extra)
    cp.append(str(client_extra))

    lib_bytes = 0
    n_dl = 0
    # 3c. neoforge json libraries (already downloaded by the installer)
    for lib in nf_json.get("libraries", []):
        if not rule_ok(lib.get("rules"), our_os):
            continue
        art = lib.get("downloads", {}).get("artifact") or {}
        rel = art.get("path") or lib.get("path")
        p = nfc / "libraries" / rel
        if p.exists():
            cp.append(str(p))
        elif art.get("url"):
            lib_bytes += fetch_to(art["url"], p)
            n_dl += 1
            cp.append(str(p))
    # 3d. vanilla json libraries (+ natives classifiers) — the heavy part
    for lib in vjson.get("libraries", []):
        if not rule_ok(lib.get("rules"), our_os):
            continue
        dl = lib.get("downloads", {})
        art = dl.get("artifact")
        if art and art.get("url"):
            p = out / "libraries" / art["path"]
            if not p.exists() or (art.get("size") and p.stat().st_size != art["size"]):
                lib_bytes += fetch_to(art["url"], p)
                n_dl += 1
            cp.append(str(p))
        natives = dl.get(f"natives-{our_os}")
        if natives and natives.get("url"):
            p = out / "libraries" / natives["path"]
            if not p.exists():
                lib_bytes += fetch_to(natives["url"], p)
                n_dl += 1
            cp.append(str(p))

    # dedupe, keep order (bootstrap modules first is fine; JVM prefers -p)
    seen = set()
    cp = [x for x in cp if not (x in seen or seen.add(x))]
    (out / "classpath.txt").write_text(":".join(cp))

    # --- 4. assets --------------------------------------------------------------
    asset_index = vjson.get("assetIndex") or {}
    asset_id = asset_index.get("id")
    if args.assets:
        index = json.loads(http_get(asset_index["url"]))
        (out / "assets" / "indexes").mkdir(parents=True, exist_ok=True)
        (out / "assets" / "indexes" / f"{asset_id}.json").write_bytes(
            json.dumps(index).encode())
        for name, obj in index.get("objects", {}).items():
            h = obj["hash"]
            target = out / "assets" / "objects" / h[:2] / h
            if target.exists() and target.stat().st_size == obj.get("size"):
                continue
            fetch_to(f"{RESOURCES}/{h[:2]}/{h}", target)
            n_dl += 1

    # --- 5. launch line ----------------------------------------------------------
    # neoforge jvm args (module path etc.) + vanilla jvm args (natives, -cp)
    nf_jvm = merge_args(nf_json.get("arguments", {}).get("jvm", []), our_os)
    v_jvm = merge_args(vjson.get("arguments", {}).get("jvm", []), our_os)
    cp_text = (out / "classpath.txt").read_text()
    resolved = []
    for a in nf_jvm + v_jvm:
        resolved.append(str(a)
                        .replace("${library_directory}", str(nfc / "libraries"))
                        .replace("${classpath_separator}", ":")
                        .replace("${version_name}", NFC_ID)
                        .replace("${natives_directory}", str(out / "natives"))
                        .replace("${launcher_name}", "c-imagine")
                        .replace("${launcher_version}", "1")
                        .replace("${classpath}", cp_text))
    jvm_line = " ".join(drop_unresolved(resolved, None))

    game = merge_args(vjson.get("arguments", {}).get("game", []), our_os) \
        + merge_args(nf_json.get("arguments", {}).get("game", []), our_os)
    resolved_game = []
    for a in game:
        resolved_game.append(str(a)
                             .replace("${auth_player_name}", "cimviewer")
                             .replace("${version_name}", NFC_ID)
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
    game_line = " ".join(drop_unresolved(resolved_game, None))
    launch = (f"java {jvm_line} {nf_json['mainClass']} "
              f"{game_line} "
              f"--width 1280 --height 720 "
              f"--quickPlayMultiplayer 127.0.0.1:25565")
    # sanity: no unresolved launcher placeholders may survive
    if "${" in launch.split(nf_json['mainClass'], 1)[1]:
        print("ERROR: unresolved ${...} placeholders in the game args",
              file=sys.stderr)
        return 1
    (out / "launch.txt").write_text(launch + "\n")

    summary = {
        "neoforge": NFC_VERSION,
        "minecraft": MC_ID,
        "main_class": nf_json["mainClass"],
        "classpath_entries": len(cp),
        "assets_id": asset_id,
        "assets_bytes": asset_index.get("totalSize"),
        "libraries_downloaded_bytes": lib_bytes,
        "installer_ran": not args.skip_installer,
    }
    (out / "summary.json").write_text(json.dumps(summary, indent=2))
    print(json.dumps(summary, indent=2))
    print(f"fetched {n_dl} files -> {out}")
    print(f"launch: {launch[:220]}...")
    return 0


if __name__ == "__main__":
    sys.exit(main())
