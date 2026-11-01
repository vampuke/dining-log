import type { Bindings } from '../env';
import { AuthError, guard, iso, jwtConfig, nowSeconds, revoked } from './config';
import { seal, unseal, type SealedResult } from './encrypted-result';
import { signAccessToken } from './jwt';
import { activeSession, insertRefresh, lookupRefresh, newRefresh, publicUser, revokeSession, type AuthResult, type RefreshRow, type UserRow } from './session';
interface RotationRow extends SealedResult { request_id: string; expires_at: number }
export interface AuthEnvelope { data: AuthResult; meta: { request_id: string } }
const aad = (row: RefreshRow, id: string, expires: number) => `refresh:v1:${row.id}:${row.user_id}:${id}:${expires}`;
async function consumed(env: Bindings, old: RefreshRow, requestId: string): Promise<AuthEnvelope> {
  if (!await activeSession(env, old.user_id, old.session_id)) throw revoked();
  const cache = await env.DB.prepare('SELECT * FROM refresh_rotation_results WHERE old_token_id=? AND user_id=?').bind(old.id, old.user_id).first<RotationRow>();
  if (old.consumed_request_id === requestId && cache && cache.request_id === requestId && cache.expires_at > nowSeconds()) {
    return unseal<AuthEnvelope>(env, cache, aad(old, requestId, cache.expires_at));
  }
  await revokeSession(env.DB, old.user_id, old.session_id, 'token_reuse').run();
  throw new AuthError(401, 'REFRESH_REUSED', 'Refresh token was reused. Sign in again.');
}
export async function rotate(env: Bindings, token: string, requestId: string): Promise<AuthEnvelope> {
  const old = await lookupRefresh(env.DB, token);
  if (!old || !await activeSession(env, old.user_id, old.session_id)) throw revoked();
  if (old.consumed_at !== null) return consumed(env, old, requestId);
  if (old.expires_at <= nowSeconds()) throw revoked();
  const session = (await activeSession(env, old.user_id, old.session_id))!;
  const user = (await env.DB.prepare('SELECT * FROM users WHERE id=?').bind(old.user_id).first<UserRow>())!;
  const now = nowSeconds(), expires = now + 120;
  const next = await newRefresh(user.id, session.id, now, session.expires_at, old.id);
  const response: AuthEnvelope = { data: { user: publicUser(user), token_type: 'Bearer', access_token: await signAccessToken({ sub: user.id, sid: session.id, av: user.auth_version }, jwtConfig(env)), expires_in: 600,
    refresh_token: next.token, refresh_expires_at: iso(next.expiresAt), session_expires_at: iso(session.expires_at), session_id: session.id }, meta: { request_id: crypto.randomUUID() } };
  const encrypted = await seal(env, response, aad(old, requestId, expires));
  try {
    await env.DB.batch([
      env.DB.prepare('UPDATE refresh_tokens SET consumed_at=?,consumed_request_id=? WHERE id=? AND token_hash=? AND consumed_at IS NULL AND expires_at>?').bind(now, requestId, old.id, old.token_hash, now), ...guard(env.DB),
      env.DB.prepare(`UPDATE sessions SET last_refreshed_at=? WHERE id=? AND user_id=? AND revoked_at IS NULL AND expires_at>?
        AND auth_version=? AND EXISTS (SELECT 1 FROM users WHERE id=? AND auth_version=? AND deleted_at IS NULL)`).bind(now, session.id, user.id, now, user.auth_version, user.id, user.auth_version), ...guard(env.DB),
      insertRefresh(env.DB, next),
      env.DB.prepare('INSERT INTO refresh_rotation_results (old_token_id,user_id,request_id,encryption_key_version,nonce,ciphertext,created_at,expires_at) VALUES (?,?,?,?,?,?,?,?)').bind(old.id, user.id, requestId, encrypted.encryption_key_version, encrypted.nonce, encrypted.ciphertext, now, expires),
    ]);
  } catch (error) {
    const current = await lookupRefresh(env.DB, token);
    if (current?.consumed_at !== null && current?.consumed_at !== undefined) return consumed(env, current, requestId);
    if (!await activeSession(env, old.user_id, old.session_id)) throw revoked();
    throw error;
  }
  return response;
}
