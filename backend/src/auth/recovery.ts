import { digest } from './config';
export function normalizeRecoveryCode(code: string): string { return code.replace(/[\s-]/g, '').toUpperCase(); }
function makeCode(): string {
  const bytes = crypto.getRandomValues(new Uint8Array(16));
  const alphabet = 'ABCDEFGHIJKLMNOPQRSTUVWXYZ234567';
  let bits = 0, buffer = 0, out = '';
  for (const byte of bytes) {
    buffer = (buffer << 8) | byte; bits += 8;
    while (bits >= 5) { bits -= 5; out += alphabet[(buffer >>> bits) & 31]; }
  }
  if (bits) out += alphabet[(buffer << (5 - bits)) & 31];
  return out.match(/.{1,4}/g)!.join('-');
}
export async function recoveryCodes(db: D1Database, userId: string, now: number) {
  const previous = await db.prepare('SELECT code_hash FROM recovery_codes WHERE user_id=?').bind(userId).all<{ code_hash: string }>();
  const seen = new Set(previous.results.map(row => row.code_hash));
  const codes: string[] = [], hashes: string[] = [];
  // Exclude every retained old hash and duplicates within this generation.
  // The unique database constraint also protects against concurrent collisions.
  while (codes.length < 8) {
    const code = makeCode(), hash = await digest(normalizeRecoveryCode(code));
    if (seen.has(hash)) continue;
    seen.add(hash); codes.push(code); hashes.push(hash);
  }
  return { codes, statements: hashes.map(hash => db.prepare('INSERT INTO recovery_codes (id,user_id,code_hash,created_at) VALUES (?,?,?,?)').bind(crypto.randomUUID(), userId, hash, now)) };
}
