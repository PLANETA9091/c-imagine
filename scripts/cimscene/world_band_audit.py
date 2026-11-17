#!/usr/bin/env python3
"""14-e86 THE WORLD-BAND AUDIT: parse the restored world's region files and
report the block states at the p16 wall-band cells (x -5..5, y -60..-54,
z 9) vs the manifest's states — the H1 snapshot-gap receipt (the walk's
END vs the walk-time contract)."""
import struct
import sys
import zlib

def read_region(path, cx, cz):
    with open(path, 'rb') as f:
        data = f.read()
    idx = ((cx & 31) + (cz & 31) * 32) * 4
    off = (data[idx] << 16) | (data[idx+1] << 8) | data[idx+2]
    if off == 0:
        return None
    start = off * 4096
    ln = struct.unpack('>I', data[start:start+4])[0]
    comp = data[start+4]
    payload = data[start+5:start+4+ln]
    if comp == 2:
        return zlib.decompress(payload)
    if comp == 1:
        import gzip
        return gzip.decompress(payload)
    return payload

def find_tag(buf, start, key):
    # find a named tag's payload start (the name length prefix = big-endian)
    needle = b'\x08' + struct.pack('>H', len(key)) + key.encode()
    at = buf.find(needle, start)
    if at < 0:
        return None, None
    # the name's payload = the string after
    slen = struct.unpack('>H', buf[at+3+0+0:at+5][0:2])[0] if False else None
    return at, len(key)

def parse_chunk(buf):
    # minimal NBT walk: collect the block_states palette + the indices per section
    import nbtlib
    nbt = nbtlib.File.from_buffer(io.BytesIO(buf), byteorder='big') if hasattr(nbtlib.File, 'from_buffer') else None
    return nbt

def main():
    import nbtlib
    path = sys.argv[1] if len(sys.argv) > 1 else '/tmp/e57-art/sp-505/world/region/r.-1.0.mca'
    # the p16 cells: x -5..5, z 9 -> the chunk (0, 0); y -60..-54
    cx, cz = 0, 0
    raw = read_region(path, cx, cz)
    if raw is None:
        print("chunk (0,0) not generated")
        return
    import io as _io
    nbt = nbtlib.File.parse(_io.BytesIO(raw), byteorder='big')
    root = nbt
    secs = root['sections'] if 'sections' in root else root.get('sections')
    # find the sections covering y -60..-54: the section index = (y+64)>>4 = 0
    for sec in secs:
        yidx = int(sec['Y'])
        if yidx != 0:
            continue
        pal = sec['palette']
        print(f"section Y={yidx}: palette {len(pal)} entries")
        names = [str(p['Name']) for p in pal]
        from collections import Counter
        print("  palette:", Counter(names).most_common(12))
        if 'block_states' in sec:
            data = sec['block_states']['data']
            print("  data bytes:", len(data))
    print("OK")

if __name__ == '__main__':
    main()
