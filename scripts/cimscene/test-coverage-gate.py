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
  G. entity manifest skip row note=failed:<t>                 -> RED (named)
     (gallery #25: the spawn crash must be the named backlog, not silence)
  H. entity manifest skip row note=no-instance                -> RED (named)
  I. entity manifest skip row note=dissolved                  -> RED (named)
  J. entity manifest skip row note=wrong-series (foreign)     -> GREEN
     (only the absence notes are red; series ownership is not)
  K. no-walk class total>0 (biome 64, no gallery yet)         -> RED (named)
     (12-aq: the ledger sees the visual class, the red is the backlog)
  L. no-walk class total=-1 (unreadable dynamic registry)     -> RED (named)
  M. no-walk classes all total=0 in the namespace             -> GREEN
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
        "blockentity\t3\t0\n"
        "biome\t0\t0\n"
        "painting_variant\t0\t0\n"
        "wolf_variant\t0\t0\n"
        "cat_variant\t0\t0\n"
        "frog_variant\t0\t0\n"
        "villager_type\t0\t0\n"
        "mob_effect\t0\t0\n"
        "trim_material\t0\t0\n"
        "trim_pattern\t0\t0\n"
        "banner_pattern\t0\t0\n"
        "decorated_pot_pattern\t0\t0\n"
        "enchantment\t0\t0\n"
        "jukebox_song\t0\t0\n"
        "dimension_type\t0\t0\n", encoding="utf-8")
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

    # G: entity spawn crash row -> RED, named (the #25 honesty hole)
    with tempfile.TemporaryDirectory() as td:
        root = pathlib.Path(td)
        build_fixture(root)
        p = root / "scenes" / "gallery-entitydyn-create-p0.tsv"
        p.write_text("cell\tx\ty\tz\tentity\tdyn\tnote\n"
                     "skip\t-1\t-1\t-1\tcreate:carriage_contraption\t0\t"
                     "failed:java.lang.NullPointerException\n", encoding="utf-8")
        r = run_gate(root)
        ok = r.returncode == 1 and "create:carriage_contraption(failed:" in r.stdout \
            and "entity-dyn" in r.stdout
        print("G entity failed row RED:", "PASS" if ok else f"FAIL\n{r.stdout}{r.stderr}")
        failures += 0 if ok else 1

    # H: no-instance row -> RED, named
    with tempfile.TemporaryDirectory() as td:
        root = pathlib.Path(td)
        build_fixture(root)
        p = root / "scenes" / "gallery-entity-create-p1.tsv"
        p.write_text("cell\tx\ty\tz\tentity\tdyn\tnote\n"
                     "skip\t-1\t-1\t-1\tcreate:ghost_mob\t0\tno-instance\n",
                     encoding="utf-8")
        r = run_gate(root)
        ok = r.returncode == 1 and "create:ghost_mob(no-instance)" in r.stdout \
            and "entity-mobs" in r.stdout
        print("H entity no-instance row RED:", "PASS" if ok else f"FAIL\n{r.stdout}{r.stderr}")
        failures += 0 if ok else 1

    # I: dissolved row -> RED, named
    with tempfile.TemporaryDirectory() as td:
        root = pathlib.Path(td)
        build_fixture(root)
        p = root / "scenes" / "gallery-entitylarge-create-p0.tsv"
        p.write_text("cell\tx\ty\tz\tentity\tdyn\tnote\n"
                     "skip\t-1\t-1\t-1\tcreate:sliding_boat\t0\tdissolved\n",
                     encoding="utf-8")
        r = run_gate(root)
        ok = r.returncode == 1 and "create:sliding_boat(dissolved)" in r.stdout \
            and "entity-large" in r.stdout
        print("I entity dissolved row RED:", "PASS" if ok else f"FAIL\n{r.stdout}{r.stderr}")
        failures += 0 if ok else 1

    # J: a foreign skip note (series ownership) must NOT red
    with tempfile.TemporaryDirectory() as td:
        root = pathlib.Path(td)
        build_fixture(root)
        p = root / "scenes" / "gallery-entitydyn-create-p1.tsv"
        p.write_text("cell\tx\ty\tz\tentity\tdyn\tnote\n"
                     "skip\t-1\t-1\t-1\tcreate:other_series_owner\t0\t"
                     "wrong-series\n", encoding="utf-8")
        r = run_gate(root)
        ok = r.returncode == 0 and "COVERAGE: GREEN" in r.stdout
        print("J foreign skip note GREEN:", "PASS" if ok else f"FAIL\n{r.stdout}{r.stderr}")
        failures += 0 if ok else 1

    # K: a no-walk visual class with entries -> RED, named (12-aq)
    with tempfile.TemporaryDirectory() as td:
        root = pathlib.Path(td)
        build_fixture(root)
        idx = root / "scenes" / "gallery-create-index.tsv"
        txt = idx.read_text(encoding="utf-8").replace(
            "biome\t0\t0\n", "biome\t64\t0\n")
        idx.write_text(txt, encoding="utf-8")
        r = run_gate(root)
        ok = r.returncode == 1 and "biome\ttotal=64" in r.stdout \
            and "no-walk-yet" in r.stdout
        print("K no-walk class RED:", "PASS" if ok else f"FAIL\n{r.stdout}{r.stderr}")
        failures += 0 if ok else 1

    # L: an unreadable dynamic registry (-1) -> RED
    with tempfile.TemporaryDirectory() as td:
        root = pathlib.Path(td)
        build_fixture(root)
        idx = root / "scenes" / "gallery-create-index.tsv"
        txt = idx.read_text(encoding="utf-8").replace(
            "painting_variant\t0\t0\n", "painting_variant\t-1\t0\n")
        idx.write_text(txt, encoding="utf-8")
        r = run_gate(root)
        ok = r.returncode == 1 and "painting_variant" in r.stdout \
            and "unreadable-registry" in r.stdout
        print("L unreadable registry RED:", "PASS" if ok else f"FAIL\n{r.stdout}{r.stderr}")
        failures += 0 if ok else 1

    # M: all no-walk classes at 0 -> GREEN (the create case)
    with tempfile.TemporaryDirectory() as td:
        root = pathlib.Path(td)
        build_fixture(root)  # every no-walk row is 0 in the base fixture
        r = run_gate(root)
        ok = r.returncode == 0 and "COVERAGE: GREEN" in r.stdout
        print("M no-walk zeros GREEN:", "PASS" if ok else f"FAIL\n{r.stdout}{r.stderr}")
        failures += 0 if ok else 1

    print("SMOKE:", "ALL PASS" if failures == 0 else f"{failures} FAILURES")
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
