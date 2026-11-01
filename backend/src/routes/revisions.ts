import { Hono } from 'hono';
import type { AppEnv } from '../env';
import { notImplemented } from './placeholder';

export const revisions = new Hono<AppEnv>();
revisions.post('/revisions', notImplemented);
revisions.get('/revisions', notImplemented);
revisions.get('/restaurants/:restaurant_id/revisions', notImplemented);
revisions.get('/dishes/:dish_id/revisions', notImplemented);
revisions.get('/visits/:visit_id/revisions', notImplemented);
revisions.get('/revisions/:revision_id', notImplemented);
revisions.patch('/revisions/:revision_id', notImplemented);
revisions.delete('/revisions/:revision_id', notImplemented);
revisions.post('/revisions/:revision_id/restore', notImplemented);
