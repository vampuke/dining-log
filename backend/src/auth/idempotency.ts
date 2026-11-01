import type { Bindings } from '../env';
import { AuthError, nowSeconds, privateHash, revoked } from './config';
import { seal, unseal, type SealedResult } from './encrypted-result';
import { activeSession } from './session';

interface CachedResponse { data: unknown; meta: { request_id: string } }
interface CacheRow { method: string; path: string; request_hash: string; response_status: number; response_json: string; expires_at: number }
function canonical(value: unknown): string {
  if (Array.isArray(value)) return `[${value.map(canonical).join(',')}]`;
  if (value && typeof value === 'object') return `{${Object.entries(value).sort(([a], [b]) => a.localeCompare(b)).map(([k, v]) => `${JSON.stringify(k)}:${canonical(v)}`).join(',')}}`;
  return JSON.stringify(value);
}
export class AuthIdempotency {
  private constructor(private env: Bindings, private userId: string, private key: string, private method: string, private path: string, private hash: string) {}
  static async create(env: Bindings, userId: string, key: string | undefined, method: string, path: string, payload: unknown) {
    if (!key) return null;
    return new AuthIdempotency(env, userId, key, method, path, await privateHash(env, `auth-idempotency:${method}:${path}:${canonical(payload)}`));
  }
  private aad(expires: number) { return `auth-idempotency:v1:${this.userId}:${this.key}:${this.method}:${this.path}:${this.hash}:${expires}`; }
  async read(): Promise<{ response: CachedResponse; status: number } | null> {
    const row = await this.env.DB.prepare('SELECT * FROM idempotency_keys WHERE user_id=? AND idempotency_key=?').bind(this.userId, this.key).first<CacheRow>();
    if (!row) return null;
    if (row.method !== this.method || row.path !== this.path || row.request_hash !== this.hash) throw new AuthError(409, 'IDEMPOTENCY_CONFLICT', 'Idempotency key was used for a different request.');
    if (row.expires_at <= nowSeconds()) throw new AuthError(409, 'IDEMPOTENCY_CONFLICT', 'Auth retry window has expired. Sign in with your current credentials.');
    const response = await unseal<CachedResponse>(this.env, JSON.parse(row.response_json) as SealedResult, this.aad(row.expires_at));
    if (response.data && typeof response.data === 'object' && 'session_id' in response.data && typeof response.data.session_id === 'string') {
      if (!await activeSession(this.env, this.userId, response.data.session_id)) throw revoked();
    }
    return { response, status: row.response_status };
  }
  async statement(response: CachedResponse, status: number): Promise<D1PreparedStatement> {
    const now = nowSeconds(), expires = now + 120;
    const encrypted = await seal(this.env, response, this.aad(expires));
    return this.env.DB.prepare('INSERT INTO idempotency_keys (user_id,idempotency_key,method,path,request_hash,response_status,response_json,created_at,expires_at) VALUES (?,?,?,?,?,?,?,?,?)')
      .bind(this.userId, this.key, this.method, this.path, this.hash, status, JSON.stringify(encrypted), now, expires);
  }
}

// Business writes reuse the P2 store/commit interface, with public JSON and a
// seven-day window. Credential operations retain their encrypted 120s policy.
export class BusinessIdempotency {
  private constructor(private db: D1Database, public userId: string, public key: string, private method: string, private path: string, private hash: string) {}
  static async create(db: D1Database, userId: string, key: string, method: string, path: string, payload: unknown) {
    const { digest } = await import('./config');
    const normalizedPath = path.replace(/\/{2,}/g, '/').replace(/\/$/, '').replace(/[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}/gi, s => s.toLowerCase());
    return new BusinessIdempotency(db, userId, key.toLowerCase(), method, normalizedPath, await digest(`${method}\n${normalizedPath}\n${canonical(payload)}`));
  }
  async read(): Promise<{ response: CachedResponse; status: number } | null> {
    const row = await this.db.prepare('SELECT * FROM idempotency_keys WHERE user_id=? AND idempotency_key=?').bind(this.userId, this.key).first<CacheRow>();
    if (!row) return null;
    if (row.method !== this.method || row.path !== this.path || row.request_hash !== this.hash) throw new AuthError(409, 'IDEMPOTENCY_CONFLICT', 'Idempotency key was used for a different request.');
    if (row.expires_at <= Date.now()) throw new AuthError(409, 'IDEMPOTENCY_CONFLICT', 'Retry window has expired. Reload before saving.');
    return { response: JSON.parse(row.response_json) as CachedResponse, status: row.response_status };
  }
  async statement(response: CachedResponse, status: number): Promise<D1PreparedStatement> {
    const now = Date.now();
    return this.db.prepare('INSERT INTO idempotency_keys (user_id,idempotency_key,method,path,request_hash,response_status,response_json,created_at,expires_at) VALUES (?,?,?,?,?,?,?,?,?)')
      .bind(this.userId, this.key, this.method, this.path, this.hash, status, JSON.stringify(response), now, now + 7 * 86400000);
  }
}
