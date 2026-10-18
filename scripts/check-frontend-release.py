#!/usr/bin/env python3
"""Build/test a separate production copy, preserving running previews and .next."""
import os
import argparse
from pathlib import Path
import shutil
import subprocess
import time

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--evidence-prefix", choices=["review-production", "autonomous-production", "acceptance-production"], default="review-production")
args = parser.parse_args()
root = Path(__file__).resolve().parents[1]
work = root / '.tooling' / ('release-check-' + time.strftime('%Y%m%d-%H%M%S'))
front = work / 'frontend'
shutil.copytree(root / 'frontend', front, ignore=shutil.ignore_patterns('node_modules', '.next', 'test-results', 'playwright-report', 'coverage', '*.tsbuildinfo', '.env', '.env.*'))
(front / 'node_modules').symlink_to(root / 'frontend/node_modules', target_is_directory=True)
(work / 'evidence').mkdir()
env = os.environ.copy()
env['PATH'] = str(root / '.tooling/node/node_modules/node/bin') + os.pathsep + env['PATH']
env['NEXT_TELEMETRY_DISABLED'] = '1'
env['PLAYWRIGHT_BROWSERS_PATH'] = str(root / '.tooling/playwright')
# A synthetic key suffices for mocked UI checks; never read the user's .env.
env['NEXT_PUBLIC_KAKAO_MAP_APP_KEY'] = 'synthetic-build-only'
config = '''import { defineConfig, devices } from "@playwright/test";
export default defineConfig({ testDir:"./e2e", workers:1, reporter:[["list"]],
 use:{baseURL:"http://127.0.0.1:3002", ...devices["Pixel 7"], trace:"off", screenshot:"off", video:"off"},
 webServer:{command:"node .next/standalone/server.js",env:{PORT:"3002",HOSTNAME:"127.0.0.1"},url:"http://127.0.0.1:3002",reuseExistingServer:false,timeout:60000}
});'''
(front / 'playwright.review.config.ts').write_text(config)
for label, command in [('build', ['npm','run','build']), ('e2e', ['node','node_modules/@playwright/test/cli.js','test','--config=playwright.review.config.ts'])]:
    with (root / 'evidence' / (args.evidence_prefix + '-' + label + '.log')).open('w') as log:
        result = subprocess.run(command, cwd=front, env=env, stdout=log, stderr=log)
    if result.returncode:
        raise SystemExit(label + ' failed; inspect evidence log')
    if label == 'build':
        shutil.copytree(front / '.next/static', front / '.next/standalone/.next/static')
        if (front / 'public').exists():
            shutil.copytree(front / 'public', front / '.next/standalone/public')
    print(label + ': PASS', flush=True)
print('Production copy retained under .tooling; existing preview unchanged.')
