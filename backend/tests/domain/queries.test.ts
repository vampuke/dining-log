import { applyD1Migrations, env } from 'cloudflare:test';
import { beforeAll, describe, expect, it } from 'vitest';
import { base64UrlDecode, base64UrlEncode } from '../../src/auth/encoding';
import { account, batchDB, data, error, request, restaurant, type Entity, type Mutation, type Revision } from './helpers';
beforeAll(async () => { await applyD1Migrations(env.DB, env.TEST_MIGRATIONS); });
type Page<T> = { items: T[] };
type Search = { restaurants: { restaurant: Entity; matched_fields: string[] }[]; visits: Entity[]; revisions: Revision[] };
async function search(token: string, q: string) { return data<Search>(await request(`/search?q=${encodeURIComponent(q)}`, token)); }
describe('P3 literal search filters and keyset pagination', () => {
  it('S13 normalized Chinese English fullwidth multiple keywords and literal percent underscore hits', async () => {
    const a = await account(), t = a.access_token, r = await restaurant(t, { name: '川菜 ＢＩＳＴＲＯ', cuisine: 'ＳＩＣＨＵＡＮ', review: '鲜香 SERVICE 100%', note: '星期一 low_cost' });
    const d = await data<Mutation>(await request(`/restaurants/${r.entity.id}/dishes`, t, { id: crypto.randomUUID(), name: '招牌 ＴＯＦＵ', review: 'CRISPY 豆腐' }), 201);
    for (const [q, field] of [['川菜', 'restaurant'], ['bistro', 'restaurant'], ['ＴＯＦＵ', 'dish'], ['crispy', 'review'], ['service', 'review'], ['星期一', 'note'], ['%', 'review'], ['_', 'note']] as const) { const found = await search(t, q); expect(found.restaurants.map(x => x.restaurant.id)).toEqual([r.entity.id]); expect(found.restaurants[0].matched_fields).toContain(field); }
    expect((await search(t, 'ＢＩＳＴＲＯ 豆腐')).restaurants).toHaveLength(1);
    expect((await search(t, 'BISTRO missing')).restaurants).toEqual([]);
    const plain = await restaurant(t, { name: 'Another', review: '100x', note: 'lowxcost' });
    expect((await search(t, '%')).restaurants.map(x => x.restaurant.id)).not.toContain(plain.entity.id); expect((await search(t, '_')).restaurants.map(x => x.restaurant.id)).not.toContain(plain.entity.id);
    expect((await data<Page<Entity>>(await request('/restaurants?cuisine=sichuan', t))).items.map(x => x.id)).toEqual([r.entity.id]);
    expect((await data<Page<Entity>>(await request(`/restaurants/${r.entity.id}/dishes?q=tofu`, t))).items.map(x => x.id)).toEqual([d.entity.id]);
  });
  it('search index rebuilds with sibling concurrent changes and delete restore in the same batch', async () => {
    const a = await account(), t = a.access_token, r = await restaurant(t);
    let entered!: () => void, release!: () => void; const ready = new Promise<void>(resolve => entered = resolve), gate = new Promise<void>(resolve => release = resolve);
    const db = batchDB(async statements => { entered(); await gate; return env.DB.batch(statements); });
    const pending = request(`/restaurants/${r.entity.id}/dishes`, t, { id: crypto.randomUUID(), name: 'alphaunique' }, 'POST', crypto.randomUUID(), db);
    await ready; const other = await data<Mutation>(await request(`/restaurants/${r.entity.id}/dishes`, t, { id: crypto.randomUUID(), name: 'betaunique' }), 201); release(); await data(await pending, 201);
    expect((await search(t, 'alphaunique betaunique')).restaurants).toHaveLength(1);
    const updated = await data<Mutation>(await request(`/dishes/${other.entity.id}`, t, { expected_version: 1, name: 'gammaunique', review: 'newreview' }, 'PATCH')); expect(updated.revisions).toHaveLength(1);
    expect((await search(t, 'betaunique')).restaurants).toEqual([]); expect((await search(t, 'gammaunique newreview')).restaurants).toHaveLength(1);
    await data(await request(`/dishes/${other.entity.id}`, t, { expected_version: 2 }, 'DELETE')); expect((await search(t, 'gammaunique')).restaurants).toEqual([]);
    await data(await request(`/dishes/${other.entity.id}/restore`, t, { expected_version: 3 })); expect((await search(t, 'gammaunique')).restaurants).toHaveLength(1);
    await data(await request(`/restaurants/${r.entity.id}`, t, { expected_version: 1, note: 'updatednote' }, 'PATCH')); expect((await search(t, 'updatednote')).restaurants[0].matched_fields).toContain('note');
  });
  it('visit and optional revision search groups exclude deleted targets and hidden history', async () => {
    const a = await account(), t = a.access_token, r = await restaurant(t, { name: 'Groupmarker' });
    const visit = await data<Mutation>(await request(`/restaurants/${r.entity.id}/visits`, t, { id: crypto.randomUUID(), dining_date: '2024-02-29', note: 'Visitmarker' }), 201);
    const changed = await data<Mutation>(await request(`/restaurants/${r.entity.id}`, t, { expected_version: 1, review: 'Revisionmarker' }, 'PATCH'));
    expect((await search(t, 'Revisionmarker')).revisions).toEqual([]);
    expect((await data<Search>(await request('/search?q=Revisionmarker&groups=restaurants,visits,revisions', t))).revisions.map(r => r.id)).toEqual([changed.revisions[0].id]);
    expect((await data<Search>(await request('/search?q=Visitmarker&groups=visits', t))).visits.map(v => v.id)).toEqual([visit.entity.id]);
    expect((await data<Page<Entity>>(await request('/visits?q=groupmarker', t))).items.map(v => v.id)).toEqual([visit.entity.id]);
    await data(await request(`/revisions/${changed.revisions[0].id}`, t, { expected_metadata_version: 1 }, 'DELETE')); expect((await data<Search>(await request('/search?q=Revisionmarker&groups=revisions', t))).revisions).toEqual([]);
    await data(await request(`/restaurants/${r.entity.id}`, t, { expected_version: 2 }, 'DELETE')); expect((await data<Search>(await request('/search?q=groupmarker&groups=restaurants,visits,revisions', t)))).toEqual({ restaurants: [], visits: [], revisions: [] });
  });
  it('keyset rating null and duplicate dates pages have no gaps and strict cursors reject tampering', async () => {
    const a = await account(), b = await account('bob'), t = a.access_token, ids: string[] = [];
    for (const rating of [4.3, 4.3, 2, null, null]) ids.push((await restaurant(t, { rating })).entity.id);
    let cursor: string | null = null; const seen: Entity[] = [];
    do {
      const res = await request(`/restaurants?sort=rating_desc&limit=2${cursor ? `&cursor=${cursor}` : ''}`, t); expect(res.status).toBe(200);
      const body = await res.json() as { data: Page<Entity>; meta: { has_more: boolean; next_cursor: string | null } }; seen.push(...body.data.items); expect(body.meta.has_more).toBe(body.meta.next_cursor !== null); cursor = body.meta.next_cursor;
    } while (cursor);
    expect(new Set(seen.map(r => r.id)).size).toBe(5); expect(seen.map(r => r.rating)).toEqual([4.3, 4.3, 2, null, null]);
    const res = await request('/restaurants?limit=1', t); const raw = await res.json() as { meta: { next_cursor: string } }; const valid = raw.meta.next_cursor;
    const payload = JSON.parse(new TextDecoder().decode(base64UrlDecode(valid))) as Record<string, unknown>;
    for (const modified of [{ ...payload, id: "' OR 1=1 --" }, { ...payload, key: 0 }, { ...payload, extra: true }, { ...payload, signature: '0'.repeat(64) }, { ...payload, sort: 'rating_desc' }]) {
      const encoded = base64UrlEncode(new TextEncoder().encode(JSON.stringify(modified))); await error(await request(`/restaurants?limit=1&cursor=${encoded}`, t), 400, 'INVALID_CURSOR');
    }
    for (const value of ['not-a-cursor', '%', 'A'.repeat(2049)]) await error(await request(`/restaurants?cursor=${encodeURIComponent(value)}`, t), 400, 'INVALID_CURSOR');
    await error(await request(`/restaurants?cursor=${valid}&category=cafe`, t), 400, 'INVALID_CURSOR'); await error(await request(`/restaurants?cursor=${valid}`, b.access_token), 400, 'INVALID_CURSOR');
    const r = ids[0]; for (let i = 0; i < 3; i++) await data(await request(`/restaurants/${r}/visits`, t, { id: crypto.randomUUID(), dining_date: '2024-02-29' }), 201);
    const first = await request('/visits?limit=2', t); const page = await first.json() as { data: Page<Entity>; meta: { next_cursor: string } }; const next = await data<Page<Entity>>(await request(`/visits?limit=2&cursor=${page.meta.next_cursor}`, t)); expect(new Set([...page.data.items, ...next.items].map(v => v.id)).size).toBe(3);
  });
  it('restaurant rating category visited dates and revision field time filters are enforced', async () => {
    const a = await account(), t = a.access_token, r = await restaurant(t, { category: 'cafe', rating: 4.3 }); await restaurant(t, { rating: null });
    await data(await request(`/restaurants/${r.entity.id}/visits`, t, { id: crypto.randomUUID(), dining_date: '2024-02-29', meal: 'lunch' }), 201);
    const q = '/restaurants?category=cafe&min_rating=4&max_rating=4.5&visited=true&visited_from=2024-02-29&visited_to=2024-02-29'; expect((await data<Page<Entity>>(await request(q, t))).items.map(x => x.id)).toEqual([r.entity.id]);
    expect((await data<Page<Entity>>(await request('/restaurants?unrated=true&visited=false', t))).items).toHaveLength(1);
    expect((await data<Page<Entity>>(await request('/visits?from=2024-02-29&to=2024-02-29&meal=lunch', t))).items).toHaveLength(1);
    expect((await data<Page<Revision>>(await request(`/restaurants/${r.entity.id}/revisions?field=rating_tenths&action=create&from=2020-01-01T00:00:00Z&to=2100-01-01T00:00:00Z`, t))).items).toHaveLength(1);
    for (const path of ['/restaurants?unrated=true&min_rating=1', '/restaurants?min_rating=5&max_rating=1', '/restaurants?deleted=only&visited=true', '/visits?from=2026-01-01&to=2025-01-01', '/revisions?entity_id='+r.entity.id, '/restaurants?limit=0', '/restaurants?limit=51', '/restaurants?limit=1&limit=2', '/search', '/search?q=x&groups=dishes', '/search?q=x&limit=21']) await error(await request(path, t), 400, 'VALIDATION_ERROR');
  });
});
