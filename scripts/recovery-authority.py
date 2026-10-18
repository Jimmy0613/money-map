#!/usr/bin/env python3
"""Independent, fail-closed recovery authority. No database restore or ingress mutation.

Only an independently trusted verifier can sign transition receipts. This service
cannot infer WAL freshness from a hash or a user-provided boolean. Keep its SQLite
store and both signing keys outside application DB snapshots. See recovery-design.md.
"""
import argparse
import base64
import hashlib
import json
import os
from pathlib import Path
import re
import sqlite3
import subprocess
import tempfile
import time
from http.server import BaseHTTPRequestHandler, HTTPServer

STATES = ['BLOCKED', 'FENCED', 'LEDGER_VERIFIED', 'RESTORED', 'RECONCILED', 'VALIDATED', 'RELEASED']
BINDINGS = ['environment', 'deployment', 'generation', 'target', 'sourceSystem',
            'timelineHistoryDigest', 'targetLsn', 'ledgerDigest', 'runId']


def packed(value):
    return json.dumps(value, sort_keys=True, separators=(',', ':')).encode()


def crypto(args):
    result = subprocess.run(['openssl', *args], stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=3)
    if result.returncode:
        raise ValueError('Signature operation rejected')
    return result.stdout


def envelope(payload, key):
    raw = packed(payload)
    with tempfile.TemporaryDirectory() as folder:
        path = Path(folder) / 'payload'
        path.write_bytes(raw)
        path.chmod(0o600)
        signature = crypto(['pkeyutl', '-sign', '-rawin', '-inkey', str(key), '-in', str(path)])
    return {'payload': base64.b64encode(raw).decode(), 'signature': base64.b64encode(signature).decode()}


def verify(document, key):
    if set(document) != {'payload', 'signature'}:
        raise ValueError('Invalid envelope')
    raw = base64.b64decode(document['payload'], validate=True)
    signature = base64.b64decode(document['signature'], validate=True)
    if len(raw) > 8192 or len(signature) != 64:
        raise ValueError('Invalid envelope size')
    with tempfile.TemporaryDirectory() as folder:
        data, sig = Path(folder) / 'payload', Path(folder) / 'signature'
        data.write_bytes(raw)
        sig.write_bytes(signature)
        crypto(['pkeyutl', '-verify', '-pubin', '-rawin', '-inkey', str(key),
                '-in', str(data), '-sigfile', str(sig)])
    return json.loads(raw)


