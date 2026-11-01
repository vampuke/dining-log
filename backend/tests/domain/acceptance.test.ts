import { applyD1Migrations, env } from 'cloudflare:test';
import { beforeAll, describe, expect, it, vi } from 'vitest';
import { account, counts, data, error, request, restaurant, restaurantInput, type Entity, type Mutation, type Revision } from './helpers';
import { cleanupDomain } from '../../src/domain/cleanup';
import { cleanupAuth } from '../../src/auth/cleanup';
beforeAll(async () => { await applyD1Migrations(env.DB, env.TEST_MIGRATIONS); });
type Page<T> = { items: T[] };
async function requestChild(token: string, id: string, kind: string, extra: object) { return request(`/restaurants/${id}/${kind}`, token, { id: crypto.randomUUID(), ...(kind === 'dishes' ? { name: 'Dish' } : { dining_date: '2024-02-29' }), ...extra }); }
async function makeChild(token: string, id: string, kind: 'dishes' | 'visits', extra = {}) { return data<Mutation>(await requestChild(token, id, kind, extra), 201); }
describe('P3 acceptance CRUD ownership and history', () => {
  it('S1 plain address five restaurant dishes three visits including same day', async () => {
    const a = await account(), t = a.access_token, r = await restaurant(t);
    for (let i = 0; i < 5; i++) await makeChild(t, r.entity.id, 'dishes', { name: `Dish ${i}` });
    for (const dining_date of ['2024-02-29', '2024-02-29', '2024-03-01']) await makeChild(t, r.entity.id, 'visits', { dining_date });
    const dishes = await data<Page<Entity>>(await request(`/restaurants/${r.entity.id}/dishes`, t));
    expect(dishes.items).toHaveLength(5); expect(dishes.items.every(d => d.restaurant_id === r.entity.id)).toBe(true);
    const visits = await data<Page<Entity & { dining_date: string }>>(await request('/visits', t)); expect(visits.items).toHaveLength(3); expect(visits.items.map(v => v.dining_date)).toEqual(['2024-03-01', '2024-02-29', '2024-02-29']);
    const detail = await data<Entity & { stats: object }>(await request(`/restaurants/${r.entity.id}`, t)); expect(detail.address).toBe('18 Zhongshan Road, 2F'); expect(detail.stats).toEqual({ dish_count: 5, visit_count: 3, last_visit_date: '2024-03-01' });
    await error(await request('/dishes', t), 404, 'NOT_FOUND');
    for (const forbidden of ['visit_id', 'dish_visit']) await error(await request(`/restaurants/${r.entity.id}/dishes`, t, { id: crypto.randomUUID(), name: 'bad', [forbidden]: crypto.randomUUID() }), 400, 'UNKNOWN_FIELD');
  });
  it('S8 all account lists IDs children history restore and sessions isolate owners', async () => {
    const a = await account(), b = await account('bob'), t = a.access_token, r = await restaurant(b.access_token, { name: 'Private Bob' });
    const d = await makeChild(b.access_token, r.entity.id, 'dishes'), v = await makeChild(b.access_token, r.entity.id, 'visits');
    for (const path of ['/restaurants', '/visits', '/revisions']) expect((await data<Page<Entity>>(await request(path, t))).items).toEqual([]);
    const paths = [`/restaurants/${r.entity.id}`, `/restaurants/${r.entity.id}/dishes`, `/restaurants/${r.entity.id}/visits`, `/restaurants/${r.entity.id}/revisions`, `/dishes/${d.entity.id}`, `/visits/${v.entity.id}`, `/dishes/${d.entity.id}/revisions`, `/visits/${v.entity.id}/revisions`, `/revisions/${r.revisions[0].id}`, `/revisions?restaurant_id=${r.entity.id}`, `/visits?restaurant_id=${r.entity.id}`, `/revisions?entity_type=dish&entity_id=${d.entity.id}`];
    for (const path of paths) { const res = await request(path, t); expect(res.status).toBe(404); expect(await res.text()).not.toContain('Private Bob'); }
    for (const [path, payload, method] of [[`/restaurants/${r.entity.id}`, { expected_version: 1, name: 'intrusion' }, 'PATCH'], [`/restaurants/${r.entity.id}/dishes`, { id: crypto.randomUUID(), name: 'intrusion' }, 'POST'], [`/dishes/${d.entity.id}`, { expected_version: 1 }, 'DELETE'], [`/visits/${v.entity.id}/restore`, { expected_version: 1 }, 'POST'], [`/restaurants/${r.entity.id}/restore`, { expected_version: 1 }, 'POST'], [`/revisions/${r.revisions[0].id}/restore`, { expected_metadata_version: 1 }, 'POST'], [`/auth/sessions/${b.session_id}`, undefined, 'DELETE']] as const) { const res = await request(path, t, payload, method); expect(res.status).toBe(404); expect(await res.text()).not.toContain('Private Bob'); }
    const sessions = await data<Page<{ id: string }>>(await request('/auth/sessions', t)); expect(sessions.items.every(s => s.id !== b.session_id)).toBe(true);
  });
  it('S9 parent delete excludes search and every child CRUD restore preserves child tombstone', async () => {
    const a = await account(), t = a.access_token, r = await restaurant(t), d = await makeChild(t, r.entity.id, 'dishes'), live = await makeChild(t, r.entity.id, 'dishes'), v = await makeChild(t, r.entity.id, 'visits');
    await data(await request(`/dishes/${d.entity.id}`, t, { expected_version: 1 }, 'DELETE'));
    const deleted = await data<Mutation>(await request(`/restaurants/${r.entity.id}`, t, { expected_version: 1 }, 'DELETE'));
    const delRev = await data<Revision>(await request(`/revisions/${deleted.revisions[0].id}`, t)); expect(delRev.diff).toEqual([{ field: 'deleted_at', before: null, after: expect.any(Number) }]);
    expect((await data<{ restaurants: unknown[] }>(await request('/search?q=Bistro', t))).restaurants).toEqual([]);
    for (const kind of ['dishes', 'visits'] as const) {
      const id = kind === 'dishes' ? live.entity.id : v.entity.id;
      for (const [path, payload, method] of [[`/restaurants/${r.entity.id}/${kind}`, undefined, 'GET'], [`/restaurants/${r.entity.id}/${kind}`, { id: crypto.randomUUID(), ...(kind === 'dishes' ? { name: 'blocked' } : { dining_date: '2024-02-29' }) }, 'POST'], [`/${kind}/${id}`, undefined, 'GET'], [`/${kind}/${id}`, { expected_version: 1, ...(kind === 'dishes' ? { name: 'blocked' } : { note: 'blocked' }) }, 'PATCH'], [`/${kind}/${id}`, { expected_version: 1 }, 'DELETE'], [`/${kind}/${id}/restore`, { expected_version: 1 }, 'POST']] as const) await error(await request(path, t, payload, method), 404, 'PARENT_UNAVAILABLE');
    }
    const restored = await data<Mutation>(await request(`/restaurants/${r.entity.id}/restore`, t, { expected_version: 2 })); expect(restored.entity.version).toBe(3);
    const restoreRev = await data<Revision>(await request(`/revisions/${restored.revisions[0].id}`, t)); expect(restoreRev.diff).toEqual([{ field: 'deleted_at', before: expect.any(Number), after: null }]);
    expect((await data<Page<Entity>>(await request(`/restaurants/${r.entity.id}/dishes`, t))).items.map(d => d.id)).toEqual([live.entity.id]);
    await error(await request(`/dishes/${d.entity.id}`, t), 404, 'NOT_FOUND');
    expect((await data<Page<Entity>>(await request(`/restaurants/${r.entity.id}/dishes?deleted=only`, t))).items.map(d => d.id)).toEqual([d.entity.id]);
    expect((await data<{ restaurants: unknown[] }>(await request('/search?q=Bistro', t))).restaurants).toHaveLength(1);
  });
  it('S10 restore exact thirty day boundary and bounded purge preserve other accounts', async () => {
    const a = await account(), b = await account('bob'), t = a.access_token, keep = await restaurant(b.access_token), ownKeep = await restaurant(t), old = await restaurant(t);
    await data<Mutation>(await request(`/restaurants/${old.entity.id}`, t, { expected_version: 1 }, 'DELETE'));
    const real = Date.now();
    vi.spyOn(Date, 'now').mockReturnValue(real);
    try {
    await env.DB.prepare('UPDATE restaurants SET deleted_at=?,created_at=?,updated_at=? WHERE id=?').bind(real - 30 * 86400000, real - 31 * 86400000, real - 30 * 86400000, old.entity.id).run();
    await error(await request(`/restaurants/${old.entity.id}/restore`, t, { expected_version: 2 }), 410, 'RESTORE_WINDOW_EXPIRED');
    // Force >20 expired children through several bounded cleanup calls.
    for (let i = 0; i < 25; i++) await env.DB.prepare('INSERT INTO dishes (id,user_id,restaurant_id,name,created_at,updated_at) VALUES (?,?,?,?,?,?)').bind(crypto.randomUUID(), a.user.id, old.entity.id, 'purge child', real - 31 * 86400000, real - 31 * 86400000).run();
    await cleanupDomain(env.DB, real);
    expect(await env.DB.prepare('SELECT count(*) n FROM dishes WHERE restaurant_id=?').bind(old.entity.id).first('n')).toBe(5);
    for (let i = 0; i < 8; i++) await cleanupDomain(env.DB, real);
    expect(await env.DB.prepare('SELECT id FROM restaurants WHERE id=?').bind(old.entity.id).first()).toBeNull();
    expect((await data<Entity>(await request(`/restaurants/${keep.entity.id}`, b.access_token))).id).toBe(keep.entity.id);
    expect((await data<Entity>(await request(`/restaurants/${ownKeep.entity.id}`, t))).id).toBe(ownKeep.entity.id);
    expect((await env.DB.prepare('PRAGMA foreign_key_check').all()).results).toEqual([]);
    } finally { vi.restoreAllMocks(); }
  });
  it('restore succeeds just inside thirty days without a session clock jump', async () => {
    const a = await account(), r = await restaurant(a.access_token), now = Date.now();
    vi.spyOn(Date, 'now').mockReturnValue(now);
    try {
    await env.DB.prepare('UPDATE restaurants SET created_at=?,updated_at=?,deleted_at=? WHERE id=?').bind(now - 31 * 86400000, now - 30 * 86400000 + 1, now - 30 * 86400000 + 1, r.entity.id).run();
    expect((await data<Mutation>(await request(`/restaurants/${r.entity.id}/restore`, a.access_token, { expected_version: 1 }))).entity.deleted_at).toBeNull();
    } finally { vi.restoreAllMocks(); }
  });
  it('S11 revision read note hide restore forbid diff and original timestamps', async () => {
    const a = await account(), t = a.access_token, r = await restaurant(t), id = r.revisions[0].id, original = await data<Revision>(await request(`/revisions/${id}`, t));
    for (const field of ['diff', 'diff_json', 'created_at', 'entity_version_before', 'source_revision_id']) await error(await request(`/revisions/${id}`, t, { expected_metadata_version: 1, note: 'x', [field]: 'forged' }, 'PATCH'), 400, 'UNKNOWN_FIELD');
    const note = await data<{ metadata_version: number }>(await request(`/revisions/${id}`, t, { expected_metadata_version: 1, note: '  Reason\r\n  details  ' }, 'PATCH')); expect(note.metadata_version).toBe(2);
    await error(await request(`/revisions/${id}`, t, { expected_metadata_version: 1, note: 'stale' }, 'PATCH'), 409, 'VERSION_CONFLICT');
    await data(await request(`/revisions/${id}`, t, { expected_metadata_version: 2 }, 'DELETE')); await error(await request(`/revisions/${id}`, t), 404, 'NOT_FOUND');
    expect((await data<Page<Revision>>(await request('/revisions', t))).items).toEqual([]);
    expect((await data<Revision>(await request(`/revisions/${id}?deleted=include`, t))).diff).toEqual(original.diff);
    await data(await request(`/revisions/${id}/restore`, t, { expected_metadata_version: 3 }));
    const restored = await data<Revision & { note: string; created_at: string }>(await request(`/revisions/${id}`, t)); expect(restored.diff).toEqual(original.diff); expect(restored.note).toBe('Reason\n  details'); expect(await counts()).toMatchObject({ revisions: 1, change_sets: 1 });
  });
  it('S12 reapply recomputes current diff and preserves unselected fields and ownership', async () => {
    const a = await account(), b = await account('bob'), t = a.access_token, r = await restaurant(t, { rating: 4 });
    const change = await data<Mutation>(await request(`/restaurants/${r.entity.id}`, t, { expected_version: 1, rating: 4.3, review: 'Source' }, 'PATCH'));
    await data(await request(`/restaurants/${r.entity.id}`, t, { expected_version: 2, rating: 5, note: 'Keep this', review: 'Current' }, 'PATCH'));
    const source = change.revisions[0].id, body = { source_revision_id: source, side: 'before', fields: ['rating_tenths'], expected_version: 3 };
    const applied = await data<{ entity: Entity; revision: Revision; changed: boolean }>(await request('/revisions', t, body), 201);
    expect(applied.entity).toMatchObject({ rating: 4, review: 'Current', note: 'Keep this', version: 4 }); expect(applied.revision.source_revision_id).toBe(source); expect(applied.revision.diff).toEqual([{ field: 'rating_tenths', before: 50, after: 40 }]);
    const same = await data<{ revision: null; change_set_id: null; changed: boolean }>(await request('/revisions', t, { ...body, expected_version: 4 })); expect(same).toMatchObject({ revision: null, change_set_id: null, changed: false });
    await error(await request('/revisions', b.access_token, { ...body, expected_version: 4 }), 404, 'NOT_FOUND');
    await expect(env.DB.prepare('INSERT INTO dishes (id,user_id,restaurant_id,name,created_at,updated_at) VALUES (?,?,?,?,?,?)').bind(crypto.randomUUID(), b.user.id, r.entity.id, 'cross owner', Date.now(), Date.now()).run()).rejects.toThrow(/foreign key/i);
    await data(await request(`/restaurants/${r.entity.id}`, t, { expected_version: 4 }, 'DELETE'));
    await error(await request('/revisions', t, { ...body, expected_version: 5 }), 404, 'NOT_FOUND');
  });
  it('reapply rejects create delete restore sources duplicates empty excessive and forbidden fields', async () => {
    const a = await account(), t = a.access_token, r = await restaurant(t);
    const changed = await data<Mutation>(await request(`/restaurants/${r.entity.id}`, t, { expected_version: 1, name: 'New' }, 'PATCH'));
    const base = { source_revision_id: changed.revisions[0].id, side: 'before', expected_version: 2 };
    for (const fields of [[], ['name', 'name'], Array.from({ length: 8 }, (_, i) => `field${i}`), ['id'], ['user_id'], ['owner'], ['version'], ['updated_at'], ['deleted_at'], ['review']]) await error(await request('/revisions', t, { ...base, fields }), 400, 'VALIDATION_ERROR');
    await error(await request('/revisions', t, { ...base, fields: ['name'], source_revision_id: r.revisions[0].id }), 400, 'VALIDATION_ERROR');
    const del = await data<Mutation>(await request(`/restaurants/${r.entity.id}`, t, { expected_version: 2 }, 'DELETE'));
    const restore = await data<Mutation>(await request(`/restaurants/${r.entity.id}/restore`, t, { expected_version: 3 }));
    for (const source of [del, restore]) await error(await request('/revisions', t, { ...base, expected_version: 4, fields: ['deleted_at'], source_revision_id: source.revisions[0].id }), 400, 'VALIDATION_ERROR');
  });
  it('S15 long text internal newlines null unrated and leap day round trip explicit errors', async () => {
    const a = await account(), t = a.access_token, review = '行\n  文'.repeat(800), r = await restaurant(t, { review, note: null, rating: null });
    expect((await data<Entity>(await request(`/restaurants/${r.entity.id}`, t)))).toMatchObject({ review, rating: null, note: null });
    const v = await makeChild(t, r.entity.id, 'visits', { dining_date: '2024-02-29', note: ' line\r\n  two ' }); expect(await data(await request(`/visits/${v.entity.id}`, t))).toMatchObject({ dining_date: '2024-02-29', note: 'line\n  two' });
    for (const rating of [0, 5.1, 4.33, '4.3']) await error(await request('/restaurants', t, restaurantInput({ rating })), 400, 'VALIDATION_ERROR');
    for (const dining_date of ['2023-02-29', '2026-02-30', '2026-13-01', '1899-12-31', '2101-01-01', '2026-1-1']) await error(await request(`/restaurants/${r.entity.id}/visits`, t, { id: crypto.randomUUID(), dining_date }), 400, 'VALIDATION_ERROR');
    await error(await request('/restaurants', t, restaurantInput({ review: '文'.repeat(4001) })), 400, 'VALIDATION_ERROR');
    await error(await request(`/restaurants/${r.entity.id}`, t, { review: 'no version' }, 'PATCH'), 400, 'MISSING_VERSION');
    await error(await request(`/restaurants/${r.entity.id}`, t, { expected_version: 1, rating_tenths: 40.5 }, 'PATCH'), 400, 'UNKNOWN_FIELD');
    await expect(env.DB.prepare('UPDATE restaurants SET rating_tenths=? WHERE id=?').bind(40.5, r.entity.id).run()).rejects.toThrow(/constraint/i);
    await error(await request('/restaurants', t, restaurantInput({ review: 'x'.repeat(70000) })), 413, 'PAYLOAD_TOO_LARGE');
  });
  it('business cache lasts seven days and P2 auth janitor cannot erase it early', async () => {
    const a = await account(), t = a.access_token, key = crypto.randomUUID(), input = restaurantInput();
    const first = await request('/restaurants', t, input, 'POST', key); const original = await first.json();
    const row = await env.DB.prepare('SELECT created_at,expires_at FROM idempotency_keys WHERE user_id=? AND idempotency_key=?').bind(a.user.id, key).first<{ created_at: number; expires_at: number }>(); expect(row!.expires_at - row!.created_at).toBe(7 * 86400000);
    await cleanupAuth(env.DB); expect(await (await request('/restaurants', t, input, 'POST', key)).json()).toEqual(original);
    await env.DB.prepare('UPDATE idempotency_keys SET created_at=?,expires_at=? WHERE user_id=? AND idempotency_key=?').bind(Date.now() - 8 * 86400000, Date.now() - 1, a.user.id, key).run();
    await error(await request('/restaurants', t, input, 'POST', key), 409, 'IDEMPOTENCY_CONFLICT');
  });
});
