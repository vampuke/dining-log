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
let endpoints = 0;
for (const [path, methods] of Object.entries(doc.paths)) for (const [method, op] of Object.entries(methods)) {
  if (!['get', 'post', 'patch', 'delete'].includes(method)) continue;
  endpoints++;
  const domain = /^\/(restaurants|dishes|visits|revisions|search)(\/|$)/.test(path);
  if (!domain) continue;
  assert.ok(op.responses['400'] && op.responses['401'] && op.responses['404'] && op.responses['500']);
  for (const [status, response] of Object.entries(op.responses)) if (status.startsWith('2')) assert.ok(response.content['application/json'].schema);
  if (method !== 'get') assert.ok(op.parameters.some(p => p.$ref?.endsWith('/IdempotencyKey')));
  if (method === 'get' && ['/restaurants', '/visits', '/revisions'].includes(path)) assert.ok(op.parameters.some(p => p.name === 'cursor'));
}
assert.equal(doc.info.version, '1.0.0');
assert.equal(endpoints, 45);
assert.equal(schemas.RevisionApply.properties.fields.maxItems, 7);
assert.equal(schemas.RevisionApply.properties.fields.uniqueItems, true);
assert.equal(schemas.RestaurantPatch.additionalProperties, false);
assert.ok(!schemas.FieldDiff.properties.field.enum.includes('version'));
console.log(`Verified ${endpoints} v1 endpoint contracts and all references`);
