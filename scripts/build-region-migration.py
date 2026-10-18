#!/usr/bin/env python3
"""Deterministic official snapshot import. Never modifies existing migrations."""
from pathlib import Path
from zipfile import ZipFile
import csv, io, hashlib, json, uuid
root=Path(__file__).resolve().parents[1]
source=root/'evidence/official-region-download.bin'
version='code.go.kr-2026-09-18'
with ZipFile(source) as z:
    rows=list(csv.DictReader(io.StringIO(z.read(z.namelist()[0]).decode('cp949')),delimiter='\t'))
regions=[r for r in rows if r['법정동코드'].endswith('00000') and r['법정동코드'][2:5]!='000']
assert len({r['법정동코드'] for r in regions})==len(regions)
quote=lambda s:"'"+s.replace("'","''")+"'"
lines=['-- Official code.go.kr snapshot fetched 2026-09-18; see region-data.md.', '-- Historical alias dates are intentionally not inferred from the snapshot.']
for r in regions:
    code=r['법정동코드'][:5]
    names=r['법정동명'].split(' ',1)
    sido=names[0];sigungu=names[1] if len(names)>1 else ''
    lines.append('INSERT INTO region_dictionary(region_key,region_code,sido_name,sigungu_name,source_version) VALUES ('+','.join(map(quote,['CODE:'+code,code,sido,sigungu,version]))+');')
    if r['폐지여부']=='존재':
        alias=str(uuid.uuid5(uuid.NAMESPACE_URL,version+':'+code))
        lines.append('INSERT INTO region_aliases(id,region_key,sido_normalized,sigungu_normalized,valid_from) VALUES ('+','.join(map(quote,[alias,'CODE:'+code,sido.lower(),sigungu.lower(),'2026-09-18']))+');')
output=root/'backend/src/main/resources/db/migration/V5__official_region_snapshot.sql'
with output.open('x',encoding='utf-8') as f:f.write('\n'.join(lines)+'\n')
metadata={'source':'https://www.code.go.kr/stdcode/regCodeL.do','download':'https://www.code.go.kr/etc/codeFullDown.do','retrieved':'2026-09-18','version':version,'sha256':hashlib.sha256(source.read_bytes()).hexdigest(),'rows':len(rows),'region_rows':len(regions),'current_region_rows':sum(r['폐지여부']=='존재' for r in regions)}
(root/'evidence/region-source-metadata.json').write_text(json.dumps(metadata,ensure_ascii=False,indent=2)+'\n')
print('Verified official snapshot:',metadata['rows'],'rows;',len(regions),'regional dictionary records')
