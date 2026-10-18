#!/usr/bin/env python3
"""Build/serve a temporary loopback-only personal signup UI. Never expose it via a tunnel.

Public signup stays disabled. The user enters their password and accepts terms locally.
No account, password, consent, or existing data is created/changed by this launcher.
"""
import argparse
import base64
import json
import os
from pathlib import Path
import secrets
import shutil
import socket
import subprocess
import time
import urllib.request
from urllib.parse import urlsplit

root = Path(__file__).resolve().parents[1]
folder = root / '.tooling/recovery-setup/personal-registration'
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--build-only', action='store_true')
args = parser.parse_args()
folder.mkdir(parents=True, exist_ok=True, mode=0o700)
os.umask(0o077)
env = os.environ.copy()
env['PATH'] = str(root / '.tooling/node/node_modules/node/bin') + os.pathsep + env['PATH']
env.update(NEXT_TELEMETRY_DISABLED='1', NEXT_PUBLIC_KAKAO_MAP_APP_KEY='',
           BACKEND_INTERNAL_URL='http://127.0.0.1:8086')
front = folder / 'frontend'
if args.build_only:
    if front.exists():
        raise SystemExit('Existing registration build preserved; use a new private build directory if a rebuild is needed')
    shutil.copytree(root / 'frontend', front, ignore=shutil.ignore_patterns(
        'node_modules', '.next', '.env', '.env.*', 'test-results', 'playwright-report', 'coverage', '*.tsbuildinfo'))
    (front / 'node_modules').symlink_to(root / 'frontend/node_modules', target_is_directory=True)
    shutil.copy2(root / 'package.json', folder / 'package.json')
    with (root / 'evidence/bootstrap-frontend-build.log').open('w') as output:
        result = subprocess.run(['npm', 'run', 'build'], cwd=folder, env=env, stdout=output, stderr=output)
    if result.returncode: raise SystemExit('npm run build failed')
    server = next(p for p in (front / '.next/standalone').rglob('server.js') if 'node_modules' not in p.parts)
    shutil.copytree(front / '.next/static', server.parent / '.next/static', dirs_exist_ok=True)
    if (front / 'public').exists(): shutil.copytree(front / 'public', server.parent / 'public', dirs_exist_ok=True)
    (folder / 'server-path.txt').write_text(str(server))
    print('PASS npm run build (isolated registration UI; existing frontend preserved)')
    raise SystemExit(0)

for port in [3004, 8086]:
    with socket.socket() as probe:
        if probe.connect_ex(('127.0.0.1', port)) == 0: raise SystemExit('Local registration port in use; refused')
config = {}
for line in (root / '.tooling/render-setup/backend.env').read_text().splitlines():
    if line and not line.startswith('#') and '=' in line:
        key, value = line.split('=', 1)
        config[key] = value.strip().strip('"\'')
direct = config['DATABASE_URL_UNPOOLED']
if not (urlsplit(direct).hostname or '').startswith('ep-delicate-cake-aoq0bn7q.'):
    raise SystemExit('Production endpoint mismatch; refused')
if not config.get('APP_ALLOWED_EMAILS') or ',' in config['APP_ALLOWED_EMAILS']:
    raise SystemExit('Exactly one personal email must be configured')
if config.get('APP_SIGNUP_ENABLED') != 'false': raise SystemExit('Public signup must remain closed')
# Require a functioning external authority before starting the local enrollment window.
with urllib.request.urlopen('https://money-map-r459.onrender.com/actuator/health/readiness', timeout=90) as response:
    if response.status != 200: raise SystemExit('Public gate is not ready')
backend_env = env.copy()
backend_env.update(DATABASE_URL=config['DATABASE_URL'], DATABASE_URL_UNPOOLED=direct,
    SPRING_PROFILES_ACTIVE='local,neon', BACKEND_PORT='8086', SERVER_ADDRESS='127.0.0.1',
    APP_ORIGIN='http://localhost:3004', AUTH_COOKIE_SECURE='false', APP_RECOVERY_REQUIRED='false',
    APP_ACCESS_RESTRICTED='true', APP_ALLOWED_EMAILS=config['APP_ALLOWED_EMAILS'], APP_SIGNUP_ENABLED='true',
    JWT_SECRET=base64.b64encode(secrets.token_bytes(32)).decode(),
    CURSOR_SECRET=base64.b64encode(secrets.token_bytes(32)).decode())
server = Path((folder / 'server-path.txt').read_text().strip())
processes = []
outputs = []
try:
    for name in ['backend', 'frontend']:
        outputs.append((folder / (name + '.log')).open('w'))
    processes.append(subprocess.Popen(['java', '-Xmx256m', '-jar', str(root / 'backend/build/libs/money-map-0.1.0.jar')],
        cwd=root, env=backend_env, stdout=outputs[0], stderr=outputs[0]))
    for _ in range(90):
        if processes[0].poll() is not None: raise SystemExit('Local registration backend stopped')
        try:
            with urllib.request.urlopen('http://127.0.0.1:8086/actuator/health/liveness', timeout=1) as response:
                if response.status == 200: break
        except Exception: pass
        time.sleep(1)
    front_env = env.copy()
    front_env.update(PORT='3004', HOSTNAME='127.0.0.1')
    processes.append(subprocess.Popen(['node', str(server)], cwd=server.parent, env=front_env,
        stdout=outputs[1], stderr=outputs[1]))
    print('Local-only personal signup: http://localhost:3004/signup', flush=True)
    (folder / 'processes.json').write_text(json.dumps([p.pid for p in processes]))
    while all(p.poll() is None for p in processes): time.sleep(1)
finally:
    for process in reversed(processes):
        if process.poll() is None:
            process.terminate()
            try: process.wait(timeout=15)
            except subprocess.TimeoutExpired: process.kill(); process.wait()
    for output in outputs: output.close()
