import { applyD1Migrations, env } from 'cloudflare:test';
import { beforeAll, beforeEach, afterEach, describe, expect, it, vi } from 'vitest';
import app from '../../src/index';
import type { Bindings } from '../../src/env';
import type { AuthResult } from '../../src/auth/session';
import { base64UrlEncode } from '../../src/auth/encoding';
import { failureKey } from '../../src/auth/rate-limit';
import * as limits from '../../src/auth/rate-limit';
import * as passwords from '../../src/auth/password';
import * as jwt from '../../src/auth/jwt';
import { digest, privateHash, passwordConfig, jwtConfig } from '../../src/auth/config';
import { normalizeRecoveryCode } from '../../src/auth/recovery';
import { lookupRefresh } from '../../src/auth/session';

// Clearly public sequential-byte fixtures. No production credentials.
const key = (offset: number) => base64UrlEncode(Uint8Array.from({ length: 32 }, (_, i) => (i + offset) % 256));
const bindings: Bindings = { DB: env.DB, JWT_HS256_KEYS: JSON.stringify({ v1: key(0) }), PASSWORD_PEPPERS: JSON.stringify({ v1: key(32), old: key(128) }), REFRESH_RETRY_KEYS: JSON.stringify({ v1: key(64) }), RATE_LIMIT_HMAC_KEY: key(96),
  AUTH_ISSUER: 'https://dining.alanvampuke.top', AUTH_AUDIENCE: 'dining-log-android', JWT_ACTIVE_KID: 'v1', PASSWORD_ACTIVE_PEPPER: 'v1', PASSWORD_ITERATIONS: '10000', REFRESH_RETRY_KEY_ID: 'v1', REGISTRATION_ENABLED: 'true' };
const password = 'a deliberate strong passphrase';
const newPassword = 'another deliberate strong passphrase';
let ipCounter = 0;
function request(path: string, payload?: unknown, headers: Record<string, string> = {}, method?: string) {
  return app.request(`/api/v1${path}`, { method: method ?? (payload === undefined ? 'GET' : 'POST'), headers: { 'Content-Type': 'application/json', 'CF-Connecting-IP': `192.0.2.${++ipCounter}`, ...headers }, body: payload === undefined ? undefined : JSON.stringify(payload) }, bindings);
}
const registerPayload = (username = 'alice') => ({ username, password, device_name: 'Test device' });
async function register(username = 'alice') { const response = await request('/auth/register', registerPayload(username)); expect(response.status).toBe(201); return (await response.json() as { data: AuthResult }).data; }
const bearer = (token: string) => ({ Authorization: `Bearer ${token}` });
const refresh = (token: string, requestId = crypto.randomUUID()) => request('/auth/refresh', { refresh_token: token }, { 'X-Refresh-Request-Id': requestId });
async function error(response: Response) { return (await response.json() as { error: { code: string; message: string; field_errors: object } }).error; }
async function clearWindows() { await env.DB.prepare('DELETE FROM auth_rate_limits WHERE window_start<>0').run(); }
async function assertInvalidated(auth: AuthResult) {
  expect((await request('/auth/session', undefined, bearer(auth.access_token))).status).toBe(401);
  expect((await refresh(auth.refresh_token)).status).toBe(401);
}
beforeAll(async () => { await applyD1Migrations(env.DB, env.TEST_MIGRATIONS); });
beforeEach(() => { ipCounter = 0; });
afterEach(() => { vi.restoreAllMocks(); });

