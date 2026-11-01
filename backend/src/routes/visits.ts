import { Hono } from 'hono';
import type { AppEnv } from '../env';
import { notImplemented } from './placeholder';

export const visits = new Hono<AppEnv>();
visits.post('/restaurants/:restaurant_id/visits', notImplemented);
visits.get('/restaurants/:restaurant_id/visits', notImplemented);
visits.get('/visits', notImplemented);
visits.get('/visits/:visit_id', notImplemented);
visits.patch('/visits/:visit_id', notImplemented);
visits.delete('/visits/:visit_id', notImplemented);
visits.post('/visits/:visit_id/restore', notImplemented);
