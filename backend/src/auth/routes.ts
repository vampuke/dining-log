import { Hono, type Context } from 'hono';
import { z } from 'zod';
import type { AppEnv } from '../env';
import { AuthError, guard, invalidCredentials, nowSeconds, passwordConfig, revoked } from './config';
import { base64UrlEncode } from './encoding';
import { hashPassword, PASSWORD_ALGORITHM, PASSWORD_ITERATIONS, verifyPassword } from './password';
import { AuthIdempotency } from './idempotency';
import { checkLock, clearFailures, consumeLimits, consumeTokenLimit, failureKey, recordFailure } from './rate-limit';
import { normalizeRecoveryCode, recoveryCodes } from './recovery';
import { digest } from './config';
import { rotate } from './rotation';
import { accessIdentity, activeSession, issueSession, lookupRefresh, publicSession, publicUser, revokeSession, userByName, type SessionRow, type UserRow } from './session';
import { loginSchema, passwordSchema, recoverSchema, refreshSchema, registerSchema, uuid } from './schemas';

type C = Context<AppEnv>;
const envelope = (data: unknown) => ({ data, meta: { request_id: crypto.randomUUID() } });
function json(c: C, response: unknown, status = 200): Response { return new Response(JSON.stringify(response), { status, headers: { 'Content-Type': 'application/json; charset=UTF-8', 'Cache-Control': 'no-store', ...Object.fromEntries(c.res.headers) } }); }
function ip(c: C) { return c.req.header('CF-Connecting-IP') ?? 'unknown'; }
async function body<T extends z.ZodTypeAny>(c: C, schema: T): Promise<z.infer<T>> {
  if (c.req.header('Content-Type')?.split(';')[0].trim().toLowerCase() !== 'application/json') throw new AuthError(415, 'UNSUPPORTED_MEDIA_TYPE', 'Use application/json.');
  const reader = c.req.raw.body?.getReader();
  const chunks: Uint8Array[] = []; let bytes = 0;
  if (reader) { for (;;) { const part = await reader.read(); if (part.done) break; bytes += part.value.length; if (bytes > 65536) { await reader.cancel(); throw new AuthError(413, 'PAYLOAD_TOO_LARGE', 'Request body is too large.'); } chunks.push(part.value); } }
  const all = new Uint8Array(bytes); let offset = 0; for (const chunk of chunks) { all.set(chunk, offset); offset += chunk.length; }
  let value: unknown; try { value = JSON.parse(new TextDecoder('utf-8', { fatal: true }).decode(all)); } catch { throw new AuthError(400, 'VALIDATION_ERROR', 'Invalid JSON request.'); }
  const result = schema.safeParse(value);
  if (!result.success) throw new AuthError(400, 'VALIDATION_ERROR', 'Invalid request fields.');
  return result.data;
}
function idemKey(c: C) { const key = c.req.header('Idempotency-Key'); if (key && !uuid.safeParse(key).success) throw new AuthError(400, 'VALIDATION_ERROR', 'Idempotency-Key must be UUID v4.'); return key; }
async function idempotency(c: C, userId: string, payload: unknown) { return AuthIdempotency.create(c.env, userId, idemKey(c), c.req.method, c.req.path, payload); }
async function cached(c: C, idem: AuthIdempotency | null): Promise<Response | null> { const hit = await idem?.read(); return hit ? json(c, hit.response, hit.status) : null; }
async function commit(c: C, statements: D1PreparedStatement[], data: unknown, status: number, idem: AuthIdempotency | null, failure?: () => Promise<never>) {
  const response = envelope(data);
  if (idem) statements.push(await idem.statement(response, status));
  try { await c.env.DB.batch(statements); }
  catch (error) {
    const replay = await cached(c, idem); if (replay) return replay;
    if (failure) return failure();
    throw error;
  }
  return status === 204 ? c.body(null, 204) : json(c, response, status);
}
async function verifyLoginPassword(c: C, name: string, password: string, user: UserRow | null): Promise<string> {
  const key = await failureKey(c.env, name);
  await checkLock(c.env, key);
  const config = passwordConfig(c.env);
  // Fixed dummy record: same PBKDF2/pepper/constant-time HMAC verify as real users.
  const dummy = { password_algorithm: PASSWORD_ALGORITHM, password_iterations: PASSWORD_ITERATIONS,
    password_salt: base64UrlEncode(new Uint8Array(16)), password_hash: base64UrlEncode(new Uint8Array(32)), pepper_version: config.pepperVersion };
  const valid = await verifyPassword(password, user ?? dummy, config.peppers);
  if (!valid || !user) { await recordFailure(c.env, key); throw invalidCredentials(); }
  return key;
}
const auth = new Hono<AppEnv>();
auth.use('*', async (c, next) => {
  c.header('Cache-Control', 'no-store');
  try { await next(); } catch (error) {
    if (error instanceof AuthError) {
      if (error.retryAfter) c.header('Retry-After', String(error.retryAfter));
      c.res = json(c, { error: { code: error.code, message: error.message, field_errors: {} }, meta: { request_id: crypto.randomUUID() } }, error.status);
    } else {
      // Never log auth request/response bodies, credentials or underlying errors.
      c.res = json(c, { error: { code: 'TEMPORARILY_UNAVAILABLE', message: 'Please try again later.', field_errors: {} }, meta: { request_id: crypto.randomUUID() } }, 503);
    }
  }
});
auth.post('/auth/register', async c => {
  const input = await body(c, registerSchema); idemKey(c);
  if (c.env.REGISTRATION_ENABLED !== 'true') throw new AuthError(503, 'TEMPORARILY_UNAVAILABLE', 'Registration is currently disabled.');
  const existing = await userByName(c.env.DB, input.username);
  if (existing) { const replay = await cached(c, await idempotency(c, existing.id, input)); if (replay) return replay; }
  await consumeLimits(c.env, 'register', ip(c));
  if (existing) throw new AuthError(409, 'USERNAME_TAKEN', 'Username is already registered.');
  const record = await hashPassword(input.password, passwordConfig(c.env));
  const now = nowSeconds();
  const user: UserRow = { ...record, id: crypto.randomUUID(), username: input.username, display_name: input.display_name ?? input.username,
    auth_version: 1, version: 1, created_at: now, updated_at: now, password_changed_at: now, deleted_at: null };
  const session = await issueSession(c.env, user, input.device_name);
  const recovery = await recoveryCodes(c.env.DB, user.id, now);
  const idem = await idempotency(c, user.id, input);
  return commit(c, [c.env.DB.prepare(`INSERT INTO users (id,username,display_name,password_algorithm,password_hash,password_salt,password_iterations,pepper_version,password_changed_at,created_at,updated_at)
    VALUES (?,?,?,?,?,?,?,?,?,?,?)`).bind(user.id, user.username, user.display_name, record.password_algorithm, record.password_hash, record.password_salt, record.password_iterations, record.pepper_version, now, now, now),
    ...session.statements, ...recovery.statements], { ...session.result, recovery_codes: recovery.codes }, 201, idem, async () => {
      const winner = await userByName(c.env.DB, input.username);
      if (winner) { const retry = await cached(c, await idempotency(c, winner.id, input)); if (retry) return retry as never; throw new AuthError(409, 'USERNAME_TAKEN', 'Username is already registered.'); }
      throw new AuthError(503, 'TEMPORARILY_UNAVAILABLE', 'Please try again later.');
    });
});
auth.post('/auth/login', async c => {
  const input = await body(c, loginSchema); idemKey(c);
  const user = await userByName(c.env.DB, input.username);
  const idem = user ? await idempotency(c, user.id, input) : null;
  const replay = await cached(c, idem); if (replay) return replay;
  await consumeLimits(c.env, 'login', ip(c), input.username);
  const key = await verifyLoginPassword(c, input.username, input.password, user);
  const verified = user!; const statements: D1PreparedStatement[] = [];
  const config = passwordConfig(c.env);
  let current = verified;
  if (verified.pepper_version !== config.pepperVersion || verified.password_iterations !== PASSWORD_ITERATIONS) {
    const record = await hashPassword(input.password, config); current = { ...verified, ...record };
    statements.push(c.env.DB.prepare(`UPDATE users SET password_hash=?,password_salt=?,password_iterations=?,pepper_version=? WHERE id=? AND password_hash=? AND auth_version=? AND deleted_at IS NULL`)
      .bind(record.password_hash, record.password_salt, record.password_iterations, record.pepper_version, verified.id, verified.password_hash, verified.auth_version), ...guard(c.env.DB));
  }
  const session = await issueSession(c.env, current, input.device_name, key);
  return commit(c, [...statements, ...session.statements, clearFailures(c.env.DB, key)], session.result, 200, idem, async () => { await checkLock(c.env, key); throw invalidCredentials(); });
});
auth.post('/auth/refresh', async c => {
  const input = await body(c, refreshSchema);
  const requestId = c.req.header('X-Refresh-Request-Id');
  if (!uuid.safeParse(requestId).success) throw new AuthError(400, 'VALIDATION_ERROR', 'X-Refresh-Request-Id must be UUID v4.');
  await consumeTokenLimit(c.env, ip(c));
  return json(c, await rotate(c.env, input.refresh_token, requestId!));
});
auth.post('/auth/logout', async c => {
  const input = await body(c, refreshSchema); idemKey(c);
  await consumeTokenLimit(c.env, ip(c));
  const token = await lookupRefresh(c.env.DB, input.refresh_token);
  if (!token) return c.body(null, 204);
  const idem = await idempotency(c, token.user_id, input); const replay = await cached(c, idem); if (replay) return c.body(null, 204);
  return commit(c, [revokeSession(c.env.DB, token.user_id, token.session_id, 'logout')], null, 204, idem);
});
auth.post('/auth/password', async c => {
  const input = await body(c, passwordSchema);
  // Signature first; an exact encrypted idempotent retry can follow the old session's revocation.
  const { user, claims } = await accessIdentity(c.env, c.req.header('Authorization'), false);
  const idem = await idempotency(c, user.id, { ...input, session_id: claims.sid });
  const replay = await cached(c, idem); if (replay) return replay;
  if (!await activeSession(c.env, user.id, claims.sid, claims.av)) throw revoked();
  await consumeLimits(c.env, 'login', ip(c), user.username);
  const key = await verifyLoginPassword(c, user.username, input.current_password, user);
  const record = await hashPassword(input.new_password, passwordConfig(c.env));
  const now = nowSeconds(), changed = { ...user, ...record, auth_version: user.auth_version + 1, version: user.version + 1, updated_at: now, password_changed_at: now };
  const next = await issueSession(c.env, changed, input.device_name, key);
  return commit(c, [c.env.DB.prepare(`UPDATE users SET password_hash=?,password_salt=?,password_iterations=?,pepper_version=?,password_changed_at=?,auth_version=auth_version+1,version=version+1,updated_at=?
    WHERE id=? AND password_hash=? AND auth_version=? AND deleted_at IS NULL
    AND EXISTS (SELECT 1 FROM sessions WHERE id=? AND user_id=? AND auth_version=? AND revoked_at IS NULL AND expires_at>?)
    AND NOT EXISTS (SELECT 1 FROM auth_rate_limits WHERE scope='login_account' AND key_hash=? AND window_start=0 AND attempts>=5 AND expires_at>?)`)
    .bind(record.password_hash, record.password_salt, record.password_iterations, record.pepper_version, now, now, user.id, user.password_hash, user.auth_version, claims.sid, user.id, claims.av, now, key, now), ...guard(c.env.DB),
    c.env.DB.prepare("UPDATE sessions SET revoked_at=?,revoke_reason='password_change' WHERE user_id=? AND revoked_at IS NULL").bind(now, user.id),
    ...next.statements, clearFailures(c.env.DB, key)], next.result, 200, idem, async () => { await checkLock(c.env, key); throw revoked(); });
});
auth.post('/auth/recover', async c => {
  const input = await body(c, recoverSchema); idemKey(c);
  const user = await userByName(c.env.DB, input.username);
  const idem = user ? await idempotency(c, user.id, input) : null; const replay = await cached(c, idem); if (replay) return replay;
  await consumeLimits(c.env, 'recovery', ip(c), input.username);
  const code = await c.env.DB.prepare('SELECT id FROM recovery_codes WHERE user_id=? AND code_hash=? AND used_at IS NULL AND disabled_at IS NULL').bind(user?.id ?? '', await digest(normalizeRecoveryCode(input.recovery_code))).first<{ id: string }>();
  if (!code || !user) throw invalidCredentials();
  const record = await hashPassword(input.new_password, passwordConfig(c.env));
  const now = nowSeconds(), changed = { ...user, ...record, auth_version: user.auth_version + 1, version: user.version + 1, updated_at: now, password_changed_at: now };
  const key = await failureKey(c.env, user.username);
  const next = await issueSession(c.env, changed, input.device_name);
  const recovery = await recoveryCodes(c.env.DB, user.id, now);
  return commit(c, [c.env.DB.prepare('UPDATE recovery_codes SET used_at=? WHERE id=? AND user_id=? AND used_at IS NULL AND disabled_at IS NULL').bind(now, code.id, user.id), ...guard(c.env.DB),
    c.env.DB.prepare('UPDATE users SET password_hash=?,password_salt=?,password_iterations=?,pepper_version=?,password_changed_at=?,auth_version=auth_version+1,version=version+1,updated_at=? WHERE id=? AND password_hash=? AND auth_version=? AND deleted_at IS NULL')
      .bind(record.password_hash, record.password_salt, record.password_iterations, record.pepper_version, now, now, user.id, user.password_hash, user.auth_version), ...guard(c.env.DB),
    c.env.DB.prepare("UPDATE sessions SET revoked_at=?,revoke_reason='recovery' WHERE user_id=? AND revoked_at IS NULL").bind(now, user.id),
    c.env.DB.prepare('UPDATE recovery_codes SET disabled_at=? WHERE user_id=? AND disabled_at IS NULL').bind(now, user.id),
    clearFailures(c.env.DB, key), ...recovery.statements, ...next.statements], { ...next.result, recovery_codes: recovery.codes }, 200, idem, async () => { throw invalidCredentials(); });
});
auth.get('/auth/session', async c => {
  const { user, claims } = await accessIdentity(c.env, c.req.header('Authorization'));
  const session = (await activeSession(c.env, user.id, claims.sid, claims.av))!;
  return json(c, envelope({ user: publicUser(user), session: publicSession(session, claims.sid) }));
});
auth.get('/auth/sessions', async c => {
  const { user, claims } = await accessIdentity(c.env, c.req.header('Authorization'));
  const rows = await c.env.DB.prepare('SELECT * FROM sessions WHERE user_id=? AND auth_version=? AND revoked_at IS NULL AND expires_at>? ORDER BY created_at DESC,id DESC').bind(user.id, user.auth_version, nowSeconds()).all<SessionRow>();
  return json(c, envelope({ items: rows.results.map(s => publicSession(s, claims.sid)) }));
});
auth.delete('/auth/sessions/:session_id', async c => {
  const { user, claims } = await accessIdentity(c.env, c.req.header('Authorization'), false);
  const sessionId = c.req.param('session_id'); if (!uuid.safeParse(sessionId).success) throw new AuthError(400, 'VALIDATION_ERROR', 'Invalid session ID.');
  const idem = await idempotency(c, user.id, { session_id: sessionId, caller_session_id: claims.sid }); const replay = await cached(c, idem); if (replay) return c.body(null, 204);
  if (!await activeSession(c.env, user.id, claims.sid, claims.av)) throw revoked();
  const own = await c.env.DB.prepare('SELECT id FROM sessions WHERE user_id=? AND id=?').bind(user.id, sessionId).first();
  if (!own) throw new AuthError(404, 'NOT_FOUND', 'Session not found.');
  return commit(c, [revokeSession(c.env.DB, user.id, sessionId, 'user_revoke')], null, 204, idem);
});
export { auth };

auth.onError((error, c) => {
  const failure = error instanceof AuthError ? error : new AuthError(503, 'TEMPORARILY_UNAVAILABLE', 'Please try again later.');
  if (failure.retryAfter) c.header('Retry-After', String(failure.retryAfter));
  return json(c, { error: { code: failure.code, message: failure.message, field_errors: {} }, meta: { request_id: crypto.randomUUID() } }, failure.status);
});
