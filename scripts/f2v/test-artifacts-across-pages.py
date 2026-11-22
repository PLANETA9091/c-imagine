#!/usr/bin/env python3
"""Unit tests for artifacts-across-pages.py (the 14-e250 pagination fix).

  count mode   sums across multiple pages, dedupes by id, skips blank and
               garbage pages, ignores expired entries, exact-name match only
  merge mode   emits one listing with id-unique artifacts sorted by id and
               the honest total_count, feeding find-artifact.py's stdin

Run:  python3 test-artifacts-across-pages.py
"""
import importlib.util
import json
import pathlib
import subprocess
import sys

HERE = pathlib.Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location(
    "aap", HERE / "artifacts-across-pages.py")
aap = importlib.util.module_from_spec(spec)
spec.loader.exec_module(aap)


def run(blob, *args):
    p = subprocess.run([sys.executable, str(HERE / "artifacts-across-pages.py"),
                        *args], input=blob.encode(), capture_output=True)
    assert p.returncode == 0, p.stderr.decode()
    return p.stdout.decode()


def page(*arts, total=None):
    return json.dumps({"total_count": total if total is not None else len(arts),
                       "artifacts": list(arts)})


def art(i, name, expired=False):
    return {"id": i, "name": name, "expired": expired}


def test_count_across_pages():
    # the 14-aa race guard contract: NAME on argv -> integer count
    p1 = page(art(1, "wall-truth-shots-0"), art(2, "wall-truth-meta-0"))
    p2 = page(art(3, "wall-ours-shots-0"), art(4, "wall-truth-meta-0"))
    blob = p1 + "\n" + p2
    assert run(blob, "wall-truth-meta-0").strip() == "2", "count must sum across pages"
    assert run(blob, "wall-truth-shots-0").strip() == "1"
    assert run(blob, "absent").strip() == "0"


def test_dedupe_by_id():
    # the same artifact listed on two pages counts once
    p1 = page(art(7, "wall-truth-meta-0"))
    blob = p1 + "\n" + p1 + "\n" + p1
    assert run(blob, "wall-truth-meta-0").strip() == "1"


def test_expired_ignored():
    blob = page(art(1, "wall-truth-meta-0"), art(2, "wall-truth-meta-0", expired=True))
    assert run(blob, "wall-truth-meta-0").strip() == "1"


def test_garbage_pages_tolerated():
    # a failed curl emits nothing; a truncated body must not kill the merge
    good = page(art(5, "wall-truth-meta-0"))
    blob = "\n" + good + "\n{\"artifacts\": [\"trunc" + "\n" + good + "\nnoise\n"
    assert run(blob, "wall-truth-meta-0").strip() == "1"
    assert run("", "wall-truth-meta-0").strip() == "0"


def test_pretty_printed_docs():
    # the API returns pretty-printed bodies — multi-line docs must merge
    d = {"total_count": 1, "artifacts": [art(9, "wall-truth-meta-0")]}
    blob = json.dumps(d, indent=2) + "\n" + json.dumps(d, indent=2)
    assert run(blob, "wall-truth-meta-0").strip() == "1"


def test_merge_mode():
    # the faces-sampling contract: merge mode -> find-artifact.py reads it
    p1 = page(art(1, "wall-truth-shots-0"))
    p2 = page(art(2, "wall-truth-meta-0"))
    merged = json.loads(run(p1 + "\n" + p2))
    assert merged["total_count"] == 2
    assert [a["id"] for a in merged["artifacts"]] == [1, 2], "sorted by id"
    # find-artifact.py's exact stdin contract
    fa = subprocess.run([sys.executable, str(HERE / "find-artifact.py"), "meta", "0"],
                        input=run(p1 + "\n" + p2).encode(), capture_output=True)
    assert fa.returncode == 0
    assert fa.stdout.decode().strip() == "2"


if __name__ == "__main__":
    for name, fn in sorted(globals().items()):
        if name.startswith("test_"):
            fn()
            print(f"PASS {name}")
    print("all green")