class Authority:
    def __init__(self, path):
        self.path = Path(path)
        # Missing authority state must never be silently recreated by the server.
        self.db = sqlite3.connect('file:' + str(self.path.resolve()) + '?mode=rw', uri=True)
        self.db.row_factory = sqlite3.Row
        self.db.execute('PRAGMA busy_timeout=1000')
        self.db.execute('PRAGMA synchronous=FULL')

    @staticmethod
    def initialize(path, verifier_key):
        trusted_key = crypto(['pkey', '-pubin', '-in', str(verifier_key), '-outform', 'DER'])
        fingerprint = hashlib.sha256(trusted_key).hexdigest()
        path = Path(path)
        fd = os.open(path, os.O_CREAT | os.O_EXCL | os.O_WRONLY, 0o600)
        os.close(fd)
        with sqlite3.connect(path) as db:
            db.executescript('''
                CREATE TABLE trust(verifier_key_sha256 TEXT NOT NULL);
                CREATE TABLE current(environment TEXT PRIMARY KEY, deployment TEXT NOT NULL,
                  generation INTEGER NOT NULL, state TEXT NOT NULL, target TEXT NOT NULL,
                  owner TEXT NOT NULL, lease_until INTEGER NOT NULL, receipt TEXT);
                CREATE TABLE audit(sequence INTEGER PRIMARY KEY AUTOINCREMENT,
                  environment TEXT NOT NULL, generation INTEGER NOT NULL,
                  state TEXT NOT NULL, at INTEGER NOT NULL, receipt_hash TEXT NOT NULL);
            ''')
            db.execute('INSERT INTO trust VALUES (?)', (fingerprint,))

    def begin(self, environment, deployment, target, expected, owner, now=None):
        now = int(time.time()) if now is None else now
        if not all(re.fullmatch(r'[A-Za-z0-9_.-]{1,100}', v) for v in [environment, deployment, owner]):
            raise ValueError('Invalid identifier')
        if not re.fullmatch(r'[a-f0-9]{64}', target):
            raise ValueError('Invalid target fingerprint')
        self.db.execute('BEGIN IMMEDIATE')
        try:
            previous = self.db.execute('SELECT * FROM current WHERE environment=?', (environment,)).fetchone()
            if (previous['generation'] if previous else 0) != expected:
                raise ValueError('Generation conflict')
            # A second worker cannot replace an active worker's generation.
            if previous and previous['lease_until'] > now and previous['owner'] != owner:
                raise ValueError('Worker lease conflict')
            generation = expected + 1
            self.db.execute('INSERT OR REPLACE INTO current VALUES (?,?,?,?,?,?,?,?)',
                            (environment, deployment, generation, 'BLOCKED', target, owner, now + 60, None))
            self.db.execute('INSERT INTO audit(environment,generation,state,at,receipt_hash) VALUES (?,?,?,?,?)',
                            (environment, generation, 'BLOCKED', now, ''))
            self.db.commit()
            return generation
        except Exception:
            self.db.rollback()
            raise

    def advance(self, document, verifier_key, owner, now=None):
        supplied = crypto(['pkey', '-pubin', '-in', str(verifier_key), '-outform', 'DER'])
        pinned = self.db.execute('SELECT verifier_key_sha256 FROM trust').fetchone()[0]
        if hashlib.sha256(supplied).hexdigest() != pinned:
            raise ValueError('Untrusted verifier')
        receipt = verify(document, verifier_key)
        now = int(time.time()) if now is None else now
        if (receipt.get('version') != 1 or not isinstance(receipt.get('issuedAt'), int)
                or not isinstance(receipt.get('expiresAt'), int)
                or not now - 5 <= receipt['issuedAt'] <= now
                or not now < receipt['expiresAt'] <= receipt['issuedAt'] + 60):
            raise ValueError('Stale verifier receipt')
        self.db.execute('BEGIN IMMEDIATE')
        try:
            row = self.db.execute('SELECT * FROM current WHERE environment=?', (receipt.get('environment'),)).fetchone()
            if not row or row['owner'] != owner or row['lease_until'] <= now:
                raise ValueError('Missing worker lease')
            if any(receipt.get(k) != row[k] for k in ['environment', 'deployment', 'generation', 'target']):
                raise ValueError('Target or generation mismatch')
            previous = json.loads(row['receipt']) if row['receipt'] else None
            expected = STATES[min(STATES.index(row['state']) + 1, len(STATES) - 1)]
            if receipt.get('state') != expected:
                raise ValueError('Out-of-order transition')
            if previous and any(previous.get(k) != receipt.get(k) for k in BINDINGS):
                raise ValueError('Verification binding changed')
            if not all(isinstance(receipt.get(k), str) and receipt[k] for k in BINDINGS if k != 'generation'):
                raise ValueError('Missing binding')
            for k in ['target', 'timelineHistoryDigest', 'ledgerDigest']:
                if not re.fullmatch('[a-f0-9]{64}', receipt[k]):
                    raise ValueError('Invalid digest')
            if not re.fullmatch(r'[0-9A-F]+/[0-9A-F]+', receipt['targetLsn']):
                raise ValueError('Invalid WAL boundary')
            # Results must come from the separate verifier, not an unsigned CLI assertion.
            if receipt.get('fenced') is not True:
                raise ValueError('Fence unverified')
            step = STATES.index(receipt['state'])
            if step >= 2 and (receipt.get('walContinuous') is not True
                              or receipt.get('durableCommitBoundary') is not True):
                raise ValueError('WAL durability or continuity unverified')
            if step >= 3 and receipt.get('publicOnly') is not True:
                raise ValueError('Restore isolation unverified')
            if step >= 4 and (any(type(receipt.get(k)) is not int or receipt[k] != 0 for k in
                                  ['deletedUsersRemaining', 'sessionsRemaining', 'orphansRemaining'])):
                raise ValueError('Reconciliation failed')
            if step >= 5 and (receipt.get('schemaVerified') is not True
                              or receipt.get('oldCredentialsRejected') is not True):
                raise ValueError('Validation incomplete')
            if step >= 6 and receipt.get('ingressBound') is not True:
                raise ValueError('Ingress target binding unverified')
            self.db.execute('UPDATE current SET state=?,receipt=?,lease_until=? WHERE environment=?',
                            (receipt['state'], packed(receipt).decode(), now + 60, receipt['environment']))
            self.db.execute('INSERT INTO audit(environment,generation,state,at,receipt_hash) VALUES (?,?,?,?,?)',
                            (receipt['environment'], receipt['generation'], receipt['state'], now,
                             hashlib.sha256(packed(receipt)).hexdigest()))
            self.db.commit()
        except Exception:
            self.db.rollback()
            raise

    def decision(self, request, now=None):
        now = int(time.time()) if now is None else now
        if set(request) != {'environment', 'deployment', 'target', 'nonce'}:
            raise ValueError('Invalid challenge')
        if not all(isinstance(v, str) and len(v) <= 200 for v in request.values()):
            raise ValueError('Invalid challenge fields')
        if not re.fullmatch(r'[a-f0-9-]{36}', request['nonce']):
            raise ValueError('Invalid nonce')
        row = self.db.execute('SELECT * FROM current WHERE environment=?', (request['environment'],)).fetchone()
        if not row or row['state'] != 'RELEASED' or any(row[k] != request[k] for k in ['deployment', 'target']):
            raise ValueError('Deployment blocked')
        receipt = json.loads(row['receipt'])
        if receipt['expiresAt'] <= now or row['lease_until'] <= now:
            raise ValueError('Verification expired')
        return dict(request, version=1, state='RELEASED', generation=row['generation'],
                    issuedAt=now, expiresAt=min(now + 5, receipt['expiresAt']))


