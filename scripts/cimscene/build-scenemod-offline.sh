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

# Compile classpath: every layer jar EXCEPT the reobfuscated Minecraft
# server bundles (net/minecraft/server/1.21.1/server-1.21.1.jar,
# neoforge-<v>-server.jar and the timestamped -extra/-slim/-unpacked
# intermediates). Those carry OBFUSCATED Minecraft classes under the same
# package names — for classes that exist in BOTH jars
# (net.minecraft.server.MinecraftServer keeps its name when obfuscated)
# javac picks the first match and the mod compiles against OBFUSCATED
# signatures (measured: tell/getTickCount "missing"). The mojmap artifact
# is the timestamped -srg.jar.
CP="$(find "$LIBS" -name '*.jar' \
    ! -path '*/net/minecraft/server/1.21.1/*' \
    ! -name 'neoforge-*-server.jar' \
    ! -name 'server-*extra.jar' \
    ! -name 'server-*slim.jar' \
    ! -name 'server-*unpacked.jar' | tr '\n' ':')"
BUILD="$ROOT/scene-mod/build/offline/classes"
rm -rf "$BUILD"
mkdir -p "$BUILD"

echo "=== javac cimscene (offline, $(echo "$CP" | tr ':' '\n' | grep -c . ) cp jars) ==="
# -proc:none: the offline classpath is SRG-named (the reobfuscated layer), so
# the sponge-mixin annotation processor cannot resolve mojmap @Redirect
# targets (measured 12-ar: "Unable to locate obfuscation mapping for
# @Redirect target guardEntityTick"). Runtime is mojmap — the target exists;
# the CI gradle build (ModDevGradle, mojmap CP) runs the AP for real.
javac --release 21 -nowarn -proc:none -cp "$CP" -d "$BUILD" $(find "$SRC" -name '*.java')

echo "=== jar ==="
mkdir -p "$(dirname "$OUT_JAR")"
jar --create --file "$OUT_JAR" -C "$BUILD" . -C "$RES" .
echo "built: $OUT_JAR"
