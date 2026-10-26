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
# pipefail-safe tee: the harness exit code propagates
set -o pipefail
java -cp "$JAR" -Dcim.gamedir="$OUT/game" \
  dev.cim.bootstrap.LocalHarness "$LIB" "$LAYER" "$MODS" "$MANIFEST" \
  2>&1 | tee "$OUT/harness.log"
