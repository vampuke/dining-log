import { Hono } from 'hono';
import { z } from 'zod';
import type { AppEnv } from '../env';

const releaseSchema = z.object({
  version_code: z.coerce.number().int().positive().max(2147483647),
  version_name: z.string().trim().min(1).max(80),
  url: z.string().url().refine(value => {
    const url = new URL(value);
    return url.protocol === 'https:' && url.hostname.length > 0 && !url.username && !url.password;
  }),
  sha256: z.string().regex(/^[a-f0-9]{64}$/i).transform(value => value.toLowerCase()),
  notes: z.string().max(4000),
  released_at: z.coerce.number().int().positive().max(Number.MAX_SAFE_INTEGER),
}).strict();

export const appVersion = new Hono<AppEnv>();
appVersion.get('/app/version', c => {
  // Public configuration only: no credentials, owner identity or D1 access.
  const result = releaseSchema.safeParse({
    version_code: c.env.APP_VERSION_CODE, version_name: c.env.APP_VERSION_NAME,
    url: c.env.APP_DOWNLOAD_URL, sha256: c.env.APP_APK_SHA256,
    notes: c.env.APP_RELEASE_NOTES, released_at: c.env.APP_RELEASED_AT,
  });
  if (!result.success) {
    c.header('Cache-Control', 'no-store');
    return c.json({ error: { code: 'TEMPORARILY_UNAVAILABLE', message: 'Release metadata is unavailable.', field_errors: {} },
      meta: { request_id: crypto.randomUUID() } }, 503);
  }
  c.header('Cache-Control', 'public, max-age=300');
  return c.json({ latest: result.data });
});
