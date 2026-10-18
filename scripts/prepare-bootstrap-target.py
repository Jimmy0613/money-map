#!/usr/bin/env python3
"""Apply normal Flyway migrations behind the forced gate; provision a metadata-only login.

Credentials remain in the ignored .tooling/recovery-setup directory. Does not register,
release, reset, restore, delete rows, or stop any existing application/container.
"""
import argparse
import base64
import json
import os
from pathlib import Path
import secrets
import socket
import subprocess
import time
import urllib.request
from urllib.parse import quote, urlsplit, urlunsplit

root = Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--branch-id', required=True)
parser.add_argument('--endpoint-id', required=True)
parser.add_argument('--direct-url-file', required=True)
parser.add_argument('--label', required=True, choices=['test', 'production'])
args = parser.parse_args()
folder = root / '.tooling/recovery-setup' / args.label
folder.mkdir(parents=True, exist_ok=True, mode=0o700)
os.umask(0o077)

def private(name, text):
    p = folder / name
    p.write_text(text)
    p.chmod(0o600)
    return p

direct = Path(args.direct_url_file).read_text().strip()
url = urlsplit(direct)
if (url.scheme != 'postgresql' or not url.hostname or not url.username or not url.password
        or not url.hostname.startswith(args.endpoint_id + '.') or not url.hostname.endswith('.neon.tech')):
    raise SystemExit('Target URL mismatch; refused')
api_key = json.loads((root / '.tooling/recovery-setup/neon-api-key.json').read_text())['key']
def metadata(path):
    req = urllib.request.Request('https://console.neon.tech/api/v2/projects/wispy-leaf-78515154/' + path,
                                 headers={'Authorization': 'Bearer ' + api_key})
    with urllib.request.urlopen(req, timeout=15) as response:
        return json.load(response)
branch = metadata('branches/' + args.branch_id)['branch']
endpoint = metadata('endpoints/' + args.endpoint_id)['endpoint']
if (branch['id'] != args.branch_id or endpoint['branch_id'] != args.branch_id
        or endpoint['id'] != args.endpoint_id or endpoint['type'] != 'read_write'
        or branch.get('parent_id') or branch.get('last_reset_at') or branch.get('restored_from')
        or branch.get('restored_as') or branch.get('restore_status') or branch.get('recovery')
        or branch.get('init_source') not in ['parent-data', 'parent-schema', 'schema-only']):
    raise SystemExit('Provider target/provenance mismatch; refused')
with socket.socket() as probe:
    if probe.connect_ex(('127.0.0.1', 8084)) == 0:
        raise SystemExit('Bootstrap test port already occupied; refused')

env = os.environ.copy()
env.update(DATABASE_URL=direct, DATABASE_URL_UNPOOLED=direct,
           SPRING_PROFILES_ACTIVE='prod,neon', BACKEND_PORT='8084',
           SERVER_ADDRESS='127.0.0.1', APP_ORIGIN='https://money-map-frontend-murex.vercel.app',
           AUTH_COOKIE_SECURE='true', APP_RECOVERY_REQUIRED='true',
           APP_ACCESS_RESTRICTED='true', APP_ALLOWED_EMAILS='',
           JWT_SECRET=base64.b64encode(secrets.token_bytes(32)).decode(),
           CURSOR_SECRET=base64.b64encode(secrets.token_bytes(32)).decode())
jar = root / 'backend/build/libs/money-map-0.1.0.jar'
log = private('migration.log', '')
with log.open('w') as output:
    process = subprocess.Popen(['java', '-Xmx256m', '-jar', str(jar)], env=env,
                               stdout=output, stderr=output, cwd=root)
    try:
        ready = False
        for _ in range(300):
            if process.poll() is not None:
                break
            try:
                with urllib.request.urlopen('http://127.0.0.1:8084/actuator/health/liveness', timeout=1) as response:
                    ready = response.status == 200
            except Exception:
                pass
            if ready:
                break
            time.sleep(1)
        if not ready:
            raise SystemExit('Flyway/server startup failed; inspect private migration log')
    finally:
        process.terminate()
        try:
            process.wait(timeout=15)
        except subprocess.TimeoutExpired:
            process.kill()
            process.wait()

role = 'money_map_recovery_verifier'
password_file = folder / 'role-password.txt'
if password_file.exists():
    password = password_file.read_text().strip()
else:
    password = secrets.token_urlsafe(32)
    private('role-password.txt', password)
if not all(c.isalnum() or c in '-_' for c in password):
    raise SystemExit('Unexpected stored role credential; refused')
marker = folder / 'role-created.json'
if not marker.exists():
    sql = private('role.sql', f'''BEGIN;
CREATE ROLE {role} LOGIN NOINHERIT NOCREATEDB NOCREATEROLE NOREPLICATION PASSWORD '{password}';
GRANT CONNECT ON DATABASE "{url.path.lstrip('/')}" TO {role};
GRANT USAGE ON SCHEMA public TO {role};
GRANT EXECUTE ON FUNCTION public.recovery_observation(boolean) TO {role};
COMMIT;
''')
    # Database name is checked before interpolation; credentials never enter argv.
    if not url.path.lstrip('/').replace('_', '').isalnum():
        raise SystemExit('Unexpected database name; refused')
    with (folder / 'role-setup.log').open('w') as output:
        result = subprocess.run([str(root / 'scripts/neon'), 'psql', args.branch_id,
            '--project-id', 'wispy-leaf-78515154', '--database-name', url.path.lstrip('/'),
            '--', '-v', 'ON_ERROR_STOP=1', '-f', str(sql)],
            stdout=output, stderr=output, cwd=root)
    if result.returncode:
        raise SystemExit('Metadata login provisioning failed; existing roles were not overwritten')
    private('role-created.json', json.dumps({'branchId': args.branch_id, 'endpointId': args.endpoint_id}))
else:
    expected = {'branchId': args.branch_id, 'endpointId': args.endpoint_id}
    if json.loads(marker.read_text()) != expected:
        raise SystemExit('Stored role registration belongs to another target; refused')

verifier_url = urlunsplit((url.scheme,
    quote(role, safe='') + ':' + quote(password, safe='') + '@' + url.netloc.split('@', 1)[1],
    url.path, url.query, ''))
private('verifier.url', verifier_url)
print('PASS Flyway migrations with API forced closed')
print('PASS dedicated metadata-only verifier login prepared (no user data reset)')
