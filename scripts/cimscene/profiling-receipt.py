#!/usr/bin/env python3
"""Read all six boot/RSS legs; missing evidence never proves a passing budget."""
import argparse
import json
import math
from pathlib import Path
import re
import statistics
import subprocess
import sys
import tempfile
import zipfile

REPO = "repos/PLANETA9091/c-imagine"


def summarize(read, provenance):
    result = dict(provenance)
    for leg in ("bare", "modded"):
        rss, boots = [], []
        for index in (1, 2, 3):
            prefix = f"{leg}-{index}/"
            samples = [int(line) for line in read(prefix + "rss.log").splitlines() if line.strip()]
            if not samples or min(samples) <= 0:
                raise ValueError(f"missing positive RSS samples: {prefix}")
            match = re.search(r'Done \(([\d.]+)s\)! For help', read(prefix + "boot.log"))
            if match is None:
                raise ValueError(f"missing Paper boot receipt: {prefix}")
            boot = float(match.group(1))
            if not math.isfinite(boot) or boot <= 0:
                raise ValueError(f"invalid Paper boot duration: {prefix}")
            # Match scripts/perf/boot-budget.py: median of per-boot RSS
            # maxima, never the final sample (which can hide a transient peak).
            rss.append(max(samples) / 1024.0)
            boots.append(boot)
        result[leg] = {"rss_peaks_mib": rss, "rss_median_peak_mib": statistics.median(rss),
                       "boot_walls": boots, "boot_median": statistics.median(boots)}
    delta = result["modded"]["rss_median_peak_mib"] - result["bare"]["rss_median_peak_mib"]
    ratio = result["modded"]["boot_median"] / result["bare"]["boot_median"]
    # Compare unrounded values; rounding must not manufacture a PASS.
    result.update(rss_delta_mib=delta, boot_ratio=ratio,
                  contract={"rss_delta_limit_mib": 400, "boot_ratio_limit": 1.25},
                  verdict="PASS" if delta <= 400 and ratio <= 1.25 else "FAIL")
    return result


def api(endpoint, *flags):
    return json.loads(subprocess.check_output(["gh", "api", endpoint, *flags], timeout=90))


def remote_summary(run_id, output):
    run = api(f"{REPO}/actions/runs/{run_id}")
    if run.get("status") != "completed":
        raise ValueError("profiling run is incomplete")
    pages = api(f"{REPO}/actions/runs/{run_id}/artifacts?per_page=100", "--paginate", "--slurp")
    artifacts = [a for page in pages for a in page["artifacts"]
                 if a["name"] == "profiling-logs" and not a.get("expired")]
    if len(artifacts) != 1:
        raise ValueError("expected exactly one unexpired profiling-logs artifact")
    artifact_id = artifacts[0]["id"]
    with tempfile.NamedTemporaryFile(dir=output, suffix=".part") as temporary:
        subprocess.run(["gh", "api", f"{REPO}/actions/artifacts/{artifact_id}/zip"],
                       stdout=temporary, check=True, timeout=600)
        temporary.flush()
        with zipfile.ZipFile(temporary.name) as source:
            if source.testzip() is not None:
                raise ValueError("corrupt profiling artifact")
            result = summarize(lambda name: source.read(name).decode("utf-8", errors="replace"),
                               {"source": "github-artifact", "run_id": run_id,
                                "source_sha": run["head_sha"], "artifact_id": artifact_id})
        archive = output / f"profiling-{run_id}-{artifact_id}.zip"
        archive.write_bytes(Path(temporary.name).read_bytes())
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("run", type=int)
    parser.add_argument("--out", type=Path, default=Path("/tmp/prof_receipt"))
    parser.add_argument("--logs", type=Path, help="offline log tree; source identity is explicitly unverified")
    args = parser.parse_args()
    args.out.mkdir(parents=True, exist_ok=True)
    receipt = args.out / "receipt.json"
    # A failed reuse must not leave a stale passing receipt.
    receipt.write_text(json.dumps({"run_id": args.run, "verdict": "INCOMPLETE"}) + "\n")
    try:
        if args.logs is not None:
            result = summarize(lambda name: (args.logs / name).read_text(errors="replace"),
                               {"source": "offline-unverified", "run_id": args.run,
                                "source_sha": None, "artifact_id": None})
        else:
            result = remote_summary(args.run, args.out)
    except (OSError, ValueError, KeyError, zipfile.BadZipFile, subprocess.SubprocessError) as error:
        print(f"profiling receipt incomplete: {error}", file=sys.stderr)
        return 1
    data = json.dumps(result, indent=2) + "\n"
    receipt.write_text(data)
    print(data, end="")
    return 0 if result["verdict"] == "PASS" else 1


if __name__ == "__main__":
    sys.exit(main())
