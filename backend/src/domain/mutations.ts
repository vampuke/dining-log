import { AuthError, guard } from '../auth/config';
import { cached, commit } from '../auth/routes';
import { type BusinessIdempotency } from '../auth/idempotency';
import { owned, parent, checkVersion, classify } from '../repositories/domain';
import { entity, diff, fields, tables, toStorage, type Kind, type Row, type Diff, type Value } from './model';
import { normalizeSearch, parse, patches } from './schemas';
import { type C } from './http';
import { rebuild } from './search-index';
import { revisionDetail, revisionSummary } from '../repositories/history';
export type Action = 'create' | 'update' | 'delete' | 'restore';
export function insertBusiness(db: D1Database, kind: Kind, row: Row) {
  const keys = Object.keys(row);
  if (kind === 'restaurant') return db.prepare(`INSERT INTO restaurants (${keys.join(',')}) VALUES (${keys.map(() => '?').join(',')})`).bind(...keys.map(k => row[k]));
  return db.prepare(`INSERT INTO ${tables[kind]} (${keys.join(',')}) SELECT ${keys.map(() => '?').join(',')} FROM restaurants WHERE id=? AND user_id=? AND deleted_at IS NULL`).bind(...keys.map(k => row[k]), row.restaurant_id!, row.user_id);
}
function storageRow(kind: Kind, payload: Record<string, unknown>, userId: string, now: number, restaurantId?: string): Row {
  const values = toStorage(payload); const defaults = Object.fromEntries(fields[kind].map(f => [f, null]));
  const row = { ...defaults, ...values, id: String(payload.id), user_id: userId, version: 1, created_at: now, updated_at: now, deleted_at: null } as Row;
  if (kind !== 'restaurant') row.restaurant_id = restaurantId!;
  shadows(kind, row); return row;
}
function shadows(kind: Kind, row: Row) {
  if (kind === 'restaurant') row.cuisine_normalized = row.cuisine === null ? null : normalizeSearch(String(row.cuisine));
  if (kind === 'visit') row.search_text = normalizeSearch(String(row.note ?? ''));
}
function history(db: D1Database, kind: Kind, row: Row, changes: Diff[], action: Action, setId: string, now: number, source: string | null) {
  const serialized = JSON.stringify(changes);
  if (new TextEncoder().encode(serialized).length > 131072) throw new AuthError(413, 'DIFF_TOO_LARGE', 'Change record is too large.');
  const rev = { id: crypto.randomUUID(), user_id: row.user_id, change_set_id: setId, restaurant_id: kind === 'restaurant' ? row.id : row.restaurant_id!, entity_type: kind, entity_id: row.id, dish_id: kind === 'dish' ? row.id : null, visit_id: kind === 'visit' ? row.id : null, action, entity_version_before: row.version - 1, entity_version_after: row.version, diff_json: serialized, source_revision_id: source, search_text: normalizeSearch(changes.map(d => `${d.field} ${d.before ?? ''} ${d.after ?? ''}`).join(' ')), metadata_version: 1, metadata_updated_at: now, created_at: now, deleted_at: null, note: null, note_search: '' };
  const keys = Object.keys(rev) as (keyof typeof rev)[];
  return { rev, statement: db.prepare(`INSERT INTO revisions (${keys.join(',')}) VALUES (${keys.map(() => '?').join(',')})`).bind(...keys.map(k => rev[k])) };
}
export async function create(c: C, kind: Kind, payload: Record<string, unknown>, idem: BusinessIdempotency, restaurantId?: string) {
  const replay = await cached(c, idem); if (replay) return replay;
  const db = c.env.DB, userId = c.get('userId'), now = Date.now();
  const parentRow = kind !== 'restaurant' ? await parent(db, userId, restaurantId!) : null;
  const row = storageRow(kind, payload, userId, now, restaurantId);
  const first = payload.first_visit ? storageRow('visit', payload.first_visit as Record<string, unknown>, userId, now, row.id) : null;
  const entities = [{ kind, row }, ...(first ? [{ kind: 'visit' as Kind, row: first }] : [])];
  const setId = crypto.randomUUID(), statements: D1PreparedStatement[] = [], cleanup: D1PreparedStatement[] = [];
  for (const item of entities) { statements.push(insertBusiness(db, item.kind, item.row)); const [assertRows, clear] = guard(db, `${idem.key}:${item.kind}:${item.row.id}`); statements.push(assertRows); cleanup.push(clear); }
  statements.push(db.prepare('INSERT INTO change_sets (id,user_id,request_id,created_at) VALUES (?,?,?,?)').bind(setId, userId, idem.key, now));
  const restaurantName = kind === 'restaurant' ? row.name : parentRow!.name;
  const revisions = entities.map(item => history(db, item.kind, item.row, diff(item.kind, null, item.row), 'create', setId, now, null));
  statements.push(...revisions.map(r => r.statement), ...await rebuild(db, userId, kind, row, now, kind === 'restaurant'));
  const result = { entity: entity(kind, row), changed: true, change_set_id: setId, revisions: revisions.map((r, i) => revisionSummary({ ...r.rev, restaurant_name: restaurantName, entity_label: entities[i].kind === 'visit' ? entities[i].row.dining_date : entities[i].row.name })), ...(kind === 'restaurant' ? { first_visit: first ? entity('visit', first) : null } : {}) };
  // commit adds the successful response after business/history/search; guard cleanup
  // is also transactional. Every affected_rows assertion directly follows its write.
  statements.push(...cleanup);
  return commit(c, statements, result, 201, idem, async () => {
    if (kind !== 'restaurant') await parent(db, userId, restaurantId!);
    for (const item of entities) { const existing = await db.prepare(`SELECT id FROM ${tables[item.kind]} WHERE user_id=? AND id=?`).bind(userId, item.row.id).first(); if (existing) throw new AuthError(409, 'ID_CONFLICT', 'ID is already in use.'); }
    throw new Error('Creation batch failed');
  }, true);
}
export async function mutate(c: C, kind: Kind, id: string, payload: Record<string, unknown>, action: Exclude<Action, 'create'>, idem: BusinessIdempotency, source: string | null = null) {
  const replay = await cached(c, idem); if (replay) return replay;
  const db = c.env.DB, userId = c.get('userId'), expected = Number(payload.expected_version);
  const before = await owned(db, userId, kind, id, action === 'restore'); checkVersion(kind, before, expected);
  const now = Math.max(Date.now(), before.updated_at);
  if (action === 'restore' && before.deleted_at !== null && now >= before.deleted_at + 30 * 86400000) throw new AuthError(410, 'RESTORE_WINDOW_EXPIRED', 'Restore window has expired.');
  const row = { ...before, ...(action === 'update' ? toStorage(payload) : { deleted_at: action === 'delete' ? now : null }) } as Row;
  const changes = diff(kind, before, row), changed = changes.length > 0;
  if (changed) { row.version++; row.updated_at = now; shadows(kind, row); }
  const setId = changed ? crypto.randomUUID() : null;
  const updateFields = changed ? [...fields[kind], 'updated_at', 'version', ...(kind === 'restaurant' ? ['cuisine_normalized'] : kind === 'visit' ? ['search_text'] : [])] : ['version'];
  const statements = [db.prepare(`UPDATE ${tables[kind]} SET ${updateFields.map(f => `${f}=?`).join(',')} WHERE id=? AND user_id=? AND version=? AND deleted_at IS ?${kind === 'restaurant' ? '' : ' AND EXISTS (SELECT 1 FROM restaurants p WHERE p.id=restaurant_id AND p.user_id=? AND p.deleted_at IS NULL)'}${action === 'restore' ? ' AND (deleted_at IS NULL OR deleted_at>?)' : ''}`)
    .bind(...updateFields.map(f => row[f]), id, userId, expected, before.deleted_at, ...(kind === 'restaurant' ? [] : [userId]), ...(action === 'restore' ? [now - 30 * 86400000] : []))];
  const [assertRows, clear] = guard(db, `${idem.key}:${kind}:${id}`); statements.push(assertRows);
  let rev: ReturnType<typeof history> | null = null;
  if (changed) {
    statements.push(db.prepare('INSERT INTO change_sets (id,user_id,request_id,created_at) VALUES (?,?,?,?)').bind(setId, userId, idem.key, now));
    rev = history(db, kind, row, changes, action, setId!, now, source); statements.push(rev.statement, ...await rebuild(db, userId, kind, row, now, action === 'restore' && kind === 'restaurant'));
  }
  const parentRow = kind === 'restaurant' ? row : await parent(db, userId, row.restaurant_id!);
  const enriched = rev ? { ...rev.rev, restaurant_name: parentRow.name, entity_label: kind === 'visit' ? row.dining_date : row.name } : null;
  const current = entity(kind, row);
  const result = source ? { entity: current, changed, change_set_id: setId, revision: enriched ? revisionDetail(enriched) : null } : { entity: action === 'delete' ? { id, ...(kind === 'restaurant' ? {} : { restaurant_id: row.restaurant_id }), version: row.version, deleted_at: new Date(row.deleted_at!).toISOString() } : current, changed, change_set_id: setId, revisions: enriched ? [revisionSummary(enriched)] : [] };
  statements.push(clear);
  return commit(c, statements, result, source && changed ? 201 : 200, idem, async () => {
    const current = await owned(db, userId, kind, id, action === 'restore');
    if (action === 'restore' && current.deleted_at !== null && Date.now() >= current.deleted_at + 30 * 86400000) throw new AuthError(410, 'RESTORE_WINDOW_EXPIRED', 'Restore window has expired.');
    return classify(db, userId, kind, id, expected, action === 'restore');
  }, true);
}
export async function apply(c: C, sourceRow: Record<string, Value>, fieldsToApply: string[], side: 'before' | 'after', expected: number, idem: BusinessIdempotency) {
  const kind = sourceRow.entity_type as Kind;
  if (sourceRow.action !== 'update') throw new AuthError(400, 'VALIDATION_ERROR', 'Only update revisions can be reapplied.');
  const diffs = JSON.parse(String(sourceRow.diff_json)) as Diff[];
  const payload: Record<string, unknown> = { expected_version: expected };
  for (const field of fieldsToApply) {
    const found = diffs.find(d => d.field === field);
    if (!found || !fields[kind].includes(field) || field === 'deleted_at') throw new AuthError(400, 'VALIDATION_ERROR', 'Field cannot be reapplied.');
    const v = found[side];
    if (field === 'rating_tenths') { if (v !== null && (typeof v !== 'number' || !Number.isInteger(v) || v < 10 || v > 50)) throw new AuthError(400, 'VALIDATION_ERROR', 'Invalid historical rating.'); payload.rating = v === null ? null : Number(v) / 10; }
    else payload[field] = v;
  }
  const validated = parse(patches[kind], payload);
  // The source never supplies before values. mutate reads the current target and
  // recomputes its diff, retaining every unselected field.
  return mutate(c, kind, String(sourceRow.entity_id), validated, 'update', idem, String(sourceRow.id));
}
