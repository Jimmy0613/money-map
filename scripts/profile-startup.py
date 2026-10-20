"""Offline Spring startup/JFR probe against an existing disposable loopback DB.

Extracts a built bootJar and launches its unchanged application configuration using
scripts/diagnostics/StartupProbe.java. No HTTP diagnostics endpoint is enabled.
Raw JFR/logs are private: JFR can include system properties and environment values.
This uses an exploded classpath, so timings are not comparable to java -jar results.
"""
import argparse
import base64
import hashlib
import json
import os
from pathlib import Path
import re
import secrets
import statistics
import subprocess
import zipfile

ROOT = Path(__file__).resolve().parents[1]
p = argparse.ArgumentParser(description=__doc__)
p.add_argument('--jar', required=True, type=Path)
p.add_argument('--jdk', required=True, type=Path)
p.add_argument('--jdbc', required=True)
p.add_argument('--output', type=Path, default=ROOT / '.tooling/startup-profile')
p.add_argument('--runs', type=int, default=3)
args = p.parse_args()
args.jar = args.jar.resolve()
args.jdk = args.jdk.resolve()
if not re.fullmatch(r'jdbc:postgresql://127\.0\.0\.1:[0-9]+/fixture_[a-zA-Z0-9_]+', args.jdbc):
    p.error('Only an existing disposable loopback fixture database is allowed')
if not 1 <= args.runs <= 5:
    p.error('Use 1 to 5 measured runs')
output = args.output.resolve()
private_root = (ROOT / '.tooling').resolve()
if private_root not in output.parents:
    p.error('Raw diagnostics must remain under the ignored .tooling directory')
output.mkdir(parents=True, exist_ok=True)
exploded = output / 'exploded'
with zipfile.ZipFile(args.jar) as jar:
    for item in jar.infolist():
        if item.filename.startswith(('BOOT-INF/classes/', 'BOOT-INF/lib/')):
            target = (exploded / item.filename).resolve()
            if exploded not in target.parents:
                raise ValueError('Unsafe jar entry')
            jar.extract(item, exploded)
classes = output / 'probe-classes'
classes.mkdir(exist_ok=True)
classpath = os.pathsep.join([str(classes), str(exploded / 'BOOT-INF/classes'),
                            str(exploded / 'BOOT-INF/lib/*')])
suffix = '.exe' if os.name == 'nt' else ''
subprocess.run([str(args.jdk / f'bin/javac{suffix}'), '-cp', classpath, '-d', str(classes),
                str(ROOT / 'scripts/diagnostics/StartupProbe.java')], check=True)
env = os.environ.copy()
for key in list(env):
    if key.startswith(('APP_', 'SPRING_', 'RECOVERY_', 'DATABASE_URL', 'AUTH_COOKIE',
                       'JWT_SECRET', 'CURSOR_SECRET')) or key in ('JAVA_TOOL_OPTIONS', 'JDK_JAVA_OPTIONS', '_JAVA_OPTIONS'):
        env.pop(key, None)
env.update(SPRING_PROFILES_ACTIVE='local', SPRING_DATASOURCE_URL=args.jdbc,
           SPRING_DATASOURCE_USERNAME='demo_test', SPRING_DATASOURCE_PASSWORD='',
           SPRING_FLYWAY_URL=args.jdbc, SPRING_FLYWAY_USER='demo_test', SPRING_FLYWAY_PASSWORD='',
           SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE='3',
           SPRING_DATASOURCE_HIKARI_MINIMUM_IDLE='0',
           SPRING_DATASOURCE_HIKARI_IDLE_TIMEOUT='30000',
           SPRING_DATASOURCE_HIKARI_CONNECTION_TIMEOUT='15000',
           SPRING_DATASOURCE_HIKARI_KEEPALIVE_TIME='0',
           APP_ORIGIN='http://localhost:3000', AUTH_COOKIE_SECURE='false',
           APP_SIGNUP_ENABLED='false', BACKEND_PORT='0',
           JWT_SECRET=base64.b64encode(secrets.token_bytes(32)).decode(),
           CURSOR_SECRET=base64.b64encode(secrets.token_bytes(32)).decode())
flags = ['-Xmx192m', '-XX:MaxMetaspaceSize=128m', '-XX:ReservedCodeCacheSize=48m',
         '-XX:MaxDirectMemorySize=32m', '-Xss512k']
results = []
for n in range(args.runs + 1):
    folder = output / ('warmup' if n == 0 else f'run-{n}')
    folder.mkdir(exist_ok=True)
    with (folder / 'console.log').open('w', encoding='utf-8') as log:
        subprocess.run([str(args.jdk / f'bin/java{suffix}'), *flags, '-cp', classpath,
                        'StartupProbe', str(folder)], env=env, cwd=folder, stdout=log,
                       stderr=subprocess.STDOUT, timeout=120, check=True,
                       creationflags=getattr(subprocess, 'CREATE_NO_WINDOW', 0))
    result = json.loads((folder / 'timeline.json').read_text(encoding='utf-8'))
    print(json.dumps({'run': n, 'spring_run_seconds': result['spring_run_seconds'],
                      'events': len(result['events'])}), flush=True)
    if n:
        results.append(result)
summary = dict(scope='Windows, exploded classpath, warm loopback PostgreSQL, JFR enabled; not Render/Neon',
               jar_sha256=hashlib.sha256(args.jar.read_bytes()).hexdigest(), jvm_flags=flags,
               median_spring_run_seconds=statistics.median(r['spring_run_seconds'] for r in results),
               runs=results)
(output / 'summary.json').write_text(json.dumps(summary, indent=2), encoding='utf-8')
print('Private diagnostics saved under .tooling; sanitize before publishing.')
