import { z } from 'zod';
import { uuid } from '../auth/schemas';
import { AuthError } from '../auth/config';
export const id = uuid.transform(v => v.toLowerCase());
export const normalizeText = (v: string) => v.normalize('NFC').replace(/\r\n/g, '\n').trim();
export const normalizeSearch = (v: string) => v.normalize('NFKC').toLowerCase().replace(/\s+/gu, ' ').trim();
function text(max: number, required = false) {
  return z.string().max(max * 4 + 1024).transform(normalizeText).pipe(z.string().refine(v => !v.includes('\0') && Array.from(v).length <= max && (!required || v.length > 0), `Use ${required ? '1' : '0'}–${max} characters.`));
}
const optionalText = (max: number) => text(max).transform(v => v || null).nullable().optional();
export const rating = z.number().min(1).max(5).refine(v => Math.abs(v * 10 - Math.round(v * 10)) < 1e-9, 'Use at most one decimal place.');
export const date = z.string().regex(/^\d{4}-\d{2}-\d{2}$/).refine(v => v >= '1900-01-01' && v <= '2100-12-31' && Number.isFinite(Date.parse(v)) && new Date(v).toISOString().slice(0, 10) === v, 'Use a valid date from 1900 to 2100.');
export const instant = z.string().datetime({ precision: 3 });
export const version = z.number().int().min(1).max(Number.MAX_SAFE_INTEGER);
export const category = z.enum(['restaurant', 'cafe', 'bakery', 'bar', 'street_food', 'other']);
export const meal = z.enum(['breakfast', 'lunch', 'dinner', 'other']);
const restaurantFields = { name: text(120, true), category, cuisine: optionalText(60), address: text(500, true), rating: rating.nullable().optional(), review: optionalText(4000), note: optionalText(4000) };
const dishFields = { name: text(120, true), rating: rating.nullable().optional(), review: optionalText(2000) };
const visitFields = { dining_date: date, meal: meal.nullable().optional(), note: optionalText(2000) };
export const visitCreate = z.object({ id, ...visitFields }).strict();
export const creates = { restaurant: z.object({ id, ...restaurantFields, first_visit: visitCreate.optional() }).strict(), dish: z.object({ id, ...dishFields }).strict(), visit: visitCreate };
const patch = <T extends z.ZodRawShape>(fields: T) => z.object(fields).partial().extend({ expected_version: version }).strict().refine(v => Object.keys(v).some(k => k !== 'expected_version'), 'Provide at least one editable field.');
export const patches = { restaurant: patch(restaurantFields), dish: patch(dishFields), visit: patch(visitFields) };
export const versionBody = z.object({ expected_version: version }).strict();
export const metadataBody = z.object({ expected_metadata_version: version }).strict();
export const metadataPatch = metadataBody.extend({ note: optionalText(500) }).strict().refine(v => 'note' in v, 'Provide note.');
export const applyBody = z.object({ source_revision_id: id, side: z.enum(['before', 'after']), fields: z.array(z.string()).min(1).max(7).refine(v => new Set(v).size === v.length, 'Fields must be unique.'), expected_version: version }).strict();
export function parse<T extends z.ZodTypeAny>(schema: T, value: unknown): z.infer<T> {
  const parsed = schema.safeParse(value); if (parsed.success) return parsed.data;
  const issues = parsed.error.issues;
  const unknown = issues.some(i => i.code === 'unrecognized_keys');
  const missing = issues.some(i => ['expected_version', 'expected_metadata_version'].includes(String(i.path[0])) && i.code === 'invalid_type' && i.received === 'undefined');
  const fieldErrors: Record<string, string[]> = {};
  for (const i of issues) { const field = i.path.join('.') || '_request'; (fieldErrors[field] ??= []).push(i.message); }
  throw new AuthError(400, unknown ? 'UNKNOWN_FIELD' : missing ? 'MISSING_VERSION' : 'VALIDATION_ERROR', 'Invalid request fields.', undefined, undefined, fieldErrors);
}
