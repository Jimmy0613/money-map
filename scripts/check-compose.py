#!/usr/bin/env python3
"""Validate compose WITHOUT rendering substituted secrets to stdout."""
import os
import subprocess
from pathlib import Path
root = Path(__file__).resolve().parents[1]
env=os.environ.copy()
for key in ('POSTGRES_PASSWORD','JWT_SECRET','CURSOR_SECRET'):
    env.setdefault(key,'validation-placeholder-not-for-running')
result=subprocess.run(['docker','compose','config','--quiet'],cwd=root,env=env,capture_output=True,text=True)
print('Compose validation: '+('PASS' if result.returncode==0 else 'FAIL (output suppressed to protect environment)'))
raise SystemExit(result.returncode)
