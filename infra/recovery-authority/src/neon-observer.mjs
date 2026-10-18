import { boundedText } from './bounded-body.mjs';
import { neon } from '@neondatabase/serverless';
import { BindingMismatch, providerBinding, sha } from './policy.mjs';

async function provider(path, env) {
  const response = await fetch(`https://console.neon.tech/api/v2/projects/${env.NEON_PROJECT_ID}/${path}`, {
    headers: { Authorization: `Bearer ${env.NEON_API_KEY}` },
    redirect: 'manual', signal: AbortSignal.timeout(3000),
  });
  if (!response.ok) throw new Error('Provider unavailable');
  const text = await boundedText(response, 65536);
  return JSON.parse(text);
}

export async function observe(env, initial) {
  let stage = "target-url";
  try {
    // URL and role are secret bindings; this login can execute only the metadata function.
    const url = new URL(env.VERIFIER_DATABASE_URL);
    if (url.protocol !== 'postgresql:' || !url.hostname.endsWith('.neon.tech')
        || url.hostname.includes('-pooler.') || !url.hostname.startsWith(env.NEON_ENDPOINT_ID + '.')) {
      throw new BindingMismatch();
    }
    stage = "sql-client";
    const sql = neon(env.VERIFIER_DATABASE_URL, { fetchOptions: { signal: AbortSignal.timeout(3000) } });
    stage = "live-observation";
    const [before, endpoint, rows] = await Promise.all([
      provider(`branches/${env.NEON_BRANCH_ID}`, env),
      provider(`endpoints/${env.NEON_ENDPOINT_ID}`, env),
      sql`SELECT public.recovery_observation(${initial}) AS observation`,
    ]);
    stage = "provider-binding";
    const binding = providerBinding(before.branch, endpoint.endpoint, env);
    const observation = rows[0]?.observation;
    if (!observation || observation.migrationFailures !== 0 || (initial && observation.empty !== true)) {
      throw new BindingMismatch();
    }
    // A reset seen on either side of the SQL observation invalidates the result.
    stage = "provider-recheck";
    const after = await provider(`branches/${env.NEON_BRANCH_ID}`, env);
    if (providerBinding(after.branch, endpoint.endpoint, env) !== binding) throw new BindingMismatch();
    stage = "digests";
    return { provider: binding, stable: await sha(observation.stable),
      target: await sha(observation.physical) };
  } catch (error) { error.stage = stage; throw error; }
}

