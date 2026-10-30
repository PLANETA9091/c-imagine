#!/usr/bin/env python3
"""Parallel asset fetcher for the truth client (asset index from the vanilla
1.21.1 profile). Writes objects under <out>/assets/objects/<h2>/<hash> and the
index under <out>/assets/indexes/<id>.json — the vanilla layout the client's
--assetsDir expects. Parallel (16 workers), resumable (skips existing)."""
import json
import pathlib
import sys
import urllib.request
from concurrent.futures import ThreadPoolExecutor

profile = json.loads(pathlib.Path(sys.argv[1]).read_text())
out = pathlib.Path(sys.argv[2])
asset_index_meta = profile["assetIndex"]
asset_id = asset_index_meta["id"]
index_url = asset_index_meta["url"]

req = urllib.request.Request(index_url, headers={"User-Agent": "c-imagine-truth/1.0"})
index = json.loads(urllib.request.urlopen(req, timeout=120).read())
(out / "assets" / "indexes").mkdir(parents=True, exist_ok=True)
(out / "assets" / "indexes" / f"{asset_id}.json").write_bytes(
    json.dumps(index).encode())
base = index.get("map_to_resources", False)

objects = index["objects"]
print(f"asset index {asset_id}: {len(objects)} objects")

RES = "https://resources.download.minecraft.net"
done = [0]


def fetch(item):
    name, meta = item
    h = meta["hash"]
    target = out / "assets" / "objects" / h[:2] / h
    if target.exists() and target.stat().st_size == meta["size"]:
        done[0] += 1
        return
    target.parent.mkdir(parents=True, exist_ok=True)
    for attempt in range(3):
        try:
            req = urllib.request.Request(f"{RES}/{h[:2]}/{h}",
                                         headers={"User-Agent": "c-imagine-truth/1.0"})
            data = urllib.request.urlopen(req, timeout=120).read()
            if len(data) != meta["size"]:
                raise IOError(f"size {len(data)} != {meta['size']}")
            target.write_bytes(data)
            done[0] += 1
            if done[0] % 500 == 0:
                print(f"  {done[0]}/{len(objects)}")
            return
        except Exception as e:  # noqa: BLE001
            if attempt == 2:
                print(f"FAIL {name}: {e}")
            continue


with ThreadPoolExecutor(max_workers=16) as ex:
    list(ex.map(fetch, objects.items()))
print(f"assets done: {done[0]}/{len(objects)} -> {out/'assets'}")
