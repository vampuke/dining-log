import { applyD1Migrations, env } from 'cloudflare:test';
import { beforeAll, beforeEach, describe, expect, it } from 'vitest';
import fixture from './sql/fixtures.sql?raw';
import { digest } from '../src/auth/config';

const tables = ['auth_rate_limits', 'change_sets', 'dishes', 'idempotency_keys', 'mutation_guards', 'recovery_codes',
  'refresh_rotation_results', 'refresh_tokens', 'restaurant_search', 'restaurants', 'revisions', 'sessions', 'users', 'visits'];
const migration = () => env.TEST_MIGRATIONS.find(entry => entry.name === '0002_address_optional.sql')!;
beforeAll(async () => {
  expect(env.TEST_MIGRATIONS[0].name).toBe('0001_initial.sql');
  await applyD1Migrations(env.DB, [env.TEST_MIGRATIONS[0]]);
});
beforeEach(async () => {
  const queries = fixture.replace(/^--.*$/gm, '').split(';').map(sql => sql.trim()).filter(Boolean);
  await env.DB.batch(queries.map(sql => env.DB.prepare(sql)));
});
async function snapshot() {
  const result: Record<string, { rows: number; sha256: string }> = {};
  for (const table of tables) {
    const rows = (await env.DB.prepare(`SELECT * FROM ${table}`).all()).results.map(row => JSON.stringify(row)).sort();
    result[table] = { rows: rows.length, sha256: await digest(rows.join('\n')) };
  }
  return result;
}
async function indexes() {
  return (await env.DB.prepare("SELECT name,sql FROM sqlite_master WHERE type='index' AND tbl_name='restaurants' AND sql IS NOT NULL ORDER BY name").all()).results;
}

describe('0002 as an atomic D1 migration over populated 0001', () => {
  it('preserves every application table and exact indexes with foreign keys enabled', async () => {
    expect(await env.DB.prepare('PRAGMA foreign_keys').first('foreign_keys')).toBe(1);
    const before = await snapshot(), originalIndexes = await indexes();
    expect(before.restaurants.rows).toBe(3);
    for (const table of ['dishes', 'visits', 'restaurant_search', 'revisions']) expect(before[table].rows).toBeGreaterThan(0);
    expect(originalIndexes).toHaveLength(5);
    await applyD1Migrations(env.DB, [migration()]);
    expect(await snapshot()).toEqual(before);
    expect(await indexes()).toEqual(originalIndexes);
    const indexList = (await env.DB.prepare('PRAGMA index_list(restaurants)').all<{ name: string; origin: string }>()).results;
    expect(indexList.filter(index => index.origin === 'c').map(index => index.name).sort()).toEqual(originalIndexes.map(index => index.name).sort());
    expect((await env.DB.prepare('PRAGMA foreign_key_check').all()).results).toEqual([]);
    // D1's authorizer rejects integrity_check; the standalone SQLite script
    // performs the full check. Keep D1's supported quick_check as an extra gate.
    expect(await env.DB.prepare('PRAGMA quick_check').first('quick_check')).toBe('ok');
    await env.DB.prepare("UPDATE restaurants SET address=NULL WHERE id='10000000-0000-4000-8000-000000000001'").run();
    expect(await env.DB.prepare("SELECT address FROM restaurants WHERE id='10000000-0000-4000-8000-000000000001'").first('address')).toBeNull();
    for (const address of ['', '   ']) await expect(env.DB.prepare('UPDATE restaurants SET address=?').bind(address).run()).rejects.toThrow('CHECK constraint failed');
    await expect(env.DB.prepare("UPDATE revisions SET diff_json='[]'").run()).rejects.toThrow('revision core is immutable');
    expect((await env.DB.prepare("SELECT name FROM sqlite_master WHERE name LIKE '_b_%'").all()).results).toEqual([]);
  });
  it('rolls back the cascading DROP and schema when a later D1 batch statement fails', async () => {
    const before = await snapshot(), originalIndexes = await indexes();
    const queries = migration().queries;
    const drop = queries.findIndex(query => /DROP TABLE restaurants\s*;?\s*$/i.test(query));
    expect(drop).toBeGreaterThan(0);
    const statements = queries.map(query => env.DB.prepare(query));
    statements.splice(drop + 1, 0, env.DB.prepare("INSERT INTO mutation_guards VALUES ('migration-failure',0)"));
    await expect(env.DB.batch(statements)).rejects.toThrow('CHECK constraint failed');
    expect(await snapshot()).toEqual(before);
    expect(await indexes()).toEqual(originalIndexes);
    await expect(env.DB.prepare('UPDATE restaurants SET address=NULL').run()).rejects.toThrow('NOT NULL constraint failed');
    expect((await env.DB.prepare('PRAGMA foreign_key_check').all()).results).toEqual([]);
    expect(await env.DB.prepare('PRAGMA quick_check').first('quick_check')).toBe('ok');
  });
});
