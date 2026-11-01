import { z } from 'zod';
import { AuthError } from '../auth/config';
import { envelope, json } from '../auth/routes';
import { owned, parent } from '../repositories/domain';
import { historyColumns, historyJoin, revisionSummary } from '../repositories/history';
import { entity, iso, type Kind, type Row, type Value } from './model';
import { category, meal, id, date, rating, normalizeSearch, parse } from './schemas';
import { type C } from './http';
import { decode, encode, filterHash, keyset, type Cursor } from './pagination';
const boolean = z.enum(['true', 'false']).transform(v => v === 'true');
const queryRating = z.string().regex(/^\d(?:\.\d)?$/).transform(Number).pipe(rating);
const queryText = z.string().refine(v => Array.from(v).length <= 100).transform(normalizeSearch).refine(v => v.split(' ').filter(Boolean).length <= 5);
const instant = z.string().datetime().transform(v => new Date(v).toISOString());
const common = { q: queryText.optional(), deleted: z.enum(['exclude', 'include', 'only']).default('exclude'), cursor: z.string().optional(), limit: z.string().regex(/^\d+$/).transform(Number).pipe(z.number().int().min(1).max(50)).default('20') };
const rated = { min_rating: queryRating.optional(), max_rating: queryRating.optional(), unrated: boolean.optional(), sort: z.enum(['updated_desc', 'rating_desc']).default('updated_desc') };
export const restaurantQuery = z.object({ ...common, ...rated, category: category.optional(), cuisine: z.string().refine(v => Array.from(v).length <= 60).transform(normalizeSearch).optional(), visited: boolean.optional(), visited_from: date.optional(), visited_to: date.optional() }).strict();
export const dishQuery = z.object({ ...common, ...rated }).strict();
export const visitQuery = z.object({ ...common, restaurant_id: id.optional(), from: date.optional(), to: date.optional(), meal: meal.optional() }).strict();
export const revisionQuery = z.object({ ...common, restaurant_id: id.optional(), entity_type: z.enum(['restaurant', 'dish', 'visit']).optional(), entity_id: id.optional(), action: z.enum(['create', 'update', 'delete', 'restore']).optional(), field: z.enum(['name', 'category', 'cuisine', 'address', 'rating_tenths', 'review', 'note', 'dining_date', 'meal', 'deleted_at']).optional(), from: instant.optional(), to: instant.optional() }).strict();
type Query = Record<string, string | boolean | number | undefined>;
export function validateQuery<T extends z.ZodTypeAny>(c: C, schema: T): z.infer<T> {
  const raw = c.req.query(); for (const [key, values] of Object.entries(c.req.queries())) if (values.length !== 1) throw new AuthError(400, 'VALIDATION_ERROR', `Repeated query parameter: ${key}.`);
  const q = parse(schema, raw) as Query;
  if (q.unrated && (q.min_rating !== undefined || q.max_rating !== undefined) || q.min_rating !== undefined && q.max_rating !== undefined && Number(q.min_rating) > Number(q.max_rating) || q.from && q.to && q.from > q.to || q.visited_from && q.visited_to && q.visited_from > q.visited_to || q.deleted === 'only' && (q.visited !== undefined || q.visited_from || q.visited_to)) throw new AuthError(400, 'VALIDATION_ERROR', 'Incompatible filters.');
  if (q.entity_id && !q.entity_type) throw new AuthError(400, 'VALIDATION_ERROR', 'entity_id requires entity_type.');
  return q as z.infer<T>;
}
const words = (q: Query) => String(q.q ?? '').split(' ').filter(Boolean);
function deletion(alias: string, q: Query) { return q.deleted === 'include' ? '' : ` AND ${alias}.deleted_at IS ${q.deleted === 'only' ? 'NOT ' : ''}NULL`; }
function literal(column: string, tokens: string[], params: Value[]) { return tokens.map(token => { params.push(token); return ` AND instr(${column},?)>0`; }).join(''); }
function ratingWhere(alias: string, q: Query, params: Value[]) {
  let sql = q.unrated ? ` AND ${alias}.rating_tenths IS NULL` : '';
  for (const [field, op] of [['min_rating', '>='], ['max_rating', '<=']] as const) if (q[field] !== undefined) { sql += ` AND ${alias}.rating_tenths${op}?`; params.push(Math.round(Number(q[field]) * 10)); } return sql;
}
const statsSQL = `(SELECT count(*) FROM dishes d WHERE d.user_id=r.user_id AND d.restaurant_id=r.id AND d.deleted_at IS NULL) dish_count,
 (SELECT count(*) FROM visits v WHERE v.user_id=r.user_id AND v.restaurant_id=r.id AND v.deleted_at IS NULL) visit_count,
 (SELECT max(dining_date) FROM visits v WHERE v.user_id=r.user_id AND v.restaurant_id=r.id AND v.deleted_at IS NULL) last_visit_date`;
