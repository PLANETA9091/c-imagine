#!/usr/bin/env python3
"""Poll gallery run #110 (42023200006) for the ISOLATE_SCALE datum in ours s0's log."""
import json, subprocess, time, sys

TOKEN = open("/home/z/my-project/.secrets/gh_token").read().strip()
RUN = "42023200006"
HDR = ["-H", f"Authorization: Bearer {TOKEN}"]
BASE = "https://api.github.com/repos/PLANETA9091/c-imagine"


def api(path):
    r = subprocess.run(["curl", "-s", *HDR, f"{BASE}{path}"], capture_output=True, text=True, timeout=60)
    return json.loads(r.stdout)


def job_log(job_id):
    r = subprocess.run(["curl", "-sL", *HDR, f"{BASE}/actions/jobs/{job_id}/logs"], capture_output=True, text=True, timeout=120)
    return r.stdout


deadline = time.time() + int(sys.argv[1] if len(sys.argv) > 1 else 560)
seen_done = False
while time.time() < deadline:
    jobs = api(f"/actions/runs/{RUN}/jobs?per_page=40")
    arr = jobs.get("jobs", [])
    ours = [j for j in arr if "ours s0" in j["name"]]
    if not ours:
        print(time.strftime("%H:%M:%S"), "no ours s0 job yet"); time.sleep(45); continue
    j = ours[0]
    print(time.strftime("%H:%M:%S"), j["name"], j["status"], j["conclusion"], flush=True)
    if j["status"] == "completed":
        log = job_log(j["id"])
        hits = [l.split("Z ", 1)[-1] for l in log.splitlines() if "ISOLATE_SCALE" in l and "print(" not in l]
        print("=== ISOLATE_SCALE lines:", hits if hits else "NONE")
        # also show the step outcome and last error if failed
        for s in j["steps"]:
            if s["conclusion"] == "failure":
                print("FAILED STEP:", s["name"])
        if not hits and j["conclusion"] == "failure":
            errs = [l.split("Z ", 1)[-1] for l in log.splitlines() if "##[error]" in l]
            print("ERRORS:", errs[:5])
        seen_done = True
        break
    time.sleep(45)
if not seen_done:
    print("TIMEOUT — run still in progress, re-run the monitor")
