import type { Bindings } from '../env';
import { base64UrlDecode, base64UrlEncode } from './encoding';
export interface SealedResult { encryption_key_version: string; nonce: string; ciphertext: string }
async function key(env: Bindings, version: string): Promise<CryptoKey> {
  const keys: Record<string, string> = JSON.parse(env.REFRESH_RETRY_KEYS);
  if (!Object.hasOwn(keys, version)) throw new Error('Unknown retry key');
  const bytes = base64UrlDecode(keys[version]);
  if (bytes.length !== 32) throw new Error('Invalid retry key');
  return crypto.subtle.importKey('raw', bytes, 'AES-GCM', false, ['encrypt', 'decrypt']);
}
export async function seal(env: Bindings, value: unknown, aad: string): Promise<SealedResult> {
  const version = env.REFRESH_RETRY_KEY_ID;
  const nonce = crypto.getRandomValues(new Uint8Array(12));
  const ciphertext = await crypto.subtle.encrypt({ name: 'AES-GCM', iv: nonce, additionalData: new TextEncoder().encode(aad) }, await key(env, version), new TextEncoder().encode(JSON.stringify(value)));
  return { encryption_key_version: version, nonce: base64UrlEncode(nonce), ciphertext: base64UrlEncode(new Uint8Array(ciphertext)) };
}
export async function unseal<T>(env: Bindings, row: SealedResult, aad: string): Promise<T> {
  const bytes = await crypto.subtle.decrypt({ name: 'AES-GCM', iv: base64UrlDecode(row.nonce), additionalData: new TextEncoder().encode(aad) }, await key(env, row.encryption_key_version), base64UrlDecode(row.ciphertext));
  return JSON.parse(new TextDecoder().decode(bytes)) as T;
}
