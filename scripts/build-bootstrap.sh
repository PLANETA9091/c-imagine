#!/usr/bin/env bash
# Build java/cim-bootstrap -> cim-bootstrap.jar.
# Used locally and by CI (ci.yml / integration.yml).
#
# Compile-time classpath (COMPILE-ONLY, nothing packaged): sponge-mixin
# 0.16.4+mixin.0.8.7 + ASM 9.10.1 — the exact versions the 1.21.1 layer
# carries; at runtime the shadow loader serves the layer's own copies
# (harness contract §8). Set MIXIN_COMPILE_JAR / ASM_COMPILE_DIR to reuse
# local layer jars and skip the downloads.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SRC="$ROOT/java/cim-bootstrap/src/main/java"
OUT="$ROOT/java/cim-bootstrap/build"
JAR="$OUT/cim-bootstrap.jar"

MIXIN_VERSION="0.16.4+mixin.0.8.7"
ASM_VERSION="9.10.1"
CP=""

fetch() { # fetch <url> <dest>
  if [ ! -f "$2" ]; then
    echo "fetching compile-only $(basename "$2")"
    curl -sfL --max-time 300 -o "$2" "$1"
  fi
}

if [ -n "${MIXIN_COMPILE_JAR:-}" ]; then
  cp "$MIXIN_COMPILE_JAR" "$OUT/sponge-mixin.jar"
else
  mkdir -p "$OUT"
  fetch "https://maven.neoforged.net/releases/net/fabricmc/sponge-mixin/$MIXIN_VERSION/sponge-mixin-$MIXIN_VERSION.jar" \
    "$OUT/sponge-mixin.jar"
fi
CP="$OUT/sponge-mixin.jar"

for art in asm asm-tree; do
  if [ -n "${ASM_COMPILE_DIR:-}" ] && [ -f "$ASM_COMPILE_DIR/$art-$ASM_VERSION.jar" ]; then
    cp "$ASM_COMPILE_DIR/$art-$ASM_VERSION.jar" "$OUT/$art.jar"
  else
    fetch "https://repo1.maven.org/maven2/org/ow2/asm/$art/$ASM_VERSION/$art-$ASM_VERSION.jar" \
      "$OUT/$art.jar"
  fi
  CP="$CP:$OUT/$art.jar"
done

rm -rf "$OUT/classes"
mkdir -p "$OUT/classes"

# -proc:none: the sponge-mixin jar ships annotation processors (they expect
# mixin-annotated sources); cim-bootstrap carries none.
# shellcheck disable=SC2046
javac --release 21 -Xlint:all -proc:none -cp "$CP" -d "$OUT/classes" $(find "$SRC" -name '*.java')

jar --create --file "$JAR" --main-class dev.cim.bootstrap.Bootstrap \
    -C "$OUT/classes" . \
    -C "$ROOT/java/cim-bootstrap/src/main/resources" .

echo "built: $JAR"
