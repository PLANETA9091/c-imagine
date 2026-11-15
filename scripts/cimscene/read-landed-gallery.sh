#!/usr/bin/env bash
# 14-e35 THE LANDED-GALLERY READ — the mechanical one-shot receipt for a
# completed gallery run (the 14-e32 handoff sequence, items 1-3):
#   (1) pull the verdict + the shard reports + the ours meta (the pin= logs);
#   (2) the iso census (the isolation premise's arbiter);
#   (3) the pin receipts joined with the wall-class RED cells;
#   (4) the verdict pipeline vs the e21 baseline (when --baseline given).
# Usage: read-landed-gallery.sh <run-id> [--out DIR] [--baseline DIR] [--ns NS]
set -euo pipefail
RUN=${1:?usage: read-landed-gallery.sh <run-id> [--out DIR] [--baseline DIR] [--ns NS]}
shift || true
OUT=/tmp/vp_landed
BASELINE=
NS=ae2
while [ $# -gt 0 ]; do
  case "$1" in
    --out) OUT=$2; shift 2 ;;
    --baseline) BASELINE=$2; shift 2 ;;
    --ns) NS=$2; shift 2 ;;
    *) echo "unknown arg $1" >&2; exit 1 ;;
  esac
done
HERE=$(cd "$(dirname "$0")" && pwd)
mkdir -p "$OUT"

echo "== (1) pulling verdict+reports+meta from run $RUN"
if [ ! -d "$OUT/verdict" ]; then
  python3 "$HERE/verdict-pull.py" "$RUN" --out "$OUT" --kinds verdict,reports,meta
fi

echo "== (2) the iso census (LEAK-CLEARED / TRUE-DEFECT / ISO-ONLY-RED / GREEN-BOTH)"
python3 "$HERE/iso-census.py" "$OUT"/reports/gallery-report-* 2>&1 | tail -40 || true

echo "== (3) the pin receipts: ours boot logs -> the per-cell pin= lines"
PINLOG=$(mktemp)
find "$OUT/meta" -path '*ours*' -name 'boot.log' -type f -exec cat {} \; > "$PINLOG" 2>/dev/null || true
if [ ! -s "$PINLOG" ]; then
  find "$OUT/meta" -name 'boot.log' -type f -exec cat {} \; > "$PINLOG" 2>/dev/null || true
fi
PINS=$(grep -c "pin=" "$PINLOG" || true)
echo "pin= lines total: $PINS"
if [ "$PINS" -gt 0 ]; then
  # the source-chain census
  grep -o "src=[a-z-]*" "$PINLOG" | sort | uniq -c | sort -rn || true
  # the pin value census
  grep -o "pin=[0-9]*/[0-9]*" "$PINLOG" | sort | uniq -c | sort -rn | head -12 || true
  # the wall-class join with the RED cells: the RED states from the pulled joins
  python3 - "$OUT" "$NS" "$PINLOG" << 'PY'
import json, glob, os, sys, re, collections
out, ns, pinlog = sys.argv[1], sys.argv[2], sys.argv[3]
# pin lines keyed by state
pins = collections.defaultdict(list)
for m in re.finditer(r'state=(\S+) .*? pin=(\d+)/(\d+) src=([a-z-]+)', open(pinlog, errors='replace').read()):
    pins[m.group(1)].append((m.group(2), m.group(3), m.group(4)))
reds = collections.Counter()
unmatched = 0
for f in glob.glob(os.path.join(out, 'reports', 'gallery-report-*', f'join-{ns}-p*.json')):
    d = json.load(open(f))
    for r in d.get('rows', []):
        if r.get('verdict') != 'RED':
            continue
        st = r.get('state', '')
        fam = st.split('[')[0]
        hits = pins.get(st)
        if not hits:
            unmatched += 1
            reds[(fam, '<no-pin-line>')] += 1
        else:
            for b, s, src in hits:
                reds[(fam, f'{b}/{s} {src}')] += 1
print('RED x pin join (top 16):')
for (fam, pin), n in reds.most_common(16):
    print(f'  {n:4d}  {fam:52s} pin={pin}')
print('RED rows with no pin line at all:', unmatched)
PY
else
  echo "NO pin= lines found — the run predates 4524dedc or the boot logs are missing"
fi

echo "== (4) the verdict pipeline vs the baseline"
if [ -n "$BASELINE" ]; then
  bash "$HERE/verdict_pipeline.sh" "$RUN" --baseline "$BASELINE" --out "$OUT/pipe" || true
else
  echo "no --baseline given — the pipeline step skipped"
fi
echo "== read complete: $OUT"
