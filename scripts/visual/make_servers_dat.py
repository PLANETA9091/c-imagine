#!/usr/bin/env python3
"""Write the visual-harness servers.dat that makes --quickPlayMultiplayer
AUTO-ACCEPT the server resource pack (M5.3 fix, measured on run 6).

Bytecode-proven chain (26.3 client.jar, javap):

  QuickPlay.joinMultiplayerWorld
    -> new ServerList(mc).load()                 SYNCHRONOUS read of
                                                 <gameDir>/servers.dat
    -> ServerList.get(String)                    EXACT ip-field equals match
    -> entry found? use it : create fresh (PROMPT)  <-- run 6 stall: the
                                                 fresh entry answered the
                                                 PackConfirmScreen nobody
                                                 could click under Xvfb
    -> ConnectScreen$1 -> DownloadedPackSource.configureForServerControl(
           conn, convertPackStatus(serverData.getResourcePackStatus()))
       convertPackStatus: ENABLED->ALLOWED, DISABLED->DECLINED, PROMPT->PENDING
    -> ServerPackManager.pushPack with ALLOWED prompt status auto-accepts
       (acceptPack), downloads and applies — NO prompt screen.

  ServerData$ServerPackStatus.FIELD_CODEC = Codec.BOOL
      .optionalFieldOf("acceptTextures")            (constant-pool proven)
      xmap: true->ENABLED, false->DISABLED, absent->PROMPT

  ServerList.load: NbtIo.read(Path) — UNCOMPRESSED NBT in 26.3 (javap:
  Files.newInputStream straight into NbtIo.read(DataInput); no GZIP layer).
  Entries without a "hidden" tag land in the visible list (lambda$load$0).

So the file we write is:

  root TAG_Compound (name "") {
    "servers": TAG_List<TAG_Compound> (len 1) [
      { "name": "c-imagine" (TAG_String)
        "ip":   "127.0.0.1:25565" (TAG_String)   exact-match target
        "acceptTextures": TAG_Byte 1             -> ENABLED -> auto-accept }
    ]
  }

Usage: make_servers_dat.py <gameDir> [host:port]
Writes <gameDir>/servers.dat; --selftest asserts the exact byte layout.
"""
import pathlib
import struct
import sys

NBT_TAG_BYTE = 1
NBT_TAG_STRING = 8
NBT_TAG_LIST = 9
NBT_TAG_COMPOUND = 10
NBT_TAG_END = 0


def _tag_string(name: str, value: str) -> bytes:
    data = value.encode("utf-8")
    return (
        bytes([NBT_TAG_STRING])
        + struct.pack(">H", len(name)) + name.encode("utf-8")
        + struct.pack(">H", len(data)) + data
    )


def _payload_string(value: str) -> bytes:
    data = value.encode("utf-8")
    return struct.pack(">H", len(data)) + data


def _payload_byte(value: int) -> bytes:
    return struct.pack(">b", value)


def build_servers_dat(host_port: str = "127.0.0.1:25565") -> bytes:
    """The exact 26.3 servers.dat layout (see module docstring)."""
    out = bytearray()
    # root compound, empty name
    out += bytes([NBT_TAG_COMPOUND]) + struct.pack(">H", 0)
    # "servers" TAG_List of compounds
    name = b"servers"
    out += (
        bytes([NBT_TAG_LIST])
        + struct.pack(">H", len(name)) + name
        + bytes([NBT_TAG_COMPOUND])          # element type
        + struct.pack(">i", 1)               # list length
    )
    # element 0: compound payload (named tags + end)
    entry = bytearray()
    entry += _tag_string("name", "c-imagine")
    entry += _tag_string("ip", host_port)
    entry += (
        bytes([NBT_TAG_BYTE])
        + struct.pack(">H", len("acceptTextures")) + b"acceptTextures"
        + _payload_byte(1)                   # true -> ENABLED
    )
    entry += bytes([NBT_TAG_END])
    out += entry
    # root end
    out += bytes([NBT_TAG_END])
    return bytes(out)


def selftest() -> int:
    blob = build_servers_dat()
    expected = (
        b"\x0a\x00\x00"                                   # root compound ""
        b"\x09\x00\x07servers"                            # TAG_List "servers"
        b"\x0a\x00\x00\x00\x01"                           # elem compound, len 1
        b"\x08\x00\x04name\x00\x09c-imagine"              # name
        b"\x08\x00\x02ip\x00\x0f127.0.0.1:25565"          # ip (15 chars)
        b"\x01\x00\x0eacceptTextures\x01"                 # acceptTextures=1
        b"\x00"                                           # entry end
        b"\x00"                                           # root end
    )
    assert blob == expected, f"layout drift:\n got {blob.hex()}\n exp {expected.hex()}"
    # determinism + round-trip sanity of the string lengths
    assert b"acceptTextures" in blob and b"127.0.0.1:25565" in blob
    print(f"selftest OK ({len(blob)} bytes)")
    return 0


def main(argv: list[str]) -> int:
    if "--selftest" in argv:
        return selftest()
    game_dir = pathlib.Path(argv[1])
    host_port = argv[2] if len(argv) > 2 else "127.0.0.1:25565"
    blob = build_servers_dat(host_port)
    game_dir.mkdir(parents=True, exist_ok=True)
    target = game_dir / "servers.dat"
    target.write_bytes(blob)
    print(f"servers.dat: {target} ({len(blob)} bytes, ip={host_port}, acceptTextures=1)")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
