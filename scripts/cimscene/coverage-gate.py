#!/usr/bin/env python3
"""The COVERAGE gate — partial walks can never green the verdict again.

Owner directive 2026-10-31: the pixel verdict must SEE every registered
entry of the mod's content classes. The scene-mod writes a per-namespace
coverage ledger (gallery-<ns>-index.tsv: class|total|cellsPerPage, derived
from the same registries on both stacks). This gate joins the ledger
against what the run ACTUALLY produced:

  for every class with total > 0:
    pages_needed = ceil(total / cellsPerPage)
    - every page manifest present in the truth scenes dir?
    - every truth screenshot present?
    - every ours screenshot present?
    - the walk REACHED exhaustion: the terminal done marker
      (page = pages_needed, cells=0 ... exhausted=1) present in the truth
      boot log?
  blockentity (no pages of its own — its picture is drawn by the block
  wall): the reconciliation file gallery-<ns>-be-uncovered.tsv must EXIST
  and be EMPTY — a BlockEntityType whose every valid state is fluid-carried
  or RenderShape.INVISIBLE never reaches the wall and is named here.

HONEST-RED on the manifest's own words (owner directive "СЕРЬЕЗНО ВСЁ",
measured failure gallery #25 run 41188197904): a manifest row whose note
means ABSENCE turns the run RED with the named subjects. The pixel join
alone would compare empty-vs-empty and stay silent. Per class:
  entity-*   dissolved (slid/died/QUARANTINED before the final settle),
             no-instance (EntityType.create returned null), failed-* (the
             spawn or configure threw) — measured #25/#27/#30;
  item       summon-failed (the display summon failed) — 12-ap;
  particle   no-simple-options (the vanilla particle command rejected the
             bare spawn — measured #30: ALL 13 create particles);
  fluid      no-channel (no legacy block and no bucket — measured #30: 4 of
             8 create fluids).
(BLOCK manifests' skip rows are the different, deliberate accounting of
12-ao: fluid-carried states moved to their own class, RenderShape.INVISIBLE
states render identically on both legs by vanilla's own contract — reported
as skipped, never red.)

THE FINAL-REWRITE CONTRACT (12-as, measured #30): the entity-tick
quarantine can fire AFTER the settle pass — potato_projectile was alive at
pass 1 (row ok) and quarantined at ~+48 ticks, the shot then showed an
empty cell and the manifest claimed content nobody saw. The scene's second
settle pass (+40 ticks after pass 1, still inside the shot window) rewrites
the manifest with the final states; the gate reads the rewritten file.

Any shortfall is COVERAGE-INCOMPLETE — the run is RED no matter how green
the per-cell joins were (the measured motivating gap: create registers
31,389 blockstates; the gate walked 3 pages = 231 = 0.74% and could green
on that).

SHOT NAME LAW (the false-red fix, worklog 12-ao): the gate joins against
the shot names the walks ACTUALLY produce, which evolved per leg:
  block          gal-p{p}.png            (no ns infix — the original walk)
  entity-mobs    gal-e{p}.png            (minecraft control)
                 gal-emobs-<ns>{p}.png   (mod ns: the series word appears)
  entity-dyn     gal-edyn{p}.png / gal-edyn-<ns>{p}.png
  entity-large   gal-elarge{p}.png / gal-elarge-<ns>{p}.png
  item           gal-i<ns>{p}.png        (gal-i-create0.png)
  particle       gal-pa<ns>{p}.png       (gal-pa-create0.png)
  fluid          gal-fl<ns>{p}.png       (gal-fl-create0.png)
  ours shots     the same with the ours- prefix
where <ns> = "" for the minecraft control and "-create" for the mod
namespace. The first committed gate used bare templates for every class
and would have reported COVERAGE-INCOMPLETE on a perfect exhaustive run —
a gate must join against what landed, or it lies.

Usage:
  coverage-gate.py --ns create --index <gallery-<ns>-index.tsv> \
      --scenes <truth scenes dir> --truth-shots <dir> --ours-shots <dir> \
      [--boot <truth boot.log>]
"""
import argparse
import math
import pathlib
import sys

