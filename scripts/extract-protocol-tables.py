#!/usr/bin/env python3
"""M4.0 ground truth: extract wire packet-id tables from the Mojang-mapped
Paper 26.3 server sources (Vineflower output of net/minecraft/network/protocol).

Packet ids on the wire are the REGISTRATION ORDER of addPacket() calls in the
per-state protocol builders (GameProtocols, ConfigurationProtocols, LoginProtocols,
HandshakeProtocols, StatusProtocols). The `withBundlePacket` call CONSUMES an id
at its chain position (play clientbound id 0 = bundle_delimiter).

Usage:
  python3 extract-protocol-tables.py <proto-src-dir> <out-json>
"""
import json
import re
import sys
from pathlib import Path

ADD = re.compile(
    r"addPacket\(\s*([A-Za-z0-9_$]+)\.([A-Z0-9_]+)\s*,\s*([A-Za-z0-9_$]+)\.([A-Za-z0-9_$]+)"
)
BUNDLE = re.compile(
    r"withBundlePacket\(\s*([A-Za-z0-9_$]+)\.([A-Z0-9_]+)\s*,\s*([A-Za-z0-9_$]+)::new\s*,\s*new\s+([A-Za-z0-9_$]+)\("
)

FILES = {
    "handshake": "handshake/HandshakeProtocols.java",
    "status": "status/StatusProtocols.java",
    "login": "login/LoginProtocols.java",
    "configuration": "configuration/ConfigurationProtocols.java",
    "play": "game/GameProtocols.java",
}


def parse_file(path: Path):
    text = path.read_text(encoding="utf-8")
    # Merge addPacket() and withBundlePacket() occurrences into ONE ordered
    # stream by source position: each consumes exactly one wire id.
    events = []
    for m in ADD.finditer(text):
        holder, const, cls, member = m.groups()
        flow = "clientbound" if const.startswith("CLIENTBOUND") else "serverbound"
        events.append((m.start(), flow, cls, f"{holder}.{const}.{member}"))
    for m in BUNDLE.finditer(text):
        holder, const, cont, delim = m.groups()
        flow = "clientbound" if const.startswith("CLIENTBOUND") else "serverbound"
        events.append((m.start(), flow, delim, f"{holder}.{const} (bundle delimiter)"))
    events.sort(key=lambda e: e[0])
    tables = {"clientbound": [], "serverbound": []}
    for _, flow, cls, const in events:
        tables[flow].append([cls, const])
    return tables


def main():
    src = Path(sys.argv[1])
    out = Path(sys.argv[2])
    result = {}
    for state, rel in FILES.items():
        p = src / "net/minecraft/network/protocol" / rel
        if not p.exists():
            print(f"WARN missing {p}")
            continue
        t = parse_file(p)
        result[state] = {
            "clientbound": [[i, c, k] for i, (c, k) in enumerate(t["clientbound"])],
            "serverbound": [[i, c, k] for i, (c, k) in enumerate(t["serverbound"])],
        }
    out.write_text(json.dumps(result, indent=1), encoding="utf-8")
    for state, t in result.items():
        for flow in ("clientbound", "serverbound"):
            rows = t[flow]
            print(f"== {state}/{flow}: {len(rows)} packets ==")
            for i, cls, const in rows:
                print(f"  {i:3d} {cls:<55s} {const}")


if __name__ == "__main__":
    main()
