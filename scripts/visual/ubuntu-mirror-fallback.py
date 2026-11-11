#!/usr/bin/env python3
"""Retarget an unreachable Ubuntu security host to the official archive.

The caller checks DNS first. Suites, package pins, keys and signature checks
remain unchanged; archive.ubuntu.com also publishes the security suites.
"""
import pathlib
import re
import sys


def rewrite(apt_root):
    paths = [apt_root / "sources.list", apt_root / "apt-mirrors.txt"]
    for pattern in ("*.list", "*.sources"):
        paths.extend((apt_root / "sources.list.d").glob(pattern))
    changed = 0
    for path in paths:
        if not path.is_file():
            continue
        old = path.read_text()
        new = re.sub(r"(?<=://)security\.ubuntu\.com(?=[:/\s]|$)",
                     "archive.ubuntu.com", old)
        if new != old:
            path.write_text(new)
            changed += 1
    return changed


if __name__ == "__main__":
    print("Ubuntu security mirror fallback: updated files=" + str(rewrite(pathlib.Path(sys.argv[1]))))
