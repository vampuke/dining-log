import { z } from 'zod';
import { digest, privateHash, AuthError } from '../auth/config';
import { base64UrlEncode, base64UrlDecode } from '../auth/encoding';
import { id, date } from './schemas';
import type { C } from './http';
import type { Value } from './model';
const cursorSchema = z.object({ sort: z.enum(['updated_desc', 'rating_desc', 'date_desc', 'created_desc']), key: z.union([z.number().int().nonnegative().max(Number.MAX_SAFE_INTEGER), date, z.null()]), id, filter: z.string().regex(/^[a-f0-9]{64}$/), signature: z.string().regex(/^[a-f0-9]{64}$/) }).strict();
export type Cursor = z.infer<typeof cursorSchema>;
export async function filterHash(c: C, query: Record<string, unknown>, scope: string) { const { cursor: _cursor, limit: _limit, ...filters } = query; void _cursor; void _limit; return digest(JSON.stringify([c.get('userId'), scope, Object.entries(filters).sort(([a], [b]) => a.localeCompare(b))])); }
export async function decode(c: C, encoded: string | undefined, sort: Cursor['sort'], filter: string) {
  if (!encoded) return null;
  try {
    if (encoded.length > 2048 || !/^[A-Za-z0-9_-]+$/.test(encoded)) throw new Error();
    const parsed = cursorSchema.parse(JSON.parse(new TextDecoder('utf-8', { fatal: true }).decode(base64UrlDecode(encoded))));
    const { signature, ...payload } = parsed;
    if (parsed.sort !== sort || parsed.filter !== filter || signature !== await privateHash(c.env, `business-cursor:${JSON.stringify(payload)}`)) throw new Error();
    if (sort === 'date_desc' ? typeof parsed.key !== 'string' : sort === 'rating_desc' ? parsed.key !== null && (typeof parsed.key !== 'number' || parsed.key < 10 || parsed.key > 50) : typeof parsed.key !== 'number') throw new Error();
    return parsed;
  } catch { throw new AuthError(400, 'INVALID_CURSOR', 'Invalid cursor. Reset pagination.'); }
}
export async function encode(c: C, sort: Cursor['sort'], key: Value, rowId: string, filter: string) {
  const payload = { sort, key, id: rowId, filter };
  const signature = await privateHash(c.env, `business-cursor:${JSON.stringify(payload)}`);
  return base64UrlEncode(new TextEncoder().encode(JSON.stringify({ ...payload, signature })));
}
export function keyset(column: string, idColumn: string, cursor: Cursor | null, params: Value[]) {
  if (!cursor) return '';
  if (cursor.sort === 'rating_desc') {
    if (cursor.key === null) { params.push(cursor.id); return ` AND ${column} IS NULL AND ${idColumn}<?`; }
    params.push(cursor.key, cursor.key, cursor.id); return ` AND (${column}<? OR ${column} IS NULL OR (${column}=? AND ${idColumn}<?))`;
  }
  params.push(cursor.key, cursor.key, cursor.id); return ` AND (${column}<? OR (${column}=? AND ${idColumn}<?))`;
}
