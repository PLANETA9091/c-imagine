#!/usr/bin/env python3
"""Parallel lanes for the parity runner: lane config, validation and the shared work queue.

A lane = one OURS player (Horizon client on hz.sh slot N) + one TRUTH player (truth-ctl port) + one
area origin. Lanes run on the same two servers. Lane k builds in its own area, so scenario coordinates
are offset by the lane origin (run.py: offset_scenario / offset_command). The lane config lives in
lanes.json next to this file (or --lanes-file):

  [{"lane": 0, "ours_player": "Lane_B", "hz_slot": 3,
    "truth_player": "TruthLane0", "truth_port": 25599, "origin": [0, 0, 0]}, ...]

Rules checked by validate_lanes: lane ids are 0..n-1, names, hz slots and truth ports are unique, and
origins are at least MIN_SEPARATION apart (Chebyshev distance) so no lane's reset area or scenario
footprint touches another lane's.

run_queue runs one pool process per lane. Each process is pinned to its lane by the pool initializer.
Work items go through the pool's shared task queue (imap_unordered, chunksize 1), so a lane that
finishes early takes the next scenario. The main process is the only writer of results.
"""
import json
import multiprocessing
import os
import traceback

HERE = os.path.dirname(os.path.abspath(__file__))
DEFAULT_LANES_FILE = os.path.join(HERE, "lanes.json")
MIN_SEPARATION = 48  # the reset area spans x 296..320 around each origin (25 wide): 48 leaves a gap
REQUIRED = ("lane", "ours_player", "hz_slot", "truth_player", "truth_port", "origin")


class LaneError(ValueError):
    pass


def normalize(entry):
    missing = [k for k in REQUIRED if k not in entry]
    if missing:
        raise LaneError("lane entry misses %s: %r" % (", ".join(missing), entry))
    origin = entry["origin"]
    if not isinstance(origin, list) or len(origin) != 3 or not all(isinstance(v, int) for v in origin):
        raise LaneError("lane %s: origin must be [x, y, z] integers" % entry["lane"])
    return {
        "lane": int(entry["lane"]),
        "ours_player": str(entry["ours_player"]),
        "hz_slot": int(entry["hz_slot"]),
        "truth_player": str(entry["truth_player"]),
        "truth_port": int(entry["truth_port"]),
        "origin": [int(v) for v in origin],
    }


def validate_lanes(lanes):
    """lanes: list of normalized dicts. Raises LaneError on the first broken rule."""
    if not lanes:
        raise LaneError("no lanes defined")
    ids = [l["lane"] for l in lanes]
    if sorted(ids) != list(range(len(lanes))):
        raise LaneError("lane ids must be 0..%d, got %s" % (len(lanes) - 1, ids))
    for key in ("ours_player", "hz_slot", "truth_player", "truth_port"):
        vals = [l[key] for l in lanes]
        dup = sorted(set(v for v in vals if vals.count(v) > 1), key=str)
        if dup:
            raise LaneError("%s must be unique across lanes, duplicated: %s" % (key, dup))
    if any(l["hz_slot"] < 1 for l in lanes):
        raise LaneError("hz_slot must be >= 1")
    for i, a in enumerate(lanes):
        for b in lanes[i + 1:]:
            sep = max(abs(a["origin"][0] - b["origin"][0]), abs(a["origin"][1] - b["origin"][1]),
                      abs(a["origin"][2] - b["origin"][2]))
            if sep < MIN_SEPARATION:
                raise LaneError("lanes %d and %d origins are %d apart (need >= %d)"
                                % (a["lane"], b["lane"], sep, MIN_SEPARATION))
    return lanes


def load_lanes(path=DEFAULT_LANES_FILE):
    with open(path, encoding="utf-8") as f:
        raw = json.load(f)
    if not isinstance(raw, list):
        raise LaneError("%s: top level must be a list of lanes" % path)
    lanes = sorted((normalize(e) for e in raw), key=lambda l: l["lane"])
    return validate_lanes(lanes)


def first_k(lanes, k):
    """The first k lanes (by id). Raises when the file defines fewer."""
    if k < 1:
        raise LaneError("--lanes wants K >= 1")
    if k > len(lanes):
        raise LaneError("--lanes %d but the lane file defines %d" % (k, len(lanes)))
    return lanes[:k]


def lane_by_id(lanes, lane_id):
    for l in lanes:
        if l["lane"] == lane_id:
            return l
    raise LaneError("no lane %d in the lane file" % lane_id)


# ------------------------------------------------------------------ shared queue
_LANE = None  # the lane this pool process is pinned to (set by _init_worker)


def _init_worker(counter, lanes):
    global _LANE
    with counter.get_lock():
        index = counter.value
        counter.value += 1
    _LANE = lanes[index]


def current_lane():
    """The lane of the calling pool process (None outside run_queue)."""
    return _LANE


def _call(args):
    work, item = args
    lane = _LANE
    try:
        return {"item": item, "lane": lane["lane"] if lane else None, "result": work(item, lane),
                "error": None}
    except Exception:  # one bad scenario must not stop the queue
        return {"item": item, "lane": lane["lane"] if lane else None, "result": None,
                "error": traceback.format_exc()}


def run_queue(items, lanes, work, on_result=None):
    """Run work(item, lane) for every item on len(lanes) lane-pinned processes.

    work must be a module-level function (it is pickled for the pool). Returns the list of
    {"item", "lane", "result", "error"} dicts in completion order. on_result, when given, is called in
    the main process with each dict as it completes (for live CSV writing).
    """
    if not lanes:
        raise LaneError("run_queue needs at least one lane")
    ctx = multiprocessing.get_context("fork")
    counter = ctx.Value("i", 0)
    done = []
    with ctx.Pool(len(lanes), initializer=_init_worker, initargs=(counter, list(lanes))) as pool:
        for res in pool.imap_unordered(_call, [(work, item) for item in items], chunksize=1):
            done.append(res)
            if on_result is not None:
                on_result(res)
    return done
