#!/usr/bin/env python3
"""Minimal RCON client (stdlib) — deterministic console for the gallery runs.

The console FIFO (tail -f | java) proved fragile in the sandbox: tail dies on
the first EOF and the next writer blocks forever. RCON is a stateful TCP
protocol the servers already ship (enable-rcon=true) — every command gets its
response back, so the harness can ASSERT on results instead of log-grepping.

Usage:
  rcon.py <host> <port> <password> <command...>
  rcon.py <host> <port> <password> --f <file-of-commands>

Prints the raw response per command. Exit 0 always (responses are the data).
"""
import socket
import struct
import sys


def pkt(req_id, ptype, payload):
    body = struct.pack("<ii", req_id, ptype) + payload.encode("utf-8") + b"\x00\x00"
    return struct.pack("<i", len(body)) + body


def read_pkt(s):
    raw = b""
    while len(raw) < 4:
        c = s.recv(4 - len(raw))
        if not c:
            raise ConnectionError("rcon closed")
        raw += c
    (ln,) = struct.unpack("<i", raw)
    body = b""
    while len(body) < ln:
        c = s.recv(ln - len(body))
        if not c:
            raise ConnectionError("rcon closed mid-packet")
        body += c
    rid, ptype = struct.unpack("<ii", body[:8])
    return rid, ptype, body[8:-2].decode("utf-8", "replace")


def main():
    if len(sys.argv) < 5:
        raise SystemExit(__doc__)
    host, port, pw = sys.argv[1], int(sys.argv[2]), sys.argv[3]
    rest = sys.argv[4:]
    if rest[0] == "--f":
        cmds = [ln.strip() for ln in open(rest[1], encoding="utf-8") if ln.strip()]
    else:
        cmds = list(rest)  # each argument is its own command
    s = socket.create_connection((host, port), timeout=30)
    s.sendall(pkt(1, 3, pw))
    rid, _, _ = read_pkt(s)
    if rid == -1:
        raise SystemExit("rcon: auth failed")
    for c in cmds:
        s.sendall(pkt(2, 2, c))
        rid, _, resp = read_pkt(s)
        print(f"> {c}\n{resp}".rstrip())
    s.close()


if __name__ == "__main__":
    main()
