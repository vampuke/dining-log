import { describe, expect, it } from 'vitest';
import { decodeJwt, decodeProtectedHeader, SignJWT } from 'jose';
import { base64UrlEncode } from '../src/auth/encoding';
import { signAccessToken, verifyAccessToken, type JwtConfig } from '../src/auth/jwt';

// Sequential-byte public fixtures; not production signing material.
const bytes = Uint8Array.from({ length: 32 }, (_, index) => index);
const config: JwtConfig = {
  issuer: 'https://dining.alanvampuke.top',
  audience: 'dining-log-android',
  activeKid: 'v1',
  keys: { v1: base64UrlEncode(bytes) },
};
const claims = {
  sub: '00000000-0000-4000-8000-000000000001',
  sid: '70000000-0000-4000-8000-000000000001',
  av: 1,
};

describe('access JWT cryptographic foundation', () => {
  it('signs HS256 with allowlisted kid and a 600-second lifetime', async () => {
    const token = await signAccessToken(claims, config);
    const header = decodeProtectedHeader(token);
    const payload = decodeJwt(token);
    expect(header.alg).toBe('HS256');
    expect(header.kid).toBe('v1');
    expect(payload.exp! - payload.iat!).toBe(600);
    expect(await verifyAccessToken(token, config)).toEqual(claims);
  });

  it('rejects altered signatures and non-allowlisted kid', async () => {
    const token = await signAccessToken(claims, config);
    const parts = token.split('.');
    parts[2] = (parts[2][0] === 'A' ? 'B' : 'A') + parts[2].slice(1);
    await expect(verifyAccessToken(parts.join('.'), config)).rejects.toThrow();
    await expect(verifyAccessToken(token, { ...config, keys: {} })).rejects.toThrow('Unknown JWT kid');
  });

  it('rejects incorrect issuer, expired token and oversized token', async () => {
    const now = Math.floor(Date.now() / 1000);
    const makeToken = (issuer: string, issuedAt: number) => new SignJWT({ sid: claims.sid, av: claims.av })
      .setProtectedHeader({ alg: 'HS256', kid: 'v1' }).setSubject(claims.sub)
      .setIssuer(issuer).setAudience(config.audience).setJti(crypto.randomUUID())
      .setIssuedAt(issuedAt).setExpirationTime(issuedAt + 600).sign(bytes);
    await expect(verifyAccessToken(await makeToken('https://invalid.example', now), config)).rejects.toThrow();
    await expect(verifyAccessToken(await makeToken(config.issuer, now - 700), config)).rejects.toThrow();
    await expect(verifyAccessToken('x'.repeat(2049), config)).rejects.toThrow('2 KiB');
  });
});
