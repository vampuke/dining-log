import { describe, expect, it } from 'vitest';
import { pbkdf2 } from '@noble/hashes/pbkdf2';
import { sha256 } from '@noble/hashes/sha256';
import vectors from './password-vectors.json';
import { base64UrlEncode } from '../src/auth/encoding';
import { hashPassword, PASSWORD_ALGORITHM, verifyPassword } from '../src/auth/password';

const hexBytes = (hex: string) => Uint8Array.from(hex.match(/../g)!, (byte) => parseInt(byte, 16));
const toHex = (bytes: Uint8Array) => Array.from(bytes, (byte) => byte.toString(16).padStart(2, '0')).join('');
const peppers = { v1: Uint8Array.from({ length: 32 }, (_, i) => i) };

describe('PBKDF2-HMAC-SHA256 with independent HMAC pepper', () => {
  it('matches RFC 7914 section 11 using the standard JS implementation', () => {
    expect(toHex(pbkdf2(sha256, 'passwd', 'salt', { c: 1, dkLen: 64 }))).toBe(
      '55ac046e56e3089fec1691c22544b605f94185216dde0465e68b9d57c20dacbc49ca9cccf179b645991664b39d77ef317c71b845b1e30bd509112041d3a19783',
    );
  });

  it('has at least three independently checked 10000-iteration vectors', () => {
    expect(vectors.filter((vector) => vector.iterations === 10000).length).toBeGreaterThanOrEqual(3);
  });

  it.each(vectors)('matches the fixed vector at $iterations iterations for "$password"', async (vector) => {
    const salt = hexBytes(vector.saltHex);
    const derived = pbkdf2(sha256, new TextEncoder().encode(vector.password), salt, { c: vector.iterations, dkLen: 32 });
    expect(toHex(derived)).toBe(vector.derivedHex);
    const record = {
      password_algorithm: PASSWORD_ALGORITHM,
      password_salt: base64UrlEncode(salt),
      password_hash: base64UrlEncode(hexBytes(vector.storedHex)),
      password_iterations: vector.iterations,
      pepper_version: 'v1',
    };
    expect(await verifyPassword(vector.password, record, { v1: hexBytes(vector.pepperHex) })).toBe(true);
    expect(await verifyPassword(vector.password + '!', record, peppers)).toBe(false);
    expect(await verifyPassword(vector.password, record, { v1: new Uint8Array(32) })).toBe(false);
  });

  it('generates independent random salts and verifies round trips', async () => {
    const a = await hashPassword('correct horse battery staple', { pepperVersion: 'v1', peppers });
    const b = await hashPassword('correct horse battery staple', { pepperVersion: 'v1', peppers });
    expect(a.password_iterations).toBe(10000);
    expect(a.password_salt).toHaveLength(22);
    expect(a.password_hash).toHaveLength(43);
    expect(a.password_salt).not.toBe(b.password_salt);
    expect(await verifyPassword('correct horse battery staple', a, peppers)).toBe(true);
  });

  it('preserves spaces and Unicode rather than normalizing a password', async () => {
    const record = await hashPassword('  café cafe\u0301 密码  ', { pepperVersion: 'v1', peppers });
    expect(await verifyPassword('  café cafe\u0301 密码  ', record, peppers)).toBe(true);
    expect(await verifyPassword('café cafe\u0301 密码', record, peppers)).toBe(false);
    expect(await verifyPassword('  café café 密码  ', record, peppers)).toBe(false);
  });

  it('rejects an iteration downgrade and a missing pepper version', async () => {
    await expect(hashPassword('password', { pepperVersion: 'v1', peppers, iterations: 9999 })).rejects.toThrow();
    await expect(hashPassword('password', { pepperVersion: 'v2', peppers })).rejects.toThrow();
  });
});
