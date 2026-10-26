#!/usr/bin/env bash
# Offline cimtest build: compile the NeoForge 1.21.1 test mod with plain javac
# against the mojmap jars of an installed NeoForge 21.1.265 server layer.
#
# The gradle/ModDevGradle path (test-mods/) remains the CI reference; this
# script gives the sandbox a dependency-free build with the same artifact
# shape (classes + META-INF/neoforge.mods.toml + assets).
#
# Usage: build-cimtest-offline.sh <layerDir> [outJar]
#   <layerDir>  NeoForge 21.1.265 install dir (contains libraries/)
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
LAYER="${1:?usage: build-cimtest-offline.sh <layerDir> [outJar]}"
OUT_JAR="${2:-$ROOT/test-mods/build/offline/cimtest-1.0.0.jar}"
SRC="$ROOT/test-mods/src/main/java"
RES="$ROOT/test-mods/src/main/resources"
LIBS="$LAYER/libraries"

if [ ! -d "$LIBS" ]; then
  echo "ERROR: layer libraries dir missing: $LIBS"; exit 2
fi

# Compile classpath: every layer jar (mojmap names live in the join jar and
# the neoforge universal jar; eventbus/slf4j/nightconfig ride along). Order
# does not matter for javac resolution.
if [ -f /proc/self/cgroup ]; then :; fi
CP="$(find "$LIBS" -name '*.jar' | tr '\n' ':')"

BUILD="$ROOT/test-mods/build/offline/classes"
rm -rf "$BUILD"
mkdir -p "$BUILD"

echo "=== javac cimtest (offline, $(find "$LIBS" -name '*.jar' | wc -l) cp jars) ==="
# shellcheck disable=SC2086
javac --release 21 -nowarn -cp "$CP" -d "$BUILD" $(find "$SRC" -name '*.java')

echo "=== jar ==="
mkdir -p "$(dirname "$OUT_JAR")"
jar --create --file "$OUT_JAR" -C "$BUILD" . -C "$RES" .
echo "built: $OUT_JAR"
