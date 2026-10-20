"""Compare two jars only against a disposable loopback PostgreSQL fixture.

The database must already exist. Never use a production URL or credentials.
Warm each jar once, then alternate three measured launches per jar. This measures
application startup with a warm local DB, not Render/Neon cold-start latency.
"""
import argparse
import base64
import json
import os
from pathlib import Path
import re
import secrets
import socket
import statistics
import subprocess
import time
import urllib.error
import urllib.request
import zipfile

p = argparse.ArgumentParser()
p.add_argument('--before', required=True, type=Path)
p.add_argument('--after', required=True, type=Path)
p.add_argument('--java', required=True, type=Path)
p.add_argument('--jdbc', required=True)
p.add_argument('--output', type=Path, default=Path('.tooling/startup-review'))
p.add_argument('--port', type=int, default=8088)
args = p.parse_args()
if not re.fullmatch(r'jdbc:postgresql://127\.0\.0\.1:[0-9]+/fixture_[a-zA-Z0-9_]+', args.jdbc):
    p.error('Only an existing disposable loopback fixture database is allowed')
args.output = args.output.resolve()
args.output.mkdir(parents=True, exist_ok=True)
jars = {'before': args.before.resolve(), 'after': args.after.resolve()}
java = args.java.resolve()
flags = ['-Xmx192m', '-XX:MaxMetaspaceSize=128m', '-XX:ReservedCodeCacheSize=48m',
         '-XX:MaxDirectMemorySize=32m', '-Xss512k']
env = os.environ.copy()
# Do not inherit personal registration, production overrides, or VM options.
for key in list(env):
    if key.startswith(('APP_', 'SPRING_', 'RECOVERY_', 'DATABASE_URL', 'AUTH_COOKIE',
                       'JWT_SECRET', 'CURSOR_SECRET')) or key in ('JAVA_TOOL_OPTIONS', 'JDK_JAVA_OPTIONS', '_JAVA_OPTIONS'):
        env.pop(key, None)
env.update(SPRING_PROFILES_ACTIVE='local', SPRING_DATASOURCE_URL=args.jdbc,
           SPRING_DATASOURCE_USERNAME='demo_test', SPRING_DATASOURCE_PASSWORD='',
           APP_ORIGIN='http://localhost:3000', AUTH_COOKIE_SECURE='false',
           APP_SIGNUP_ENABLED='false', BACKEND_PORT=str(args.port),
           JWT_SECRET=base64.b64encode(secrets.token_bytes(32)).decode(),
           CURSOR_SECRET=base64.b64encode(secrets.token_bytes(32)).decode())
opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))

def run(label, iteration):
    with socket.socket() as check:
        if check.connect_ex(('127.0.0.1', args.port)) == 0:
            raise RuntimeError('Benchmark port is already in use')
    name = f'{label}-{iteration}'
    log = args.output / f'{name}.log'
    gc = args.output / f'{name}-gc.log'
    with log.open('w', encoding='utf-8') as out:
        start = time.perf_counter()
        process = subprocess.Popen([str(java), *flags, f'-Xlog:gc:file={gc.name}',
                                    '-jar', str(jars[label])], env=env,
                                   cwd=args.output, stdout=out, stderr=subprocess.STDOUT,
                                   creationflags=getattr(subprocess, 'CREATE_NO_WINDOW', 0))
        try:
            while time.perf_counter() - start < 120:
                if process.poll() is not None:
                    raise RuntimeError(f'{name} exited; inspect its private log')
                try:
                    with opener.open(f'http://127.0.0.1:{args.port}/actuator/health/readiness', timeout=.3) as response:
                        if response.status == 200 and json.load(response).get('status') == 'UP':
                            ready = time.perf_counter() - start
                            break
                except (OSError, urllib.error.URLError):
                    pass
                time.sleep(.1)
            else:
                raise TimeoutError(f'{name} did not become ready')
            content = log.read_text(encoding='utf-8')
            match = re.search(r'Started MoneyMapApplication in ([0-9.]+) seconds', content)
            if not match:
                raise RuntimeError('Startup marker missing')
            gc_text = gc.read_text(encoding='utf-8')
            pauses = [float(value) for value in re.findall(r'Pause[^\n]* ([0-9.]+)ms', gc_text)]
            result = dict(label=label, iteration=iteration, ready_seconds=round(ready, 3),
                          spring_seconds=float(match[1]), gc_pauses=len(pauses),
                          gc_pause_ms=round(sum(pauses), 3),
                          jpa_initialized='Initialized JPA EntityManagerFactory' in content)
            print(json.dumps(result), flush=True)
            return result
        finally:
            process.terminate()
            process.wait(timeout=20)

run('before', 'warmup')
run('after', 'warmup')
results = []
for label in ['before', 'after', 'after', 'before', 'before', 'after']:
    results.append(run(label, 1 + sum(r['label'] == label for r in results)))
summary = {'scope': 'Windows loopback warm PostgreSQL; not hosting cold start',
           'jvm_flags': flags, 'results': results, 'jars': {}, 'median': {}}
for label, jar in jars.items():
    with zipfile.ZipFile(jar) as archive:
        libraries = [n for n in archive.namelist() if n.startswith('BOOT-INF/lib/') and n.endswith('.jar')]
    summary['jars'][label] = dict(bytes=jar.stat().st_size, libraries=len(libraries))
    summary['median'][label] = {key: statistics.median(r[key] for r in results if r['label'] == label)
                                for key in ['ready_seconds', 'spring_seconds', 'gc_pause_ms']}
(args.output / 'summary.json').write_text(json.dumps(summary, indent=2), encoding='utf-8')
print(json.dumps(summary['median']), flush=True)
