import { applyD1Migrations, env, type D1Migration } from 'cloudflare:test';
import { sha256 } from '@noble/hashes/sha256';
import { beforeAll, describe, expect, it } from 'vitest';
import { bindings, request } from '../domain/helpers';
import { issueSession, type UserRow } from '../../src/auth/session';
import { fragment } from '../../src/domain/search-index';
import type { Row } from '../../src/domain/model';

declare module 'cloudflare:test' {
  interface ProvidedEnv { DB: D1Database; TEST_MIGRATIONS: D1Migration[] }
}

const SEED = 0x51a71c;
const BASE_TIME = Date.UTC(2026, 9, 31, 12);
const REPETITIONS = 30;
const P95_LIMIT_MS = 100;
const USER_ID = '00000000-0000-4000-8000-000000000001';
const SEARCH_HIT = '招牌测试';
const SEARCH_MISS = 'capacity_missing_51a71c';
const encoder = new TextEncoder();
const APP_TABLES = [
  'users', 'sessions', 'refresh_tokens', 'refresh_rotation_results', 'recovery_codes',
  'auth_rate_limits', 'restaurants', 'dishes', 'restaurant_search', 'visits',
  'change_sets', 'revisions', 'idempotency_keys', 'mutation_guards',
] as const;
const SCENARIOS = [
  { name: 'p1-4500', restaurants: 500, dishesPerRestaurant: 5, visitsPerRestaurant: 3,
    restaurantRevisions: 11, childRevisions: 2, objects: 4500, history: 13500 },
  { name: 'plan-6000', restaurants: 500, dishesPerRestaurant: 8, visitsPerRestaurant: 3,
    restaurantRevisions: 14, childRevisions: 2, objects: 6000, history: 18000 },
] as const;

type Value = string | number | null;
type Fields = Record<string, Value>;
type Statement = { sql: string; params: Value[] };
type TextKind = 'english_short' | 'chinese_short' | 'near_limit';
type EntityType = 'restaurant' | 'dish' | 'visit';
type Diff = { field: string; before: Value; after: Value };
type Revision = { id: string; changeSetId: string; requestId: string;
  version: number; createdAt: number; diff: Diff[]; searchText: string };
type Entity = { id: string; restaurantId: string; type: EntityType; fields: Fields;
  kind: TextKind; createdAt: number; updatedAt: number; history: Revision[] };
type Summary = { id: string; rating: number; updatedAt: number; hit: boolean;
  dishIds: string[]; visitIds: string[] };
type Query = { id: string; statements: Statement[]; expectedIds: string[][] };

function mulberry32(seed: number) {
  let state = seed >>> 0;
  return () => {
    state = (state + 0x6d2b79f5) >>> 0;
    let value = Math.imul(state ^ (state >>> 15), 1 | state);
    value ^= value + Math.imul(value ^ (value >>> 7), 61 | value);
    return ((value ^ (value >>> 14)) >>> 0) / 4294967296;
  };
}

