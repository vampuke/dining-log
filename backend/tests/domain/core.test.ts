import { applyD1Migrations, env } from 'cloudflare:test';
import { beforeAll, describe, expect, it } from 'vitest';
import { account, batchDB, counts, data, error, request, restaurant, restaurantInput, type Mutation, type Revision } from './helpers';
beforeAll(async () => { await applyD1Migrations(env.DB, env.TEST_MIGRATIONS); });
describe('P3 atomic mutations and server history', () => {
  it('S2 create history and 40 to 43 integer rating diff', async () => {
    const a = await account(), token = a.access_token, created = await restaurant(token, { rating: 4.0 });
    expect(created.entity.rating).toBe(4.0); expect(created.revisions).toHaveLength(1);
    const initial = await data<Revision>(await request(`/revisions/${created.revisions[0].id}`, token));
    expect(initial.diff.every(d => d.after !== null)).toBe(true); expect(initial.diff.every(d => d.before === null)).toBe(true);
    const result = await data<Mutation>(await request(`/restaurants/${created.entity.id}`, token, { expected_version: 1, rating: 4.3 }, 'PATCH'));
    expect(result.entity.rating).toBe(4.3); expect(result.revisions).toHaveLength(1);
    const rev = await data<Revision>(await request(`/revisions/${result.revisions[0].id}`, token));
    expect(rev.diff).toEqual([{ field: 'rating_tenths', before: 40, after: 43 }]);
  });
  it('S3 multiple changed fields make one server computed revision', async () => {
    const a = await account(), r = await restaurant(a.access_token);
    const result = await data<Mutation>(await request(`/restaurants/${r.entity.id}`, a.access_token, { expected_version: 1, address: 'New address', review: 'Good' }, 'PATCH'));
    expect(result.revisions).toHaveLength(1);
    const rev = await data<Revision>(await request(`/revisions/${result.revisions[0].id}`, a.access_token));
    expect(rev.diff).toEqual([{ field: 'address', before: '18 Zhongshan Road, 2F', after: 'New address' }, { field: 'review', before: null, after: 'Good' }]);
    expect(rev.changed_fields).toEqual(['address', 'review']);
    await error(await request(`/restaurants/${r.entity.id}`, a.access_token, { expected_version: 2, before: { address: 'forged' }, address: 'Oops' }, 'PATCH'), 400, 'UNKNOWN_FIELD');
  });
  it('S4 normalized no-op preserves time version and history atomically', async () => {
    const a = await account(), r = await restaurant(a.access_token, { review: 'Line one\n  Line two', note: null }); const before = await counts();
    const result = await data<Mutation>(await request(`/restaurants/${r.entity.id}`, a.access_token, { expected_version: 1, review: ' \r\nLine one\r\n  Line two\r\n ', note: '  ' }, 'PATCH'));
    expect(result).toMatchObject({ changed: false, change_set_id: null, revisions: [], entity: { version: 1, updated_at: r.entity.updated_at } });
    expect((await counts()).revisions).toBe(before.revisions); expect((await counts()).change_sets).toBe(before.change_sets);
    await error(await request(`/restaurants/${r.entity.id}`, a.access_token, { expected_version: 2, review: 'Line one\n  Line two' }, 'PATCH'), 409, 'VERSION_CONFLICT');
  });
  it('S5 same key retries create restaurant first visit and history once', async () => {
    const a = await account(), key = crypto.randomUUID(), input = restaurantInput({ first_visit: { id: crypto.randomUUID(), dining_date: '2024-02-29' } });
    const first = await request('/restaurants', a.access_token, input, 'POST', key); expect(first.status).toBe(201); const original = await first.json();
    const retry = await request('/restaurants', a.access_token, { ...input, name: `  ${input.name}  ` }, 'POST', key); expect(retry.status).toBe(201); expect(await retry.json()).toEqual(original);
    expect(await counts()).toMatchObject({ restaurants: 1, visits: 1, revisions: 2, change_sets: 1, mutation_guards: 0 });
    await error(await request('/restaurants', a.access_token, { ...input, address: 'Other' }, 'POST', key), 409, 'IDEMPOTENCY_CONFLICT');
  });
  it('S6 real concurrent same-base PATCH has one winner and no loser residue', async () => {
    const a = await account(), r = await restaurant(a.access_token), before = await counts();
    const responses = await Promise.all(['one', 'two'].map(review => request(`/restaurants/${r.entity.id}`, a.access_token, { expected_version: 1, review }, 'PATCH')));
    expect(responses.map(r => r.status).sort()).toEqual([200, 409]);
    const conflict = await error(responses.find(r => r.status === 409)!, 409, 'VERSION_CONFLICT'); expect(conflict.current?.version).toBe(2); expect(conflict.current).not.toHaveProperty('user_id');
    expect(await counts()).toMatchObject({ change_sets: before.change_sets! + 1, revisions: before.revisions! + 1, idempotency_keys: before.idempotency_keys! + 1, mutation_guards: 0 });
  });
  it('same-key CAS loser replays the committed winner before classifying conflict', async () => {
    const a = await account(), r = await restaurant(a.access_token), key = crypto.randomUUID();
    let entered!: () => void, release!: () => void; const ready = new Promise<void>(r => entered = r), gate = new Promise<void>(r => release = r);
    const db = batchDB(async statements => { entered(); await gate; return env.DB.batch(statements); });
    const body = { expected_version: 1, review: 'winner' }; const pending = request(`/restaurants/${r.entity.id}`, a.access_token, body, 'PATCH', key, db);
    await ready; const winner = await request(`/restaurants/${r.entity.id}`, a.access_token, body, 'PATCH', key); release();
    expect(winner.status).toBe(200); const loser = await pending; expect(loser.status).toBe(200); expect(await loser.json()).toEqual(await winner.json()); expect(await counts()).toMatchObject({ revisions: 2, change_sets: 2, mutation_guards: 0 });
  });
  it('S7 deletion between parent read and child insert rolls back every child write', async () => {
    const a = await account(), r = await restaurant(a.access_token);
    const db = batchDB(async statements => { await data(await request(`/restaurants/${r.entity.id}`, a.access_token, { expected_version: 1 }, 'DELETE')); return env.DB.batch(statements); });
    await error(await request(`/restaurants/${r.entity.id}/dishes`, a.access_token, { id: crypto.randomUUID(), name: 'never committed' }, 'POST', crypto.randomUUID(), db), 404, 'PARENT_UNAVAILABLE');
    expect(await counts()).toMatchObject({ dishes: 0, revisions: 2, change_sets: 2, mutation_guards: 0 });
  });
  it('no-op child save still guards the parent at batch time', async () => {
    const a = await account(), r = await restaurant(a.access_token), d = await data<Mutation>(await request(`/restaurants/${r.entity.id}/dishes`, a.access_token, { id: crypto.randomUUID(), name: 'Dish' }), 201);
    const db = batchDB(async statements => { await data(await request(`/restaurants/${r.entity.id}`, a.access_token, { expected_version: 1 }, 'DELETE')); return env.DB.batch(statements); });
    await error(await request(`/dishes/${d.entity.id}`, a.access_token, { expected_version: 1, name: 'Dish' }, 'PATCH', crypto.randomUUID(), db), 404, 'PARENT_UNAVAILABLE');
    expect(await counts()).toMatchObject({ revisions: 3, change_sets: 3, mutation_guards: 0 });
  });
  it('batch Nth statement failure rolls back business guards history search and idempotency', async () => {
    const a = await account(), r = await restaurant(a.access_token), before = await counts();
    const db = batchDB(statements => env.DB.batch([...statements.slice(0, 4), env.DB.prepare("INSERT INTO mutation_guards VALUES ('injected',0)"), ...statements.slice(4)]));
    await error(await request(`/restaurants/${r.entity.id}`, a.access_token, { expected_version: 1, review: 'roll back' }, 'PATCH', crypto.randomUUID(), db), 500, 'INTERNAL_ERROR');
    expect(await counts()).toEqual(before); expect((await data<Mutation['entity']>(await request(`/restaurants/${r.entity.id}`, a.access_token))).version).toBe(1);
    const sql = await env.DB.prepare('SELECT content FROM restaurant_search WHERE restaurant_id=?').bind(r.entity.id).first<string>('content'); expect(sql).not.toContain('roll back');
  });
  it('late batch failure rolls back rebuilt search and successful idempotency response', async () => {
    const a = await account(), t = a.access_token, r = await restaurant(t), before = await counts(), key = crypto.randomUUID();
    const searchBefore = await env.DB.prepare('SELECT content FROM restaurant_search WHERE restaurant_id=?').bind(r.entity.id).first<string>('content');
    const db = batchDB(statements => env.DB.batch([...statements, env.DB.prepare("INSERT INTO mutation_guards VALUES ('late-injected',0)")]));
    const payload = { expected_version: 1, review: 'late rollback marker' };
    await error(await request(`/restaurants/${r.entity.id}`, t, payload, 'PATCH', key, db), 500, 'INTERNAL_ERROR');
    expect(await counts()).toEqual(before);
    expect(await env.DB.prepare('SELECT content FROM restaurant_search WHERE restaurant_id=?').bind(r.entity.id).first<string>('content')).toBe(searchBefore);
    expect(await data<Mutation['entity']>(await request(`/restaurants/${r.entity.id}`, t))).toMatchObject({ version: 1, review: null });
    const retry = await data<Mutation>(await request(`/restaurants/${r.entity.id}`, t, payload, 'PATCH', key));
    expect(retry).toMatchObject({ changed: true, entity: { version: 2, review: payload.review } });
    expect(await counts()).toMatchObject({ revisions: before.revisions! + 1, change_sets: before.change_sets! + 1, idempotency_keys: before.idempotency_keys! + 1, mutation_guards: 0 });
  });
  it('success response is cached before transactional guard cleanup', async () => {
    const a = await account(), t = a.access_token, r = await restaurant(t);
    // A test-only trigger makes the prescribed response/cleanup ordering observable.
    await env.DB.prepare(`CREATE TRIGGER p3_response_guard BEFORE INSERT ON idempotency_keys
      WHEN NEW.method='PATCH'
      BEGIN
        SELECT CASE WHEN NOT EXISTS (SELECT 1 FROM mutation_guards
          WHERE operation_id=NEW.idempotency_key || ':restaurant:' || json_extract(NEW.response_json,'$.data.entity.id')
          AND affected_rows=1)
          THEN RAISE(ABORT,'guard cleared before successful response') END;
      END`).run();
    const result = await data<Mutation>(await request(`/restaurants/${r.entity.id}`, t, { expected_version: 1, review: 'cached atomically' }, 'PATCH'));
    expect(result.entity.version).toBe(2);
    expect(await counts()).toMatchObject({ revisions: 2, change_sets: 2, idempotency_keys: 2, mutation_guards: 0 });
  });
  it('first visit late constraint failure also rolls back the restaurant', async () => {
    const a = await account(), r = await restaurant(a.access_token), v = await data<Mutation>(await request(`/restaurants/${r.entity.id}/visits`, a.access_token, { id: crypto.randomUUID(), dining_date: '2024-02-29' }), 201), before = await counts();
    await error(await request('/restaurants', a.access_token, restaurantInput({ first_visit: { id: v.entity.id, dining_date: '2024-02-29' } })), 409, 'ID_CONFLICT'); expect(await counts()).toEqual(before);
  });
});
describe('P3 forced interleavings preserve atomicity and error attribution', () => {
  it('no-op restaurant save loses a version race and leaves no successful cache or history', async () => {
    const a = await account(), t = a.access_token, r = await restaurant(t), before = await counts();
    const db = batchDB(async statements => { await data(await request(`/restaurants/${r.entity.id}`, t, { expected_version: 1, name: 'winner' }, 'PATCH')); return env.DB.batch(statements); });
    await error(await request(`/restaurants/${r.entity.id}`, t, { expected_version: 1, name: r.entity.name }, 'PATCH', crypto.randomUUID(), db), 409, 'VERSION_CONFLICT');
    expect(await counts()).toMatchObject({ revisions: before.revisions! + 1, change_sets: before.change_sets! + 1, idempotency_keys: before.idempotency_keys! + 1, mutation_guards: 0 });
  });
  it('same-key different fingerprint batch loser is idempotency conflict before version conflict', async () => {
    const a = await account(), t = a.access_token, r = await restaurant(t), key = crypto.randomUUID();
    const db = batchDB(async statements => { await data(await request(`/restaurants/${r.entity.id}`, t, { expected_version: 1, review: 'winner' }, 'PATCH', key)); return env.DB.batch(statements); });
    await error(await request(`/restaurants/${r.entity.id}`, t, { expected_version: 1, review: 'loser' }, 'PATCH', key, db), 409, 'IDEMPOTENCY_CONFLICT');
    expect(await counts()).toMatchObject({ revisions: 2, change_sets: 2, mutation_guards: 0 });
  });
  it('batch deletion of target is NOT_FOUND before any version conflict', async () => {
    const a = await account(), t = a.access_token, r = await restaurant(t);
    const db = batchDB(async statements => { await data(await request(`/restaurants/${r.entity.id}`, t, { expected_version: 1 }, 'DELETE')); return env.DB.batch(statements); });
    await error(await request(`/restaurants/${r.entity.id}`, t, { expected_version: 1, review: 'stale' }, 'PATCH', crypto.randomUUID(), db), 404, 'NOT_FOUND');
    expect(await counts()).toMatchObject({ revisions: 2, change_sets: 2, mutation_guards: 0 });
  });
});
describe('P3 creation operation identity', () => {
  it('restaurant and first visit sharing a UUID have independent changes guards and revisions', async () => {
    const a = await account(), id = crypto.randomUUID();
    const result = await data<Mutation>(await request('/restaurants', a.access_token, restaurantInput({ id, first_visit: { id, dining_date: '2024-02-29' } })), 201);
    expect(result.revisions).toHaveLength(2); expect(result.first_visit!.id).toBe(id);
    expect(await counts()).toMatchObject({ restaurants: 1, visits: 1, change_sets: 1, revisions: 2, mutation_guards: 0 });
  });
});
describe('P3 creation failure ownership', () => {
  it('foreign IDs fail without revealing current or leaving a partial restaurant first visit', async () => {
    const a = await account(), b = await account('bob'), t = a.access_token, privateRestaurant = await restaurant(b.access_token), privateVisit = await data<Mutation>(await request(`/restaurants/${privateRestaurant.entity.id}/visits`, b.access_token, { id: crypto.randomUUID(), dining_date: '2024-02-29' }), 201), before = await counts();
    await error(await request('/restaurants', t, restaurantInput({ id: privateRestaurant.entity.id })), 404, 'NOT_FOUND');
    const failure = await error(await request('/restaurants', t, restaurantInput({ first_visit: { id: privateVisit.entity.id, dining_date: '2024-02-29' } })), 404, 'NOT_FOUND'); expect(failure).not.toHaveProperty('current');
    expect(await counts()).toEqual(before);
  });
});
