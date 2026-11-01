import { z } from 'zod';
import { json, envelope } from '../auth/routes';
import { router, input, idempotency, pathId, withIdempotency } from '../domain/http';
import { applyBody, metadataBody, metadataPatch } from '../domain/schemas';
import { apply } from '../domain/mutations';
import { mutateMetadata } from '../domain/revision-metadata';
import { list, validateQuery, revisionQuery } from '../domain/queries';
import { owned } from '../repositories/domain';
import { historyOwned, revisionDetail } from '../repositories/history';
export const revisions = router();
revisions.post('/revisions', async c => {
  const p = await input(c, applyBody), idem = await idempotency(c, p);
  return withIdempotency(c, idem, async () => {
    const source = await historyOwned(c.env.DB, c.get('userId'), p.source_revision_id, true);
    return apply(c, source, p.fields, p.side, p.expected_version, idem);
  });
});
revisions.get('/revisions', c => list(c, 'revision') as Promise<Response>);
revisions.get('/restaurants/:restaurant_id/revisions', async c => { const id = pathId(c, 'restaurant_id'); await owned(c.env.DB, c.get('userId'), 'restaurant', id, true); const q = validateQuery(c, revisionQuery.omit({ restaurant_id: true })); return list(c, 'revision', id, q) as Promise<Response>; });
for (const [path, kind, param] of [['/dishes/:dish_id/revisions', 'dish', 'dish_id'], ['/visits/:visit_id/revisions', 'visit', 'visit_id']] as const) revisions.get(path, async c => {
  const id = pathId(c, param), row = await owned(c.env.DB, c.get('userId'), kind, id, true, false);
  const q = validateQuery(c, revisionQuery.omit({ restaurant_id: true, entity_type: true, entity_id: true }));
  return list(c, 'revision', undefined, { ...q, restaurant_id: row.restaurant_id, entity_type: kind, entity_id: id }) as Promise<Response>;
});
revisions.get('/revisions/:revision_id', async c => { const id = pathId(c, 'revision_id'), q = validateQuery(c, z.object({ deleted: z.enum(['exclude', 'include']).default('exclude') }).strict()); return json(c, envelope(revisionDetail(await historyOwned(c.env.DB, c.get('userId'), id, q.deleted === 'include')))); });
revisions.patch('/revisions/:revision_id', async c => { const id = pathId(c, 'revision_id'), p = await input(c, metadataPatch); return mutateMetadata(c, id, p, 'update', await idempotency(c, p)); });
revisions.delete('/revisions/:revision_id', async c => { const id = pathId(c, 'revision_id'), p = await input(c, metadataBody); return mutateMetadata(c, id, p, 'delete', await idempotency(c, p)); });
revisions.post('/revisions/:revision_id/restore', async c => { const id = pathId(c, 'revision_id'), p = await input(c, metadataBody); return mutateMetadata(c, id, p, 'restore', await idempotency(c, p)); });
