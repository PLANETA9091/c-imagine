#!/usr/bin/env bash
# M2 real-mod harness: relocation + real-FML mod execution in the shadow space
# (no Paper, no CRUSSTY — the fast inner loop; CI parity job uses this script).
#
# Preconditions:
#   - target/release/libimagine.so        (cargo build --release)
#   - java/cim-bootstrap/build/cim-bootstrap.jar (scripts/build-bootstrap.sh)
#
# Usage: run-m2-harness.sh <layerDir> <modsDir> <outDir>
#   <layerDir>  NeoForge 21.1.265 install libraries dir (installer layout)
#   <modsDir>   mods directory (real Modrinth jars + cimtest)
#   <outDir>    output dir (manifest + harness.log + game dir)
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
LAYER="${1:?usage: run-m2-harness.sh <layerDir> <modsDir> <outDir>}"
MODS="${2:?usage: run-m2-harness.sh <layerDir> <modsDir> <outDir>}"
OUT="${3:?usage: run-m2-harness.sh <layerDir> <modsDir> <outDir>}"

# Absolute paths up front: the java step cds into OUT, which would otherwise
# break relative layer/mods/manifest paths passed by the caller.
mkdir -p "$OUT"; OUT="$(cd "$OUT" && pwd)"
LAYER="$(cd "$LAYER" && pwd)"
MODS="$(cd "$MODS" && pwd)"

LIB="$ROOT/target/release/libimagine.so"
CLI="$ROOT/target/release/cim-manifest"
JAR="$ROOT/java/cim-bootstrap/build/cim-bootstrap.jar"

[ -f "$LIB" ] || { echo "ERROR: $LIB missing (cargo build --release)"; exit 2; }
[ -f "$CLI" ] || { echo "ERROR: $CLI missing (cargo build --release)"; exit 2; }
[ -f "$JAR" ] || { echo "ERROR: $JAR missing (scripts/build-bootstrap.sh)"; exit 2; }
[ -d "$LAYER" ] || { echo "ERROR: layer dir missing: $LAYER"; exit 2; }
[ -d "$MODS" ] || { echo "ERROR: mods dir missing: $MODS"; exit 2; }

mkdir -p "$OUT"
MANIFEST="$OUT/m2manifest.json"

echo "=== cim-manifest (scan + manifest) ==="
CIM_LAYER_DIR="$LAYER" "$CLI" "$MODS" "$MANIFEST"

echo "=== LocalHarness (real FML pipeline) ==="
mkdir -p "$OUT/game"
# Shadow-space log4j config (scoped via log4j2.component.properties in the
# bootstrap jar): relative path resolves against the java CWD.
cp "$ROOT/scripts/log4j2-layer.xml" "$OUT/"
# pipefail-safe tee: the harness exit code propagates
set -o pipefail
cd "$OUT"
# -Xlog:class+resolve: the JVM names every CP resolution site — the Create
# construct autopsy needs the exact requester of the client-class resolution
# that the real server never performs (12-r/12-s loop).
java -cp "$JAR" -Dcim.gamedir="$OUT/game" \
  -Xlog:class+resolve=info \
  dev.cim.bootstrap.LocalHarness "$LIB" "$LAYER" "$MODS" "$MANIFEST" \
  2>&1 | tee "$OUT/harness.log"
