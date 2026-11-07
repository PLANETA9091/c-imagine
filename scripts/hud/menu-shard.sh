#!/usr/bin/env bash
# One slice of the menu gate: real NeoForge frames, then ours, then the pixel verdict.
# Usage: menu-shard.sh <namespace> <index> <count>. Env: J21, J25 (JDK homes), SLUGS unused.
set -uo pipefail
# Ports and display are per slice, so several slices can run side by side on one host.
GAME_PORT=${GAME_PORT:-25565}; RCON_PORT=${RCON_PORT:-25575}; PACK_PORT=${PACK_PORT:-25580}; DISP=${DISP:-99}
NS=$1; SI=$2; K=$3
ROOT=$PWD
SCR=srv-wall/c-imagine/screens
RC="python3 scripts/cimscene/rcon.py 127.0.0.1 $RCON_PORT cimgate"
export DISPLAY=:$DISP LIBGL_ALWAYS_SOFTWARE=1 GALLIUM_DRIVER=llvmpipe LP_NUM_THREADS=4
stamp() { echo "[$(date +%T)] $*"; }
wait_rcon() { for i in $(seq 1 240); do $RC list 2>/dev/null | grep -q 'players' && return 0; sleep 1; done; return 1; }
wait_viewer() { for i in $(seq 1 180); do $RC list 2>/dev/null | grep -q cimviewer && return 0; sleep 1; done; return 1; }

mkdir -p truth-shots ours-shots vtools gallery-report
"$J21/bin/javac" -d vtools scripts/visual/Shot.java scripts/visual/Key.java
Xvfb :$DISP -screen 0 1280x720x24 +extension GLX +render -noreset &
SHOT="$J21/bin/java -cp vtools Shot"; KEY="$J21/bin/java -cp vtools Key"

# --- the plugin builds while the real server and client boot
( JAVA_HOME=$J25 [ -f paper-plugin/target/cim-paper-plugin.jar ] && [ -f paper-plugin/target/selftest-cp.txt ] && exit 0
mvn -B -ntp -q -DskipTests -Dpaper.version="$(bash scripts/resolve-paper-version.sh 26.3)" package -f paper-plugin/pom.xml \
    && cd paper-plugin && JAVA_HOME=$J25 mvn -B -ntp -q dependency:build-classpath -Dmdep.outputFile=target/selftest-cp.txt ) \
    > plugin-build.log 2>&1 &
BUILD=$!

