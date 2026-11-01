// GitHub Actions only: reject dangling refs and untyped v1 endpoint contracts.
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import console from 'node:console';
import yaml from 'js-yaml';
const doc = yaml.load(readFileSync('../contracts/openapi.yaml', 'utf8'));
const schemas = doc.components.schemas;
function walk(value) {
  if (!value || typeof value !== 'object') return;
  if ('$ref' in value) {
    const parts = value.$ref.split('/').slice(1);
    assert.ok(parts.reduce((current, key) => current?.[key], doc), `Dangling ${value.$ref}`);
  }
  for (const [key, entry] of Object.entries(value)) {
    if (key === 'schema' || key === 'items' || key === 'before' || key === 'after' || key === 'data') assert.notDeepEqual(entry, {}, `Untyped ${key}`);
    walk(entry);
  }
}
walk(doc);
const actual = [];
let endpoints = 0;
for (const [path, methods] of Object.entries(doc.paths)) for (const [method, op] of Object.entries(methods)) {
  if (!['get', 'post', 'patch', 'delete'].includes(method)) continue;
  endpoints++;
  actual.push(`${method.toUpperCase()} ${path}`);
  const domain = /^\/(restaurants|dishes|visits|revisions|search)(\/|$)/.test(path);
  if (!domain) continue;
  assert.ok(op.responses['400'] && op.responses['401'] && op.responses['404'] && op.responses['500']);
  for (const [status, response] of Object.entries(op.responses)) if (status.startsWith('2')) assert.ok(response.content['application/json'].schema);
  if (method !== 'get') assert.ok(op.parameters.some(p => p.$ref?.endsWith('/IdempotencyKey')));
  if (method === 'get' && ['/restaurants', '/visits', '/revisions'].includes(path)) assert.ok(op.parameters.some(p => p.name === 'cursor'));
}
assert.equal(doc.info.version, '1.0.0');
const expected = [
  'POST /auth/register', 'POST /auth/login', 'POST /auth/refresh', 'POST /auth/logout',
  'POST /auth/logout-all', 'POST /auth/password', 'POST /auth/recover', 'POST /auth/recovery-codes',
  'GET /auth/sessions', 'DELETE /auth/sessions/{session_id}', 'GET /auth/session',
  'GET /me', 'PATCH /me', 'DELETE /me', 'GET /health', 'GET /app/version',
  'POST /restaurants', 'GET /restaurants', 'GET /restaurants/{restaurant_id}',
  'PATCH /restaurants/{restaurant_id}', 'DELETE /restaurants/{restaurant_id}', 'POST /restaurants/{restaurant_id}/restore',
  'POST /restaurants/{restaurant_id}/dishes', 'GET /restaurants/{restaurant_id}/dishes',
  'GET /dishes/{dish_id}', 'PATCH /dishes/{dish_id}', 'DELETE /dishes/{dish_id}', 'POST /dishes/{dish_id}/restore',
  'POST /restaurants/{restaurant_id}/visits', 'GET /restaurants/{restaurant_id}/visits', 'GET /visits',
  'GET /visits/{visit_id}', 'PATCH /visits/{visit_id}', 'DELETE /visits/{visit_id}', 'POST /visits/{visit_id}/restore',
  'POST /revisions', 'GET /revisions', 'GET /restaurants/{restaurant_id}/revisions',
  'GET /dishes/{dish_id}/revisions', 'GET /visits/{visit_id}/revisions', 'GET /revisions/{revision_id}',
  'PATCH /revisions/{revision_id}', 'DELETE /revisions/{revision_id}', 'POST /revisions/{revision_id}/restore', 'GET /search',
];
// Assert exact method/path coverage rather than an erroneous hand-count.
assert.deepEqual(actual.sort(), expected.sort());
assert.equal(schemas.RevisionApply.properties.fields.maxItems, 7);
assert.equal(schemas.RevisionApply.properties.fields.uniqueItems, true);
assert.equal(schemas.RestaurantPatch.additionalProperties, false);
assert.ok(!schemas.FieldDiff.properties.field.enum.includes('version'));
assert.deepEqual(Object.keys(schemas.User.properties).sort(), ['id', 'username', 'display_name', 'version', 'created_at', 'updated_at'].sort());
assert.equal(schemas.User.additionalProperties, false);
assert.equal(schemas.UserPatch.additionalProperties, false);
assert.ok(doc.paths['/me'].patch.parameters.some(p => p.$ref.endsWith('/IdempotencyKey')));
assert.ok(!doc.paths['/me'].delete.parameters?.some(p => p.$ref.endsWith('/IdempotencyKey')));
assert.equal(schemas.RecoveryCodeResult.properties.recovery_codes.minItems, 8);
assert.equal(schemas.RecoveryCodeResult.properties.recovery_codes.maxItems, 8);
assert.equal(schemas.RecoveryCodeResult.properties.recovery_codes.uniqueItems, true);
assert.ok(!schemas.RestaurantCreate.required.includes('address'));
for (const name of ['Restaurant', 'RestaurantCreate', 'RestaurantPatch', 'RestaurantDetail']) {
  assert.deepEqual(schemas[name].properties.address.type, ['string', 'null']);
}
assert.deepEqual(doc.paths['/app/version'].get.security, []);
assert.equal(doc.paths['/app/version'].get.x_auth, undefined);
assert.equal(doc.paths['/app/version'].get['x-auth'], 'Public');
assert.equal(readFileSync('../contracts/openapi.yaml', 'utf8').match(/TODO|notImplemented/g)?.length ?? 0, 0);
console.log(`Verified ${endpoints} v1 endpoint contracts and all references`);
