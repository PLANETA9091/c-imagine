#!/usr/bin/env bash
# CIM LEG — FULL, one command (same contract as run-truth-full.sh):
#   assemble Paper 26.3 server + F2vPack -> boot -> RCON pins + the MIRROR
#   gallery (truth manifest = cell contract, block_display carriers) ->
#   vanilla 26.3 client joins WITH the pack -> gallery walk shots.
# Artifacts: $G/srv-cim/{boot.log,c-imagine/scenes/gallery.tsv}, $G/cim-shots/
set -uo pipefail
ROOT=/home/z/my-project/work/c-imagine
G=/tmp/my-project/artifacts/gal
SRV=$G/srv-cim
JDK25=/tmp/my-project/artifacts/jdk25/bin
JDK21=/tmp/my-project/artifacts/jdk21/bin
log(){ echo "[cim-full] $*"; }
export PATH=$JDK25:$JDK21:$PATH
export LD_LIBRARY_PATH=/tmp/my-project/artifacts/xlibs/x/usr/lib/x86_64-linux-gnu${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}
export CIM_VTOOLS=$G/vtools
export LP_NUM_THREADS=2

# --- 0. clean slate (SIGKILL: MC java shields SIGTERM) -----------------------
pkill -KILL -f "paperclip" 2>/dev/null
pkill -KILL -f unix_args 2>/dev/null
pkill -KILL -f "paper.jar" 2>/dev/null
pkill -KILL -f bootstraplauncher 2>/dev/null
pkill -KILL -f "java.*cim" 2>/dev/null
for i in $(seq 1 30); do
  BUSY=$(pgrep -c -f "paperclip|paper.jar|bootstraplauncher" 2>/dev/null || echo 0)
  PORT=$(ss -tln | grep -c ":25565 \|:25575 \|:25580 " 2>/dev/null || echo 0)
  [ "$BUSY" = "0" ] && [ "$PORT" = "0" ] && break
  sleep 2
done
sleep 5

# --- 1. assemble -------------------------------------------------------------
mkdir -p "$SRV/plugins" "$SRV/mods" "$SRV/c-imagine/f2v"
cp paper263/paperclip.jar "$SRV/" 2>/dev/null || cp /tmp/my-project/artifacts/paper263/paperclip.jar "$SRV/"
ln -sfn /tmp/my-project/artifacts/paper263/cache "$SRV/cache"
ln -sfn /tmp/my-project/artifacts/paper263/libraries "$SRV/libraries"
ln -sfn /tmp/my-project/artifacts/paper263/versions "$SRV/versions"
cp "$ROOT/paper-plugin/target/cim-paper-plugin.jar" "$SRV/plugins/"
(cd "$ROOT/test-mods/src/main/resources" && zip -qr /tmp/cimtest-assets.jar assets) 2>/dev/null
cp /tmp/cimtest-assets.jar "$SRV/mods/cimtest-1.0.0.jar"
cp "$ROOT/scripts/f2v/visual-items.tsv" "$ROOT/scripts/f2v/visual-equipment.tsv" \
   "$ROOT/scripts/f2v/visual-blocks.tsv" "$ROOT/scripts/f2v/visual-palettes.tsv" "$SRV/c-imagine/f2v/"
"$JDK25/java" -cp "$SRV/plugins/cim-paper-plugin.jar" dev.cim.f2v.F2vPack \
  --build "$SRV/mods" "$SRV/c-imagine/f2v" || { echo "F2vPack build FAILED"; exit 1; }
test -f "$SRV/c-imagine/f2v/pack.sha1" || { echo "pack.sha1 missing"; exit 1; }
log "pack built: $(cat "$SRV/c-imagine/f2v/pack.sha1" | head -c 12)..."

SHA1=$(cat "$SRV/c-imagine/f2v/pack.sha1")
echo "eula=true" > "$SRV/eula.txt"
cat > "$SRV/server.properties" <<PROPS
online-mode=false
white-list=false
difficulty=peaceful
level-type=minecraft\:flat
level-seed=42
view-distance=6
simulation-distance=4
spawn-protection=0
max-players=5
enable-rcon=true
rcon.port=25575
rcon.password=cimgate
resource-pack=http\://127.0.0.1\:25580/cim-f2v-pack.zip
resource-pack-sha1=$SHA1
PROPS

# --- 2. boot Paper -----------------------------------------------------------
cd "$SRV"
rm -rf world world_nether world_the_end
mkfifo console.in 2>/dev/null || true
setsid bash -c "cd '$SRV' && tail -f console.in | $JDK25/java -Xmx768M -jar paperclip.jar --nogui >> boot.log 2>&1" < /dev/null > /dev/null 2>&1 &
R=""
for i in $(seq 1 60); do
  R=$(python3 "$ROOT/scripts/cimscene/rcon.py" 127.0.0.1 25575 cimgate "list" 2>/dev/null | tail -1)
  [ -n "$R" ] && break; sleep 2
done
[ -n "$R" ] || { echo "PAPER/RCON FAILED"; tail -30 boot.log; exit 1; }
grep -q "f2v pack: serving" boot.log || { echo "PACK NOT SERVING"; tail -20 boot.log; exit 1; }
log "paper+RCON+pack up: $R"

# --- 3. pins + mirror gallery -------------------------------------------------
python3 "$ROOT/scripts/cimscene/rcon.py" 127.0.0.1 25575 cimgate \
  "gamerule doDaylightCycle false" "time set noon" \
  "gamerule doWeatherCycle false" "weather clear" \
  "gamerule doMobSpawning false" "forceload add -64 -32 64 48" > /dev/null 2>&1
mkdir -p "$SRV/c-imagine/scenes"
python3 "$ROOT/scripts/cimscene/cim-gallery-walk.py" \
  "$G/truth-srv/c-imagine/scenes/gallery.tsv" \
  "$ROOT/scripts/f2v/visual-blocks.tsv" \
  "$SRV/c-imagine/scenes/gallery.tsv" -60 > "$G/cim-summons.txt" 2> "$G/cim-mirror.log"
log "mirror: $(rg -c "MISSING-CARRIER" "$G/cim-mirror.log" 2>/dev/null || echo 0) missing carriers; $(wc -l < "$G/cim-summons.txt") commands"
grep -v "^#" "$G/cim-summons.txt" > "$G/cim-summons-cmds.txt"
python3 "$ROOT/scripts/cimscene/rcon.py" 127.0.0.1 25575 cimgate --f "$G/cim-summons-cmds.txt" \
  > "$G/cim-rcon.log" 2>&1 || { echo "SUMMONS FAILED"; tail -10 "$G/cim-rcon.log"; exit 1; }
log "summons placed"

# --- 4. client + walk ----------------------------------------------------------
bash "$ROOT/scripts/cimscene/run-client-walk.sh" cim \
  "$SRV/c-imagine/scenes/gallery.tsv" "$G/cim-shots" 127.0.0.1:25575:cimgate
RC=$?
log "client-walk rc=$RC"
exit $RC