# class -> (manifest template, truth shot stem, ours shot stem,
#           terminal-marker template). {p} = page number; the shot names
#           get the ns/series infix per the SHOT NAME LAW. The ENTITY
#           series split: mobs/dyn/large each paginate the SAME entry
#           list, so each series must walk ceil(total/18) pages
#           (wrong-series entries are recorded skip rows, not coverage).
CLASSES = {
    "block": ("gallery-{ns}-p{p}.tsv", "gal-p", "ours-gal-p",
              "gallery done ns={ns} page={p} cells=0"),
    "entity-mobs": ("gallery-entity-{ns}-p{p}.tsv", "gal-e", "ours-gal-e",
                    "gallery-entity done ns={ns} page={p} cells=0"),
    "entity-dyn": ("gallery-entitydyn-{ns}-p{p}.tsv", "gal-e", "ours-gal-e",
                   "gallery-entitydyn done ns={ns} page={p} cells=0"),
    "entity-large": ("gallery-entitylarge-{ns}-p{p}.tsv", "gal-e", "ours-gal-e",
                     "gallery-entitylarge done ns={ns} page={p} cells=0"),
    "item": ("gallery-item-{ns}-p{p}.tsv", "gal-i", "ours-gal-i",
             "gallery-item done ns={ns} page={p} cells=0"),
    "particle": ("gallery-particle-{ns}-p{p}.tsv", "gal-pa", "ours-gal-pa",
                 "gallery-particle done ns={ns} page={p} cells=0"),
    "fluid": ("gallery-fluid-{ns}-p{p}.tsv", "gal-fl", "ours-gal-fl",
              "gallery-fluid done ns={ns} page={p} cells=0"),
}

# the series word baked between the stem and the ns infix (SHOT NAME LAW):
# minecraft's mob walk predates the series naming (gal-e0.png), every mod
# mob shot carries it (gal-emobs-create0.png); dyn/large carry theirs on
# both (gal-edyn0.png / gal-edyn-create0.png)
SERIES_WORD = {"entity-mobs": "mobs", "entity-dyn": "dyn",
               "entity-large": "large"}

# THE REST OF THE VISUAL SURFACE (12-aq): ledger classes the scene COUNTS
# (static BuiltIn + datapack registries whose entries alter a rendered
# frame) but NO gallery walk exists for yet. A positive total is
# COVERAGE-INCOMPLETE reason=no-walk-yet — the named backlog (the verdict
# must SEE its holes, never green over them); total=0 is OK; a negative
# total means the dynamic registry itself was unreadable at index time —
# unverifiable coverage is also red, honestly.
# The documented line: worldgen plumbing (features, carvers, loot,
# predicates, attributes, menus, recipes...) shapes the world or has no
# per-entry pixel surface — it stays OUTSIDE the world-frame ledger.
NO_WALK = {"biome", "painting_variant", "wolf_variant", "cat_variant",
           "frog_variant", "villager_type", "mob_effect", "trim_material",
           "trim_pattern", "banner_pattern", "decorated_pot_pattern",
           "enchantment", "jukebox_song", "dimension_type"}

# per class: manifest skip-row notes that mean the subject is ABSENT from
# the picture (see the HONEST-RED docstring above). A note matches when it
# equals an entry or starts with one (entity failed:<throwable>).
ABSENCE_NOTES = {
    "entity-mobs": ("dissolved", "no-instance", "failed"),
    "entity-dyn": ("dissolved", "no-instance", "failed"),
    "entity-large": ("dissolved", "no-instance", "failed"),
    "item": ("summon-failed",),
    "particle": ("no-simple-options",),
    "fluid": ("no-channel",),
}


def shot_infix(cls, ns):
    """The namespace infix of the shot file names: none for the block
    class (the original walk) and none for the minecraft control;
    -<ns> for mod namespaces on every newer class."""
    if cls == "block" or ns == "minecraft":
        return ""
    return "-" + ns


def shot_stem(base, sub, ns):
    word = SERIES_WORD.get(sub, "")
    if sub == "entity-mobs" and ns == "minecraft":
        word = ""
    return base + word


def read_index(path, ns):
    rows = {}
    lines = pathlib.Path(path).read_text(encoding="utf-8").splitlines()
    if not lines or lines[0].split("\t") != ["class", "total", "cellsPerPage"]:
        raise SystemExit(f"{path}: unexpected index header")
    for ln, line in enumerate(lines[1:], start=2):
        parts = line.split("\t")
        if len(parts) != 3:
            raise SystemExit(f"{path}:{ln}: bad row {line!r}")
        rows[parts[0]] = (int(parts[1]), int(parts[2]))
    return rows


