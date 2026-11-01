import { nowSeconds } from './config';

const userChildren = ['sessions', 'refresh_tokens', 'refresh_rotation_results', 'recovery_codes', 'restaurants',
  'dishes', 'visits', 'restaurant_search', 'revisions', 'change_sets', 'idempotency_keys'] as const;

// Account timestamps are seconds; domain cleanup uses milliseconds. Recheck
// retention on every DELETE and drain credentials before their cascading parent.
export async function cleanupDeletedAccounts(db: D1Database, now = nowSeconds()): Promise<void> {
  const cutoff = now - 30 * 86400;
  const expired = 'EXISTS (SELECT 1 FROM users u WHERE u.id=x.user_id AND u.deleted_at<=?)';
  await db.batch([
    db.prepare(`DELETE FROM refresh_rotation_results WHERE old_token_id IN
      (SELECT x.old_token_id FROM refresh_rotation_results x WHERE ${expired} LIMIT 20)`).bind(cutoff),
    db.prepare(`DELETE FROM refresh_tokens WHERE id IN
      (SELECT x.id FROM refresh_tokens x WHERE ${expired} AND NOT EXISTS
        (SELECT 1 FROM refresh_rotation_results r WHERE r.user_id=x.user_id AND r.old_token_id=x.id) LIMIT 20)`).bind(cutoff),
    db.prepare(`DELETE FROM sessions WHERE id IN
      (SELECT x.id FROM sessions x WHERE ${expired} AND NOT EXISTS
        (SELECT 1 FROM refresh_tokens t WHERE t.user_id=x.user_id AND t.session_id=x.id) LIMIT 20)`).bind(cutoff),
    db.prepare(`DELETE FROM recovery_codes WHERE id IN
      (SELECT x.id FROM recovery_codes x WHERE ${expired} LIMIT 20)`).bind(cutoff),
    db.prepare(`DELETE FROM idempotency_keys WHERE rowid IN
      (SELECT x.rowid FROM idempotency_keys x WHERE ${expired} LIMIT 20)`).bind(cutoff),
    // Domain/history/change-set rows are drained by cleanupDomain. Check every
    // child table before deleting users so the final FK CASCADE removes no rows.
    db.prepare(`DELETE FROM users WHERE id IN (SELECT u.id FROM users u WHERE u.deleted_at<=? AND
      ${userChildren.map(table => `NOT EXISTS (SELECT 1 FROM ${table} c WHERE c.user_id=u.id)`).join(' AND ')} LIMIT 20)`).bind(cutoff),
  ]);
}
