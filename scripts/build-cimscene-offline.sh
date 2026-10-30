#!/usr/bin/env bash
# Offline cimscene build: compile the NeoForge 1.21.1 harness mod with plain
# javac against the mojmap jars of an installed NeoForge 21.1.265 server layer
# (the same dependency-free path as build-cimtest-offline.sh; the gradle/
# ModDevGradle path in scene-mod/ stays the CI reference with the identical
# artifact shape).
#
# Usage: build-cimscene-offline.sh <layerDir> [outJar]
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
LAYER="${1:?usage: build-cimscene-offline.sh <layerDir> [outJar]}"
OUT_JAR="${2:-$ROOT/scene-mod/build/offline/cimscene-1.0.0.jar}"
SRC="$ROOT/scene-mod/src/main/java"
RES="$ROOT/scene-mod/src/main/resources"
LIBS="$LAYER/libraries"

if [ ! -d "$LIBS" ]; then
  echo "ERROR: layer libraries dir missing: $LIBS"; exit 2
fi

CP="$(find "$LIBS" -name '*.jar' | tr '\n' ':')"
BUILD="$ROOT/scene-mod/build/offline/classes"
rm -rf "$BUILD"
mkdir -p "$BUILD"

echo "=== javac cimscene (offline, $(find "$LIBS" -name '*.jar' | wc -l) cp jars) ==="
# shellcheck disable=SC2086
javac --release 21 -nowarn -cp "$CP" -d "$BUILD" $(find "$SRC" -name '*.java')

echo "=== jar ==="
mkdir -p "$(dirname "$OUT_JAR")"
jar --create --file "$OUT_JAR" -C "$BUILD" . -C "$RES" .
echo "OK: $OUT_JAR"
