import type { Context } from 'hono';
import type { AppEnv } from '../env';

// P1 only: no business error code is invented outside PLAN.md.
export function notImplemented(c: Context<AppEnv>) {
  c.header('Cache-Control', 'no-store');
  return c.json({ status: 'not_implemented', api_version: 'v1' }, 501);
}
