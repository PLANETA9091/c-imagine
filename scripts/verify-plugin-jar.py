#!/usr/bin/env python3
"""Validate the packaged plugin's offline startup and Java 21 contract."""
from pathlib import Path
import re
import sys
from zipfile import BadZipFile, ZipFile


def verify(path):
    with ZipFile(path) as jar:
        names = set(jar.namelist())
        required = {"org/objectweb/asm/tree/analysis/Analyzer.class",
                    "META-INF/licenses/asm-analysis.txt"}
        missing = required - names
        if missing:
            raise ValueError("missing runtime payload: " + ", ".join(sorted(missing)))
        if "org/objectweb/asm/ClassReader.class" in names:
            raise ValueError("ASM core must remain supplied by Paper")
        if re.search(r"(?m)^libraries\s*:", jar.read("plugin.yml").decode()):
            raise ValueError("plugin startup still requests a dependency resolver")
        for name in sorted(names):
            if name.endswith(".class"):
                data = jar.read(name)
                if (len(data) < 8 or data[:4] != bytes.fromhex("cafebabe")
                        or not 45 <= int.from_bytes(data[6:8], "big") <= 65):
                    raise ValueError("not Java 21 compatible: " + name)
    print("plugin artifact: bundled analysis, no startup resolver, Java 21: PASS")


if __name__ == "__main__":
    try:
        verify(Path(sys.argv[1]))
    except (BadZipFile, OSError, ValueError, KeyError, IndexError) as error:
        raise SystemExit("plugin artifact FAIL: " + str(error))
