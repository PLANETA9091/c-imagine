#!/usr/bin/env python3
"""Exercise real apt source formats without root or network access."""
import importlib.util
import pathlib
import tempfile

spec = importlib.util.spec_from_file_location("fallback", pathlib.Path(__file__).with_name("ubuntu-mirror-fallback.py"))
fallback = importlib.util.module_from_spec(spec)
spec.loader.exec_module(fallback)
with tempfile.TemporaryDirectory() as directory:
    root = pathlib.Path(directory)
    (root / "sources.list.d").mkdir()
    fixtures = {
        "sources.list": "deb [signed-by=/usr/share/keyrings/ubuntu-archive-keyring.gpg] https://security.ubuntu.com/ubuntu resolute-security main\n",
        "apt-mirrors.txt": "http://security.ubuntu.com/ubuntu\nhttps://archive.ubuntu.com/ubuntu\n",
        "sources.list.d/ubuntu.sources": "Types: deb\nURIs: https://security.ubuntu.com/ubuntu\nSuites: resolute-security\nComponents: main universe\nSigned-By: /usr/share/keyrings/ubuntu-archive-keyring.gpg\n",
        "sources.list.d/third-party.list": "deb https://security.ubuntu.com.example/ubuntu stable main\n",
        "sources.list.d/other.sources": "Types: deb\nURIs: mirror+file:/etc/apt/apt-mirrors.txt\nSuites: resolute resolute-updates\n",
        "keys.gpg": "https://security.ubuntu.com/untouched\n",
    }
    for name, content in fixtures.items():
        (root / name).write_text(content)
    assert fallback.rewrite(root) == 3
    for name, content in fixtures.items():
        expected = content.replace("://security.ubuntu.com/", "://archive.ubuntu.com/") if name != "keys.gpg" else content
        assert (root / name).read_text() == expected, name
    assert fallback.rewrite(root) == 0
    assert fallback.rewrite(root / "missing") == 0
print("Ubuntu mirror fallback: apt formats, signing keys, unrelated hosts and idempotence PASS")