function uuid(random: () => number) {
  const bytes = Array.from({ length: 16 }, () => Math.floor(random() * 256));
  bytes[6] = (bytes[6] & 15) | 64;
  bytes[8] = (bytes[8] & 63) | 128;
  const hex = bytes.map((byte) => byte.toString(16).padStart(2, '0')).join('');
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`;
}

function normalize(text: string) {
  return text.normalize('NFKC').toLowerCase().replace(/\s+/g, ' ').trim();
}

function text(label: string, limit: number, kind: TextKind, random: () => number, marker = '') {
  const suffix = Math.floor(random() * 0xffffffff).toString(16);
  const prefix = `${label} ${suffix} ${marker}`.trim();
  if (kind === 'english_short') return `${prefix} cozy fresh meal`.slice(0, Math.min(limit, 72));
  if (kind === 'chinese_short') return `${prefix} 鲜香可口服务周到`.slice(0, Math.min(limit, 56));
  // All characters are BMP code points. Long values occupy 98–99.9% of the field limit.
  const length = limit - 1 - Math.floor(random() * Math.max(1, Math.floor(limit * 0.02)));
  return `${prefix} ${'食记餐厅菜品到店鲜香服务体验'.repeat(limit)}`.slice(0, length);
}

const kinds: TextKind[] = ['english_short', 'chinese_short', 'near_limit'];
const descendingId = (a: string, b: string) => a === b ? 0 : a < b ? 1 : -1;
const bind = (statement: Statement) => env.DB.prepare(statement.sql).bind(...statement.params);
const insert = (table: string, fields: Fields): Statement => ({
  sql: `INSERT INTO ${table} (${Object.keys(fields).join(',')}) VALUES (${Object.keys(fields).map(() => '?').join(',')})`,
  params: Object.values(fields),
});

function entity(type: EntityType, restaurantId: string, fields: Fields, kind: TextKind,
  createdAt: number, revisions: number, random: () => number, groupedSet?: { id: string; requestId: string }): Entity {
  const id = type === 'restaurant' ? restaurantId : uuid(random);
  const bodyField = type === 'visit' ? 'note' : 'review';
  const limit = type === 'restaurant' ? 4000 : 2000;
  let previous: Value = null;
  const history: Revision[] = [];
  for (let version = 1; version <= revisions; version++) {
    const after = text(`${type} ${id.slice(0, 8)} v${version}`, limit, kind, random);
    const diff: Diff[] = version === 1
      ? Object.entries({ ...fields, [bodyField]: after }).map(([field, value]) => ({ field, before: null, after: value }))
      : [{ field: bodyField, before: previous, after }];
    history.push({
      id: uuid(random), changeSetId: groupedSet?.id ?? uuid(random),
      requestId: groupedSet?.requestId ?? uuid(random), version, createdAt: createdAt + version - 1,
      diff, searchText: normalize(diff.map((change) => `${change.field} ${change.before ?? ''} ${change.after ?? ''}`).join(' ')),
    });
    previous = after;
  }
  return { id, restaurantId, type, fields: { ...fields, [bodyField]: previous }, kind,
    createdAt, updatedAt: createdAt + revisions - 1, history };
}

function cluster(index: number, dishCount: number, restaurantRevisions: number, childRevisions: number,
  random: () => number, grouped = false) {
  const restaurantId = uuid(random);
  const kind = kinds[index % 3];
  const createdAt = BASE_TIME + index * 1000;
  const group = grouped ? { id: uuid(random), requestId: uuid(random) } : undefined;
  const restaurant = entity('restaurant', restaurantId, {
    name: text(`Restaurant ${index}`, 120, kind, random),
    category: ['restaurant', 'cafe', 'bakery', 'bar', 'street_food', 'other'][Math.floor(random() * 6)],
    cuisine: text(`Cuisine ${index}`, 60, kind, random),
    address: text(`Address ${index}`, 500, kind, random),
    rating_tenths: 10 + Math.floor(random() * 41),
    note: text(`Note ${index}`, 4000, kind, random, index % 25 === 0 ? SEARCH_HIT : ''),
  }, kind, createdAt, restaurantRevisions, random, group);
  const dishes = Array.from({ length: dishCount }, (_, slot) => {
    const childKind = kinds[(index + slot) % 3];
    return entity('dish', restaurantId, {
      name: text(`Dish ${index}/${slot}`, 120, childKind, random),
      rating_tenths: 10 + Math.floor(random() * 41),
    }, childKind, createdAt + 20 + slot * 10, childRevisions, random, group);
  });
  const diningDate = new Date(BASE_TIME - Math.floor(random() * 700) * 86_400_000).toISOString().slice(0, 10);
  const visits = Array.from({ length: 3 }, (_, slot) => entity('visit', restaurantId, {
    // Two visits deliberately share the same calendar date.
    dining_date: slot < 2 ? diningDate : new Date(BASE_TIME - Math.floor(random() * 700) * 86_400_000).toISOString().slice(0, 10),
    meal: ['breakfast', 'lunch', 'dinner', 'other'][Math.floor(random() * 4)],
  }, kinds[(index + slot) % 3], createdAt + 120 + slot * 10, childRevisions, random, group));
  const entities = [restaurant, ...dishes, ...visits];
  const statements: Statement[] = entities.map((item) => {
    const fields: Fields = { id: item.id, user_id: USER_ID, ...item.fields,
      version: item.history.length, created_at: item.createdAt, updated_at: item.updatedAt };
    if (item.type === 'restaurant') fields.cuisine_normalized = normalize(String(item.fields.cuisine));
    else fields.restaurant_id = restaurantId;
    if (item.type === 'visit') fields.search_text = normalize(`${item.fields.dining_date} ${item.fields.meal} ${item.fields.note}`);
    if (grouped && item.type === 'restaurant') {
      fields.version = 1;
      fields.updated_at = item.createdAt;
      fields.review = item.history[0].diff.find((change) => change.field === 'review')!.after;
    }
    return insert(item.type === 'restaurant' ? 'restaurants' : item.type === 'dish' ? 'dishes' : 'visits', fields);
  });
  if (grouped) statements.push({
    sql: 'UPDATE restaurants SET review=?,version=2,updated_at=? WHERE user_id=? AND id=? AND version=1',
    params: [restaurant.fields.review, restaurant.updatedAt, USER_ID, restaurantId],
  });
  const seenSets = new Set<string>();
  for (const item of entities) for (const revision of item.history) {
    if (seenSets.has(revision.changeSetId)) continue;
    seenSets.add(revision.changeSetId);
    statements.push(insert('change_sets', { id: revision.changeSetId, user_id: USER_ID,
      request_id: revision.requestId, created_at: revision.createdAt }));
  }
  for (const item of entities) for (const revision of item.history) statements.push(insert('revisions', {
    id: revision.id, user_id: USER_ID, change_set_id: revision.changeSetId,
    restaurant_id: restaurantId, entity_type: item.type, entity_id: item.id,
    dish_id: item.type === 'dish' ? item.id : null, visit_id: item.type === 'visit' ? item.id : null,
    action: revision.version === 1 ? 'create' : 'update',
    entity_version_before: revision.version - 1, entity_version_after: revision.version,
    diff_json: JSON.stringify(revision.diff), search_text: revision.searchText,
    metadata_updated_at: revision.createdAt, created_at: revision.createdAt,
  }));
  statements.push(insert('restaurant_search', { restaurant_id: restaurantId, user_id: USER_ID,
    content: JSON.stringify({ parts: Object.fromEntries([restaurant, ...dishes].map(item => [`${item.type}:${item.id}`, fragment(item.type === 'restaurant' ? 'restaurant' : 'dish', { ...item.fields, id: item.id, deleted_at: null } as Row)])), text: normalize([restaurant, ...dishes].flatMap((item) => Object.values(item.fields)).join(' ')) }),
    updated_at: Math.max(...entities.map((item) => item.updatedAt)) }));
  return { statements, entities, summary: { id: restaurantId,
    rating: Number(restaurant.fields.rating_tenths), updatedAt: restaurant.updatedAt,
    hit: index % 25 === 0, dishIds: dishes.map((dish) => dish.id), visitIds: visits.map((visit) => visit.id) } };
}

async function seed(scenario: typeof SCENARIOS[number]) {
  const random = mulberry32(SEED);
  const digest = sha256.create();
  const summaries: Summary[] = [];
  const textCounts = Object.fromEntries(['restaurant', 'dish', 'visit'].map((type) =>
    [type, { english_short: 0, chinese_short: 0, near_limit: 0 }])) as Record<EntityType, Record<TextKind, number>>;
  let rowsWritten = 0;
  let rowsRead = 0;
  let statements = 0;
  let batches = 0;
  const start = Date.now();
  const write = async (items: Statement[]) => {
    // Bound memory and batch size, including the long-text history variants.
    for (let offset = 0; offset < items.length; offset += 40) {
      const chunk = items.slice(offset, offset + 40);
      for (const statement of chunk) digest.update(encoder.encode(JSON.stringify(statement) + '\n'));
      const results = await env.DB.batch(chunk.map(bind));
      for (const result of results) {
        expect(result.success).toBe(true);
        rowsWritten += result.meta.rows_written;
        rowsRead += result.meta.rows_read;
      }
      statements += chunk.length;
      batches++;
    }
  };
  await write([insert('users', { id: USER_ID, username: 'capacity_user', display_name: 'Synthetic capacity account',
    password_algorithm: 'pbkdf2-sha256+hmac-sha256-v1', password_hash: 'x'.repeat(43),
    password_salt: 'x'.repeat(22), password_iterations: 10000, pepper_version: 'public-fixture',
    password_changed_at: BASE_TIME, created_at: BASE_TIME, updated_at: BASE_TIME })]);
  for (let index = 0; index < scenario.restaurants; index++) {
    const data = cluster(index, scenario.dishesPerRestaurant, scenario.restaurantRevisions, scenario.childRevisions, random);
    await write(data.statements);
    summaries.push(data.summary);
    for (const item of data.entities) textCounts[item.type][item.kind]++;
  }
  return { summaries, metadata: { seed: SEED, seedHex: '0x51a71c', fixedTimeUtc: new Date(BASE_TIME).toISOString(),
    fixtureSha256: Array.from(digest.digest(), (byte) => byte.toString(16).padStart(2, '0')).join(''),
    rowsWritten, rowsRead, statements, batches, wallMs: Date.now() - start, textCounts } };
}

async function tableCounts() {
  const counts: Record<string, number> = {};
  for (const table of APP_TABLES) counts[table] = Number(await env.DB.prepare(`SELECT COUNT(*) AS n FROM ${table}`).first('n'));
  return counts;
}

async function storage() {
  const tables = await env.DB.prepare("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' ORDER BY name").all<{ name: string }>();
  // D1 exposes internal names in sqlite_master but forbids reading _cf_* metadata.
  // Their pages still contribute to meta.size_after; all application tables remain measured.
  const protectedTables = tables.results.filter(({ name }) => name.startsWith('_cf_')).map(({ name }) => name);
  const tableStats: { table: string; rows: number; textUtf8Bytes: number }[] = [];
  let allocatedBytes: number | null = null;
  for (const { name } of tables.results) {
    if (protectedTables.includes(name)) continue;
    if (!/^[a-zA-Z0-9_]+$/.test(name)) throw new Error(`Unexpected table name: ${name}`);
    const columns = await env.DB.prepare(`PRAGMA table_info("${name}")`).all<{ name: string; type: string }>();
    const textColumns = columns.results.filter((column) => /TEXT|BLOB/i.test(column.type));
    const lengths = textColumns.map((column) => `COALESCE(length(CAST("${column.name}" AS BLOB)),0)`).join('+') || '0';
    const result = await env.DB.prepare(`SELECT COUNT(*) AS rows, COALESCE(SUM(${lengths}),0) AS bytes FROM "${name}"`).all<{ rows: number; bytes: number }>();
    tableStats.push({ table: name, rows: result.results[0].rows, textUtf8Bytes: result.results[0].bytes });
    if (result.meta.size_after > 0) allocatedBytes = result.meta.size_after;
  }
  let dbstatBytes: number | null = null;
  let dbstatUnavailable: string | null = null;
  let pagesByObject: { name: string; bytes: number }[] = [];
  try {
    dbstatBytes = Number(await env.DB.prepare('SELECT SUM(pgsize) AS bytes FROM dbstat').first('bytes'));
    pagesByObject = (await env.DB.prepare('SELECT name,SUM(pgsize) AS bytes FROM dbstat GROUP BY name ORDER BY name').all<{ name: string; bytes: number }>()).results;
  } catch (error) {
    dbstatUnavailable = String(error);
    if (!/no such table|not authorized|not supported|SQLITE_AUTH|access denied/i.test(dbstatUnavailable)) throw error;
  }
  return { method: dbstatBytes !== null ? 'dbstat' : 'length(CAST(TEXT/BLOB AS BLOB))',
    dbstatBytes, dbstatUnavailable, allocatedBytes, pagesByObject, tableStats, protectedTables,
    textUtf8Bytes: tableStats.reduce((sum, table) => sum + table.textUtf8Bytes, 0) };
}

function quantiles(values: number[]) {
  const sorted = [...values].sort((a, b) => a - b);
  return { min: sorted[0], p50: sorted[Math.ceil(sorted.length * 0.50) - 1],
    p95: sorted[Math.ceil(sorted.length * 0.95) - 1], max: sorted[sorted.length - 1] };
}

async function measure(query: Query) {
  const samples: { wallMs: number; sqlMs: number; rowsRead: number; rowsWritten: number; returnedRows: number[] }[] = [];
  for (let repetition = 0; repetition < REPETITIONS; repetition++) {
    const prepared = query.statements.map(bind);
    const start = Date.now();
    const results = prepared.length === 1 ? [await prepared[0].all<{ id: string }>()] : await env.DB.batch<{ id: string }>(prepared);
    const wallMs = Date.now() - start;
    for (let index = 0; index < results.length; index++) {
      expect(results[index].success).toBe(true);
      expect(results[index].results.map((row) => row.id).sort()).toEqual([...query.expectedIds[index]].sort());
    }
    const sample = { wallMs, sqlMs: results.reduce((sum, result) => sum + result.meta.duration, 0),
      rowsRead: results.reduce((sum, result) => sum + result.meta.rows_read, 0),
      rowsWritten: results.reduce((sum, result) => sum + result.meta.rows_written, 0),
      returnedRows: results.map((result) => result.results.length) };
    for (const value of [sample.wallMs, sample.sqlMs, sample.rowsRead, sample.rowsWritten]) expect(Number.isFinite(value)).toBe(true);
    expect(sample.rowsWritten).toBe(0);
    samples.push(sample);
  }
  const plans: string[][] = [];
  for (const statement of query.statements) plans.push((await env.DB.prepare(`EXPLAIN QUERY PLAN ${statement.sql}`).bind(...statement.params).all<{ detail: string }>()).results.map((row) => row.detail));
  return { id: query.id, statements: query.statements, plans, samples,
    wallMs: quantiles(samples.map((sample) => sample.wallMs)),
    sqlMs: quantiles(samples.map((sample) => sample.sqlMs)),
    rowsRead: quantiles(samples.map((sample) => sample.rowsRead)) };
}

function queries(summaries: Summary[]): Query[] {
  const selected = summaries[2]; // Near-limit restaurant; children span all three text sizes.
  const fields = 'id,name,category,cuisine,address,rating_tenths,updated_at';
  const updated = [...summaries].sort((a, b) => b.updatedAt - a.updatedAt || descendingId(a.id, b.id)).slice(0, 20).map((row) => row.id);
  const rated = [...summaries].sort((a, b) => b.rating - a.rating || descendingId(a.id, b.id)).slice(0, 20).map((row) => row.id);
  const detail = { sql: 'SELECT * FROM restaurants WHERE user_id=? AND id=? AND deleted_at IS NULL', params: [USER_ID, selected.id] };
  const dishes = { sql: 'SELECT * FROM dishes WHERE user_id=? AND restaurant_id=? AND deleted_at IS NULL ORDER BY updated_at DESC,id DESC', params: [USER_ID, selected.id] };
  const visits = { sql: 'SELECT * FROM visits WHERE user_id=? AND restaurant_id=? AND deleted_at IS NULL ORDER BY dining_date DESC,id DESC', params: [USER_ID, selected.id] };
  const search = `SELECT ${fields} FROM restaurants WHERE user_id=? AND deleted_at IS NULL AND instr((SELECT content FROM restaurant_search WHERE restaurant_id=restaurants.id),?)>0 LIMIT 20`;
  return [
    { id: 'updated_page', statements: [{ sql: `SELECT ${fields} FROM restaurants WHERE user_id=? AND deleted_at IS NULL ORDER BY updated_at DESC,id DESC LIMIT 20`, params: [USER_ID] }], expectedIds: [updated] },
    { id: 'rating_page', statements: [{ sql: `SELECT ${fields} FROM restaurants WHERE user_id=? AND deleted_at IS NULL ORDER BY rating_tenths DESC,id DESC LIMIT 20`, params: [USER_ID] }], expectedIds: [rated] },
    { id: 'aggregate_search_hit', statements: [{ sql: search, params: [USER_ID, SEARCH_HIT] }], expectedIds: [summaries.filter((row) => row.hit).map((row) => row.id)] },
    { id: 'aggregate_search_miss', statements: [{ sql: search, params: [USER_ID, SEARCH_MISS] }], expectedIds: [[]] },
    { id: 'restaurant_detail', statements: [detail], expectedIds: [[selected.id]] },
    { id: 'restaurant_dishes', statements: [dishes], expectedIds: [selected.dishIds] },
    { id: 'restaurant_visits', statements: [visits], expectedIds: [selected.visitIds] },
    { id: 'detail_children_batch', statements: [detail, dishes, visits], expectedIds: [[selected.id], selected.dishIds, selected.visitIds] },
    { id: 'entity_revisions_10', statements: [{
      sql: 'SELECT * FROM revisions WHERE user_id=? AND entity_type=? AND entity_id=? AND deleted_at IS NULL ORDER BY created_at DESC,id DESC LIMIT 10',
      params: [USER_ID, 'restaurant', selected.id],
    }], expectedIds: [[]] },
  ];
}

async function completeWrite() {
  const data = cluster(500, 5, 2, 1, mulberry32(SEED ^ 0xabc123), true);
  const before = await tableCounts();
  const prepared = data.statements.map(bind);
  const start = Date.now();
  const results = await env.DB.batch(prepared);
  const wallMs = Date.now() - start;
  expect(wallMs, 'A complete batch must have a measurable wall duration').toBeGreaterThan(0);
  const after = await tableCounts();
  for (const result of results) expect(result.success).toBe(true);
  const expectedChanges: Record<string, number> = { restaurants: 1, dishes: 5, visits: 3, revisions: 10, change_sets: 1, restaurant_search: 1 };
  for (const table of APP_TABLES) expect(after[table] - before[table], table).toBe(expectedChanges[table] ?? 0);
  const changes = results.reduce((sum, result) => sum + result.meta.changes, 0);
  expect(changes).toBe(22); // 21 inserts and one real restaurant update, atomically.
  const version = await env.DB.prepare('SELECT version FROM restaurants WHERE user_id=? AND id=?').bind(USER_ID, data.summary.id).first('version');
  expect(version).toBe(2);
  return { statements: data.statements.map((statement) => ({ sql: statement.sql,
    parameterUtf8Bytes: statement.params.map((value) => value === null ? 0 : encoder.encode(String(value)).byteLength) })),
    wallMs, sqlMs: results.reduce((sum, result) => sum + result.meta.duration, 0),
    rowsWritten: results.reduce((sum, result) => sum + result.meta.rows_written, 0),
    rowsRead: results.reduce((sum, result) => sum + result.meta.rows_read, 0), changes,
    rowDeltas: Object.fromEntries(APP_TABLES.map((table) => [table, after[table] - before[table]])) };
}

async function measureDomainAPI(summaries: Summary[], scenario: typeof SCENARIOS[number]) {
  const user = await env.DB.prepare('SELECT * FROM users WHERE id=?').bind(USER_ID).first<UserRow>();
  const issued = await issueSession(bindings, user!, 'Synthetic P3 capacity');
  await env.DB.batch(issued.statements);
  const selected = summaries[2];
  const cases = [
    { path: '/restaurants?limit=20', count: 20 },
    { path: '/restaurants?sort=rating_desc&limit=20', count: 20 },
    { path: '/search?q=' + encodeURIComponent(SEARCH_HIT), count: 10 },
    { path: '/search?q=' + SEARCH_MISS, count: 0 },
    { path: `/restaurants/${selected.id}`, count: null },
    { path: `/restaurants/${selected.id}/dishes`, count: scenario.dishesPerRestaurant },
    { path: `/restaurants/${selected.id}/visits`, count: 3 },
    { path: `/restaurants/${selected.id}/revisions?limit=10`, count: 10 },
  ];
  const results = [];
  for (const test of cases) {
    const samples = [];
    for (let i = 0; i < REPETITIONS; i++) {
      const started = Date.now();
      const response = await request(test.path, issued.result.access_token);
      const body = await response.json() as { data: { id?: string; items?: unknown[]; restaurants?: unknown[] } };
      samples.push(Date.now() - started);
      expect(response.status, test.path).toBe(200);
      if (test.count === null) expect(body.data.id).toBe(selected.id);
      else expect(body.data.items ?? body.data.restaurants).toHaveLength(test.count);
    }
    results.push({ path: test.path, samples, wallMs: quantiles(samples) });
  }
  return results;
}

beforeAll(async () => { await applyD1Migrations(env.DB, env.TEST_MIGRATIONS); });

describe('deterministic capacity in the local Cloudflare Workers D1 pool (GitHub Actions only)', () => {
  it.each(SCENARIOS)('$name: exact corpus, measured SQL, atomic complete write, p95 <= 100ms', async (scenario) => {
    // The npm script selects one scenario per fresh Workers-pool process.
    // Fail rather than accidentally benchmarking a reused account / database.
    for (const [table, count] of Object.entries(await tableCounts())) expect(count, `${table} starts empty`).toBe(0);
    const seeded = await seed(scenario);
    expect(seeded.metadata.wallMs, 'The large seed must have a measurable wall duration').toBeGreaterThan(0);
    const counts = await tableCounts();
    expect(counts.users).toBe(1);
    expect(counts.restaurants).toBe(scenario.restaurants);
    expect(counts.dishes).toBe(scenario.restaurants * scenario.dishesPerRestaurant);
    expect(counts.visits).toBe(scenario.restaurants * scenario.visitsPerRestaurant);
    expect(counts.restaurants + counts.dishes + counts.visits).toBe(scenario.objects);
    expect(counts.revisions).toBe(scenario.history);
    expect(counts.change_sets).toBe(scenario.history);
    expect(counts.restaurant_search).toBe(500);
    const revisionRange = await env.DB.prepare("SELECT MIN(n) AS min,MAX(n) AS max FROM (SELECT COUNT(*) AS n FROM revisions WHERE user_id=? AND entity_type='restaurant' GROUP BY entity_id)").bind(USER_ID).first<{ min: number; max: number }>();
    expect(revisionRange?.min).toBe(scenario.restaurantRevisions);
    expect(revisionRange?.min).toBeGreaterThanOrEqual(10);
    expect(revisionRange?.max).toBe(scenario.restaurantRevisions);
    for (const [table, type] of [['restaurants', 'restaurant'], ['dishes', 'dish'], ['visits', 'visit']]) {
      const mismatches = await env.DB.prepare(`SELECT COUNT(*) AS n FROM ${table} e WHERE e.user_id=? AND e.version<>(SELECT MAX(entity_version_after) FROM revisions r WHERE r.user_id=e.user_id AND r.entity_type=? AND r.entity_id=e.id)`).bind(USER_ID, type).first('n');
      expect(mismatches).toBe(0);
    }
    const measuredStorage = await storage();
    const measuredQueries = [];
    for (const query of queries(seeded.summaries)) {
      if (query.id === 'entity_revisions_10') {
        // Cardinality and descending version order are checked separately from timing.
        const ids = await bind(query.statements[0]).all<{ id: string; entity_version_after: number }>();
        expect(ids.results.map((row) => row.entity_version_after)).toEqual(Array.from({ length: 10 }, (_, i) => scenario.restaurantRevisions - i));
        query.expectedIds = [ids.results.map((row) => row.id)];
      }
      measuredQueries.push(await measure(query));
    }
    const actualAPI = await measureDomainAPI(seeded.summaries, scenario);
    const write = await completeWrite();
    expect((await env.DB.prepare('PRAGMA foreign_key_check').all()).results).toEqual([]);
    expect(await env.DB.prepare('PRAGMA quick_check').first('quick_check')).toBe('ok');
    let runtime: { sqlite_version: string | null; sqliteVersionUnavailable?: string } = { sqlite_version: null };
    try {
      runtime = (await env.DB.prepare('SELECT sqlite_version() AS sqlite_version').first<{ sqlite_version: string }>()) ?? runtime;
    } catch (error) {
      const reason = String(error);
      if (!/not authorized to use function: sqlite_version/i.test(reason)) throw error;
      runtime.sqliteVersionUnavailable = reason;
    }
    const overLimit = measuredQueries.filter((query) => query.wallMs.p95 > P95_LIMIT_MS || query.sqlMs.p95 > P95_LIMIT_MS).map((query) => query.id);
    // Emit raw samples before the performance assertions, including evidence on failures.
    console.log('CAPACITY_RESULT ' + JSON.stringify({ schemaVersion: 1, scenario, runtime,
      repetitions: REPETITIONS, p95LimitMs: P95_LIMIT_MS, wallClock: 'Date.now (1 ms resolution; Workers performance.now is not used)',
      seed: seeded.metadata, rowCounts: counts,
      restaurantRevisionRange: revisionRange, storage: measuredStorage, queries: measuredQueries, actualAPI, write,
      integrity: { foreignKeyViolations: 0, quickCheck: 'ok' }, overLimit }));
    for (const measured of actualAPI) {
      expect(measured.samples).toHaveLength(REPETITIONS);
      expect(measured.wallMs.p95, `${scenario.name}/${measured.path} API pool p95`).toBeLessThanOrEqual(P95_LIMIT_MS);
    }
    for (const query of measuredQueries) {
      expect(query.samples).toHaveLength(REPETITIONS);
      expect(query.wallMs.p95, `${scenario.name}/${query.id} wall p95`).toBeLessThanOrEqual(P95_LIMIT_MS);
      expect(query.sqlMs.p95, `${scenario.name}/${query.id} D1 SQL p95`).toBeLessThanOrEqual(P95_LIMIT_MS);
    }
  });
});
