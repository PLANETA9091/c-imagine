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
  N. particle manifest skip row no-simple-options             -> DIAG, GREEN
     (14-cs: particle is a diagnostic class — the absence is NAMED in the
     verdict line, the run stays green; measured #30: all 13 create
     particles rejected the bare spawn)
  O. fluid manifest skip row no-channel                       -> RED (named)
     (measured #30: 4 of 8 create fluids have no legacy block/bucket)
  P. shard shot lists (13-b): listing covers -> GREEN; a hole -> RED
  Q. biome walk (multi-ns block law): walked diagnostic class -> DIAG-OK,
     run GREEN (2026-11-04: the biome-tint walk exists, not gate-hard yet)
  R. diagnostic class walk crashed (missing biome manifest)   -> DIAG-INCOMPLETE
     printed, run GREEN (the hole is VISIBLE, the verdict is not red yet)
  S. held shots ride the item pages: present -> DIAG-OK; one missing
     ours held shot -> DIAG-INCOMPLETE, run GREEN (diagnostic flip law)
  T. inv walk (round 2): walked diagnostic class -> DIAG-OK, run GREEN;
     a missing ours inv shot -> DIAG-INCOMPLETE, run GREEN
  U. fluid no-truth-surface skip row (round 2 ladder exhausted) -> RED named
  V. particle no-simple-options:<detail> suffix note          -> DIAG, GREEN
     (round 2: the note carries the programmatic-ladder failure reason;
     the prefix match must keep NAMING it — 14-cs made the class
     diagnostic, so the name surfaces, the verdict never reds)
  W. entity client-crash-banned row (merged round 3, 14-am)   -> RED named
     (the ban list saved the client — the banned subject still reds with
     its name, the ban can never silently eat verdict content)
  X. mobs manifest: cells + wrong-series:<kind> rows together -> GREEN
     (measured #114: the walk records series ownership as named skip rows;
     ownership is not absence — the owning series reds its own absences)

THE LOCAL-RUNNER HOLE (round 2, hardening for THIS workspace only): the
owner's agent sandbox resolves sys.executable to the ZCode desktop AppImage,
so a naive subprocess.run([sys.executable, GATE, ...]) SPAWNS THE DESKTOP
APP and every test false-FAILs locally. The harness therefore honours
PYGATE_PYTHON: PY = os.environ.get("PYGATE_PYTHON") or sys.executable.
CI is unaffected (no such env var there); local verification here runs
  PYGATE_PYTHON=/usr/bin/python3 python3 scripts/cimscene/test-coverage-gate.py
"""
import pathlib
import os
import shutil
import subprocess
import sys
import tempfile

HERE = pathlib.Path(__file__).resolve().parent
GATE = HERE / "coverage-gate.py"
# THE LOCAL-RUNNER HOLE (see the module docstring): an explicit interpreter
# override for sandboxes where sys.executable is not a plain python3.
PY = os.environ.get("PYGATE_PYTHON") or sys.executable


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
        "sound\t0\t77\n"
        "gui\t0\t1\n"
        "biome\t0\t1\n"
        "inv\t0\t36\n"
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
        # THE SHOT NAME LAW (multi-ns): the block class carries the ns
        # infix too (gal-p-create0.png); only the minecraft control keeps
        # the bare original gal-p{p}.png (no block walk exists for it)
        for p in (0, 1):
            name = f"{prefix}gal-p-{ns}{p}.png" if ns != "minecraft" \
                else f"{prefix}gal-p{p}.png"
            (base / name).write_bytes(b"x")
        # the SHOT NAME LAW: create mob shots carry the series word
        # (gal-emobs-create0.png), minecraft's mob walk predates it
        # (gal-e0.png); dyn/large carry theirs on both
        words = ("mobs", "dyn", "large") if ns != "minecraft" else ("", "dyn", "large")
        for word in words:
            for p in (0, 1):
                name = f"{prefix}gal-e{word}-{ns}{p}.png" if ns != "minecraft" \
                    else f"{prefix}gal-e{word}{p}.png"
                (base / name).write_bytes(b"x")
        for cls, word in (("item", "i"), ("particle", "pa"), ("fluid", "fl"),
                          ("held", "h"), ("sound", "s"), ("gui", "gui"),
                          ("inv", "inv"),
                          ("biome", "b")):
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
        [PY, str(GATE), "--ns", ns,
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
    # (biome LEFT the no-walk set 2026-11-04 — the walk exists, diagnostic;
    # mob_effect still has no walk, so K rides it now)
    with tempfile.TemporaryDirectory() as td:
        root = pathlib.Path(td)
        build_fixture(root)
        idx = root / "scenes" / "gallery-create-index.tsv"
        txt = idx.read_text(encoding="utf-8").replace(
            "mob_effect\t0\t0\n", "mob_effect\t64\t0\n")
        idx.write_text(txt, encoding="utf-8")
        r = run_gate(root)
        ok = r.returncode == 1 and "mob_effect\ttotal=64" in r.stdout \
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

    # N: particle absence row -> NAMED, run GREEN (14-cs: particle joined
    # DIAGNOSTIC_CLASSES — the absence is reported, the verdict never reds;
    # the 12-as red law is retired with the flip)
    with tempfile.TemporaryDirectory() as td:
        root = pathlib.Path(td)
        build_fixture(root)
        p = root / "scenes" / "gallery-particle-create-p0.tsv"
        p.write_text("cell\tx\ty\tz\tparticle\tdyn\tnote\n"
                     "skip\t-1\t-1\t-1\tcreate:rot\t1\tno-simple-options\n",
                     encoding="utf-8")
        r = run_gate(root)
        ok = r.returncode == 0 and "COVERAGE: GREEN" in r.stdout \
            and "DIAG-INCOMPLETE\tparticle" in r.stdout \
            and "create:rot(no-simple-options)" in r.stdout
        print("N particle absence DIAG named GREEN:",
              "PASS" if ok else f"FAIL\n{r.stdout}{r.stderr}")
        failures += 0 if ok else 1

    # A literal resource path "null" does not justify removing a registered type.
    with tempfile.TemporaryDirectory() as td:
        root = pathlib.Path(td)
        build_fixture(root)
        (root / "scenes" / "gallery-particle-create-p0.tsv").write_text(
            "cell\tx\ty\tz\tparticle\tdyn\tnote\n"
            "skip\t-1\t-1\t-1\tcreate:null\t1\tnull-path-dead-id\n",
            encoding="utf-8")
        r = run_gate(root)
        ok = r.returncode == 0 and "COVERAGE: GREEN" in r.stdout \
            and "create:null(null-path-dead-id)" in r.stdout \
            and "DIAG-INCOMPLETE\tparticle" in r.stdout
        print("N2 name-based particle exclusion DIAG GREEN:",
              "PASS" if ok else f"FAIL\n{r.stdout}{r.stderr}")
        failures += 0 if ok else 1

    # O: fluid no-channel row -> RED, named (12-as)
    with tempfile.TemporaryDirectory() as td:
        root = pathlib.Path(td)
        build_fixture(root)
        p = root / "scenes" / "gallery-fluid-create-p0.tsv"
        p.write_text("cell\tx\ty\tz\tfluid\tdyn\tchannel\textra\n"
                     "skip\t-1\t-1\t-1\tcreate:honey_flow\t1\tno-channel\n",
                     encoding="utf-8")
        r = run_gate(root)
        ok = r.returncode == 1 and "create:honey_flow(no-channel)" in r.stdout \
            and "fluid" in r.stdout
        print("O fluid no-channel RED:", "PASS" if ok else f"FAIL\n{r.stdout}{r.stderr}")
        failures += 0 if ok else 1

    # P: SHARD SHOT LISTS (13-b) — real PNGs replaced by shots.txt listings.
    # P1: the listing covers every expected name -> GREEN (existence joins
    #     the listing without any PNG on disk).
    # P2: the listing drops one name -> RED with the missing count.
    with tempfile.TemporaryDirectory() as td:
        root = pathlib.Path(td)
        build_fixture(root)
        names = sorted(p.name for p in (root / "truth-shots").glob("*.png"))
        onames = sorted(p.name for p in (root / "ours-shots").glob("*.png"))
        for d, lst in (("truth-shots", names), ("ours-shots", onames)):
            for f in (root / d).glob("*.png"):
                f.unlink()
            (root / d / "shots.txt").write_text("\n".join(lst) + "\n",
                                                encoding="utf-8")
        r = run_gate(root)
        ok = r.returncode == 0 and "COVERAGE: GREEN" in r.stdout \
            and "shard shot lists in effect" in r.stdout
        print("P1 shard shot lists GREEN:", "PASS" if ok else f"FAIL\n{r.stdout}{r.stderr}")
        failures += 0 if ok else 1
    with tempfile.TemporaryDirectory() as td:
        root = pathlib.Path(td)
        build_fixture(root)
        names = sorted(p.name for p in (root / "truth-shots").glob("*.png"))
        onames = sorted(p.name for p in (root / "ours-shots").glob("*.png"))
        for d, lst in (("truth-shots", names), ("ours-shots", onames)):
            for f in (root / d).glob("*.png"):
                f.unlink()
            (root / d / "shots.txt").write_text("\n".join(lst) + "\n",
                                                encoding="utf-8")
        (root / "ours-shots" / "shots.txt").write_text(
            "\n".join(n for n in onames if n != "ours-gal-p-create1.png") + "\n",
            encoding="utf-8")
        r = run_gate(root)
        ok = r.returncode == 1 and "missing_ours_shots=1" in r.stdout
        print("P2 shard shot list hole RED:", "PASS" if ok else f"FAIL\n{r.stdout}{r.stderr}")
        failures += 0 if ok else 1

    # Q: the biome walk (multi-ns law): a walked diagnostic class reports
    # DIAG-OK and the run stays GREEN — the 2026-11-04 diagnostic-first
    # contract (the biome-tint channel is the P1 BOP proof surface, not
    # measured stable yet)
    with tempfile.TemporaryDirectory() as td:
        root = pathlib.Path(td)
        build_fixture(root)
        idx = root / "scenes" / "gallery-create-index.tsv"
        txt = idx.read_text(encoding="utf-8").replace(
            "biome\t0\t1\n", "biome\t3\t1\n")
        idx.write_text(txt, encoding="utf-8")
        for p in (0, 1, 2):
            (root / "scenes" / f"gallery-biome-create-p{p}.tsv").write_text(
                "cell\tx\ty\tz\tbiome\tnote\n", encoding="utf-8")
            for d, prefix in (("truth-shots", ""), ("ours-shots", "ours-")):
                (root / d / f"{prefix}gal-b-create{p}.png").write_bytes(b"x")
        with open(root / "boot.log", "a", encoding="utf-8") as f:
            f.write("gallery-biome done ns=create page=3 cells=0 failed=0 "
                    "exhausted=1\n")
        r = run_gate(root)
        ok = r.returncode == 0 and "DIAG-OK\tbiome\ttotal=3" in r.stdout
        print("Q walked biome diagnostic DIAG-OK GREEN:",
              "PASS" if ok else f"FAIL\n{r.stdout}{r.stderr}")
        failures += 0 if ok else 1

    # R: a diagnostic walk that CRASHED (missing biome manifest) is
    # VISIBLE (DIAG-INCOMPLETE) but never reds the run — the honest hole
    # is printed, the flip law keeps the verdict green until measured
    with tempfile.TemporaryDirectory() as td:
        root = pathlib.Path(td)
        build_fixture(root)
        idx = root / "scenes" / "gallery-create-index.tsv"
        txt = idx.read_text(encoding="utf-8").replace(
            "biome\t0\t1\n", "biome\t3\t1\n")
        idx.write_text(txt, encoding="utf-8")
        (root / "scenes" / "gallery-biome-create-p0.tsv").write_text(
            "cell\tx\ty\tz\tbiome\tnote\n", encoding="utf-8")
        r = run_gate(root)
        ok = r.returncode == 0 and "DIAG-INCOMPLETE\tbiome" in r.stdout
        print("R crashed diagnostic walk visible-not-red:",
              "PASS" if ok else f"FAIL\n{r.stdout}{r.stderr}")
        failures += 0 if ok else 1

    # S: held rides the item pages — a missing held shot is DIAG-INCOMPLETE
    # (diagnostic), never COVERAGE-INCOMPLETE
    with tempfile.TemporaryDirectory() as td:
        root = pathlib.Path(td)
        build_fixture(root)
        (root / "ours-shots" / "ours-gal-h-create0.png").unlink()
        r = run_gate(root)
        ok = r.returncode == 0 and "DIAG-INCOMPLETE\theld" in r.stdout \
            and "missing_ours_shots=1" in r.stdout
        print("S held rides item pages diagnostic:",
              "PASS" if ok else f"FAIL\n{r.stdout}{r.stderr}")
        failures += 0 if ok else 1

    # T: the inv class (round 2) — a walked diagnostic class: present shots
    # report DIAG-OK; a missing ours inv shot is DIAG-INCOMPLETE, run GREEN
    with tempfile.TemporaryDirectory() as td:
        root = pathlib.Path(td)
        build_fixture(root)
        idx = root / "scenes" / "gallery-create-index.tsv"
        txt = idx.read_text(encoding="utf-8").replace(
            "inv\t0\t36\n", "inv\t36\t36\n")
        idx.write_text(txt, encoding="utf-8")
        (root / "scenes" / "gallery-inv-create-p0.tsv").write_text(
            "slot\titem\nhotbar.0\tcreate:cog\n", encoding="utf-8")
        with open(root / "boot.log", "a", encoding="utf-8") as f:
            f.write("gallery-inv done ns=create page=1 cells=0 exhausted=1\n")
        r = run_gate(root)
        ok = r.returncode == 0 and "DIAG-OK\tinv\ttotal=36" in r.stdout
        print("T1 inv walked diagnostic DIAG-OK GREEN:",
              "PASS" if ok else f"FAIL\n{r.stdout}{r.stderr}")
        failures += 0 if ok else 1
    with tempfile.TemporaryDirectory() as td:
        root = pathlib.Path(td)
        build_fixture(root)
        idx = root / "scenes" / "gallery-create-index.tsv"
        txt = idx.read_text(encoding="utf-8").replace(
            "inv\t0\t36\n", "inv\t36\t36\n")
        idx.write_text(txt, encoding="utf-8")
        (root / "scenes" / "gallery-inv-create-p0.tsv").write_text(
            "slot\titem\nhotbar.0\tcreate:cog\n", encoding="utf-8")
        (root / "ours-shots" / "ours-gal-inv-create0.png").unlink()
        r = run_gate(root)
        ok = r.returncode == 0 and "DIAG-INCOMPLETE\tinv" in r.stdout \
            and "missing_ours_shots=1" in r.stdout
        print("T2 inv missing ours shot visible-not-red:",
              "PASS" if ok else f"FAIL\n{r.stdout}{r.stderr}")
        failures += 0 if ok else 1

    # U: fluid no-truth-surface skip row (round 2 ladder exhausted) -> RED,
    # named — the honest-RED note vocabulary extension
    with tempfile.TemporaryDirectory() as td:
        root = pathlib.Path(td)
        build_fixture(root)
        p = root / "scenes" / "gallery-fluid-create-p0.tsv"
        p.write_text("cell\tx\ty\tz\tfluid\tdyn\tchannel\textra\n"
                     "skip\t-1\t-1\t-1\tcreate:ghost_liquid\t1\t"
                     "no-truth-surface:no-bucket-and-no-tank:no-fluid-texture\n",
                     encoding="utf-8")
        r = run_gate(root)
        ok = r.returncode == 1 and "create:ghost_liquid(no-truth-surface:" in r.stdout \
            and "fluid" in r.stdout
        print("U fluid no-truth-surface RED:",
              "PASS" if ok else f"FAIL\n{r.stdout}{r.stderr}")
        failures += 0 if ok else 1

    # V: the particle note carries the programmatic-ladder failure detail
    # (no-simple-options:<reason>) — the prefix match must keep NAMING it
    # (14-cs: the class is diagnostic, so the name surfaces in the DIAG
    # line and the run stays green)
    with tempfile.TemporaryDirectory() as td:
        root = pathlib.Path(td)
        build_fixture(root)
        p = root / "scenes" / "gallery-particle-create-p0.tsv"
        p.write_text("cell\tx\ty\tz\tparticle\tdyn\tnote\n"
                     "skip\t-1\t-1\t-1\tcreate:contraption_particle\t1\t"
                     "no-simple-options:com.mojang.serialization.DynamicOps$1\n",
                     encoding="utf-8")
        r = run_gate(root)
        ok = r.returncode == 0 and "COVERAGE: GREEN" in r.stdout \
            and "create:contraption_particle(no-simple-options:" in r.stdout \
            and "DIAG-INCOMPLETE\tparticle" in r.stdout
        print("V particle detailed note DIAG named GREEN:",
              "PASS" if ok else f"FAIL\n{r.stdout}{r.stderr}")
        failures += 0 if ok else 1

    # W: the 14-am ban list — a banned type is a NAMED absence
    with tempfile.TemporaryDirectory() as td:
        root = pathlib.Path(td)
        build_fixture(root)
        p = root / "scenes" / "gallery-entitydyn-create-p0.tsv"
        p.write_text("cell\tx\ty\tz\tentity\tdyn\tnote\n"
                     "skip\t-1\t-1\t-1\tcreate:contraption\t1\tclient-crash-banned\n",
                     encoding="utf-8")
        r = run_gate(root)
        ok = r.returncode == 1 and "create:contraption(client-crash-banned)" in r.stdout             and "entity-dyn" in r.stdout
        print("W entity client-crash-banned RED:",
              "PASS" if ok else f"FAIL\n{r.stdout}{r.stderr}")
        failures += 0 if ok else 1

    # X: the #114 post-fix mobs manifest shape (measured gallery #114, the
    # mod-ns mob pages): cell rows AND wrong-series:<kind> skip rows in the
    # SAME manifest — the walk now records series ownership as named rows
    # (create's nine dyn-classified entities in the mobs walk). Series
    # ownership is NOT absence: the gate stays GREEN and names nothing;
    # the owning series' own manifest carries the absences.
    with tempfile.TemporaryDirectory() as td:
        root = pathlib.Path(td)
        build_fixture(root)
        p = root / "scenes" / "gallery-entity-create-p0.tsv"
        p.write_text("cell\tx\ty\tz\tentity\tdyn\tnote\n"
                     "cell\t-5\t-60\t9\tminecraft:allay\t0\tok\n"
                     "skip\t-1\t-1\t-1\tcreate:carriage_contraption\t0\twrong-series:dyn\n"
                     "skip\t-1\t-1\t-1\tcreate:contraption\t0\twrong-series:dyn\n"
                     "skip\t-1\t-1\t-1\tcreate:super_glue\t0\twrong-series:dyn\n",
                     encoding="utf-8")
        r = run_gate(root)
        ok = r.returncode == 0 and "COVERAGE: GREEN" in r.stdout \
            and "unshown=" not in r.stdout
        print("X mixed cells + wrong-series rows GREEN:",
              "PASS" if ok else f"FAIL\n{r.stdout}{r.stderr}")
        failures += 0 if ok else 1

    # X2: the same shape must keep REDDING the owning series' true
    # absences — wrong-series rows on the mobs manifest plus the dyn
    # manifest's banned/failed rows: only the dyn series reds.
    with tempfile.TemporaryDirectory() as td:
        root = pathlib.Path(td)
        build_fixture(root)
        (root / "scenes" / "gallery-entity-create-p0.tsv").write_text(
            "cell\tx\ty\tz\tentity\tdyn\tnote\n"
            "skip\t-1\t-1\t-1\tcreate:contraption\t0\twrong-series:dyn\n",
            encoding="utf-8")
        p = root / "scenes" / "gallery-entitydyn-create-p0.tsv"
        p.write_text("cell\tx\ty\tz\tentity\tdyn\tnote\n"
                     "skip\t-1\t-1\t-1\tcreate:contraption\t0\tclient-crash-banned\n"
                     "skip\t-1\t-1\t-1\tcreate:package\t0\t"
                     "failed:io.netty.handler.codec.EncoderException\n",
                     encoding="utf-8")
        r = run_gate(root)
        ok = r.returncode == 1 and "entity-dyn" in r.stdout \
            and "create:contraption(client-crash-banned)" in r.stdout \
            and "create:package(failed:" in r.stdout \
            and "wrong-series" not in r.stdout
        print("X2 dyn absences RED, mobs wrong-series silent:",
              "PASS" if ok else f"FAIL\n{r.stdout}{r.stderr}")
        failures += 0 if ok else 1

    print("SMOKE:", "ALL PASS" if failures == 0 else f"{failures} FAILURES")
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
