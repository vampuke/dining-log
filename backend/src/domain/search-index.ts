import { normalizeSearch } from './schemas';
import type { Kind, Row } from './model';
type Fragment = { active: number; restaurant?: string; dish?: string; review?: string; note?: string };
export function fragment(kind: Kind, row: Row): Fragment {
  if (kind === 'restaurant') return { active: row.deleted_at === null ? 1 : 0, restaurant: normalizeSearch([row.name, row.category, row.cuisine, row.address].filter(v => v !== null).join(' ')), review: normalizeSearch(String(row.review ?? '')), note: normalizeSearch(String(row.note ?? '')) };
  return { active: row.deleted_at === null ? 1 : 0, dish: normalizeSearch(String(row.name)), review: normalizeSearch(String(row.review ?? '')) };
}
// The existing TEXT content stores atomic per-entity fragments plus rebuilt text.
// json_set replaces one fragment against the latest committed row, so concurrent
// sibling writes cannot overwrite each other's normalized search text.
export async function rebuild(db: D1Database, userId: string, kind: Kind, row: Row, now: number, full = false): Promise<D1PreparedStatement[]> {
  if (kind === 'visit') return [];
  const restaurantId = kind === 'restaurant' ? row.id : row.restaurant_id!;
  if (kind === 'restaurant' && row.deleted_at !== null) return [db.prepare('DELETE FROM restaurant_search WHERE user_id=? AND restaurant_id=?').bind(userId, restaurantId)];
  const statements: D1PreparedStatement[] = [];
  if (full) {
    const dishes = await db.prepare('SELECT * FROM dishes WHERE user_id=? AND restaurant_id=?').bind(userId, restaurantId).all<Row>();
    const parts = { [`restaurant:${row.id}`]: fragment(kind, row), ...Object.fromEntries(dishes.results.map(d => [`dish:${d.id}`, fragment('dish', d)])) };
    statements.push(db.prepare(`INSERT INTO restaurant_search (restaurant_id,user_id,content,updated_at) VALUES (?,?,?,?)
      ON CONFLICT(restaurant_id) DO UPDATE SET content=excluded.content,updated_at=excluded.updated_at WHERE restaurant_search.user_id=excluded.user_id`).bind(restaurantId, userId, JSON.stringify({ parts, text: '' }), now));
  } else {
    statements.push(db.prepare('UPDATE restaurant_search SET content=json_set(content,?,json(?)),updated_at=? WHERE user_id=? AND restaurant_id=?').bind(`$.parts."${kind}:${row.id}"`, JSON.stringify(fragment(kind, row)), now, userId, restaurantId));
  }
  statements.push(db.prepare(`UPDATE restaurant_search SET content=json_set(content,'$.text',coalesce((
    SELECT group_concat(f.value,' ') FROM json_each(json_extract(restaurant_search.content,'$.parts')) p,
    json_each(p.value) f WHERE json_extract(p.value,'$.active')=1 AND f.key IN ('restaurant','dish','review','note')
  ),'')),updated_at=? WHERE user_id=? AND restaurant_id=?`).bind(now, userId, restaurantId));
  return statements;
}
