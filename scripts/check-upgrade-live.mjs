// Isolated live Neon/Cloudflare check. Never accepts production registration.
import fs from 'node:fs';
import assert from 'node:assert/strict';
import { neon } from '../infra/recovery-authority/node_modules/@neondatabase/serverless/index.mjs';
import { sha } from '../infra/recovery-authority/src/policy.mjs';

const [folder, base, phase] = process.argv.slice(2);
assert.ok(folder && /^https:\/\/money-map-upgrade-check-20261019\.[a-z0-9-]+\.workers\.dev$/.test(base));
assert.ok(['initial', 'upgrade'].includes(phase));
const config = JSON.parse(fs.readFileSync(`${folder}/wrangler.json`, 'utf8')).vars;
assert.equal(config.RECOVERY_ENVIRONMENT, 'upgrade-test-20261019');
assert.notEqual(config.NEON_BRANCH_ID, 'br-twilight-shape-aob4z652');
const secrets = JSON.parse(fs.readFileSync(`${folder}/secrets.json`, 'utf8'));
assert.equal(new URL(secrets.VERIFIER_DATABASE_URL).pathname, '/money_map_upgrade_20261019');
const sql = neon(secrets.VERIFIER_DATABASE_URL);
const rows = await sql`SELECT public.recovery_observation(false) AS observation`;
const target = await sha(rows[0].observation.physical);
await assert.rejects(() => sql`SELECT email,password_hash FROM public.users`);
await assert.rejects(() => sql`DELETE FROM privacy.deletion_ledger`);
console.log('PASS actual Neon observation; verifier cannot read personal rows or write deletion history');
const challenge = () => ({ environment: config.RECOVERY_ENVIRONMENT,
  deployment: config.RECOVERY_DEPLOYMENT, target, nonce: crypto.randomUUID() });
async function call(path, value, admin = false) {
  return fetch(new URL(path, base), { method: 'POST', headers: { 'Content-Type': 'application/json',
    Authorization: `Bearer ${admin ? secrets.ADMIN_TOKEN : secrets.DECISION_TOKEN}` },
    body: JSON.stringify(value), signal: AbortSignal.timeout(20000) });
}
async function decision() {
  const value = challenge();
  const response = await call('/decision', value);
  assert.equal(response.status, 200, 'live signed decision');
  const envelope = await response.json();
  const payload = Uint8Array.from(atob(envelope.payload), c => c.charCodeAt(0));
  const key = await crypto.subtle.importKey('spki',
    Uint8Array.from(atob(fs.readFileSync(`${folder}/public-key.txt`, 'utf8')), c => c.charCodeAt(0)),
    { name: 'Ed25519' }, false, ['verify']);
  assert.equal(await crypto.subtle.verify('Ed25519', key,
    Uint8Array.from(atob(envelope.signature), c => c.charCodeAt(0)), payload), true);
  const proof = JSON.parse(new TextDecoder().decode(payload));
  for (const name of ['environment', 'deployment', 'target', 'nonce']) assert.equal(proof[name], value[name]);
  return proof;
}
if (phase === 'initial') {
  assert.deepEqual(await (await call('/status', {}, true)).json(), { state: 'UNREGISTERED', generation: 0 });
  assert.equal((await call('/register', challenge(), true)).status, 201);
  assert.equal((await decision()).generation, 1);
  console.log('PASS initial isolated registration and live signed old-commit decision');
} else {
  const value = { ...challenge(), previousDeployment: 'a'.repeat(40), expectedGeneration: 1 };
  assert.equal((await call('/upgrade', value)).status, 401);
  assert.equal((await call('/upgrade', { ...value, expectedGeneration: 2 }, true)).status, 409);
  assert.equal((await call('/upgrade', value, true)).status, 200);
  assert.equal((await call('/upgrade', value, true)).status, 409);
  assert.equal((await call('/decision', { ...challenge(), deployment: value.previousDeployment })).status, 503);
  assert.equal((await decision()).generation, 2);
  assert.equal((await call('/register', challenge(), true)).status, 409);
  console.log('PASS actual upgrade; wrong credentials/generation/replay/old commit rejected; new signed decision accepted');
  assert.equal((await call('/block', {}, true)).status, 200);
  assert.equal((await call('/upgrade', { ...value, expectedGeneration: 3,
    previousDeployment: config.RECOVERY_DEPLOYMENT }, true)).status, 400);
  assert.equal((await call('/upgrade', value, true)).status, 409);
  assert.equal((await call('/decision', challenge())).status, 503);
  assert.deepEqual(await (await call('/status', {}, true)).json(), { state: 'BLOCKED', generation: 3 });
  console.log('PASS explicit block remains closed after upgrade attempts');
}
