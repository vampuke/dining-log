// Five stages, at most 20 rows per stage (100 primary keys per invocation).
// Every stage rechecks expiry. FK cascades only remove the single search row;
// child/history rows are drained before their parent can be deleted.
export async function cleanupDomain(db: D1Database, now = Date.now()) {
  const cutoff = now - 30 * 86400000, accountCutoff = Math.floor(cutoff / 1000);
  const accountExpired = `EXISTS (SELECT 1 FROM users u WHERE u.id=x.user_id AND u.deleted_at<=${accountCutoff})`;
  const restaurantExpired = `EXISTS (SELECT 1 FROM restaurants r WHERE r.user_id=x.user_id AND r.id=x.restaurant_id AND r.deleted_at<=${cutoff})`;
  const expired = (kind: 'restaurant' | 'dish' | 'visit') => `(x.deleted_at<=${cutoff} OR ${accountExpired}${kind === 'restaurant' ? '' : ` OR ${restaurantExpired}`})`;
  const historyExpired = `(${accountExpired} OR ${restaurantExpired} OR EXISTS (SELECT 1 FROM dishes d WHERE d.user_id=x.user_id AND d.id=x.dish_id AND d.deleted_at<=${cutoff}) OR EXISTS (SELECT 1 FROM visits v WHERE v.user_id=x.user_id AND v.id=x.visit_id AND v.deleted_at<=${cutoff}))`;
  // Delete cached plaintext before removing the referenced target. Account expiry
  // also covers P2 encrypted credential retries, without trying to decode them.
  await db.batch([
    db.prepare(`DELETE FROM idempotency_keys WHERE rowid IN (SELECT x.rowid FROM idempotency_keys x WHERE
      ${accountExpired} OR (x.expires_at>100000000000 AND x.expires_at<=?) OR
      EXISTS (SELECT 1 FROM restaurants r WHERE r.user_id=x.user_id AND r.deleted_at<=? AND (r.id=json_extract(x.response_json,'$.data.entity.id') OR r.id=json_extract(x.response_json,'$.data.entity.restaurant_id'))) OR
      EXISTS (SELECT 1 FROM dishes d WHERE d.user_id=x.user_id AND d.deleted_at<=? AND d.id=json_extract(x.response_json,'$.data.entity.id')) OR
      EXISTS (SELECT 1 FROM visits v WHERE v.user_id=x.user_id AND v.deleted_at<=? AND (v.id=json_extract(x.response_json,'$.data.entity.id') OR v.id=json_extract(x.response_json,'$.data.first_visit.id'))) OR
      EXISTS (SELECT 1 FROM revisions h JOIN restaurants r ON r.id=h.restaurant_id AND r.user_id=h.user_id LEFT JOIN dishes d ON d.id=h.dish_id AND d.user_id=h.user_id LEFT JOIN visits v ON v.id=h.visit_id AND v.user_id=h.user_id WHERE h.user_id=x.user_id AND h.id=json_extract(x.response_json,'$.data.id') AND (r.deleted_at<=? OR d.deleted_at<=? OR v.deleted_at<=?)) LIMIT 20)`)
      .bind(now, cutoff, cutoff, cutoff, cutoff, cutoff, cutoff),
    db.prepare(`DELETE FROM revisions WHERE id IN (SELECT x.id FROM revisions x WHERE ${historyExpired} AND NOT EXISTS (SELECT 1 FROM idempotency_keys k WHERE k.user_id=x.user_id AND json_extract(k.response_json,'$.data.id')=x.id) LIMIT 20)`),
    ...(['dish', 'visit'] as const).map(kind => db.prepare(`DELETE FROM ${kind === 'dish' ? 'dishes' : 'visits'} WHERE id IN (SELECT x.id FROM ${kind === 'dish' ? 'dishes' : 'visits'} x WHERE ${expired(kind)} AND NOT EXISTS (SELECT 1 FROM revisions h WHERE h.user_id=x.user_id AND h.entity_type='${kind}' AND h.entity_id=x.id) AND NOT EXISTS (SELECT 1 FROM idempotency_keys k WHERE k.user_id=x.user_id AND (json_extract(k.response_json,'$.data.entity.id')=x.id OR json_extract(k.response_json,'$.data.first_visit.id')=x.id)) LIMIT 20)`)),
    db.prepare(`DELETE FROM restaurants WHERE id IN (SELECT x.id FROM restaurants x WHERE ${expired('restaurant')} AND NOT EXISTS (SELECT 1 FROM dishes d WHERE d.user_id=x.user_id AND d.restaurant_id=x.id) AND NOT EXISTS (SELECT 1 FROM visits v WHERE v.user_id=x.user_id AND v.restaurant_id=x.id) AND NOT EXISTS (SELECT 1 FROM revisions h WHERE h.user_id=x.user_id AND h.restaurant_id=x.id) AND NOT EXISTS (SELECT 1 FROM idempotency_keys k WHERE k.user_id=x.user_id AND (json_extract(k.response_json,'$.data.entity.id')=x.id OR json_extract(k.response_json,'$.data.entity.restaurant_id')=x.id)) LIMIT 20)`),
  ]);
  // Empty sets are housekeeping, bounded separately; no history is discarded.
  await db.prepare('DELETE FROM change_sets WHERE id IN (SELECT s.id FROM change_sets s WHERE NOT EXISTS (SELECT 1 FROM revisions h WHERE h.change_set_id=s.id AND h.user_id=s.user_id) LIMIT 20)').run();
}
