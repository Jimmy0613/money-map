import { test } from 'node:test';
import assert from 'node:assert/strict';
import { authorize, BindingMismatch, challenge, providerBinding, sameRegistration, sha, signedProof, upgradeChallenge } from '../src/policy.mjs';

const config = { NEON_PROJECT_ID: 'project', NEON_BRANCH_ID: 'br-original', NEON_ENDPOINT_ID: 'ep-original',
  RECOVERY_ENVIRONMENT: 'production', RECOVERY_DEPLOYMENT: 'deployment' };
const branch = { id: 'br-original', project_id: 'project', current_state: 'ready', init_source: 'parent-data',
  created_at: '2026-10-18T00:00:00Z' };
const endpoint = { id: 'ep-original', project_id: 'project', branch_id: 'br-original', type: 'read_write',
  current_state: 'active', created_at: '2026-10-18T00:00:00Z' };

test('upgrade accepts only the configured full commit and explicit previous commit and safe generation', () => {
  const env = { ...config, RECOVERY_DEPLOYMENT: 'b'.repeat(40) };
  const value = { environment: env.RECOVERY_ENVIRONMENT, deployment: env.RECOVERY_DEPLOYMENT,
    previousDeployment: 'a'.repeat(40), expectedGeneration: 1,
    target: 'c'.repeat(64), nonce: crypto.randomUUID() };
  assert.deepEqual(upgradeChallenge(value, env), value);
  for (const bad of [null, [], {}, { ...value, extra: true },
    ...[0, -1, 1.5, '1', null, Number.MAX_SAFE_INTEGER].map(expectedGeneration => ({ ...value, expectedGeneration })),
    ...['main', 'abc123', 'd'.repeat(40), null].map(deployment => ({ ...value, deployment })),
    ...['main', '', value.deployment, null].map(previousDeployment => ({ ...value, previousDeployment })),
    { ...value, environment: 'other' }, { ...value, target: 'invalid' }, { ...value, nonce: 'invalid' },
  ]) assert.throws(() => upgradeChallenge(bad, env));
});

test('initial registration rejects restored, reset, imported, copied, pending and mismatched targets', () => {
  assert.ok(providerBinding(branch, endpoint, config));
  for (const bad of [
    { last_reset_at: 'now' }, { restored_from: 'snapshot' }, { restored_as: 'old' },
    { restore_status: { state: 'done' } }, { recovery: {} }, { parent_id: 'parent' },
    { parent_lsn: '0/1' }, { parent_timestamp: 'now' }, { init_source: 'import' },
    { pending_state: 'ready' }, { current_state: 'resetting' }, { project_id: 'other' },
    { id: 'br-replaced' }, { created_at: null },
  ]) assert.throws(() => providerBinding({ ...branch, ...bad }, endpoint, config));
  for (const bad of [ { id: 'ep-replaced' }, { branch_id: 'other' }, { type: 'read_only' },
    { current_state: 'init' }, { pending_state: 'active' }, { project_id: 'other' } ]) {
    assert.throws(() => providerBinding(branch, { ...endpoint, ...bad }, config));
  }
});

test('logical target/schema and provider registration must match; compute restart can be revalidated', async () => {
  const stable = await sha('tenant:timeline:db:schema');
  const provider = providerBinding(branch, endpoint, config);
  const record = { state: 'RELEASED', stable, provider };
  assert.equal(sameRegistration(record, { stable, provider, target: await sha('new-process') }), true);
  assert.equal(sameRegistration(record, { stable: await sha('other-timeline'), provider }), false);
  assert.equal(sameRegistration(record, { stable, provider: 'new-endpoint' }), false);
  assert.equal(sameRegistration({ ...record, state: 'BLOCKED' }, { stable, provider }), false);
});

test('ordinary compute wake-up is transient; restore/reset provenance is a permanent mismatch', () => {
  assert.throws(() => providerBinding(branch, { ...endpoint, current_state: 'init', pending_state: 'active' }, config),
    error => !(error instanceof BindingMismatch));
  assert.throws(() => providerBinding({ ...branch, current_state: 'resetting' }, endpoint, config), BindingMismatch);
});

test('proof binds nonce/environment/deployment/target and is an actual Ed25519 signature', async () => {
  const value = { environment: 'production', deployment: 'deployment', target: 'a'.repeat(64), nonce: crypto.randomUUID() };
  assert.deepEqual(challenge(value, config), value);
  for (const bad of [{ nonce: 'replayed' }, { environment: 'other' }, { deployment: 'other' },
    { target: 'invalid' }, { extra: true }]) assert.throws(() => challenge({ ...value, ...bad }, config));
  const pair = await crypto.subtle.generateKey('Ed25519', true, ['sign', 'verify']);
  const privateKey = btoa(String.fromCharCode(...new Uint8Array(await crypto.subtle.exportKey('pkcs8', pair.privateKey))));
  const envelope = await signedProof(value, privateKey);
  const bytes = Uint8Array.from(atob(envelope.payload), c => c.charCodeAt(0));
  const signature = Uint8Array.from(atob(envelope.signature), c => c.charCodeAt(0));
  assert.equal(await crypto.subtle.verify('Ed25519', pair.publicKey, signature, bytes), true);
  bytes[0] ^= 1;
  assert.equal(await crypto.subtle.verify('Ed25519', pair.publicKey, signature, bytes), false);
});

test('separate strong admin and decision secrets are required', async () => {
  const token = 'synthetic-token-'.repeat(4);
  assert.equal(await authorize(new Request('https://example.test'), token), false);
  assert.equal(await authorize(new Request('https://example.test', { headers: { Authorization: `Bearer ${token}` } }), token), true);
  assert.equal(await authorize(new Request('https://example.test', { headers: { Authorization: 'Bearer wrong' } }), token), false);
  assert.equal(await authorize(new Request('https://example.test'), ''), false);
});
