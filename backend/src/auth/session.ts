import type { Bindings } from '../env';
import { type PasswordRecord } from './password';
import { signAccessToken, verifyAccessToken } from './jwt';
import { base64UrlEncode } from './encoding';
import { AuthError, digest, guard, iso, jwtConfig, nowSeconds, revoked } from './config';

export const SESSION_TTL_SECONDS = 15552000;

export interface UserRow extends PasswordRecord {
  id: string; username: string; display_name: string; auth_version: number; password_changed_at: number;
  version: number; created_at: number; updated_at: number; deleted_at: number | null;
}
export interface SessionRow {
  id: string; user_id: string; auth_version: number; device_name: string; created_at: number;
  last_refreshed_at: number; expires_at: number; revoked_at: number | null;
}
export interface AuthResult {
  user: ReturnType<typeof publicUser>; token_type: 'Bearer'; access_token: string; expires_in: 600;
  refresh_token: string; refresh_expires_at: string; session_expires_at: string; session_id: string;
  recovery_codes?: string[];
}
export function publicUser(user: UserRow) { return { id: user.id, username: user.username, display_name: user.display_name,
  version: user.version, created_at: iso(user.created_at), updated_at: iso(user.updated_at) }; }
export function publicSession(row: SessionRow, currentId: string) { return { id: row.id, device_name: row.device_name,
  created_at: iso(row.created_at), last_refreshed_at: iso(row.last_refreshed_at), expires_at: iso(row.expires_at), is_current: row.id === currentId }; }
export async function userByName(db: D1Database, username: string, includeDeleted = false): Promise<UserRow | null> { return db.prepare(`SELECT * FROM users WHERE username=?${includeDeleted ? '' : ' AND deleted_at IS NULL'}`).bind(username).first<UserRow>(); }
export async function activeSession(env: Bindings, userId: string, sessionId: string, authVersion?: number): Promise<SessionRow | null> {
  return env.DB.prepare(`SELECT s.* FROM sessions s JOIN users u ON u.id=s.user_id
    WHERE s.id=? AND s.user_id=? AND s.revoked_at IS NULL AND s.expires_at>?
    AND u.deleted_at IS NULL AND u.auth_version=s.auth_version AND (? IS NULL OR s.auth_version=?)`).bind(sessionId, userId, nowSeconds(), authVersion ?? null, authVersion ?? null).first<SessionRow>();
}
export async function accessIdentity(env: Bindings, authorization: string | undefined, checkSession = true) {
  if (!authorization || !/^Bearer [^\s]+$/.test(authorization)) throw revoked();
  let claims;
  try { claims = await verifyAccessToken(authorization.slice(7), jwtConfig(env)); }
  catch (error) {
    if (error && typeof error === 'object' && 'code' in error && error.code === 'ERR_JWT_EXPIRED') throw new AuthError(401, 'TOKEN_EXPIRED', 'Access token has expired.');
    throw revoked();
  }
  const session = checkSession ? await activeSession(env, claims.sub, claims.sid, claims.av) : null;
  if (checkSession && !session) throw revoked();
  const user = await env.DB.prepare('SELECT * FROM users WHERE id=? AND deleted_at IS NULL').bind(claims.sub).first<UserRow>();
  if (!user) throw revoked();
  return { claims, user, session };
}
export async function newRefresh(userId: string, sessionId: string, now: number, expiresAt: number, parent: string | null = null) {
  const id = crypto.randomUUID(), token = `${id}.${base64UrlEncode(crypto.getRandomValues(new Uint8Array(32)))}`;
  return { id, token, hash: await digest(token), userId, sessionId, now, expiresAt: Math.min(now + 604800, expiresAt), parent };
}
export type NewRefresh = Awaited<ReturnType<typeof newRefresh>>;
export function insertRefresh(db: D1Database, token: NewRefresh): D1PreparedStatement {
  return db.prepare('INSERT INTO refresh_tokens (id,user_id,session_id,token_hash,parent_token_id,created_at,expires_at) VALUES (?,?,?,?,?,?,?)').bind(token.id, token.userId, token.sessionId, token.hash, token.parent, token.now, token.expiresAt);
}
export async function issueSession(env: Bindings, user: UserRow, device: string, failureKey?: string) {
  const now = nowSeconds(), sessionId = crypto.randomUUID(), expiresAt = now + SESSION_TTL_SECONDS;
  const refresh = await newRefresh(user.id, sessionId, now, expiresAt);
  const result: AuthResult = { user: publicUser(user), token_type: 'Bearer', access_token: await signAccessToken({ sub: user.id, sid: sessionId, av: user.auth_version }, jwtConfig(env)), expires_in: 600,
    refresh_token: refresh.token, refresh_expires_at: iso(refresh.expiresAt), session_expires_at: iso(expiresAt), session_id: sessionId };
  const statements = [env.DB.prepare(`INSERT INTO sessions (id,user_id,auth_version,device_name,created_at,last_refreshed_at,expires_at)
    SELECT ?,id,auth_version,?,?,?,? FROM users WHERE id=? AND password_hash=? AND auth_version=? AND deleted_at IS NULL
    AND NOT EXISTS (SELECT 1 FROM auth_rate_limits WHERE scope='login_account' AND key_hash=? AND window_start=0 AND attempts>=5 AND expires_at>?)`)
    .bind(sessionId, device, now, now, expiresAt, user.id, user.password_hash, user.auth_version, failureKey ?? '', now), ...guard(env.DB), insertRefresh(env.DB, refresh)];
  return { result, statements };
}
export interface RefreshRow {
  id: string; user_id: string; session_id: string; token_hash: string; expires_at: number;
  consumed_at: number | null; consumed_request_id: string | null;
}
export async function lookupRefresh(db: D1Database, token: string): Promise<RefreshRow | null> {
  if (!/^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}\.[A-Za-z0-9_-]{43}$/i.test(token)) return null;
  return db.prepare('SELECT * FROM refresh_tokens WHERE token_hash=?').bind(await digest(token)).first<RefreshRow>();
}
export function revokeSession(db: D1Database, userId: string, sessionId: string, reason: string): D1PreparedStatement {
  return db.prepare('UPDATE sessions SET revoked_at=?,revoke_reason=? WHERE user_id=? AND id=? AND revoked_at IS NULL').bind(nowSeconds(), reason, userId, sessionId);
}
