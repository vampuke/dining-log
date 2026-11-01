import { Hono } from 'hono';
import type { AppEnv, Bindings } from './env';
import { cleanupAuth } from './auth/cleanup';
import { auth } from './routes/auth';
import { restaurants } from './routes/restaurants';
import { dishes } from './routes/dishes';
import { visits } from './routes/visits';
import { revisions } from './routes/revisions';
import { notImplemented } from './routes/placeholder';

const app = new Hono<AppEnv>();
const api = new Hono<AppEnv>();
api.get('/health', (c) => c.json({ status: 'ok', api_version: 'v1' }));
api.route('/', auth);
api.route('/', restaurants);
api.route('/', dishes);
api.route('/', visits);
api.route('/', revisions);
api.get('/search', notImplemented);
app.route('/api/v1', api);
app.notFound((c) => {
  c.header('Cache-Control', 'no-store');
  return c.json({ error: { code: 'NOT_FOUND', message: 'Not found.', field_errors: {} },
    meta: { request_id: crypto.randomUUID() } }, 404);
});
export default Object.assign(app, {
  async scheduled(_controller: ScheduledController, env: Bindings) { await cleanupAuth(env.DB); },
});
