#!/usr/bin/env bash
# Wait for the scene producer's distinct completion/exhaustion file protocol.
set -euo pipefail
stem=${1:?usage: wait-scene-marker.sh STEM [TIMEOUT_SECONDS]}
limit=${2:-240}
[[ "$limit" =~ ^[0-9]+$ ]] || { echo 'invalid marker timeout' >&2; exit 1; }
deadline=$((SECONDS + limit))
while true; do
  if [[ -f "$stem.done" && -f "$stem.exhausted" ]]; then
    echo "ambiguous scene markers: $stem" >&2
    exit 1
  elif [[ -f "$stem.done" ]]; then
    echo done
    exit 0
  elif [[ -f "$stem.exhausted" ]]; then
    echo exhausted
    exit 0
  fi
  if (( SECONDS >= deadline )); then
    echo "missing scene terminal marker: $stem" >&2
    exit 1
  fi
  sleep 1
done