describe('P2 authentication against Workers D1', () => {
  it('S1 concurrent NFKC/case registrations create exactly one account', async () => {
    const responses = await Promise.all(['Alice', 'alice', 'ALICE', ' Ａｌｉｃｅ '].map(name => request('/auth/register', registerPayload(name))));
    expect(responses.map(r => r.status).sort()).toEqual([201, 409, 409, 409]);
    expect(await env.DB.prepare('SELECT count(*) n FROM users').first('n')).toBe(1);
    expect(await env.DB.prepare('SELECT username FROM users').first('username')).toBe('alice');
    expect(await env.DB.prepare('SELECT count(*) n FROM sessions').first('n')).toBe(1);
    expect(await env.DB.prepare('SELECT count(*) n FROM recovery_codes').first('n')).toBe(8);
  });
  it('S1 username validation and strong Unicode password policy are shared', async () => {
    for (const username of ['ab', '1alice', '中文姓名', 'a'.repeat(33)]) expect((await request('/auth/register', registerPayload(username))).status).toBe(400);
    for (const value of ['password1234', '123456789012', ' '.repeat(12), 'x'.repeat(129), 'good password\0invalid']) expect((await request('/auth/register', { ...registerPayload(), password: value })).status).toBe(400);
    expect((await request('/auth/register', { ...registerPayload(), password: '🍜'.repeat(12) })).status).toBe(201);
  });
  it('S2 success, wrong password and unknown username consume all login windows with identical errors', async () => {
    const auth = await register();
    const correct = await request('/auth/login', { ...registerPayload('ALICE') }); expect(correct.status).toBe(200);
    const spy = vi.spyOn(passwords, 'verifyPassword');
    const wrong = await request('/auth/login', { ...registerPayload(), password: 'wrong password' });
    const unknown = await request('/auth/login', { ...registerPayload('nobody'), password: 'wrong password' });
    expect(wrong.status).toBe(401); expect(unknown.status).toBe(401);
    expect(await error(wrong)).toEqual(await error(unknown));
    expect(spy).toHaveBeenCalledTimes(2); expect(spy.mock.calls[1][1].password_iterations).toBe(10000);
    const hash = await privateHash(bindings, 'login_account:60:alice');
    expect(await env.DB.prepare('SELECT attempts FROM auth_rate_limits WHERE key_hash=? AND window_start<>0').bind(hash).first('attempts')).toBe(2);
    expect((await request('/auth/session', undefined, bearer(auth.access_token))).status).toBe(200);
  });
  it('S2 minute limits run before PBKDF2 and carry Retry-After', async () => {
    await register(); const spy = vi.spyOn(passwords, 'verifyPassword');
    for (let i = 0; i < 3; i++) expect((await request('/auth/login', registerPayload())).status).toBe(200);
    const response = await request('/auth/login', registerPayload()); expect(response.status).toBe(429);
    expect(Number(response.headers.get('Retry-After'))).toBeGreaterThan(0); expect(spy).toHaveBeenCalledTimes(3);
    expect((await error(response)).code).toBe('RATE_LIMITED');
  });
  it('S2 fifth consecutive failure locks known and unknown users for exactly fifteen minutes', async () => {
    await register();
    for (const username of ['alice', 'nobody']) {
      for (let i = 0; i < 5; i++) { await clearWindows(); expect((await request('/auth/login', { ...registerPayload(username), password: 'wrong password' })).status).toBe(401); }
      await clearWindows(); const spy = vi.spyOn(passwords, 'verifyPassword');
      const response = await request('/auth/login', registerPayload(username)); expect(response.status).toBe(429); expect(spy).not.toHaveBeenCalled(); spy.mockRestore();
      const key = await failureKey(bindings, username);
      const row = (await env.DB.prepare('SELECT attempts,expires_at FROM auth_rate_limits WHERE key_hash=? AND window_start=0').bind(key).first<{ attempts: number; expires_at: number }>())!;
      expect(row.attempts).toBe(5); expect(row.expires_at - Math.floor(Date.now() / 1000)).toBeGreaterThanOrEqual(899); expect(row.expires_at - Math.floor(Date.now() / 1000)).toBeLessThanOrEqual(900);
      await env.DB.prepare('UPDATE auth_rate_limits SET expires_at=? WHERE key_hash=? AND window_start=0').bind(Math.floor(Date.now() / 1000) - 1, key).run();
      await clearWindows(); expect((await request('/auth/login', { ...registerPayload(username), password: 'wrong password' })).status).toBe(401);
      expect(await env.DB.prepare('SELECT attempts FROM auth_rate_limits WHERE key_hash=? AND window_start=0').bind(key).first('attempts')).toBe(1);
    }
  });
  it('S2 success clears consecutive failures and registration respects IP minute/hour/day budgets', async () => {
    await register(); await request('/auth/login', { ...registerPayload(), password: 'wrong password' }); await request('/auth/login', registerPayload());
    expect(await env.DB.prepare('SELECT count(*) n FROM auth_rate_limits WHERE window_start=0').first('n')).toBe(0);
    const headers = { 'CF-Connecting-IP': '198.51.100.1' };
    expect((await request('/auth/register', registerPayload('bob'), headers)).status).toBe(201);
    expect((await request('/auth/register', registerPayload('carol'), headers)).status).toBe(429);
    expect((await env.DB.prepare("SELECT count(*) n FROM auth_rate_limits WHERE scope='register_ip'").first<number>('n'))!).toBeGreaterThanOrEqual(6);
  });
  it('S2 login hour/IP windows and recovery limits are independent of login lock', async () => {
    const auth = await register();
    const start = Math.floor(Date.now() / 3600000) * 3600;
    const hash = await privateHash(bindings, 'login_account:3600:alice');
    await env.DB.prepare("INSERT INTO auth_rate_limits VALUES ('login_account',?,?,10,?)").bind(hash, start, start + 3600).run();
    expect((await request('/auth/login', registerPayload())).status).toBe(429);
    const fk = await failureKey(bindings, 'alice'); await env.DB.prepare("INSERT INTO auth_rate_limits VALUES ('login_account',?,0,5,?)").bind(fk, Math.floor(Date.now() / 1000) + 900).run();
    const response = await request('/auth/recover', { username: 'alice', recovery_code: auth.recovery_codes![0], new_password: newPassword, device_name: 'Recovered' }); expect(response.status).toBe(200);
    expect(await env.DB.prepare('SELECT count(*) n FROM auth_rate_limits WHERE window_start=0').first('n')).toBe(0);
    expect((await request('/auth/recover', { username: 'alice', recovery_code: 'invalid', new_password: newPassword, device_name: 'Recovered' })).status).toBe(429);
  });
  it('S3 same request-id lost-response retry returns byte-identical encrypted rotation result', async () => {
    const auth = await register(); const id = crypto.randomUUID();
    const first = await refresh(auth.refresh_token, id), retry = await refresh(auth.refresh_token, id);
    expect(first.status).toBe(200); expect(retry.status).toBe(200);
    const firstText = await first.text(); expect(await retry.text()).toBe(firstText);
    const next = (JSON.parse(firstText) as { data: AuthResult }).data;
    expect(next.refresh_token).not.toBe(auth.refresh_token);
    const stored = await env.DB.prepare('SELECT * FROM refresh_rotation_results').first();
    expect(JSON.stringify(stored)).not.toContain(next.refresh_token); expect(JSON.stringify(stored)).not.toContain(next.access_token);
    expect((await lookupRefresh(env.DB, auth.refresh_token))!.consumed_request_id).toBe(id);
  });
  it('S3 concurrent same request-id rotations create one child and replay the winner', async () => {
    const auth = await register(), id = crypto.randomUUID();
    const responses = await Promise.all(Array.from({ length: 4 }, () => refresh(auth.refresh_token, id)));
    expect(responses.map(r => r.status)).toEqual([200, 200, 200, 200]);
    const values = await Promise.all(responses.map(r => r.text())); expect(new Set(values).size).toBe(1);
    expect(await env.DB.prepare('SELECT count(*) n FROM refresh_tokens WHERE parent_token_id IS NOT NULL').first('n')).toBe(1);
    expect(await env.DB.prepare('SELECT count(*) n FROM mutation_guards').first('n')).toBe(0);
  });
  it('S4 cross request-id old-token reuse revokes the entire session immediately', async () => {
    const auth = await register(); const first = await refresh(auth.refresh_token); expect(first.status).toBe(200);
    const next = (await first.json() as { data: AuthResult }).data;
    const reuse = await refresh(auth.refresh_token); expect(reuse.status).toBe(401); expect((await error(reuse)).code).toBe('REFRESH_REUSED');
    await assertInvalidated(auth); await assertInvalidated(next);
    expect(await env.DB.prepare('SELECT revoke_reason FROM sessions').first('revoke_reason')).toBe('token_reuse');
  });
  it('S4 expired replay window revokes session even with the original request-id', async () => {
    const auth = await register(), id = crypto.randomUUID(); await refresh(auth.refresh_token, id);
    await env.DB.prepare('UPDATE refresh_rotation_results SET expires_at=created_at-1,created_at=created_at-121').run();
    const response = await refresh(auth.refresh_token, id); expect(response.status).toBe(401); expect((await error(response)).code).toBe('REFRESH_REUSED');
  });
  it('S5 password change invalidates all old access/refresh within the same second and creates current session', async () => {
    const auth = await register(); const login = await request('/auth/login', registerPayload()); const other = (await login.json() as { data: AuthResult }).data;
    const response = await request('/auth/password', { current_password: password, new_password: newPassword, device_name: 'Changed' }, bearer(auth.access_token)); expect(response.status).toBe(200);
    const changed = (await response.json() as { data: AuthResult }).data;
    await assertInvalidated(auth); await assertInvalidated(other);
    expect((await request('/auth/session', undefined, bearer(changed.access_token))).status).toBe(200);
    expect(await env.DB.prepare('SELECT auth_version FROM users').first('auth_version')).toBe(2);
    await clearWindows(); expect((await request('/auth/login', registerPayload())).status).toBe(401);
    await clearWindows(); expect((await request('/auth/login', { ...registerPayload(), password: newPassword })).status).toBe(200);
  });
  it('S5 failed reauth retains current session and uses the same lock/window budget', async () => {
    const auth = await register();
    const response = await request('/auth/password', { current_password: 'wrong password', new_password: newPassword, device_name: 'Changed' }, bearer(auth.access_token)); expect(response.status).toBe(401);
    expect((await request('/auth/session', undefined, bearer(auth.access_token))).status).toBe(200);
    expect(await env.DB.prepare('SELECT attempts FROM auth_rate_limits WHERE window_start=0').first('attempts')).toBe(1);
  });
  it('S5 recovery invalidates every old session/token and stores only recovery hashes', async () => {
    const auth = await register();
    const response = await request('/auth/recover', { username: 'ALICE', recovery_code: auth.recovery_codes![0].toLowerCase().replace(/-/g, ' '), new_password: newPassword, device_name: 'Recovered' }); expect(response.status).toBe(200);
    const recovered = (await response.json() as { data: AuthResult }).data;
    await assertInvalidated(auth); expect((await request('/auth/session', undefined, bearer(recovered.access_token))).status).toBe(200);
    expect(recovered.recovery_codes).toHaveLength(8);
    const rows = (await env.DB.prepare('SELECT code_hash FROM recovery_codes').all<{ code_hash: string }>()).results;
    expect(rows).toHaveLength(16); expect(rows.every(row => /^[a-f0-9]{64}$/.test(row.code_hash))).toBe(true);
    for (const code of recovered.recovery_codes!) expect(rows.map(r => r.code_hash)).toContain(await digest(normalizeRecoveryCode(code)));
  });
  it('S6 recovery CAS race commits exactly one new password/session/code set', async () => {
    const auth = await register();
    // Isolate the CAS race: normal 1/min account limit is covered separately.
    // This test-only spy admits both overlapping transactions; no production bypass exists.
    vi.spyOn(limits, 'consumeLimits').mockResolvedValue();
    const payload = { username: 'alice', recovery_code: auth.recovery_codes![0], new_password: newPassword, device_name: 'Recovered' };
    const responses = await Promise.all([request('/auth/recover', payload), request('/auth/recover', { ...payload, new_password: 'a different racing strong password' })]);
    expect(responses.map(r => r.status).sort()).toEqual([200, 401]);
    expect(await env.DB.prepare('SELECT count(*) n FROM recovery_codes WHERE used_at IS NOT NULL').first('n')).toBe(1);
    expect(await env.DB.prepare('SELECT count(*) n FROM recovery_codes WHERE disabled_at IS NULL AND used_at IS NULL').first('n')).toBe(8);
    expect(await env.DB.prepare('SELECT count(*) n FROM sessions WHERE revoked_at IS NULL').first('n')).toBe(1);
    expect(await env.DB.prepare('SELECT auth_version FROM users').first('auth_version')).toBe(2);
    expect(await env.DB.prepare('SELECT count(*) n FROM mutation_guards').first('n')).toBe(0);
    for (const code of auth.recovery_codes!) expect((await request('/auth/recover', { ...payload, recovery_code: code })).status).toBe(401);
  });
  it('S5 password-change racing with slow login cannot create an old-version session', async () => {
    const auth = await register();
    const original = passwords.verifyPassword;
    let release!: () => void, started!: () => void;
    const gate = new Promise<void>(resolve => { release = resolve; }), ready = new Promise<void>(resolve => { started = resolve; });
    vi.spyOn(passwords, 'verifyPassword').mockImplementationOnce(async (...args) => { const result = await original(...args); started(); await gate; return result; });
    const login = request('/auth/login', registerPayload()); await ready;
    const change = await request('/auth/password', { current_password: password, new_password: newPassword, device_name: 'Changed' }, bearer(auth.access_token)); expect(change.status).toBe(200);
    release(); expect((await login).status).toBe(401);
    expect(await env.DB.prepare('SELECT count(*) n FROM sessions WHERE revoked_at IS NULL').first('n')).toBe(1);
  });
  it('session listing/revocation is owner scoped and logout accepts expired access', async () => {
    const alice = await register(), bob = await register('bob');
    expect((await request(`/auth/sessions/${bob.session_id}`, undefined, bearer(alice.access_token), 'DELETE')).status).toBe(404);
    const listed = await request('/auth/sessions', undefined, bearer(alice.access_token));
    const items = (await listed.json() as { data: { items: { id: string; is_current: boolean }[] } }).data.items;
    expect(items).toEqual([expect.objectContaining({ id: alice.session_id, is_current: true })]);
    expect((await request('/auth/logout', { refresh_token: alice.refresh_token }, { Authorization: 'Bearer expired' })).status).toBe(204);
    expect((await request('/auth/logout', { refresh_token: alice.refresh_token })).status).toBe(204);
    expect((await request('/auth/logout', { refresh_token: 'invalid' })).status).toBe(204);
    await assertInvalidated(alice); expect((await request('/auth/session', undefined, bearer(bob.access_token))).status).toBe(200);
  });
  it('auth idempotency replays exactly, conflicts on changed payload and never stores plaintext secrets', async () => {
    const id = crypto.randomUUID(), headers = { 'Idempotency-Key': id };
    const first = await request('/auth/register', registerPayload(), headers); expect(first.status).toBe(201); const value = await first.text();
    const retry = await request('/auth/register', registerPayload(), headers); expect(retry.status).toBe(201); expect(await retry.text()).toBe(value);
    expect((await request('/auth/register', { ...registerPayload(), password: newPassword }, headers)).status).toBe(409);
    const row = await env.DB.prepare('SELECT response_json,request_hash FROM idempotency_keys').first();
    const auth = (JSON.parse(value) as { data: AuthResult }).data;
    for (const secret of [password, auth.access_token, auth.refresh_token, ...auth.recovery_codes!]) expect(JSON.stringify(row)).not.toContain(secret);
    const changeHeaders = { ...bearer(auth.access_token), 'Idempotency-Key': crypto.randomUUID() };
    const payload = { current_password: password, new_password: newPassword, device_name: 'Changed' };
    const change = await request('/auth/password', payload, changeHeaders); expect(change.status).toBe(200); const changed = await change.text();
    const changeRetry = await request('/auth/password', payload, changeHeaders); expect(changeRetry.status).toBe(200); expect(await changeRetry.text()).toBe(changed);
    await assertInvalidated(auth);
  });
  it('strict JSON schemas reject unknown fields, missing refresh id, oversized bodies and media types', async () => {
    expect((await request('/auth/register', { ...registerPayload(), owner: 'bad' })).status).toBe(400);
    expect((await request('/auth/refresh', { refresh_token: 'bad' })).status).toBe(400);
    expect((await app.request('/api/v1/auth/login', { method: 'POST', body: 'x'.repeat(65537), headers: { 'Content-Type': 'application/json' } }, bindings)).status).toBe(413);
    expect((await app.request('/api/v1/auth/login', { method: 'POST', body: '{}' }, bindings)).status).toBe(415);
    const response = await request('/auth/login', registerPayload()); expect(response.headers.get('Cache-Control')).toBe('no-store');
  });
  it('password rows contain salted PBKDF2 10000 plus pepper, never plaintext', async () => {
    await register(); await register('bob');
    const rows = (await env.DB.prepare('SELECT * FROM users').all<import('../../src/auth/session').UserRow>()).results;
    expect(rows[0].password_hash).not.toBe(password); expect(rows[0].password_hash).not.toBe(rows[1].password_hash);
    expect(rows[0].password_salt).not.toBe(rows[1].password_salt);
    for (const row of rows) {
      expect(row.password_iterations).toBe(10000); expect(row.pepper_version).toBe('v1');
      expect(await passwords.verifyPassword(password, row, passwordConfig(bindings).peppers)).toBe(true);
      expect(await passwords.verifyPassword(newPassword, row, passwordConfig(bindings).peppers)).toBe(false);
    }
  });
  it('concurrent login/recovery idempotency commits one session and conflicts on changed payload', async () => {
    const auth = await register(); const headers = { 'Idempotency-Key': crypto.randomUUID() };
    const logins = await Promise.all([request('/auth/login', registerPayload(), headers), request('/auth/login', registerPayload(), headers)]);
    expect(logins.map(r => r.status)).toEqual([200, 200]); expect(await logins[0].text()).toBe(await logins[1].text());
    expect(await env.DB.prepare('SELECT count(*) n FROM sessions').first('n')).toBe(2);
    expect((await request('/auth/login', { ...registerPayload(), device_name: 'Another' }, headers)).status).toBe(409);
    const recoverHeaders = { 'Idempotency-Key': crypto.randomUUID() };
    const payload = { username: 'alice', recovery_code: auth.recovery_codes![0], new_password: newPassword, device_name: 'Recovered' };
    const first = await request('/auth/recover', payload, recoverHeaders); expect(first.status).toBe(200); const text = await first.text();
    const retry = await request('/auth/recover', payload, recoverHeaders); expect(retry.status).toBe(200); expect(await retry.text()).toBe(text);
    expect((await request('/auth/recover', { ...payload, recovery_code: auth.recovery_codes![1] }, recoverHeaders)).status).toBe(409);
    expect(await env.DB.prepare('SELECT count(*) n FROM sessions WHERE revoked_at IS NULL').first('n')).toBe(1);
  });
  it('DELETE own session and logout idempotent retries return empty 204 after revocation', async () => {
    const auth = await register(), headers = { ...bearer(auth.access_token), 'Idempotency-Key': crypto.randomUUID() };
    for (let i = 0; i < 2; i++) { const response = await request(`/auth/sessions/${auth.session_id}`, undefined, headers, 'DELETE'); expect(response.status).toBe(204); expect(await response.text()).toBe(''); }
    const logoutHeaders = { 'Idempotency-Key': crypto.randomUUID() };
    for (let i = 0; i < 2; i++) { const response = await request('/auth/logout', { refresh_token: auth.refresh_token }, logoutHeaders); expect(response.status).toBe(204); expect(await response.text()).toBe(''); }
  });
  it('JWT owner/version and refresh idle/absolute expiration are enforced', async () => {
    const alice = await register(), bob = await register('bob');
    const forgedOwner = await jwt.signAccessToken({ sub: bob.user.id, sid: alice.session_id, av: 1 }, jwtConfig(bindings));
    expect((await request('/auth/session', undefined, bearer(forgedOwner))).status).toBe(401);
    const token = (await lookupRefresh(env.DB, alice.refresh_token))!;
    await env.DB.prepare('UPDATE refresh_tokens SET created_at=?,expires_at=? WHERE id=?').bind(1, Math.floor(Date.now() / 1000) - 1, token.id).run();
    expect((await refresh(alice.refresh_token)).status).toBe(401);
    await env.DB.prepare('UPDATE sessions SET created_at=1,last_refreshed_at=1,expires_at=? WHERE id=?').bind(Math.floor(Date.now() / 1000) - 1, bob.session_id).run();
    await assertInvalidated(bob);
  });
  it('refresh racing with logout cannot consume token or commit a child', async () => {
    const auth = await register(); const original = jwt.signAccessToken;
    let release!: () => void, started!: () => void;
    const gate = new Promise<void>(resolve => { release = resolve; }), ready = new Promise<void>(resolve => { started = resolve; });
    vi.spyOn(jwt, 'signAccessToken').mockImplementationOnce(async (...args) => { const token = await original(...args); started(); await gate; return token; });
    const refreshing = refresh(auth.refresh_token); await ready;
    expect((await request('/auth/logout', { refresh_token: auth.refresh_token })).status).toBe(204);
    release(); expect((await refreshing).status).toBe(401);
    expect(await env.DB.prepare('SELECT count(*) n FROM refresh_tokens').first('n')).toBe(1);
    expect((await lookupRefresh(env.DB, auth.refresh_token))!.consumed_at).toBeNull();
    expect(await env.DB.prepare('SELECT count(*) n FROM refresh_rotation_results').first('n')).toBe(0);
  });
  it('IP hour/minute, registration day/hour and recovery hour limits enforce exact configured budgets', async () => {
    const now = Math.floor(Date.now() / 1000), ip = '203.0.113.10';
    for (const [prefix, scope, duration, attempts] of [
      ['login_ip', 'login_ip', 60, 10], ['login_ip', 'login_ip', 3600, 30],
      ['register_ip', 'register_ip', 3600, 3], ['register_ip', 'register_ip', 86400, 5],
      ['recovery_ip', 'recovery_ip', 3600, 10], ['recovery_account', 'recovery_account', 3600, 3],
    ] as const) {
      await env.DB.prepare('DELETE FROM auth_rate_limits').run();
      const start = Math.floor(now / duration) * duration;
      const hash = await privateHash(bindings, `${prefix}:${duration}:${scope === 'recovery_account' ? 'alice' : ip}`);
      await env.DB.prepare('INSERT INTO auth_rate_limits VALUES (?,?,?, ?,?)').bind(scope, hash, start, attempts, start + duration).run();
      const spy = vi.spyOn(passwords, 'verifyPassword');
      const response = scope.startsWith('login') ? await request('/auth/login', registerPayload(), { 'CF-Connecting-IP': ip })
        : scope.startsWith('register') ? await request('/auth/register', registerPayload(), { 'CF-Connecting-IP': ip })
        : await request('/auth/recover', { username: 'alice', recovery_code: 'invalid', new_password: newPassword, device_name: 'Recovery' }, { 'CF-Connecting-IP': ip });
      expect(response.status).toBe(429); expect(Number(response.headers.get('Retry-After'))).toBeGreaterThan(0); expect(spy).not.toHaveBeenCalled(); spy.mockRestore();
    }
  });
  it('fifth failure racing with a verified login prevents session creation', async () => {
    await register(); const key = await failureKey(bindings, 'alice');
    await env.DB.prepare("INSERT INTO auth_rate_limits VALUES ('login_account',?,0,4,?)").bind(key, Math.floor(Date.now() / 1000) + 3600).run();
    const original = passwords.verifyPassword; let release!: () => void, started!: () => void;
    const gate = new Promise<void>(resolve => { release = resolve; }), ready = new Promise<void>(resolve => { started = resolve; });
    vi.spyOn(passwords, 'verifyPassword').mockImplementationOnce(async (...args) => { const result = await original(...args); started(); await gate; return result; });
    const login = request('/auth/login', registerPayload()); await ready;
    expect((await request('/auth/login', { ...registerPayload(), password: 'wrong password' })).status).toBe(401);
    release(); expect((await login).status).toBe(429);
    expect(await env.DB.prepare('SELECT count(*) n FROM sessions').first('n')).toBe(1);
  });
  it('revoked sessions and expired auth idempotency windows cannot reveal cached credentials', async () => {
    const headers = { 'Idempotency-Key': crypto.randomUUID() }, first = await request('/auth/register', registerPayload(), headers);
    const auth = (await first.json() as { data: AuthResult }).data;
    await request('/auth/logout', { refresh_token: auth.refresh_token });
    expect((await request('/auth/register', registerPayload(), headers)).status).toBe(401);
    await env.DB.prepare('UPDATE idempotency_keys SET created_at=1,expires_at=2').run();
    expect((await request('/auth/register', registerPayload(), headers)).status).toBe(409);
  });
  it('successful login upgrades an old pepper with hash CAS in the session batch', async () => {
    await register(); const options = passwordConfig(bindings);
    const old = await passwords.hashPassword(password, { ...options, pepperVersion: 'old' });
    await env.DB.prepare('UPDATE users SET password_hash=?,password_salt=?,pepper_version=?').bind(old.password_hash, old.password_salt, 'old').run();
    expect((await request('/auth/login', registerPayload())).status).toBe(200);
    expect(await env.DB.prepare('SELECT pepper_version FROM users').first('pepper_version')).toBe('v1');
    expect(await env.DB.prepare('SELECT password_hash FROM users').first('password_hash')).not.toBe(old.password_hash);
    expect(await env.DB.prepare('SELECT count(*) n FROM mutation_guards').first('n')).toBe(0);
  });

});
