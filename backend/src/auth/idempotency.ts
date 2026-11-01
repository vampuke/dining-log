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
