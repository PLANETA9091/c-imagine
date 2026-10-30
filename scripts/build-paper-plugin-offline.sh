#!/usr/bin/env bash
# Offline cim-paper-plugin build: compile the Paper plugin with plain javac
# against the paper-api 26.3 jar (+ gson + joml from Paper's libraries — the
# SAME artifacts the server runtime provides, so the plugin never bundles or
# shades them). The maven path in paper-plugin/ stays the CI reference with
# the identical artifact shape.
#
# Usage: build-paper-plugin-offline.sh [outJar]
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
A=/tmp/my-project/artifacts
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
[ -f "$PAPER_API" ] || { echo "ERROR: paper-api jar missing: $PAPER_API"; exit 2; }
for f in "$GSON" "$JOML" "$NETTY" "$NETTY2" "$ADV" "$ADVKEY" "$ANN" "$BUNGEE" "$GUAVA"; do
  [ -n "$f" ] && [ -f "$f" ] || { echo "ERROR: dependency jar missing ($f)"; exit 2; }
done

SRC="$ROOT/paper-plugin/src/main/java"
RES="$ROOT/paper-plugin/src/main/resources"
BUILD="$ROOT/paper-plugin/build/offline/classes"
rm -rf "$BUILD"
mkdir -p "$BUILD"

CP="$PAPER_API:$GSON:$JOML:$NETTY:$NETTY2:$ADV:$ADVKEY:$ANN:$BUNGEE:$GUAVA"
echo "=== javac cim-paper-plugin (offline) ==="
javac --release 21 -nowarn -cp "$CP" -d "$BUILD" $(find "$SRC" -name '*.java')

echo "=== jar ==="
mkdir -p "$(dirname "$OUT_JAR")"
jar --create --file "$OUT_JAR" -C "$BUILD" . -C "$RES" .
echo "OK: $OUT_JAR"
echo "CLI classpath for F2vPack --build (gson needed outside the server):"
echo "  $OUT_JAR:$GSON"
