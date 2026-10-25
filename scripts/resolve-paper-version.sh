#!/usr/bin/env bash
# Resolve the newest stable Paper API version for a Minecraft version line.
# Usage: resolve-paper-version.sh [MC_VERSION]   (default 26.3)
# Prints e.g. 26.3.build.223-stable to stdout.
set -euo pipefail

MC="${1:-26.3}"
METADATA_URL="https://repo.papermc.io/repository/maven-public/io/papermc/paper/paper-api/maven-metadata.xml"

VERSION=$(curl -fsSL "$METADATA_URL" \
  | grep -o "<version>${MC}\.build\.[0-9]\+-stable</version>" \
  | sed -E 's#</?version>##g' \
  | sort -V | tail -1)

if [ -z "$VERSION" ]; then
  echo "ERROR: no stable paper-api build found for ${MC}" >&2
  exit 1
fi
echo "$VERSION"
