#!/usr/bin/env bash
# Build java/cim-bootstrap -> cim-bootstrap.jar (JDK only, no dependencies).
# Used locally and by CI (ci.yml / integration.yml).
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SRC="$ROOT/java/cim-bootstrap/src/main/java"
OUT="$ROOT/java/cim-bootstrap/build"
JAR="$OUT/cim-bootstrap.jar"

rm -rf "$OUT/classes"
mkdir -p "$OUT/classes"

# shellcheck disable=SC2046
javac --release 21 -Xlint:all -d "$OUT/classes" $(find "$SRC" -name '*.java')

jar --create --file "$JAR" --main-class dev.cim.bootstrap.Bootstrap -C "$OUT/classes" .

echo "built: $JAR"
