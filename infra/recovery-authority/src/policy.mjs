// No restored/reset/imported database can enter the initial-registration path.
export function providerBinding(branch, endpoint, config) {
  if (branch.id !== config.NEON_BRANCH_ID || branch.project_id !== config.NEON_PROJECT_ID
      || branch.parent_id || branch.parent_lsn || branch.parent_timestamp
      || branch.last_reset_at || branch.restored_from || branch.restored_as
      || branch.restore_status || branch.recovery
      || !['parent-data', 'parent-schema', 'schema-only'].includes(branch.init_source)
      || !branch.created_at || !endpoint.created_at || endpoint.id !== config.NEON_ENDPOINT_ID
      || endpoint.branch_id !== branch.id || endpoint.project_id !== branch.project_id
      || endpoint.type !== 'read_write'
      || branch.current_state === 'resetting' || branch.pending_state === 'resetting') {
    throw new BindingMismatch();
  }
  // Ordinary compute wake-up/deployment is not evidence of a restored DB.
  // Deny this request, but do not permanently revoke the unchanged registration.
  if (branch.current_state !== 'ready' || branch.pending_state || endpoint.pending_state
      || !['active', 'idle'].includes(endpoint.current_state)) throw new Error('Provider not ready');
  return JSON.stringify([branch.project_id, branch.id, branch.created_at,
    branch.init_source, endpoint.id, endpoint.created_at]);
}

export class BindingMismatch extends Error {}

export async function sha(value) {
  if (typeof value !== 'string' || !value || value.length > 8192) throw new BindingMismatch();
  return Array.from(new Uint8Array(await crypto.subtle.digest('SHA-256',
    new TextEncoder().encode(value))), b => b.toString(16).padStart(2, '0')).join('');
}

export function challenge(value, env) {
  if (!value || Object.keys(value).sort().join(',') !== 'deployment,environment,nonce,target'
      || value.environment !== env.RECOVERY_ENVIRONMENT
      || value.deployment !== env.RECOVERY_DEPLOYMENT
      || !/^[a-f0-9]{64}$/.test(value.target)
      || !/^[a-f0-9]{8}-[a-f0-9]{4}-4[a-f0-9]{3}-[89ab][a-f0-9]{3}-[a-f0-9]{12}$/.test(value.nonce)) {
    throw new Error('Invalid challenge');
  }
  return value;
}

export function sameRegistration(record, observed) {
  return record.state === 'RELEASED' && record.stable === observed.stable
    && record.provider === observed.provider;
}

export async function authorize(request, secret) {
  if (!secret || secret.length < 32) return false;
  const given = request.headers.get('Authorization') || '';
  if (given.length > 1024) return false;
  // Compare fixed-size digests, not variable-length secret strings.
  const a = await sha(given || 'missing'), b = await sha('Bearer ' + secret);
  let diff = 0;
  for (let i = 0; i < a.length; i++) diff |= a.charCodeAt(i) ^ b.charCodeAt(i);
  return diff === 0;
}

export async function signedProof(value, encodedKey) {
  const key = await crypto.subtle.importKey('pkcs8',
    Uint8Array.from(atob(encodedKey), c => c.charCodeAt(0)), { name: 'Ed25519' }, false, ['sign']);
  const payload = new TextEncoder().encode(JSON.stringify(value));
  const signature = new Uint8Array(await crypto.subtle.sign('Ed25519', key, payload));
  return {
    payload: btoa(String.fromCharCode(...payload)),
    signature: btoa(String.fromCharCode(...signature)),
  };
}
