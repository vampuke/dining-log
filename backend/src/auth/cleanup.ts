import { nowSeconds } from './config';
// Logical retry expiry is checked on every read. Hourly cron physically removes
// expired credential ciphertext and old rate windows; no user/session validity cache.
export async function cleanupAuth(db: D1Database): Promise<void> {
  const now = nowSeconds();
  await db.batch([
    db.prepare('DELETE FROM refresh_rotation_results WHERE expires_at<=?').bind(now),
    // Keep the request fingerprint for 24h to reject expired-key blind retries;
    // wipe credential ciphertext as soon as the janitor observes expiry.
    db.prepare("UPDATE idempotency_keys SET response_json='{}' WHERE expires_at<=? AND response_json<>'{}'").bind(now),
    db.prepare('DELETE FROM idempotency_keys WHERE expires_at<=?').bind(now - 86400),
    db.prepare('DELETE FROM auth_rate_limits WHERE expires_at<=?').bind(now),
  ]);
}
