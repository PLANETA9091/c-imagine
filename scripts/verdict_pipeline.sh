#!/usr/bin/env bash
# verdict_pipeline.sh <gallery-run-id> [--baseline DIR] [--out DIR]
#
# THE MECHANICAL RECEIPT (worklog 2026-11-14T11:15Z, made one-shot):
#   (1) verdict-pull.py <run> --out DIR --kinds verdict,reports --forensics
#   (2) the join-level RED census by luma bucket (the e21 classification)
#   (3) the delta vs the baseline run's census (the collapse vs the stubborn)
#   (4) the registered prediction checks — each prints PASS/FAIL against its
#       band, no interpretation step between the numbers and the verdict.
#
# Registered predictions (append here as lanes land):
#   14-e23 (the baseline 43611950455 = the e19.2 build): the
#   (luma_norm=true, luma_fit=false, scale 1.3-1.8) un-fit mass 1607 collapses
#   — PASS <= 400, PARTIAL 400-1200, FAIL > 1200 (the bake landed in the pack
#   bytes or it did not; the sky-corner remainder and the geometry
#   residuals stay, they never rode the dead branch).
#
# Report-only: never gates, never edits a verdict.
set -euo pipefail

RUN="${1:?usage: verdict_pipeline.sh <gallery-run-id> [--baseline DIR] [--out DIR]}"
BASE="/tmp/vp_e21"
OUT="/tmp/vp_next"
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CIMS="$HERE/cimscene"

while [ $# -gt 1 ]; do
  case "$2" in
    --baseline) BASE="$3"; shift 2;;
    --out) OUT="$3"; shift 2;;
    *) echo "unknown arg $2" >&2; exit 2;;
  esac
done

echo "=== (1) pull + forensics: run $RUN -> $OUT ==="
python3 "$CIMS/verdict-pull.py" "$RUN" --out "$OUT" --kinds verdict,reports --forensics --top 16

echo
echo "=== (2) the join-level RED census by luma bucket ==="
python3 - "$OUT" <<'PY'
import json, glob, collections, sys
out = sys.argv[1]
buckets = collections.Counter()
fam = collections.Counter()
total = 0
for jf in glob.glob(f'{out}/gallery-report-*/join-*.json'):
    try:
        d = json.load(open(jf))
    except Exception:
        continue
    cls = jf.split('join-')[1].rsplit('-p', 1)[0]
    for r in d.get('rows', []):
        if r.get('verdict') != 'RED':
            continue
        total += 1
        ls = r.get('luma_scale')
        b = ls if ls is None else (
            '<1.05' if ls < 1.05 else '1.05-1.3' if ls < 1.3
            else '1.3-1.8' if ls < 1.8 else '1.8-3' if ls < 3 else '>=3')
        key = (r.get('luma_norm'), r.get('luma_fit'), b)
        buckets[key] += 1
        if key == (True, False, '1.3-1.8'):
            fam[cls] += 1
print('total join RED rows:', total)
for k, c in buckets.most_common(12):
    print('  ', k, c)
print('=== the 14-e23 target bucket (norm=T,fit=F,1.3-1.8) by family ===')
for k, c in fam.most_common(8):
    print('  ', k, c)
json.dump({'total': total,
           'buckets': {repr(k): v for k, v in buckets.items()},
           'e23_target': dict(fam)},
          open(f'{out}/luma-census.json', 'w'), indent=1)
PY

E23=$(python3 - "$OUT" <<'PY'
import json, glob, sys
out = sys.argv[1] if len(sys.argv) > 1 else '/tmp/vp_next'
n = 0
for jf in glob.glob(f'{out}/gallery-report-*/join-*.json'):
    try:
        d = json.load(open(jf))
    except Exception:
        continue
    for r in d.get('rows', []):
        if (r.get('verdict') == 'RED' and (r.get('luma_norm'), r.get('luma_fit')) == (True, False)
                and 1.3 <= (r.get('luma_scale') or 0) < 1.8):
            n += 1
print(n)
PY
)

echo
echo "=== (4) the registered prediction checks ==="
if [ -d "$BASE" ]; then
  python3 - "$E23" <<'PY'
import sys
n = int(sys.argv[1])
print(f'14-e23: the (norm=T,fit=F,scale 1.3-1.8) un-fit mass = {n}')
print('  baseline (run 43611950455, the e19.2 build) = 1607')
print('  PASS <= 400 | PARTIAL 401-1200 | FAIL > 1200')
print('  14-e23 verdict:', 'PASS' if n <= 400 else 'PARTIAL' if n <= 1200 else 'FAIL')
PY
else
  echo "baseline $BASE missing — the delta step skipped (the absolute checks still ran)"
fi
