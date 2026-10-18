#!/usr/bin/env python3
"""Isolated, temporary HTTPS phone preview. Never reads existing DB credentials.

Start: python3 scripts/mobile-preview.py
Stop: interrupt this process; the preview DB is stopped and preserved.
Logs and runtime secrets stay under ignored .tooling/mobile-preview/.
"""
import base64
import hashlib
import json
import os
from pathlib import Path
import re
import secrets
import shutil
import signal
import socket
import subprocess
import time
import urllib.request

ROOT = Path(__file__).resolve().parents[1]
WORK = ROOT / '.tooling/mobile-preview'
DOCKER = '/Applications/Docker.app/Contents/Resources/bin/docker'
NODE = ROOT / '.tooling/node/node_modules/node/bin/node'
CONTAINER = 'money-map-mobile-preview-db'
children = []
db_started = False


def run(command, name, env=None, cwd=ROOT):
    with (WORK / (name + '.log')).open('ab') as log:
        result = subprocess.run(command, cwd=cwd, env=env, stdout=log, stderr=log)
    if result.returncode:
        raise RuntimeError(name + ' failed; inspect log with secret redaction')


def start(command, name, env=None, cwd=ROOT):
    with (WORK / (name + '.log')).open('ab') as log:
        process = subprocess.Popen(command, cwd=cwd, env=env, stdout=log,
                                   stderr=log, start_new_session=True)
    children.append(process)
    return process


def wait_http(url):
    for _ in range(90):
        try:
            with urllib.request.urlopen(url, timeout=2) as response:
                if response.status == 200:
                    return
        except Exception:
            pass
        if any(p.poll() is not None for p in children):
            raise RuntimeError('A preview process exited')
        time.sleep(1)
    raise RuntimeError('Preview readiness timed out')


def stop(*_):
    raise KeyboardInterrupt


