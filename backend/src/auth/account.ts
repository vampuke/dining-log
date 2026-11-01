import type { Context } from 'hono';
import type { AppEnv } from '../env';
import { auth, body, cached, commit, envelope, json, verifyLoginPassword } from './routes';
import { AuthError, guard, iso, nowSeconds, revoked } from './config';
import { accessIdentity, activeSession, publicUser, type UserRow } from './session';
import { accountDeleteSchema, reauthSchema, userPatchSchema, uuid } from './schemas';
import { clearFailures, consumeLimits } from './rate-limit';
import { recoveryCodes } from './recovery';
import { BusinessIdempotency } from './idempotency';

type C = Context<AppEnv>;
async function currentUser(c: C, id: string) {
  const row = await c.env.DB.prepare('SELECT * FROM users WHERE id=? AND deleted_at IS NULL').bind(id).first<UserRow>();
  if (!row) throw revoked();
  return row;
}
async function versionConflict(c: C, id: string): Promise<never> {
  throw new AuthError(409, 'VERSION_CONFLICT', 'The account has changed. Reload before saving.', undefined, publicUser(await currentUser(c, id)));
}
async function reauthenticate(c: C, password: string) {
  const identity = await accessIdentity(c.env, c.req.header('Authorization'));
  await consumeLimits(c.env, 'login', c.req.header('CF-Connecting-IP') ?? 'unknown', identity.user.username);
  // Reuse the login verifier: PBKDF2 + pepper HMAC and constant-time compare,
  // including its dummy record and shared lock/failure budget.
  const key = await verifyLoginPassword(c, identity.user.username, password, identity.user);
  return { ...identity, key };
}
function accountCAS(c: C, user: UserRow, sid: string, key: string, set: string, values: (string | number)[]) {
  const now = nowSeconds();
  return c.env.DB.prepare(`UPDATE users SET ${set},version=version+1,updated_at=?
    WHERE id=? AND version=? AND auth_version=? AND password_hash=? AND deleted_at IS NULL
    AND EXISTS (SELECT 1 FROM sessions WHERE id=? AND user_id=? AND auth_version=? AND revoked_at IS NULL AND expires_at>?)
    AND NOT EXISTS (SELECT 1 FROM auth_rate_limits WHERE scope='login_account' AND key_hash=? AND window_start=0 AND attempts>=5 AND expires_at>?)`)
    .bind(...values, now, user.id, user.version, user.auth_version, user.password_hash, sid, user.id, user.auth_version, now, key, now);
}

auth.get('/me', async c => {
  const { user } = await accessIdentity(c.env, c.req.header('Authorization'));
  return json(c, envelope(publicUser(user)));
});
auth.patch('/me', async c => {
  const { user, claims } = await accessIdentity(c.env, c.req.header('Authorization'));
  const input = await body(c, userPatchSchema);
  const key = c.req.header('Idempotency-Key');
  if (!uuid.safeParse(key).success) throw new AuthError(400, 'VALIDATION_ERROR', 'Idempotency-Key must be UUID v4.');
  const idem = await BusinessIdempotency.create(c.env.DB, user.id, key!, c.req.method, c.req.path, input);
  const replay = await cached(c, idem); if (replay) return replay;
  if (input.expected_version !== user.version) return versionConflict(c, user.id);
  const now = nowSeconds();
  const updated = { ...user, display_name: input.display_name, version: user.version + 1, updated_at: now };
  return commit(c, [c.env.DB.prepare(`UPDATE users SET display_name=?,version=version+1,updated_at=? WHERE id=? AND version=? AND auth_version=? AND deleted_at IS NULL
    AND EXISTS (SELECT 1 FROM sessions WHERE id=? AND user_id=? AND auth_version=? AND revoked_at IS NULL AND expires_at>?)`)
    .bind(input.display_name, now, user.id, input.expected_version, claims.av, claims.sid, user.id, claims.av, now), ...guard(c.env.DB)], publicUser(updated), 200, idem, async () => {
      if (!await activeSession(c.env, user.id, claims.sid, claims.av)) throw revoked();
      return versionConflict(c, user.id);
    });
});
auth.post('/auth/logout-all', async c => {
  const input = await body(c, reauthSchema);
  const { user, claims, key } = await reauthenticate(c, input.password);
  const now = nowSeconds();
  return commit(c, [accountCAS(c, user, claims.sid, key, 'auth_version=auth_version+1', []), ...guard(c.env.DB),
    c.env.DB.prepare("UPDATE sessions SET revoked_at=?,revoke_reason='logout_all' WHERE user_id=? AND revoked_at IS NULL").bind(now, user.id),
    clearFailures(c.env.DB, key)], null, 204, null, async () => { throw revoked(); });
});
auth.post('/auth/recovery-codes', async c => {
  const input = await body(c, reauthSchema);
  const { user, claims, key } = await reauthenticate(c, input.password);
  const now = nowSeconds(), recovery = await recoveryCodes(c.env.DB, user.id, now);
  return commit(c, [accountCAS(c, user, claims.sid, key, 'auth_version=auth_version', []), ...guard(c.env.DB),
    c.env.DB.prepare('UPDATE recovery_codes SET disabled_at=? WHERE user_id=? AND disabled_at IS NULL').bind(now, user.id),
    ...recovery.statements, clearFailures(c.env.DB, key)], { recovery_codes: recovery.codes }, 200, null, async () => {
      if (!await activeSession(c.env, user.id, claims.sid, claims.av)) throw revoked();
      return versionConflict(c, user.id);
    });
});
auth.delete('/me', async c => {
  const input = await body(c, accountDeleteSchema);
  const { user, claims, key } = await reauthenticate(c, input.password);
  if (input.confirm_username !== user.username) throw new AuthError(400, 'VALIDATION_ERROR', 'Confirm the current username.');
  if (input.expected_version !== user.version) return versionConflict(c, user.id);
  const now = nowSeconds();
  return commit(c, [accountCAS(c, user, claims.sid, key, 'deleted_at=?,auth_version=auth_version+1', [now]), ...guard(c.env.DB),
    c.env.DB.prepare("UPDATE sessions SET revoked_at=?,revoke_reason='account_delete' WHERE user_id=? AND revoked_at IS NULL").bind(now, user.id),
    clearFailures(c.env.DB, key)], { deleted_at: iso(now), purge_after: iso(now + 30 * 86400) }, 202, null, async () => {
      if (!await activeSession(c.env, user.id, claims.sid, claims.av)) throw revoked();
      return versionConflict(c, user.id);
    });
});
