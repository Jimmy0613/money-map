#!/usr/bin/env python3
"""Synthetic keys/state only; never reads .env, application DBs or live authority state."""
import importlib.util
import json
from pathlib import Path
import tempfile
import time
import unittest
import uuid
import threading
import urllib.request
import urllib.error

spec = importlib.util.spec_from_file_location('authority', Path(__file__).with_name('recovery-authority.py'))
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class AuthorityTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.key, self.public = self.root / 'key.pem', self.root / 'public.pem'
        module.crypto(['genpkey', '-algorithm', 'ED25519', '-out', str(self.key)])
        module.crypto(['pkey', '-in', str(self.key), '-pubout', '-out', str(self.public)])
        self.store = self.root / 'authority.sqlite'
        module.Authority.initialize(self.store, self.public)
        self.authority = module.Authority(self.store)
        self.now = int(time.time())
        self.base = dict(version=1, environment='fixture', deployment='candidate', generation=1,
                         target='a'*64, sourceSystem='123', timelineHistoryDigest='b'*64,
                         targetLsn='0/12345678', ledgerDigest='c'*64, runId='test-run',
                         issuedAt=self.now, expiresAt=self.now+60, fenced=True,
                         walContinuous=True, durableCommitBoundary=True, publicOnly=True,
                         deletedUsersRemaining=0, sessionsRemaining=0, orphansRemaining=0,
                         schemaVerified=True, oldCredentialsRejected=True, ingressBound=True)
        self.request = dict(environment='fixture', deployment='candidate', target='a'*64, nonce=str(uuid.uuid4()))

    def tearDown(self):
        self.authority.db.close()
        self.temp.cleanup()

    def begin(self, expected=0, owner='worker'):
        return self.authority.begin('fixture', 'candidate', 'a'*64, expected, owner, self.now)

    def advance(self, state, **changes):
        receipt = dict(self.base, state=state, **changes)
        self.authority.advance(module.envelope(receipt, self.key), self.public, 'worker', self.now)

    def released(self):
        self.begin()
        for state in module.STATES[1:]:
            self.advance(state)

    def test_missing_state_and_unregistered_fail_closed(self):
        with self.assertRaises(Exception):
            module.Authority(self.root / 'missing.sqlite')
        with self.assertRaises(ValueError):
            self.authority.decision(self.request, self.now)
        self.begin()
        with self.assertRaises(ValueError):
            self.authority.decision(self.request, self.now)

    def test_full_ordered_signed_workflow_and_expiry(self):
        self.released()
        decision = self.authority.decision(self.request, self.now)
        self.assertEqual('RELEASED', decision['state'])
        self.assertEqual(self.request['nonce'], decision['nonce'])
        self.assertEqual(decision, module.verify(module.envelope(decision, self.key), self.public))
        with self.assertRaises(ValueError):
            self.authority.decision(self.request, self.now+61)

    def test_generation_cas_worker_lease_and_old_signed_receipt(self):
        self.released()
        with self.assertRaises(ValueError):
            self.begin(expected=0)
        with self.assertRaises(ValueError):
            self.begin(expected=1, owner='other-worker')
        self.assertEqual(2, self.begin(expected=1))
        with self.assertRaises(ValueError):
            self.advance('FENCED')  # Valid signature, old generation.
        with self.assertRaises(ValueError):
            self.authority.decision(self.request, self.now)

    def test_skipped_stage_and_wrong_target_rejected(self):
        self.begin()
        with self.assertRaises(ValueError):
            self.advance('RELEASED')
        with self.assertRaises(ValueError):
            self.advance('FENCED', target='d'*64)

    def test_wal_gap_wrong_timeline_and_lsn_rejected(self):
        self.begin()
        self.advance('FENCED')
        for change in [dict(walContinuous=False), dict(durableCommitBoundary=False),
                       dict(timelineHistoryDigest='d'*64), dict(targetLsn='1/222')]:
            with self.assertRaises(ValueError):
                self.advance('LEDGER_VERIFIED', **change)
        self.assertEqual('FENCED', self.authority.db.execute('SELECT state FROM current').fetchone()[0])

    def test_reconciliation_and_ingress_failure_stay_blocked(self):
        self.begin()
        for state in ['FENCED', 'LEDGER_VERIFIED', 'RESTORED']:
            self.advance(state)
        for field in ['deletedUsersRemaining', 'sessionsRemaining', 'orphansRemaining']:
            with self.assertRaises(ValueError):
                self.advance('RECONCILED', **{field: 1})
        self.advance('RECONCILED')
        self.advance('VALIDATED')
        with self.assertRaises(ValueError):
            self.advance('RELEASED', ingressBound=False)
        with self.assertRaises(ValueError):
            self.authority.decision(self.request, self.now)

    def test_process_restart_preserves_block_and_audit(self):
        self.begin()
        self.advance('FENCED')
        self.authority.db.close()
        self.authority = module.Authority(self.store)
        with self.assertRaises(ValueError):
            self.authority.decision(self.request, self.now)
        self.assertEqual(2, self.authority.db.execute('SELECT count(*) FROM audit').fetchone()[0])

    def test_expired_signed_receipt_and_tampered_signature_rejected(self):
        self.begin()
        with self.assertRaises(ValueError):
            self.advance('FENCED', issuedAt=self.now-100, expiresAt=self.now-40)
        document = module.envelope(dict(self.base, state='FENCED'), self.key)
        document['signature'] = 'A'*88
        with self.assertRaises(ValueError):
            self.authority.advance(document, self.public, 'worker', self.now)

    def test_actual_http_signature_and_immediate_generation_revocation(self):
        self.released()
        server = module.make_server(self.store, self.key, 0)
        worker = threading.Thread(target=server.serve_forever, daemon=True)
        worker.start()
        try:
            url = 'http://127.0.0.1:%d/decision' % server.server_port
            request = urllib.request.Request(url, data=module.packed(self.request),
                                             headers={'Content-Type': 'application/json'})
            with urllib.request.urlopen(request, timeout=3) as response:
                self.assertEqual('no-store', response.headers['Cache-Control'])
                proof = module.verify(json.load(response), self.public)
                self.assertEqual(self.request['nonce'], proof['nonce'])
                self.assertEqual(1, proof['generation'])
            self.begin(expected=1)
            with self.assertRaises(urllib.error.HTTPError) as error:
                urllib.request.urlopen(request, timeout=3)
            self.assertEqual(503, error.exception.code)
        finally:
            server.shutdown()
            worker.join(timeout=2)
            server.server_close()

    def test_untrusted_verifier_cannot_self_authorize(self):
        self.begin()
        other, other_public = self.root / 'other.pem', self.root / 'other-public.pem'
        module.crypto(['genpkey', '-algorithm', 'ED25519', '-out', str(other)])
        module.crypto(['pkey', '-in', str(other), '-pubout', '-out', str(other_public)])
        document = module.envelope(dict(self.base, state='FENCED'), other)
        with self.assertRaises(ValueError):
            self.authority.advance(document, other_public, 'worker', self.now)

    def test_restored_application_configuration_cannot_select_old_generation(self):
        self.released()
        self.begin(expected=1)
        old_request = dict(self.request)
        with self.assertRaises(ValueError):
            self.authority.decision(old_request, self.now)


if __name__ == '__main__':
    unittest.main(verbosity=2)
