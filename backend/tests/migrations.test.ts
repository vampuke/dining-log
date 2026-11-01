import { applyD1Migrations, env, type D1Migration } from 'cloudflare:test';
import { beforeAll, beforeEach, describe, expect, it } from 'vitest';
import fixtureSql from './sql/fixtures.sql?raw';

declare module 'cloudflare:test' {
  interface ProvidedEnv { DB: D1Database; TEST_MIGRATIONS: D1Migration[] }
}

beforeAll(async () => { await applyD1Migrations(env.DB, env.TEST_MIGRATIONS); });
beforeEach(async () => {
  // This checked-in fixture has no semicolons inside literals or inline comments.
  const statements = fixtureSql.replace(/^--.*$/gm, '').split(';').map((sql) => sql.trim()).filter(Boolean);
  await env.DB.batch(statements.map((sql) => env.DB.prepare(sql)));
});

describe('PLAN migrations in a local Cloudflare Workers D1 pool', () => {
  it('creates every table and passes D1 integrity checks', async () => {
    const tables = await env.DB.prepare("SELECT name FROM sqlite_master WHERE type='table'").all<{ name: string }>();
    const names = tables.results.map((row) => row.name);
    for (const name of ['users', 'sessions', 'refresh_tokens', 'refresh_rotation_results', 'recovery_codes', 'auth_rate_limits', 'restaurants', 'dishes', 'restaurant_search', 'visits', 'change_sets', 'revisions', 'idempotency_keys', 'mutation_guards']) {
      expect(names).toContain(name);
    }
    expect((await env.DB.prepare('PRAGMA foreign_key_check').all()).results).toEqual([]);
    expect(await env.DB.prepare('PRAGMA quick_check').first('quick_check')).toBe('ok');
  });

  it.each([0, 51, 43.5])('rejects invalid rating_tenths=%s for both entities', async (rating) => {
    for (const table of ['restaurants', 'dishes']) {
      await expect(env.DB.prepare(`UPDATE ${table} SET rating_tenths=?`).bind(rating).run()).rejects.toThrow('CHECK constraint failed');
    }
  });

  it.each(['2026-02-30', '2023-02-29', '2026-13-01', '2026-2-01', '1899-12-31'])('rejects invalid date %s', async (date) => {
    await expect(env.DB.prepare('UPDATE visits SET dining_date=?').bind(date).run()).rejects.toThrow('CHECK constraint failed');
  });

  it('accepts a leap day and multiple visits on that day', async () => {
    expect(await env.DB.prepare("SELECT count(*) AS n FROM visits WHERE dining_date='2024-02-29'").first('n')).toBe(2);
  });

  it.each(['dishes', 'visits', 'restaurant_search', 'refresh_tokens', 'refresh_rotation_results'])('rejects a cross-account composite FK in %s', async (table) => {
    await expect(env.DB.prepare(`UPDATE ${table} SET user_id=?`).bind('00000000-0000-4000-8000-000000000002').run()).rejects.toThrow('FOREIGN KEY constraint failed');
  });

  it('keeps original diff immutable and permits explanation metadata', async () => {
    await expect(env.DB.prepare("UPDATE revisions SET diff_json='[]'").run()).rejects.toThrow('revision core is immutable');
    await expect(env.DB.prepare('UPDATE revisions SET created_at=2').run()).rejects.toThrow('revision core is immutable');
    await env.DB.prepare("UPDATE revisions SET note='Synthetic note', metadata_version=2, metadata_updated_at=2").run();
    expect(await env.DB.prepare('SELECT note FROM revisions').first('note')).toBe('Synthetic note');
  });

  it('rolls back a D1 batch when the CAS changes zero rows', async () => {
    await expect(env.DB.batch([
      env.DB.prepare("UPDATE restaurants SET rating_tenths=44, version=version+1 WHERE id='10000000-0000-4000-8000-000000000001' AND version=999"),
      env.DB.prepare("INSERT INTO mutation_guards VALUES ('cas-zero', changes())"),
    ])).rejects.toThrow('CHECK constraint failed');
    expect(await env.DB.prepare("SELECT version FROM restaurants WHERE id='10000000-0000-4000-8000-000000000001'").first('version')).toBe(1);
    expect(await env.DB.prepare('SELECT count(*) AS n FROM mutation_guards').first('n')).toBe(0);
  });

  it('rolls back successful entity/guard writes if a later revision fails', async () => {
    await expect(env.DB.batch([
      env.DB.prepare("UPDATE restaurants SET rating_tenths=44, version=version+1 WHERE id='10000000-0000-4000-8000-000000000001' AND version=1"),
      env.DB.prepare("INSERT INTO mutation_guards VALUES ('cas-later', changes())"),
      env.DB.prepare("INSERT INTO revisions (id,user_id,change_set_id,restaurant_id,entity_type,entity_id,action,entity_version_before,entity_version_after,diff_json,search_text,metadata_updated_at,created_at) SELECT '60000000-0000-4000-8000-000000000002',user_id,change_set_id,restaurant_id,entity_type,entity_id,'update',1,2,'[]',search_text,2,2 FROM revisions"),
    ])).rejects.toThrow('CHECK constraint failed');
    expect(await env.DB.prepare("SELECT rating_tenths FROM restaurants WHERE id='10000000-0000-4000-8000-000000000001'").first('rating_tenths')).toBe(43);
    expect(await env.DB.prepare('SELECT count(*) AS n FROM mutation_guards').first('n')).toBe(0);
    expect(await env.DB.prepare('SELECT count(*) AS n FROM revisions').first('n')).toBe(1);
  });
});