def unshown_rows(scenes, mf_tpl, ns, needed, cls):
    """The named subjects absent from the picture: manifest skip rows whose
    note is one of the class's ABSENCE_NOTES. Rows are the honesty channel
    the pixel join cannot see (empty vs empty)."""
    prefixes = ABSENCE_NOTES.get(cls, ())
    if not prefixes:
        return []
    out = []
    for p in range(needed):
        mf = scenes / mf_tpl.format(ns=ns, p=p)
        if not mf.is_file():
            continue
        for line in mf.read_text(encoding="utf-8").splitlines()[1:]:
            parts = line.split("\t")
            if len(parts) < 7 or parts[0] != "skip":
                continue
            note = parts[6].strip()
            if any(note == pre or note.startswith(pre) for pre in prefixes):
                out.append(f"{parts[4]}({note})")
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--ns", required=True)
    ap.add_argument("--index", required=True)
    ap.add_argument("--scenes", required=True)
    ap.add_argument("--truth-shots", required=True)
    ap.add_argument("--ours-shots", required=True)
    ap.add_argument("--boot")
    args = ap.parse_args()

    index = read_index(args.index, args.ns)
    scenes = pathlib.Path(args.scenes)
    tshots = pathlib.Path(args.truth_shots)
    oshots = pathlib.Path(args.ours_shots)
    boot = pathlib.Path(args.boot).read_text(encoding="utf-8",
                                             errors="replace") if args.boot else None

    incomplete = []
    print(f"coverage-gate: ns={args.ns} index={args.index}")
    for cls, total_cells in index.items():
        total, cells_per_page = total_cells
        if cls == "blockentity":
            # no pages of its own: the BE picture is drawn by the block
            # wall — the reconciliation file is the contract
            f = scenes / f"gallery-{args.ns}-be-uncovered.tsv"
            if total == 0:
                print("OK\tblockentity\ttotal=0 (no BE types in this namespace)")
                continue
            if not f.is_file():
                print(f"COVERAGE-INCOMPLETE\tblockentity\ttotal={total} "
                      f"reason=reconciliation-file-missing ({f})")
                incomplete.append("blockentity")
                continue
            names = [l.strip() for l in
                     f.read_text(encoding="utf-8").splitlines() if l.strip()]
            if names:
                print(f"COVERAGE-INCOMPLETE\tblockentity\ttotal={total} "
                      f"uncovered={','.join(names)} (BE types whose every "
                      f"valid state never reaches the wall are RED, not green)")
                incomplete.append("blockentity")
                continue
            print(f"OK\tblockentity\ttotal={total} (every BE type has a "
                  f"placeable valid state on the block wall)")
            continue
        if cls in NO_WALK:
            if total < 0:
                print(f"COVERAGE-INCOMPLETE\t{cls}\treason=unreadable-registry "
                      "(the ledger could not read the dynamic registry — "
                      "unverifiable coverage is RED, not green)")
                incomplete.append(cls)
                continue
            if total == 0:
                print(f"OK\t{cls}\ttotal=0 (nothing registered in this namespace)")
                continue
            print(f"COVERAGE-INCOMPLETE\t{cls}\ttotal={total} "
                  "reason=no-walk-yet (the ledger SEES the class; no gallery "
                  "walk exists yet — the named backlog, not a silent hole)")
            incomplete.append(cls)
            continue
        # the ledger's single `entity` row fans out to the three series —
        # each series paginates the SAME entry list, so each must walk
        # ceil(total / cellsPerPage) pages (wrong-series entries are
        # recorded skip rows, never coverage)
        series = (["entity-mobs", "entity-dyn", "entity-large"]
                  if cls == "entity" else [cls])
        for sub in series:
            if sub not in CLASSES:
                raise SystemExit(f"{args.index}: unknown class {sub!r}")
            mf_tpl, tbase, obase, done_tpl = CLASSES[sub]
            infix = shot_infix(sub, args.ns)
            tstem = shot_stem(tbase, sub, args.ns)
            ostem = shot_stem(obase, sub, args.ns)
            needed = math.ceil(total / cells_per_page) if total > 0 else 0
            missing_mf = missing_ts = missing_os = 0
            no_exhaust = False
            for p in range(needed):
                if not (scenes / mf_tpl.format(ns=args.ns, p=p)).is_file():
                    missing_mf += 1
                if not (tshots / f"{tstem}{infix}{p}.png").is_file():
                    missing_ts += 1
                if not (oshots / f"{ostem}{infix}{p}.png").is_file():
                    missing_os += 1
            if boot is not None and needed > 0:
                marker = done_tpl.format(ns=args.ns, p=needed)
                if marker not in boot:
                    no_exhaust = True
            ok = needed == 0 or (missing_mf == 0 and missing_ts == 0
                                 and missing_os == 0 and not no_exhaust)
            unshown = unshown_rows(scenes, mf_tpl, args.ns, needed, sub)
            if unshown:
                ok = False
            note = "OK" if ok else "COVERAGE-INCOMPLETE"
            line = (f"{note}\t{sub}\ttotal={total}\tpages_needed={needed} "
                    f"missing_manifests={missing_mf} "
                    f"missing_truth_shots={missing_ts} "
                    f"missing_ours_shots={missing_os}"
                    + (" terminal-marker-missing" if no_exhaust else ""))
            if unshown:
                names = ", ".join(unshown[:8])
                more = len(unshown) - min(len(unshown), 8)
                line += (f" unshown={names}"
                         + (f" +{more} more" if more > 0 else "")
                         + " (subjects absent from the picture are RED)")
            print(line)
            if not ok:
                incomplete.append(sub)

    if incomplete:
        print(f"COVERAGE: RED — incomplete walks: {', '.join(incomplete)} "
              f"(content that never reached a page is RED, not green)")
        return 1
    print("COVERAGE: GREEN — every class walked to exhaustion, "
          "every page manifested and shot on both legs")
    return 0


if __name__ == "__main__":
    sys.exit(main())