def make_server(path, signing_key, port):
    # Signing authority only listens on loopback; remote use requires a separately secured proxy.
    class Handler(BaseHTTPRequestHandler):
        def log_message(self, *args):
            pass

        def do_POST(self):
            authority = None
            try:
                if self.path != '/decision' or self.headers.get('Transfer-Encoding'):
                    raise ValueError('Invalid request')
                length = int(self.headers.get('Content-Length', '0'))
                if not 0 < length <= 2048:
                    raise ValueError('Invalid request size')
                self.connection.settimeout(1)
                request = json.loads(self.rfile.read(length))
                authority = Authority(path)
                answer = envelope(authority.decision(request), signing_key)
                body, status = packed(answer), 200
            except Exception:
                body, status = b'{"code":"RECOVERY_REQUIRED"}', 503
            finally:
                if authority:
                    authority.db.close()
            self.send_response(status)
            self.send_header('Content-Type', 'application/json')
            self.send_header('Cache-Control', 'no-store')
            self.send_header('Content-Length', str(len(body)))
            self.end_headers()
            self.wfile.write(body)

    return HTTPServer(('127.0.0.1', port), Handler)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--store', required=True)
    actions = parser.add_subparsers(dest='action', required=True)
    initialize = actions.add_parser('init')
    initialize.add_argument('--verifier-public-key', required=True)
    begin = actions.add_parser('begin')
    for name in ['environment', 'deployment', 'target', 'owner']:
        begin.add_argument('--' + name, required=True)
    begin.add_argument('--expected-generation', type=int, required=True)
    advance = actions.add_parser('advance')
    for name in ['receipt', 'verifier-public-key', 'owner']:
        advance.add_argument('--' + name, required=True)
    server = actions.add_parser('serve')
    server.add_argument('--signing-key', required=True)
    server.add_argument('--port', type=int, default=8091)
    args = parser.parse_args()
    try:
        if args.action == 'init':
            Authority.initialize(args.store, args.verifier_public_key)
        elif args.action == 'serve':
            make_server(args.store, args.signing_key, args.port).serve_forever()
        else:
            authority = Authority(args.store)
            try:
                if args.action == 'begin':
                    authority.begin(args.environment, args.deployment, args.target,
                                    args.expected_generation, args.owner)
                else:
                    path = Path(args.receipt)
                    if path.stat().st_size > 16000:
                        raise ValueError('Receipt too large')
                    authority.advance(json.loads(path.read_text()), args.verifier_public_key, args.owner)
            finally:
                authority.db.close()
        print('Authority operation completed')
    except Exception:
        parser.exit(1, 'Authority operation rejected; sensitive details suppressed\n')


if __name__ == '__main__':
    main()
