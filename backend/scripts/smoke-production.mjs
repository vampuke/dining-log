// No dependencies, saved credentials, response dumps, or automatic retries.
import { randomBytes, randomUUID } from 'node:crypto';

const production = 'https://dining.alanvampuke.top/api/v1';
const base = process.env.DINING_SMOKE_BASE_URL ?? production;
const localCI = process.env.GITHUB_ACTIONS === 'true' && base === 'http://127.0.0.1:8787/api/v1';
if (base !== production && !localCI) throw new Error('Smoke requires the production host or the isolated Actions fixture');

function requireValue(condition, description) {
  if (!condition) throw new Error(`Smoke assertion failed: ${description}`);
}

async function request(step, method, path, expected, body, token, extraHeaders = {}) {
  let response;
  try {
    response = await fetch(base + path, {
      method, redirect: 'error', signal: AbortSignal.timeout(20000),
      headers: { ...(body === undefined ? {} : { 'Content-Type': 'application/json' }),
        ...(token ? { Authorization: `Bearer ${token}` } : {}),
        ...(['POST', 'PATCH', 'DELETE'].includes(method) ? { 'Idempotency-Key': randomUUID() } : {}),
        ...extraHeaders },
      ...(body === undefined ? {} : { body: JSON.stringify(body) }),
    });
  } catch {
    throw new Error(`Smoke ${step}: transport failed; stopped without retry`);
  }
  let payload = null;
  if (response.status !== 204) {
    try { payload = await response.json(); } catch { throw new Error(`Smoke ${step}: invalid JSON (HTTP ${response.status})`); }
  }
  if (response.status !== expected) {
    const code = payload?.error?.code;
    const safeCode = typeof code === 'string' && /^[A-Z_]{1,64}$/.test(code) ? code : 'UNKNOWN';
    throw new Error(`Smoke ${step}: HTTP ${response.status}, expected ${expected}, code=${safeCode}; stopped`);
  }
  if (path !== '/health') requireValue(response.headers.get('Cache-Control') === 'no-store', `${step} cache control`);
  console.log(JSON.stringify({ step, method, status: response.status }));
  return payload;
}