# --- truth: real NeoForge, real client
stamp truth boot
cp -r layer-srv srv-wall; mkdir -p srv-wall/mods srv-wall/c-imagine/screens
cp scene-mod/build/libs/cimscene-1.0.0.jar mods-ladder/*.jar srv-wall/mods/
echo eula=true > srv-wall/eula.txt
printf "online-mode=false\nwhite-list=false\ndifficulty=peaceful\nlevel-type=minecraft:flat\nlevel-seed=42\nview-distance=4\nsimulation-distance=4\nspawn-protection=0\nmax-players=5\nserver-port=$GAME_PORT\nenable-rcon=true\nrcon.port=$RCON_PORT\nrcon.password=cimgate\n" > srv-wall/server.properties
( cd srv-wall && setsid bash -c "$J21/bin/java -Xmx2G @libraries/net/neoforged/neoforge/21.1.265/unix_args.txt --nogui >> boot.log 2>&1" < /dev/null > /dev/null 2>&1 & )
mkdir -p truth-client/game/mods truth-client/game/config
find truth-client/game/mods -maxdepth 1 -type f -name '*.jar' -delete
cp srv-wall/mods/*.jar truth-client/game/mods/
OPTS='tutorialStep:none\nonboardAccessibility:false\nfov:0.0\nrenderClouds:false\nchatVisibility:2\njoinedFirstServer:true\nnotificationDisplayTime:0.5\n'
printf "$OPTS" > truth-client/game/options.txt
python3 scripts/visual/make_servers_dat.py truth-client/game 127.0.0.1:$GAME_PORT
wait_rcon || { tail -40 srv-wall/boot.log; exit 2; }
$RC 'gamerule doDaylightCycle false' 'time set noon' 'gamerule doWeatherCycle false' 'weather clear' \
    'gamerule doMobSpawning false' 'gamerule randomTickSpeed 0' >/dev/null
LAUNCH=$(sed -e "s#127.0.0.1:25565#127.0.0.1:$GAME_PORT#g" -e "s#^java #$J21/bin/java -Dcim.screenart=$ROOT/srv-wall/c-imagine/screens #" truth-client/launch.txt)
client() { setsid bash -c "cd truth-client && $LAUNCH >> client.out 2>&1" < /dev/null > /dev/null 2>&1 & }
client
wait_viewer || { tail -30 truth-client/client.out; exit 2; }
for i in $(seq 1 40); do grep -qE 'Loaded [0-9]+ advancements' truth-client/client.out && break; sleep 1; done
sleep 4
stamp truth joined
# The plan job already scanned every mod; a slice only scans when run on its own.
mkdir -p srv-wall/c-imagine/scenes
if ls plan-scenes/gallery-gui-"$NS"-p*.tsv >/dev/null 2>&1; then
  cp plan-scenes/gallery-gui-"$NS"-p*.tsv srv-wall/c-imagine/scenes/
else
  $RC "cimscene gallery gui-scan $NS" >/dev/null
  sleep 1
fi
N=$(find srv-wall/c-imagine/scenes -maxdepth 1 -name "gallery-gui-$NS-p*.tsv" | wc -l)
stamp "truth walk: $N menus, slice $SI/$K"
# Recipe toasts come from picking items up, so unlock them all now and let them pass.
$RC 'tp cimviewer 0.5 -60 0.5 0 0' 'recipe give cimviewer *' >/dev/null
sleep 8
for p in $(seq "$SI" "$K" $((N-1))); do
  # A disconnected client sits on its error screen, so it is replaced, not waited for.
  $RC list | grep -q cimviewer || { stamp "viewer lost before page $p, relaunching"; pkill -f 'cim.screenart' || true; sleep 1; client; wait_viewer; sleep 8; $RC 'tp cimviewer 0.5 -60 0.5 0 0' >/dev/null; }
  $RC 'item replace entity cimviewer hotbar.8 with minecraft:diamond 5' >/dev/null
  M="srv-wall/c-imagine/scenes/gallery-gui-$NS-p$p.done"
  B=$(cat "$M" 2>/dev/null || echo none)
  $RC "cimscene gallery gui $NS $p" >/dev/null
  for i in $(seq 1 60); do [ "$(cat "$M" 2>/dev/null || echo none)" != "$B" ] && break; sleep 0.2; done
  sleep 1
  $SHOT "truth-shots/gal-gui-$NS$p.png" || true
  # Menus behind this menu's buttons: the client presses through them, and the parent
  # is reopened whenever one of them replaces it.
  if grep -q '^cell' "srv-wall/c-imagine/scenes/gallery-gui-$NS-p$p.tsv" 2>/dev/null; then
    IDX=0
    for round in 1 2 3 4; do
      rm -f "$SCR/crawl-done"; echo "$IDX" > "$SCR/crawl-go"
      for i in $(seq 1 90); do [ -f "$SCR/crawl-done" ] && break; sleep 0.2; done
      [ -f "$SCR/crawl-done" ] || { rm -f "$SCR/crawl-go"; break; }
      IDX=$(cat "$SCR/crawl-done"); [ "$IDX" = "-1" ] && break
      $RC "cimscene gallery gui $NS $p" >/dev/null; sleep 1
    done
  fi
done
stamp "truth done: $(find srv-wall/c-imagine/screens -maxdepth 1 -name '*.png' | wc -l) screens captured"
# A menu the crawl reached has no opener, so its capture becomes its page.
python3 - "$NS" <<'PY' > crawl-pages.txt
import glob, os, shutil, sys
ns = sys.argv[1]
pages = {}
for f in glob.glob(f'srv-wall/c-imagine/scenes/gallery-gui-{ns}-p*.tsv'):
    rows = [l.rstrip('\n').split('\t') for l in open(f, encoding='utf-8', errors='replace')]
    for r in rows:
        if r[0] == 'skip' and len(r) > 4:
            pages[r[4]] = f
for tsv in glob.glob(f'srv-wall/c-imagine/screens/{ns}_*.tsv'):
    rows = [l.rstrip('\n').split('\t') for l in open(tsv, encoding='utf-8')]
    menu = next((r for r in rows if r[0] == 'menu'), None)
    frame = tsv[:-4] + '.frame'
    if not menu or menu[1] not in pages or not os.path.isfile(frame):
        continue
    page_file = pages[menu[1]]
    page = page_file.rsplit('-p', 1)[1][:-4]
    with open(page_file, 'w', encoding='utf-8') as out:
        out.write('cell\tx\ty\tz\tmenu\tnote\n')
        out.write(f'cell\t0\t0\t0\t{menu[1]}\topened-by:crawl\t{menu[2]}\n')
        for i, r in enumerate(x for x in rows if x[0] == 'slot'):
            out.write(f'slot\t{i}\t{r[1]}\t{r[2]}\t{menu[1]}\t{r[3]}\t{r[4]}\n')
    shutil.copy(frame, f'truth-shots/gal-gui-{ns}{page}.png')
    print(page)
# Interactions: each recorded state's frame becomes a page of its own.
for f in glob.glob(f'srv-wall/c-imagine/scenes/gallery-gui-{ns}-p*.tsv'):
    cell = next((l.rstrip('\n').split('\t') for l in open(f, encoding='utf-8', errors='replace')
                 if l.startswith('cell\t') and not l.startswith('cell\tx')), None)
    if not cell:
        continue
    base = 'srv-wall/c-imagine/screens/' + cell[4].replace(':', '_').replace('/', '_')
    page = f.rsplit('-p', 1)[1][:-4]
    if os.path.isfile(base + '.presses'):
        for line in open(base + '.presses', encoding='utf-8'):
            q = line.split('\t')
            tag = 'move' if q[0] == 'move' else 'w' + q[1]
            if os.path.isfile(f'{base}.{tag}.frame'):
                shutil.copy(f'{base}.{tag}.frame', f'truth-shots/gal-guiw-{ns}{page}-{tag}.png')
# The art and this frame are one render, so an animation is the same moment in both.
for f in glob.glob(f'srv-wall/c-imagine/scenes/gallery-gui-{ns}-p*.tsv'):
    cell = next((l.rstrip('\n').split('\t') for l in open(f, encoding='utf-8', errors='replace')
                 if l.startswith('cell\t') and not l.startswith('cell\tx')), None)
    if cell:
        frame = 'srv-wall/c-imagine/screens/' + cell[4].replace(':', '_').replace('/', '_') + '.frame'
        shot = 'truth-shots/gal-gui-%s%s.png' % (ns, f.rsplit('-p', 1)[1][:-4])
        if os.path.isfile(frame) and os.path.isfile(shot):
            shutil.copy(frame, shot)
PY
stamp "crawl reached $(wc -l < crawl-pages.txt) more menus"
$RC stop >/dev/null 2>&1 || true
sleep 2
pkill -f 'cim.screenart' || true

# --- a small pack from what was just captured
wait $BUILD || { tail -30 plugin-build.log; exit 2; }
CP="paper-plugin/target/cim-paper-plugin.jar:$(cat paper-plugin/target/selftest-cp.txt)"
mkdir -p srv-wall/c-imagine/f2v
python3 - "$NS" <<'PY'
import glob, sys
ns = sys.argv[1]
ids = set()
for f in glob.glob(f'srv-wall/c-imagine/scenes/gallery-gui-{ns}-p*.tsv'):
    for line in open(f, encoding='utf-8', errors='replace'):
        p = line.rstrip('\n').split('\t')
        if p[0] == 'cell' and len(p) > 5:
            note = p[5].replace('opened-by:', '').replace('sneak+', '')
            if note.startswith('item:'):
                ids.add(note[5:])
with open('srv-wall/c-imagine/f2v/visual-items.resolved.tsv', 'w', encoding='utf-8') as out:
    for i, item in enumerate(sorted(ids)):
        out.write(f'{item}\tminecraft:paper\t{900000 + i}.0\n')
PY
"$J25/bin/java" -Xmx3g -Djava.awt.headless=true -cp "$CP" dev.cim.hud.HudPack --emit pack.zip srv-wall/mods | tail -1

# --- ours: Paper and the vanilla 26.3 client
stamp ours boot
mkdir -p srv-ours/plugins srv-ours/c-imagine/f2v srv-ours/c-imagine/scenes
cp paper-plugin/target/cim-paper-plugin.jar srv-ours/plugins/
cp pack.zip srv-ours/c-imagine/f2v/pack.zip
SHA1=$(sha1sum pack.zip | cut -d' ' -f1); echo "$SHA1" > srv-ours/c-imagine/f2v/pack.sha1
cp srv-wall/c-imagine/f2v/*.tsv scripts/f2v/visual-blocks.tsv srv-ours/c-imagine/f2v/
cp srv-wall/c-imagine/scenes/gallery-gui-*.tsv srv-ours/c-imagine/scenes/
echo eula=true > srv-ours/eula.txt
printf "online-mode=false\nwhite-list=false\ndifficulty=peaceful\nlevel-type=minecraft\\\\:flat\nlevel-seed=42\nview-distance=4\nsimulation-distance=4\nspawn-protection=0\nmax-players=5\nserver-port=$GAME_PORT\nenable-rcon=true\nrcon.port=$RCON_PORT\nrcon.password=cimgate\nresource-pack=http\\\\://127.0.0.1\\\\:$PACK_PORT/cim-f2v-pack.zip\nresource-pack-sha1=%s\n" "$SHA1" > srv-ours/server.properties
[ -f srv-ours/paper.jar ] || { URL=$(curl -s https://fill.papermc.io/v3/projects/paper/versions/26.3/builds/latest | python3 -c "import json,sys; print(json.load(sys.stdin)['downloads']['server:default']['url'])"); curl -sL --max-time 200 -o srv-ours/paper.jar "$URL"; }
( cd srv-ours && setsid bash -c "$J25/bin/java -Xmx3G -Dcim.f2v.port=$PACK_PORT -Dcim.f2v.fastBoot=true -jar paper.jar --nogui >> boot.log 2>&1" < /dev/null > /dev/null 2>&1 & )
[ -f visual-client/launch.txt ] || python3 scripts/fetch-visual-client.py 26.3 visual-client
sed -i -e "s#127.0.0.1:25565#127.0.0.1:$GAME_PORT#g" -e 's/^java /java -Xmx1G /' visual-client/launch.txt
mkdir -p visual-client/game
printf "${OPTS}preferredGraphicsBackend:opengl\n" > visual-client/game/options.txt
python3 scripts/visual/make_servers_dat.py visual-client/game 127.0.0.1:$GAME_PORT
wait_rcon || { tail -40 srv-ours/boot.log; exit 2; }
$RC 'gamerule advance_time false' 'time set noon' 'gamerule advance_weather false' 'weather clear' 'gamerule spawn_mobs false' >/dev/null
ARGS=$(sed 's/^java //' visual-client/launch.txt)
setsid bash -c "cd visual-client && $J25/bin/java $ARGS > client.out 2>&1" < /dev/null > /dev/null 2>&1 &
wait_viewer || { tail -30 visual-client/client.out; exit 2; }
for i in $(seq 1 40); do [ "$(grep -c 'Reloading ResourceManager' visual-client/client.out)" -ge 2 ] && break; sleep 1; done
sleep 10   # the join toasts pass
stamp ours joined
$RC 'tp cimviewer 0.5 -60 0.5 0 0' >/dev/null
for p in $( { seq "$SI" "$K" $((N-1)); cat crawl-pages.txt; } | sort -nu); do
  $RC 'item replace entity cimviewer hotbar.8 with minecraft:diamond 5' >/dev/null
  $RC "cim gallerypage-gui $NS $p" >/dev/null
  sleep 0.6
  $SHOT "ours-shots/ours-gal-gui-$NS$p.png" || true
  # The same interactions, in the order the real client made them.
  TAGS=$( { [ -f "truth-shots/gal-guiw-$NS$p-move.png" ] && echo move; ls truth-shots | grep -oE "^gal-guiw-$NS$p-w[0-9]+\.png$" | grep -oE 'w[0-9]+\.png$' | tr -d '.png' | sort -t w -k2 -n; } )
  for T in $TAGS; do
    if [ "$T" = move ]; then $RC "cim hudpress move" >/dev/null; else $RC "cim hudpress ${T#w}" >/dev/null; fi
    sleep 0.5
    $SHOT "ours-shots/ours-gal-guiw-$NS$p-$T.png" || true
  done
done
stamp ours done

# --- the verdict
python3 -m pip install -q --break-system-packages pillow 2>/dev/null || python3 -m pip install -q pillow
python3 scripts/cimscene/menu-sheet.py --meta srv-wall/c-imagine/scenes --truth truth-shots --ours ours-shots \
    --art srv-wall/c-imagine/screens --stride "$SI/$K" --out gallery-report/menus --report gallery-report --ns "$NS"
RCODE=$?
stamp "verdict rc=$RCODE"
exit 0
