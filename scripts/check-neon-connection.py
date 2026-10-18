#!/usr/bin/env python3
"""Actual Neon integration on an explicitly named test endpoint; no production env reads."""
import argparse
import base64
import os
from pathlib import Path
import secrets
import shutil
import socket
import subprocess
import time
import urllib.request
from urllib.parse import urlsplit

parser=argparse.ArgumentParser(description=__doc__)
parser.add_argument('--expected-endpoint',required=True)
args=parser.parse_args()
root=Path(__file__).resolve().parents[1]
private=root/'.tooling/neon-setup'
pooled=(private/'test-pooled.url').read_text().strip()
direct=(private/'test-direct.url').read_text().strip()
for value,expected in [(pooled,args.expected_endpoint+'-pooler.'),(direct,args.expected_endpoint+'.')]:
    if not (urlsplit(value).hostname or '').startswith(expected):
        raise SystemExit('Test endpoint mismatch; refused')
for port in [8082,3003]:
    with socket.socket() as probe:
        if probe.connect_ex(('127.0.0.1',port))==0:
            raise SystemExit('Test port already occupied; refused')
work=private/('app-check-'+time.strftime('%Y%m%d-%H%M%S'))
front=work/'frontend'
shutil.copytree(root/'frontend',front,ignore=shutil.ignore_patterns('node_modules','.next','.env','.env.*','test-results','playwright-report','coverage','*.tsbuildinfo'))
(front/'node_modules').symlink_to(root/'frontend/node_modules',target_is_directory=True)
shutil.copy2(root/'package.json',work/'package.json')
env=os.environ.copy()
env['PATH']=str(root/'.tooling/node/node_modules/node/bin')+os.pathsep+env['PATH']
env.update(NEXT_TELEMETRY_DISABLED='1',NEXT_PUBLIC_KAKAO_MAP_APP_KEY='',BACKEND_INTERNAL_URL='http://127.0.0.1:8082')
with (root/'evidence/neon-build.log').open('w') as log:
    result=subprocess.run(['npm','run','build'],cwd=work,env=env,stdout=log,stderr=log)
if result.returncode:
    raise SystemExit('npm run build failed; see evidence/neon-build.log')
print('PASS npm run build (isolated production copy)',flush=True)
server_files=list((front/'.next/standalone').rglob('server.js'))
server=next((p for p in server_files if 'node_modules' not in p.parts),None)
if not server:
    raise SystemExit('Standalone server missing')
shutil.copytree(front/'.next/static',server.parent/'.next/static',dirs_exist_ok=True)
if (front/'public').exists():shutil.copytree(front/'public',server.parent/'public',dirs_exist_ok=True)
backend_env=env.copy()
backend_env.update(DATABASE_URL=pooled,DATABASE_URL_UNPOOLED=direct,SPRING_PROFILES_ACTIVE='local,neon',
    APP_ORIGIN='http://localhost:3003',AUTH_COOKIE_SECURE='false',APP_RECOVERY_REQUIRED='false',
    JWT_SECRET=base64.b64encode(secrets.token_bytes(32)).decode(),
    CURSOR_SECRET=base64.b64encode(secrets.token_bytes(32)).decode(),BACKEND_PORT='8082')
# Framework failures may mention a DB endpoint: keep startup logs private.
(private/'test-backend.log').touch(mode=0o600)
processes=[]
logs=[]
try:
    backend_log=(private/'test-backend.log').open('w');logs.append(backend_log)
    backend=subprocess.Popen(['java','-Xmx256m','-jar',str(root/'backend/build/libs/money-map-0.1.0.jar')],cwd=root,env=backend_env,stdout=backend_log,stderr=backend_log)
    processes.append(backend)
    for _ in range(180):
        if backend.poll() is not None:raise RuntimeError('Neon backend startup failed; private log retained')
        try:
            with urllib.request.urlopen('http://127.0.0.1:8082/actuator/health/readiness',timeout=2) as response:
                if response.status==200:break
        except Exception:time.sleep(.5)
    else:raise RuntimeError('Neon backend readiness timeout')
    print('PASS Spring Boot startup, Flyway migrations and Neon readiness',flush=True)
    frontend_log=(private/'test-frontend.log').open('w');logs.append(frontend_log)
    frontend_env=env.copy();frontend_env.update(PORT='3003',HOSTNAME='127.0.0.1')
    frontend=subprocess.Popen(['node',str(server)],cwd=server.parent,env=frontend_env,stdout=frontend_log,stderr=frontend_log)
    processes.append(frontend)
    for _ in range(80):
        try:
            with urllib.request.urlopen('http://localhost:3003/login',timeout=1) as response:
                if response.status==200:break
        except Exception:time.sleep(.25)
    else:raise RuntimeError('Frontend readiness timeout')
    browser_env=env.copy();browser_env['PLAYWRIGHT_BROWSERS_PATH']=str(root/'.tooling/playwright')
    result=subprocess.run(['node',str(root/'scripts/e2e-neon.cjs')],cwd=root,env=browser_env)
    if result.returncode:raise RuntimeError('Real Neon end-to-end verification failed')
finally:
    for process in reversed(processes):
        if process.poll() is None:
            process.terminate()
            try:process.wait(timeout=5)
            except subprocess.TimeoutExpired:process.kill();process.wait()
    for log in logs:log.close()
