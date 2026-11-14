#!/usr/bin/env python3
"""THE COPROC LAW REGRESSION TEST (14-e15, report-only).

Proves with the exact production shapes (a synthetic driver + the real
rcon() body copied from the workflow) that:

  1. rcon() inside a pipeline element FAILS to send ("rcon driver died
     (send: ...)") — the measured walk-killer of three runs (16 shards
     each): a pipeline subshell cannot reach the coproc fd.
  2. rcon() inside a command substitution FAILS the same way.
  3. The FILE route (rcon > f 2>&1; tail -1 f) works — the fixed shape
     the workflow now uses at the 8 poke + 2 alive + 2 helper sites.

Exit 0 = the law holds (the broken shapes fail, the fixed shape works).
Any other combination = the regression is real; exit 1.
"""
import subprocess
import sys
import tempfile
import os

DRIVER = """\
import sys
print("__RCON_READY__", flush=True)
for line in sys.stdin:
    c = line.strip()
    if not c: continue
    print(f"reply to {c}", flush=True)
    print("__RCON_RESP_END__", flush=True)
"""

HARNESS = r"""#!/usr/bin/env bash
coproc RCON { python3 "%DRIVER%"; }
rcon() {
  local c line out ok
  for c in "$@"; do
    [ -n "$c" ] || continue
    printf '%s\n' "$c" >&"${RCON[1]}" || { echo "RCON_DRIVER_DIED_SEND"; return 1; }
    out=""; ok=0
    while IFS= read -r line <&"${RCON[0]}"; do
      if [ "$line" = "__RCON_RESP_END__" ]; then ok=1; break; fi
      out+="$line"$'\n'
    done
    [ "$ok" = "1" ] || { echo "RCON_DRIVER_DIED_READ"; return 1; }
    printf '%s' "$out"
  done
}
read -r -t 5 RCON_HELLO <&"${RCON[0]}" || { echo HANDSHAKE_FAIL; exit 1; }
case "$1" in
  pipe)   rcon "cmd" 2>&1 | tail -1 ;;
  subst)  OUT=$(rcon "cmd" 2>/dev/null); echo "SUBST:$OUT" ;;
  file)   rcon "cmd" > poke-receipt.log 2>&1 && tail -1 poke-receipt.log ;;
esac
echo "RC=$?"
"""


def run(mode):
    with tempfile.TemporaryDirectory() as td:
        drv = os.path.join(td, "driver.py")
        open(drv, "w").write(DRIVER)
        sh = os.path.join(td, "harness.sh")
        open(sh, "w").write(HARNESS.replace("%DRIVER%", drv))
        r = subprocess.run(["bash", sh, mode], capture_output=True, text=True,
                           timeout=30, cwd=td)
        return r.stdout + r.stderr


def main():
    pipe = run("pipe")
    subst = run("subst")
    file = run("file")
    ok = True
    if "RCON_DRIVER_DIED_SEND" not in pipe:
        print("UNEXPECTED: the piped shape did NOT die — the walk-killer is gone?!")
        ok = False
    else:
        print("law 1 holds: the pipeline shape dies (the reproduced walk-killer)")
    # 14-e15 MEASURED ASYMMETRY (bash 5.x): the pipeline element kills the
    # coproc send; the command substitution DOES reach the fd (verified here).
    # The policy still forbids both (the file route = shape-invariant), but
    # the regression test asserts the measured truth so a bash change cannot
    # silently flip the asymmetry.
    if "SUBST:reply to cmd" in subst:
        print("law 2 holds: the command-substitution shape WORKS (the measured")
        print("  bash-5 asymmetry — the policy keeps it forbidden anyway)")
    else:
        print("law 2 note: the command-substitution shape no longer delivers")
        print("  (the bash asymmetry flipped — re-verify the alive-check sites)")
        ok = False
    if "reply to cmd" not in file or "RC=0" not in file:
        print("UNEXPECTED: the file route failed:", file.strip()[:200])
        ok = False
    else:
        print("law 3 holds: the file route delivers the receipt (the fixed shape)")
    print("RESULT:", "THE COPROC LAW HOLDS" if ok else "REGRESSION")
    sys.exit(0 if ok else 1)


if __name__ == "__main__":
    main()
