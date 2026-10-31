#!/usr/bin/env python3
"""Smoke tests for coverage-gate.py (the COVERAGE law, worklog 12-ao).

Builds synthetic fixture trees and asserts the gate's verdicts:
  A. full exhaustive fixture (create ns, empty be-uncovered)  -> GREEN
  B. one missing ours fluid shot                              -> RED
  C. non-empty be-uncovered file                              -> RED (named)
  D. missing be-uncovered file                                -> RED
  E. wrong shot-name convention (gal-i0 vs gal-i-create0)     -> RED
     (the false-red fix: the gate must join the names the walks produce)
  F. minecraft control ns with the bare naming                -> GREEN
"""
import pathlib
import shutil
import subprocess
import sys
import tempfile

HERE = pathlib.Path(__file__).resolve().parent
GATE = HERE / "coverage-gate.py"


def build_fixture(root, ns="create"):
    scenes = root / "scenes"
    tshots = root / "truth-shots"
    oshots = root / "ours-shots"
    for d in (scenes, tshots, oshots):
        d.mkdir(parents=True, exist_ok=True)
    cells = 77 if ns != "special" else 77
    ecells = 18
    (scenes / f"gallery-{ns}-index.tsv").write_text(
        "class\ttotal\tcellsPerPage\n"
        f"block\t154\t{cells}\n"
        f"entity\t{ecells * 2}\t{ecells}\n"
        f"item\t{cells}\t{cells}\n"
        "particle\t10\t77\n"
        "fluid\t18\t18\n"
        "blockentity\t3\t0\n", encoding="utf-8")
    # block pages 0..1 (+ terminal page 2)
    for p in (0, 1):
        (scenes / f"gallery-{ns}-p{p}.tsv").write_text("cell\tx\ty\tz\tstate\n", encoding="utf-8")
    # entity series pages 0..1 each
    for tag in ("entity", "entitydyn", "entitylarge"):
        for p in (0, 1):
            (scenes / f"gallery-{tag}-{ns}-p{p}.tsv").write_text(
                "cell\tx\ty\tz\tentity\tdyn\tnote\n", encoding="utf-8")
    for p in (0,):
        for cls in ("item", "particle", "fluid"):
            (scenes / f"gallery-{cls}-{ns}-p{p}.tsv").write_text(
                "cell\tx\ty\tz\tthing\tdyn\tnote\n", encoding="utf-8")
    (scenes / f"gallery-{ns}-be-uncovered.tsv").write_text("", encoding="utf-8")

    def shots(base, prefix):
        for p in (0, 1):
            (base / f"{prefix}gal-p{p}.png").write_bytes(b"x")
        # the SHOT NAME LAW: create mob shots carry the series word
        # (gal-emobs-create0.png), minecraft's mob walk predates it
        # (gal-e0.png); dyn/large carry theirs on both
        words = ("mobs", "dyn", "large") if ns != "minecraft" else ("", "dyn", "large")
        for word in words:
            for p in (0, 1):
                name = f"{prefix}gal-e{word}-{ns}{p}.png" if ns != "minecraft" \
                    else f"{prefix}gal-e{word}{p}.png"
                (base / name).write_bytes(b"x")
        for cls, word in (("item", "i"), ("particle", "pa"), ("fluid", "fl")):
            name = f"{prefix}gal-{word}-{ns}0.png" if ns != "minecraft" \
                else f"{prefix}gal-{word}0.png"
            (base / name).write_bytes(b"x")

    shots(tshots, "")
    shots(oshots, "ours-")

    terminal = []
    terminal.append(f"gallery done ns={ns} page=2 cells=0 skipped_fluid=0 "
                    "skipped_invisible=0 exhausted=1")
    for tag in ("entity", "entitydyn", "entitylarge"):
        terminal.append(f"gallery-{tag} done ns={ns} page=2 cells=0 dyn=0 "
                        "skipped_no_instance=0 skipped_wrong_series=0 "
                        "dissolved=0 exhausted=1")
    for tag in ("item", "particle", "fluid"):
        terminal.append(f"gallery-{tag} done ns={ns} page=1 cells=0 failed=0 "
                        "exhausted=1")
    (root / "boot.log").write_text("\n".join(terminal) + "\n", encoding="utf-8")


