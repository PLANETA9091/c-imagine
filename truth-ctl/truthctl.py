#!/usr/bin/env python3
"""Tiny client for the truth-ctl/1 control socket (NeoForge client mod).

Usage:
    truthctl.py [--host H] [--port N] [--timeout S] CMD [ARGS...]

Examples:
    truthctl.py hello
    truthctl.py state
    truthctl.py break 10 64 10 --timeout 20
    truthctl.py select wooden_pickaxe
    truthctl.py use-item --hold 40
    truthctl.py respawn

--timeout S (before CMD) sets opts.timeout; a "--timeout S" after CMD is passed
through in args and interpreted by the mod the same way.
Prints the reply JSON. Exit status 1 when the reply has an "error" key.
Stdlib only.
"""

import json
import socket
import sys

USAGE = "usage: truthctl.py [--host H] [--port N] [--timeout S] CMD [ARGS...]"


def main(argv):
    host = "127.0.0.1"
    port = 25599
    opts = {}
    i = 0
    while i < len(argv) and argv[i].startswith("--") and argv[i] in ("--host", "--port", "--timeout", "-h", "--help"):
        flag = argv[i]
        if flag in ("-h", "--help"):
            print(USAGE)
            return 0
        if i + 1 >= len(argv):
            print("missing value for " + flag, file=sys.stderr)
            return 2
        value = argv[i + 1]
        if flag == "--host":
            host = value
        elif flag == "--port":
            port = int(value)
        else:
            opts["timeout"] = value
        i += 2
    if i >= len(argv):
        print(USAGE, file=sys.stderr)
        return 2
    cmd = argv[i]
    args = argv[i + 1:]
    request = {"cmd": cmd, "args": args, "opts": opts}
    line = json.dumps(request) + "\n"

    # Generous socket timeout: break/wait can run for tens of seconds.
    sock_timeout = 300.0
    try:
        with socket.create_connection((host, port), timeout=sock_timeout) as sock:
            sock.sendall(line.encode("utf-8"))
            buf = b""
            while b"\n" not in buf:
                chunk = sock.recv(65536)
                if not chunk:
                    break
                buf += chunk
    except OSError as exc:
        print("cannot reach truth-ctl at %s:%d: %s" % (host, port, exc), file=sys.stderr)
        return 1
    text = buf.split(b"\n", 1)[0].decode("utf-8", "replace")
    if not text:
        print("no reply from truth-ctl", file=sys.stderr)
        return 1
    try:
        reply = json.loads(text)
    except json.JSONDecodeError:
        print(text)
        return 1
    print(json.dumps(reply, indent=2, sort_keys=True))
    return 1 if isinstance(reply, dict) and "error" in reply else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
