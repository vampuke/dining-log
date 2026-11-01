import { defineWorkersConfig, readD1Migrations } from '@cloudflare/vitest-pool-workers/config';

// Explicit opt-in: capacity_test.ts is deliberately outside the normal *.test.ts suite.
export default defineWorkersConfig(async () => ({
  test: {
    include: ['tests/capacity/capacity_test.ts'],
    testTimeout: 600_000,
    hookTimeout: 60_000,
    poolOptions: { workers: {
      // Each scenario runs in its own process via test:capacity. Avoid cloning / rolling
      // back ~200 MB D1 snapshots in the pool's per-test stacked-storage mechanism.
      isolatedStorage: false,
      wrangler: { configPath: './wrangler.toml' },
      miniflare: { compatibilityDate: '2025-06-17', bindings: {
        TEST_MIGRATIONS: await readD1Migrations('./migrations'),
      } },
    } },
  },
}));
