#!/usr/bin/env bash
# M5.5 ladder fetch (MODPACK.md Tier 0): download real mods from Modrinth
# by slug, pinned to NeoForge 1.21.1. Unlike fetch-real-mods.sh (best
# effort, WARN + continue), a ladder mod is MANDATORY for its rung — a
# failed fetch is a hard error so the rung never silently runs empty.
#
# Usage: fetch-ladder.sh <output-dir> <slug>...
set -euo pipefail

OUT="${1:?usage: fetch-ladder.sh <output-dir> <slug>...}"
shift
GAME_VERSION="1.21.1"
LOADER="neoforge"

mkdir -p "$OUT"
for slug in "$@"; do
  url=$(curl -sf --max-time 60 "https://api.modrinth.com/v2/project/${slug}/version?game_versions=%5B%22${GAME_VERSION}%22%5D&loaders=%5B%22${LOADER}%22%5D" |
    python3 -c '
import json, sys
versions = json.load(sys.stdin)
if not versions:
    raise SystemExit(f"no version found for {slug}")
v = versions[0]
print(v["files"][0]["url"])
print(v["files"][0]["filename"], file=sys.stderr)
print(v["version_number"], file=sys.stderr)
') || { echo "ERROR: ${slug} fetch failed (ladder mod is mandatory)"; exit 2; }
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
