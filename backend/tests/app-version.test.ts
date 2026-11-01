import { describe, expect, it } from 'vitest';
import app from '../src/index';
import type { Bindings } from '../src/env';

const config = {
  APP_VERSION_CODE: '6', APP_VERSION_NAME: '0.6.0',
  APP_DOWNLOAD_URL: 'https://github.com/vampuke/dining-log/releases/download/v0.6.0/app-release.apk',
  APP_APK_SHA256: 'a'.repeat(64), APP_RELEASE_NOTES: '账号与交互优化', APP_RELEASED_AT: '1793544038',
};
function request(overrides = {}, headers?: Record<string, string>) {
  const forbiddenDB = new Proxy({} as D1Database, { get() { throw new Error('Version endpoint accessed D1'); } });
  // No auth bindings are provided: the public endpoint must be independent.
  return app.request('/api/v1/app/version', { headers }, { DB: forbiddenDB, ...config, ...overrides } as Bindings);
}
describe('public app version from Worker vars', () => {
  it('requires no login or D1 and returns exactly the configured public fields', async () => {
    const response = await request(); expect(response.status).toBe(200);
    expect(response.headers.get('Cache-Control')).toBe('public, max-age=300');
    expect(await response.json()).toEqual({ latest: {
      version_code: 6, version_name: '0.6.0', url: config.APP_DOWNLOAD_URL,
      sha256: config.APP_APK_SHA256, notes: config.APP_RELEASE_NOTES, released_at: 1793544038,
    } });
  });
  it('reads changed vars and ignores an invalid Authorization header', async () => {
    const response = await request({ APP_VERSION_CODE: '10', APP_VERSION_NAME: '0.10.0', APP_RELEASE_NOTES: '新的发布说明' }, { Authorization: 'invalid' });
    expect(response.status).toBe(200);
    expect(await response.json()).toEqual({ latest: {
      version_code: 10, version_name: '0.10.0', url: config.APP_DOWNLOAD_URL,
      sha256: config.APP_APK_SHA256, notes: '新的发布说明', released_at: 1793544038,
    } });
  });
  it.each([{ APP_VERSION_CODE: undefined }, { APP_VERSION_CODE: '0' }, { APP_VERSION_CODE: '1.5' },
    { APP_DOWNLOAD_URL: 'http://example.invalid/app.apk' }, { APP_DOWNLOAD_URL: 'https://userinfo@example.invalid/app.apk' },
    { APP_APK_SHA256: 'bad' }, { APP_RELEASED_AT: '' }])('does not advertise an invalid release configuration %j', async overrides => {
    const response = await request(overrides); expect(response.status).toBe(503);
    expect(response.headers.get('Cache-Control')).toBe('no-store');
    const result = await response.json() as { error: { code: string } };
    expect(result.error.code).toBe('TEMPORARILY_UNAVAILABLE');
    expect(result).not.toHaveProperty('latest');
  });
});
