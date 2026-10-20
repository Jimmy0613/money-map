#!/usr/bin/env python3
"""Run known development commands, loading dotenv without shell evaluation."""
import os
import shutil
import subprocess
import sys
from pathlib import Path
root = Path(__file__).resolve().parents[1]
if len(sys.argv) != 2 or sys.argv[1] not in ('backend', 'frontend', 'db', 'stop'):
    raise SystemExit('Usage: python3 scripts/run-local.py backend|frontend|db|stop')
env = os.environ.copy()
p = root / '.env'
if p.exists():
    for raw in p.read_text(encoding='utf-8').splitlines():
        line = raw.strip()
        if not line or line.startswith('#'): continue
        key, sep, value = line.partition('=')
        if not sep or not key.replace('_','').isalnum():
            raise SystemExit('Invalid dotenv line; values omitted.')
        env.setdefault(key, value.strip().strip('"').strip("'"))
node = root / '.tooling/node/node_modules/node/bin'
if node.exists(): env['PATH'] = str(node)+os.pathsep+env['PATH']
env['GRADLE_USER_HOME'] = str(root / '.tooling/gradle')
if os.name == 'nt':
    wrapper = root / 'backend/gradlew.bat'
    gradle = str(wrapper) if wrapper.exists() else shutil.which('gradle', path=env['PATH'])
    if sys.argv[1] == 'backend' and not gradle:
        raise SystemExit('Install Gradle 8.14.3 on PATH for Windows; see development.md.')
else:
    gradle = './gradlew'
npm = shutil.which('npm.cmd' if os.name == 'nt' else 'npm', path=env['PATH'])
if sys.argv[1] == 'frontend' and not npm:
    raise SystemExit('Install Node 22.22.0 and npm on PATH; see development.md.')
commands = {
    'backend': (root/'backend', [gradle,'bootRun']),
    'frontend': (root/'frontend', [npm,'run','dev','--','--port',env.get('FRONTEND_PORT','3000')]),
    'db': (root,['docker','compose','up','-d','db']),
    'stop': (root,['docker','compose','stop'])
}
cwd, command = commands[sys.argv[1]]
raise SystemExit(subprocess.call(command, cwd=cwd, env=env))
