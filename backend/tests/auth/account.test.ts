import { applyD1Migrations, env } from 'cloudflare:test';
import { afterEach, beforeAll, describe, expect, it, vi } from 'vitest';
import { account, bindings, request, restaurant } from '../domain/helpers';
import { decodeJwt } from 'jose';
import app from '../../src/index';
import { digest, jwtConfig } from '../../src/auth/config';
import { verifyAccessToken } from '../../src/auth/jwt';
import { normalizeRecoveryCode } from '../../src/auth/recovery';
import * as passwords from '../../src/auth/password';

const password = 'a deliberate strong passphrase';
const userKeys = ['id', 'username', 'display_name', 'version', 'created_at', 'updated_at'].sort();
async function value(response: Response, status = 200) {
  expect(response.status).toBe(status);
  return await response.json() as { data: Record<string, unknown>; error: { code: string; current: Record<string, unknown> } };
}
beforeAll(async () => { await applyD1Migrations(env.DB, env.TEST_MIGRATIONS); });
afterEach(() => vi.restoreAllMocks());

describe('P6 account endpoints against Workers D1', () => {
  it.each([['/me', 'GET', undefined], ['/me', 'PATCH', { expected_version: 1, display_name: 'Name' }],
    ['/me', 'DELETE', { expected_version: 1, password, confirm_username: 'alice' }],
    ['/auth/logout-all', 'POST', { password }], ['/auth/recovery-codes', 'POST', { password }]])('%s %s requires an active access token', async (path, method, payload) => {
    expect((await request(path as string, '', payload, method as string)).status).toBe(401);
  });
  it('GET returns exactly the six public keys and isolates owners', async () => {
    const alice = await account(), bob = await account('bob');
    for (const user of [alice, bob]) {
      const response = await request('/me', user.access_token);
      expect(response.headers.get('Cache-Control')).toBe('no-store');
      const result = (await value(response)).data;
      expect(Object.keys(result).sort()).toEqual(userKeys);
      expect(result).toEqual(user.user);
    }
  });
  it.each(['/auth/logout-all', '/auth/recovery-codes', '/me'])('%s recomputes the same slow hash for wrong and correct passwords', async path => {
    const alice = await account();
    const spy = vi.spyOn(passwords, 'verifyPassword');
    const method = path === '/me' ? 'DELETE' : 'POST';
    const extra = path === '/me' ? { expected_version: 1, confirm_username: 'alice' } : {};
    const wrong = await value(await request(path, alice.access_token, { ...extra, password: 'wrong password' }, method), 401);
    expect(wrong.error.code).toBe('INVALID_CREDENTIALS');
    expect((await request('/me', alice.access_token)).status).toBe(200);
    expect((await request(path, alice.access_token, { ...extra, password }, method)).status).toBe(path === '/me' ? 202 : path.endsWith('logout-all') ? 204 : 200);
    expect(spy).toHaveBeenCalledTimes(2);
    expect(spy.mock.calls[0][1]).toEqual(spy.mock.calls[1][1]);
    expect(spy.mock.calls[0][1].password_iterations).toBe(10000);
    expect(await Promise.all(spy.mock.results.map(r => r.value))).toEqual([false, true]);
  });
  it.each(['/auth/logout-all', '/auth/recovery-codes', '/me'])('%s rolls back when authentication changes after the slow hash', async path => {
    const alice = await account(), bob = await account('bob');
    const before = await env.DB.prepare('SELECT count(*) n FROM recovery_codes WHERE user_id=?').bind(alice.user.id).first('n');
    const verifier = passwords.verifyPassword;
    vi.spyOn(passwords, 'verifyPassword').mockImplementationOnce(async (...args) => {
      const valid = await verifier(...args);
      await env.DB.prepare('UPDATE users SET auth_version=auth_version+1 WHERE id=?').bind(alice.user.id).run();
      return valid;
    });
    const payload = path === '/me' ? { password, expected_version: 1, confirm_username: 'alice' } : { password };
    expect((await value(await request(path, alice.access_token, payload, path === '/me' ? 'DELETE' : 'POST'), 401)).error.code).toBe('SESSION_REVOKED');
    expect(await env.DB.prepare('SELECT deleted_at FROM users WHERE id=?').bind(alice.user.id).first('deleted_at')).toBeNull();
    expect(await env.DB.prepare('SELECT version FROM users WHERE id=?').bind(alice.user.id).first('version')).toBe(1);
    expect(await env.DB.prepare('SELECT count(*) n FROM recovery_codes WHERE user_id=?').bind(alice.user.id).first('n')).toBe(before);
    expect(await env.DB.prepare('SELECT count(*) n FROM recovery_codes WHERE user_id=? AND disabled_at IS NOT NULL').bind(alice.user.id).first('n')).toBe(0);
    expect(await env.DB.prepare('SELECT count(*) n FROM mutation_guards').first('n')).toBe(0);
    expect((await value(await request('/me', bob.access_token))).data).toEqual(bob.user);
  });
  it('logout-all immediately revokes unexpired access and every refresh, without affecting B', async () => {
    const alice = await account(), bob = await account('bob');
    const login = await app.request('/api/v1/auth/login', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ username: 'alice', password, device_name: 'Second device' }) }, bindings);
    const other = (await value(login)).data;
    expect((await request('/auth/logout-all', alice.access_token, { password })).status).toBe(204);
    for (const token of [alice.access_token, other.access_token as string]) {
      const claims = await verifyAccessToken(token, jwtConfig(bindings));
      expect(claims.av).toBe(1);
      expect(decodeJwt(token).exp).toBeGreaterThan(Math.floor(Date.now() / 1000));
      expect((await value(await request('/me', token), 401)).error.code).toBe('SESSION_REVOKED');
    }
    for (const token of [alice.refresh_token, other.refresh_token]) {
      const response = await app.request('/api/v1/auth/refresh', { method: 'POST', headers: { 'Content-Type': 'application/json', 'X-Refresh-Request-Id': crypto.randomUUID() }, body: JSON.stringify({ refresh_token: token }) }, bindings);
      expect(response.status).toBe(401);
    }
    expect(await env.DB.prepare('SELECT auth_version FROM users WHERE id=?').bind(alice.user.id).first('auth_version')).toBe(2);
    expect(await env.DB.prepare("SELECT count(*) n FROM sessions WHERE user_id=? AND revoke_reason='logout_all'").bind(alice.user.id).first('n')).toBe(2);
    expect((await request('/me', bob.access_token)).status).toBe(200);
    expect(await env.DB.prepare('SELECT auth_version FROM users WHERE id=?').bind(bob.user.id).first('auth_version')).toBe(1);
  });
  it('recovery regeneration stores only eight fresh hashes and disables every old code only for A', async () => {
    const alice = await account(), bob = await account('bob');
    const result = (await value(await request('/auth/recovery-codes', alice.access_token, { password }))).data;
    const codes = result.recovery_codes as string[];
    expect(codes).toHaveLength(8); expect(new Set(codes).size).toBe(8);
    expect(codes.filter(code => alice.recovery_codes!.includes(code))).toEqual([]);
    const rows = (await env.DB.prepare('SELECT code_hash,disabled_at,used_at FROM recovery_codes WHERE user_id=?').bind(alice.user.id).all<{ code_hash: string; disabled_at: number | null; used_at: number | null }>()).results;
    expect(rows).toHaveLength(16);
    const oldHashes = await Promise.all(alice.recovery_codes!.map(code => digest(normalizeRecoveryCode(code))));
    const newHashes = await Promise.all(codes.map(code => digest(normalizeRecoveryCode(code))));
    expect(rows.filter(r => r.disabled_at !== null).map(r => r.code_hash).sort()).toEqual(oldHashes.sort());
    expect(rows.filter(r => r.disabled_at === null && r.used_at === null).map(r => r.code_hash).sort()).toEqual(newHashes.sort());
    expect(rows.every(r => /^[a-f0-9]{64}$/.test(r.code_hash))).toBe(true);
    for (const code of codes) expect(JSON.stringify(rows)).not.toContain(code);
    expect(await env.DB.prepare('SELECT count(*) n FROM recovery_codes WHERE user_id=? AND disabled_at IS NULL AND used_at IS NULL').bind(bob.user.id).first('n')).toBe(8);
    const bobHashes = await Promise.all(bob.recovery_codes!.map(code => digest(normalizeRecoveryCode(code))));
    expect((await env.DB.prepare('SELECT code_hash FROM recovery_codes WHERE user_id=? AND disabled_at IS NULL AND used_at IS NULL').bind(bob.user.id).all<{ code_hash: string }>()).results.map(row => row.code_hash).sort()).toEqual(bobHashes.sort());
    expect((await request('/me', bob.access_token)).status).toBe(200);
    // Prove disabled hashes cannot be used by the existing recovery endpoint.
    for (const code of alice.recovery_codes!) {
      // Each code starts a separate rate-limit window; verification and CAS remain real.
      await env.DB.prepare('DELETE FROM auth_rate_limits WHERE window_start<>0').run();
      expect((await value(await request('/auth/recover', '', { username: 'alice', recovery_code: code, new_password: 'another deliberate strong passphrase', device_name: 'Recovery test' }), 401)).error.code).toBe('INVALID_CREDENTIALS');
    }
    await env.DB.prepare('DELETE FROM auth_rate_limits WHERE window_start<>0').run();
    expect((await request('/auth/recover', '', { username: 'alice', recovery_code: codes[0], new_password: 'another deliberate strong passphrase', device_name: 'Recovery test' })).status).toBe(200);
  });
  it('PATCH requires UUID idempotency and rejects username and internal fields strictly', async () => {
    const alice = await account();
    for (const extra of [{ username: 'bob' }, { auth_version: 2 }, { deleted_at: null }]) expect((await request('/me', alice.access_token, { expected_version: 1, display_name: 'New name', ...extra }, 'PATCH')).status).toBe(400);
    for (const key of ['', 'invalid']) expect((await request('/me', alice.access_token, { expected_version: 1, display_name: 'New name' }, 'PATCH', key)).status).toBe(400);
    expect((await value(await request('/me', alice.access_token))).data.version).toBe(1);
  });
  it('PATCH replays identical bytes without incrementing version and keeps B isolated even with the same key', async () => {
    const alice = await account(), bob = await account('bob'), key = crypto.randomUUID();
    const input = { expected_version: 1, display_name: '食记用户' };
    const first = await request('/me', alice.access_token, input, 'PATCH', key);
    expect(first.status).toBe(200); const text = await first.text();
    const retry = await request('/me', alice.access_token, input, 'PATCH', key);
    expect(retry.status).toBe(200); expect(await retry.text()).toBe(text);
    expect(Object.keys(JSON.parse(text).data).sort()).toEqual(userKeys);
    expect((await value(await request('/me', alice.access_token))).data.version).toBe(2);
    expect((await value(await request('/me', bob.access_token))).data).toEqual(bob.user);
    expect((await value(await request('/me', bob.access_token, input, 'PATCH', key))).data.id).toBe(bob.user.id);
    expect((await value(await request('/me', alice.access_token, { ...input, display_name: 'Other' }, 'PATCH', key), 409)).error.code).toBe('IDEMPOTENCY_CONFLICT');
  });
  it('PATCH concurrent same key commits once and distinct keys return public current on CAS conflict', async () => {
    const alice = await account(), key = crypto.randomUUID();
    const input = { expected_version: 1, display_name: 'Changed' };
    const responses = await Promise.all(Array.from({ length: 3 }, () => request('/me', alice.access_token, input, 'PATCH', key)));
    expect(responses.map(r => r.status)).toEqual([200, 200, 200]);
    expect(new Set(await Promise.all(responses.map(r => r.text()))).size).toBe(1);
    const conflict = (await value(await request('/me', alice.access_token, input, 'PATCH'), 409)).error;
    expect(conflict.code).toBe('VERSION_CONFLICT');
    expect(Object.keys(conflict.current).sort()).toEqual(userKeys); expect(conflict.current.version).toBe(2);
  });
  it('DELETE rejects a mismatched confirmation and a stale version without deleting the user', async () => {
    const alice = await account();
    expect((await request('/me', alice.access_token, { password, expected_version: 1, confirm_username: 'bob' }, 'DELETE')).status).toBe(400);
    const conflict = (await value(await request('/me', alice.access_token, { password, expected_version: 2, confirm_username: 'alice' }, 'DELETE'), 409)).error;
    expect(conflict.code).toBe('VERSION_CONFLICT'); expect(Object.keys(conflict.current).sort()).toEqual(userKeys);
    expect((await request('/me', alice.access_token)).status).toBe(200);
  });
  it('DELETE immediately revokes sessions, retains username and data for 30 days, and never enters idempotency', async () => {
    const alice = await account(), bob = await account('bob');
    const record = await restaurant(alice.access_token);
    await value(await request(`/restaurants/${record.entity.id}/dishes`, alice.access_token, { id: crypto.randomUUID(), name: 'Retained dish' }), 201);
    await value(await request(`/restaurants/${record.entity.id}/visits`, alice.access_token, { id: crypto.randomUUID(), dining_date: '2026-10-31' }), 201);
    const tables = ['restaurants', 'dishes', 'visits', 'restaurant_search', 'revisions', 'change_sets'];
    const retained = await Promise.all(tables.map(table => env.DB.prepare(`SELECT count(*) n FROM ${table} WHERE user_id=?`).bind(alice.user.id).first('n')));
    expect(retained.every(count => Number(count) > 0)).toBe(true);
    const before = await env.DB.prepare('SELECT count(*) n FROM idempotency_keys').first('n');
    const result = (await value(await request('/me', alice.access_token, { password, expected_version: 1, confirm_username: 'alice' }, 'DELETE'), 202)).data;
    expect(Date.parse(result.purge_after as string) - Date.parse(result.deleted_at as string)).toBe(30 * 86400 * 1000);
    expect(await env.DB.prepare('SELECT deleted_at FROM users WHERE id=?').bind(alice.user.id).first('deleted_at')).toBe(Date.parse(result.deleted_at as string) / 1000);
    expect(await env.DB.prepare('SELECT auth_version FROM users WHERE id=?').bind(alice.user.id).first('auth_version')).toBe(2);
    expect(await env.DB.prepare('SELECT revoke_reason FROM sessions WHERE user_id=?').bind(alice.user.id).first('revoke_reason')).toBe('account_delete');
    await verifyAccessToken(alice.access_token, jwtConfig(bindings));
    expect(decodeJwt(alice.access_token).exp).toBeGreaterThan(Math.floor(Date.now() / 1000));
    expect((await value(await request('/me', alice.access_token), 401)).error.code).toBe('SESSION_REVOKED');
    const refresh = await app.request('/api/v1/auth/refresh', { method: 'POST', headers: { 'Content-Type': 'application/json', 'X-Refresh-Request-Id': crypto.randomUUID() }, body: JSON.stringify({ refresh_token: alice.refresh_token }) }, bindings);
    expect(refresh.status).toBe(401);
    expect((await value(await request('/auth/register', '', { username: 'alice', password, device_name: 'Retained name' }), 409)).error.code).toBe('USERNAME_TAKEN');
    expect((await request('/auth/login', '', { username: 'alice', password, device_name: 'Deleted account' })).status).toBe(401);
    expect(await env.DB.prepare('SELECT count(*) n FROM idempotency_keys').first('n')).toBe(before);
    expect(await Promise.all(tables.map(table => env.DB.prepare(`SELECT count(*) n FROM ${table} WHERE user_id=?`).bind(alice.user.id).first('n')))).toEqual(retained);
    expect((await value(await request('/me', bob.access_token))).data).toEqual(bob.user);
  });
});
