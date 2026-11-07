#!/usr/bin/env bash
# Plans the menu gate from the mods themselves: boots real NeoForge with every locked
# mod, takes each mod id from its jar, scans that mod's menus, and sizes the slices
# from the real counts. Nothing names a mod. Writes the matrix to $GITHUB_OUTPUT and
# leaves the scan in plan-scenes/ for the slices.
# Env: J21 (JDK home). Per slice: about this many menus.
set -uo pipefail
PER_SLICE=${PER_SLICE:-22}
RC="python3 scripts/cimscene/rcon.py 127.0.0.1 25575 cimgate"

cp -r layer-srv srv-plan; mkdir -p srv-plan/mods
cp scene-mod/build/libs/cimscene-1.0.0.jar mods-ladder/*.jar srv-plan/mods/
echo eula=true > srv-plan/eula.txt
printf 'online-mode=false\nlevel-type=minecraft:flat\nenable-rcon=true\nrcon.port=25575\nrcon.password=cimgate\nview-distance=4\nsimulation-distance=4\n' > srv-plan/server.properties
( cd srv-plan && setsid bash -c "$J21/bin/java -Xmx3G @libraries/net/neoforged/neoforge/21.1.265/unix_args.txt --nogui >> boot.log 2>&1" < /dev/null > /dev/null 2>&1 & )

# Every mod id the jars declare, nested jars included.
python3 - > mod-ids.txt <<'PY'
import glob, io, re, zipfile
ids = set()
def scan(z):
    for name in z.namelist():
        if name == 'META-INF/neoforge.mods.toml':
            ids.update(re.findall(r'^\s*modId\s*=\s*"([^"]+)"', z.read(name).decode('utf-8', 'replace'), re.M))
        elif name.startswith('META-INF/jarjar/') and name.endswith('.jar'):
            scan(zipfile.ZipFile(io.BytesIO(z.read(name))))
for jar in glob.glob('mods-ladder/*.jar'):
    scan(zipfile.ZipFile(jar))
print('\n'.join(sorted(ids)))
PY
echo "mods: $(tr '\n' ' ' < mod-ids.txt)"

for i in $(seq 1 240); do $RC list 2>/dev/null | grep -q players && break; sleep 1; done
for ns in $(cat mod-ids.txt); do
  $RC "cimscene gallery gui-scan $ns" >/dev/null 2>&1 || true
done
sleep 2
$RC stop >/dev/null 2>&1 || true
mkdir -p plan-scenes; cp srv-plan/c-imagine/scenes/gallery-gui-*.tsv plan-scenes/ 2>/dev/null || true

python3 - "$PER_SLICE" <<'PY' > matrix.json
import collections, glob, json, math, sys
per = int(sys.argv[1])
count = collections.Counter(f.split('gallery-gui-')[1].rsplit('-p', 1)[0] for f in glob.glob('plan-scenes/gallery-gui-*.tsv'))
include = []
for ns, n in sorted(count.items()):
    k = max(1, math.ceil(n / per))
    include += [{"ns": ns, "si": i, "k": k} for i in range(k)]
print(json.dumps({"include": include}))
PY
cat matrix.json
echo "matrix=$(cat matrix.json)" >> "$GITHUB_OUTPUT"
