#!/usr/bin/env bash
# Local fast loop (Actions-economy policy, owner directive):
# the full ci.yml "rust" job but on the sandbox, zero GitHub minutes.
#
#   scripts/local-check.sh          # fmt + clippy + tests
#   scripts/local-check.sh release  # + release build of the module
#
# ci.yml keeps running on push as the cheap correctness gate; the heavy
# workflows (integration/visual/profiling) run on dispatch + weekly cron only.
set -euo pipefail

cd "$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

if [ ! -f "$HOME/.cargo/env" ]; then
  echo "ERROR: rustup env missing ($HOME/.cargo/env)"; exit 2
fi
# shellcheck disable=SC1091
source "$HOME/.cargo/env"

echo "=== cargo fmt --check ==="
cargo fmt --all -- --check

echo "=== cargo clippy -D warnings ==="
cargo clippy --workspace --all-targets -- -D warnings

echo "=== cargo test --workspace ==="
cargo test --workspace --quiet

if [ "${1:-}" = "release" ]; then
  echo "=== cargo build -p imagine --release ==="
  cargo build -p imagine --release --quiet
  echo "release ok: target/release/libimagine.so"
fi

echo "local-check: ALL OK"
