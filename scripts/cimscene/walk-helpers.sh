#!/usr/bin/env bash
# 14-di: the truth walk's shared helper functions, sourced by every walk
# step (gui-first, blocks, entity..inv). The RCON coproc + the rcon()
# wrapper stay per-step (the coproc must live at step level); these
# functions only CALL rcon / read the marker files at call time.
# Keep in sync with the seed job's inline copies (they predate the split).

base_of() { # $1 = stem -> V0D/V0E baselines; MUST run BEFORE the RCON
  # command: the scene writes the marker INSIDE the command's
  # execution (the mtime measured #39: 17:57:32 vs the response
  # 17:57:33.8) — a baseline read after the response would see
  # the fresh file and wait for a change that already happened
  V0D=$(cat "srv-wall/c-imagine/scenes/$1.done" 2>/dev/null || echo 0)
  V0E=$(cat "srv-wall/c-imagine/scenes/$1.exhausted" 2>/dev/null || echo 0)
}
wait_done() { # $1 = stem $2 = V0D $3 = V0E; rc0 done | rc1 exhausted | rc2 timeout
  local BASE="srv-wall/c-imagine/scenes/$1"
  local VD VE
  for i in $(seq 1 1000); do
    VD=$(cat "$BASE.done" 2>/dev/null || echo 0)
    VE=$(cat "$BASE.exhausted" 2>/dev/null || echo 0)
    [ "$VD" != "$2" ] && return 0
    [ "$VE" != "$3" ] && return 1
    sleep 0.03
  done
  echo "no done marker file: $1"; return 2
}
# 14-ak CLIENT-CRASH CONTAINMENT (measured #84, run 41848055305
# truth shard 0: during the create-entity DYN walk the REAL client
# DIED — a bare create:contraption spawn carries spawn data the
# client cannot process (Contraption.readNBT NPE, "contraption" is
# null) and the frame became the "Connection Lost" screen; EVERY
# truth shot after that point on the shard was the disconnect
# screen — the two item pages joined 77/77 garbage RED each and
# gated the gallery). After each page's wait_done the walk asserts
# the client is alive (rcon "list" must carry cimviewer); on death:
# rejoin (the camera-pins launch snippet), RE-RUN the same page
# command once more — the scene's entity-crash-ban.tsv now holds
# the killer types as named skip rows (the scene's settle pass
# bans them when it sees zero players online) so the re-run cannot
# kill again — and re-shoot. Bounded: max 2 rejoins per page, then
# the shard FAILS loudly with the page id (the coverage gate stays
# honest — no silent gaps). The item/particle/fluid walks run the
# same liveness check (they follow the entity walk; their content
# is not modded-entity spawns — item_display rigs are vanilla
# types — so no ban is expected, but the rejoin + re-run applies).
client_alive() {
  local L
  L=$(rcon "list" 2>/dev/null) || return 1
  case "$L" in *cimviewer*) return 0;; *) return 1;; esac
}
DEATHS=0
truth_rejoin() {
  echo "client death detected — rejoining the truth client"
  DEATHS=$((DEATHS+1))
  mkdir -p truth-client-deaths
  cp truth-client/client.out "truth-client-deaths/client-$DEATHS.out" 2>/dev/null || true
  cp -r truth-client/game/crash-reports "truth-client-deaths/crash-reports-$DEATHS" 2>/dev/null || true
  pkill -f "truth-client/game" 2>/dev/null || true   # the dead JVM stays on the disconnect screen; free the window + heap
  sleep 2
  # 14-di: the rejoin carries the screen-art dump too (a menu opened
  # on a rejoined client must still capture its art)
  # delimiter '#' — same $PWD-contains-slashes fix as the first attempt
  LAUNCH=$(sed "s#^java #java -Dcim.screenart=$PWD/truth-screens #" truth-client/launch.txt)
  setsid bash -c "cd truth-client && $LAUNCH > client.out 2>&1" < /dev/null > /dev/null 2>&1 &
  local L=""
  for i in $(seq 1 90); do
    L=$(rcon "list" 2>/dev/null || true)
    case "$L" in *cimviewer*) break;; esac
    sleep 1
  done
  case "$L" in *cimviewer*) ;;
    *) echo "truth client FAILED to rejoin"; tail -30 truth-client/client.out; return 1;; esac
  for i in $(seq 1 30); do
    grep -qE "Loaded [0-9]+ advancements" truth-client/client.out 2>/dev/null && break
    sleep 1
  done
  sleep 8   # first chunk render on llvmpipe (mirror of camera-pins)
  # a fresh client boots with the HUD on: without this every later
  # shot carries the hotbar, the hand and the tutorial toast
  java -cp vtools Key F1
  sleep 1
  echo "truth client rejoined: $L"
}
ensure_alive_rerun() { # $1=stem $2=page label $3=page; $4..=the page's rcon words (re-run verbatim)
  local STEM="$1" LABEL="$2" P="$3" RC ATTEMPTS=0
  shift 3
  while ! client_alive; do
    ATTEMPTS=$((ATTEMPTS+1))
    if [ "$ATTEMPTS" -gt 2 ]; then
      echo "CLIENT-DEATH-UNCONTAINED after page $LABEL — 2 rejoins spent, failing the shard honestly"
      exit 1
    fi
    truth_rejoin || exit 1
    base_of "$STEM"
    rcon "$@" >/dev/null 2>&1 || true
    RC=0; wait_done "$STEM" "$V0D" "$V0E" || RC=$?
    if [ "$RC" = "2" ]; then
      echo "page $LABEL re-run no done marker"; tail -10 srv-wall/boot.log; exit 1
    fi
  done
}
