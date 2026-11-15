#!/usr/bin/env bash
# Established shell entry point; authentication belongs to gh.
set -euo pipefail
HERE=$(cd "$(dirname "$0")" && pwd)
exec python3 "$HERE/profiling-receipt.py" "$@"
