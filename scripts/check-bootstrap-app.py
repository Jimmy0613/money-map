#!/usr/bin/env python3
"""Run the production gate against the real Cloudflare test authority and isolated Neon DB."""
import base64
import datetime
import http.cookies
import json
import os
from pathlib import Path
import secrets
import socket
import subprocess
import time
import urllib.error
import urllib.request
import uuid

root = Path(__file__).resolve().parents[1]
folder = root / '.tooling/recovery-setup/test'
config = json.loads((folder / 'wrangler.json').read_text())['vars']
if config['NEON_BRANCH_ID'] != 'br-rough-term-aoa5ees1':
    raise SystemExit('Only isolated bootstrap test branch is permitted')
credential = json.loads((folder / 'secrets.json').read_text())
direct = (folder.parent / 'test-direct.url').read_text().strip()
origin = 'https://money-map-frontend-murex.vercel.app'
with socket.socket() as probe:
    if probe.connect_ex(('127.0.0.1', 8085)) == 0:
        raise SystemExit('Test port in use; refused')
email = 'bootstrap-' + str(uuid.uuid4()) + '@example.invalid'
password = secrets.token_urlsafe(32) + 'A1'
env = os.environ.copy()
env.update(DATABASE_URL=direct, DATABASE_URL_UNPOOLED=direct, SPRING_PROFILES_ACTIVE='prod,neon',
    BACKEND_PORT='8085', SERVER_ADDRESS='127.0.0.1', AUTH_COOKIE_SECURE='true', APP_ORIGIN=origin,
    APP_RECOVERY_REQUIRED='false', APP_ACCESS_RESTRICTED='true', APP_ALLOWED_EMAILS=email,
    APP_SIGNUP_ENABLED='true',
    JWT_SECRET=base64.b64encode(secrets.token_bytes(32)).decode(),
    CURSOR_SECRET=base64.b64encode(secrets.token_bytes(32)).decode(),
    RECOVERY_AUTHORITY_URL='https://money-map-recovery-test.mjlee95613.workers.dev/decision',
    RECOVERY_AUTHORITY_TOKEN=credential['DECISION_TOKEN'], RECOVERY_AUTHORITY_TIMEOUT_MS='5000',
    RECOVERY_AUTHORITY_PUBLIC_KEY=(folder.parent / 'authority-public.der.b64').read_text().strip(),
    RECOVERY_ENVIRONMENT=config['RECOVERY_ENVIRONMENT'], RECOVERY_DEPLOYMENT=config['RECOVERY_DEPLOYMENT'])
cookies = {}

def request(path, method='GET', body=None):
    headers = {'Origin': origin, 'Cookie': '; '.join(k + '=' + v for k, v in cookies.items())}
    if body is not None:
        headers['Content-Type'] = 'application/json'
        headers['X-XSRF-TOKEN'] = cookies.get('XSRF-TOKEN', '')
    req = urllib.request.Request('http://127.0.0.1:8085' + path, method=method, headers=headers,
        data=None if body is None else json.dumps(body).encode())
    try:
        response = urllib.request.urlopen(req, timeout=25)
    except urllib.error.HTTPError as error:
        response = error
    for raw in response.headers.get_all('Set-Cookie', []):
        parsed = http.cookies.SimpleCookie(raw)
        for key, value in parsed.items():
            if value.value: cookies[key] = value.value
            else: cookies.pop(key, None)
    raw = response.read()
    return response.status, json.loads(raw) if raw else None

log = folder / 'production-profile-app.log'
log.touch(mode=0o600)
with log.open('w') as output:
    process = subprocess.Popen(['java', '-Xmx256m', '-jar', str(root / 'backend/build/libs/money-map-0.1.0.jar')],
        env=env, cwd=root, stdout=output, stderr=output)
    created = False
    try:
        for _ in range(90):
            if process.poll() is not None: raise RuntimeError('Test server stopped')
            try:
                status, _ = request('/actuator/health/liveness')
                if status == 200: break
            except Exception: pass
            time.sleep(1)
        assert request('/actuator/health/readiness')[0] == 200
        assert request('/api/v1/auth/csrf')[0] == 200
        print('PASS production profile accepts real Cloudflare signed decision (no local bypass)', flush=True)
        signup = {'email': 'unlisted@example.invalid', 'password': password, 'passwordConfirmation': password,
            'termsAccepted': True, 'termsVersion': 'v1'}
        status, body = request('/api/v1/auth/signup', 'POST', signup)
        assert status == 403 and body['error']['code'] == 'REGISTRATION_CLOSED'
        print('PASS unlisted signup rejected through real production filter chain', flush=True)
        signup['email'] = email
        assert request('/api/v1/auth/signup', 'POST', signup)[0] == 201
        created = True
        assert request('/api/v1/auth/login', 'POST', {'email': email, 'password': password})[0] == 200
        assert request('/api/v1/users/me')[0] == 200
        assert request('/api/v1/auth/csrf')[0] == 200
        assert request('/api/v1/auth/refresh', 'POST', {})[0] == 200
        assert request('/api/v1/users/me')[0] == 200
        print('PASS allowed synthetic signup/login/access/refresh with live external verification', flush=True)
    finally:
        if created:
            try:
                request('/api/v1/auth/csrf')
                assert request('/api/v1/users/me', 'DELETE', {'currentPassword': password, 'confirmation': 'DELETE'})[0] == 204
                assert request('/api/v1/users/me')[0] == 401
                print('PASS synthetic account removed and previous session denied', flush=True)
            except Exception:
                print('FAIL synthetic cleanup requires inspection; no database reset performed', flush=True)
        process.terminate()
        try: process.wait(timeout=15)
        except subprocess.TimeoutExpired:
            process.kill()
            process.wait()
