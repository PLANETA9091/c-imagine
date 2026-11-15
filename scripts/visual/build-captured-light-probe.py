#!/usr/bin/env python3
"""Build before/after diagnostics from real e26 Create/AE2 capture artifacts.

Baseline loads both original GalleryPack and UnlitBlockModel; candidate uses
production material-alpha normalization. Texture alpha/atlas allocations may
change, while both builds read identical immutable native mesh/sprite inputs.
This is a small renderer experiment, never a coverage or mod-parity gate.
The comprehensive dual-stack gallery remains the acceptance test.
"""
import argparse
import copy
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import zipfile

BASELINE = '80176324765a90476e037c0f5b269a2a4b4e066e'
SUBJECTS = [
    'create:item_vault[axis=z,large=true]',
    'create:framed_glass_door[facing=north,half=lower,hinge=left,open=false,powered=false,visible=true]',
    'ae2:quartz_pillar_wall[east=low,north=low,south=tall,up=false,waterlogged=false,west=none]',
]


def rename(value):
    if isinstance(value, str):
        return value.replace('c-imagine:', 'c-imagine-candidate:')
    if isinstance(value, dict):
        return {k: rename(v) for k, v in value.items()}
    if isinstance(value, list):
        return [rename(v) for v in value]
    return value


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument('capture', type=Path)
    ap.add_argument('client', type=Path)
    ap.add_argument('output', type=Path)
    args = ap.parse_args()
    repo = Path(__file__).resolve().parents[2]
    capture = next(args.capture.rglob('sprite-aliases.tsv')).parent
    rows = (capture / 'rows.tsv').read_text().splitlines()
    selected = [next(r for r in rows if r.startswith(st + '#q')) for st in SUBJECTS]
    aliases = dict(line.split('\t', 1) for line in
                   (capture / 'sprite-aliases.tsv').read_text().splitlines()
                   if line and not line.startswith('#'))
    root = args.output.resolve()
    root.mkdir(parents=True, exist_ok=True)
    plugin = repo / 'paper-plugin/target/cim-paper-plugin.jar'
    deps = (repo / 'paper-plugin/target/probe-cp.txt').read_text().strip()
    classpath = str(plugin) + ':' + deps
    baseline_sources = []
    for name in ('UnlitBlockModel', 'GalleryPack'):
        source = root / ('baseline-src/dev/cim/f2v/' + name + '.java')
        source.parent.mkdir(parents=True, exist_ok=True)
        source.write_bytes(subprocess.check_output([
            'git', 'show', BASELINE + ':paper-plugin/src/main/java/dev/cim/f2v/' + name + '.java'], cwd=repo))
        baseline_sources.append(str(source))
    baseline_classes = root / 'baseline-classes'
    subprocess.run(['javac', '--release', '21', '-cp', classpath, '-d',
                    str(baseline_classes)] + baseline_sources, check=True)
    empty_mods = root / 'empty-mods'
    empty_mods.mkdir(exist_ok=True)
    for variant in ('before', 'candidate'):
        target = root / variant
        quads = target / 'quads'
        (quads / 'textures').mkdir(parents=True, exist_ok=True)
        needed = set()
        for row in selected:
            mesh = row.rsplit('#q', 1)[1]
            source = capture / (mesh + '.tsv')
            shutil.copyfile(source, quads / source.name)
            for line in source.read_text().splitlines():
                needed.add(line.split('\t')[0])
        for sprite in needed:
            alias = aliases[sprite]
            if not alias.startswith('~'):
                raise ValueError('Expected native decoded sprite: ' + alias)
            name = alias[1:] + '.png'
            shutil.copyfile(capture / 'textures' / name, quads / 'textures' / name)
            meta = capture / 'textures' / (name + '.mcmeta')
            if meta.exists():
                shutil.copyfile(meta, quads / 'textures' / meta.name)
        (quads / 'sprite-aliases.tsv').write_text(''.join(
            sprite + '\t' + aliases[sprite] + '\n' for sprite in sorted(needed)))
        (target / 'visual-gallery.tsv').write_text('\n'.join(selected) + '\n')
        cp = str(baseline_classes) + ':' + classpath if variant == 'before' else classpath
        subprocess.run(['java', '-Djava.awt.headless=true', '-cp', cp,
                        'dev.cim.f2v.F2vPack', '--build', str(empty_mods),
                        str(target), '--assets', str(args.client.resolve())], check=True)

    def resolved(variant):
        return {r.split('\t')[0].split('#q')[0]: float(r.split('\t')[1])
                for r in (root / variant / 'visual-gallery.resolved.tsv').read_text().splitlines()
                if r and not r.startswith('#')}

    before = resolved('before')
    candidate = resolved('candidate')
    if before != candidate or set(before) != set(SUBJECTS):
        raise ValueError('Probe must resolve exactly the same three native subjects')
    with zipfile.ZipFile(root / 'before/pack.zip') as z:
        entries = {n: z.read(n) for n in z.namelist()}
    paper_path = 'assets/minecraft/items/paper.json'
    paper = json.loads(entries[paper_path])
    dispatch = paper['model']
    if dispatch['type'] != 'minecraft:range_dispatch':
        raise ValueError('Unexpected paper dispatch')
    with zipfile.ZipFile(root / 'candidate/pack.zip') as z:
        # Material alpha is the variable under test; output PNG equality
        # would forbid the repair. Both variants copy the same native inputs.
        after = json.loads(z.read(paper_path))['model']['entries']
        for row in after:
            if row['threshold'] in set(candidate.values()):
                shifted = rename(copy.deepcopy(row))
                shifted['threshold'] += 100000
                dispatch['entries'].append(shifted)
        for name in z.namelist():
            if name.startswith('assets/c-imagine/'):
                value = z.read(name)
                if name.endswith('.json'):
                    value = json.dumps(rename(json.loads(value))).encode()
                entries[name.replace('assets/c-imagine/', 'assets/c-imagine-candidate/', 1)] = value
    dispatch['entries'].sort(key=lambda row: row['threshold'])
    entries[paper_path] = json.dumps(paper).encode()
    final = root / 'served'
    final.mkdir(exist_ok=True)
    with zipfile.ZipFile(final / 'pack.zip', 'w', zipfile.ZIP_DEFLATED) as z:
        for name, value in sorted(entries.items()):
            z.writestr(zipfile.ZipInfo(name, (1980, 1, 1, 0, 0, 0)), value,
                       compress_type=zipfile.ZIP_DEFLATED)
    (final / 'pack.sha1').write_text(hashlib.sha1((final / 'pack.zip').read_bytes()).hexdigest() + '\n')
    shutil.copyfile(root / 'before/visual-gallery.resolved.tsv', final / 'visual-gallery.resolved.tsv')
    provenance = {'diagnostic_only': True, 'capture_run': 43645299155,
                  'capture_artifact': 14133294738,
                  'capture_source': '7264eb58fb1b710de41d3e3389d5144bb2d7f0e4',
                  'baseline': BASELINE, 'experiment': 'production-material-alpha',
                  'candidate': subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=repo, text=True).strip(),
                  'subjects': before}
    (root / 'provenance.json').write_text(json.dumps(provenance, indent=2) + '\n')
    commands = {phase: [] for phase in ('before', 'after')}
    for i, state in enumerate(SUBJECTS):
        x = [-1.5, 2.5, 6.5][i]
        for phase, offset in (('before', 0), ('after', 100000)):
            item = '{id:"minecraft:paper",count:1,components:{"minecraft:custom_model_data":{floats:[' + str(before[state] + offset) + 'f]}}}'
            if phase == 'before':
                commands[phase].append(f'summon minecraft:item_display {x} -59.5 3.5 {{Tags:["probe_{i}"],brightness:{{block:0,sky:15}},item:{item}}}')
            else:
                commands[phase].append(f'data merge entity @e[tag=probe_{i},limit=1] {{item:{item}}}')
            commands[phase].append(f'data get entity @e[tag=probe_{i},limit=1]')
            match = '{item:{components:{"minecraft:custom_model_data":{floats:[' + str(before[state] + offset) + 'f]}}}}'
            commands[phase].append(f'execute if data entity @e[tag=probe_{i},limit=1] {match} run say CIM_CAPTURE_{phase}_{i}_OK')
    for phase, lines in commands.items():
        (root / ('scene-' + phase + '.txt')).write_text('\n'.join(lines) + '\n')


if __name__ == '__main__':
    main()
