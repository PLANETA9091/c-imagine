#!/usr/bin/env bash
# Offline cim-paper-plugin build: compile the Paper plugin with plain javac
# against the paper-api 26.3 jar (+ gson + joml from Paper's libraries — the
# SAME artifacts the server runtime provides, so the plugin never bundles or
# shades them). ASM analysis is packaged just as in Maven. The Maven path
# stays the CI reference for the same runtime payload.
#
# Usage: build-paper-plugin-offline.sh [outJar]
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
A="${CIM_ARTIFACTS:-/tmp/my-project/artifacts}"
OUT_JAR="${1:-$ROOT/paper-plugin/build/offline/cim-paper-plugin.jar}"

PAPER_API="$A/paper-api-26.3-stable.jar"
GSON="$(find "$A/paper263/libraries" -name 'gson-2*.jar' | head -1)"
JOML="$(find "$A/paper263/libraries" -name 'joml-1*.jar' | head -1)"
NETTY="$(find "$A/paper263/libraries" -name 'netty-transport-classes-4*.jar' -o -name 'netty-common-4*.jar' | head -1)"
NETTY2="$(find "$A/paper263/libraries" -name 'netty-transport-4*.jar' | head -1)"
ADV="$(find "$A/paper263/libraries" -name 'adventure-api-*.jar' | head -1)"
ADVKEY="$(find "$A/paper263/libraries" -name 'adventure-key-*.jar' | head -1)"
ANN="$A/annotations.jar"
BUNGEE="$A/bungee-chat.jar"
GUAVA="$(find "$A/paper263/libraries" -name 'guava-3*.jar' | head -1)"
ASM="$(find "$A/paper263/libraries" "$A" -name 'asm*-9*.jar' -o -name 'maven-artifact-*.jar' | tr '\n' ':')"
ASM_ANALYSIS="$(find "$A/paper263/libraries" "$A" -name 'asm-analysis-9.9.1.jar' -print -quit)"
[ -n "$ASM_ANALYSIS" ] || { echo "ERROR: asm-analysis-9.9.1.jar missing"; exit 2; }
[ -f "$PAPER_API" ] || { echo "ERROR: paper-api jar missing: $PAPER_API"; exit 2; }
for f in "$GSON" "$JOML" "$NETTY" "$NETTY2" "$ADV" "$ADVKEY" "$ANN" "$BUNGEE" "$GUAVA"; do
  [ -n "$f" ] && [ -f "$f" ] || { echo "ERROR: dependency jar missing ($f)"; exit 2; }
done

SRC="$ROOT/paper-plugin/src/main/java"
RES="$ROOT/paper-plugin/src/main/resources"
BUILD="$ROOT/paper-plugin/build/offline/classes"
rm -rf "$BUILD"
mkdir -p "$BUILD"

CP="$PAPER_API:$GSON:$JOML:$NETTY:$NETTY2:$ADV:$ADVKEY:$ANN:$BUNGEE:$GUAVA:$ASM"
echo "=== javac cim-paper-plugin (offline) ==="
# JDK 25 reads Paper's v69 API, while plugin bytecode follows the Java 21
# repository contract, exactly as in the Maven build.
javac --release 21 -nowarn -cp "$CP" -d "$BUILD" $(find "$SRC" -name '*.java')

# Keep Maven's artifact shape: analysis classes are local; ASM core/tree stay provided.
unzip -qo "$ASM_ANALYSIS" 'org/objectweb/asm/tree/analysis/*' -d "$BUILD"

echo "=== jar ==="
mkdir -p "$(dirname "$OUT_JAR")"
jar --create --file "$OUT_JAR" -C "$BUILD" . -C "$RES" .
echo "OK: $OUT_JAR"
echo "CLI classpath for F2vPack --build (gson needed outside the server):"
echo "  $OUT_JAR:$GSON"
