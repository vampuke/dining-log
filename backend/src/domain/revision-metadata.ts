import { guard } from '../auth/config';
import { cached, commit } from '../auth/routes';
import { type BusinessIdempotency } from '../auth/idempotency';
import { historyOwned, checkMetadata, metadata } from '../repositories/history';
import { normalizeSearch } from './schemas';
import type { C } from './http';
export async function mutateMetadata(c: C, id: string, payload: { expected_metadata_version: number; note?: string | null }, action: 'update' | 'delete' | 'restore', idem: BusinessIdempotency) {
  const replay = await cached(c, idem); if (replay) return replay;
  const db = c.env.DB, userId = c.get('userId'), row = await historyOwned(db, userId, id, action === 'restore'); checkMetadata(row, payload.expected_metadata_version);
  const note = action === 'update' ? payload.note ?? null : row.note;
  const deleted = action === 'delete' ? Date.now() : action === 'restore' ? null : row.deleted_at;
  const changed = note !== row.note || deleted !== row.deleted_at;
  const updated = { ...row, note, note_search: normalizeSearch(String(note ?? '')), deleted_at: deleted, metadata_version: Number(row.metadata_version) + (changed ? 1 : 0), metadata_updated_at: changed ? Math.max(Date.now(), Number(row.metadata_updated_at)) : row.metadata_updated_at };
  const statements = [db.prepare('UPDATE revisions SET note=?,note_search=?,deleted_at=?,metadata_version=?,metadata_updated_at=? WHERE user_id=? AND id=? AND metadata_version=? AND deleted_at IS ?').bind(updated.note, updated.note_search, updated.deleted_at, updated.metadata_version, updated.metadata_updated_at, userId, id, payload.expected_metadata_version, row.deleted_at)];
  const [assertRows, clear] = guard(db, `${idem.key}:metadata:${id}`); statements.push(assertRows);
  return commit(c, statements, metadata(updated), 200, idem, async () => { const current = await historyOwned(db, userId, id, action === 'restore'); checkMetadata(current, payload.expected_metadata_version); throw new Error('Revision metadata batch failed'); }, true, [clear]);
}
