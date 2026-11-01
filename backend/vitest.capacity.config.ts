import { defineWorkersConfig, readD1Migrations } from '@cloudflare/vitest-pool-workers/config';

// Explicit opt-in: capacity_test.ts is deliberately outside the normal *.test.ts suite.
export default defineWorkersConfig(async () => ({
  test: {
    include: ['tests/capacity/capacity_test.ts'],
    testTimeout: 600_000,
    hookTimeout: 60_000,
    poolOptions: { workers: {
      wrangler: { configPath: './wrangler.toml' },
      miniflare: { compatibilityDate: '2025-06-20', bindings: {
        TEST_MIGRATIONS: await readD1Migrations('./migrations'),
      } },
    } },
  },
}));
