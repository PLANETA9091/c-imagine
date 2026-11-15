#!/usr/bin/env bash
# 14-e35 THE LANDED-GALLERY READ — the mechanical one-shot receipt for a
# completed gallery run (the 14-e32 handoff sequence, items 1-3):
#   (1) pull the verdict + the shard reports + the ours meta (the pin= logs);
#   (2) the iso census (the isolation premise's arbiter);
#   (3) the pin receipts joined with the wall-class RED cells;
#   (4) the verdict pipeline vs the e21 baseline (when --baseline given).
# Usage: read-landed-gallery.sh <run-id> [--out DIR] [--baseline DIR] [--ns NS] [--local]
# --local reads an existing tree and explicitly leaves run identity unverified.
set -euo pipefail
RUN=${1:?usage: read-landed-gallery.sh <run-id> [--out DIR] [--baseline DIR] [--ns NS]}
shift || true
OUT=/tmp/vp_landed
BASELINE=
NS=ae2
LOCAL=0
while [ $# -gt 0 ]; do
  case "$1" in
    --out) OUT=$2; shift 2 ;;
    --baseline) BASELINE=$2; shift 2 ;;
    --ns) NS=$2; shift 2 ;;
    --local) LOCAL=1; shift ;;
    *) echo "unknown arg $1" >&2; exit 1 ;;
  esac
done
HERE=$(cd "$(dirname "$0")" && pwd)
mkdir -p "$OUT"

echo "== (1) pulling verdict+reports+meta from run $RUN"
if [ "$LOCAL" = 1 ]; then
  echo "OFFLINE evidence tree: run identity is unverified"
else
  python3 - "$RUN" <<'PY_PREFLIGHT'
import json, subprocess, sys
run_id = sys.argv[1]
run = json.loads(subprocess.check_output(["gh", "run", "view", run_id, "--json", "status,conclusion,headSha"]))
if run["status"] != "completed" or run["conclusion"] not in ("success", "failure"):
    raise SystemExit("incomplete gallery: run did not finish with a measured verdict")
pages = json.loads(subprocess.check_output(["gh", "api",
    f"repos/PLANETA9091/c-imagine/actions/runs/{run_id}/artifacts?per_page=100", "--paginate", "--slurp"]))
names = {a["name"] for page in pages for a in page["artifacts"] if not a.get("expired")}
expected = {"gallery-verdict"} | {f"{stem}{i}" for stem in
    ("gallery-report-", "wall-ours-meta-", "wall-truth-meta-") for i in range(8)}
if expected - names:
    raise SystemExit("incomplete gallery artifacts: " + ", ".join(sorted(expected - names)))
print("Measured source:", run["headSha"], "run:", run_id)
PY_PREFLIGHT
  python3 "$HERE/verdict-pull.py" "$RUN" --out "$OUT" --kinds verdict,reports,meta
fi
REPORTS=()
for dir in "$OUT"/gallery-report-*; do
  [ ! -d "$dir" ] || REPORTS+=("$dir")
done
[ "${#REPORTS[@]}" -eq 8 ] || { echo "incomplete gallery: expected eight report directories" >&2; exit 1; }

echo "== (2) the iso census (LEAK-CLEARED / TRUE-DEFECT / ISO-ONLY-RED / GREEN-BOTH)"
python3 "$HERE/iso-census.py" "${REPORTS[@]}" --ns "$NS" --top 5

echo "== (3) the pin receipts: ours boot logs -> the per-cell pin= lines"
PINLOG=$(mktemp)
trap 'rm -f "$PINLOG"' EXIT
META=()
for dir in "$OUT"/wall-ours-meta-*; do
  [ ! -d "$dir" ] || META+=("$dir")
done
[ "${#META[@]}" -eq 8 ] || { echo "incomplete gallery: expected eight ours metadata directories" >&2; exit 1; }
find "${META[@]}" -name 'boot.log' -type f -exec cat {} \; > "$PINLOG"
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
for f in glob.glob(os.path.join(out, 'gallery-report-*', f'join-{ns}-p*.json')):
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
  exit 1
fi

echo "== (4) the verdict pipeline vs the baseline"
if [ -n "$BASELINE" ]; then
  bash "$HERE/verdict_pipeline.sh" "$RUN" --baseline "$BASELINE" --out "$OUT/pipe"
else
  echo "no --baseline given — the pipeline step skipped"
fi
echo "== read complete: $OUT"
