#!/usr/bin/env python3
"""Synthetic-only restore rehearsal; never connects to existing databases."""
import hashlib
import importlib.util
import json
from pathlib import Path
import tempfile
import uuid

spec = importlib.util.spec_from_file_location('restore_stage', Path(__file__).with_name('restore-public.py'))
r = importlib.util.module_from_spec(spec)
spec.loader.exec_module(r)
source = target = None
try:
    with tempfile.TemporaryDirectory(prefix='money-map-restore-test-') as directory:
        work = Path(directory)
        archive, ledger = work / 'public.dump', work / 'ledger.json'
        ledger.write_text('[]')
        try:
            r.stage(archive, ledger, '0' * 64)
            raise AssertionError('Unverified ledger accepted')
        except RuntimeError:
            pass
        source = r.create_target()
        migrations = sorted((Path(__file__).resolve().parents[1] / 'backend/src/main/resources/db/migration').glob('V*.sql'))
        for migration in migrations:
            r.sql(source, migration.read_text())
        deleted, kept, category, transaction, session = [str(uuid.uuid4()) for _ in range(5)]
        r.sql(source, f"""
INSERT INTO users(id,email,password_hash,terms_version) VALUES
('{deleted}','deleted@example.invalid','synthetic-only','v1'),('{kept}','kept@example.invalid','synthetic-only','v1');
INSERT INTO categories(id,user_id,type,name,normalized_name) VALUES ('{category}','{deleted}','EXPENSE','synthetic','synthetic');
INSERT INTO transactions(id,user_id,type,amount,category_id,transaction_date) VALUES ('{transaction}','{deleted}','EXPENSE',1000,'{category}','2026-09-01');
INSERT INTO transaction_locations(transaction_id,user_id,source,latitude,longitude) VALUES ('{transaction}','{deleted}','MAP',0,0);
INSERT INTO auth_sessions(id,user_id,expires_at) VALUES ('{session}','{kept}',now()+interval '1 day');
INSERT INTO refresh_tokens(id,session_id,token_hash) VALUES (gen_random_uuid(),'{session}',repeat('a',64));
""")
        archive.write_bytes(r.run(['exec', source, 'pg_dump', '-U', 'postgres', '-Fc', '--schema=public', 'money_map']))
        r.sql(source, f"BEGIN; INSERT INTO privacy.deletion_ledger(user_id) VALUES ('{deleted}'); DELETE FROM users WHERE id='{deleted}'; COMMIT;")
        ledger.write_bytes(r.sql(source, "SELECT coalesce(json_agg(json_build_object('user_id',user_id,'deleted_at',deleted_at)), '[]') FROM privacy.deletion_ledger;"))
        result = r.stage(archive, ledger, hashlib.sha256(ledger.read_bytes()).hexdigest())
        target = result['container']
        assert result['releaseAllowed'] is False
        assert r.sql(target, 'SELECT count(*) FROM users;').strip() == b'1'
        assert r.sql(target, f"SELECT count(*) FROM users WHERE id='{deleted}';").strip() == b'0'
        for table in ['categories','transactions','transaction_locations','auth_sessions','refresh_tokens']:
            assert r.sql(target, f'SELECT count(*) FROM {table};').strip() == b'0'
        assert r.sql(target, 'SELECT count(*) FROM privacy.deletion_ledger;').strip() == b'1'
        # A valid digest on an old (empty) ledger still must never authorize release.
        r.remove_target(target); target = None
        ledger.write_text('[]')
        stale = r.stage(archive, ledger, hashlib.sha256(ledger.read_bytes()).hexdigest())
        target = stale['container']
        assert stale['releaseAllowed'] is False
        assert r.sql(target, 'SELECT count(*) FROM users;').strip() == b'2'
        print(json.dumps({'result':'PASS','checks':['digest mismatch blocked','public-only restore','latest synthetic ledger replay','deleted user and related rows absent','survivor preserved','sessions and refresh tokens removed','stale ledger release denied','no published ports'], 'existingDatabasesModified':False}))
finally:
    for name in [target, source]:
        if name:
            r.remove_target(name)
