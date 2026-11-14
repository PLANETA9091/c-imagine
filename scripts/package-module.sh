#!/usr/bin/env bash
# Package the installable CRUSSTY module: dist/module-imagine.zip
#
# Layout (matches CRUSSTY's module scanner):
#   module.json          {"id":"imagine","main":"libimagine.so"}
#   libimagine.so        the native module (crates/imagine, release build)
#   cim-bootstrap.jar    the Java bootstrap embedded in/next to the module
#   cimlive-1.0.0.jar    the cimlive mod (live-mod/, NeoForge 1.21.1); the loader
#                        installs it into <server>/mods/ (crates/imagine bundled.rs)
#
# Prerequisite: the cimlive jar must exist. Build it first with
#   gradle build --no-daemon -p live-mod
# or point CIM_CIMLIVE_JAR at a built cimlive jar.
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

# 1b) cimlive (NeoForge mod). Required: a module zip without it cannot run live menus.
CIMLIVE_NAME="cimlive-1.0.0.jar"
CIMLIVE_JAR="${CIM_CIMLIVE_JAR:-$ROOT/live-mod/build/libs/$CIMLIVE_NAME}"
if [ ! -f "$CIMLIVE_JAR" ]; then
  echo "error: cimlive jar not found: $CIMLIVE_JAR" >&2
  echo "       build it: gradle build --no-daemon -p live-mod   (or set CIM_CIMLIVE_JAR)" >&2
  exit 1
fi
cp "$CIMLIVE_JAR" "$MOD_DIR/$CIMLIVE_NAME"

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

# 5) Contract check: the zip must carry every file the loader needs, cimlive included.
ZIP_LIST="$(unzip -Z1 "$DIST/module-imagine.zip")"
for want in imagine/module.json imagine/libimagine.so imagine/cim-bootstrap.jar "imagine/$CIMLIVE_NAME"; do
  if ! grep -qxF "$want" <<<"$ZIP_LIST"; then
    echo "error: module-imagine.zip is missing $want" >&2
    exit 1
  fi
done

echo "packaged: $DIST/module-imagine.zip"
unzip -l "$DIST/module-imagine.zip"
