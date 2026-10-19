import { env, exports } from 'cloudflare:workers';
import { runInDurableObject, evictDurableObject } from 'cloudflare:test';
import { expect, test } from 'vitest';
import { BindingMismatch } from '../src/policy.mjs';

const previous = 'a'.repeat(40);
const target = 'c'.repeat(64);
const observed = { stable: 'same-lineage-schema', provider: 'same-provider', target };
const record = () => ({ state: 'RELEASED', generation: 7,
  environment: env.RECOVERY_ENVIRONMENT, deployment: previous,
  stable: observed.stable, provider: observed.provider, registeredAt: 123 });
const upgrade = () => ({ environment: env.RECOVERY_ENVIRONMENT,
  deployment: env.RECOVERY_DEPLOYMENT, previousDeployment: previous,
  expectedGeneration: 7, target, nonce: crypto.randomUUID() });
const stub = () => env.REGISTRY.get(env.REGISTRY.newUniqueId());

test('upgrade HTTP endpoint requires admin rather than decision credentials', async () => {
  for (const token of ['', env.DECISION_TOKEN]) {
    const response = await exports.default.fetch(new Request('https://authority.test/upgrade', {
      method: 'POST', headers: { Authorization: `Bearer ${token}` }, body: JSON.stringify(upgrade()),
    }));
    expect(response.status).toBe(401);
  }
  const response = await exports.default.fetch(new Request('https://authority.test/upgrade', {
    method: 'POST', headers: { Authorization: `Bearer ${env.ADMIN_TOKEN}` }, body: '{}',
  }));
  expect(response.status).toBe(400);
});

test('upgrade commits preserved binding and atomic audit, survives eviction, rejects replay and old deployment', async () => {
  const object = stub();
  const value = upgrade();
  await runInDurableObject(object, async (instance, state) => {
    instance.persist(record());
    instance.observe = async () => observed;
    const response = await instance.handle('/upgrade', JSON.stringify(value));
    expect(response.status).toBe(200);
    expect(await response.json()).toEqual({ state: 'RELEASED', generation: 8, deployment: env.RECOVERY_DEPLOYMENT });
    expect(instance.current()).toMatchObject({ ...record(), generation: 8, deployment: env.RECOVERY_DEPLOYMENT });
    expect(state.storage.sql.exec('SELECT generation,state FROM audit ORDER BY sequence').toArray())
      .toEqual([{ generation: 7, state: 'RELEASED' }, { generation: 8, state: 'RELEASED' }]);
    expect(state.storage.sql.exec('SELECT * FROM deployment_audit').toArray()).toEqual([
      expect.objectContaining({ generation: 8, previous_deployment: previous,
        deployment: env.RECOVERY_DEPLOYMENT, target, nonce: value.nonce }),
    ]);
    expect((await instance.handle('/upgrade', JSON.stringify(value))).status).toBe(409);
    expect((await instance.execute('/decision', JSON.stringify({ environment: value.environment,
      deployment: previous, target, nonce: crypto.randomUUID() }))).status).toBe(503);
    // Real signing is still mandatory after an upgrade.
    const pair = await crypto.subtle.generateKey('Ed25519', true, ['sign', 'verify']);
    instance.env.SIGNING_KEY = btoa(String.fromCharCode(...new Uint8Array(await crypto.subtle.exportKey('pkcs8', pair.privateKey))));
    const challenge = { environment: value.environment, deployment: value.deployment, target, nonce: crypto.randomUUID() };
    const decision = await instance.handle('/decision', JSON.stringify(challenge));
    expect(decision.status).toBe(200);
    const proof = await decision.json();
    const payload = Uint8Array.from(atob(proof.payload), c => c.charCodeAt(0));
    expect(await crypto.subtle.verify('Ed25519', pair.publicKey,
      Uint8Array.from(atob(proof.signature), c => c.charCodeAt(0)), payload)).toBe(true);
    expect(JSON.parse(new TextDecoder().decode(payload))).toMatchObject({ ...challenge, generation: 8 });
  });
  await evictDurableObject(object);
  expect(await (await object.execute('/status', '{}')).json()).toEqual({ state: 'RELEASED', generation: 8 });
  expect((await object.execute('/upgrade', JSON.stringify(value))).status).toBe(409);
});

