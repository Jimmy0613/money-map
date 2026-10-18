// Real Neon + Workers integration. Credentials are read only from an ignored file.
import fs from 'node:fs';
import assert from 'node:assert/strict';
import { neon } from '../infra/recovery-authority/node_modules/@neondatabase/serverless/index.mjs';
import { sha } from '../infra/recovery-authority/src/policy.mjs';

const [folder, base, phase = 'initial'] = process.argv.slice(2);
if (!folder || !base || !['initial', 'continued', 'block'].includes(phase)) throw new Error('Missing explicit test target');
const config = JSON.parse(fs.readFileSync(`${folder}/wrangler.json`, 'utf8')).vars;
if (config.RECOVERY_ENVIRONMENT !== 'bootstrap-test') throw new Error('Only isolated test registration permitted');
const secrets = JSON.parse(fs.readFileSync(`${folder}/secrets.json`, 'utf8'));
const sql = neon(secrets.VERIFIER_DATABASE_URL);
const rows = await sql`SELECT public.recovery_observation(true) AS observation`;
if (phase === 'initial') assert.equal(rows[0].observation.empty, true);
assert.ok(rows[0].observation.stable);
console.log('PASS actual Neon metadata-only observation' + (phase === 'initial' ? ' and empty database' : ''));
await assert.rejects(() => sql`SELECT email,password_hash FROM public.users`);
await assert.rejects(() => sql`DELETE FROM privacy.deletion_ledger`);
console.log('PASS verifier cannot read personal rows or modify deletion ledger');
const challenge = () => ({ environment: config.RECOVERY_ENVIRONMENT, deployment: config.RECOVERY_DEPLOYMENT,
  target: '', nonce: crypto.randomUUID() });
const target = await sha(rows[0].observation.physical);
async function request(path, admin = false, value = { ...challenge(), target }) {
  return fetch(new URL(path, base), { method: 'POST', headers: { 'Content-Type': 'application/json',
    Authorization: `Bearer ${admin ? secrets.ADMIN_TOKEN : secrets.DECISION_TOKEN}` },
    body: JSON.stringify(value), signal: AbortSignal.timeout(12000) });
}
assert.equal((await fetch(new URL('/decision', base), { method: 'POST', body: '{}' })).status, 401);
if (phase === 'initial') {
  assert.equal((await request('/decision')).status, 503);
  assert.equal((await request('/register', true)).status, 201);
  assert.equal((await request('/register', true)).status, 409);
  console.log('PASS missing registration blocked; verified first registration accepted once');
}
if (phase === 'block') {
  assert.equal((await request('/block', true, {})).status, 200);
  assert.equal((await request('/decision')).status, 503);
  assert.equal((await request('/register', true)).status, 409);
  console.log('PASS explicit revocation persists and cannot be reopened through bootstrap');
} else {
  const value = { ...challenge(), target };
  const response = await request('/decision', false, value);
  assert.equal(response.status, 200);
  const envelope = await response.json();
  const payload = Uint8Array.from(atob(envelope.payload), c => c.charCodeAt(0));
  const publicBytes = Uint8Array.from(atob(fs.readFileSync(
    '.tooling/recovery-setup/authority-public.der.b64', 'utf8')), c => c.charCodeAt(0));
  const publicKey = await crypto.subtle.importKey('spki', publicBytes,
    { name: 'Ed25519' }, false, ['verify']);
  assert.equal(await crypto.subtle.verify('Ed25519', publicKey,
    Uint8Array.from(atob(envelope.signature), c => c.charCodeAt(0)), payload), true);
  const proof = JSON.parse(new TextDecoder().decode(payload));
  for (const key of ['environment', 'deployment', 'target', 'nonce']) assert.equal(proof[key], value[key]);
  assert.equal(proof.state, 'RELEASED');
  assert.ok(proof.expiresAt - proof.issuedAt <= 5);
  assert.equal((await request('/decision', false, { ...challenge(), target: '0'.repeat(64) })).status, 503);
  console.log('PASS live provider/SQL revalidation and Ed25519 nonce-bound decision; wrong target denied');
}
