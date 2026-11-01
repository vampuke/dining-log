import type { Bindings } from '../env';
import { AuthError, privateHash, nowSeconds } from './config';
type Scope = 'login_account' | 'login_ip' | 'register_ip' | 'recovery_account' | 'recovery_ip';
type Window = [Scope, string, number, number];
export function limited(seconds: number): AuthError { return new AuthError(429, 'RATE_LIMITED', 'Too many attempts. Try again later.', Math.max(1, seconds)); }
export async function consumeLimits(env: Bindings, operation: 'login' | 'register' | 'recovery', ip: string, username = ''): Promise<void> {
  const windows: Window[] = operation === 'register'
    ? [['register_ip', ip, 60, 1], ['register_ip', ip, 3600, 3], ['register_ip', ip, 86400, 5]]
    : operation === 'login'
      ? [['login_account', username, 60, 3], ['login_account', username, 3600, 10], ['login_ip', ip, 60, 10], ['login_ip', ip, 3600, 30]]
      : [['recovery_account', username, 60, 1], ['recovery_account', username, 3600, 3], ['recovery_ip', ip, 60, 3], ['recovery_ip', ip, 3600, 10]];
  const now = nowSeconds();
  const hashes = await Promise.all(windows.map(([scope, identifier, duration]) => privateHash(env, `${scope}:${duration}:${identifier}`)));
  const result = await env.DB.batch<{ attempts: number }>(windows.map(([scope, , duration], i) => {
    const start = Math.floor(now / duration) * duration;
    return env.DB.prepare('INSERT INTO auth_rate_limits (scope,key_hash,window_start,attempts,expires_at) VALUES (?,?,?,1,?) ON CONFLICT(scope,key_hash,window_start) DO UPDATE SET attempts=attempts+1 RETURNING attempts').bind(scope, hashes[i], start, start + duration);
  }));
  let retry = 0;
  result.forEach((row, i) => { const [, , duration, maximum] = windows[i]; if (row.results[0].attempts > maximum) retry = Math.max(retry, Math.floor(now / duration) * duration + duration - now); });
  if (retry) throw limited(retry);
}
export async function failureKey(env: Bindings, username: string): Promise<string> { return privateHash(env, `failed:login_account:${username}`); }
export async function checkLock(env: Bindings, key: string): Promise<void> {
  const now = nowSeconds();
  const row = await env.DB.prepare("SELECT attempts,expires_at FROM auth_rate_limits WHERE scope='login_account' AND key_hash=? AND window_start=0").bind(key).first<{ attempts: number; expires_at: number }>();
  if (row && row.attempts >= 5 && row.expires_at > now) throw limited(row.expires_at - now);
}
export async function recordFailure(env: Bindings, key: string): Promise<void> {
  const now = nowSeconds();
  await env.DB.prepare(`INSERT INTO auth_rate_limits (scope,key_hash,window_start,attempts,expires_at) VALUES ('login_account',?,0,1,?)
    ON CONFLICT(scope,key_hash,window_start) DO UPDATE SET
    attempts=CASE WHEN expires_at<=? THEN 1 ELSE attempts+1 END,
    expires_at=CASE WHEN expires_at>? AND attempts>=5 THEN expires_at WHEN expires_at>? AND attempts>=4 THEN ? ELSE ? END`).bind(key, now + 3600, now, now, now, now + 900, now + 3600).run();
}
export function clearFailures(db: D1Database, key: string): D1PreparedStatement { return db.prepare("DELETE FROM auth_rate_limits WHERE scope='login_account' AND key_hash=? AND window_start=0").bind(key); }
// Refresh/logout edge prefilter: the existing login_ip scope, independently prefixed.
// D1 replaces unavailable edge binding; it never shares login's counters.
export async function consumeTokenLimit(env: Bindings, ip: string): Promise<void> {
  const now = nowSeconds(), start = Math.floor(now / 60) * 60;
  const key = await privateHash(env, `token_ip:minute:${ip}`);
  const row = await env.DB.prepare("INSERT INTO auth_rate_limits VALUES ('login_ip',?,?,1,?) ON CONFLICT(scope,key_hash,window_start) DO UPDATE SET attempts=attempts+1 RETURNING attempts").bind(key, start, start + 60).first<{ attempts: number }>();
  if (row!.attempts > 60) throw limited(start + 60 - now);
}
