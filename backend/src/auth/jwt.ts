import { decodeProtectedHeader, jwtVerify, SignJWT } from 'jose';
import { base64UrlDecode } from './encoding';

export interface JwtConfig {
  issuer: 'https://dining.alanvampuke.top';
  audience: 'dining-log-android';
  activeKid: string;
  keys: Readonly<Record<string, string>>; // Base64URL 256-bit keys supplied by Worker secrets.
}
export interface AccessClaims { sub: string; sid: string; av: number }
const uuidV4 = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;
function validateClaims(claims: AccessClaims): void {
  if (!uuidV4.test(claims.sub) || !uuidV4.test(claims.sid) || !Number.isSafeInteger(claims.av) || claims.av < 1) {
    throw new Error('Invalid access claims');
  }
}
function key(config: JwtConfig, kid: string): Uint8Array {
  if (!Object.hasOwn(config.keys, kid)) throw new Error('Unknown JWT kid');
  const bytes = base64UrlDecode(config.keys[kid]);
  if (bytes.length < 32) throw new Error('JWT key must be at least 256 bits');
  return bytes;
}
export async function signAccessToken(claims: AccessClaims, config: JwtConfig): Promise<string> {
  validateClaims(claims);
  const now = Math.floor(Date.now() / 1000);
  return new SignJWT({ sid: claims.sid, av: claims.av })
    .setProtectedHeader({ alg: 'HS256', kid: config.activeKid, typ: 'JWT' })
    .setIssuer(config.issuer).setAudience(config.audience).setSubject(claims.sub)
    .setJti(crypto.randomUUID()).setIssuedAt(now).setExpirationTime(now + 600)
    .sign(key(config, config.activeKid));
}
// Cryptographic validation only; P2 must subsequently JOIN sessions + users in D1
// to validate revocation, expiry, ownership and both auth_version values.
export async function verifyAccessToken(token: string, config: JwtConfig): Promise<AccessClaims> {
  if (new TextEncoder().encode(token).length > 2048) throw new Error('Access token exceeds 2 KiB');
  const header = decodeProtectedHeader(token);
  if (header.alg !== 'HS256' || typeof header.kid !== 'string' || header.jku !== undefined || header.x5u !== undefined || header.jwk !== undefined) {
    throw new Error('Invalid JWT header');
  }
  const { payload } = await jwtVerify(token, key(config, header.kid), {
    algorithms: ['HS256'], issuer: config.issuer, audience: config.audience,
    clockTolerance: 30, requiredClaims: ['exp', 'iat', 'sub', 'sid', 'av', 'jti'], maxTokenAge: '10m',
  });
  if (typeof payload.sub !== 'string' || typeof payload.sid !== 'string' || typeof payload.av !== 'number' ||
      typeof payload.iat !== 'number' || !Number.isInteger(payload.iat) || typeof payload.exp !== 'number' ||
      !Number.isInteger(payload.exp) || payload.exp !== payload.iat + 600 || payload.iat > Math.floor(Date.now() / 1000) + 30 ||
      typeof payload.jti !== 'string' || !uuidV4.test(payload.jti)) throw new Error('Invalid JWT claims');
  const claims = { sub: payload.sub, sid: payload.sid, av: payload.av };
  validateClaims(claims);
  return claims;
}
