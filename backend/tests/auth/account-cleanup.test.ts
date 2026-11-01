import { applyD1Migrations, env } from 'cloudflare:test';
import { beforeAll, describe, expect, it } from 'vitest';
import { cleanupDeletedAccounts } from '../../src/auth/account-cleanup';
import { cleanupDomain } from '../../src/domain/cleanup';
import { digest } from '../../src/auth/config';
import type { AuthResult } from '../../src/auth/session';
import { account, batchDB, request, restaurant } from '../domain/helpers';

const day = 86400;
const children = ['sessions', 'refresh_tokens', 'refresh_rotation_results', 'recovery_codes', 'restaurants',
  'dishes', 'visits', 'restaurant_search', 'revisions', 'change_sets', 'idempotency_keys'];
const credentialTables = ['refresh_rotation_results', 'refresh_tokens', 'sessions', 'recovery_codes', 'idempotency_keys'];
beforeAll(async () => { await applyD1Migrations(env.DB, env.TEST_MIGRATIONS); });

async function snapshot(userId: string) {
  const entries = await Promise.all(['users', ...children].map(async table => {
    const rows = (await env.DB.prepare(`SELECT * FROM ${table} WHERE ${table === 'users' ? 'id' : 'user_id'}=?`).bind(userId).all()).results;
    // Compare complete records without putting authentication material in failures.
    return [table, { count: rows.length, checksum: await digest(rows.map(row => JSON.stringify(row)).sort().join('\n')) }] as const;
  }));
  return Object.fromEntries(entries);
}
async function fixture(large = true) {
  const alice = await account(), bob = await account('bob');
  const parent = await restaurant(alice.access_token);
  expect((await request(`/restaurants/${parent.entity.id}/dishes`, alice.access_token,
    { id: crypto.randomUUID(), name: 'Retained dish' })).status).toBe(201);
  expect((await request(`/restaurants/${parent.entity.id}/visits`, alice.access_token,
    { id: crypto.randomUUID(), dining_date: '2026-10-31' })).status).toBe(201);
  await restaurant(bob.access_token);
  const now = Math.floor(Date.now() / 1000);
  if (large) {
    for (let index = 0; index < 23; index++) {
      const session = crypto.randomUUID(), token = crypto.randomUUID();
      await env.DB.batch([
        env.DB.prepare("INSERT INTO sessions (id,user_id,auth_version,device_name,created_at,last_refreshed_at,expires_at) VALUES (?,?,1,'Cleanup fixture',?,?,?)")
          .bind(session, alice.user.id, now, now, now + 60),
        env.DB.prepare('INSERT INTO refresh_tokens (id,user_id,session_id,token_hash,created_at,expires_at) VALUES (?,?,?,?,?,?)')
          .bind(token, alice.user.id, session, await digest(`cleanup-token-${token}`), now, now + 60),
        env.DB.prepare("INSERT INTO refresh_rotation_results (old_token_id,user_id,request_id,encryption_key_version,nonce,ciphertext,created_at,expires_at) VALUES (?,?,?,'fixture','AAAAAAAAAAAAAAAA','public synthetic ciphertext',?,?)")
          .bind(token, alice.user.id, crypto.randomUUID(), now, now + 60),
        env.DB.prepare('INSERT INTO recovery_codes (id,user_id,code_hash,created_at) VALUES (?,?,?,?)')
          .bind(crypto.randomUUID(), alice.user.id, await digest(`cleanup-code-${token}`), now),
        env.DB.prepare("INSERT INTO idempotency_keys VALUES (?,?,'PATCH','/cleanup-fixture',?,200,'{}',?,?)")
          .bind(alice.user.id, crypto.randomUUID(), await digest(`cleanup-request-${token}`), now, now + 60),
      ]);
    }
  }
  // Keep caches unexpired so these tests isolate account retention from the
  // independent cache-expiry janitor, including the other live owner's cache.
  await env.DB.prepare('UPDATE idempotency_keys SET expires_at=?').bind((now + 40 * day) * 1000).run();
  const deletion = await request('/me', alice.access_token,
    { expected_version: 1, password: 'a deliberate strong passphrase', confirm_username: 'alice' }, 'DELETE');
  expect(deletion.status).toBe(202);
  const deletedAt = await env.DB.prepare('SELECT deleted_at FROM users WHERE id=?').bind(alice.user.id).first<number>('deleted_at');
  return { alice, bob, expiry: deletedAt! + 30 * day };
}

