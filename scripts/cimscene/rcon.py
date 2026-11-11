#!/usr/bin/env python3
"""Minimal RCON client (stdlib) — deterministic console for the gallery runs.

The console FIFO (tail -f | java) proved fragile in the sandbox: tail dies on
the first EOF and the next writer blocks forever. RCON is a stateful TCP
protocol the servers already ship (enable-rcon=true) — every command gets its
response back, so the harness can ASSERT on results instead of log-grepping.

Usage:
  rcon.py <host> <port> <password> <command...>
  rcon.py <host> <port> <password> --f <file-of-commands>
  rcon.py <host> <port> <password> --batch   (commands from stdin, one per line)

Prints the raw response per command. Exit 0 always (responses are the data).

--batch is the persistent-driver mode for the walk loops: ONE process, ONE
TCP connection, ONE auth — the bash walk drives it line-by-line (coproc) and
keeps its per-page marker waits between the commands. The startup + connect
+ auth cost (~0.3-0.5 s) is paid once instead of per page (~500+ invocations
per shard ≈ 3-4 min of pure overhead). The per-command response block is
terminated by __RCON_RESP_END__ (flushed) so the caller can read exactly one
response per command; __RCON_READY__ confirms the auth before the first send.
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


def connect_auth(host, port, pw):
    s = socket.create_connection((host, port), timeout=30)
    s.sendall(pkt(1, 3, pw))
    rid, _, _ = read_pkt(s)
    if rid == -1:
        raise SystemExit("rcon: auth failed")
    return s


def main():
    if len(sys.argv) < 5:
        raise SystemExit(__doc__)
    host, port, pw = sys.argv[1], int(sys.argv[2]), sys.argv[3]
    rest = sys.argv[4:]
    if rest[0] == "--batch":
        batch(host, port, pw)
        return
    if rest[0] == "--f":
        cmds = [ln.strip() for ln in open(rest[1], encoding="utf-8") if ln.strip()]
    else:
        cmds = list(rest)  # each argument is its own command
    s = connect_auth(host, port, pw)
    for c in cmds:
        s.sendall(pkt(2, 2, c))
        rid, _, resp = read_pkt(s)
        print(f"> {c}\n{resp}".rstrip())
    s.close()


def batch(host, port, pw):
    """Persistent driver: commands from stdin, one per line; one connection."""
    s = connect_auth(host, port, pw)
    print("__RCON_READY__", flush=True)
    for line in iter(sys.stdin.readline, ""):
        c = line.rstrip("\n")
        if not c or c.startswith("#"):
            continue
        s.sendall(pkt(2, 2, c))
        rid, _, resp = read_pkt(s)
        print(f"> {c}", flush=True)
        if resp:
            print(resp, flush=True)
        print("__RCON_RESP_END__", flush=True)
    s.close()


if __name__ == "__main__":
    main()
