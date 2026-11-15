#!/usr/bin/env python3
"""Process-level regressions for the diagnostic probe; no display or network."""
import os
from pathlib import Path
import subprocess
import tempfile

TOOL = Path(__file__).with_name('probe-client.sh').resolve()
with tempfile.TemporaryDirectory() as tmp:
    root = Path(tmp)
    java = root / 'java'
    java.write_text('''#!/usr/bin/env python3
import os, sys, time
assert os.environ['SDL_VIDEO_DRIVER'] == 'x11'
assert os.environ['SDL_VIDEO_FORCE_EGL'] == '1'
assert sys.argv[1:] == ['--graphicsBackend', os.environ['EXPECT_BACKEND']]
mode = os.environ['PROBE_FIXTURE']
if mode == 'ready':
    print('Sound engine started', flush=True)
    time.sleep(20)
elif mode == 'crash':
    print('Unable to initialize SDL: No available video device', flush=True)
    sys.exit(1)
elif mode == 'early_exit':
    print('Backend: selected, but no window', flush=True)
else:
    time.sleep(20)
''')
    java.chmod(0o755)
    launch = root / 'launch.txt'
    launch.write_text('java --graphicsBackend opengl\n')
    for mode, backend, expected in [('ready', 'opengl', 0), ('ready', 'vulkan', 0),
                                    ('crash', 'opengl', 1), ('early_exit', 'opengl', 1),
                                    ('timeout', 'vulkan', 1)]:
        env = dict(os.environ, PATH=str(root)+os.pathsep+os.environ['PATH'],
                   CIM_PROBE_SECONDS='2', PROBE_FIXTURE=mode, EXPECT_BACKEND=backend)
        r = subprocess.run(['bash', str(TOOL), str(launch), backend, str(root/'client.out')],
                           env=env, text=True, capture_output=True, timeout=8)
        assert r.returncode == expected, (mode, backend, r.stdout, r.stderr)
        assert not list(root.glob('.cim-window-probe.*'))
        print('PASS', mode, backend)
