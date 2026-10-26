#!/usr/bin/env bash
# Download real NeoForge 1.21.1 mods from Modrinth for integration testing.
#
# Early real-mod integration is an explicit owner requirement: the matrix must
# show visible progression against third-party mods, not only the first-party
# cimtest marker mod. The mod set below is server-side friendly (loads on a
# dedicated server) and spans the M2 surface:
#   appleskin    - simple @Mod ctor + event listeners (both sides)
#   jade         - moderate size, API surface other mods reference
#   jei          - large mod, deep registry interaction
#   ferrite-core - memory-optimization mod (static init heavy)
#   spark        - profiler, universal
#
# Usage: fetch-real-mods.sh <output-dir>
set -euo pipefail

OUT="${1:?usage: fetch-real-mods.sh <output-dir>}"
GAME_VERSION="1.21.1"
LOADER="neoforge"
SLUGS=(appleskin jade jei ferrite-core spark)

mkdir -p "$OUT"
for slug in "${SLUGS[@]}"; do
  # Latest version matching the game version + loader; primary file URL.
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
' ) || { echo "WARN: ${slug} fetch failed"; continue; }
  # url variable holds the first line (the URL); metadata went to stderr.
  file_url=$(echo "$url" | head -1)
  file_name=$(basename "$file_url" | python3 -c 'import sys, urllib.parse; print(urllib.parse.unquote(sys.stdin.read().strip()))')
  if [ -f "$OUT/$file_name" ]; then
    echo "cached: $file_name"
  else
    echo "downloading: $file_name"
    curl -sfL --max-time 300 -o "$OUT/$file_name" "$file_url"
  fi
done
ls -la "$OUT"
