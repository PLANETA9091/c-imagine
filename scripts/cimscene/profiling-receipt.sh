#!/usr/bin/env bash
# 14-e37 THE PROFILING RECEIPT — the mechanical 14-dx contract read for a
# completed profiling run: pull profiling-logs, compute the per-leg RSS
# medians and the boot-time ratio, judge against the pinned contract
# (RSS delta <= 400 MiB, boot ratio <= 1.25).
# Usage: profiling-receipt.sh <run-id> [--out DIR]
set -euo pipefail
RUN=${1:?usage: profiling-receipt.sh <run-id> [--out DIR]}
shift || true
OUT=/tmp/prof_receipt
while [ $# -gt 0 ]; do
  case "$1" in
    --out) OUT=$2; shift 2 ;;
    *) echo "unknown arg $1" >&2; exit 1 ;;
  esac
done
HERE=$(cd "$(dirname "$0")" && pwd)
mkdir -p "$OUT"
TOKEN=$(git -C "$HERE/../.." remote get-url origin | sed -E 's|https://[^:]+:([^@]+)@.*|\1|')

echo "== pulling profiling-logs from run $RUN"
AID=$(curl -s -H "Authorization: Bearer $TOKEN" \
  "https://api.github.com/repos/PLANETA9091/c-imagine/actions/runs/$RUN/artifacts?per_page=100" \
  | python3 -c "import json,sys; d=json.load(sys.stdin); print(next((a['id'] for a in d.get('artifacts',[]) if a['name']=='profiling-logs'), ''))")
[ -n "$AID" ] || { echo "no profiling-logs artifact"; exit 1; }
mkdir -p "$OUT/logs"
curl -sL -H "Authorization: Bearer $TOKEN" -o "$OUT/logs.zip" \
  "https://api.github.com/repos/PLANETA9091/c-imagine/actions/artifacts/$AID/zip"
python3 - "$OUT" << 'PY'
import json, statistics, sys, zipfile, os, re
out = sys.argv[1]
z = zipfile.ZipFile(os.path.join(out, 'logs.zip'))
z.extractall(os.path.join(out, 'logs'))

def rss_mib(path):
    # 14-e37 decode (measured on #87, 43641993866): each rss.log interleaves
    # a small probe counter and the sampled RSS; the leg's value = the LAST
    # line (the exit sample). The 4th receipt's numbers reproduce exactly:
    # bare finals 1140.5/1140.5/1143.4 -> median 1140.6 MiB.
    vals = []
    for ln in open(path):
        ln = ln.strip()
        if not ln:
            continue
        kb = int(ln)
        vals.append(kb / 1024.0)
    return [vals[-1]] if vals else []

def boot_s(path):
    # the FIRST "Done (Xs)! For help" = the Paper boot duration; the
    # modded logs carry a SECOND Done from the embedded 1.21.1 layer
    # (0.793s) — not the boot. Measured on #87: modded 12.637 = the
    # receipt's 12.64.
    for ln in open(path, errors='replace'):
        m = re.search(r'Done \(([\d.]+)s\)! For help', ln)
        if m:
            return float(m.group(1))
    return None

summary = {}
for leg in ('bare', 'modded'):
    rss_all, boots = [], []
    for i in (1, 2, 3):
        rp = os.path.join(out, 'logs', f'{leg}-{i}', 'rss.log')
        bp = os.path.join(out, 'logs', f'{leg}-{i}', 'boot.log')
        if os.path.exists(rp):
            v = rss_mib(rp)
            if v:
                rss_all.append(statistics.median(v))
        if os.path.exists(bp):
            b = boot_s(bp)
            if b is not None:
                boots.append(b)
    summary[leg] = {
        'rss_medians': [round(v, 1) for v in rss_all],
        'rss_median_of_medians': round(statistics.median(rss_all), 1) if rss_all else None,
        'boot_walls': boots,
        'boot_median': round(statistics.median(boots), 2) if boots else None,
    }

delta = summary['modded']['rss_median_of_medians'] - summary['bare']['rss_median_of_medians']
ratio = (summary['modded']['boot_median'] or 0) / (summary['bare']['boot_median'] or 1)
summary['rss_delta_mib'] = round(delta, 1)
summary['boot_ratio'] = round(ratio, 3)
summary['contract'] = {'rss_delta_limit_mib': 400, 'boot_ratio_limit': 1.25}
summary['verdict'] = ('PASS' if delta <= 400 and ratio <= 1.25 else 'FAIL')
json.dump(summary, open(os.path.join(out, 'receipt.json'), 'w'), indent=1)
print(json.dumps(summary, indent=1))
PY
echo "== receipt at $OUT/receipt.json"
