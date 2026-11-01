import type { Bindings } from '../env';
import { base64UrlDecode } from './encoding';
import type { JwtConfig } from './jwt';
import type { PasswordOptions } from './password';

export class AuthError extends Error {
  constructor(public status: 400 | 401 | 404 | 409 | 413 | 415 | 429 | 503, public code: string,
    message: string, public retryAfter?: number) { super(message); }
}
export const invalidCredentials = () => new AuthError(401, 'INVALID_CREDENTIALS', 'Invalid username or credentials.');
export const revoked = () => new AuthError(401, 'SESSION_REVOKED', 'Session is no longer valid.');
export const nowSeconds = () => Math.floor(Date.now() / 1000);
export const iso = (n: number) => new Date(n * 1000).toISOString();
export function jwtConfig(env: Bindings): JwtConfig {
  if (env.AUTH_ISSUER !== 'https://dining.alanvampuke.top' || env.AUTH_AUDIENCE !== 'dining-log-android') throw new Error('Invalid auth configuration');
  return { issuer: env.AUTH_ISSUER, audience: env.AUTH_AUDIENCE, activeKid: env.JWT_ACTIVE_KID, keys: JSON.parse(env.JWT_HS256_KEYS) };
}
export function passwordConfig(env: Bindings): PasswordOptions {
  if (env.PASSWORD_ITERATIONS !== '10000') throw new Error('P2 requires 10000 password iterations');
  const encoded: Record<string, string> = JSON.parse(env.PASSWORD_PEPPERS);
  return { pepperVersion: env.PASSWORD_ACTIVE_PEPPER, peppers: Object.fromEntries(Object.entries(encoded).map(([k, v]) => [k, base64UrlDecode(v)])) };
}
export async function digest(value: string): Promise<string> {
  return hex(new Uint8Array(await crypto.subtle.digest('SHA-256', new TextEncoder().encode(value))));
}
function hex(bytes: Uint8Array): string { return Array.from(bytes, v => v.toString(16).padStart(2, '0')).join(''); }
export async function privateHash(env: Bindings, value: string): Promise<string> {
  const bytes = base64UrlDecode(env.RATE_LIMIT_HMAC_KEY);
  if (bytes.length !== 32) throw new Error('Invalid rate-limit key');
  const key = await crypto.subtle.importKey('raw', bytes, { name: 'HMAC', hash: 'SHA-256' }, false, ['sign']);
  return hex(new Uint8Array(await crypto.subtle.sign('HMAC', key, new TextEncoder().encode(value))));
}
export function guard(db: D1Database): D1PreparedStatement[] {
  const id = crypto.randomUUID();
  return [db.prepare('INSERT INTO mutation_guards VALUES (?, changes())').bind(id), db.prepare('DELETE FROM mutation_guards WHERE operation_id=?').bind(id)];
}
