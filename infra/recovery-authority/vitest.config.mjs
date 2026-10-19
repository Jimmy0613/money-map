import { cloudflareTest } from '@cloudflare/vitest-plugin';
import { defineConfig } from 'vitest/config';

export default defineConfig({
  plugins: [cloudflareTest({
    wrangler: { configPath: './wrangler.jsonc' },
    miniflare: { bindings: {
      NEON_PROJECT_ID: 'test-project', NEON_BRANCH_ID: 'br-test', NEON_ENDPOINT_ID: 'ep-test',
      RECOVERY_ENVIRONMENT: 'runtime-test', RECOVERY_DEPLOYMENT: 'bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb',
      ADMIN_TOKEN: 'synthetic-admin-token-for-runtime-tests-only',
      DECISION_TOKEN: 'synthetic-decision-token-for-runtime-tests-only',
      VERIFIER_DATABASE_URL: 'postgresql://verifier:synthetic@ep-test.aws.neon.tech/test',
      NEON_API_KEY: 'synthetic-api-key',
    } },
  })],
  test: { include: ['test/*.spec.mjs'] },
});