async function smoke() {
  const health = await request('health', 'GET', '/health', 200);
  requireValue(health?.status === 'ok' && health.api_version === 'v1', 'health contract');
  const username = `smoke_${randomBytes(10).toString('hex')}`;
  const password = randomBytes(32).toString('base64url');
  const device_name = 'production-smoke';
  const registered = (await request('register', 'POST', '/auth/register', 201, { username, password, device_name })).data;
  requireValue(registered?.recovery_codes?.length === 8 && registered.token_type === 'Bearer', 'registration credentials and recovery codes');
  let credentials = (await request('login', 'POST', '/auth/login', 200, { username, password, device_name })).data;
  requireValue(credentials.user.id === registered.user.id && credentials.session_id !== registered.session_id, 'login owner and independent session');

  const restaurantId = randomUUID(), dishId = randomUUID(), visitIds = [randomUUID(), randomUUID()];
  const restaurantPath = `/restaurants/${restaurantId}`;
  let restaurant = (await request('create-restaurant', 'POST', '/restaurants', 201, {
    id: restaurantId, name: 'Production smoke fixture', category: 'restaurant', address: 'Synthetic test address', rating: 4,
  }, credentials.access_token)).data.entity;
  requireValue(restaurant.id === restaurantId && restaurant.version === 1 && restaurant.rating === 4, 'created restaurant');
  const dish = (await request('create-dish', 'POST', `${restaurantPath}/dishes`, 201, {
    id: dishId, name: 'Synthetic dish', rating: 2.7,
  }, credentials.access_token)).data.entity;
  requireValue(dish.id === dishId && dish.restaurant_id === restaurantId && dish.rating === 2.7, 'independent dish rating');
  const date = new Date().toISOString().slice(0, 10);
  for (const [index, id] of visitIds.entries()) {
    const visit = (await request(`create-visit-${index + 1}`, 'POST', `${restaurantPath}/visits`, 201,
      { id, dining_date: date, meal: 'dinner' }, credentials.access_token)).data.entity;
    requireValue(visit.id === id && visit.dining_date === date && visit.restaurant_id === restaurantId, 'same-date separate visits');
  }
  const updated = (await request('update-rating', 'PATCH', restaurantPath, 200,
    { expected_version: restaurant.version, rating: 4.3 }, credentials.access_token)).data;
  restaurant = updated.entity;
  requireValue(updated.changed && restaurant.rating === 4.3 && restaurant.version === 2, 'rating/version update');
  const updateRevision = updated.revisions.find(revision => revision.action === 'update');
  requireValue(updateRevision?.changed_fields?.includes('rating_tenths'), 'mutation revision rating field');
  const history = (await request('list-history', 'GET', `${restaurantPath}/revisions?entity_type=restaurant`, 200,
    undefined, credentials.access_token)).data.items;
  requireValue(history.some(revision => revision.id === updateRevision.id && revision.entity_version_before === 1 && revision.entity_version_after === 2), 'persisted update revision');
  const revision = (await request('read-rating-diff', 'GET', `/revisions/${updateRevision.id}`, 200,
    undefined, credentials.access_token)).data;
  requireValue(revision.diff.some(change => change.field === 'rating_tenths' && change.before === 40 && change.after === 43), 'persisted exact rating diff 40 to 43');

  const deleted = (await request('delete-restaurant', 'DELETE', restaurantPath, 200,
    { expected_version: restaurant.version }, credentials.access_token)).data.entity;
  requireValue(deleted.version === 3 && typeof deleted.deleted_at === 'string', 'soft deletion/version');
  await request('deleted-hidden', 'GET', restaurantPath, 404, undefined, credentials.access_token);
  const deletedDetail = (await request('read-deleted', 'GET', `${restaurantPath}?deleted=include`, 200,
    undefined, credentials.access_token)).data;
  requireValue(deletedDetail.deleted_at !== null && deletedDetail.version === 3, 'include deleted');
  restaurant = (await request('restore-restaurant', 'POST', `${restaurantPath}/restore`, 200,
    { expected_version: deleted.version }, credentials.access_token)).data.entity;
  requireValue(restaurant.version === 4 && restaurant.deleted_at === null && restaurant.rating === 4.3, 'restore preserves rating');
  const detail = (await request('read-restored', 'GET', restaurantPath, 200, undefined, credentials.access_token)).data;
  requireValue(detail.stats.dish_count === 1 && detail.stats.visit_count === 2 && detail.rating === 4.3, 'restored children and restaurant rating');
  const restoredHistory = (await request('read-delete-restore-history', 'GET', `${restaurantPath}/revisions?entity_type=restaurant`, 200,
    undefined, credentials.access_token)).data.items;
  requireValue(['delete', 'restore'].every(action => restoredHistory.some(item => item.action === action)), 'delete/restore revisions');
  const priorRefresh = credentials.refresh_token;
  const refreshed = (await request('refresh', 'POST', '/auth/refresh', 200,
    { refresh_token: priorRefresh }, undefined, { 'X-Refresh-Request-Id': randomUUID() })).data;
  requireValue(refreshed.refresh_token !== priorRefresh && refreshed.session_id === credentials.session_id, 'refresh rotation');
  credentials = refreshed;
  const session = (await request('refreshed-session', 'GET', '/auth/session', 200, undefined, credentials.access_token)).data;
  requireValue(session.user.id === registered.user.id && session.session.id === credentials.session_id, 'refreshed identity');

  await request('cleanup-soft-delete', 'DELETE', restaurantPath, 200,
    { expected_version: restaurant.version }, credentials.access_token);
  await request('cleanup-registration-session', 'POST', '/auth/logout', 204, { refresh_token: registered.refresh_token });
  await request('logout', 'POST', '/auth/logout', 204, { refresh_token: credentials.refresh_token });
  const rejected = await request('old-access-rejected', 'GET', '/auth/session', 401, undefined, credentials.access_token);
  requireValue(rejected.error.code === 'SESSION_REVOKED', 'logout immediately revokes old access');
  console.log(JSON.stringify({ smoke: 'passed', rating_diff: [40, 43], visits: 2,
    cleanup: 'test restaurant soft-deleted; both sessions revoked; synthetic account retained' }));
}

try { await smoke(); } catch (error) {
  // Only our fixed diagnostic labels reach logs, never request/response bodies.
  console.error(error instanceof Error && error.message.startsWith('Smoke') ? error.message : 'Smoke failed; stopped without dumping credentials');
  process.exitCode = 1;
}
