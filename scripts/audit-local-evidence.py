#!/usr/bin/env python3
"""Check known local secrets without printing values or offending content."""
from pathlib import Path
import json, re
root=Path(__file__).resolve().parents[1]
values=[]
p=root/'.env'
if p.exists():
    for line in p.read_text().splitlines():
        key, _, value=line.partition('=')
        if key in {'POSTGRES_PASSWORD','SPRING_DATASOURCE_PASSWORD','JWT_SECRET','CURSOR_SECRET'} and value:
            values.append(value.encode())
files=list((root/'evidence').glob('*.log'))
files+=list((root/'backend/build/test-results').rglob('TEST-*.xml'))
files+=list((root/'frontend/.next/static').rglob('*.js'))
violations=[]
for p in files:
    content=p.read_bytes()
    if any(v in content for v in values): violations.append(str(p.relative_to(root)))
logs='\n'.join(p.read_text(errors='replace') for p in (root/'evidence').glob('*local-*.log'))
lines=[line for line in logs.splitlines() if 'requestId=' in line]
fields_ok=bool(lines) and all(all(key in line for key in ['path=','status=','durationMs=','code=']) for line in lines)
synthetic_absent=not bool(re.search(r'e2e-[0-9a-f-]+@example\.invalid|mm_access=|mm_refresh=|Using generated security password:',logs))
result={'knownSecretValuesPrinted':False,'scannedFiles':len(files),'knownSecretMatchCount':len(violations),'filesWithMatch':violations,'requestLogCount':len(lines),'structuredFieldsPresent':fields_ok,'syntheticEmailAndAuthCookieAbsent':synthetic_absent,'scope':'Known local secret matching and local app logs only; no production proxy/unknown-secret guarantee.'}
(root/'evidence/log-audit.json').write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n')
print('Local evidence audit: '+('PASS' if not violations and fields_ok and synthetic_absent else 'FAIL'))
raise SystemExit(0 if not violations and fields_ok and synthetic_absent else 1)
