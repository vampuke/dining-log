import { Hono } from 'hono';
import type { AppEnv } from '../env';
import { notImplemented } from './placeholder';

export const dishes = new Hono<AppEnv>();
dishes.post('/restaurants/:restaurant_id/dishes', notImplemented);
dishes.get('/restaurants/:restaurant_id/dishes', notImplemented);
dishes.get('/dishes/:dish_id', notImplemented);
dishes.patch('/dishes/:dish_id', notImplemented);
dishes.delete('/dishes/:dish_id', notImplemented);
dishes.post('/dishes/:dish_id/restore', notImplemented);
