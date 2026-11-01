import { Hono, type Context } from 'hono';
import { z } from 'zod';
import type { AppEnv } from '../env';
import { AuthError } from '../auth/config';
import { accessIdentity } from '../auth/session';
import { body, cached, json } from '../auth/routes';
import { BusinessIdempotency } from '../auth/idempotency';
import { id, parse } from './schemas';
export type C = Context<AppEnv>;
export function router() {
  const app = new Hono<AppEnv>();
  app.use('*', async (c, next) => {
    c.header('Cache-Control', 'no-store');
    // Hono merges middleware from sibling routers. Verify once for a matched
    // endpoint; unmatched paths continue to the common 404 handler.
    if (c.req.matchedRoutes.some(route => !route.path.endsWith('*')) && !c.get('userId')) {
      const { user } = await accessIdentity(c.env, c.req.header('Authorization'));
      c.set('userId', user.id);
    }
    await next();
  });
  app.onError((e, c) => {
    const error = e instanceof AuthError ? e : new AuthError(500, 'INTERNAL_ERROR', 'Unable to complete the request.');
    return json(c, { error: { code: error.code, message: error.message, field_errors: error.fieldErrors, ...(error.current === undefined ? {} : { current: error.current }) }, meta: { request_id: crypto.randomUUID() } }, error.status);
  }); return app;
}
export async function input<T extends z.ZodTypeAny>(c: C, schema: T) { return parse(schema, await body(c, z.unknown())); }
export function pathId(c: C, name: string) { return parse(id, c.req.param(name)); }
export async function idempotency(c: C, payload: unknown) { const key = parse(id, c.req.header('Idempotency-Key')); return BusinessIdempotency.create(c.env.DB, c.get('userId'), key, c.req.method, c.req.path, payload); }
export async function withIdempotency(c: C, idem: BusinessIdempotency, work: () => Promise<Response>): Promise<Response> {
  const replay = await cached(c, idem); if (replay) return replay;
  try { return await work(); }
  catch (error) {
    // A winner can commit after the cache miss but before entity/parent/source
    // preflight. Replay it before exposing that now-stale preflight error.
    // cached also verifies the fingerprint before returning any result.
    const replay = await cached(c, idem); if (replay) return replay;
    throw error;
  }
}
export const notFound = () => new AuthError(404, 'NOT_FOUND', 'Record not found.');
export const parentUnavailable = () => new AuthError(404, 'PARENT_UNAVAILABLE', 'Parent restaurant is unavailable.');
export function conflict(current: unknown) { return new AuthError(409, 'VERSION_CONFLICT', 'The record has changed. Reload before saving.', undefined, current); }
