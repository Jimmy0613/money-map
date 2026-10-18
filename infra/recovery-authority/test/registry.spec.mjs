import { env, exports } from 'cloudflare:workers';
import { runInDurableObject, evictDurableObject } from 'cloudflare:test';
import { afterEach, expect, test, vi } from 'vitest';

afterEach(() => vi.unstubAllGlobals());
const body = () => JSON.stringify({ environment: env.RECOVERY_ENVIRONMENT,
  deployment: env.RECOVERY_DEPLOYMENT, target: 'a'.repeat(64), nonce: crypto.randomUUID() });
const request = (path, admin = false, value = body()) => new Request(`https://authority.test${path}`, {
  method: 'POST', body: value, headers: { Authorization: `Bearer ${admin ? env.ADMIN_TOKEN : env.DECISION_TOKEN}` },
});

test('HTTP boundary rejects unauthenticated calls and unregistered decisions', async () => {
  expect((await exports.default.fetch(new Request('https://authority.test/decision', { method: 'POST', body: '{}' }))).status).toBe(401);
  expect((await exports.default.fetch(request('/decision'))).status).toBe(503);
  expect((await exports.default.fetch(request('/status', false, '{}'))).status).toBe(401);
});

test('explicit revocation persists in SQLite across actual object eviction', async () => {
  const stub = env.REGISTRY.get(env.REGISTRY.newUniqueId());
  const blocked = await stub.execute('/block', '{}');
  expect(blocked.status).toBe(200);
  await blocked.json(); // Release the RPC response stream before waiting for eviction.
  let status = await (await stub.execute('/status', '{}')).json();
  expect(status).toEqual({ state: 'BLOCKED', generation: 1 });
  await evictDurableObject(stub);
  status = await (await stub.execute('/status', '{}')).json();
  expect(status).toEqual({ state: 'BLOCKED', generation: 1 });
  expect((await stub.execute('/register', body())).status).toBe(409);
  await runInDurableObject(stub, (instance, state) => {
    expect(state.storage.sql.exec('SELECT state,generation FROM audit').toArray()).toEqual([{ state: 'BLOCKED', generation: 1 }]);
  });
});

test('registry state changes cannot be undone by a stale verification result', async () => {
  const stub = env.REGISTRY.get(env.REGISTRY.newUniqueId());
  await runInDurableObject(stub, (instance) => {
    const previous = { state: 'RELEASED', generation: 1 };
    instance.persist(previous);
    instance.block(previous);
    instance.block(previous);
    expect(instance.current().generation).toBe(2);
    expect(instance.current().state).toBe('BLOCKED');
  });
  expect((await stub.execute('/decision', body())).status).toBe(503);
});

test('dependency failure does not create an initial registration', async () => {
  const stub = env.REGISTRY.get(env.REGISTRY.newUniqueId());
  vi.stubGlobal('fetch', () => Promise.reject(new Error('Synthetic outage')));
  expect((await stub.execute('/register', body())).status).toBe(503);
  expect(await (await stub.execute('/status', '{}')).json()).toEqual({ state: 'UNREGISTERED', generation: 0 });
});
