#!/usr/bin/env bash
# Fetch decompile inputs for the ground-truth job:
# vanilla client jar + official Mojang mappings + mapping-io / tiny-remapper /
# Vineflower from Maven Central. Layout under $2 (default: work/):
#   client.jar client.txt mapping-io.jar tiny-remapper.jar vineflower.jar
#   RESOLVED_VERSION (echoed; the version actually decompiled)
#
# Mojang dropped `client_mappings` from 26.x version metadata (downloads now
# carry client+server only), so the script falls back to the newest version
# that still publishes official mappings and records it in RESOLVED_VERSION.
set -euo pipefail

REQUESTED="${1:?version}"
OUT="${2:-work}"
mkdir -p "$OUT"
cd "$OUT"

mvn="https://repo1.maven.org/maven2"
fabricmc="https://maven.fabricmc.net"

# --- pick a version that actually publishes official mappings ----------------
resolve_version() {
  local want="$1"
  for v in "$want" 1.21.1; do
    local url
    url=$(python3 - "$v" <<'PY'
import json, sys, urllib.request
want = sys.argv[1]
d = json.load(urllib.request.urlopen(
    "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json"))
e = next(v for v in d["versions"] if v["id"] == want)
m = json.load(urllib.request.urlopen(e["url"]))
if "client_mappings" in m.get("downloads", {}):
    print(e["url"])
PY
)
    if [ -n "$url" ]; then
      echo "$v $url"
      return 0
    fi
  done
  return 1
}

read -r VERSION VERSION_URL <<<"$(resolve_version "$REQUESTED")"
if [ -z "$VERSION" ]; then
  echo "no version with official client mappings found (tried $REQUESTED, 1.21.1)"
  exit 1
fi
echo "RESOLVED_VERSION=$VERSION" | tee RESOLVED_VERSION
echo "decompiling client for version $VERSION (requested: $REQUESTED)"

curl -s "$VERSION_URL" -o version.json
CLIENT=$(python3 -c "import json; print(json.load(open('version.json'))['downloads']['client']['url'])")
MAPS=$(python3 -c "import json; print(json.load(open('version.json'))['downloads']['client_mappings']['url'])")

curl -sL "$CLIENT" -o client.jar
curl -sL "$MAPS" -o client.txt

# --- toolchain (maven-metadata: prefer <latest>, then <release>, then last) --
fetch_latest() { # <group-path> <artifact> <base-url> <out>
  local gp="$1" art="$2" base="$3" out="$4"
  local ver
  ver=$(curl -s "$base/$gp/$art/maven-metadata.xml" | python3 -c "
import sys, re
x = sys.stdin.read()
for tag in ('latest', 'release'):
    m = re.search(r'<%s>([^<]+)</%s>' % (tag, tag), x)
    if m:
        print(m.group(1)); break
else:
    vs = re.findall(r'<version>([^<]+)</version>', x)
    print(vs[-1] if vs else '')
")
  [ -n "$ver" ] || { echo "cannot resolve $art version"; return 1; }
  echo "$art version: $ver"
  curl -sL "$base/$gp/$art/$ver/$art-$ver.jar" -o "$out"
}

fetch_latest "net/fabricmc" "mapping-io" "$mvn" mapping-io.jar
fetch_latest "net/fabricmc" "tiny-remapper" "$fabricmc" tiny-remapper.jar
fetch_latest "org/vineflower" "vineflower" "$mvn" vineflower.jar

# tiny-remapper needs ASM on the classpath.
ASM=$(curl -s "$mvn/org/ow2/asm/asm/maven-metadata.xml" | python3 -c "
import sys, re
x = sys.stdin.read()
m = re.search(r'<release>([^<]+)</release>', x) or re.search(r'<latest>([^<]+)</latest>', x)
print(m.group(1) if m else '')")
echo "asm version: $ASM"
for A in asm asm-commons asm-tree asm-analysis asm-util; do
  curl -sL "$mvn/org/ow2/asm/$A/$ASM/$A-$ASM.jar" -o "$A.jar"
done

echo "inputs ready: $(ls -la)"