def run_gate(root, ns="create"):
    return subprocess.run(
        [sys.executable, str(GATE), "--ns", ns,
         "--index", str(root / "scenes" / f"gallery-{ns}-index.tsv"),
         "--scenes", str(root / "scenes"),
         "--truth-shots", str(root / "truth-shots"),
         "--ours-shots", str(root / "ours-shots"),
         "--boot", str(root / "boot.log")],
        capture_output=True, text=True)


def main():
    failures = 0

    # A: GREEN path
    with tempfile.TemporaryDirectory() as td:
        root = pathlib.Path(td)
        build_fixture(root)
        r = run_gate(root)
        ok = r.returncode == 0 and "COVERAGE: GREEN" in r.stdout
        print("A full-fixture GREEN:", "PASS" if ok else f"FAIL\n{r.stdout}{r.stderr}")
        failures += 0 if ok else 1

    # B: missing ours fluid shot -> RED
    with tempfile.TemporaryDirectory() as td:
        root = pathlib.Path(td)
        build_fixture(root)
        (root / "ours-shots" / "ours-gal-fl-create0.png").unlink()
        r = run_gate(root)
        ok = r.returncode == 1 and "fluid" in r.stdout and "missing_ours_shots=1" in r.stdout
        print("B missing ours fluid shot RED:", "PASS" if ok else f"FAIL\n{r.stdout}{r.stderr}")
        failures += 0 if ok else 1

    # C: non-empty be-uncovered -> RED, named
    with tempfile.TemporaryDirectory() as td:
        root = pathlib.Path(td)
        build_fixture(root)
        (root / "scenes" / "gallery-create-be-uncovered.tsv").write_text(
            "create:ghost_be\n", encoding="utf-8")
        r = run_gate(root)
        ok = r.returncode == 1 and "create:ghost_be" in r.stdout
        print("C be-uncovered named RED:", "PASS" if ok else f"FAIL\n{r.stdout}{r.stderr}")
        failures += 0 if ok else 1

    # D: missing be-uncovered file -> RED
    with tempfile.TemporaryDirectory() as td:
        root = pathlib.Path(td)
        build_fixture(root)
        (root / "scenes" / "gallery-create-be-uncovered.tsv").unlink()
        r = run_gate(root)
        ok = r.returncode == 1 and "reconciliation-file-missing" in r.stdout
        print("D be-uncovered missing RED:", "PASS" if ok else f"FAIL\n{r.stdout}{r.stderr}")
        failures += 0 if ok else 1

    # E: the naming-law guard — a WRONG-named item shot must be RED
    with tempfile.TemporaryDirectory() as td:
        root = pathlib.Path(td)
        build_fixture(root)
        (root / "truth-shots" / "gal-i-create0.png").rename(
            root / "truth-shots" / "gal-i0.png")
        r = run_gate(root)
        ok = r.returncode == 1 and "item" in r.stdout and "missing_truth_shots=1" in r.stdout
        print("E wrong item shot name RED:", "PASS" if ok else f"FAIL\n{r.stdout}{r.stderr}")
        failures += 0 if ok else 1

    # F: minecraft control ns with the bare naming -> GREEN
    with tempfile.TemporaryDirectory() as td:
        root = pathlib.Path(td)
        build_fixture(root, ns="minecraft")
        r = run_gate(root, ns="minecraft")
        ok = r.returncode == 0 and "COVERAGE: GREEN" in r.stdout
        print("F minecraft bare naming GREEN:", "PASS" if ok else f"FAIL\n{r.stdout}{r.stderr}")
        failures += 0 if ok else 1

    print("SMOKE:", "ALL PASS" if failures == 0 else f"{failures} FAILURES")
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
