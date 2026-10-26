#!/usr/bin/env python3
"""Vanilla server jar class census (ground-truth job).

Downloads the vanilla server jar for a version from Mojang's manifest,
extracts the inner jar from the bundler, and produces:
  <out>.json  — machine-readable census
  <out>.md    — human-readable summary

Usage: census_server.py <version> <out-prefix>
"""

import json
import sys
import urllib.request
import zipfile
from collections import Counter
from pathlib import Path

MANIFEST = "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json"


def fetch(url: str, dest: Path) -> None:
    print(f"GET {url}")
    urllib.request.urlretrieve(url, dest)


def main() -> None:
    version, out_prefix = sys.argv[1], sys.argv[2]
    work = Path(f"/tmp/census-{version}")
    work.mkdir(parents=True, exist_ok=True)

    manifest = json.loads(urllib.request.urlopen(MANIFEST).read())
    entry = next(v for v in manifest["versions"] if v["id"] == version)
    meta = json.loads(urllib.request.urlopen(entry["url"]).read())
    server_url = meta["downloads"]["server"]["url"]

    bundle = work / "server.jar"
    fetch(server_url, bundle)

    with zipfile.ZipFile(bundle) as z:
        names = z.namelist()
        inner_name = None
        # Modern bundlers: META-INF/versions.list (space-separated sha version path
        # or JSON); legacy jars may carry the classes directly.
        if "META-INF/versions.list" in names:
            raw = z.read("META-INF/versions.list").decode().strip()
            try:
                parsed = json.loads(raw)
                if isinstance(parsed, list) and parsed:
                    v0 = parsed[0]
                    inner_name = v0["path"] if isinstance(v0, dict) else v0.split()[2]
            except json.JSONDecodeError:
                lines = [ln for ln in raw.splitlines() if ln.strip()]
                if lines:
                    inner_name = lines[0].split()[2]
        if inner_name is None:
            # Legacy single jar: count classes directly from the bundle.
            inner_path = bundle
        else:
            if not inner_name.startswith("META-INF/versions/"):
                inner_name = "META-INF/versions/" + inner_name
            inner = z.read(inner_name)
            inner_path = work / "inner.jar"
            inner_path.write_bytes(inner)

    classes_total = 0
    packages = Counter()
    data_files = Counter()
    with zipfile.ZipFile(inner_path) as z:
        for name in z.namelist():
            if name.endswith(".class") and not name.endswith("package-info.class"):
                classes_total += 1
                parts = name.split("/")
                if len(parts) >= 3:
                    packages["/".join(parts[:3])] += 1
                elif len(parts) >= 2:
                    packages["/".join(parts[:2])] += 1
                else:
                    packages["(root)"] += 1
            elif name.startswith("data/") and not name.endswith("/"):
                parts = name.split("/")
                if len(parts) >= 3:
                    data_files[parts[2]] += 1

    result = {
        "version": version,
        "classes_total": classes_total,
        "top_packages": dict(packages.most_common(40)),
        "data_file_types": dict(data_files.most_common(60)),
        "source": server_url,
    }
    Path(f"{out_prefix}.json").write_text(json.dumps(result, indent=1))
    lines = [
        f"# Vanilla server census — {version}",
        "",
        f"Classes: **{classes_total}** · data file types: **{len(data_files)}**",
        "",
        "| package (top-3 segments) | classes |",
        "|---|---|",
    ]
    for pkg, n in packages.most_common(25):
        lines.append(f"| {pkg} | {n} |")
    lines += ["", "| data type | files |", "|---|---|"]
    for dt, n in data_files.most_common(30):
        lines.append(f"| {dt} | {n} |")
    Path(f"{out_prefix}.md").write_text("\n".join(lines) + "\n")
    print(f"census {version}: {classes_total} classes, {len(data_files)} data types")


if __name__ == "__main__":
    main()
