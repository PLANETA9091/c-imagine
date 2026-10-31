#!/usr/bin/env bash
# M5.5 ladder fetch (MODPACK.md Tier 0): download real mods from Modrinth
# by slug, pinned to NeoForge 1.21.1. Unlike fetch-real-mods.sh (best
# effort, WARN + continue), a ladder mod is MANDATORY for its rung — a
# failed fetch is a hard error so the rung never silently runs empty.
#
# THE LOCK (13-c): scripts/cimscene/mods.lock pins slug=version — without
# it the fetch resolved "latest matching", which lets an upstream release
# silently desync the seeded pack/manifests from a fresh walk. A slug
# absent from the lock still resolves latest (and the fetch PRINTS the
# resolved version so it can be pinned deliberately).
#
# Usage: fetch-ladder.sh <output-dir> <slug>...
set -euo pipefail

OUT="${1:?usage: fetch-ladder.sh <output-dir> <slug>...}"
shift
GAME_VERSION="1.21.1"
LOADER="neoforge"
LOCK="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/mods.lock"

lock_version() {
  [ -f "$LOCK" ] || return 1
  python3 - "$1" "$LOCK" <<'PY'
import sys
slug, lock = sys.argv[1], sys.argv[2]
for line in open(lock, encoding="utf-8"):
    line = line.strip()
    if not line or line.startswith("#") or "=" not in line:
        continue
    k, v = line.split("=", 1)
    if k.strip() == slug:
        print(v.strip())
        break
PY
}

mkdir -p "$OUT"
for slug in "$@"; do
  PINNED=$(lock_version "$slug" || true)
  if [ -n "${PINNED:-}" ]; then
    echo "locked: $slug=$PINNED (scripts/cimscene/mods.lock)"
  else
    echo "WARNING: $slug is NOT in scripts/cimscene/mods.lock — resolving latest; pin it deliberately"
  fi
  url=$(curl -sf --max-time 60 "https://api.modrinth.com/v2/project/${slug}/version?game_versions=%5B%22${GAME_VERSION}%22%5D&loaders=%5B%22${LOADER}%22%5D" |
    python3 -c '
import json, sys
slug = sys.argv[1]
pinned = sys.argv[2] if len(sys.argv) > 2 and sys.argv[2] not in ("", "none") else None
raw = sys.stdin.read()
if not raw.strip():
    # curl -sf swallowed the HTTP error (404 bad slug, 429 rate limit, ...)
    raise SystemExit(f"modrinth returned NO body for {slug} (bad slug? rate limit?) — check https://modrinth.com/mod/{slug}")
versions = json.loads(raw)
if not versions:
    raise SystemExit(f"no version found for {slug} (game_versions={sys.argv[3]}, loaders={sys.argv[4]})")
v = versions[0]
if pinned:
    match = [x for x in versions if x["version_number"] == pinned]
    if not match:
        raise SystemExit(f"{slug}: lock pins {pinned} but Modrinth lists: "
                         + ", ".join(x["version_number"] for x in versions[:5])
                         + " — bump scripts/cimscene/mods.lock")
    v = match[0]
print(v["files"][0]["url"])
print(v["files"][0]["filename"], file=sys.stderr)
print(v["version_number"], file=sys.stderr)
' "$slug" "${PINNED:-none}" "$GAME_VERSION" "$LOADER") || { echo "ERROR: ${slug} fetch failed (ladder mod is mandatory)"; exit 2; }
  file_url=$(echo "$url" | head -1)
  file_name=$(basename "$file_url" | python3 -c 'import sys, urllib.parse; print(urllib.parse.unquote(sys.stdin.read().strip()))')
  if [ -f "$OUT/$file_name" ]; then
    echo "cached: $file_name"
  else
    echo "downloading: $file_name"
    curl -sfL --max-time 600 -o "$OUT/$file_name" "$file_url"
  fi
done
ls -la "$OUT"