def main():
    global db_started
    os.umask(0o077)
    WORK.mkdir(parents=True, exist_ok=True)
    for port in (3001, 8081, 55432):
        with socket.socket() as probe:
            probe.bind(('127.0.0.1', port))
    if any((Path.home() / '.cloudflared' / n).exists()
           for n in ('config.yml', 'config.yaml')):
        raise RuntimeError('Existing cloudflared config requires separate review')
    state_file = WORK / 'secrets.json'
    if state_file.exists():
        private = json.loads(state_file.read_text())
    else:
        private = {k: secrets.token_urlsafe(48) for k in ('db', 'jwt', 'cursor')}
        with state_file.open('x') as out:
            json.dump(private, out)
    key = ''
    for raw in (ROOT / '.env').read_text().splitlines():
        name, sep, value = raw.partition('=')
        if sep and name.strip() == 'NEXT_PUBLIC_KAKAO_MAP_APP_KEY':
            key = value.strip().strip('"').strip("'")
    if not key:
        raise RuntimeError('Kakao public JavaScript key is missing')
    env = os.environ.copy()
    env.update(PATH=str(NODE.parent) + os.pathsep + env['PATH'],
               GRADLE_USER_HOME=str(ROOT / '.tooling/gradle'),
               BACKEND_INTERNAL_URL='http://127.0.0.1:8081',
               NEXT_PUBLIC_KAKAO_MAP_APP_KEY=key,
               NEXT_TELEMETRY_DISABLED='1')
    # Build a copy: local development output and source files are untouched.
    front = WORK / ('frontend-' + time.strftime('%Y%m%d-%H%M%S'))
    shutil.copytree(ROOT / 'frontend', front, ignore=shutil.ignore_patterns(
        'node_modules', '.next', 'test-results', 'playwright-report',
        'coverage', '*.tsbuildinfo', '.env', '.env.*'))
    (front / 'node_modules').symlink_to(ROOT / 'frontend/node_modules', target_is_directory=True)
    print('Building isolated production frontend and current backend...', flush=True)
    run([str(NODE), str(front / 'node_modules/next/dist/bin/next'), 'build', '--webpack'],
        'build-frontend', env, front)
    run(['./gradlew', 'bootJar'], 'build-backend', env, ROOT / 'backend')
    # Explicitly named preview volume is never removed or reset.
    found = subprocess.run([DOCKER, 'container', 'inspect', CONTAINER],
                           stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    db_env = os.environ.copy()
    db_env['POSTGRES_PASSWORD'] = private['db']
    if found.returncode == 0:
        label = subprocess.check_output([DOCKER, 'inspect', '--format',
            '{{index .Config.Labels "money-map.purpose"}}', CONTAINER], text=True).strip()
        if label != 'isolated-mobile-preview':
            raise RuntimeError('Unrecognized existing container')
        run([DOCKER, 'start', CONTAINER], 'db')
    else:
        run([DOCKER, 'run', '-d', '--name', CONTAINER,
         '--label', 'money-map.purpose=isolated-mobile-preview',
         '-e', 'POSTGRES_PASSWORD', '-e', 'POSTGRES_USER=mobile_preview',
         '-e', 'POSTGRES_DB=mobile_preview', '-p', '127.0.0.1:55432:5432',
         '-v', 'money-map-mobile-preview-pgdata:/var/lib/postgresql/data',
             'postgres:17.6'], 'db', db_env)
    db_started = True
    print('Starting approved temporary HTTPS tunnel...', flush=True)
    tunnel_log = WORK / 'tunnel.log'
    offset = tunnel_log.stat().st_size if tunnel_log.exists() else 0
    start([str(ROOT / '.tooling/cloudflared/cloudflared'), 'tunnel',
           '--no-autoupdate', '--protocol', 'http2', '--url', 'http://127.0.0.1:3001',
           '--metrics', '127.0.0.1:49312'], 'tunnel')
    origin = None
    for _ in range(60):
        match = re.search(r'https://[a-z0-9-]+\.trycloudflare\.com',
                          tunnel_log.read_text()[offset:])
        if match:
            origin = match.group()
            break
        time.sleep(1)
    if not origin:
        raise RuntimeError('No HTTPS hostname received')
    backend_env = env | dict(
        BACKEND_PORT='8081', SERVER_ADDRESS='127.0.0.1',
        SPRING_DATASOURCE_URL='jdbc:postgresql://127.0.0.1:55432/mobile_preview',
        SPRING_DATASOURCE_USERNAME='mobile_preview',
        SPRING_DATASOURCE_PASSWORD=private['db'],
        JWT_SECRET=base64.b64encode(base64.urlsafe_b64decode(private['jwt'])).decode(),
        CURSOR_SECRET=base64.b64encode(base64.urlsafe_b64decode(private['cursor'])).decode(),
        APP_ORIGIN=origin, AUTH_COOKIE_SECURE='true')
    start(['java', '-jar', str(ROOT / 'backend/build/libs/money-map-0.1.0.jar')],
          'backend', backend_env)
    wait_http('http://127.0.0.1:8081/actuator/health/readiness')
    start([str(NODE), str(front / 'node_modules/next/dist/bin/next'),
           'start', '--hostname', '127.0.0.1', '--port', '3001'], 'frontend', env, front)
    wait_http('http://127.0.0.1:3001/login')
    (WORK / 'status.json').write_text(json.dumps({
        'origin': origin, 'supervisorPid': os.getpid(),
        'pids': [p.pid for p in children], 'database': CONTAINER,
        'sourceEnvSha256': hashlib.sha256((ROOT / '.env').read_bytes()).hexdigest(),
        'startedAt': time.strftime('%Y-%m-%dT%H:%M:%S%z'),
    }, indent=2))
    print('READY ' + origin, flush=True)
    while all(p.poll() is None for p in children):
        time.sleep(2)
    raise RuntimeError('Preview process exited')


if __name__ == '__main__':
    signal.signal(signal.SIGTERM, stop)
    signal.signal(signal.SIGINT, stop)
    try:
        main()
    except KeyboardInterrupt:
        print('Stopping preview; existing and preview data preserved.', flush=True)
    finally:
        for child in reversed(children):
            if child.poll() is None:
                try:
                    os.killpg(child.pid, signal.SIGTERM)
                except ProcessLookupError:
                    pass
        # Only a container started in this invocation may be stopped.
        if db_started:
            subprocess.run([DOCKER, 'stop', CONTAINER], stdout=subprocess.DEVNULL,
                           stderr=subprocess.DEVNULL)
