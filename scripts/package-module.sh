#!/usr/bin/env bash
# Package the installable CRUSSTY module: dist/module-imagine.zip
#
# Layout (matches CRUSSTY's module scanner):
#   module.json          {"id":"imagine","main":"libimagine.so"}
#   libimagine.so        the native module (crates/imagine, release build)
#   cim-bootstrap.jar    the Java bootstrap embedded in/next to the module
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DIST="$ROOT/dist"
MOD_DIR="$DIST/imagine"

cd "$ROOT"

# 1) Java bootstrap (skipped when CI already staged it via CIM_BOOTSTRAP_JAR).
if [ -n "${CIM_BOOTSTRAP_JAR:-}" ]; then
  mkdir -p "$MOD_DIR"
  cp "$CIM_BOOTSTRAP_JAR" "$MOD_DIR/cim-bootstrap.jar"
else
  bash "$ROOT/scripts/build-bootstrap.sh"
  mkdir -p "$MOD_DIR"
  cp "$ROOT/java/cim-bootstrap/build/cim-bootstrap.jar" "$MOD_DIR/cim-bootstrap.jar"
fi

# 2) Native module (release).
cargo build -p imagine --release
cp "$ROOT/target/release/libimagine.so" "$MOD_DIR/libimagine.so"

# 3) Manifest (id "imagine" matches the cdylib name convention).
cat > "$MOD_DIR/module.json" <<'JSON'
{
  "id": "imagine",
  "main": "libimagine.so"
}
JSON

# 4) Zip (module dir ships as a single archive; CRUSSTY extracts zip modules).
rm -f "$DIST/module-imagine.zip"
(cd "$DIST" && zip -r module-imagine.zip imagine >/dev/null)

echo "packaged: $DIST/module-imagine.zip"
unzip -l "$DIST/module-imagine.zip"
