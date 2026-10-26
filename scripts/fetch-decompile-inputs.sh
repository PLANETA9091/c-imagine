#!/usr/bin/env bash
# Fetch decompile inputs for the ground-truth job:
# vanilla client jar + official Mojang mappings + mapping-io / tiny-remapper /
# Vineflower from Maven Central. Layout under $1 (default: work/):
#   client.jar client.txt mapping-io.jar tiny-remapper.jar vineflower.jar
set -euo pipefail

VERSION="${1:?version}"
OUT="${2:-work}"
mkdir -p "$OUT"
cd "$OUT"

base="https://maven.fabricmc.net"
mvn="https://repo1.maven.org/maven2"

# --- Mojang manifest → client jar + mappings --------------------------------
curl -s "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json" -o manifest.json
URL=$(python3 -c "
import json
d = json.load(open('manifest.json'))
v = next(v for v in d['versions'] if v['id'] == '$VERSION')
print(v['url'])
")
curl -s "$URL" -o version.json
CLIENT=$(python3 -c "import json; print(json.load(open('version.json'))['downloads']['client']['url'])")
MAPS=$(python3 -c "import json; print(json.load(open('version.json'))['downloads']['client_mappings']['url'])")

curl -sL "$CLIENT" -o client.jar
curl -sL "$MAPS" -o client.txt

# --- toolchain ---------------------------------------------------------------
MAPPING_IO=$(curl -s "$mvn/net/fabricmc/mapping-io/maven-metadata.xml" \
  | grep -oE "<release>[^<]+" | cut -d'>' -f2)
curl -sL "$mvn/net/fabricmc/mapping-io/$MAPPING_IO/mapping-io-$MAPPING_IO.jar" -o mapping-io.jar

TINY_REMAPPER=$(curl -s "$base/net/fabricmc/tiny-remapper/maven-metadata.xml" \
  | grep -oE "<release>[^<]+" | cut -d'>' -f2)
curl -sL "$base/net/fabricmc/tiny-remapper/$TINY_REMAPPER/tiny-remapper-$TINY_REMAPPER.jar" \
  -o tiny-remapper.jar

VINEFLOWER=$(curl -s "$mvn/org/vineflower/vineflower/maven-metadata.xml" \
  | grep -oE "<release>[^<]+" | cut -d'>' -f2)
curl -sL "$mvn/org/vineflower/vineflower/$VINEFLOWER/vineflower-$VINEFLOWER.jar" \
  -o vineflower.jar

echo "inputs ready: $(ls -la)"
