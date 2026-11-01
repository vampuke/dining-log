import { notFound, parentUnavailable, conflict } from '../domain/http';
import { entity, tables, type Kind, type Row } from '../domain/model';
export async function owned(db: D1Database, userId: string, kind: Kind, id: string, includeDeleted = false, requireParent = true): Promise<Row> {
  const row = await db.prepare(`SELECT * FROM ${tables[kind]} WHERE user_id=? AND id=?`).bind(userId, id).first<Row>();
  if (!row || (!includeDeleted && row.deleted_at !== null)) throw notFound();
  if (kind !== 'restaurant' && requireParent) await parent(db, userId, row.restaurant_id!);
  return row;
}
export async function parent(db: D1Database, userId: string, id: string) {
  const row = await db.prepare('SELECT * FROM restaurants WHERE user_id=? AND id=? AND deleted_at IS NULL').bind(userId, id).first<Row>();
  if (!row) throw parentUnavailable(); return row;
}
export function checkVersion(kind: Kind, row: Row, expected: number) { if (row.version !== expected) throw conflict(entity(kind, row)); }
export async function classify(db: D1Database, userId: string, kind: Kind, id: string, expected: number, includeDeleted: boolean): Promise<never> {
  const row = await owned(db, userId, kind, id, includeDeleted); checkVersion(kind, row, expected);
  throw new Error('Business batch failed without an ownership or version conflict');
}
