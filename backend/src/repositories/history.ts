import { iso, type Diff, type Value } from '../domain/model';
import { notFound, conflict } from '../domain/http';
export type HistoryRow = Record<string, Value>;
export const historyColumns = `h.id,h.change_set_id,h.restaurant_id,h.entity_type,h.entity_id,h.action,h.entity_version_before,h.entity_version_after,h.note,h.metadata_version,h.metadata_updated_at,h.created_at,h.deleted_at,
 r.name restaurant_name,CASE h.entity_type WHEN 'restaurant' THEN r.name WHEN 'dish' THEN d.name ELSE v.dining_date END entity_label,
 (SELECT json_group_array(json_extract(value,'$.field')) FROM json_each(h.diff_json)) changed_fields_json`;
export const historyJoin = `FROM revisions h JOIN restaurants r ON r.id=h.restaurant_id AND r.user_id=h.user_id
 LEFT JOIN dishes d ON d.id=h.dish_id AND d.user_id=h.user_id AND d.restaurant_id=h.restaurant_id
 LEFT JOIN visits v ON v.id=h.visit_id AND v.user_id=h.user_id AND v.restaurant_id=h.restaurant_id`;
export function revisionSummary(row: HistoryRow) {
  return { id: row.id, change_set_id: row.change_set_id, restaurant_id: row.restaurant_id, restaurant_name: row.restaurant_name, entity_type: row.entity_type, entity_id: row.entity_id, entity_label: row.entity_label, action: row.action, entity_version_before: row.entity_version_before, entity_version_after: row.entity_version_after,
    changed_fields: row.changed_fields_json ? JSON.parse(String(row.changed_fields_json)) as string[] : (JSON.parse(String(row.diff_json)) as { field: string }[]).map(d => d.field), note: row.note, metadata_version: row.metadata_version, created_at: iso(row.created_at), deleted_at: iso(row.deleted_at) };
}
export function revisionDetail(row: HistoryRow) {
  const changes = JSON.parse(String(row.diff_json)) as Diff[];
  const diff = changes.map(d => d.field === 'deleted_at' ? { ...d, before: iso(d.before), after: iso(d.after) } : d);
  return { ...revisionSummary(row), diff, source_revision_id: row.source_revision_id, metadata_updated_at: iso(row.metadata_updated_at) };
}
export function metadata(row: HistoryRow) { return { id: row.id, note: row.note, metadata_version: row.metadata_version, metadata_updated_at: iso(row.metadata_updated_at), deleted_at: iso(row.deleted_at) }; }
export async function historyOwned(db: D1Database, userId: string, id: string, include = false) {
  const row = await db.prepare(`SELECT ${historyColumns},h.diff_json,h.source_revision_id ${historyJoin} WHERE h.user_id=? AND h.id=?${include ? '' : ' AND h.deleted_at IS NULL'}`).bind(userId, id).first<HistoryRow>();
  if (!row) throw notFound(); return row;
}
export function checkMetadata(row: HistoryRow, expected: number) { if (row.metadata_version !== expected) throw conflict(metadata(row)); }
