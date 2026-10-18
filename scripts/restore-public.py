#!/usr/bin/env python3
"""Stage a public-schema archive in a NEW, network-isolated PostgreSQL container.

Never overwrites an existing DB. A supplied ledger digest verifies integrity, NOT
freshness. The restored candidate is NEVER published or declared release-ready.
Authoritative latest-commit/WAL evidence must be checked separately before use.
"""
import argparse
import csv
import hashlib
import io
import json
import re
from pathlib import Path
import subprocess
import time
import uuid
from datetime import datetime

DOCKER = '/Applications/Docker.app/Contents/Resources/bin/docker'
LABEL = 'money-map.restore-stage'


def run(args, data=None):
    p = subprocess.run([DOCKER, *args], input=data, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    if p.returncode:
        # pg_restore/psql error text can contain rows and SQL; never print it.
        raise RuntimeError('Isolated restore command failed; private output suppressed')
    return p.stdout


def owned(name):
    state = json.loads(run(['inspect', name]))[0]
    return (state['Config']['Labels'].get(LABEL) == 'true'
            and state['HostConfig']['NetworkMode'] == 'none'
            and not state['HostConfig'].get('PortBindings'))


def sql(name, command):
    if not owned(name):
        raise RuntimeError('Refusing non-isolated target')
    return run(['exec', '-i', name, 'psql', '-X', '-qAt', '-v', 'ON_ERROR_STOP=1', '-U', 'postgres', 'money_map'], command.encode())


def create_target():
    name = 'money-map-restore-' + uuid.uuid4().hex
    run(['run', '-d', '--name', name, '--label', LABEL + '=true', '--network', 'none',
         '--memory', '512m', '--cpus', '2', '-e', 'POSTGRES_HOST_AUTH_METHOD=trust',
         '-e', 'POSTGRES_DB=money_map', 'postgres:17.6'])
    try:
        for _ in range(60):
            p = subprocess.run([DOCKER, 'exec', name, 'pg_isready', '-U', 'postgres'], capture_output=True)
            if p.returncode == 0:
                return name
            time.sleep(1)
        raise RuntimeError('Isolated database readiness timeout')
    except BaseException:
        remove_target(name)
        raise


def remove_target(name):
    if not name.startswith('money-map-restore-') or not owned(name):
        raise RuntimeError('Refusing to remove unowned target')
    run(['rm', '-f', '-v', name])


def stage(archive, ledger, expected_digest):
    raw = Path(ledger).read_bytes()
    if len(expected_digest) != 64 or hashlib.sha256(raw).hexdigest() != expected_digest:
        raise RuntimeError('Ledger integrity not verified; restoration blocked')
    rows = json.loads(raw)
    if not isinstance(rows, list):
        raise RuntimeError('Invalid ledger')
    seen = set()
    output = io.StringIO()
    writer = csv.writer(output, lineterminator="\n")
    for row in rows:
        if set(row) != {'user_id', 'deleted_at'}:
            raise RuntimeError('Ledger permits only user_id and deleted_at')
        user = str(uuid.UUID(row['user_id']))
        timestamp = row['deleted_at'].replace('Z', '+00:00')
        timestamp = re.sub(r'(\.\d{1,6})(?=[+-]\d{2}:\d{2}$)', lambda m: m[1].ljust(7, '0'), timestamp)
        when = datetime.fromisoformat(timestamp)
        if when.tzinfo is None or user in seen:
            raise RuntimeError('Invalid ledger time or duplicate user')
        seen.add(user)
        writer.writerow([user, when.isoformat()])
    name = create_target()
    try:
        # Only public is restored; a privacy schema in the archive is not restored.
        run(['exec', '-i', name, 'pg_restore', '-U', 'postgres', '-d', 'money_map',
             '--schema=public', '--no-owner', '--no-privileges', '--exit-on-error'], Path(archive).read_bytes())
        sql(name, 'CREATE SCHEMA privacy; CREATE TABLE privacy.deletion_ledger(user_id uuid PRIMARY KEY, deleted_at timestamptz NOT NULL);')
        sql(name, 'COPY privacy.deletion_ledger FROM STDIN WITH (FORMAT csv);\n' + output.getvalue() + '\\.\n')
        sql(name, '''BEGIN;
LOCK TABLE privacy.deletion_ledger IN ACCESS EXCLUSIVE MODE;
DELETE FROM users WHERE id IN (SELECT user_id FROM privacy.deletion_ledger);
DELETE FROM auth_sessions;
SET CONSTRAINTS ALL IMMEDIATE;
DO $$ BEGIN
IF EXISTS (SELECT 1 FROM users u JOIN privacy.deletion_ledger d ON u.id=d.user_id)
 OR EXISTS (SELECT 1 FROM categories c LEFT JOIN users u ON u.id=c.user_id WHERE u.id IS NULL)
 OR EXISTS (SELECT 1 FROM transactions t LEFT JOIN users u ON u.id=t.user_id WHERE u.id IS NULL)
 OR EXISTS (SELECT 1 FROM transactions t LEFT JOIN categories c ON (c.user_id,c.id,c.type)=(t.user_id,t.category_id,t.type) WHERE c.id IS NULL)
 OR EXISTS (SELECT 1 FROM places p LEFT JOIN users u ON u.id=p.user_id WHERE u.id IS NULL)
 OR EXISTS (SELECT 1 FROM transaction_locations l LEFT JOIN transactions t ON (t.user_id,t.id)=(l.user_id,l.transaction_id) WHERE t.id IS NULL)
 OR EXISTS (SELECT 1 FROM transaction_locations l LEFT JOIN places p ON (p.user_id,p.id)=(l.user_id,l.place_id) WHERE l.place_id IS NOT NULL AND p.id IS NULL)
 OR EXISTS (SELECT 1 FROM operation_keys k LEFT JOIN users u ON u.id=k.user_id WHERE u.id IS NULL)
 OR EXISTS (SELECT 1 FROM auth_sessions) OR EXISTS (SELECT 1 FROM refresh_tokens)
THEN RAISE EXCEPTION 'RESTORE_VALIDATION_FAILED'; END IF;
END $$;
COMMIT;''')
        return {'container': name, 'reconciled': True, 'releaseAllowed': False,
                'status': 'BLOCKED_PENDING_AUTHORITATIVE_LEDGER_FRESHNESS_AND_OPERATIONS_VERIFICATION'}
    except BaseException:
        remove_target(name)
        raise


if __name__ == '__main__':
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--archive', type=Path, required=True)
    p.add_argument('--ledger', type=Path, required=True)
    p.add_argument('--ledger-sha256', required=True)
    args = p.parse_args()
    try:
        print(json.dumps(stage(args.archive, args.ledger, args.ledger_sha256)))
    except Exception:
        raise SystemExit('Restore blocked. No existing database was modified; diagnostic values suppressed.')
