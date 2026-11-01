import { Hono } from 'hono';
import type { AppEnv } from '../env';
import { notImplemented } from './placeholder';

export const restaurants = new Hono<AppEnv>();
restaurants.post('/restaurants', notImplemented);
restaurants.get('/restaurants', notImplemented);
restaurants.get('/restaurants/:restaurant_id', notImplemented);
restaurants.patch('/restaurants/:restaurant_id', notImplemented);
restaurants.delete('/restaurants/:restaurant_id', notImplemented);
restaurants.post('/restaurants/:restaurant_id/restore', notImplemented);
