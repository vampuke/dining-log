import { pbkdf2 } from '@noble/hashes/pbkdf2';
import { sha256 } from '@noble/hashes/sha256';
import { base64UrlDecode, base64UrlEncode } from './encoding';

export const PASSWORD_ALGORITHM = 'pbkdf2-sha256+hmac-sha256-v1' as const;
export const PASSWORD_ITERATIONS = 10_000;
export interface PasswordRecord {
  password_algorithm: typeof PASSWORD_ALGORITHM;
  password_hash: string;
  password_salt: string;
  password_iterations: number;
  pepper_version: string;
}
export interface PasswordOptions {
  pepperVersion: string;
  peppers: Readonly<Record<string, Uint8Array>>;
  iterations?: number;
}
function derive(password: string, salt: Uint8Array, iterations: number): Uint8Array {
  if (!Number.isSafeInteger(iterations) || iterations < PASSWORD_ITERATIONS) {
    throw new Error('Password iterations must be an integer >= 10000');
  }
  if (salt.length !== 16) throw new Error('Password salt must be 16 bytes');
  // No trim, case conversion or Unicode normalization of passwords.
  return pbkdf2(sha256, new TextEncoder().encode(password), salt, { c: iterations, dkLen: 32 });
}
async function pepperKey(peppers: PasswordOptions['peppers'], version: string): Promise<CryptoKey> {
  if (!Object.hasOwn(peppers, version) || peppers[version].length !== 32) {
    throw new Error('Unknown pepper version or invalid 256-bit pepper');
  }
  return crypto.subtle.importKey('raw', peppers[version], { name: 'HMAC', hash: 'SHA-256' }, false, ['sign', 'verify']);
}
export async function hashPassword(password: string, options: PasswordOptions): Promise<PasswordRecord> {
  const iterations = options.iterations ?? PASSWORD_ITERATIONS;
  const salt = crypto.getRandomValues(new Uint8Array(16));
  const key = await pepperKey(options.peppers, options.pepperVersion);
  const derived = derive(password, salt, iterations);
  try {
    const hash = await crypto.subtle.sign('HMAC', key, derived);
    return { password_algorithm: PASSWORD_ALGORITHM, password_hash: base64UrlEncode(new Uint8Array(hash)),
      password_salt: base64UrlEncode(salt), password_iterations: iterations, pepper_version: options.pepperVersion };
  } finally { derived.fill(0); }
}
export async function verifyPassword(password: string, record: PasswordRecord, peppers: PasswordOptions['peppers']): Promise<boolean> {
  if (record.password_algorithm !== PASSWORD_ALGORITHM) throw new Error('Unsupported password algorithm');
  const salt = base64UrlDecode(record.password_salt);
  const hash = base64UrlDecode(record.password_hash);
  if (hash.length !== 32) throw new Error('Password hash must be 32 bytes');
  const key = await pepperKey(peppers, record.pepper_version);
  const derived = derive(password, salt, record.password_iterations);
  try { return await crypto.subtle.verify('HMAC', key, hash, derived); }
  finally { derived.fill(0); }
}
