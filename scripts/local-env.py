#!/usr/bin/env python3
"""Create a NEW local-only secret file; never overwrites or prints secrets."""
from pathlib import Path
import os
import secrets
import base64
root = Path(__file__).resolve().parents[1]
target = root / '.env'
password = secrets.token_urlsafe(32)
values = {
    'APP_ORIGIN': 'http://localhost:3000', 'FRONTEND_PORT': '3000',
    'BACKEND_PORT': '8080', 'DB_PORT': '5432', 'POSTGRES_DB': 'money_map',
    'POSTGRES_USER': 'money_map', 'POSTGRES_PASSWORD': password,
    'SPRING_DATASOURCE_URL': 'jdbc:postgresql://localhost:5432/money_map',
    'SPRING_DATASOURCE_USERNAME': 'money_map', 'SPRING_DATASOURCE_PASSWORD': password,
    'JWT_SECRET': base64.b64encode(secrets.token_bytes(32)).decode(),
    'CURSOR_SECRET': base64.b64encode(secrets.token_bytes(32)).decode(),
    'AUTH_COOKIE_SECURE': 'false', 'SPRING_PROFILES_ACTIVE': 'local',
    'BACKEND_INTERNAL_URL': 'http://localhost:8080', 'NEXT_PUBLIC_KAKAO_MAP_APP_KEY': ''
}
try:
    fd = os.open(target, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
except FileExistsError:
    raise SystemExit('Existing .env preserved; no changes made.')
with os.fdopen(fd, 'w', encoding='utf-8') as stream:
    stream.write('\n'.join(f'{k}={v}' for k, v in values.items())+'\n')
print('Created local .env with mode 0600. Values are not printed.')