test('unregistered, blocked, wrong generation and previous deployment cannot be upgraded', async () => {
  for (const fixture of [null, { ...record(), state: 'BLOCKED' }, { ...record(), generation: 8 },
    { ...record(), deployment: 'd'.repeat(40) }, { ...record(), environment: 'other' }]) {
    await runInDurableObject(stub(), async instance => {
      if (fixture) instance.persist(fixture);
      instance.observe = () => { throw new Error('must not query dependencies'); };
      expect((await instance.handle('/upgrade', JSON.stringify(upgrade()))).status).toBe(409);
      expect(instance.current()).toEqual(fixture);
    });
  }
});

test('changed lineage/schema/provider permanently blocks; outage and stale physical target do not release or rewrite', async () => {
  for (const change of [{ stable: 'new-schema' }, { provider: 'new-endpoint' }, 'restore', 'outage', { target: 'e'.repeat(64) }]) {
    await runInDurableObject(stub(), async (instance, state) => {
      instance.persist(record());
      instance.observe = async () => {
        if (change === 'restore') throw new BindingMismatch();
        if (change === 'outage') throw new Error('synthetic outage');
        return { ...observed, ...change };
      };
      const response = await instance.execute('/upgrade', JSON.stringify(upgrade()));
      expect(response.status).toBe(change.target ? 409 : 503);
      const blocked = change === 'restore' || change.stable || change.provider;
      expect(instance.current()).toMatchObject({ deployment: previous,
        generation: blocked ? 8 : 7, state: blocked ? 'BLOCKED' : 'RELEASED' });
      expect(state.storage.sql.exec('SELECT * FROM deployment_audit').toArray()).toEqual([]);
    });
  }
});

test('concurrent upgrades allow one winner and block during observation cannot be undone', async () => {
  await runInDurableObject(stub(), async (instance, state) => {
    instance.persist(record());
    let release;
    const pendingObservation = new Promise(resolve => { release = resolve; });
    instance.observe = () => pendingObservation;
    const a = instance.handle('/upgrade', JSON.stringify(upgrade()));
    const b = instance.handle('/upgrade', JSON.stringify(upgrade()));
    release(observed);
    const results = await Promise.all([a, b]);
    expect(results.map(r => r.status).sort()).toEqual([200, 409]);
    expect(state.storage.sql.exec('SELECT * FROM deployment_audit').toArray()).toHaveLength(1);
  });
  await runInDurableObject(stub(), async instance => {
    instance.persist(record());
    let release;
    instance.observe = () => new Promise(resolve => { release = resolve; });
    const pending = instance.handle('/upgrade', JSON.stringify(upgrade()));
    instance.block(record());
    release(observed);
    expect((await pending).status).toBe(409);
    expect(instance.current()).toMatchObject({ state: 'BLOCKED', generation: 8, deployment: previous });
  });
});

test('audit write failure rolls back registration and general audit together', async () => {
  await runInDurableObject(stub(), async (instance, state) => {
    instance.persist(record());
    instance.observe = async () => observed;
    state.storage.sql.exec(`CREATE TRIGGER fail_upgrade BEFORE INSERT ON deployment_audit
      BEGIN SELECT RAISE(ABORT, 'synthetic storage failure'); END`);
    expect((await instance.execute('/upgrade', JSON.stringify(upgrade()))).status).toBe(503);
    expect(instance.current()).toEqual(record());
    expect(state.storage.sql.exec('SELECT * FROM audit').toArray()).toHaveLength(1);
  });
});