const restaurantColumns = `r.id,r.name,r.category,r.cuisine,substr(r.address,1,120) address_excerpt,r.rating_tenths,r.version,r.updated_at,${statsSQL}`;
export function restaurantSummary(r: Row) { return { id: r.id, name: r.name, category: r.category, cuisine: r.cuisine, address_excerpt: r.address_excerpt, rating: r.rating_tenths === null ? null : Number(r.rating_tenths) / 10, version: r.version, updated_at: iso(r.updated_at), dish_count: r.dish_count, visit_count: r.visit_count, last_visit_date: r.last_visit_date }; }
const visitSummary = (r: Row) => ({ id: r.id, restaurant_id: r.restaurant_id, restaurant_name: r.restaurant_name, dining_date: r.dining_date, meal: r.meal, note_excerpt: r.note_excerpt, version: r.version, updated_at: iso(r.updated_at) });
function matches(tokens: string[], params: Value[]) {
  if (!tokens.length) return `NULL matched_fields_json`;
  params.push(...tokens);
  return `(SELECT json_group_array(DISTINCT f.key) FROM json_each(json_extract(s.content,'$.parts')) p,json_each(p.value) f
    WHERE json_extract(p.value,'$.active')=1 AND f.key IN ('restaurant','dish','review','note') AND (${tokens.map(() => 'instr(f.value,?)>0').join(' OR ')})) matched_fields_json`;
}
export async function list(c: C, kind: Kind | 'revision', scopedId?: string, providedQuery?: Query, asSearch = false) {
  const db = c.env.DB, user = c.get('userId');
  const q: Query = providedQuery ?? validateQuery(c, kind === 'restaurant' ? restaurantQuery : kind === 'dish' ? dishQuery : kind === 'visit' ? visitQuery : revisionQuery);
  if (scopedId) { if (kind !== 'revision') await parent(db, user, scopedId); q.restaurant_id = scopedId; }
  if ((kind === 'visit' || kind === 'revision') && q.restaurant_id && !scopedId) await owned(db, user, 'restaurant', String(q.restaurant_id), kind === 'revision');
  if (kind === 'revision' && q.entity_id) await owned(db, user, q.entity_type as Kind, String(q.entity_id), true, false);
  const tokens = words(q), sort: Cursor['sort'] = kind === 'visit' ? 'date_desc' : kind === 'revision' ? 'created_desc' : q.sort as Cursor['sort'];
  const hash = await filterHash(c, q, `${kind}:${scopedId ?? ''}`), cursor = await decode(c, q.cursor as string | undefined, sort, hash);
  const params: Value[] = [];
  let columns: string, from: string, where: string, column: string, alias: string;
  if (kind === 'restaurant') {
    columns = `${restaurantColumns},${matches(tokens, params)}`; from = 'FROM restaurants r LEFT JOIN restaurant_search s ON s.restaurant_id=r.id AND s.user_id=r.user_id'; alias = 'r';
    params.push(user); where = 'r.user_id=?' + deletion('r', q) + ratingWhere('r', q, params);
    for (const [field, col] of [['category', 'category'], ['cuisine', 'cuisine_normalized']] as const) if (q[field] !== undefined) { where += ` AND r.${col}=?`; params.push(String(q[field])); }
    if (q.visited !== undefined || q.visited_from || q.visited_to) {
      let visit = 'SELECT 1 FROM visits v WHERE v.user_id=r.user_id AND v.restaurant_id=r.id AND v.deleted_at IS NULL';
      if (q.visited_from) { visit += ' AND v.dining_date>=?'; params.push(String(q.visited_from)); } if (q.visited_to) { visit += ' AND v.dining_date<=?'; params.push(String(q.visited_to)); }
      where += ` AND ${q.visited === false ? 'NOT ' : ''}EXISTS (${visit})`;
    }
    where += literal("json_extract(s.content,'$.text')", tokens, params); column = sort === 'rating_desc' ? 'r.rating_tenths' : 'r.updated_at';
  } else if (kind === 'dish') {
    columns = 'd.id,d.restaurant_id,r.name restaurant_name,d.name,d.rating_tenths,substr(d.review,1,120) review_excerpt,d.version,d.updated_at';
    from = 'FROM dishes d JOIN restaurants r ON r.id=d.restaurant_id AND r.user_id=d.user_id JOIN restaurant_search s ON s.restaurant_id=r.id AND s.user_id=r.user_id'; alias = 'd';
    params.push(user, String(q.restaurant_id)); where = 'd.user_id=? AND d.restaurant_id=? AND r.deleted_at IS NULL' + deletion('d', q) + ratingWhere('d', q, params);
    where += literal(`(coalesce(json_extract(s.content,'$.parts."dish:' || d.id || '".dish'),'') || ' ' || coalesce(json_extract(s.content,'$.parts."dish:' || d.id || '".review'),''))`, tokens, params); column = sort === 'rating_desc' ? 'd.rating_tenths' : 'd.updated_at';
  } else if (kind === 'visit') {
    columns = 'v.id,v.restaurant_id,r.name restaurant_name,v.dining_date,v.meal,substr(v.note,1,120) note_excerpt,v.version,v.updated_at';
    from = 'FROM visits v JOIN restaurants r ON r.id=v.restaurant_id AND r.user_id=v.user_id LEFT JOIN restaurant_search s ON s.restaurant_id=r.id AND s.user_id=r.user_id'; alias = 'v'; params.push(user); where = 'v.user_id=? AND r.deleted_at IS NULL' + deletion('v', q);
    if (q.restaurant_id) { where += ' AND v.restaurant_id=?'; params.push(String(q.restaurant_id)); }
    for (const [field, col, op] of [['from', 'dining_date', '>='], ['to', 'dining_date', '<='], ['meal', 'meal', '=']] as const) if (q[field]) { where += ` AND v.${col}${op}?`; params.push(String(q[field])); }
    where += literal(`(v.search_text || ' ' || coalesce(json_extract(s.content,'$.parts."restaurant:' || r.id || '".restaurant'),''))`, tokens, params); column = 'v.dining_date';
  } else {
    columns = historyColumns; from = historyJoin; alias = 'h'; params.push(user); where = 'h.user_id=?' + deletion('h', q);
    if (asSearch) where += " AND r.deleted_at IS NULL AND (h.entity_type='restaurant' OR (h.entity_type='dish' AND d.deleted_at IS NULL) OR (h.entity_type='visit' AND v.deleted_at IS NULL))";
    for (const field of ['restaurant_id', 'entity_type', 'entity_id', 'action']) if (q[field]) { where += ` AND h.${field}=?`; params.push(String(q[field])); }
    if (q.field) { where += " AND EXISTS (SELECT 1 FROM json_each(h.diff_json) WHERE json_extract(value,'$.field')=?)"; params.push(String(q.field)); }
    for (const [field, op] of [['from', '>='], ['to', '<']] as const) if (q[field]) { where += ` AND h.created_at${op}?`; params.push(Date.parse(String(q[field]))); }
    where += literal("(h.search_text || ' ' || h.note_search)", tokens, params); column = 'h.created_at';
  }
  where += keyset(column, `${alias}.id`, cursor, params); params.push(Number(q.limit) + 1);
  const rows = (await db.prepare(`SELECT ${columns} ${from} WHERE ${where} ORDER BY ${column} DESC,${alias}.id DESC LIMIT ?`).bind(...params).all<Row>()).results;
  const hasMore = rows.length > Number(q.limit), selected = rows.slice(0, Number(q.limit));
  const items = selected.map(r => kind === 'restaurant' ? { ...restaurantSummary(r), ...(tokens.length ? { matched_fields: JSON.parse(String(r.matched_fields_json ?? '[]')) as string[], match_label: null } : {}) } : kind === 'visit' ? visitSummary(r) : kind === 'revision' ? revisionSummary(r) : { id: r.id, restaurant_id: r.restaurant_id, restaurant_name: r.restaurant_name, name: r.name, rating: r.rating_tenths === null ? null : Number(r.rating_tenths) / 10, review_excerpt: r.review_excerpt, version: r.version, updated_at: iso(r.updated_at) });
  const last = selected.at(-1);
  const next = hasMore && last ? await encode(c, sort, last[column.split('.')[1]], last.id, hash) : null;
  if (asSearch) return items;
  return json(c, { data: { items }, meta: { request_id: crypto.randomUUID(), has_more: hasMore, next_cursor: next } });
}
export async function detail(c: C, kind: Kind, id: string) {
  const q = validateQuery(c, z.object({ deleted: z.enum(['exclude', 'include']).default('exclude') }).strict());
  const row = await owned(c.env.DB, c.get('userId'), kind, id, q.deleted === 'include'); const out = entity(kind, row);
  if (kind === 'restaurant') { const stats = await c.env.DB.prepare(`SELECT ${statsSQL} FROM restaurants r WHERE r.user_id=? AND r.id=?`).bind(c.get('userId'), id).first<Row>(); out.stats = { dish_count: stats!.dish_count, visit_count: stats!.visit_count, last_visit_date: stats!.last_visit_date }; }
  return json(c, envelope(out));
}
export async function search(c: C) {
  const q = validateQuery(c, z.object({ q: queryText.refine(v => !!v), groups: z.string().default('restaurants').refine(v => { const a = v.split(','); return a.length <= 3 && new Set(a).size === a.length && a.every(s => ['restaurants', 'visits', 'revisions'].includes(s)); }), limit: z.string().regex(/^\d+$/).transform(Number).pipe(z.number().int().min(1).max(20)).default('10') }).strict());
  const results: Record<string, unknown[]> = { restaurants: [], visits: [], revisions: [] };
  for (const group of q.groups.split(',')) {
    const kind = group === 'restaurants' ? 'restaurant' : group === 'visits' ? 'visit' : 'revision';
    const items = await list(c, kind, undefined, { q: q.q, limit: q.limit, deleted: 'exclude', sort: 'updated_desc' }, true) as Record<string, unknown>[];
    results[group] = group === 'restaurants' ? items.map(({ matched_fields, match_label, ...restaurant }) => ({ restaurant, matched_fields, match_label })) : items;
  } return json(c, envelope(results));
}
