import { env } from 'cloudflare:test';
import { expect } from 'vitest';
import app from '../../src/index';
import type { Bindings } from '../../src/env';
import type { AuthResult } from '../../src/auth/session';
import { base64UrlEncode } from '../../src/auth/encoding';
const key = (offset: number) => base64UrlEncode(Uint8Array.from({ length: 32 }, (_, i) => (i + offset) % 256));
export const bindings: Bindings = { DB: env.DB, JWT_HS256_KEYS: JSON.stringify({ v1: key(0) }), PASSWORD_PEPPERS: JSON.stringify({ v1: key(32) }), REFRESH_RETRY_KEYS: JSON.stringify({ v1: key(64) }), RATE_LIMIT_HMAC_KEY: key(96), AUTH_ISSUER: 'https://dining.alanvampuke.top', AUTH_AUDIENCE: 'dining-log-android', JWT_ACTIVE_KID: 'v1', PASSWORD_ACTIVE_PEPPER: 'v1', PASSWORD_ITERATIONS: '10000', REFRESH_RETRY_KEY_ID: 'v1', REGISTRATION_ENABLED: 'true' };
export function request(path: string, token: string, payload?: unknown, method = payload === undefined ? 'GET' : 'POST', idem = crypto.randomUUID(), db = env.DB) {
  return app.request(`/api/v1${path}`, { method, headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${token}`, 'Idempotency-Key': idem, 'CF-Connecting-IP': '192.0.2.10' }, body: payload === undefined ? undefined : JSON.stringify(payload) }, { ...bindings, DB: db });
}
export async function account(username = 'alice'): Promise<AuthResult> {
  const res = await app.request('/api/v1/auth/register', { method: 'POST', headers: { 'Content-Type': 'application/json', 'CF-Connecting-IP': username === 'alice' ? '192.0.2.1' : '192.0.2.2' }, body: JSON.stringify({ username, password: 'a deliberate strong passphrase', device_name: 'P3 test' }) }, bindings);
  expect(res.status).toBe(201); return (await res.json() as { data: AuthResult }).data;
}
export type Entity = { id: string; version: number; updated_at: string; deleted_at: string | null; restaurant_id?: string; rating?: number | null; name?: string; review?: string | null; note?: string | null; address?: string };
export type Revision = { id: string; change_set_id: string; action: string; changed_fields: string[]; metadata_version: number; diff: { field: string; before: unknown; after: unknown }[]; source_revision_id: string | null };
export type Mutation = { entity: Entity; changed: boolean; revisions: Revision[]; change_set_id: string | null; first_visit?: Entity };
export async function data<T>(res: Response, status = 200): Promise<T> { expect(res.status, await res.clone().text()).toBe(status); expect(res.headers.get('Cache-Control')).toBe('no-store'); return (await res.json() as { data: T }).data; }
export async function error(res: Response, status: number, code: string) { expect(res.status, await res.clone().text()).toBe(status); const value = await res.json() as { error: { code: string; current?: Entity } }; expect(value.error.code).toBe(code); return value.error; }
export const restaurantInput = (extra = {}) => ({ id: crypto.randomUUID(), name: '食记 Bistro', category: 'restaurant', address: '18 Zhongshan Road, 2F', ...extra });
export async function restaurant(token: string, extra = {}) { return data<Mutation>(await request('/restaurants', token, restaurantInput(extra)), 201); }
export async function counts() { const tables = ['restaurants', 'dishes', 'visits', 'change_sets', 'revisions', 'idempotency_keys', 'mutation_guards', 'restaurant_search']; const entries = await Promise.all(tables.map(async table => [table, await env.DB.prepare(`SELECT count(*) n FROM ${table}`).first<number>('n')] as const)); return Object.fromEntries(entries); }
// Intercept the real D1 batch, while every statement still executes in Workers SQLite.
export function batchDB(intercept: (statements: D1PreparedStatement[]) => Promise<D1Result[]>) { return { prepare: env.DB.prepare.bind(env.DB), batch: intercept } as D1Database; }
