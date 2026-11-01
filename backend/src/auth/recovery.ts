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
  const codes = Array.from({ length: 8 }, makeCode);
  const hashes = await Promise.all(codes.map(code => digest(normalizeRecoveryCode(code))));
  return { codes, statements: hashes.map(hash => db.prepare('INSERT INTO recovery_codes (id,user_id,code_hash,created_at) VALUES (?,?,?,?)').bind(crypto.randomUUID(), userId, hash, now)) };
}
