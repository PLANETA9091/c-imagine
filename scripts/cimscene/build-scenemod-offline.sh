#!/usr/bin/env bash
# Offline cimscene build: compile the NeoForge 1.21.1 scene mod with plain javac
# against the mojmap jars of an installed NeoForge 21.1.265 server layer.
#
# Mirrors scripts/build-cimtest-offline.sh (the gradle/ModDevGradle path in
# scene-mod/ remains the CI reference; this gives the sandbox the same artifact
# shape: classes + META-INF/neoforge.mods.toml + pack.mcmeta).
#
# Usage: build-scenemod-offline.sh <layerDir> [outJar]
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
LAYER="${1:?usage: build-scenemod-offline.sh <layerDir> [outJar]}"
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
javac --release 21 -nowarn -cp "$CP" -d "$BUILD" $(find "$SRC" -name '*.java')

echo "=== jar ==="
mkdir -p "$(dirname "$OUT_JAR")"
jar --create --file "$OUT_JAR" -C "$BUILD" . -C "$RES" .
echo "built: $OUT_JAR"
