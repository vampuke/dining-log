export type Kind = 'restaurant' | 'dish' | 'visit';
export type Value = string | number | null;
export type Row = Record<string, Value> & { id: string; user_id: string; version: number; created_at: number; updated_at: number; deleted_at: number | null; restaurant_id?: string };
export type Diff = { field: string; before: Value; after: Value };
export const tables = { restaurant: 'restaurants', dish: 'dishes', visit: 'visits' } as const;
export const fields: Record<Kind, string[]> = { restaurant: ['name', 'category', 'cuisine', 'address', 'rating_tenths', 'review', 'note', 'deleted_at'], dish: ['name', 'rating_tenths', 'review', 'deleted_at'], visit: ['dining_date', 'meal', 'note', 'deleted_at'] };
export function diff(kind: Kind, before: Row | null, after: Row): Diff[] {
  return fields[kind].flatMap(field => { const a = before?.[field] ?? null, b = after[field] ?? null; return a === b ? [] : [{ field, before: a, after: b }]; });
}
export const iso = (v: Value | undefined) => typeof v === 'number' ? new Date(v).toISOString() : null;
export const excerpt = (v: Value | undefined) => typeof v === 'string' ? Array.from(v).slice(0, 120).join('') : null;
export function entity(kind: Kind, row: Row) {
  const out: Record<string, unknown> = { id: row.id, version: row.version, created_at: iso(row.created_at), updated_at: iso(row.updated_at), deleted_at: iso(row.deleted_at) };
  if (kind !== 'restaurant') out.restaurant_id = row.restaurant_id;
  for (const field of fields[kind]) if (field !== 'deleted_at') { if (field === 'rating_tenths') out.rating = row[field] === null ? null : Number(row[field]) / 10; else out[field] = row[field] ?? null; }
  return out;
}
export function toStorage(payload: Record<string, unknown>): Record<string, Value> {
  return Object.fromEntries(Object.entries(payload).filter(([k]) => !['id', 'expected_version', 'first_visit'].includes(k)).map(([k, v]) => k === 'rating' ? ['rating_tenths', v === null ? null : Math.round(Number(v) * 10)] : [k, v as Value]));
}
