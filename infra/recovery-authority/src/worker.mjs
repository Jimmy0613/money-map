import { observe } from './neon-observer.mjs';
import { boundedText } from './bounded-body.mjs';
import { DurableObject } from 'cloudflare:workers';
import { authorize, BindingMismatch, challenge, sameRegistration, signedProof } from './policy.mjs';

const reply = (status, value) => Response.json(value, {
  status, headers: { 'Cache-Control': 'no-store', 'X-Content-Type-Options': 'nosniff' },
});


export class InitialRegistry extends DurableObject {
  constructor(ctx, env) {
    super(ctx, env);
    this.ctx.storage.sql.exec(`
      CREATE TABLE IF NOT EXISTS registration (singleton INTEGER PRIMARY KEY CHECK(singleton=1), value TEXT NOT NULL);
      CREATE TABLE IF NOT EXISTS audit (sequence INTEGER PRIMARY KEY AUTOINCREMENT,
        generation INTEGER NOT NULL, state TEXT NOT NULL, at INTEGER NOT NULL);
    `);
  }

  current() {
    const rows = this.ctx.storage.sql.exec('SELECT value FROM registration WHERE singleton=1').toArray();
    return rows.length ? JSON.parse(rows[0].value) : null;
  }

  persist(record) {
    this.ctx.storage.transactionSync(() => {
      this.ctx.storage.sql.exec('INSERT INTO registration VALUES (1,?) ON CONFLICT(singleton) DO UPDATE SET value=excluded.value', JSON.stringify(record));
      this.ctx.storage.sql.exec('INSERT INTO audit(generation,state,at) VALUES (?,?,?)', record.generation, record.state, Date.now());
    });
  }

  async execute(path, raw) {
    try { return await this.handle(path, raw); }
    catch (error) {
      console.warn(JSON.stringify({ code: 'RECOVERY_CHECK_FAILED',
        stage: error.stage || 'registry', kind: error instanceof BindingMismatch ? 'BINDING' : error instanceof TypeError ? 'TYPE' : 'DEPENDENCY' }));
      return reply(503, { code: 'RECOVERY_REQUIRED' });
    }
  }

  async handle(path, raw) {
    const record = this.current();
    if (path === '/status') return reply(200, {
      state: record?.state || 'UNREGISTERED', generation: record?.generation || 0,
    });
    if (path === '/block') {
      this.persist({
        ...(record || {}), state: 'BLOCKED', generation: (record?.generation || 0) + 1,
      });
      return reply(200, { state: 'BLOCKED' });
    }
    const value = challenge(JSON.parse(raw), this.env);
    if (path === '/register') {
      if (record) return reply(409, { code: 'ALREADY_REGISTERED' });
      const observed = await observe(this.env, true);
      if (observed.target !== value.target) return reply(409, { code: 'TARGET_CHANGED' });
      if (this.current()) return reply(409, { code: 'ALREADY_REGISTERED' });
      this.persist({
        state: 'RELEASED', generation: 1, environment: value.environment,
        deployment: value.deployment, stable: observed.stable,
        provider: observed.provider, registeredAt: Date.now(),
      });
      return reply(201, { state: 'RELEASED', generation: 1 });
    }
    if (!record || record.state !== 'RELEASED' || record.environment !== value.environment
        || record.deployment !== value.deployment) return reply(503, { code: 'RECOVERY_REQUIRED' });
    let observed;
    try { observed = await observe(this.env, false); }
    catch (error) {
      if (error instanceof BindingMismatch) this.block(record);
      throw error;
    }
    if (!sameRegistration(record, observed)) {
      this.block(record);
      return reply(503, { code: 'RECOVERY_REQUIRED' });
    }
    if (observed.target !== value.target) return reply(503, { code: 'RECOVERY_REQUIRED' });
    const now = Math.floor(Date.now() / 1000);
    const proof = await signedProof({ ...value, version: 1, state: 'RELEASED',
      generation: record.generation, issuedAt: now, expiresAt: now + 5 }, this.env.SIGNING_KEY);
    const current = this.current();
    // No await between this generation check and returning the decision.
    if (!current || current.state !== 'RELEASED' || current.generation !== record.generation)
      return reply(503, { code: 'RECOVERY_REQUIRED' });
    return reply(200, proof);
  }

  block(record) {
    const current = this.current();
    if (!current || current.generation !== record.generation) return;
    this.persist({
      ...record, state: 'BLOCKED', generation: record.generation + 1,
    });
  }
}

export default {
  async fetch(request, env) {
    // Reject unauthenticated calls before creating/accessing the paid-metered object.
    const path = new URL(request.url).pathname;
    if (!['/register', '/decision', '/block', '/status'].includes(path) || request.method !== 'POST')
      return reply(404, { code: 'NOT_FOUND' });
    if (!await authorize(request, path === '/decision' ? env.DECISION_TOKEN : env.ADMIN_TOKEN))
      return reply(401, { code: 'UNAUTHENTICATED' });
    try {
      const raw = await boundedText(request, 2048);
      return await env.REGISTRY.getByName(env.RECOVERY_ENVIRONMENT).execute(path, raw);
    } catch { return reply(503, { code: 'RECOVERY_REQUIRED' }); }
  },
};