describe('bounded cleanup after the account retention period', () => {
  it('preserves every owned record and the reserved username until the exact boundary', async () => {
    const { alice, bob, expiry } = await fixture();
    const beforeA = await snapshot(alice.user.id), beforeB = await snapshot(bob.user.id);
    await cleanupDomain(env.DB, (expiry - 1) * 1000);
    await cleanupDeletedAccounts(env.DB, expiry - 1);
    expect(await snapshot(alice.user.id)).toEqual(beforeA);
    expect(await snapshot(bob.user.id)).toEqual(beforeB);
    const response = await request('/auth/register', '',
      { username: 'alice', password: 'a deliberate strong passphrase', device_name: 'Retention fixture' });
    expect(response.status).toBe(409);
    expect((await response.json() as { error: { code: string } }).error.code).toBe('USERNAME_TAKEN');
  });

  it('drains at most 20 rows per credential stage, guards the parent, and eventually purges only the expired owner', async () => {
    const { alice, bob, expiry } = await fixture();
    const beforeA = await snapshot(alice.user.id), beforeB = await snapshot(bob.user.id);
    await cleanupDeletedAccounts(env.DB, expiry);
    const first = await snapshot(alice.user.id);
    for (const table of credentialTables) {
      const removed = beforeA[table].count - first[table].count;
      expect(removed).toBe(20);
    }
    // No large ON DELETE CASCADE: the user and every domain row still exist.
    for (const table of ['users', 'restaurants', 'dishes', 'visits', 'restaurant_search', 'revisions', 'change_sets']) {
      expect(first[table]).toEqual(beforeA[table]);
    }
    expect(await snapshot(bob.user.id)).toEqual(beforeB);
    for (let round = 0; round < 10 && (await snapshot(alice.user.id)).users.count; round++) {
      await cleanupDomain(env.DB, expiry * 1000);
      await cleanupDeletedAccounts(env.DB, expiry);
    }
    const purged = await snapshot(alice.user.id);
    for (const table of ['users', ...children]) expect(purged[table].count).toBe(0);
    expect(await snapshot(bob.user.id)).toEqual(beforeB);
    expect((await env.DB.prepare('PRAGMA foreign_key_check').all()).results).toEqual([]);
  });

  it('allows the same username after physical purge with a fresh ID and no inherited data', async () => {
    const { alice, expiry } = await fixture(false);
    await cleanupDomain(env.DB, expiry * 1000);
    await cleanupDeletedAccounts(env.DB, expiry);
    expect((await snapshot(alice.user.id)).users.count).toBe(0);
    // A new client IP avoids the independent one-registration-per-minute limit;
    // no production rate budget is disabled or changed by this lifecycle test.
    const registration = await request('/auth/register', '',
      { username: 'alice', password: 'a deliberate strong passphrase', device_name: 'New owner' });
    expect(registration.status).toBe(201);
    const replacement = (await registration.json() as { data: AuthResult }).data;
    expect(replacement.user.id).not.toBe(alice.user.id);
    const response = await request('/restaurants', replacement.access_token);
    expect(response.status).toBe(200);
    expect((await response.json() as { data: { items: unknown[] } }).data.items).toEqual([]);
    expect((await request('/me', alice.access_token)).status).toBe(401);
  });

  it('rolls back a late cleanup batch failure without partial credential deletion', async () => {
    const { alice, bob, expiry } = await fixture();
    const beforeA = await snapshot(alice.user.id), beforeB = await snapshot(bob.user.id);
    const db = batchDB(statements => env.DB.batch([
      ...statements.slice(0, 2),
      env.DB.prepare("INSERT INTO mutation_guards VALUES ('cleanup-failure',0)"),
      ...statements.slice(2),
    ]));
    await expect(cleanupDeletedAccounts(db, expiry)).rejects.toThrow(/CHECK constraint failed/);
    expect(await snapshot(alice.user.id)).toEqual(beforeA);
    expect(await snapshot(bob.user.id)).toEqual(beforeB);
    expect(await env.DB.prepare('SELECT count(*) n FROM mutation_guards').first('n')).toBe(0);
  });
});
